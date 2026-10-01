package com.example.cimaleek

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

class Cimaleek : MainAPI() {
    override var mainUrl = "https://wwr433.b2cima.click"
    override var name = "CIMALEEK"
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
        const val MAX_SEASONS = 20
        const val NET_TIMEOUT_MS = 15_000L
        const val SERVER_TIMEOUT_MS = 25_000L

        // all site sections: label to path
        val ROWS = listOf(
            "الأفلام" to "/movies-list/",
            "المسلسلات" to "/series-list/",
            "المضاف حديثا" to "/recent-89541/",
            "الأكثر مشاهدة" to "/trending/",
            "افلام اجنبية" to "/category/aflam-online-1/",
            "افلام نتفليكس" to "/category/netflix-movies-1/",
            "افلام انمي" to "/category/anime-movies/",
            "افلام اسيوية" to "/category/asian-aflam/",
            "افلام كرتون" to "/category/cartoon-movies/",
            "افلام هندية" to "/category/indian-movies/",
            "مسلسلات نتفليكس" to "/category/netflix-series/",
            "مسلسلات اجنبية" to "/category/english-series-1/",
            "مسلسلات اسيوية" to "/category/asian-series/",
            "مسلسلات انمي" to "/category/anime-series/",
        )

        // card: poster block (with WP post id) followed by title/year block
        val CARD_RX = Regex(
            """film-poster" data-id="(\d+)".*?<a href="([^"]+)".*?(?:data-src|src)="([^"]+)".*?<div class="title">([^<>]+)</div>.*?<div class="desc">([^<>]*)</div>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val RATING_RX = Regex("""<div class="rating">([\d.]+)</div>""")
        // series -> season pages
        val SEASON_RX = Regex("href=\"((?:https://wwr433\\.b2cima\\.click)?/seasons/[^\"]+/\")")
        val SEASONNUM_RX = Regex("""season-(\d+)""")
        // season page -> episodes (single or double quoted hrefs)
        val EP_RX = Regex("""href=["'](https://wwr433\.b2cima\.click/episodes/[^"']+/|/episodes/[^"']+/)[\"']""")
        val SENUM_RX = Regex("""-(\d+)x(\d+)-""")
        // details page: WP post id used by the player API
        val PID_RX = Regex(""""post_id":(\d+)""")
        val OG_RX = Regex("""<meta property="og:([^"]+)" content="([^"]*)"""")
        val GENRE_RX = Regex("""/genre/([^/]+)/\?type=(movies|series)""")
        val QUALITY_RX = Regex("""<div class="quality">([^<>]+)</div>""")
        val YEAR_RX = Regex("""/release/(\d{4})/""")

        val IFRAME_RX = Regex("""<iframe[^>]+src="(https?://[^"]+)"""")
        val SRC_RX = Regex("""const SRC\s*=\s*"([^"]+)"""")
        val FILE_RX = Regex(""""(?:file|source)"\s*:\s*"(https?://[^"]+)"""")
        val ATOB_RX = Regex("""window\.atob\(['"]([A-Za-z0-9+/=]{40,})['"]\)""")
        val M3U8_RX = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:[^\s"'<>\\]*)?""")
        val ORIGIN_RX = Regex("""^(https?://[^/]+)""")
        // arabic title prefixes used by the site (فيلم X, مسلسل Y, ...)
        val PREFIX_RX = Regex("""^[\p{IsArabic}\s]+?(?=[A-Za-z0-9(\[])""")
        // arabic title suffixes (X مترجم - سيما ليك)
        val TRAIL_RX = Regex("""\s*-\s*[\p{IsArabic}\s]+$""")
        val SUBBED_RX = Regex("""\s*مترجم\s*""")
    }

    private fun cleanTitle(raw: String): String {
        var title = PREFIX_RX.replace(raw.trim(), "").trim()
        title = TRAIL_RX.replace(title, "").trim()
        title = SUBBED_RX.replace(title, " ").replace(Regex("""\s+"""), " ").trim()
        return title.ifBlank { raw.trim() }
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
        val url = "$mainUrl/?s=" + URLEncoder.encode(trimmed, "UTF-8")
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
        for (m in CARD_RX.findAll(html)) {
            val page = absUrl(m.groupValues[2])
            if (!seen.add(page)) continue
            val isTv = page.contains("/series/")
            if (!isTv && !page.contains("/movies/")) continue
            val poster = m.groupValues[3].takeIf { it.startsWith("http") }
            val rawTitle = m.groupValues[4].trim()
            val title = cleanTitle(rawTitle)
            val desc = m.groupValues[5].trim()
            val year = Regex("""(\d{4})""").find(desc)?.groupValues?.get(1)?.toIntOrNull()
            val score = RATING_RX.find(m.value)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.let { Score.from10(it) }
            val pid = m.groupValues[1]
            val data = dataJson(
                mapOf(
                    "u" to page,
                    "p" to pid,
                    "t" to title,
                    "y" to (year ?: 0),
                )
            )
            if (isTv) {
                out += newTvSeriesSearchResponse(title, data, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = score
                }
            } else {
                out += newMovieSearchResponse(title, data, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = score
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
        val isTv = pageUrl.contains("/series/")

        val html = try {
            withTimeoutOrNull(NET_TIMEOUT_MS) {
                app.get(pageUrl, headers = pageHeaders()).text
            }
        } catch (_: Exception) {
            null
        } ?: throw ErrorLoadingException("CIMALEEK: no details")

        val og = mutableMapOf<String, String>()
        for (m in OG_RX.findAll(html)) {
            og[m.groupValues[1]] = m.groupValues[2]
        }
        val rawTitle = og["title"]?.trim().orEmpty()
        val title = cleanTitle(rawTitle)
            .ifBlank { info?.optString("t").orEmpty() }
            .ifBlank { null } ?: throw ErrorLoadingException("CIMALEEK: no title")
        val poster = og["image"]?.ifBlank { null } ?: info?.optString("p")?.ifBlank { null }
        val plot = og["description"]?.ifBlank { null }
        val year = YEAR_RX.find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: info?.optInt("y", 0)?.takeIf { it > 0 }
        val tags = GENRE_RX.findAll(html).map { it.groupValues[1].replace("-", " ") }
            .toList().ifEmpty { null }
        val quality = QUALITY_RX.find(html)?.groupValues?.get(1)?.trim()
        val pid = PID_RX.find(html)?.groupValues?.get(1)
        val allTags = ((tags ?: emptyList()) + listOfNotNull(quality)).distinct().ifEmpty { null }

        if (!isTv) {
            val data = dataJson(
                mapOf(
                    "u" to pageUrl,
                    "p" to pid,
                    "t" to title,
                    "y" to (year ?: 0),
                )
            )
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = allTags
            }
        }

        val seasonUrls = SEASON_RX.findAll(html)
            .map { absUrl(it.groupValues[1]) }
            .distinct()
            .toList()
            .take(MAX_SEASONS)

        val episodes = coroutineScope {
            seasonUrls.map { seasonUrl ->
                async { fetchSeasonEpisodes(seasonUrl, title) }
            }.awaitAll().flatten()
                .sortedWith(compareBy({ it.season }, { it.episode }))
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("CIMALEEK: no episodes")

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = allTags
        }
    }

    private suspend fun fetchSeasonEpisodes(seasonUrl: String, showTitle: String): List<Episode> {
        val season = SEASONNUM_RX.find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val html = try {
            withTimeoutOrNull(NET_TIMEOUT_MS) {
                app.get(seasonUrl, headers = pageHeaders()).text
            }
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val out = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (m in EP_RX.findAll(html)) {
            val epUrl = absUrl(m.groupValues[1])
            if (!seen.add(epUrl)) continue
            val se = SENUM_RX.find(epUrl)
            val number = se?.groupValues?.get(2)?.toIntOrNull() ?: continue
            val epSeason = se.groupValues[1].toIntOrNull() ?: season
            val data = dataJson(
                mapOf(
                    "u" to epUrl,
                    "t" to showTitle,
                    "s" to epSeason,
                    "e" to number,
                )
            )
            out += newEpisode(data) {
                this.season = epSeason
                this.episode = number
            }
        }
        return out
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
        var pid = info.optString("p").ifBlank { null }
        val title = info.optString("t").ifBlank { null }
        val year = info.optInt("y", 0).takeIf { it > 0 }
        val season = info.optInt("s", 0).takeIf { it > 0 }
        val episode = info.optInt("e", 0).takeIf { it > 0 }

        val seen = mutableSetOf<String>()
        var found = false

        // episode/movie pages carry their own WP post id for the player API
        if (pid == null) {
            try {
                val pageHtml = withTimeoutOrNull(NET_TIMEOUT_MS) {
                    app.get(pageUrl, headers = pageHeaders()).text
                }
                if (pageHtml != null) {
                    pid = PID_RX.find(pageHtml)?.groupValues?.get(1)
                }
            } catch (_: Exception) {
            }
        }

        // 1) watch page (may be Cloudflare-protected): extract player sources
        val watchUrl = pageUrl.trimEnd('/') + "/watch/"
        val watchHtml = try {
            withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                app.get(watchUrl, headers = pageHeaders(pageUrl)).text
            }
        } catch (_: Exception) {
            null
        }
        if (watchHtml != null) {
            if (emitStreams(watchHtml, name, watchUrl, subtitleCallback, callback, seen)) found = true
            val nested = IFRAME_RX.find(watchHtml)?.groupValues?.get(1)
            if (nested != null && nested != watchUrl) {
                val level2 = try {
                    withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                        app.get(nested, headers = pageHeaders(watchUrl)).text
                    }
                } catch (_: Exception) {
                    null
                }
                if (level2 != null && emitStreams(level2, name, nested, subtitleCallback, callback, seen)) {
                    found = true
                }
            }
        }

        // 2) lala player API via WP post id (tokens are obfuscated; scan for plain urls)
        if (pid != null) {
            val apiHtml = try {
                withTimeoutOrNull(NET_TIMEOUT_MS) {
                    app.get(
                        "$mainUrl/wp-json/lalaplayer/v2?post_id=$pid",
                        headers = apiHeaders(),
                    ).text
                }
            } catch (_: Exception) {
                null
            }
            if (apiHtml != null && emitStreams(apiHtml, name, watchUrl, subtitleCallback, callback, seen)) {
                found = true
            }
        }

        // 3) extractor fallback on the watch page
        if (!found) {
            found = try {
                loadExtractor(watchUrl, pageUrl, subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
        }

        // 4) Arabic subtitles
        val arabic = findArabicSubtitle(title, year, season, episode)
        if (arabic != null) {
            subtitleCallback(
                SubtitleFile("Arabic", arabic).apply {
                    this.headers = mapOf("User-Agent" to BROWSER_UA)
                }
            )
        }

        return found
    }

    private suspend fun emitStreams(
        html: String,
        label: String,
        streamReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        val found = linkedSetOf<String>()
        SRC_RX.find(html)?.groupValues?.get(1)?.let { found += it }
        FILE_RX.findAll(html).forEach { found += it.groupValues[1] }
        ATOB_RX.findAll(html).forEach { m ->
            val decoded = try {
                String(Base64.getMimeDecoder().decode(m.groupValues[1]), Charsets.UTF_8).trim()
            } catch (_: Exception) {
                null
            }
            if (decoded != null && decoded.startsWith("http")) found += decoded
        }
        M3U8_RX.findAll(html).forEach { found += it.value }
        IFRAME_RX.findAll(html).forEach { found += it.groupValues[1] }
        if (found.isEmpty()) return false

        val origin = ORIGIN_RX.find(streamReferer)?.groupValues?.get(1)
        var emitted = false
        for (streamUrl in found) {
            if (!streamUrl.startsWith("http")) continue
            if (streamUrl.contains(".vtt", true) || streamUrl.contains(".srt", true)) continue
            val isNew = synchronized(seen) { seen.add("$label|$streamUrl") }
            if (!isNew) continue
            val isHls = streamUrl.contains(".m3u8", true)
            try {
                callback(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = streamUrl,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = streamReferer
                        val headers = mutableMapOf(
                            "Referer" to streamReferer,
                            "User-Agent" to BROWSER_UA,
                        )
                        if (origin != null) headers["Origin"] = origin
                        this.headers = headers
                    }
                )
                emitted = true
            } catch (_: Exception) {
            }
        }
        return emitted
    }

    // ---------------------------------------------------------------- arabic subs

    private suspend fun findArabicSubtitle(
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
    ): String? {
        if (title.isNullOrBlank()) return null

        val clean = cleanTitle(title)
        val query = if (season != null && episode != null) {
            "$clean S" + season.toString().padStart(2, '0') + "E" + episode.toString().padStart(2, '0')
        } else {
            listOfNotNull(clean, year?.toString()).joinToString(" ")
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

    private fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
    )

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
