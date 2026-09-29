package com.example.qesset

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import java.util.Base64

class Qesset : MainAPI() {
    override var mainUrl = "https://qesset.com"
    override var name = "قصة عشق (Qesset)"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private val homeSections = listOf(
        "آخر الحلقات" to "/son-bolumler/",
        "مسلسلات" to "/discover/",
        "أفلام" to "/movies/",
        "أفلام جديدة" to "/category/yeni-filmler/",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val lists = coroutineScope {
            homeSections.map { (title, path) ->
                async {
                    try {
                        val items = parseCards(app.get(mainUrl + path).document)
                        if (items.isNotEmpty()) HomePageList(title, items, true) else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().encodeURL()
        if (q.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/?s=$q").document
        return parseCards(doc).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        var pageUrl = url
        var doc = app.get(url).document

        if (url.contains("/clarus/")) {
            val parent = doc.selectFirst("a[href*=/yeni-show/]")?.attr("href")?.ifBlank { null }
            if (parent != null) {
                pageUrl = parent
                doc = app.get(parent).document
            }
        }

        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.replace(Regex("""\s*-\s*قصة عشق\s*$"""), "")
            ?: throw ErrorLoadingException("No title found")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
        val year = Regex("\"datePublished\"\\s*:\\s*\"(\\d{4})\"").find(doc.toString())
            ?.groupValues?.get(1)?.toIntOrNull()

        if (pageUrl.contains("/movies/")) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        val episodes = collectEpisodes(doc)
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    private suspend fun collectEpisodes(doc: Document): List<Episode> {
        val entries = linkedMapOf<String, Triple<Int, Int, String>>()

        fun addFrom(d: Document) {
            d.select("a[href*=/clarus/]").forEach { a ->
                val href = a.attr("href")
                if (href.isBlank() || entries.containsKey(href)) return@forEach
                val epNum = Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach
                var epTitle = a.attr("title").ifBlank { a.selectFirst(".title")?.text() ?: "" }
                epTitle = epTitle.replace(Regex("""\s*-\s*قصة عشق\s*$"""), "").trim()
                if (epTitle.isBlank()) epTitle = "الحلقة $epNum"
                val season = Regex("""الموسم\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                entries[href] = Triple(season, epNum, epTitle)
            }
        }

        addFrom(doc)
        var next = doc.selectFirst("link[rel=next]")?.attr("href")?.ifBlank { null }
        var guard = 0
        while (!next.isNullOrBlank() && guard < 10) {
            val nextDoc = try {
                app.get(next).document
            } catch (_: Exception) {
                break
            }
            addFrom(nextDoc)
            next = nextDoc.selectFirst("link[rel=next]")?.attr("href")?.ifBlank { null }
            guard++
        }

        return entries.entries
            .sortedWith(compareBy({ it.value.first }, { it.value.second }))
            .map { (epUrl, data) ->
                newEpisode(epUrl) {
                    this.season = data.first
                    this.episode = data.second
                    this.name = data.third
                }
            }
    }

    private fun parseCards(doc: Document): List<SearchResponse> =
        doc.select("article").mapNotNull { item ->
            val a = item.select("a[href][title]").firstOrNull { link ->
                val h = link.attr("href")
                h.contains("/movies/") || h.contains("/yeni-show/") ||
                    h.contains("/clarus/") || h.contains("/tvshow/")
            } ?: return@mapNotNull null
            val href = a.attr("href")

            var title = a.attr("title").ifBlank { a.selectFirst(".title")?.text() ?: "" }
            title = title.replace(Regex("""\s*-\s*قصة عشق\s*$"""), "").trim()
            if (title.isBlank()) return@mapNotNull null

            val poster = a.select("[style*=background-image]").firstOrNull()?.attr("style")
                ?.let { Regex("""url\((['"]?)([^'")]+)""").find(it)?.groupValues?.get(2) }

            if (href.contains("/movies/")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data).document
        } catch (_: Exception) {
            return false
        }

        val watchHref = doc.select("a[href*=qesen]").firstOrNull()?.attr("href")?.ifBlank { null }
            ?: return false
        val b64 = Regex("""post=([A-Za-z0-9+/=_-]+)""").find(watchHref)?.groupValues?.get(1)
            ?: return false
        val decoded = try {
            String(Base64.getDecoder().decode(b64))
        } catch (_: Exception) {
            try {
                String(Base64.getUrlDecoder().decode(b64))
            } catch (_: Exception) {
                return false
            }
        }
        val json = decoded.replace("\\/", "/")
        val serverRx = Regex("""\{"name":"([^"]+)","id":"([^"]+)"\}""")
        val servers = serverRx.findAll(json).toList()
        if (servers.isEmpty()) return false

        var found = false
        val seen = mutableSetOf<String>()
        val embedReferer = "https://qesen.net/"
        for (m in servers) {
            val embed = buildEmbed(m.groupValues[1], m.groupValues[2]) ?: continue
            if (!seen.add(embed)) continue
            try {
                val ok = resolveEmbed(embed, embedReferer, subtitleCallback, callback, seen, m.groupValues[1])
                found = ok || found
            } catch (_: Exception) {
            }
        }
        return found
    }

    private fun buildEmbed(serverName: String, id: String): String? = when (serverName) {
        "Arab HD" -> "https://arabhd.onl/embed-$id.html"
        "estream" -> "https://arabveturk.com/embed-$id.html"
        "dailymotion" -> "https://www.dailymotion.com/video/$id"
        "ok" -> "https://ok.ru/videoembed/$id"
        "Red HD" -> "https://iplayerhls.com/e/$id"
        "Pro HD" -> "https://w.larhu.website/play.php?id=$id"
        "pro" -> "https://mdna.upns.online/#$id"
        "box" -> "https://youdboox.com/embed-$id.html"
        "now" -> "https://extreamnow.org/embed-$id.html"
        "facebook" -> "https://app.videas.fr/embed/media/$id"
        "youtube" -> "https://www.youtube.com/watch?v=$id"
        "youtube_in" -> "https://www.youtube.com/embed/$id"
        "express" -> id
        else -> null
    }

    private fun extractDirectUrls(raw: String): List<Pair<String, Boolean>> {
        val norm = raw.replace("\\/", "/").replace("&amp;", "&")
        val rx = Regex(
            """https?://[^\s"'<>\\]+?\.(m3u8|mp4)(?:[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        return rx.findAll(norm)
            .map { m -> m.value.trimEnd('.', ',', ';', ')', ']') to m.groupValues[1].equals("m3u8", true) }
            .distinctBy { it.first }
            .toList()
    }

    private fun embedOrigin(url: String): String {
        val m = Regex("""^(https?://[^/]+)""").find(url)
        return if (m != null) "${m.groupValues[1]}/" else url
    }

    private suspend fun emitLink(
        url: String,
        isM3u8: Boolean,
        headerRef: String?,
        label: String,
        sourceName: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!seen.add(url)) return
        val link = newExtractorLink(
            source = sourceName,
            name = label,
            url = url,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        )
        if (headerRef != null) {
            link.headers = mapOf("Referer" to headerRef)
            link.referer = headerRef
        }
        callback(link)
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
        sourceName: String = name
    ): Boolean {
        if (embedUrl.contains(".m3u8")) {
            emitLink(embedUrl, true, null, sourceName, sourceName, seen, callback)
            return true
        }
        if (embedUrl.contains(".mp4")) {
            emitLink(embedUrl, false, null, sourceName, sourceName, seen, callback)
            return true
        }

        val candidates = LinkedHashMap<String, Boolean>()
        try {
            val page = app.get(embedUrl, headers = mapOf("Referer" to referer)).text
            extractDirectUrls(page).forEach { if (!candidates.containsKey(it.first)) candidates[it.first] = it.second }
            try {
                extractDirectUrls(getAndUnpack(page)).forEach { if (!candidates.containsKey(it.first)) candidates[it.first] = it.second }
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }

        if (candidates.isNotEmpty()) {
            val origin = embedOrigin(embedUrl)
            candidates.forEach { (url, isM3u8) ->
                emitLink(url, isM3u8, origin, sourceName, sourceName, seen, callback)
            }
            return true
        }

        return try {
            loadExtractor(embedUrl, referer, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    private fun String.encodeURL(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
