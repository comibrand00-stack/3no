package com.example.cinehdlive

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class CineHdLiveProvider : MainAPI() {
    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_STREAMS = 15
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        private const val CACHE_FILE = "cinehd-live-catalog.json"

        private val catalogMutex = Mutex()
        private val whitespaceRegex = Regex("\\s+")

        @Volatile
        private var cachedCatalog: CineHdCatalog? = null

        @Volatile
        private var cachedAt: Long = 0L

        @Volatile
        var cacheDir: File? = null

        private val ARAB_COUNTRIES = setOf(
            "SA", "AE", "EG", "QA", "KW", "BH", "OM", "JO", "LB", "SY", "IQ",
            "PS", "YE", "SD", "LY", "TN", "DZ", "MA", "MR", "SO", "DJ", "KM",
        )

        private val ARAB_COUNTRY_NAMES = mapOf(
            "SA" to "السعودية",
            "AE" to "الإمارات",
            "EG" to "مصر",
            "QA" to "قطر",
            "KW" to "الكويت",
            "BH" to "البحرين",
            "OM" to "عُمان",
            "JO" to "الأردن",
            "LB" to "لبنان",
            "SY" to "سوريا",
            "IQ" to "العراق",
            "PS" to "فلسطين",
            "YE" to "اليمن",
            "SD" to "السودان",
            "LY" to "ليبيا",
            "TN" to "تونس",
            "DZ" to "الجزائر",
            "MA" to "المغرب",
            "MR" to "موريتانيا",
            "SO" to "الصومال",
            "DJ" to "جيبوتي",
            "KM" to "جزر القمر",
        )

        private val CATEGORY_NAMES = mapOf(
            "auto" to "سيارات",
            "animation" to "أنيميشن",
            "business" to "اقتصاد",
            "classic" to "كلاسيك",
            "comedy" to "كوميديا",
            "cooking" to "طبخ",
            "culture" to "ثقافة",
            "documentary" to "وثائقيات",
            "education" to "تعليم",
            "entertainment" to "ترفيه",
            "family" to "عائلة",
            "general" to "عامة",
            "interactive" to "تفاعلي",
            "kids" to "أطفال",
            "legislative" to "تشريعي",
            "lifestyle" to "أسلوب حياة",
            "movies" to "أفلام",
            "music" to "موسيقى",
            "news" to "أخبار",
            "outdoor" to "خارجي",
            "public" to "عام",
            "relax" to "استرخاء",
            "religious" to "دينية",
            "series" to "مسلسلات",
            "science" to "علوم",
            "shop" to "تسوق",
            "sports" to "رياضة",
            "travel" to "سفر",
            "weather" to "طقس",
        )

        private val REGIONS: Map<String, Set<String>> = linkedMapOf(
            "region:europe" to setOf(
                "AD", "AL", "AT", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE", "DK",
                "EE", "ES", "FI", "FO", "FR", "GB", "GG", "GI", "GR", "HR", "HU", "IE",
                "IS", "IT", "JE", "LI", "LT", "LU", "LV", "MC", "MD", "ME", "MK", "MT",
                "NL", "NO", "PL", "PT", "RO", "RS", "RU", "SE", "SI", "SK", "SM", "UA",
                "VA", "XK", "UK",
            ),
            "region:asia" to setOf(
                "AF", "AM", "AZ", "BD", "BN", "BT", "CN", "GE", "HK", "ID", "IL", "IN",
                "IR", "JP", "KG", "KH", "KP", "KR", "KZ", "LA", "LK", "MM", "MN", "MO",
                "MV", "MY", "NP", "PH", "PK", "SG", "TH", "TJ", "TL", "TM", "TR", "TW",
                "UZ", "VN",
            ),
            "region:africa" to setOf(
                "AO", "BF", "BI", "BJ", "BW", "CD", "CF", "CG", "CI", "CM", "CV", "EH",
                "ER", "ET", "GA", "GH", "GM", "GN", "GQ", "GW", "KE", "LR", "LS", "MG",
                "ML", "MU", "MW", "MZ", "NA", "NE", "NG", "RE", "RW", "SC", "SH", "SL",
                "SN", "SS", "ST", "SZ", "TD", "TG", "TZ", "UG", "YT", "ZA", "ZM", "ZW",
            ),
            "region:north-america" to setOf(
                "AG", "AI", "AW", "BB", "BL", "BM", "BQ", "BS", "BZ", "CA", "CR", "CU",
                "CW", "DM", "DO", "GD", "GL", "GP", "GT", "HN", "HT", "JM", "KN", "KY",
                "LC", "MF", "MQ", "MS", "MX", "NI", "PA", "PM", "PR", "SV", "SX", "TC",
                "TT", "US", "VC", "VG", "VI",
            ),
            "region:south-america" to setOf(
                "AR", "BO", "BR", "CL", "CO", "EC", "FK", "GF", "GY", "PE", "PY", "SR",
                "UY", "VE",
            ),
            "region:oceania" to setOf(
                "AS", "AU", "CK", "FJ", "FM", "GU", "KI", "MH", "MP", "NC", "NF", "NR",
                "NU", "NZ", "PF", "PG", "PN", "PW", "SB", "TK", "TO", "TV", "UM", "VU",
                "WF", "WS",
            ),
        )
    }

    override var mainUrl = "https://cinehd.vc"
    override var name = "CineHD Live"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    override val mainPage = mainPageOf(
        "arabic" to "قنوات عربية",
        "country:SA" to "السعودية",
        "country:AE" to "الإمارات",
        "country:EG" to "مصر",
        "country:IQ" to "العراق",
        "country:QA" to "قطر",
        "country:KW" to "الكويت",
        "country:BH" to "البحرين",
        "country:OM" to "عُمان",
        "country:JO" to "الأردن",
        "country:LB" to "لبنان",
        "country:SY" to "سوريا",
        "country:PS" to "فلسطين",
        "country:YE" to "اليمن",
        "country:SD" to "السودان",
        "country:LY" to "ليبيا",
        "country:TN" to "تونس",
        "country:DZ" to "الجزائر",
        "country:MA" to "المغرب",
        "country:SO" to "الصومال",
        "category:sports" to "رياضة",
        "category:news" to "أخبار",
        "category:movies" to "أفلام",
        "category:kids" to "أطفال",
        "category:music" to "موسيقى",
        "category:religious" to "دينية",
        "category:documentary" to "وثائقيات",
        "category:entertainment" to "ترفيه",
        "category:series" to "مسلسلات",
        "category:general" to "عامة",
        "region:europe" to "أوروبا",
        "region:asia" to "آسيا",
        "region:africa" to "أفريقيا",
        "region:north-america" to "أمريكا الشمالية",
        "region:south-america" to "أمريكا الجنوبية",
        "region:oceania" to "أوقيانوسيا",
        "all" to "كل القنوات",
    )

    private val apiUrl: String
        get() = mainUrl.trimEnd('/') + "/api/live-tv"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val catalog = getCatalog()
        val filtered = when {
            request.data == "all" -> catalog.channels
            request.data == "arabic" -> catalog.channels.filter { it.isArabic() }
            request.data.startsWith("country:") -> {
                val code = request.data.substringAfter(':').uppercase(Locale.ROOT)
                catalog.channels.filter { it.country == code }
            }
            request.data.startsWith("region:") -> {
                val codes = REGIONS[request.data].orEmpty() - ARAB_COUNTRIES
                catalog.channels.filter { channel ->
                    channel.country?.let { it in codes } == true
                }
            }
            request.data.startsWith("category:") -> {
                val category = request.data.substringAfter(':')
                catalog.channels.filter { category in it.categories }
            }
            else -> catalog.channels
        }.sortedBy { it.name.lowercase(Locale.ROOT) }

        val start = ((page.coerceAtLeast(1)) - 1) * PAGE_SIZE
        val items = filtered.drop(start).take(PAGE_SIZE).map { it.toSearchResponse() }

        return newHomePageResponse(
            data = request,
            list = items,
            hasNext = start + items.size < filtered.size,
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        return getCatalog().channels.asSequence()
            .filter { channel -> channel.searchText.contains(normalizedQuery) }
            .take(100)
            .map { it.toSearchResponse() }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val catalog = getCatalog()
        val channel = catalog.byId[url] ?: return null
        val playData = CineHdPlayData(channel.name, channel.streams)

        return newLiveStreamLoadResponse(
            name = channel.name,
            url = url,
            dataUrl = encodePlayData(playData),
        ) {
            posterUrl = channel.logo
            plot = buildPlot(channel, catalog.countryNames)
            tags = channel.categories.map { CATEGORY_NAMES[it] ?: it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playData = decodePlayData(data) ?: return false

        for ((index, stream) in playData.streams.withIndex()) {
            if (stream.url.isBlank()) continue

            val type = when {
                stream.url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                stream.url.contains(".mp4", ignoreCase = true) -> ExtractorLinkType.VIDEO
                else -> ExtractorLinkType.M3U8
            }

            val label = if (playData.streams.size <= 1) {
                playData.name
            } else {
                val source = stream.quality ?: stream.feed ?: "مصدر"
                "${playData.name} • $source #${index + 1}"
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = stream.url,
                    type = type,
                ) {
                    quality = getQualityFromName(stream.quality)
                }
            )
        }

        return playData.streams.isNotEmpty()
    }

    private suspend fun getCatalog(): CineHdCatalog {
        val now = System.currentTimeMillis()
        cachedCatalog?.let { if (now - cachedAt < CACHE_TTL_MS) return it }

        return catalogMutex.withLock {
            cachedCatalog?.let {
                if (System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return@withLock it
            }

            val freshCache = readCache()
            val diskCatalog = freshCache?.let { text ->
                runCatching { parseCatalog(text) }.getOrNull()
            }
            if (diskCatalog != null) {
                cachedCatalog = diskCatalog
                cachedAt = System.currentTimeMillis()
                return@withLock diskCatalog
            }

            val fetched = runCatching {
                app.get(apiUrl, timeout = 120).text
            }.getOrNull()

            val fetchedCatalog = if (!fetched.isNullOrBlank()) {
                writeCache(fetched)
                runCatching { parseCatalog(fetched) }.getOrNull()
            } else {
                null
            }

            val catalog = fetchedCatalog
                ?: readCache(ignoreAge = true)?.let { text ->
                    runCatching { parseCatalog(text) }.getOrNull()
                }
                ?: throw ErrorLoadingException("CineHD Live: unable to load channel catalog")

            cachedCatalog = catalog
            cachedAt = System.currentTimeMillis()
            catalog
        }
    }

    private fun parseCatalog(text: String): CineHdCatalog {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw ErrorLoadingException("CineHD Live: invalid channel catalog")

        val countryNames = HashMap<String, String>()
        val countries = root.optJSONArray("countries")
        if (countries != null) {
            for (index in 0 until countries.length()) {
                val item = countries.optJSONObject(index) ?: continue
                val code = item.optString("code").uppercase(Locale.ROOT)
                if (code.isNotBlank()) {
                    countryNames[code] = item.optString("name")
                }
            }
        }

        val streamsByChannel = HashMap<String, MutableList<CineHdStream>>()
        val apiStreams = root.optJSONArray("streams")
        if (apiStreams != null) {
            for (index in 0 until apiStreams.length()) {
                val item = apiStreams.optJSONObject(index) ?: continue
                val channelId = item.optString("channel")
                val url = item.optString("url")
                if (channelId.isBlank() || url.isBlank()) continue

                streamsByChannel.getOrPut(channelId) { mutableListOf() }.add(
                    CineHdStream(
                        url = url,
                        quality = item.optString("quality").takeIf { it.isNotBlank() },
                        feed = item.optString("feed").takeIf { it.isNotBlank() },
                    )
                )
            }
        }

        val channelsArray = root.optJSONArray("channels") ?: JSONArray()
        val channels = ArrayList<CineHdChannel>(channelsArray.length())

        for (index in 0 until channelsArray.length()) {
            val item = channelsArray.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val channelName = item.optString("name")
            if (id.isBlank() || channelName.isBlank()) continue

            val streams = ArrayList<CineHdStream>()
            item.optJSONObject("stream")?.let { primary ->
                val url = primary.optString("url")
                if (url.isNotBlank()) {
                    streams.add(
                        CineHdStream(
                            url = url,
                            quality = primary.optString("quality").takeIf { it.isNotBlank() },
                            feed = primary.optString("feed").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
            streams.addAll(streamsByChannel[id].orEmpty())

            val uniqueStreams = streams
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
                .take(MAX_STREAMS)
            if (uniqueStreams.isEmpty()) continue

            val altNames = item.optJSONArray("alt_names").toStringList()
            val categories = item.optJSONArray("categories").toStringList()

            channels.add(
                CineHdChannel(
                    id = id,
                    name = channelName,
                    altNames = altNames,
                    country = item.optString("country").takeIf { it.isNotBlank() }
                        ?.uppercase(Locale.ROOT),
                    categories = categories,
                    logo = item.optString("logo").takeIf { it.isNotBlank() },
                    streams = uniqueStreams,
                    searchText = normalize(
                        buildString {
                            append(channelName)
                            altNames.forEach { append(' ').append(it) }
                        }
                    ),
                )
            )
        }

        return CineHdCatalog(
            channels = channels,
            byId = channels.associateBy { it.id },
            countryNames = countryNames,
        )
    }

    private fun encodePlayData(playData: CineHdPlayData): String {
        val streams = JSONArray()
        playData.streams.forEach { stream ->
            streams.put(
                JSONObject()
                    .put("url", stream.url)
                    .put("quality", stream.quality)
                    .put("feed", stream.feed)
            )
        }

        return JSONObject()
            .put("name", playData.name)
            .put("streams", streams)
            .toString()
    }

    private fun decodePlayData(data: String): CineHdPlayData? = runCatching {
        val root = JSONObject(data)
        val array = root.optJSONArray("streams") ?: JSONArray()
        val streams = ArrayList<CineHdStream>(array.length())

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url")
            if (url.isBlank()) continue

            streams.add(
                CineHdStream(
                    url = url,
                    quality = item.optString("quality").takeIf { it.isNotBlank() },
                    feed = item.optString("feed").takeIf { it.isNotBlank() },
                )
            )
        }

        CineHdPlayData(root.optString("name"), streams)
    }.getOrNull()

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()

        val values = ArrayList<String>(length())
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) values.add(value)
        }
        return values
    }

    private fun CineHdChannel.toSearchResponse(): SearchResponse {
        val isArabic = isArabic()

        return newLiveSearchResponse(
            name = name,
            url = id,
            fix = false,
        ) {
            posterUrl = logo
            lang = if (isArabic) "ar" else null
        }
    }

    private fun CineHdChannel.isArabic(): Boolean {
        return country?.let { it in ARAB_COUNTRIES } == true
    }

    private fun buildPlot(
        channel: CineHdChannel,
        countryNames: Map<String, String>,
    ): String {
        val code = channel.country
        val country = code?.let {
            ARAB_COUNTRY_NAMES[it] ?: countryNames[it] ?: it
        } ?: "غير معروف"
        val categories = channel.categories.map { CATEGORY_NAMES[it] ?: it }

        return buildString {
            append("بث مباشر")
            append("\nالدولة: ").append(country)
            if (categories.isNotEmpty()) {
                append("\nالتصنيف: ").append(categories.joinToString(" · "))
            }
            append("\nعدد المصادر: ").append(channel.streams.size)
        }
    }

    private fun normalize(value: String): String {
        val cleaned = buildString {
            for (char in value) {
                if (char !in '\u064B'..'\u065F' && char != '\u0670' && char != '\u0640') {
                    append(char)
                }
            }
        }

        return cleaned
            .lowercase(Locale.ROOT)
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .replace(whitespaceRegex, " ")
            .trim()
    }

    private fun readCache(ignoreAge: Boolean = false): String? {
        val dir = cacheDir ?: return null
        val file = File(dir, CACHE_FILE)
        if (!file.exists()) return null
        if (!ignoreAge && System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) {
            return null
        }
        return runCatching { file.readText() }.getOrNull()
    }

    private fun writeCache(text: String) {
        val dir = cacheDir ?: return
        runCatching { File(dir, CACHE_FILE).writeText(text) }
    }
}

data class CineHdStream(
    val url: String,
    val quality: String? = null,
    val feed: String? = null,
)

data class CineHdChannel(
    val id: String,
    val name: String,
    val altNames: List<String>,
    val country: String?,
    val categories: List<String>,
    val logo: String?,
    val streams: List<CineHdStream>,
    val searchText: String,
)

data class CineHdPlayData(
    val name: String,
    val streams: List<CineHdStream>,
)

private data class CineHdCatalog(
    val channels: List<CineHdChannel>,
    val byId: Map<String, CineHdChannel>,
    val countryNames: Map<String, String>,
)
