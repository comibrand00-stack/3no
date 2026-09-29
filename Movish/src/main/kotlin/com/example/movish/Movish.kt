package com.example.movish

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Base64

class Movish : MainAPI() {
    override var mainUrl = "https://movish.to"
    override var name = "Movish"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = false

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val ROW_LIVE = "Live IPTV"
        const val ROW_SPORTS = "Sports"

        const val MAX_ITEMS = 48
        const val MAX_SEARCH_ITEMS = 50
        const val SERVER_TIMEOUT_MS = 25_000L

        // channel cards on /live-broadcasts (and ?category=sports)
        val CARD_RX = Regex(
            """<a[^>]+href="([^"]*live-broadcast/[^"]+)"[^>]*class="bc-card"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val CAT_RX = Regex("""bc-cat-chip">([^<>]+)<""")
        val LOGO_RX = Regex("""bc-logo" src="([^"]+)"""")
        val NAME_RX = Regex("""bc-cardtitle">([^<>]+)<""")

        val EMBED_RX = Regex("""iptv-embed/(\d+)""")
        val IFRAME_RX = Regex("""<iframe[^>]+src="(https?://[^"]+)"""")
        val SRC_RX = Regex("""const SRC\s*=\s*"([^"]+)"""")
        val FILE_RX = Regex(""""(?:file|source)"\s*:\s*"(https?://[^"]+)"""")
        val ATOB_RX = Regex("""window\.atob\(['"]([A-Za-z0-9+/=]{40,})['"]\)""")
        val M3U8_RX = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:[^\s"'<>\\]*)?""")
        val ORIGIN_RX = Regex("""^(https?://[^/]+)""")
        val H1_RX = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
    }

    private data class Channel(
        val pageUrl: String,
        val name: String,
        val logo: String?,
    )

    // ---------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val (live, sports) = coroutineScope {
            val liveJob = async { fetchCards("$mainUrl/live-broadcasts") }
            val sportsJob = async { fetchCards("$mainUrl/live-broadcasts?category=sports") }
            liveJob.await() to sportsJob.await()
        }

        val lists = mutableListOf<HomePageList>()
        if (live.isNotEmpty()) {
            lists += HomePageList(ROW_LIVE, live.take(MAX_ITEMS).map { it.toSearchResponse() }, true)
        }
        if (sports.isNotEmpty()) {
            lists += HomePageList(ROW_SPORTS, sports.take(MAX_ITEMS).map { it.toSearchResponse() }, true)
        }
        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val (live, sports) = coroutineScope {
            val liveJob = async { fetchCards("$mainUrl/live-broadcasts") }
            val sportsJob = async { fetchCards("$mainUrl/live-broadcasts?category=sports") }
            liveJob.await() to sportsJob.await()
        }
        return (live + sports)
            .distinctBy { it.pageUrl }
            .filter { it.name.contains(trimmed, true) }
            .take(MAX_SEARCH_ITEMS)
            .map { it.toSearchResponse() }
    }

    private fun Channel.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(name, dataJson(mapOf("u" to pageUrl, "t" to name, "p" to logo)), TvType.Movie) {
            this.posterUrl = logo
        }

    private suspend fun fetchCards(listUrl: String): List<Channel> {
        val html = try {
            app.get(listUrl, headers = pageHeaders()).text
        } catch (_: Exception) {
            return emptyList()
        }
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Channel>()
        for (m in CARD_RX.findAll(html)) {
            val href = m.groupValues[1]
            val page = absUrl(href)
            if (!seen.add(page)) continue
            val inner = m.groupValues[2]
            val rawName = NAME_RX.find(inner)?.groupValues?.get(1)?.trim()
            if (rawName.isNullOrBlank()) continue
            val logo = LOGO_RX.find(inner)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            out += Channel(page, rawName, logo)
        }
        return out
    }

    // ---------------------------------------------------------------- details

    override suspend fun load(url: String): LoadResponse? {
        var pageUrl = url
        var title: String? = null
        var poster: String? = null

        if (url.trimStart().startsWith("{")) {
            try {
                val info = JSONObject(url)
                pageUrl = info.optString("u").ifBlank { url }
                title = info.optString("t").ifBlank { null }
                poster = info.optString("p").ifBlank { null }
            } catch (_: Exception) {
            }
        }
        if (title == null && pageUrl.startsWith("http")) {
            try {
                val html = app.get(pageUrl, headers = pageHeaders()).text
                title = H1_RX.find(html)?.groupValues?.get(1)
                    ?.replace(Regex("""\s+"""), " ")?.trim()?.ifBlank { null }
            } catch (_: Exception) {
            }
        }
        title = title?.ifBlank { null } ?: throw ErrorLoadingException("Movish: no title")

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
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
        val pageUrl = if (data.trimStart().startsWith("{")) {
            try {
                JSONObject(data).optString("u").ifBlank { null }
            } catch (_: Exception) {
                null
            } ?: return false
        } else {
            data
        }
        if (!pageUrl.startsWith("http")) return false

        val html = try {
            app.get(pageUrl, headers = pageHeaders()).text
        } catch (_: Exception) {
            return false
        }

        // site embed carries the numeric backend id, e.g. /iptv-embed/609
        val embedId = EMBED_RX.find(html)?.groupValues?.get(1)
        val embedUrl = if (embedId != null) "$mainUrl/iptv-embed/$embedId" else null
        if (embedUrl == null && embedId == null) return false

        val seen = mutableSetOf<String>()
        val results = coroutineScope {
            val jobs = mutableListOf(
                async {
                    // fast path: daddy backend directly (same page the embed uses)
                    if (embedId != null) {
                        withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                            resolveDirectBackend(embedId, pageUrl, callback, seen)
                        } ?: false
                    } else false
                },
                async {
                    // full path: site embed -> dlive.sx -> backend player
                    if (embedUrl != null) {
                        withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                            resolveEmbed(embedUrl, pageUrl, subtitleCallback, callback, seen)
                        } ?: false
                    } else false
                },
            )
            jobs.awaitAll()
        }
        return results.any { it }
    }

    private suspend fun resolveDirectBackend(
        embedId: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        val backend = "https://daddyliveplayer.st/premiumtv/daddy.php?id=$embedId"
        val html = try {
            app.get(backend, headers = pageHeaders(pageReferer)).text
        } catch (_: Exception) {
            return false
        }
        return emitStreams(html, "Direct", backend, callback, seen)
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        val level1 = try {
            app.get(embedUrl, headers = pageHeaders(pageReferer)).text
        } catch (_: Exception) {
            return false
        }
        val dlive = IFRAME_RX.find(level1)?.groupValues?.get(1) ?: return try {
            loadExtractor(embedUrl, pageReferer, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }

        val level2 = try {
            app.get(dlive, headers = pageHeaders(embedUrl)).text
        } catch (_: Exception) {
            null
        }
        if (level2 != null) {
            if (emitStreams(level2, "Server 1", dlive, callback, seen)) return true
            val nested = IFRAME_RX.find(level2)?.groupValues?.get(1)
            if (nested != null && nested != dlive) {
                val level3 = try {
                    app.get(nested, headers = pageHeaders(dlive)).text
                } catch (_: Exception) {
                    null
                }
                if (level3 != null && emitStreams(level3, "Server 1", nested, callback, seen)) return true
            }
        }

        return try {
            loadExtractor(dlive, embedUrl, subtitleCallback, callback)
        } catch (_: Exception) {
            try {
                loadExtractor(embedUrl, pageReferer, subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun emitStreams(
        html: String,
        label: String,
        streamReferer: String,
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
        if (found.isEmpty()) return false

        val origin = ORIGIN_RX.find(streamReferer)?.groupValues?.get(1)
        var emitted = false
        for (streamUrl in found) {
            if (!streamUrl.startsWith("http")) continue
            val isNew = synchronized(seen) { seen.add("$label|$streamUrl") }
            if (!isNew) continue
            val isHls = streamUrl.contains(".m3u8", true)
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
        }
        return emitted
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
