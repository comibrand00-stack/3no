package com.example.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class PressPlayz : MainAPI() {
    override var mainUrl = "https://pressplayz.to"
    override var name = "PressPlayz"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = false

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val ROW_LIVE = "Live TV"
        const val ROW_SPORTS = "Sports"

        const val MAX_LIVE_ITEMS = 250
        const val MAX_SEARCH_ITEMS = 50
        const val SERVER_TIMEOUT_MS = 25_000L

        // channel entries embedded in the /live-tv page (HTML-escaped JSON island):
        // &quot;id&quot;:[0,609],&quot;name&quot;:[0,&quot;...&quot;],
        // &quot;logo&quot;:[0,&quot;...&quot;],&quot;categories&quot;:[1,[[0,&quot;sports&quot;]]]
        val ENTRY_RX = Regex(
            """&quot;id&quot;:\[0,(\d+)\],&quot;name&quot;:\[0,&quot;(.*?)&quot;\],&quot;logo&quot;:\[0,&quot;(.*?)&quot;\],&quot;categories&quot;:(.*?)]}],[0,"""
        )
        val SERVER_RX = Regex("""data-url="([^"]+)"[^>]*>\s*([^<>]+?)\s*<""")
        val IFRAME_RX = Regex("""<iframe[^>]+src="(https?://[^"]+)"""")
        val SRC_RX = Regex("""const SRC\s*=\s*"([^"]+)"""")
        val FILE_RX = Regex(""""(?:file|source)"\s*:\s*"(https?://[^"]+)"""")
        val M3U8_RX = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:[^\s"'<>\\]*)?""")
        val ORIGIN_RX = Regex("""^(https?://[^/]+)""")
        val H1_RX = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
    }

    private data class Channel(
        val id: String,
        val name: String,
        val logo: String?,
        val isSports: Boolean,
    ) {
        val pageUrl: String get() = "https://pressplayz.to/live-tv/$id"
    }

    // ---------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val channels = fetchChannels().ifEmpty { return null }

        val lists = mutableListOf<HomePageList>()
        lists += HomePageList(
            ROW_LIVE,
            channels.take(MAX_LIVE_ITEMS).map { it.toSearchResponse() },
            true,
        )
        val sports = channels.filter { it.isSports }
        if (sports.isNotEmpty()) {
            lists += HomePageList(ROW_SPORTS, sports.map { it.toSearchResponse() }, true)
        }
        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return fetchChannels()
            .filter { it.name.contains(trimmed, true) }
            .take(MAX_SEARCH_ITEMS)
            .map { it.toSearchResponse() }
    }

    private fun Channel.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(name, dataJson(mapOf("u" to pageUrl, "t" to name, "p" to logo)), TvType.Live) {
            this.posterUrl = logo
        }

    private suspend fun fetchChannels(): List<Channel> {
        val html = try {
            app.get("$mainUrl/live-tv", headers = pageHeaders()).text
        } catch (_: Exception) {
            return emptyList()
        }
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Channel>()
        for (m in ENTRY_RX.findAll(html)) {
            val id = m.groupValues[1]
            if (!seen.add(id)) continue
            val name = unescape(m.groupValues[2]).trim().ifBlank { continue }
            val logo = unescape(m.groupValues[3]).trim().ifBlank { null }
            val isSports = m.groupValues[4].contains("sport", true)
            out += Channel(id, name, logo, isSports)
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
        title = title?.ifBlank { null } ?: throw ErrorLoadingException("PressPlayz: no title")

        return newLiveStreamLoadResponse(title, pageUrl, pageUrl) {
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

        val servers = SERVER_RX.findAll(html)
            .map { it.groupValues[1] to it.groupValues[2].replace(Regex("""\s+"""), " ").trim() }
            .filter { (serverUrl, label) -> serverUrl.startsWith("http") && label.isNotBlank() }
            .distinctBy { it.first }
            .toList()
        if (servers.isEmpty()) return false

        val seen = mutableSetOf<String>()
        val results = coroutineScope {
            servers.map { (serverUrl, label) ->
                async {
                    withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                        resolveServer(serverUrl, label, pageUrl, subtitleCallback, callback, seen)
                    } ?: false
                }
            }.awaitAll()
        }
        return results.any { it }
    }

    private suspend fun resolveServer(
        serverUrl: String,
        label: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        // level 1: dlive.sx server page -> inner player iframe
        val level1 = try {
            app.get(serverUrl, headers = pageHeaders(pageReferer)).text
        } catch (_: Exception) {
            return false
        }
        val inner = IFRAME_RX.find(level1)?.groupValues?.get(1) ?: run {
            // no iframe: maybe the server page is a player itself
            return try {
                loadExtractor(serverUrl, pageReferer, subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
        }

        // level 2: player page -> direct stream
        val level2 = try {
            app.get(inner, headers = pageHeaders(serverUrl)).text
        } catch (_: Exception) {
            null
        }
        if (level2 != null) {
            if (emitStreams(level2, label, inner, callback, seen)) return true
            // level 3: one nested iframe level (players wrapping players)
            val nested = IFRAME_RX.find(level2)?.groupValues?.get(1)
            if (nested != null && nested != inner) {
                val level3 = try {
                    app.get(nested, headers = pageHeaders(inner)).text
                } catch (_: Exception) {
                    null
                }
                if (level3 != null && emitStreams(level3, label, nested, callback, seen)) return true
            }
        }

        // fallback: let CloudStream extractors try the player urls
        return try {
            loadExtractor(inner, serverUrl, subtitleCallback, callback)
        } catch (_: Exception) {
            try {
                loadExtractor(serverUrl, pageReferer, subtitleCallback, callback)
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
        M3U8_RX.findAll(html).forEach { found += it.value }
        if (found.isEmpty()) return false

        val origin = ORIGIN_RX.find(streamReferer)?.groupValues?.get(1)
        var emitted = false
        for (streamUrl in found) {
            if (!streamUrl.startsWith("http")) continue
            val isNew = synchronized(seen) { seen.add(streamUrl) }
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

    private fun unescape(text: String): String = text
        .replace("&amp;", "&")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun dataJson(values: Map<String, Any?>): String {
        val json = JSONObject()
        values.forEach { (key, value) -> if (value != null) json.put(key, value) }
        return json.toString()
    }
}
