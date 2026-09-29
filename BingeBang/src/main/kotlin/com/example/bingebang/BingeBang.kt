package com.example.bingebang

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

class BingeBang : MainAPI() {
    override var mainUrl = "https://bingebang.st"
    override var name = "BingeBang"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // static salt baked into the player bundle (window.__BB_PLAYER__ crypto)
        const val CRYPTO_SALT = "9e2b7c41a0f6d85b3c1e7a94f25d0b86"

        const val SUB_SOURCE = "https://www.subtitlecat.com"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

        const val ROW_BATCH_SIZE = 6
        const val MAX_ROW_ITEMS = 24
    }

    private val mainRows = listOf(
        "Trending Movies (Today)" to "list/trending_movie_day?limit=24",
        "Trending Movies (This Week)" to "list/trending_movie_week?limit=24",
        "Trending Series (Today)" to "list/trending_tv_day?limit=24",
        "Trending Series (This Week)" to "list/trending_tv_week?limit=24",
        "Popular Movies" to "list/movie_popular?limit=24",
        "Popular Series" to "list/tv_popular?limit=24",
        "Now Playing" to "list/movie_now_playing?limit=24",
        "On The Air" to "list/tv_on_the_air?limit=24",
        "Top Rated Movies" to "list/movie_top_rated?limit=24",
        "Top Rated Series" to "list/tv_top_rated?limit=24",
        "New Releases (Movies)" to "discover/movie?sort=date_found&limit=48",
        "New Releases (Series)" to "discover/tv?sort=date_found&limit=48",
    )

    // streaming services exposed by the site (<meta name="bb-providers">)
    private val serviceRows = listOf(
        "Netflix" to "netflix",
        "Amazon Prime Video" to "prime-video",
        "Disney+" to "disney-plus",
        "HBO Max" to "hbo-max",
        "Hulu" to "hulu",
        "Apple TV+" to "apple-tv-plus",
        "Paramount+" to "paramount-plus",
        "Peacock" to "peacock",
        "Crunchyroll" to "crunchyroll",
        "MGM+" to "mgm-plus",
    )

    // country rows (discover with country filter, verified against /api/discover)
    private val countryRows = listOf(
        "Turkey" to "TR",
        "Iran" to "IR",
        "Egypt" to "EG",
        "India" to "IN",
        "Spain" to "ES",
    )

    // ---------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val jobs = mutableListOf<Pair<String, String>>()
        jobs += mainRows
        serviceRows.forEach { (service, slug) ->
            jobs += "$service Movies" to "discover/movie?provider=$slug&sort=popularity.desc"
            jobs += "$service Series" to "discover/tv?provider=$slug&sort=popularity.desc"
        }
        countryRows.forEach { (country, code) ->
            jobs += "$country Movies" to "discover/movie?country=$code&sort=popularity.desc"
            jobs += "$country Series" to "discover/tv?country=$code&sort=popularity.desc"
        }

        val lists = mutableListOf<HomePageList>()
        for (batch in jobs.chunked(ROW_BATCH_SIZE)) {
            val fetched = coroutineScope {
                batch.map { (label, path) -> async { fetchRow(label, path) } }.awaitAll()
            }
            lists += fetched.filterNotNull()
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    private suspend fun fetchRow(label: String, path: String): HomePageList? {
        val items = try {
            val text = app.get(apiUrl(path), headers = apiHeaders()).text
            JSONObject(text).optJSONArray("results")?.toSearchList()
        } catch (_: Exception) {
            null
        } ?: return null

        if (items.isEmpty()) return null
        return HomePageList(label, items.take(MAX_ROW_ITEMS), true)
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        return try {
            val path = "search/multi?query=" + URLEncoder.encode(trimmed, "UTF-8")
            val root = JSONObject(app.get(apiUrl(path), headers = apiHeaders()).text)
            root.optJSONArray("results")?.toSearchList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun JSONArray.toSearchList(): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            searchResponse(obj)?.let { out += it }
        }
        return out
    }

    private fun searchResponse(obj: JSONObject): SearchResponse? {
        val title = obj.optString("title").ifBlank { obj.optString("name") }.ifBlank { null }
            ?: return null
        val url = obj.optString("url").ifBlank { null } ?: return null
        val poster = image(obj.optString("poster_path"))
        val year = obj.optInt("year", 0).takeIf { it > 0 }
            ?: yearFrom(obj.optString("release_date").ifBlank { obj.optString("first_air_date") })
        val score = obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { Score.from10(it) }
        val isTv = obj.optString("media_type") == "tv"

        return if (isTv) {
            newTvSeriesSearchResponse(title, absUrl(url), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, absUrl(url), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    // ---------------------------------------------------------------- details

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = pageHeaders()).text
        val marker = "data-detail-payload>"
        val start = html.indexOf(marker)
        val payload = (if (start >= 0) sliceJson(html, start + marker.length) else null)
            ?: throw ErrorLoadingException("BingeBang: no details")
        val obj = JSONObject(payload)

        val isTv = obj.optString("media_type") == "tv" || url.contains("/tv/watch/")
        val title = obj.optString("name").ifBlank { obj.optString("title") }.ifBlank { null }
            ?: throw ErrorLoadingException("BingeBang: no title")

        val poster = image(obj.optString("poster_path"))
        val backdrop = image(obj.optString("backdrop_path"))
        val year = obj.optInt("year", 0).takeIf { it > 0 } ?: yearFrom(
            obj.optString("first_air_date").ifBlank { obj.optString("release_date") }
        )
        val plot = obj.optString("overview").ifBlank { null }
        val score = obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { Score.from10(it) }
        val tags = namesOf(obj.optJSONArray("genres"))
        val contentRating = obj.optString("certification").ifBlank { null }
        val trailers = obj.optString("trailer_key").ifBlank { null }?.let { key ->
            mutableListOf(
                TrailerData(
                    extractorUrl = "https://www.youtube.com/watch?v=$key",
                    referer = "$mainUrl/",
                    raw = false,
                    headers = emptyMap(),
                )
            )
        } ?: mutableListOf()
        val recommendations = obj.optJSONArray("recommendations")
            ?.let { arr -> arr.toSearchList().ifEmpty { null } }
            ?: obj.optJSONArray("similar")?.let { arr -> arr.toSearchList().ifEmpty { null } }

        val watchUrl = absUrl(obj.optString("url").ifBlank { url })
        val playUrl = absUrl(obj.optString("play_url").ifBlank {
            url.replace("/watch/", "/play/")
        })

        if (!isTv) {
            val data = dataJson(
                mapOf(
                    "u" to playUrl,
                    "t" to title,
                    "y" to (year ?: 0),
                )
            )
            return newMovieLoadResponse(title, watchUrl, TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.contentRating = contentRating
                this.backgroundPosterUrl = backdrop
                this.trailers = trailers
                this.recommendations = recommendations
            }
        }

        val seasonEntries = obj.optJSONArray("seasons")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.orEmpty()

        val episodes = fetchEpisodes(playUrl, title, seasonEntries)
        if (episodes.isEmpty()) throw ErrorLoadingException("BingeBang: no episodes")

        val seasonNames = seasonEntries.mapNotNull { entry ->
            val number = entry.optInt("season_number", Int.MIN_VALUE)
            if (number == Int.MIN_VALUE) null
            else SeasonData(number, entry.optString("name").ifBlank { null }, null)
        }.ifEmpty { null }

        return newTvSeriesLoadResponse(title, watchUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = score
            this.contentRating = contentRating
            this.backgroundPosterUrl = backdrop
            this.trailers = trailers
            this.recommendations = recommendations
            if (seasonNames != null) this.seasonNames = seasonNames
        }
    }

    private suspend fun fetchEpisodes(
        playUrl: String,
        showTitle: String,
        seasonEntries: List<JSONObject>,
    ): List<Episode> {
        val playerUrl = "$playUrl/1/1"
        val cfg = try {
            decryptXorBlock(app.get(playerUrl, headers = pageHeaders()).text)
        } catch (_: Exception) {
            null
        }

        val episodesJson = cfg?.let { sliceJson(it, it.indexOf("__BB_EPISODES__")) }
        if (episodesJson != null) {
            val built = buildEpisodes(episodesJson, playUrl, showTitle)
            if (built.isNotEmpty()) return built
        }

        // fallback: build plain episode links from the season block
        val out = mutableListOf<Episode>()
        for (season in seasonEntries) {
            val number = season.optInt("season_number", 0)
            if (number <= 0) continue
            val count = season.optInt("episode_count", 0)
            if (count <= 0) continue
            for (episode in 1..count) {
                out += newEpisode(
                    dataJson(
                        mapOf(
                            "u" to "$playUrl/$number/$episode",
                            "t" to showTitle,
                            "s" to number,
                            "e" to episode,
                        )
                    )
                ) {
                    this.season = number
                    this.episode = episode
                }
            }
        }
        return out
    }

    private fun buildEpisodes(payload: String, playUrl: String, showTitle: String): List<Episode> {
        val root = JSONObject(payload)
        val seasons = root.optJSONArray("seasons") ?: return emptyList()
        val out = mutableListOf<Episode>()

        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val number = season.optInt("season", i + 1)
            val list = season.optJSONArray("episodes") ?: continue
            for (j in 0 until list.length()) {
                val entry = list.optJSONObject(j) ?: continue
                val episode = entry.optInt("episode", j + 1)
                val link = entry.optString("url").ifBlank { "$playUrl/$number/$episode" }
                val data = dataJson(
                    mapOf(
                        "u" to absUrl(link),
                        "t" to showTitle,
                        "s" to number,
                        "e" to episode,
                    )
                )
                out += newEpisode(data) {
                    this.season = number
                    this.episode = episode
                    this.name = entry.optString("title").ifBlank { null }
                    this.posterUrl = image(entry.optString("still"))
                    this.description = entry.optString("plot").ifBlank { null }
                    this.runTime = parseMinutes(entry.optString("runtime"))
                }
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
        }
        val playUrl = info?.optString("u")?.ifBlank { null } ?: data
        if (!playUrl.startsWith("http")) return false

        val title = info?.optString("t")?.ifBlank { null }
        val year = info?.optInt("y", 0)?.takeIf { it > 0 }
        val season = info?.optInt("s", 0)?.takeIf { it > 0 }
        val episode = info?.optInt("e", 0)?.takeIf { it > 0 }

        val cfg = try {
            decryptXorBlock(app.get(playUrl, headers = pageHeaders()).text)
        } catch (_: Exception) {
            null
        } ?: return false

        val ticket = Regex("""ticket:\s*"([^"]+)"""").find(cfg)?.groupValues?.get(1)
            ?: return false

        val headers = playerHeaders(playUrl, ticket)
        val sources = try {
            val body = app.get(apiUrl("player/sources"), headers = headers).text
            JSONObject(decryptPayload(body, ticket)).optJSONArray("servers")
        } catch (_: Exception) {
            null
        } ?: return false

        var found = false
        val seen = mutableSetOf<String>()

        for (i in 0 until sources.length()) {
            val server = sources.optJSONObject(i) ?: continue
            val source = server.optString("source_url").ifBlank { null } ?: continue

            val resolved = try {
                val path = "player/resolve?src=" + URLEncoder.encode(source, "UTF-8")
                val body = app.get(apiUrl(path), headers = headers).text
                JSONObject(decryptPayload(body, ticket))
            } catch (_: Exception) {
                continue
            }

            val streamUrl = resolved.optString("url").ifBlank { null } ?: continue
            if (!seen.add(streamUrl)) continue

            val isHls = resolved.optString("type").ifBlank {
                if (streamUrl.contains(".m3u8", true)) "hls" else "mp4"
            }.contains("hls", true)

            val label = listOfNotNull(
                server.optString("label").ifBlank { null },
                server.optString("quality").ifBlank { null },
            ).joinToString(" ").ifBlank { name }

            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to BROWSER_UA,
                    )
                }
            )
            found = true

            // Arabic subtitles only
            val subs = resolved.optJSONArray("subtitles") ?: continue
            for (j in 0 until subs.length()) {
                val sub = subs.optJSONObject(j) ?: continue
                val subUrl = sub.optString("url").ifBlank { null } ?: continue
                val code = sub.optString("lang").lowercase()
                val subLabel = sub.optString("label")
                val isArabic = code == "ar" || code == "ara" ||
                    subLabel.contains("arab", true) || subLabel.contains("عرب")
                if (!isArabic) continue
                subtitleCallback(
                    SubtitleFile(
                        "Arabic",
                        subUrl,
                    ).apply {
                        this.headers = mapOf("User-Agent" to BROWSER_UA)
                    }
                )
            }
        }

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
            app.get(
                "$SUB_SOURCE/index.php?search=" + URLEncoder.encode(query, "UTF-8"),
                headers = pageHeaders(),
            ).text
        } catch (_: Exception) {
            return null
        }

        val pages = Regex("""href="(subs/[^"]+\.html)"""")
            .findAll(search)
            .map { it.groupValues[1] }
            .take(3)
            .toList()

        for (page in pages) {
            val html = try {
                app.get("$SUB_SOURCE/$page", headers = pageHeaders()).text
            } catch (_: Exception) {
                continue
            }
            val match = Regex("""id="download_ar"[^>]*href="([^"]+)"""").find(html) ?: continue
            val href = match.groupValues[1]
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

    private fun apiUrl(path: String): String = "$mainUrl/api/$path"

    private fun pageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
    )

    private fun playerHeaders(playUrl: String, ticket: String): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to playUrl,
        "Origin" to mainUrl,
        "X-BB-Player" to "1",
        "X-BB-Ticket" to ticket,
    )

    private fun absUrl(path: String): String = when {
        path.isBlank() -> path
        path.startsWith("http") -> path
        path.startsWith("/") -> mainUrl + path
        else -> "$mainUrl/$path"
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

    private fun parseMinutes(text: String): Int? =
        Regex("""(\d+)\s*min""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: text.trim().toIntOrNull()?.takeIf { it in 1..600 }

    private fun namesOf(array: JSONArray?): List<String>? {
        if (array == null) return null
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val name = array.optJSONObject(i)?.optString("name")?.ifBlank { null }
                ?: array.optString(i).ifBlank { null }
            if (name != null) out += name
        }
        return out.ifEmpty { null }
    }

    private fun dataJson(values: Map<String, Any?>): String {
        val json = JSONObject()
        values.forEach { (key, value) -> if (value != null) json.put(key, value) }
        return json.toString()
    }

    /** returns the balanced json object starting at or after [from] */
    private fun sliceJson(text: String, from: Int): String? {
        val start = text.indexOf('{', from)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val char = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** xor-obfuscated player config embedded in play pages */
    private fun decryptXorBlock(html: String): String? {
        if (html.isBlank()) return null
        val keyMatch = Regex("""k\s*=\s*\[([0-9,\s]+)]""").find(html) ?: return null
        val dataMatch = Regex("""d\s*=\s*\[([0-9,\s]+)]""").find(html) ?: return null

        val key = keyMatch.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }
        val data = dataMatch.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }
        if (key.isEmpty() || data.isEmpty()) return null

        val out = ByteArray(data.size) { index -> (data[index] xor key[index % key.size]).toByte() }
        return String(out, Charsets.UTF_8)
    }

    /** {enc:...} payloads returned by /api/player/sources and /api/player/resolve */
    private fun decryptPayload(body: String, ticket: String): String {
        val encoded = Regex(""""enc":"([^"]+)"""").find(body)?.groupValues?.get(1) ?: return body
        val raw = try {
            base64UrlDecode(encoded)
        } catch (_: Exception) {
            return body
        }
        if (raw.size <= 48) return body

        val key = sha256((CRYPTO_SALT + ticket).toByteArray(Charsets.UTF_8))
        val iv = raw.copyOfRange(0, 16)
        val payload = raw.copyOfRange(16, raw.size)
        val out = ByteArray(payload.size)

        val counterBlock = ByteArray(key.size + iv.size + 4)
        System.arraycopy(key, 0, counterBlock, 0, key.size)
        System.arraycopy(iv, 0, counterBlock, key.size, iv.size)
        val counterOffset = key.size + iv.size

        var offset = 0
        var counter = 0
        while (offset < payload.size) {
            counterBlock[counterOffset] = (counter ushr 24).toByte()
            counterBlock[counterOffset + 1] = (counter ushr 16).toByte()
            counterBlock[counterOffset + 2] = (counter ushr 8).toByte()
            counterBlock[counterOffset + 3] = counter.toByte()

            val stream = sha256(counterBlock)
            val block = minOf(32, payload.size - offset)
            for (i in 0 until block) {
                out[offset + i] = (payload[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            offset += block
            counter++
        }
        return String(out, Charsets.UTF_8)
    }

    private fun base64UrlDecode(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            0 -> normalized
            else -> normalized + "=".repeat(4 - (normalized.length % 4))
        }
        return Base64.getDecoder().decode(padded)
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
