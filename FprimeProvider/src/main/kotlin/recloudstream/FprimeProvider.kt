package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FprimeProvider : MainAPI() {
    override var mainUrl = "https://db.insidethisplaceiswarm.cc/3"
    override var name = "fprime"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val astraBase = "https://serverone.insidethisplaceiswarm.cc/astra"
    private val aesKeyHex = "cc668a8304b57864709efdfc0051a619e146c0c8b486f190a06d1c1cf96af698"
    private val encryptedPrefix = "as_"

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private fun decryptAstra(encrypted: String): String {
        if (!encrypted.startsWith(encryptedPrefix)) return encrypted
        val bytes = hexToBytes(encrypted.substring(encryptedPrefix.length))
        if (bytes.size < 28) throw Exception("payload too short")
        val iv = bytes.sliceArray(0 until 12)
        val ct = bytes.sliceArray(12 until bytes.size)
        val authTag = ct.sliceArray(ct.size - 16 until ct.size)
        val ciphertext = ct.sliceArray(0 until ct.size - 16)
        val keyBytes = hexToBytes(aesKeyHex)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        cipher.updateAAD(ByteArray(0))
        val decrypted = cipher.doFinal(ciphertext + authTag)
        return String(decrypted, Charsets.UTF_8)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val trending = tryParseJson<TmdbResponse>(
            app.get("$mainUrl/trending/all/week?language=en-US").text
        )
        val movies = tryParseJson<TmdbResponse>(
            app.get("$mainUrl/trending/movie/week?language=en-US").text
        )
        return newHomePageResponse(
            listOf(
                HomePageList("Trending", trending?.results?.map { it.toSearchResponse(this) } ?: emptyList(), true),
                HomePageList("Trending Movies", movies?.results?.map { it.toSearchResponse(this) } ?: emptyList(), true)
            ),
            false
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val res = tryParseJson<TmdbResponse>(
            app.get("$mainUrl/search/multi?query=${query.encodeUri()}&language=en-US&include_adult=false&page=$page").text
        )
        return res?.results?.map { it.toSearchResponse(this) }?.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // url format: fprime://movie/{tmdbId} or fprime://tv/{tmdbId}
        val parts = url.removePrefix("fprime://").split("/")
        val type = parts.getOrElse(0) { "movie" }
        val tmdbId = parts.getOrElse(1) { return null } ?: return null
        if (type == "tv") {
            val details = tryParseJson<TmdbTvDetails>(
                app.get("$mainUrl/tv/$tmdbId?language=en-US").text
            ) ?: return null
            val episodes = mutableListOf<Episode>()
            // load first season episodes
            val seasonNum = 1
            val season = tryParseJson<TmdbSeason>(
                app.get("$mainUrl/tv/$tmdbId/season/$seasonNum?language=en-US").text
            )
            season?.episodes?.forEach { ep ->
                episodes.add(newEpisode("fprime://tv/$tmdbId/$seasonNum/${ep.episode_number}") {
                    name = ep.name
                    posterUrl = ep.still_path?.let { "https://image.tmdb.org/t/p/w342$it" }
                })
            }
            return newTvSeriesLoadResponse(
                details.name,
                url,
                TvType.TvSeries,
                episodes
            ) {
                plot = details.overview
                posterUrl = details.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                year = details.first_air_date?.take(4)?.toIntOrNull()
            }
        } else {
            val details = tryParseJson<TmdbMovieDetails>(
                app.get("$mainUrl/movie/$tmdbId?language=en-US").text
            ) ?: return null
            return newMovieLoadResponse(
                details.title,
                url,
                TvType.Movie,
                tmdbId
            ) {
                plot = details.overview
                posterUrl = details.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                year = details.release_date?.take(4)?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.removePrefix("fprime://").split("/")
        val type = parts.getOrElse(0) { "movie" }
        val tmdbId = parts.getOrElse(1) { return false }
        val season = parts.getOrElse(2) { "1" }
        val episode = parts.getOrElse(3) { "1" }
        val params = if (type == "tv") {
            "tmdbId=$tmdbId&type=tv&seasonId=$season&episodeId=$episode"
        } else {
            "tmdbId=$tmdbId&type=movie"
        }
        try {
            val r = app.get("$astraBase?$params")
            val json = tryParseJson<AstraResponse>(r.text) ?: return false
            val encrypted = json.sources?.Astra?.url ?: return false
            val decrypted = decryptAstra(encrypted)
            callback(
                newExtractorLink(name, name, decrypted) {
                    quality = Qualities.Unknown.value
                    type = ExtractorLinkType.HLS
                    referer = ""
                }
            )
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // Data classes
    private data class TmdbResult(
        val id: Int,
        val title: String?,
        val name: String?,
        val poster_path: String?,
        val media_type: String?,
        val release_date: String?,
        val first_air_date: String?
    ) {
        fun toSearchResponse(provider: FprimeProvider): SearchResponse {
            val isTv = media_type == "tv" || name != null && title == null
            val tmdbId = id.toString()
            val type = if (isTv) TvType.TvSeries else TvType.Movie
            val url = if (isTv) "fprime://tv/$tmdbId" else "fprime://movie/$tmdbId"
            return provider.newMovieSearchResponse(
                title ?: name ?: "Unknown",
                url,
                type
            ) {
                posterUrl = poster_path?.let { "https://image.tmdb.org/t/p/w342$it" }
            }
        }
    }

    private data class TmdbResponse(val results: List<TmdbResult>?)

    private data class TmdbMovieDetails(
        val title: String,
        val overview: String?,
        val poster_path: String?,
        val release_date: String?
    )

    private data class TmdbTvDetails(
        val name: String,
        val overview: String?,
        val poster_path: String?,
        val first_air_date: String?
    )

    private data class TmdbEpisode(
        val episode_number: Int,
        val name: String,
        val still_path: String?
    )

    private data class TmdbSeason(val episodes: List<TmdbEpisode>?)

    private data class AstraSource(val url: String?, val label: String?, val type: String?)
    private data class AstraSources(val Astra: AstraSource?)
    private data class AstraResponse(val sources: AstraSources?)
}
