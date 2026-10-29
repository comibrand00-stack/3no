package com.example.novahd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * NovaHD provider (novahd.cc).
 *
 * The site is an SPA backed by a JSON API. Every catalogue call accepts `lang=ar-SA` which
 * makes the API return Arabic titles and descriptions, so the whole UI is Arabic.
 *
 * `/api/sources` has one sharp edge: it only returns the full mirror list (and subtitles)
 * when the request carries an `x-nova-visitor` UUID header. Without it the API silently
 * falls back to a single Falcon/1080p source.
 */
class NovaHD : MainAPI() {
    override var mainUrl = "https://novahd.cc"
    override var name = "NovaHD"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val API_LANG = "ar-SA"
        const val EN_LANG = "en-US"
        const val SUB_SOURCE = "https://www.subtitlecat.com"
        const val POSTER_BASE = "https://image.tmdb.org/t/p/w500"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"
        const val ROW_BATCH_SIZE = 6

        val genreNames = mapOf(
            "Action" to "أكشن",
            "Adventure" to "مغامرة",
            "Animation" to "أنيميشن",
            "Comedy" to "كوميديا",
            "Crime" to "جريمة",
            "Documentary" to "وثائقي",
            "Drama" to "دراما",
            "Family" to "عائلي",
            "Fantasy" to "فانتازيا",
            "History" to "تاريخي",
            "Horror" to "رعب",
            "Music" to "موسيقى",
            "Mystery" to "غموض",
            "Romance" to "رومانسي",
            "Science Fiction" to "خيال علمي",
            "TV Movie" to "فيلم تلفزيوني",
            "Thriller" to "إثارة",
            "War" to "حرب",
            "Western" to "غربي",
            "Action & Adventure" to "أكشن ومغامرة",
            "Kids" to "أطفال",
            "News" to "أخبار",
            "Reality" to "واقع",
            "Sci-Fi & Fantasy" to "خيال علمي وفانتازيا",
            "Soap" to "دراما مسلسلة",
            "Talk" to "حوارية",
            "War & Politics" to "حرب وسياسة",
        )
    }

    // /api/sources refuses to expose every mirror unless the caller looks like a real visitor
    private val visitorId: String by lazy { UUID.randomUUID().toString() }

    private val movieGenres = listOf(
        "أكشن" to "Action",
        "كوميديا" to "Comedy",
        "رعب" to "Horror",
        "إثارة" to "Thriller",
        "رومانسي" to "Romance",
        "خيال علمي" to "Science Fiction",
        "أنيميشن" to "Animation",
        "جريمة" to "Crime",
        "دراما" to "Drama",
        "فانتازيا" to "Fantasy",
        "غموض" to "Mystery",
        "عائلي" to "Family",
    )

    private val showGenres = listOf(
        "أكشن ومغامرة" to "Action & Adventure",
        "كوميديا" to "Comedy",
        "دراما" to "Drama",
        "خيال علمي وفانتازيا" to "Sci-Fi & Fantasy",
        "غموض" to "Mystery",
        "جريمة" to "Crime",
        "أنيميشن" to "Animation",
        "واقع" to "Reality",
        "حرب وسياسة" to "War & Politics",
        "غربي" to "Western",
    )

    // ---------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogPage = page.coerceAtLeast(1)
        val jobs = mutableListOf<Pair<String, String>>()

        if (catalogPage == 1) {
            jobs += "الأكثر رواجًا" to "trending?type=all&lang=$API_LANG"
        }
        jobs += "أفلام شائعة" to listPath("movies", catalogPage, "popularity")
        jobs += "مسلسلات شائعة" to listPath("shows", catalogPage, "popularity")
        jobs += "أفضل الأفلام" to listPath("movies", catalogPage, "voteAverage")
        jobs += "أفضل المسلسلات" to listPath("shows", catalogPage, "voteAverage")
        jobs += "أحدث الأفلام" to listPath("movies", catalogPage, "releaseDate")
        jobs += "أحدث المسلسلات" to listPath("shows", catalogPage, "firstAirDate")

        // Genre rows only on the first page; later pages just extend the six catalogue rows.
        if (catalogPage == 1) {
            movieGenres.forEach { (label, genre) ->
                jobs += "أفلام $label" to listPath("movies", 1, "popularity", genre)
            }
            showGenres.forEach { (label, genre) ->
                jobs += "مسلسلات $label" to listPath("shows", 1, "popularity", genre)
            }
        }

        val lists = mutableListOf<HomePageList>()
        for (batch in jobs.chunked(ROW_BATCH_SIZE)) {
            val fetched = coroutineScope {
                batch.map { (label, path) -> async { label to fetchRow(path) } }.awaitAll()
            }
            for ((label, items) in fetched) {
                if (items.isNotEmpty()) lists += HomePageList(label, items, true)
            }
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    private suspend fun fetchRow(path: String): List<SearchResponse> = try {
        val text = app.get(apiUrl(path), headers = apiHeaders()).text
        JSONObject(text).optJSONArray("results")?.toSearchList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        return try {
            val path = "search?search=" + URLEncoder.encode(trimmed, "UTF-8") +
                "&page=1&lang=$API_LANG"
            val root = JSONObject(app.get(apiUrl(path), headers = apiHeaders()).text)
            root.optJSONArray("results")?.toSearchList().orEmpty()
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
        val id = obj.optInt("tmdbId", 0)
        if (id <= 0) return null
        val title = obj.optString("title").ifBlank { obj.optString("name") }.ifBlank { null }
            ?: return null
        val isShow = obj.optString("type").equals("show", true)
        val url = if (isShow) "$mainUrl/s/$id" else "$mainUrl/m/$id"
        val poster = image(obj.optString("posterPath"))
        val year = yearFrom(obj.optString("releaseDate").ifBlank { obj.optString("firstAirDate") })
        val score = obj.optDouble("voteAverage", 0.0).takeIf { it > 0.0 }?.let { Score.from10(it) }

        return if (isShow) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    // ---------------------------------------------------------------- details

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url) ?: return null
        val kind = if (ref.type == "show") "shows" else "movies"
        val obj = try {
            JSONObject(app.get(apiUrl("$kind/${ref.id}?lang=$API_LANG"), headers = apiHeaders()).text)
        } catch (_: Exception) {
            null
        } ?: throw ErrorLoadingException("NovaHD: failed to load details")

        val title = obj.optString("title").ifBlank { obj.optString("name") }.ifBlank { null }
            ?: throw ErrorLoadingException("NovaHD: missing title")
        val poster = image(obj.optString("posterPath"))
        val backdrop = image(obj.optString("backdropPath"), BACKDROP_BASE)
        val year = yearFrom(obj.optString("releaseDate").ifBlank { obj.optString("firstAirDate") })
        val plot = obj.optString("overview").ifBlank { null }
        val score = obj.optDouble("voteAverage", 0.0).takeIf { it > 0.0 }?.let { Score.from10(it) }
        val tags = genresOf(obj).map { arabicGenre(it) }.distinct().ifEmpty { null }
        val recommendations = fetchRecommendations(ref)

        if (ref.type != "show") {
            val data = dataJson(
                mapOf(
                    "u" to "$mainUrl/watch/m/${ref.id}",
                    "t" to title,
                    "y" to year,
                )
            )
            return newMovieLoadResponse(title, "$mainUrl/m/${ref.id}", TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.backgroundPosterUrl = backdrop
                this.duration = obj.optInt("runtime", 0).takeIf { it > 0 }
                this.recommendations = recommendations
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasonNames = mutableListOf<SeasonData>()
        val seasons = obj.optJSONArray("seasons")
        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val season = seasons.optJSONObject(i) ?: continue
                val seasonNumber = season.optInt("seasonNumber", i + 1)
                val list = season.optJSONArray("episodes")
                // Some responses only carry the episode count; fall back to generated entries.
                val total = list?.length()?.takeIf { it > 0 }
                    ?: season.optInt("episodeCount", 0)
                var added = 0
                for (j in 0 until total) {
                    val ep = list?.optJSONObject(j)
                    val episodeNumber = ep?.optInt("episodeNumber", j + 1) ?: (j + 1)
                    if (episodeNumber <= 0) continue
                    val data = dataJson(
                        mapOf(
                            "u" to "$mainUrl/watch/s/${ref.id}/$seasonNumber/$episodeNumber",
                            "t" to title,
                            "y" to year,
                            "s" to seasonNumber,
                            "e" to episodeNumber,
                        )
                    )
                    episodes += newEpisode(data) {
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.name = ep?.optString("title")?.ifBlank { null }
                        this.posterUrl = image(ep?.optString("stillPath"))
                        this.description = ep?.optString("overview")?.ifBlank { null }
                    }
                    added++
                }
                if (added > 0) seasonNames += SeasonData(seasonNumber, seasonLabel(seasonNumber), null)
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("NovaHD: no episodes")

        return newTvSeriesLoadResponse(title, "$mainUrl/s/${ref.id}", TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = score
            this.backgroundPosterUrl = backdrop
            this.recommendations = recommendations
            if (seasonNames.isNotEmpty()) this.seasonNames = seasonNames
        }
    }

    private suspend fun fetchRecommendations(ref: MediaRef): List<SearchResponse>? = try {
        val path = "recommendations?type=${ref.type}&tmdbId=${ref.id}&lang=$API_LANG"
        JSONObject(app.get(apiUrl(path), headers = apiHeaders()).text)
            .optJSONArray("results")
            ?.toSearchList()
            ?.ifEmpty { null }
    } catch (_: Exception) {
        null
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
        val watchUrl = info?.optString("u")?.ifBlank { null } ?: data
        val ref = parseRef(watchUrl) ?: return false

        val title = info?.optString("t")?.ifBlank { null }
        val year = info?.optInt("y", 0)?.takeIf { it > 0 }
        val season = info?.optInt("s", 0)?.takeIf { it > 0 } ?: ref.season
        val episode = info?.optInt("e", 0)?.takeIf { it > 0 } ?: ref.episode

        val path = if (ref.type == "show") {
            "sources?type=show&tmdbId=${ref.id}&season=${season ?: 1}&episode=${episode ?: 1}&lang=$API_LANG"
        } else {
            "sources?type=movie&tmdbId=${ref.id}&lang=$API_LANG"
        }

        val root = try {
            JSONObject(app.get(apiUrl(path), headers = apiHeaders()).text)
        } catch (_: Exception) {
            null
        } ?: return false

        val emitted = mutableSetOf<String>()
        val addSubtitle = { rawUrl: String ->
            val absolute = absUrl(rawUrl)
            if (absolute.isNotBlank() && emitted.add(absolute)) {
                subtitleCallback(
                    SubtitleFile("Arabic", absolute).apply {
                        this.headers = mapOf("User-Agent" to BROWSER_UA)
                    }
                )
            }
        }

        var found = false
        val sources = root.optJSONArray("sources")
        if (sources != null) {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val streamUrl = source.optString("url").ifBlank { null } ?: continue
                val provider = source.optString("provider").ifBlank { "NovaHD" }
                val quality = source.optString("quality")
                val label = source.optString("name").ifBlank {
                    listOf(provider, quality).filter { it.isNotBlank() }.joinToString(" · ")
                }
                val typeText = source.optString("type").lowercase()
                val linkType = when {
                    typeText.contains("dash") || streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                    typeText.contains("hls") || streamUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                    else -> ExtractorLinkType.VIDEO
                }
                val linkHeaders = mutableMapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to "$mainUrl/",
                )
                source.optJSONObject("headers")?.let { extra ->
                    for (key in extra.keys()) linkHeaders[key] = extra.optString(key)
                }

                callback(
                    newExtractorLink(
                        source = "$name · $provider",
                        name = label.ifBlank { quality.ifBlank { provider } },
                        url = streamUrl,
                        type = linkType,
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = linkHeaders
                        this.quality = qualityRank(quality)
                    }
                )
                found = true
            }
        }

        val subtitles = root.optJSONArray("subtitles")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val sub = subtitles.optJSONObject(i) ?: continue
                val code = sub.optString("lang").lowercase()
                val subLabel = sub.optString("label")
                val isArabic = code.startsWith("ar") ||
                    subLabel.contains("arab", true) ||
                    subLabel.contains("عرب")
                if (!isArabic) continue
                val subUrl = sub.optString("url").ifBlank { null } ?: continue
                addSubtitle(subUrl)
            }
        }

        // NovaHD never ships an Arabic track, so fall back to SubtitleCat. The English title
        // is required because SubtitleCat indexes original titles, not the Arabic localised ones.
        if (emitted.isEmpty()) {
            val english = englishTitle(ref)
            findArabicSubtitle(english ?: title, year, season, episode)?.let(addSubtitle)
        }

        return found
    }

    // ---------------------------------------------------------------- arabic subs

    private suspend fun englishTitle(ref: MediaRef): String? = try {
        val kind = if (ref.type == "show") "shows" else "movies"
        val obj = JSONObject(app.get(apiUrl("$kind/${ref.id}?lang=$EN_LANG"), headers = apiHeaders()).text)
        obj.optString("title").ifBlank { null }
    } catch (_: Exception) {
        null
    }

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
            return when {
                href.startsWith("http") -> href
                href.startsWith("/") -> SUB_SOURCE + href
                else -> "$SUB_SOURCE/$href"
            }.replace(" ", "%20")
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private data class MediaRef(
        val type: String,
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null,
    )

    /** parses both `/m/{id}` / `/s/{id}` and `/watch/m/{id}` / `/watch/s/{id}/{season}/{episode}` */
    private fun parseRef(url: String): MediaRef? {
        val match = Regex("""/(?:watch/)?([ms])/(\d+)(?:/(\d+)/(\d+))?""").find(url) ?: return null
        val id = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return MediaRef(
            type = if (match.groupValues[1] == "s") "show" else "movie",
            id = id,
            season = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 },
            episode = match.groupValues[4].toIntOrNull()?.takeIf { it > 0 },
        )
    }

    private fun apiUrl(path: String): String = "$mainUrl/api/$path"

    private fun absUrl(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> mainUrl + path
        else -> "$mainUrl/$path"
    }

    private fun listPath(kind: String, page: Int, sort: String, genre: String? = null): String {
        val builder = StringBuilder("$kind?page=$page&sort=$sort&lang=$API_LANG")
        if (!genre.isNullOrBlank()) {
            builder.append("&genre=").append(URLEncoder.encode(genre, "UTF-8"))
        }
        return builder.toString()
    }

    private fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "x-nova-visitor" to visitorId,
    )

    private fun pageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private fun image(path: String?, base: String = POSTER_BASE): String? {
        val value = path?.ifBlank { null } ?: return null
        return when {
            value.startsWith("http") -> value
            value.startsWith("/") -> base + value
            else -> "$base/$value"
        }
    }

    private fun yearFrom(text: String): Int? =
        Regex("""^(\d{4})""").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun genresOf(obj: JSONObject): List<String> = when (val raw = obj.opt("genres")) {
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).ifBlank { null } }
        is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun arabicGenre(name: String): String = genreNames[name] ?: name

    private fun seasonLabel(number: Int): String =
        if (number <= 0) "حلقات خاصة" else "الموسم $number"

    private fun dataJson(values: Map<String, Any?>): String {
        val json = JSONObject()
        values.forEach { (key, value) -> if (value != null) json.put(key, value) }
        return json.toString()
    }

    private fun qualityRank(text: String): Int {
        val value = text.lowercase()
        return when {
            value.contains("2160") || value.contains("4k") -> Qualities.P2160.value
            value.contains("1080") || value.contains("full hd") || value.contains("fhd") ->
                Qualities.P1080.value
            value.contains("720") || value.contains("hd") -> Qualities.P720.value
            value.contains("480") || value.contains("sd") -> Qualities.P480.value
            value.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
