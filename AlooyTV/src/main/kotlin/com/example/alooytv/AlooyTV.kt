package com.example.alooytv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

class AlooyTV : MainAPI() {
    override var mainUrl = "https://ec.alooytv16.xyz"
    override var name = "alooyTV"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val SUB_SOURCE = "https://www.subtitlecat.com"

        const val MAX_ROW_ITEMS = 24
        const val MAX_SEARCH_ITEMS = 50
        const val NET_TIMEOUT_MS = 15_000L
        const val SERVER_TIMEOUT_MS = 25_000L

        // all site lists: label to path
        val ROWS = listOf(
            "الأحدث" to "/tv-series.html",
            "خليجي" to "/genre/kleeji.html",
            "عربي" to "/genre/arabic.html",
            "تركي" to "/genre/turki.html",
            "رمضان خليجي 2023" to "/genre/ramadan-kleeji.html",
            "رمضان عربي 2023" to "/genre/ramadan-arabi.html",
            "رمضان خليجي 2024" to "/genre/ramadan-kleeji-2024.html",
            "رمضان عربي 2024" to "/genre/ramadan-arabi-2024.html",
            "فارسي" to "/genre/farisi.html",
            "رمضان عربي 2025" to "/genre/ramadan-arabi-2025.html",
            "رمضان خليجي 2025" to "/genre/ramadan-kleeji-2025.html",
            "انمي" to "/genre/anmi.html",
            "افلام اجنبية" to "/genre/foreign-movies.html",
            "افلام كورية" to "/genre/Korean-movies.html",
            "مسلسلات اجنبية" to "/genre/Foreign-series.html",
            "مسلسلات كورية" to "/genre/Korean-series.html",
            "مسلسلات اسيوية" to "/genre/asia-series.html",
            "رمضان خليجي 2026" to "/genre/ramadan-kleeji-2026.html",
            "رمضان عربي 2026" to "/genre/ramadan-arabi-2026.html",
            "افلام عربية" to "/genre/arabic-movies.html",
            "مسرحيات" to "/genre/masrahiyat.html",
        )

        const val CARD_SPLIT = "latest-movie-img-container"
        val LINK_RX = Regex("""href="((?:https://ec\.alooytv16\.xyz)?/watch/[a-z0-9\-]+\.html)"""")
        val POSTER_RX = Regex("""data-src="([^"]+)"""")
        val TITLE_RX = Regex("""<div class="movie-title">\s*<h3>\s*<a href="[^"]+">([^<>]+)</a>""")
        val COUNT_RX = Regex("""(\d+)\s*[^<>]{0,20}الحلقات""")
        // episode buttons: /watch/<slug>.html?key=<token> with Ep#N text
        val EP_RX = Regex("""href="([^"]+\?key=[^"]+)"[^>]*>([^<>]{1,20})<""")
        val EPNUM_RX = Regex("""Ep#(\d+)""")
        // direct mp4 in <source>; placeholder https://vid2.0 has no letter TLD
        val SRC_RX = Regex("""<source[^>]+src="(https?://[^"]+)"""")
        // protocol-relative sources: src="//host/path.mp4"
        val PROTO_RX = Regex("""<source[^>]+src="(//[^"]+)"""")
        // download button carries the same mp4 base64-encoded
        val DL_RX = Regex("""download_video\.php\?video_url=([A-Za-z0-9+/=]+)""")
        val HOST_RX = Regex("""^https?://[^/]*\.[A-Za-z]{2,}""")
        val TITLE_TAG_RX = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
        val OGIMG_RX = Regex("""<meta property="og:image" content="([^"]+)"""")
        val ORIGIN_RX = Regex("""^(https?://[^/]+)""")
    }

    // ---------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val lists = coroutineScope {
            ROWS.map { (label, path) ->
                async {
                    val items = fetchCards("$mainUrl$path")
                    if (items.isEmpty()) null else HomePageList(label, items.take(MAX_ROW_ITEMS), true)
                }
            }.awaitAll().filterNotNull()
        }
        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val url = "$mainUrl/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
        return try {
            val html = withTimeoutOrNull(NET_TIMEOUT_MS) {
                app.get(url, headers = pageHeaders()).text
            } ?: return emptyList()
            parseCards(html).take(MAX_SEARCH_ITEMS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchCards(listUrl: String): List<SearchResponse> {
        return try {
            val html = withTimeoutOrNull(NET_TIMEOUT_MS) {
                app.get(listUrl, headers = pageHeaders()).text
            } ?: return emptyList()
            parseCards(html)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (chunk in html.split(CARD_SPLIT).drop(1)) {
            val window = if (chunk.length > 2500) chunk.substring(0, 2500) else chunk
            val link = LINK_RX.find(window)?.groupValues?.get(1) ?: continue
            val page = absUrl(link)
            if (!seen.add(page)) continue
            val poster = POSTER_RX.find(window)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }
            val title = TITLE_RX.find(window)?.groupValues?.get(1)?.trim()
                ?.ifBlank { null } ?: continue
            val epCount = COUNT_RX.find(window)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val isTv = epCount > 1
            val data = dataJson(
                mapOf(
                    "u" to page,
                    "t" to title,
                    "ec" to epCount,
                )
            )
            if (isTv) {
                out += newTvSeriesSearchResponse(title, data, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                out += newMovieSearchResponse(title, data, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- details

    override suspend fun load(url: String): LoadResponse? {
        val info = try {
            JSONObject(url)
        } catch (_: Exception) {
            null
        }
        val pageUrl = info?.optString("u")?.ifBlank { null } ?: url
        if (!pageUrl.startsWith("http")) return null
        val hintSeries = info?.optInt("ec", 0)?.let { it > 1 } ?: false

        val html = try {
            withTimeoutOrNull(NET_TIMEOUT_MS) {
                app.get(pageUrl, headers = pageHeaders()).text
            }
        } catch (_: Exception) {
            null
        } ?: throw ErrorLoadingException("alooyTV: no details")

        val title = TITLE_TAG_RX.find(html)?.groupValues?.get(1)
            ?.replace(Regex("""\s+"""), " ")?.trim()?.ifBlank { null }
            ?: info?.optString("t")?.ifBlank { null }
            ?: throw ErrorLoadingException("alooyTV: no title")
        val poster = OGIMG_RX.find(html)?.groupValues?.get(1)?.ifBlank { null }

        val episodes = mutableListOf<Pair<Int, String>>()
        val seenEp = mutableSetOf<String>()
        for (m in EP_RX.findAll(html)) {
            val epUrl = absUrl(m.groupValues[1])
            if (!seenEp.add(epUrl)) continue
            val number = EPNUM_RX.find(m.groupValues[2])?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            episodes += number to epUrl
        }

        if (hintSeries || episodes.size > 1) {
            val list = episodes.distinctBy { it.first }.sortedBy { it.first }.map { (number, epUrl) ->
                val data = dataJson(
                    mapOf(
                        "u" to epUrl,
                        "t" to title,
                        "e" to number,
                    )
                )
                newEpisode(data) {
                    this.episode = number
                }
            }
            if (list.isEmpty()) throw ErrorLoadingException("alooyTV: no episodes")
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, list) {
                this.posterUrl = poster
            }
        }

        val data = dataJson(
            mapOf(
                "u" to pageUrl,
                "t" to title,
            )
        )
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, data) {
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = try {
            JSONObject(data)
        } catch (_: Exception) {
            null
        } ?: return false
        val pageUrl = info.optString("u").ifBlank { null } ?: return false
        val title = info.optString("t").ifBlank { null }
        val episode = info.optInt("e", 0).takeIf { it > 0 }

        val html = try {
            withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                app.get(pageUrl, headers = pageHeaders()).text
            }
        } catch (_: Exception) {
            null
        } ?: return false

        var found = false
        val seen = mutableSetOf<String>()
        val origin = ORIGIN_RX.find(pageUrl)?.groupValues?.get(1)
        suspend fun emit(streamUrl: String) {
            if (!HOST_RX.containsMatchIn(streamUrl)) return
            if (!seen.add(streamUrl)) return
            val isHls = streamUrl.contains(".m3u8", true)
            try {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = pageUrl
                        val headers = mutableMapOf(
                            "Referer" to pageUrl,
                            "User-Agent" to BROWSER_UA,
                        )
                        if (origin != null) headers["Origin"] = origin
                        this.headers = headers
                    }
                )
                found = true
            } catch (_: Exception) {
            }
        }
        for (m in SRC_RX.findAll(html)) {
            emit(m.groupValues[1])
        }
        if (!found) {
            // protocol-relative sources
            for (m in PROTO_RX.findAll(html)) {
                emit("https:" + m.groupValues[1])
            }
        }
        if (!found) {
            // download button carries the same mp4 base64-encoded
            for (m in DL_RX.findAll(html)) {
                val decoded = try {
                    String(
                        java.util.Base64.getMimeDecoder().decode(m.groupValues[1]),
                        Charsets.UTF_8,
                    ).trim()
                } catch (_: Exception) {
                    null
                }
                if (decoded != null && decoded.startsWith("http")) emit(decoded)
            }
        }

        val arabic = findArabicSubtitle(title, null, null, episode)
        if (arabic != null) {
            try {
                subtitleCallback(
                    SubtitleFile("Arabic", arabic).apply {
                        this.headers = mapOf("User-Agent" to BROWSER_UA)
                    }
                )
            } catch (_: Exception) {
            }
        }

        return found
    }

    // ---------------------------------------------------------------- arabic subs

    private suspend fun findArabicSubtitle(
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
    ): String? {
        if (title.isNullOrBlank()) return null

        val query = if (episode != null) {
            "$title الحلقة $episode"
        } else {
            title
        }

        val search = try {
            withTimeoutOrNull(NET_TIMEOUT_MS) {
                app.get(
                    "$SUB_SOURCE/index.php?search=" + URLEncoder.encode(query, "UTF-8"),
                    headers = pageHeaders(),
                ).text
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val pages = Regex("""href="(subs/[^"]+\.html)"""")
            .findAll(search)
            .map { it.groupValues[1] }
            .take(3)
            .toList()

        for (page in pages) {
            val html = try {
                withTimeoutOrNull(NET_TIMEOUT_MS) {
                    app.get("$SUB_SOURCE/$page", headers = pageHeaders()).text
                }
            } catch (_: Exception) {
                null
            } ?: continue
            val href = Regex("""id="download_ar"[^>]*href="([^"]+)"""")
                .find(html)?.groupValues?.get(1) ?: continue
            val absolute = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> SUB_SOURCE + href
                else -> "$SUB_SOURCE/$href"
            }
            return absolute.replace(" ", "%20")
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private fun pageHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to referer,
    )

    private fun absUrl(path: String): String = when {
        path.isBlank() -> path
        path.startsWith("http") -> path
        path.startsWith("/") -> mainUrl + path
        else -> "$mainUrl/$path"
    }

    private fun dataJson(values: Map<String, Any?>): String {
        val json = JSONObject()
        values.forEach { (key, value) -> if (value != null) json.put(key, value) }
        return json.toString()
    }
}
