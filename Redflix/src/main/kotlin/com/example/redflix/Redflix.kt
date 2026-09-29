package com.example.redflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

class Redflix : MainAPI() {
    override var mainUrl = "https://redflix.one"
    override var name = "Redflix"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val API = "https://redflix.one/api/tmdb"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
        const val SUB_SOURCE = "https://www.subtitlecat.com"

        const val MAX_ROW_ITEMS = 20
        const val MAX_SEARCH_ITEMS = 50
        const val MAX_SEASONS = 30
        const val SERVER_TIMEOUT_MS = 25_000L
        const val NET_TIMEOUT_MS = 15_000L

        // embed providers (id, label, domain) mirrored from the site player
        val SERVERS = listOf(
            Triple("vidrift", "Vid", "https://embed.vidrift.in"),
            Triple("playapi", "PlayFast", "https://playapi.co"),
            Triple("vidbolt", "Redflix", "https://vidbolt.xyz"),
            Triple("screenscape", "Hindi", "https://screenscape.me"),
            Triple("hindi-new", "Hindi New", "https://embed.reelsdownload.online"),
            Triple("cinezo", "Cinezo", "https://player.cinezo.live"),
            Triple("vidcore", "Orion", "https://vidcore.net"),
            Triple("vidup", "Premium", "https://vidup.to"),
            Triple("zxcstream", "Vidgod", "https://api.zxcstream.xyz"),
            Triple("filmu", "Bolt", "https://embed.filmu.in"),
            Triple("videasy", "Mega", "https://player.videasy.to"),
            Triple("vidlink", "Nova", "https://vidlink.pro"),
            Triple("peachify", "Hindi Mirror", "https://peachify.top"),
            Triple("vidfast", "Alpha", "https://vidfast.vc"),
        )

        val COUNTRIES = listOf(
            "Turkey" to "TR",
            "Iran" to "IR",
            "Afghanistan" to "AF",
        )

        // vidrift source objects: {"index":0,"url":"...","proxyUrl":"...",...,"provider":"Earth","direct":false}
        val OBJ_RX = Regex("""\{[^{}]*"provider"[^{}]*\}""")
        val PNAME_RX = Regex(""""provider":"([^"]+)"""")
        val URL_RX = Regex(""""url":"([^"]*)"""")
        val PROXY_RX = Regex(""""proxyUrl":"([^"]*)"""")

        // subtitle tracks: {"code":"x1","label":"Arabic","lang":"","url":"/api/subtitles/..."}
        val SUBARR_RX = Regex("""var subtitleTracks = (\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
        val TRIPLE_RX = Regex(""""label":"([^"]*)","lang":"([^"]*)","url":"([^"]*)"""")

        val IFRAME_RX = Regex("""<iframe[^>]+src="(https?://[^"]+)"""")
        val SRC_RX = Regex("""const SRC\s*=\s*"([^"]+)"""")
        val FILE_RX = Regex(""""(?:file|source)"\s*:\s*"(https?://[^"]+)"""")
        val ATOB_RX = Regex("""window\.atob\(['"]([A-Za-z0-9+/=]{40,})['"]\)""")
        val M3U8_RX = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:[^\s"'<>\\]*)?""")
        val ORIGIN_RX = Regex("""^(https?://[^/]+)""")
        // arabic subtitle file heuristic (matches ar.vtt, /Arabic/..., -ar. but not star.srt)
        val ARURL_RX = Regex("""(?i:arab|[/_.\-]ar([/_.\-?]|$))""")
    }

    // ---------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val jobs = mutableListOf<Triple<String, String, String>>()
        for ((country, code) in COUNTRIES) {
            jobs += Triple("$country Movies", "movie", code)
            jobs += Triple("$country Series", "tv", code)
        }

        val lists = coroutineScope {
            jobs.map { (label, kind, code) ->
                async {
                    val items = discover(kind, code)
                    if (items.isEmpty()) null else HomePageList(label, items.take(MAX_ROW_ITEMS), true)
                }
            }.awaitAll().filterNotNull()
        }
        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    private suspend fun discover(kind: String, country: String): List<SearchResponse> {
        val url = "$API/discover/$kind?language=en-US&sort_by=popularity.desc" +
            "&page=1&vote_count.gte=10&with_origin_country=$country"
        return try {
            JSONObject(app.get(url, headers = apiHeaders()).text)
                .optJSONArray("results")?.toSearchList(kind) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val url = "$API/search/multi?query=${URLEncoder.encode(trimmed, "UTF-8")}&language=en-US&page=1"
        return try {
            val root = JSONObject(app.get(url, headers = apiHeaders()).text)
            root.optJSONArray("results")?.toSearchList(null)?.take(MAX_SEARCH_ITEMS) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun JSONArray.toSearchList(forceKind: String?): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val kind = forceKind ?: obj.optString("media_type")
            val isTv = kind == "tv"
            if (!isTv && kind != "movie") continue
            val id = obj.optInt("id", 0)
            if (id <= 0) continue
            val title = obj.optString("title").ifBlank { obj.optString("name") }.ifBlank { continue }
            val poster = image(obj.optString("poster_path"))
            val year = obj.optInt("year", 0).takeIf { it > 0 }
                ?: yearFrom(obj.optString("release_date").ifBlank { obj.optString("first_air_date") })
            val score = obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { Score.from10(it) }
            val data = dataJson(
                mapOf(
                    "i" to id,
                    "k" to if (isTv) "tv" else "movie",
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
        val id = info?.optInt("i", 0)?.takeIf { it > 0 }
            ?: throw ErrorLoadingException("Redflix: bad data")
        val kind = info.optString("k").ifBlank { "movie" }
        val isTv = kind == "tv"

        val meta = try {
            JSONObject(app.get("$API/$kind/$id?language=en-US", headers = apiHeaders()).text)
        } catch (_: Exception) {
            null
        } ?: throw ErrorLoadingException("Redflix: no details")

        val title = meta.optString("title").ifBlank { meta.optString("name") }
            .ifBlank { info.optString("t") }
            .ifBlank { null } ?: throw ErrorLoadingException("Redflix: no title")
        val poster = image(meta.optString("poster_path"))
        val backdrop = image(meta.optString("backdrop_path"))
        val year = meta.optInt("year", 0).takeIf { it > 0 } ?: yearFrom(
            meta.optString("release_date").ifBlank { meta.optString("first_air_date") }
        ) ?: info.optInt("y", 0).takeIf { it > 0 }
        val plot = meta.optString("overview").ifBlank { null }
        val score = meta.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { Score.from10(it) }
        val tags = namesOf(meta.optJSONArray("genres"))
        val imdbId = meta.optString("imdb_id").ifBlank { null }
        val playUrl = "$mainUrl/play?id=$id&type=$kind"

        if (!isTv) {
            val data = dataJson(
                mapOf(
                    "i" to id,
                    "k" to "movie",
                    "t" to title,
                    "y" to (year ?: 0),
                    "imdb" to imdbId,
                )
            )
            return newMovieLoadResponse(title, playUrl, TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.backgroundPosterUrl = backdrop
            }
        }

        val seasonNumbers = meta.optJSONArray("seasons")?.let { arr ->
            (0 until arr.length()).mapNotNull {
                arr.optJSONObject(it)?.optInt("season_number", Int.MIN_VALUE)
            }.filter { it >= 0 }.distinct().sorted().take(MAX_SEASONS)
        }.orEmpty()

        val episodes = coroutineScope {
            seasonNumbers.map { season ->
                async { fetchSeasonEpisodes(id, season, title, imdbId) }
            }.awaitAll().flatten().sortedWith(compareBy({ it.season }, { it.episode }))
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("Redflix: no episodes")

        return newTvSeriesLoadResponse(title, playUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = score
            this.backgroundPosterUrl = backdrop
        }
    }

    private suspend fun fetchSeasonEpisodes(
        id: Int,
        season: Int,
        showTitle: String,
        imdbId: String?,
    ): List<Episode> {
        val arr = try {
            JSONObject(
                app.get("$API/tv/$id/season/$season?language=en-US", headers = apiHeaders()).text
            ).optJSONArray("episodes")
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val out = mutableListOf<Episode>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val number = entry.optInt("episode_number", 0)
            if (number <= 0) continue
            val data = dataJson(
                mapOf(
                    "i" to id,
                    "k" to "tv",
                    "t" to showTitle,
                    "s" to season,
                    "e" to number,
                    "imdb" to imdbId,
                )
            )
            out += newEpisode(data) {
                this.season = season
                this.episode = number
                this.name = entry.optString("name").ifBlank { null }
                this.posterUrl = image(entry.optString("still_path"))
                this.description = entry.optString("overview").ifBlank { null }
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
        val id = info.optInt("i", 0).takeIf { it > 0 } ?: return false
        val isTv = info.optString("k") == "tv"
        val title = info.optString("t").ifBlank { null }
        val year = info.optInt("y", 0).takeIf { it > 0 }
        val season = info.optInt("s", 0).takeIf { it > 0 }
        val episode = info.optInt("e", 0).takeIf { it > 0 }

        val seen = mutableSetOf<String>()
        val results = coroutineScope {
            SERVERS.map { (serverId, label, domain) ->
                async {
                    try {
                        val embed = buildEmbed(serverId, domain, id, isTv, season, episode)
                        val ok = withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                            resolveEmbed(embed, label, domain, subtitleCallback, callback, seen)
                        } ?: false
                        if (!ok) {
                            // still list the server so all 14 show
                            withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                                emitFallback(embed, label, callback, seen)
                            }
                        }
                    } catch (t: Throwable) {
                        // never let one bad server kill the other 13 (but respect cancellation)
                        if (t is CancellationException) throw t
                        try {
                            val embed = buildEmbed(serverId, domain, id, isTv, season, episode)
                            emitFallback(embed, label, callback, seen)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }
            }.awaitAll()
        }

        val arabic = findArabicSubtitle(title, year, season, episode)
        if (arabic != null) {
            subtitleCallback(
                SubtitleFile("Arabic", arabic).apply {
                    this.headers = mapOf("User-Agent" to BROWSER_UA)
                }
            )
        }

        return results.any { it }
    }

    private fun buildEmbed(
        serverId: String,
        domain: String,
        id: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): String {
        val s = season ?: 1
        val e = episode ?: 1
        return when (serverId) {
            "vidrift" -> if (isTv) "$domain/embed/tv/$id/$s/$e" else "$domain/embed/movie/$id"
            "playapi" -> if (isTv) "$domain/tv/$id/$s/$e" else "$domain/movie/$id"
            "vidbolt" -> if (isTv) {
                "$domain/tv/$id/$s/$e?theme=E50914&autoPlay=true&mobileSheets=true"
            } else {
                "$domain/movie/$id?theme=E50914&autoPlay=true&mobileSheets=true"
            }
            "screenscape" -> if (isTv) {
                "$domain/embed?tmdb=$id&type=tv&lan=hindi&s=$s&e=$e"
            } else {
                "$domain/embed?tmdb=$id&type=movie&lan=hindi"
            }
            "hindi-new" -> if (isTv) {
                "$domain/player/$id/$s/$e?key=k_885c7f4e1997d1d9c4e42aff"
            } else {
                "$domain/player/$id?key=k_885c7f4e1997d1d9c4e42aff"
            }
            "cinezo" -> {
                val q = "autoplay=true&poster=true&primarycolor=E50914&secondarycolor=0a0a12&iconcolor=ffffff"
                if (isTv) "$domain/embed/tv/$id/$s/$e?$q" else "$domain/embed/movie/$id?$q"
            }
            "vidcore", "vidup" -> if (isTv) {
                "$domain/tv/$id/$s/$e?theme=E50914"
            } else {
                "$domain/movie/$id?theme=E50914"
            }
            "zxcstream" -> if (isTv) "$domain/player/tv/$id/$s/$e" else "$domain/player/movie/$id"
            "filmu" -> if (isTv) {
                "$domain/tv/$id/$s/$e?color=e50914"
            } else {
                "$domain/movie/$id?color=e50914"
            }
            "videasy" -> if (isTv) {
                "$domain/tv/$id/$s/$e?color=E50914&nextEpisode=true&autoplayNextEpisode=true"
            } else {
                "$domain/movie/$id?color=E50914"
            }
            "vidlink" -> if (isTv) {
                "$domain/tv/$id/$s/$e?primaryColor=63b8bc&autoplay=true&nextbutton=true"
            } else {
                "$domain/movie/$id?primaryColor=63b8bc&autoplay=true&nextbutton=false"
            }
            "peachify" -> if (isTv) {
                "$domain/embed/tv/$id/$s/$e?accent=E50914&dub=Hindi"
            } else {
                "$domain/embed/movie/$id?accent=E50914&dub=Hindi"
            }
            else -> if (isTv) { // vidfast (Alpha) + default
                "$domain/tv/$id/$s/$e?autoNext=true&nextButton=false&title=true&poster=true&autoPlay=true"
            } else {
                "$domain/movie/$id?autoPlay=true"
            }
        }
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        label: String,
        embedHost: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        val html = try {
            withTimeoutOrNull(NET_TIMEOUT_MS) {
                app.get(embedUrl, headers = pageHeaders("$mainUrl/")).text
            }
        } catch (_: Exception) {
            null
        } ?: return false

        var emitted = false
        // vidrift-style provider objects + subtitle tracks
        for (m in OBJ_RX.findAll(html)) {
            val obj = m.value
            val prov = PNAME_RX.find(obj)?.groupValues?.get(1)
            if (prov.isNullOrBlank()) continue
            URL_RX.findAll(obj).forEach { u ->
                val stream = u.groupValues[1].ifBlank { null } ?: return@forEach
                if (emitStream(absUrl(stream, embedHost), "$label ${prov.trim()}", embedUrl, callback, seen)) {
                    emitted = true
                }
            }
            PROXY_RX.findAll(obj).forEach { u ->
                val stream = u.groupValues[1].ifBlank { null } ?: return@forEach
                if (emitStream(absUrl(stream, embedHost), "$label ${prov.trim()}", embedUrl, callback, seen)) {
                    emitted = true
                }
            }
        }
        // subtitle tracks: Arabic only
        SUBARR_RX.find(html)?.groupValues?.get(1)?.let { arr ->
            for (t in TRIPLE_RX.findAll(arr)) {
                val subLabel = t.groupValues[1]
                val code = t.groupValues[2]
                val subUrl = t.groupValues[3].ifBlank { continue }
                val isArabic = subLabel.contains("arab", true) || code.equals("ar", true)
                if (!isArabic) continue
                emitSub(absUrl(subUrl, embedHost), subtitleCallback, seen)
            }
        }
        // generic direct streams
        if (emitStreams(html, label, embedUrl, subtitleCallback, callback, seen)) emitted = true
        // nested player iframe (one level)
        if (!emitted) {
            val nested = IFRAME_RX.find(html)?.groupValues?.get(1)
            if (nested != null && nested != embedUrl) {
                val level2 = try {
                    withTimeoutOrNull(NET_TIMEOUT_MS) {
                        app.get(nested, headers = pageHeaders(embedUrl)).text
                    }
                } catch (_: Exception) {
                    null
                }
                if (level2 != null && emitStreams(level2, label, nested, subtitleCallback, callback, seen)) {
                    emitted = true
                }
            }
        }
        if (!emitted) {
            emitted = try {
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
        }
        return emitted
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
        if (found.isEmpty()) return false

        val origin = ORIGIN_RX.find(streamReferer)?.groupValues?.get(1)
        var emitted = false
        for (streamUrl in found) {
            if (!streamUrl.startsWith("http")) continue
            // subtitle files are not streams: Arabic ones go to subs, rest are skipped
            if (streamUrl.contains(".vtt", true) || streamUrl.contains(".srt", true)) {
                if (ARURL_RX.containsMatchIn(streamUrl)) {
                    emitSub(streamUrl, subtitleCallback, seen)
                }
                continue
            }
            if (emitStream(streamUrl, label, streamReferer, callback, seen, origin)) emitted = true
        }
        return emitted
    }

    private suspend fun emitStream(
        streamUrl: String,
        label: String,
        streamReferer: String,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
        origin: String? = null,
    ): Boolean {
        val isNew = synchronized(seen) { seen.add("$label|$streamUrl") }
        if (!isNew) return false
        val realOrigin = origin ?: ORIGIN_RX.find(streamReferer)?.groupValues?.get(1)
        val isHls = streamUrl.contains(".m3u8", true)
        return try {
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
                    if (realOrigin != null) headers["Origin"] = realOrigin
                    this.headers = headers
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun emitSub(
        subUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        seen: MutableSet<String>,
    ) {
        val isNew = synchronized(seen) { seen.add("sub|$subUrl") }
        if (!isNew) return
        try {
            subtitleCallback(
                SubtitleFile("Arabic", subUrl).apply {
                    this.headers = mapOf("User-Agent" to BROWSER_UA)
                }
            )
        } catch (_: Exception) {
        }
    }

    private suspend fun emitFallback(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ) {
        // list instantly: extractors were already attempted in resolveEmbed when
        // the embed page was reachable, so don't waste time retrying dead hosts
        val isNew = synchronized(seen) { seen.add("$label|$embedUrl") }
        if (!isNew) return
        try {
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = embedUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to BROWSER_UA,
                    )
                }
            )
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- arabic subs

    private suspend fun findArabicSubtitle(
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
    ): String? {
        if (title.isNullOrBlank()) return null

        val query = if (season != null && episode != null) {
            "$title S" + season.toString().padStart(2, '0') + "E" + episode.toString().padStart(2, '0')
        } else {
            listOfNotNull(title, year?.toString()).joinToString(" ")
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

    private fun absUrl(path: String, base: String): String {
        if (path.isBlank()) return path
        if (path.startsWith("http")) return path
        val origin = ORIGIN_RX.find(base)?.groupValues?.get(1) ?: base
        return if (path.startsWith("/")) origin + path else "$origin/$path"
    }

    private fun image(path: String?): String? {
        val value = path?.ifBlank { null } ?: return null
        return when {
            value.startsWith("http") -> value
            value.startsWith("/") -> IMAGE_BASE + value
            else -> "$IMAGE_BASE/$value"
        }
    }

    private fun yearFrom(text: String): Int? =
        Regex("""^(\d{4})""").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun namesOf(array: JSONArray?): List<String>? {
        if (array == null) return null
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val entryName = array.optJSONObject(i)?.optString("name")?.ifBlank { null }
                ?: array.optString(i).ifBlank { null }
            if (entryName != null) out += entryName
        }
        return out.ifEmpty { null }
    }

    private fun dataJson(values: Map<String, Any?>): String {
        val json = JSONObject()
        values.forEach { (key, value) -> if (value != null) json.put(key, value) }
        return json.toString()
    }
}
