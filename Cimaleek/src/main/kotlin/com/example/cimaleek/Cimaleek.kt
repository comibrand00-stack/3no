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
    override var mainUrl = "https://wwr438.b2cima.click"
    override var name = "CIMALEEK"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // rta.js build url -> per-build encryption secret (avoids refetching on every link)
    private val secretCache = HashMap<String, String>()

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val SUB_SOURCE = "https://www.subtitlecat.com"

        // the site rotates the wwrNNN sub-domain every few days; links are absolute
        const val DOMAIN = """wwr\d+\.b2cima\.click"""
        // encrypted player backend used by the lalaplayer iframe
        const val API_HOST = "https://re.wi7ryb.shop"
        const val LALA_SALT = "04xKNChdxW9uqaWwPl0H0WtNo7A43WEk"

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
        // lalaplayer build token, sent to the player API as ?ver=
        val VER_RX = Regex(""""ver":"([0-9a-f]{20,})"""")
        // player buttons on the detail/watch page: data-type="Vplayer" data-post="<id>" data-nume="1"
        val BTN_RX = Regex("""data-type="([^"]+)"[^>]*data-post="(\d+)"[^>]*data-nume="(\d+)"""")
        // iframe CONFIG line: var CONFIG={"_ch":"..","playToken":"..","mediaId":"..",...};
        val CONFIG_RX = Regex("""var CONFIG=(\{[^\n]*?\});""")
        // rta.js holds the per-build encryption secret inside an encoded string table
        val RTA_SRC_RX = Regex("""<script[^>]+src="([^"]*rta\.js[^"]*)"""")
        val SECRET_RX = Regex("""^[0-9a-f]{64}$""")
        // series -> season pages
        val SEASON_RX = Regex("href=\"((?:https://wwr\\d+\\.b2cima\\.click)?/seasons/[^\"]+/\")")
        val SEASONNUM_RX = Regex("""season-(\d+)""")
        // season page -> episodes (single or double quoted hrefs)
        val EP_RX = Regex("""href=["'](https://wwr\d+\.b2cima\.click/episodes/[^"']+/|/episodes/[^"']+/)[\"']""")
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
        val title = info.optString("t").ifBlank { null }
        val year = info.optInt("y", 0).takeIf { it > 0 }
        val season = info.optInt("s", 0).takeIf { it > 0 }
        val episode = info.optInt("e", 0).takeIf { it > 0 }

        val seen = mutableSetOf<String>()
        var found = false

        // movie pages and episode pages both carry "post_id" plus the player build token
        val pageHtml = http(pageUrl)
        val pid = pageHtml?.let { PID_RX.find(it)?.groupValues?.get(1) }
            ?: info.optString("p").ifBlank { null }
        val ver = pageHtml?.let { VER_RX.find(it)?.groupValues?.get(1) }

        if (pid != null) {
            val buttons = pageHtml?.let { parsePlayerButtons(it) }.orEmpty()
                .ifEmpty { listOf("Vplayer" to "1") }
            for ((type, num) in buttons) {
                val embed = lalaEmbed(pid, type, num, ver, pageUrl) ?: continue
                if (resolveEmbed(embed, subtitleCallback, callback, seen)) {
                    found = true
                    break
                }
            }
        }

        // last resort: a plain m3u8 may already be embedded in the page source
        if (!found && pageHtml != null) {
            found = emitStreams(pageHtml, name, pageUrl, subtitleCallback, callback, seen)
        }

        // Arabic subtitles from subtitlecat (best effort, never fatal)
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

    private fun parsePlayerButtons(html: String): List<Pair<String, String>> =
        BTN_RX.findAll(html)
            .map { it.groupValues[1] to it.groupValues[3] }
            .distinct()
            .toList()

    /**
     * The WP endpoint /wp-json/lalaplayer/v2 returns {"a":..,"b":[[..]],"c":..}
     * which decodes to a fresh https://re.wi7ryb.shop/embed/v/<id>/?d=.. URL.
     */
    private suspend fun lalaEmbed(
        pid: String,
        type: String,
        num: String,
        ver: String?,
        referer: String,
    ): String? {
        val url = buildString {
            append(mainUrl)
            append("/wp-json/lalaplayer/v2/?p=").append(pid)
            append("&t=").append(URLEncoder.encode(type, "UTF-8"))
            append("&n=").append(URLEncoder.encode(num, "UTF-8"))
            if (!ver.isNullOrBlank()) append("&ver=").append(ver)
            append("&_=").append(System.currentTimeMillis())
        }
        val body = http(url, apiHeaders(referer)) ?: return null
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        val a = json.optString("a")
        val b = json.optJSONArray("b")?.toString()
        val c = json.optString("c")
        if (a.isBlank() || b.isNullOrBlank() || c.isBlank()) return null
        return decodeLala(a, b, c, referer)
    }

    /**
     * _f1 + _f2 from player.runtime.js, validated against the live endpoint.
     *
     * _f1 removes the junk the API injects into the base64 blob. The cut offset is
     * not a constant: it is derived from the length of the referer path
     * (offset = max(0, path.length - 2)), which is why movie and episode pages use
     * different values. Mirrors the JS substring semantics (arguments are clamped).
     */
    private fun decodeLala(a: String, b: String, c: String, referer: String): String? {
        val pairs = try {
            val arr = org.json.JSONArray(b)
            (0 until arr.length()).mapNotNull { i ->
                val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                pair.optLong(0) to pair.optLong(1)
            }
        } catch (_: Exception) {
            return null
        }
        if (pairs.isEmpty()) return null

        val path = try {
            java.net.URI(referer).rawPath.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val offset = (path.length - 2).coerceAtLeast(0).toLong()

        var s = a
        for (index in pairs.indices.reversed()) {
            val (from, to) = pairs[index]
            val cut = (from - offset).coerceAtLeast(0).coerceAtMost(s.length.toLong()).toInt()
            val drop = to.coerceAtLeast(0).coerceAtMost(s.length.toLong()).toInt()
            s = s.substring(0, cut) + s.substring(drop)
        }

        val raw = try {
            Base64.getMimeDecoder().decode(s)
        } catch (_: Exception) {
            return null
        }
        val key = sha256Data("$LALA_SALT:$c")
        if (key.isEmpty()) return null
        val out = ByteArray(raw.size) { i ->
            (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return String(out, Charsets.ISO_8859_1)
            .trimEnd('\u0000', '\n', '\r', ' ')
            .takeIf { it.startsWith("http") }
    }

    /** Resolves an embed page into playable links (AES-GCM protocol + per-build secret). */
    private suspend fun resolveEmbed(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        seen: MutableSet<String>,
    ): Boolean {
        val page = http(embedUrl, embedHeaders(embedUrl)) ?: return false
        val configRaw = CONFIG_RX.find(page)?.groupValues?.get(1) ?: return false
        val cfg = try {
            JSONObject(configRaw)
        } catch (_: Exception) {
            return false
        }
        val ch = cfg.optString("_ch").ifBlank { return false }
        val token = cfg.optString("playToken").ifBlank { return false }
        val ts = cfg.optLong("playTokenTs")
        val mediaId = cfg.optString("mediaId").ifBlank { return false }
        val idType = cfg.optString("idType").ifBlank { "tmdb" }
        val mediaType = cfg.optString("mediaType").ifBlank { "movie" }
        val season = cfg.optInt("season", 0).takeIf { it > 0 }
        val episode = cfg.optInt("episode", 0).takeIf { it > 0 }

        val secret = rtaSecret(embedUrl, page) ?: return false
        val requestKey = sha256Data("$secret:$ch:api-request-v2")
        val responseKey = sha256Data("$secret:$ch:api-response-v1")

        val streamPayload = JSONObject().apply {
            put(idType, mediaId)
            put("type", mediaType)
            if (mediaType == "tv" && season != null && episode != null) {
                put("season", season)
                put("episode", episode)
            }
            put("token", token)
            put("ts", ts)
            put("_n", nonce())
        }
        val streamUrl = "$API_HOST/api/stream-data?k=" +
            URLEncoder.encode(encryptPayload(requestKey, ch, streamPayload.toString()), "UTF-8")
        val streamBody = http(streamUrl, embedHeaders(embedUrl)) ?: return false
        val streamJson = decryptPayload(responseKey, streamBody)?.let {
            try {
                JSONObject(it)
            } catch (_: Exception) {
                null
            }
        } ?: return false

        val data = streamJson.optJSONObject("data") ?: return false
        val servers = data.optJSONArray("servers") ?: return false
        val numbers = (0 until servers.length())
            .mapNotNull { servers.optJSONObject(it) }
            .filter { !it.optBoolean("failed", false) }
            .mapNotNull { it.optString("name").replace(Regex("""\D+"""), "").toIntOrNull() }
            .distinct()
            .take(3)
        if (numbers.isEmpty()) return false

        var emitted = false
        var fallback: Pair<String, String>? = null
        for (number in numbers) {
            if (emitted) break
            val serverPayload = JSONObject().apply {
                put("server_number", number)
                put(idType, mediaId)
                put("type", mediaType)
                if (mediaType == "tv" && season != null && episode != null) {
                    put("season", season)
                    put("episode", episode)
                }
                put("token", token)
                put("ts", ts)
                put("_n", nonce())
            }
            val serverUrl = "$API_HOST/api/server?k=" +
                URLEncoder.encode(encryptPayload(requestKey, ch, serverPayload.toString()), "UTF-8")
            val body = http(serverUrl, embedHeaders(embedUrl)) ?: continue
            val decoded = decryptPayload(responseKey, body) ?: continue
            val server = try {
                JSONObject(decoded).optJSONObject("server")
            } catch (_: Exception) {
                null
            } ?: continue

            val qualities = server.optJSONArray("qualities") ?: continue
            for (i in 0 until qualities.length()) {
                val quality = qualities.optJSONObject(i) ?: continue
                val stream = quality.optString("url").ifBlank { null } ?: continue
                val label = quality.optString("label").ifBlank { "auto" }
                val format = quality.optString("format")
                val isHls = format.equals("hls", true) || stream.contains(".m3u8", true)
                // some servers hand out CDN paths that 404; only advertise playable ones
                if (isHls && !isPlaylist(stream)) {
                    if (fallback == null) fallback = label to stream
                    continue
                }
                val key = "s$number|$stream"
                if (!synchronized(seen) { seen.add(key) }) continue
                try {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name • S$number $label",
                            url = stream,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = "$API_HOST/"
                            this.headers = mapOf(
                                "User-Agent" to BROWSER_UA,
                                "Referer" to "$API_HOST/",
                            )
                        }
                    )
                    emitted = true
                } catch (_: Exception) {
                }
            }

            val subs = server.optJSONArray("subs")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val sub = subs.optJSONObject(i) ?: continue
                    val file = sub.optString("file")
                    if (file.isBlank()) continue
                    val lang = sub.optString("lang").ifBlank { "und" }
                    val subUrl = if (file.startsWith("http")) file else API_HOST + file
                    val key = "sub|$subUrl"
                    if (!synchronized(seen) { seen.add(key) }) continue
                    subtitleCallback(
                        SubtitleFile(lang, subUrl).apply {
                            this.headers = mapOf("User-Agent" to BROWSER_UA)
                        }
                    )
                }
            }
        }

        // nothing passed the playlist probe: still offer the first server as a last resort
        if (!emitted && fallback != null) {
            val (label, stream) = fallback
            val key = "fallback|$stream"
            if (synchronized(seen) { seen.add(key) }) {
                try {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name • $label",
                            url = stream,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = "$API_HOST/"
                            this.headers = mapOf(
                                "User-Agent" to BROWSER_UA,
                                "Referer" to "$API_HOST/",
                            )
                        }
                    )
                    emitted = true
                } catch (_: Exception) {
                }
            }
        }
        return emitted
    }

    private suspend fun isPlaylist(url: String): Boolean {
        val body = http(url, embedHeaders("$API_HOST/"), 10_000) ?: return false
        return body.contains("#EXTM3U")
    }

    // ------------------------------------------------------------ player crypto

    private fun nonce(): String = java.lang.Long.toHexString(System.currentTimeMillis() + (Math.random() * 100000).toLong())

    private fun sha256Data(value: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun b64UrlDecode(text: String): ByteArray {
        var value = text.trim().replace('-', '+').replace('_', '/')
        while (value.length % 4 != 0) value += "="
        return Base64.getMimeDecoder().decode(value)
    }

    /** request body: base64url(iv[12] || tag[16] || ciphertext) prefixed with g2.<ch>. */
    private fun encryptPayload(key: ByteArray, ch: String, plain: String): String {
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv),
        )
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val cipherLen = sealed.size - 16
        val body = ByteArray(12 + sealed.size)
        System.arraycopy(iv, 0, body, 0, 12)
        System.arraycopy(sealed, cipherLen, body, 12, 16)
        System.arraycopy(sealed, 0, body, 28, cipherLen)
        return "g2.$ch." + b64UrlEncode(body)
    }

    /** response body: base64url(iv[12] || ciphertext || tag[16]). */
    private fun decryptPayload(key: ByteArray, body: String): String? {
        val raw = try {
            b64UrlDecode(body)
        } catch (_: Exception) {
            return null
        }
        if (raw.size <= 28) return null
        val iv = raw.copyOfRange(0, 12)
        val cipherText = raw.copyOfRange(12, raw.size - 16)
        val tag = raw.copyOfRange(raw.size - 16, raw.size)
        val merged = ByteArray(cipherText.size + 16)
        System.arraycopy(cipherText, 0, merged, 0, cipherText.size)
        System.arraycopy(tag, 0, merged, cipherText.size, 16)
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv),
            )
            String(cipher.doFinal(merged), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** The embed page loads rta.js, whose string table carries the per-build secret. */
    private suspend fun rtaSecret(embedUrl: String, page: String): String? {
        val src = RTA_SRC_RX.find(page)?.groupValues?.get(1) ?: return null
        val url = when {
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> API_HOST + src
            else -> API_HOST + "/" + src
        }
        secretCache[url]?.let { return it }
        val js = http(url, embedHeaders(embedUrl)) ?: return null
        val secret = extractSecret(js) ?: return null
        secretCache[url] = secret
        return secret
    }

    private fun extractSecret(src: String): String? {
        val z = Regex("""MganknooF\s*=\s*\[([\d,\s]+)]""").find(src) ?: return null
        val numbers = z.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }
        if (numbers.size < 5) return null
        var zx = 0
        for (i in 0 until 5) zx = zx xor numbers[i]

        val alpha = Regex("""function XtBqGKN\(t\)\{const a="([^"]+)"""")
            .find(src)?.groupValues?.get(1) ?: return null
        val start = src.indexOf("Aucq=[[")
        if (start < 0) return null
        val end = src.indexOf("kxLgcU=", start)
        if (end < 0) return null
        val chunk = src.substring(start, end)

        var index = 0
        for (m in Regex("""\[\d+,"([^"]*)"\]""").findAll(chunk)) {
            val entry = decodeAlpha(m.groupValues[1], alpha)
            val secret = decodeEntry(entry, index, zx)
            index++
            if (secret != null) return secret
        }
        return null
    }

    private fun decodeAlpha(text: String, alpha: String): IntArray {
        val out = ArrayList<Int>(text.length)
        var n = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=') break
            val v = alpha.indexOf(ch)
            if (v < 0) continue
            n = (n shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add((n ushr bits) and 255)
            }
        }
        return out.toIntArray()
    }

    /** NNfV(i) from rta.js: tag 41 is a string, 19 a double, 107/151/73/211 literals. */
    private fun decodeEntry(entry: IntArray, index: Int, zx: Int): String? {
        if (entry.size < 13) return null
        val c = occhau(2692134750L.toInt() xor zx, index xor 477376321)
        val s = le32(entry, 8) xor occhau(c, 1711722169)
        val h = le32(entry, 4) xor occhau(c, 1288146053)
        if (h != 1758495749) return null
        val mode = (le32(entry, 0) xor (occhau(c, 1810599775) and 3)) and 3
        val length = entry.size - 12
        val plain = IntArray(length)
        for (j in 0 until length) {
            val key = when (mode) {
                0 -> occhau(s xor c xor 2089558007, j) and 255
                1 -> {
                    val w = occhau(s + c + 3550797174L.toInt(), j xor c)
                    (w ushr ((j and 3) * 8)) and 255
                }
                else -> occhau(s xor (c * 33) xor 1589657896, j + c) and 255
            }
            plain[j] = entry[j + 12] xor key
        }
        if (plain[0] != 41) return null
        val bytes = ByteArray(length - 1) { plain[it + 1].toByte() }
        val value = String(bytes, Charsets.ISO_8859_1)
        return value.takeIf { SECRET_RX.matches(it) }
    }

    private fun le32(bytes: IntArray, offset: Int): Int =
        bytes[offset] or (bytes[offset + 1] shl 8) or
            (bytes[offset + 2] shl 16) or (bytes[offset + 3] shl 24)

    private fun occhau(value: Int, index: Int): Int {
        var x = value xor ((index + 1) * 0x9e3779b1L.toInt())
        x = x xor (x ushr 16)
        x *= 0x7feb352d
        x = x xor (x ushr 15)
        x *= 0x846ca68bL.toInt()
        x = x xor (x ushr 16)
        return x
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

    private fun apiHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to referer,
    )

    private fun embedHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "*/*",
        "Referer" to referer,
    )

    private suspend fun http(
        url: String,
        headers: Map<String, String> = pageHeaders(),
        timeout: Long = SERVER_TIMEOUT_MS,
    ): String? = try {
        withTimeoutOrNull(timeout) { app.get(url, headers = headers).text }
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

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
