package com.example.movieprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private data class ServerDef(
    val name: String,
    val url: String,
    val idType: String,
    val urlFormat: String,
    val extraParams: String,
)

class CineHD : MainAPI() {
    override var mainUrl = "https://cinehd.vc"
    override var name = "CineHD"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val SUB_SOURCE = "https://www.subtitlecat.com"
        const val TMDB_API = "https://api.themoviedb.org/3"
        const val TMDB_KEY = "ef311eb0b9b07b9c73e9fb0a732cc150"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

        const val VIDLOVE_API = "https://api.vidlove.cc"
        const val VIDLOVE_PLAYER = "https://player.vidlove.cc"

        const val ROW_BATCH_SIZE = 6
        const val MAX_ROW_ITEMS = 24
        const val EPISODE_BATCH_SIZE = 6
        const val MAX_SEASONS = 30

        const val MOVIE_SERVERS = """
Max|https://ythd.org/embed/{id}|tmdb|id|
Vidpro|https://vixsrc.to/movie/{id}|tmdb|id|
V2|https://player2.vidplus.pro/embed/movie/{id}?autoplay=true|tmdb|id|
Premium|https://player.vidplus.pro/embed/movie/{id}?autoplay=true&download=true|tmdb|id|
4K|https://player.videasy.to/movie/{id}|tmdb|id|
Vidfast|https://vidfast.vc/movie/{id}?autoplay=true|tmdb|id|
Nxsha|https://nxsha.space/embed/movie/{id}?lang=en&autoplay=true&sub=en|tmdb|id|
Super|https://vidsuper.net/movie/{id}|tmdb|id|
Vidcore|https://vidcore.net/movie/{id}?autoPlay=true&sub=en|tmdb|id|
Rock|https://vidrock.net/embed/movie/{id}?autoplay=true|tmdb|id|
Primesrc|https://primesrc.me/embed/movie?imdb={id}|imdb|id|
2Embed|https://2embed.stream/embed/movie/{id}|tmdb|id|
Cinemaos|https://cinemaos.tech/player/{id}|tmdb|id|
Prime|https://nxsha.space/embed/movie/{id}?lang=en&autoplay=true&one_server=true&server=OrVid-[Multi-Lang]|tmdb|id|
Netflix|https://nxsha.space/embed/movie/{id}?lang=en&autoplay=true&one_server=true&server=ZetPly-[Multi-Lang]|tmdb|id|
Hotstar|https://nxsha.space/embed/movie/{id}?lang=en&autoplay=true&one_server=true&server=QsPly-[Multi-Lang]|tmdb|id|
Vidnest|https://vidnest.fun/movie/{id}|tmdb|id|
Tongo|https://www.NontonGo.win/embed/movie/{id}|tmdb|id|
Echo|https://vidlink.pro/movie/{id}|tmdb|?style|?primaryColor=white&secondaryColor=white&iconColor=white&title=false&poster=true&autoplay=true
Hdmovies|/api/hdmovies/embed?type=movie&id={id}|imdb|id|
NHD|https://nhdapi.com/embed/movie/{id}?autoplay=true&autonext=true&audio=true&title=true&download=true|tmdb|id|
Mplay|https://rozgarlelo.modiplay.xyz/embed/tmdb/movie?id={id}|tmdb|id|
Xpass|https://play.xpass.top/e/movie/{id}|imdb|id|
Bravo|https://moviesapi.to/movie/{id}|tmdb|id|
Vidking|https://www.vidking.net/embed/movie/{id}?autoplay=true|tmdb|id|
111|https://111movies.net/movie/{id}|tmdb|id|
Jade|https://superflixapi.lifestyle/filme/{id}|tmdb|id|
French|https://frembed.hair/api/film.php?id={id}|tmdb|id|
Spanish|https://nxsha.space/embed/movie/{id}?lang=es&autoplay=true&sub=es|tmdb|id|
Hindi|https://nxsha.space/embed/movie/{id}?lang=hindi&autoplay=true|tmdb|id|
Tamil|https://nxsha.space/embed/movie/{id}?lang=tamil&autoplay=true|tmdb|id|
Telugu|https://nxsha.space/embed/movie/{id}?lang=telugu&autoplay=true|tmdb|id|
Arab|https://nxsha.space/embed/movie/{id}?lang=ar&autoplay=true&sub=ar|tmdb|id|
French 2|https://nxsha.space/embed/movie/{id}?lang=fr&autoplay=true&sub=fr|tmdb|id|
Brazil|https://nxsha.space/embed/movie/{id}?lang=pt&autoplay=true&sub=pt|tmdb|id|
Rus|https://nxsha.space/embed/movie/{id}?lang=ru&autoplay=true&sub=ru|tmdb|id|
German|https://nxsha.space/embed/movie/{id}?lang=de&autoplay=true&sub=de|tmdb|id|
Italy|https://vixsrc.to/movie/{id}?lang=it|tmdb|id|
Italy 2|https://nxsha.space/embed/movie/{id}?lang=it&autoplay=true&sub=it|tmdb|id|
Japan|https://nxsha.space/embed/movie/{id}?lang=ja&autoplay=true&sub=ja|tmdb|id|
Polish|https://nxsha.space/embed/movie/{id}?lang=pl&autoplay=true&sub=pl|tmdb|id|
PT|https://nxsha.space/embed/movie/{id}?lang=pt&autoplay=true&sub=pt|tmdb|id|
Thai|https://nxsha.space/embed/movie/{id}?lang=th&autoplay=true&sub=th|tmdb|id|
Turkish|https://nxsha.space/embed/movie/{id}?lang=tr&autoplay=true&sub=tr|tmdb|id|
Rive|https://www.rivestream.app/embed?type=movie&id={id}|tmdb|id|
Flicky|https://flicky.host/embed/movie/?id={id}|tmdb|id|
Peachify|https://peachify.top/embed/movie/{id}?autoplay=true&sub=English|tmdb|id|
"""

        const val TV_SERVERS = """
Max|https://ythd.org/embed/|tmdb|id/season-episode|
Vidpro|https://vixsrc.to/tv/|tmdb|id/season/episode|
V2|https://player2.vidplus.pro/embed/tv/|tmdb|id/season/episode|?autoplay=true
Premium|https://player2.vidplus.pro/embed/tv/|tmdb|id/season/episode|?autoplay=true&autonext=true&nextbutton=true&poster=true&download=true
4K|https://player.videasy.to/tv/|tmdb|id/season/episode|
Vidfast|https://vidfast.vc/tv/|tmdb|id/season/episode?autoplay=true|
Nxsha|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=en&autoplay=true&sub=en|
Super|https://vidsuper.net/tv/|tmdb|id/season/episode|
Vidcore|https://vidcore.net/tv/|tmdb|id/season/episode?autoPlay=true&sub=en|
Rock|https://vidrock.net/embed/tv/|tmdb|id/season/episode?autoplay=true&nextbutton=false&episodeselector=false|
Primesrc|https://primesrc.me/embed/tv?tmdb=|tmdb|id&season=season&episode=episode|
2Embed|https://www.2embed.stream/embed/tv/|tmdb|id/season/episode|
Cinemaos|https://cinemaos.tech/player/|tmdb|id/season/episode|
Prime|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=en&autoplay=true&one_server=true&server=OrVid-[Multi-Lang]|
Netflix|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=en&autoplay=true&one_server=true&server=ZetPly-[Multi-Lang]|
Hotstar|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=en&autoplay=true&one_server=true&server=QsPly-[Multi-Lang]|
Vidnest|https://vidnest.fun/tv/|tmdb|id/season/episode|
Tongo|https://www.NontonGo.win/embed/tv/|tmdb|id/season/episode|
Echo|https://vidlink.pro/tv/|tmdb|id/season/episode?style|?primaryColor=white&secondaryColor=white&iconColor=white&title=false&poster=true&autoplay=true
Hdmovies|/api/hdmovies/embed?type=tv&id=|imdb|id|
NHD|https://nhdapi.com/embed/tv/|tmdb|id/season/episode?autoplay=true&autonext=true&audio=true&title=true&download=true|
Mplay|https://rozgarlelo.modiplay.xyz/embed/tmdb/tv?id=|tmdb|id&s=season&e=episode|
Xpass|https://play.xpass.top/e/tv/|tmdb|id/season/episode|
Bravo|https://moviesapi.to/tv/|tmdb|id/season/episode|
Vidking|https://www.vidking.net/embed/tv/|tmdb|id/season/episode|?autoplay=true&episodeSelector=true
111|https://111movies.net/tv/|tmdb|id/season/episode|
Jade|https://superflixapi.lifestyle/serie/|tmdb|id/season/episode|
French|https://frembed.hair/api/serie.php?id=|tmdb|id&sa=season&epi=episode|
Spanish|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=es&autoplay=true&sub=es|
Hindi|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=hindi&autoplay=true|
Tamil|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=tamil&autoplay=true|
Telugu|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=telugu&autoplay=true|
Arab|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=ar&autoplay=true&sub=ar|
French 2|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=fr&autoplay=true&sub=fr|
Brazil|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=pt&autoplay=true&sub=pt|
Rus|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=ru&autoplay=true&sub=ru|
German|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=de&autoplay=true&sub=de|
Italy|https://vixsrc.to/tv/|tmdb|id/season/episode?lang=it|
Italy 2|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=it&autoplay=true&sub=it|
Japan|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=ja&autoplay=true&sub=ja|
Polish|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=pl&autoplay=true&sub=pl|
PT|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=pt&autoplay=true&sub=pt|
Thai|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=th&autoplay=true&sub=th|
Turkish|https://nxsha.space/embed/tv/|tmdb|id/season/episode?lang=tr&autoplay=true&sub=tr|
Rive|https://www.rivestream.app/embed?type=tv&id=|tmdb|id&season=season&episode=episode|
Flicky|https://flicky.host/embed/tv/?id=|tmdb|id/season/episode|
Peachify|https://peachify.top/embed/tv/|tmdb|id/season/episode?autoplay=true&sub=English|
"""
    }

    private val movieServers: List<ServerDef> by lazy { parseServers(MOVIE_SERVERS) }
    private val tvServers: List<ServerDef> by lazy { parseServers(TV_SERVERS) }

    private val countryRows = listOf(
        "United States" to "US",
        "United Kingdom" to "GB",
        "France" to "FR",
        "Spain" to "ES",
        "Turkey" to "TR",
        "South Korea" to "KR",
        "Germany" to "DE",
        "Italy" to "IT",
        "India" to "IN",
        "Egypt" to "EG",
        "Afghanistan" to "AF",
        "Iran" to "IR",
    ).flatMap { (label, code) ->
        listOf(
            "Movies ($label)" to "type=movie&country=$code",
            "TV Shows ($label)" to "type=tv&country=$code",
        )
    }

    private val mainRows = listOf(
        "Trending Movies" to "type=movie&trending=true",
        "Trending TV Shows" to "type=tv&trending=true",
        "Now Playing Movies" to "type=movie&now_playing=true",
        "Now Playing TV Shows" to "type=tv&now_playing=true",
        "Popular Movies" to "type=movie",
        "Popular TV Shows" to "type=tv",
        "Top Rated Movies" to "type=movie&sort=rating",
        "Top Rated TV Shows" to "type=tv&sort=rating",
        "Netflix Movies" to "type=movie&watch_provider=8",
        "Netflix Shows" to "type=tv&watch_provider=8",
        "Prime Video Movies" to "type=movie&watch_provider=9",
        "Prime Video Shows" to "type=tv&watch_provider=9",
        "Disney+ Movies" to "type=movie&watch_provider=337",
        "Disney+ Shows" to "type=tv&watch_provider=337",
        "Apple TV+ Shows" to "type=tv&watch_provider=350",
        "Max Shows" to "type=tv&watch_provider=1899",
    ) + countryRows

    // ---------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val lists = mutableListOf<HomePageList>()
        for (batch in mainRows.chunked(ROW_BATCH_SIZE)) {
            val fetched = coroutineScope {
                batch.map { (label, query) -> async { fetchRow(label, query) } }.awaitAll()
            }
            lists += fetched.filterNotNull()
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists)
    }

    private suspend fun fetchRow(label: String, query: String): HomePageList? {
        val defaultMedia = when {
            query.contains("type=movie") -> "movie"
            query.contains("type=tv") -> "tv"
            else -> null
        }
        val items = try {
            val text = app.get(apiUrl("search/discover?$query&page=1"), headers = apiHeaders()).text
            JSONObject(text).optJSONArray("results")?.toSearchList(defaultMedia)
        } catch (_: Exception) {
            null
        } ?: return null

        if (items.isEmpty()) return null
        return HomePageList(label, items.take(MAX_ROW_ITEMS), true)
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return newSearchResponseList(emptyList(), false)

        val root = try {
            val path = "search/discover?q=" + URLEncoder.encode(trimmed, "UTF-8") + "&page=$page"
            JSONObject(app.get(apiUrl(path), headers = apiHeaders()).text)
        } catch (_: Exception) {
            null
        }
        val items = root?.optJSONArray("results")?.toSearchList() ?: emptyList()
        val totalPages = root?.optInt("total_pages", 0) ?: 0
        return newSearchResponseList(items, items.isNotEmpty() && page < totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    private fun JSONArray.toSearchList(defaultMedia: String? = null): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            searchResponse(obj, defaultMedia)?.let { out += it }
        }
        return out
    }

    private fun searchResponse(obj: JSONObject, defaultMedia: String? = null): SearchResponse? {
        val title = obj.optString("title").ifBlank { obj.optString("name") }.ifBlank { null }
            ?: return null
        val id = obj.optInt("id", -1).takeIf { it > 0 } ?: return null
        val media = obj.optString("media_type").ifBlank { null }
            ?: defaultMedia
            ?: if (obj.optString("title").isBlank()) "tv" else "movie"
        val isTv = media == "tv"
        val url = "$mainUrl/${if (isTv) "tv" else "movie"}/$id"
        val poster = image(obj.optString("poster_path"))
        val year = yearFrom(
            obj.optString("release_date").ifBlank { obj.optString("first_air_date") }
        )
        val score = obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { Score.from10(it) }

        return if (isTv) {
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
        val match = Regex("""/(movie|tv)/(\d+)""").find(url)
            ?: throw ErrorLoadingException("CineHD: unsupported url")
        val kind = match.groupValues[1]
        val id = match.groupValues[2].toIntOrNull()
            ?: throw ErrorLoadingException("CineHD: bad id")
        val isTv = kind == "tv"
        val append = if (isTv) {
            "external_ids,videos,recommendations,content_ratings"
        } else {
            "external_ids,videos,recommendations,release_dates"
        }

        val meta = tmdbGet("$kind/$id", "&append_to_response=$append")
            ?: throw ErrorLoadingException("CineHD: no details")

        val title = meta.optString("title").ifBlank { meta.optString("name") }.ifBlank { null }
            ?: throw ErrorLoadingException("CineHD: no title")
        val poster = image(meta.optString("poster_path"))
        val backdrop = image(meta.optString("backdrop_path"))
        val year = yearFrom(meta.optString(if (isTv) "first_air_date" else "release_date"))
        val plot = meta.optString("overview").ifBlank { null }
        val score = meta.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { Score.from10(it) }
        val tags = namesOf(meta.optJSONArray("genres"))
        val runtime = meta.optInt("runtime", 0).takeIf { it > 0 }
        val contentRating = certificationOf(meta, isTv)
        val trailers = trailersOf(meta)
        val recommendations = meta.optJSONObject("recommendations")?.optJSONArray("results")
            ?.toSearchList(if (isTv) "tv" else "movie")?.ifEmpty { null }
        val imdbId = meta.optJSONObject("external_ids")?.optString("imdb_id")?.ifBlank { null }

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
            return newMovieLoadResponse(title, "$mainUrl/movie/$id", TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.duration = runtime
                this.contentRating = contentRating
                this.backgroundPosterUrl = backdrop
                this.trailers = trailers
                this.recommendations = recommendations
            }
        }

        val seasonEntries = meta.optJSONArray("seasons")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.orEmpty()

        val episodes = fetchEpisodes(id, seasonEntries, title, year)
        if (episodes.isEmpty()) throw ErrorLoadingException("CineHD: no episodes")

        val seasonNames = seasonEntries.mapNotNull { entry ->
            val number = entry.optInt("season_number", Int.MIN_VALUE)
            if (number == Int.MIN_VALUE) null
            else SeasonData(number, entry.optString("name").ifBlank { null }, null)
        }.ifEmpty { null }

        return newTvSeriesLoadResponse(title, "$mainUrl/tv/$id", TvType.TvSeries, episodes) {
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
        id: Int,
        seasons: List<JSONObject>,
        title: String,
        year: Int?,
    ): List<Episode> {
        val seasonNumbers = seasons.mapNotNull { entry ->
            val number = entry.optInt("season_number", Int.MIN_VALUE)
            val count = entry.optInt("episode_count", 0)
            if (number == Int.MIN_VALUE || number < 1 || count <= 0) null else number
        }.distinct().sorted().take(MAX_SEASONS)

        val out = mutableListOf<Episode>()
        for (batch in seasonNumbers.chunked(EPISODE_BATCH_SIZE)) {
            val fetched = coroutineScope {
                batch.map { season -> async { fetchSeasonEpisodes(id, season, title, year) } }.awaitAll()
            }
            fetched.forEach { out += it }
        }
        if (out.isNotEmpty()) return out

        for (entry in seasons) {
            val number = entry.optInt("season_number", Int.MIN_VALUE)
            val count = entry.optInt("episode_count", 0)
            if (number == Int.MIN_VALUE || number < 1 || count <= 0) continue
            for (episode in 1..count) {
                out += newEpisode(episodeData(id, title, year, number, episode)) {
                    this.season = number
                    this.episode = episode
                }
            }
        }
        return out
    }

    private suspend fun fetchSeasonEpisodes(
        id: Int,
        season: Int,
        title: String,
        year: Int?,
    ): List<Episode> {
        val meta = tmdbGet("tv/$id/season/$season") ?: return emptyList()
        val arr = meta.optJSONArray("episodes") ?: return emptyList()

        val out = mutableListOf<Episode>()
        for (i in 0 until arr.length()) {
            val episode = arr.optJSONObject(i) ?: continue
            val number = episode.optInt("episode_number", 0)
            if (number <= 0) continue

            val data = episodeData(id, title, year, season, number)
            val poster = image(episode.optString("still_path"))
            val description = episode.optString("overview").ifBlank { null }
            val name = episode.optString("name").ifBlank { null }
            val score = episode.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
                ?.let { Score.from10(it) }
            val date = airDateMillis(episode.optString("air_date"))
            val runTime = episode.optInt("runtime", 0).takeIf { it > 0 }

            out += newEpisode(data) {
                this.season = season
                this.episode = number
                this.name = name
                this.posterUrl = poster
                this.description = description
                if (score != null) this.score = score
                if (date != null) this.date = date
                if (runTime != null) this.runTime = runTime
            }
        }
        return out
    }

    private fun episodeData(id: Int, title: String, year: Int?, season: Int, episode: Int): String =
        dataJson(
            mapOf(
                "i" to id,
                "k" to "tv",
                "t" to title,
                "y" to (year ?: 0),
                "s" to season,
                "e" to episode,
            )
        )

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
        val id = info?.optInt("i", 0)?.takeIf { it > 0 } ?: return false
        val kind = info?.optString("k")?.ifBlank { null } ?: "movie"
        val title = info?.optString("t")?.ifBlank { null }
        val year = info?.optInt("y", 0)?.takeIf { it > 0 }
        val season = info?.optInt("s", 0)?.takeIf { it > 0 }
        val episode = info?.optInt("e", 0)?.takeIf { it > 0 }
        val imdbId = info?.optString("imdb")?.ifBlank { null }

        val isTv = kind == "tv"
        if (isTv && (season == null || episode == null)) return false

        val defs = if (isTv) tvServers else movieServers
        var emitted = 0
        var arabicAdded = false
        var vixSt = "-"
        var vidSt = "-"
        var modSt = "-"

        val emit: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback(link)
        }
        val emitSub: (SubtitleFile) -> Unit = { file ->
            if (file.lang.contains("Arabic", true)) arabicAdded = true
            subtitleCallback(file)
        }

        for (def in defs) {
            val url = if (isTv) {
                buildTvUrl(def, id, season!!, episode!!)
            } else {
                buildMovieUrl(def, id, imdbId)
            } ?: continue
            if (!isResolvedHost(url)) continue

            val before = emitted
            val status = try {
                resolveServer(def, url, id, season, episode, emitSub, emit) ?: continue
            } catch (e: Exception) {
                "x:" + shortError(e)
            }
            val st = if (emitted > before) {
                "ok"
            } else if (status == "ok") {
                "e:none"
            } else {
                status
            }
            val host = hostOf(url)
            when {
                host.endsWith("vixsrc.to") -> vixSt = st
                host.endsWith("111movies.net") || host.endsWith("vidlove.cc") -> vidSt = st
                host.endsWith("modiplay.xyz") -> modSt = st
            }
        }

        for (def in defs) {
            val url = if (isTv) {
                buildTvUrl(def, id, season!!, episode!!)
            } else {
                buildMovieUrl(def, id, imdbId)
            } ?: continue
            if (isResolvedHost(url)) continue

            try {
                loadExtractor(url, "$mainUrl/", emitSub, emit)
            } catch (_: Exception) {
                // no registered extractor can play it
            }
        }

        val broken = listOf("vix" to vixSt, "vid" to vidSt, "mod" to modSt)
            .filter { it.second != "-" && it.second != "ok" }
        if (broken.isNotEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "DIAG " + broken.joinToString(" ") { "${it.first}=${it.second}" },
                    url = "$mainUrl/",
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                }
            )
        }

        if (!arabicAdded) {
            val arabic = findArabicSubtitle(title, year, season, episode)
            if (arabic != null) {
                subtitleCallback(
                    SubtitleFile("Arabic", arabic).apply {
                        this.headers = mapOf("User-Agent" to BROWSER_UA)
                    }
                )
            }
        }

        return emitted > 0
    }

    private fun shortError(e: Throwable): String {
        val n = e::class.java.simpleName
        return when {
            n.contains("Cloudflare", true) -> "CF"
            n.contains("Timeout", true) || n.contains("Interrupted", true) -> "TO"
            n.contains("UnknownHost", true) -> "DNS"
            n.contains("SSL", true) || n.contains("Certificate", true) -> "SSL"
            n.contains("Connect", true) -> "CN"
            n.contains("Json", true) -> "JS"
            n.contains("Http", true) -> "HTTP"
            else -> n.take(9).ifBlank { "?" }
        }
    }

    private fun isResolvedHost(url: String): Boolean {
        val host = hostOf(url)
        return host.endsWith("vixsrc.to") ||
            host.endsWith("111movies.net") ||
            host.endsWith("vidlove.cc") ||
            host.endsWith("modiplay.xyz")
    }

    private suspend fun resolveServer(
        def: ServerDef,
        url: String,
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): String? {
        val host = hostOf(url)
        return when {
            host.endsWith("vixsrc.to") ->
                resolveVixsrc(url, def.name, callback)

            host.endsWith("111movies.net") || host.endsWith("vidlove.cc") ->
                resolveVidlove(tmdbId, season, episode, def.name, subtitleCallback, callback)

            host.endsWith("modiplay.xyz") ->
                resolveModiplay(url, subtitleCallback, callback)

            else -> null
        }
    }

    private fun hostOf(url: String): String =
        Regex("""^https?://([^/:?]+)""").find(url)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun originOf(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: ""

    private fun embedHeaders(referer: String): Map<String, String> {
        val origin = originOf(referer)
        return buildMap {
            put("User-Agent", BROWSER_UA)
            put("Accept", "*/*")
            put("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
            put("Referer", referer)
            if (origin.isNotBlank()) put("Origin", origin)
        }
    }

    private suspend fun fetchText(url: String, referer: String): String {
        var error: Exception? = null
        repeat(2) {
            try {
                return app.get(url, headers = embedHeaders(referer)).text
            } catch (e: Exception) {
                error = e
            }
        }
        throw error ?: IllegalStateException("no response")
    }

    /**
     * vixsrc resolves the TMDB id through its own API, then the signed playlist
     * URL out of the player page (the token/expires pair has to be appended).
     */
    private suspend fun resolveVixsrc(
        pageUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): String {
        val origin = originOf(pageUrl)
        if (origin.isBlank()) return "x:url"

        val tv = Regex("""/tv/(\d+)/(\d+)/(\d+)""").find(pageUrl)
        val movie = Regex("""/movie/(\d+)""").find(pageUrl)
        val apiPath = when {
            tv != null -> "api/tv/${tv.groupValues[1]}/${tv.groupValues[2]}/${tv.groupValues[3]}"
            movie != null -> "api/movie/${movie.groupValues[1]}"
            else -> return "x:url"
        }

        val apiText = try {
            fetchText("$origin/$apiPath", pageUrl)
        } catch (e: Exception) {
            return "x:" + shortError(e)
        }
        if (apiText.isBlank()) return "e:empty"
        val src = try {
            JSONObject(apiText).optString("src").ifBlank { null }
        } catch (e: Exception) {
            return if (apiText.trimStart().startsWith("<")) "h:html" else "j:" + shortError(e)
        } ?: return "j:nosrc"

        val embedUrl = origin + src
        val html = try {
            fetchText(embedUrl, pageUrl)
        } catch (e: Exception) {
            return "p:" + shortError(e)
        }
        if (html.isBlank()) return "p:empty"

        val streams = Regex("""window\.streams\s*=\s*(\[[\s\S]*?\])\s*;""").find(html)?.groupValues?.get(1)
        val streamUrl = streams?.let {
            Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1)?.replace("\\/", "/")
        }
        val master = Regex("""window\.masterPlaylist\s*=\s*\{([\s\S]*?)\}\s*;""").find(html)?.groupValues?.get(1)
        val token = master?.let { Regex("""['"]token['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }
        val expires = master?.let { Regex("""['"]expires['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }
        val masterUrl = master?.let { Regex("""\burl\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }

        if (streamUrl == null && masterUrl == null) return "m:none"
        if (token == null || expires == null) return "t:missing"

        val base = streamUrl ?: masterUrl!!
        val params = mutableListOf("token=$token", "expires=$expires", "asn=")
        if (Regex("""window\.canPlayFHD\s*=\s*true""").containsMatchIn(html)) params += "h=1"
        val finalUrl = base + (if (base.contains("?")) "&" else "?") + params.joinToString("&")

        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = finalUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "$origin/"
                this.headers = mapOf("Referer" to "$origin/", "User-Agent" to BROWSER_UA)
            }
        )
        return "ok"
    }

    /**
     * 111movies / vidlove exposes a JSON API whose `source.url` already is a
     * valid master playlist, plus a subtitle endpoint per title.
     */
    private suspend fun resolveVidlove(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): String {
        val isTv = season != null && episode != null
        val api = if (isTv) {
            "$VIDLOVE_API/tv?id=$tmdbId&season=$season&episode=$episode&mode=json"
        } else {
            "$VIDLOVE_API/movie?id=$tmdbId&mode=json"
        }

        val body = try {
            fetchText(api, "$VIDLOVE_PLAYER/")
        } catch (e: Exception) {
            return "x:" + shortError(e)
        }
        if (body.isBlank()) return "e:empty"
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return if (body.trimStart().startsWith("<")) "h:html" else "j:" + shortError(e)
        }

        val source = json.optJSONObject("source")
        val streamUrl = source?.optString("url")?.ifBlank { null }
            ?: json.optString("manifest").ifBlank { null }?.let { manifest ->
                manifest.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
            }
        if (streamUrl == null) return "j:nosrc"

        val serverLabel = source?.optString("label")?.ifBlank { null }
        val origin = originOf(VIDLOVE_PLAYER)

        callback(
            newExtractorLink(
                source = name,
                name = if (serverLabel == null) label else "$label - $serverLabel",
                url = streamUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "$origin/"
                this.headers = mapOf("Referer" to "$origin/", "User-Agent" to BROWSER_UA)
            }
        )

        val arabic = arabicSubtitle(json.optJSONArray("subtitles")) ?: run {
            val path = if (isTv) {
                "subtitles/tv/$tmdbId/$season/$episode"
            } else {
                "subtitles/movie/$tmdbId"
            }
            val list = try {
                JSONArray(fetchText("$VIDLOVE_API/$path", "$VIDLOVE_PLAYER/"))
            } catch (_: Exception) {
                null
            }
            arabicSubtitle(list)
        }
        if (arabic != null) subtitleCallback(arabic)

        return "ok"
    }

    private fun arabicSubtitle(array: JSONArray?): SubtitleFile? {
        if (array == null) return null
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val label = entry.optString("label")
            val url = entry.optString("file").ifBlank { null } ?: continue
            if (label.contains("Arabic", true) || url.contains("Arabic", true)) {
                return SubtitleFile("Arabic", url).apply {
                    this.headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to "$VIDLOVE_PLAYER/")
                }
            }
        }
        return null
    }

    /** Modplay lists its real hosts right in the page, hand them to the app extractors. */
    private suspend fun resolveModiplay(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): String {
        val html = try {
            fetchText(pageUrl, pageUrl)
        } catch (e: Exception) {
            return "x:" + shortError(e)
        }
        if (html.isBlank()) return "e:empty"
        val origin = originOf(pageUrl)
        val servers = Regex("""https?://[^\s"'<>]+""")
            .findAll(html)
            .map { it.value.trimEnd(',', ';', ')', ']') }
            .filter { server ->
                val host = hostOf(server)
                host.isNotBlank() &&
                    host != hostOf(origin) &&
                    !host.endsWith("tmdb.org") &&
                    !host.endsWith("cloudflare.com") &&
                    !server.contains("cdn-cgi")
            }
            .distinct()
            .toList()

        if (servers.isEmpty()) return "r:none"

        for (server in servers) {
            try {
                loadExtractor(server, pageUrl, subtitleCallback, callback)
            } catch (_: Exception) {
                continue
            }
        }
        return "ok"
    }

    private fun buildMovieUrl(def: ServerDef, tmdbId: Int, imdbId: String?): String? {
        val id = if (def.idType == "imdb") {
            imdbId?.takeIf { it.isNotBlank() } ?: return null
        } else {
            tmdbId.toString()
        }
        var url = if (def.urlFormat.isNotBlank()) def.url.replace("{id}", id) else def.url + id
        if (url.contains("{id}")) url = url.replace("{id}", id)
        if (def.extraParams.isNotBlank()) url += def.extraParams
        return absUrl(url)
    }

    private fun buildTvUrl(def: ServerDef, tmdbId: Int, season: Int, episode: Int): String? {
        val id = tmdbId.toString()
        var url = def.url + if (def.urlFormat.isNotBlank()) {
            applyFormat(def.urlFormat, id, season, episode)
        } else {
            "$id/$season/$episode"
        }
        if (def.extraParams.isNotBlank()) {
            val separator = if (url.contains("?")) "&" else "?"
            url += separator + def.extraParams.removePrefix("?").removePrefix("&")
        }
        return absUrl(url)
    }

    /**
     * Replaces the site placeholders without corrupting values that merely
     * contain the placeholder text (the site applies a global replace on the
     * whole query string, which mangles entries like `server=OrVid-...`).
     */
    private fun applyFormat(format: String, id: String, season: Int, episode: Int): String {
        val tokens = mapOf(
            "id" to id,
            "season" to season.toString(),
            "episode" to episode.toString(),
        )

        fun replaceTokens(text: String): String {
            var out = text
            for ((token, value) in tokens) out = out.replace(token, value)
            return out
        }

        fun replaceSegment(segment: String): String {
            val separator = segment.indexOf('=')
            if (separator < 0) return replaceTokens(segment)
            val key = segment.substring(0, separator)
            val value = segment.substring(separator + 1)
            return key + "=" + (tokens[value] ?: value)
        }

        fun replacePart(part: String): String =
            part.split("&").joinToString("&") { replaceSegment(it) }

        val query = format.indexOf('?')
        return if (query < 0) {
            replacePart(format)
        } else {
            replacePart(format.substring(0, query)) + "?" + replacePart(format.substring(query + 1))
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

    private fun parseServers(raw: String): List<ServerDef> =
        raw.lines().mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size < 4) return@mapNotNull null
            ServerDef(
                name = parts[0].trim(),
                url = parts[1].trim(),
                idType = parts[2].trim().lowercase(),
                urlFormat = parts[3].trim(),
                extraParams = parts.getOrElse(4) { "" }.trim(),
            )
        }.filter { it.name.isNotBlank() && it.url.isNotBlank() }

    private suspend fun tmdbGet(path: String, extra: String = ""): JSONObject? {
        val url = "$TMDB_API/$path?api_key=$TMDB_KEY&language=en-US$extra"
        return try {
            JSONObject(app.get(url, headers = tmdbHeaders()).text)
        } catch (_: Exception) {
            null
        }
    }

    private fun certificationOf(meta: JSONObject, isTv: Boolean): String? {
        if (isTv) {
            val results = meta.optJSONObject("content_ratings")?.optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val entry = results.optJSONObject(i) ?: continue
                if (entry.optString("iso_3166_1") == "US") {
                    return entry.optString("rating").ifBlank { null }
                }
            }
            return null
        }

        val results = meta.optJSONObject("release_dates")?.optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            if (entry.optString("iso_3166_1") != "US") continue
            val dates = entry.optJSONArray("release_dates") ?: continue
            for (j in 0 until dates.length()) {
                val certification = dates.optJSONObject(j)?.optString("certification")?.ifBlank { null }
                if (certification != null) return certification
            }
        }
        return null
    }

    private fun trailersOf(meta: JSONObject): MutableList<TrailerData> {
        val out = mutableListOf<TrailerData>()
        val videos = meta.optJSONObject("videos")?.optJSONArray("results") ?: return out

        val entries = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() }
            .sortedBy { if (it.optString("type") == "Trailer") 0 else 1 }

        for (entry in entries.take(3)) {
            out += TrailerData(
                extractorUrl = "https://www.youtube.com/watch?v=${entry.optString("key")}",
                referer = "$mainUrl/",
                raw = false,
                headers = emptyMap(),
            )
        }
        return out
    }

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

    private fun tmdbHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json, text/plain, */*",
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

    private fun airDateMillis(text: String?): Long? {
        val value = text?.ifBlank { null } ?: return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(value)?.time
        } catch (_: Exception) {
            null
        }
    }

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
}
