package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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
        val decrypted = cipher.doFinal(ciphertext + authTag)
        return String(decrypted, Charsets.UTF_8)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val trendingText = app.get("$mainUrl/trending/all/week?language=en-US").text
        val trendingJson = JSONObject(trendingText)
        val trendingArr = trendingJson.optJSONArray("results")
        val trendingList = mutableListOf<SearchResponse>()
        if (trendingArr != null) {
            for (i in 0 until trendingArr.length()) {
                val o = trendingArr.getJSONObject(i)
                val id = o.optInt("id")
                val title = o.optString("title").ifEmpty { o.optString("name") }
                val poster = o.optString("poster_path")
                val mediaType = o.optString("media_type")
                val isTv = mediaType == "tv"
                val url = if (isTv) "fprime://tv/$id" else "fprime://movie/$id"
                trendingList.add(newMovieSearchResponse(title, url, if (isTv) TvType.TvSeries else TvType.Movie) {
                    posterUrl = if (poster.isNotEmpty()) "https://image.tmdb.org/t/p/w342$poster" else null
                })
            }
        }
        return newHomePageResponse(
            listOf(HomePageList("Trending", trendingList, true)),
            false
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val text = app.get("$mainUrl/search/multi?query=${java.net.URLEncoder.encode(query, "UTF-8")}&language=en-US&include_adult=false&page=$page").text
        val json = JSONObject(text)
        val arr = json.optJSONArray("results") ?: return null
        val list = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mt = o.optString("media_type")
            if (mt != "movie" && mt != "tv") continue
            val id = o.optInt("id")
            val title = o.optString("title").ifEmpty { o.optString("name") }
            val poster = o.optString("poster_path")
            val isTv = mt == "tv"
            val url = if (isTv) "fprime://tv/$id" else "fprime://movie/$id"
            list.add(newMovieSearchResponse(title, url, if (isTv) TvType.TvSeries else TvType.Movie) {
                posterUrl = if (poster.isNotEmpty()) "https://image.tmdb.org/t/p/w342$poster" else null
            })
        }
        return list.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.removePrefix("fprime://").split("/")
        val type = parts.getOrElse(0) { "movie" }
        val tmdbId = parts.getOrElse(1) { return null } ?: return null
        if (type == "tv") {
            val text = app.get("$mainUrl/tv/$tmdbId?language=en-US").text
            val json = JSONObject(text)
            val name = json.optString("name")
            val overview = json.optString("overview")
            val poster = json.optString("poster_path")
            val firstAir = json.optString("first_air_date")
            val episodes = mutableListOf<Episode>()
            val seasonNum = 1
            try {
                val seasonText = app.get("$mainUrl/tv/$tmdbId/season/$seasonNum?language=en-US").text
                val seasonJson = JSONObject(seasonText)
                val epsArr = seasonJson.optJSONArray("episodes")
                if (epsArr != null) {
                    for (i in 0 until epsArr.length()) {
                        val ep = epsArr.getJSONObject(i)
                        val epNum = ep.optInt("episode_number")
                        val epName = ep.optString("name")
                        val still = ep.optString("still_path")
                        episodes.add(newEpisode("fprime://tv/$tmdbId/$seasonNum/$epNum") {
                            this.name = epName
                            this.posterUrl = if (still.isNotEmpty()) "https://image.tmdb.org/t/p/w342$still" else null
                        })
                    }
                }
            } catch (e: Exception) { }
            return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                plot = overview
                posterUrl = if (poster.isNotEmpty()) "https://image.tmdb.org/t/p/w500$poster" else null
                year = firstAir.take(4).toIntOrNull()
            }
        } else {
            val text = app.get("$mainUrl/movie/$tmdbId?language=en-US").text
            val json = JSONObject(text)
            val title = json.optString("title")
            val overview = json.optString("overview")
            val poster = json.optString("poster_path")
            val release = json.optString("release_date")
            return newMovieLoadResponse(title, url, TvType.Movie, tmdbId) {
                plot = overview
                posterUrl = if (poster.isNotEmpty()) "https://image.tmdb.org/t/p/w500$poster" else null
                year = release.take(4).toIntOrNull()
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
        return try {
            val r = app.get("$astraBase?$params")
            val json = JSONObject(r.text)
            val sources = json.optJSONObject("sources")
            val astra = sources?.optJSONObject("Astra")
            val encrypted = astra?.optString("url")
            if (encrypted.isNullOrEmpty()) return false
            val decrypted = decryptAstra(encrypted)
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = decrypted,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = ""
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
