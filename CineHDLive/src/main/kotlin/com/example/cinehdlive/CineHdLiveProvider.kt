package com.example.cinehdlive

import android.util.JsonReader
import android.util.JsonToken
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

class CineHdLiveProvider : MainAPI() {
    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_STREAMS = 15
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        private const val CACHE_FILE = "cinehd-live-catalog.json"
        private const val MIN_CATALOG_BYTES = 1_000L
        private const val MAX_CATALOG_BYTES = 64L * 1024 * 1024
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val MIRROR_CATALOG_URL =
            "https://raw.githubusercontent.com/comibrand00-stack/3no/catalog/catalog.json"
        private const val CATALOG_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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

    private val siteCatalogUrl: String
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

            // 1. Fresh local cache from a previous successful load.
            readCache()?.let { file ->
                runCatching { parseCatalog(file.inputStream().buffered()) }.getOrNull()?.let { catalog ->
                    cachedCatalog = catalog
                    cachedAt = System.currentTimeMillis()
                    return@withLock catalog
                }
            }

            // 2. GitHub mirror first (fast, reliable), then the site itself.
            val errors = ArrayList<String>(2)
            for (url in listOf(MIRROR_CATALOG_URL, siteCatalogUrl)) {
                try {
                    val catalog = fetchCatalog(url)
                    cachedCatalog = catalog
                    cachedAt = System.currentTimeMillis()
                    return@withLock catalog
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val reason = e.message ?: e.javaClass.simpleName
                    errors.add("${hostOf(url)}: ${reason.take(160)}")
                }
            }

            // 3. Last resort: expired local cache.
            readCache(ignoreAge = true)?.let { file ->
                runCatching { parseCatalog(file.inputStream().buffered()) }.getOrNull()?.let { catalog ->
                    cachedCatalog = catalog
                    cachedAt = System.currentTimeMillis()
                    return@withLock catalog
                }
            }

            throw ErrorLoadingException(
                "CineHD Live: unable to load channel catalog (${errors.joinToString(" | ")})"
            )
        }
    }

    /**
     * Downloads the catalog through the raw response body.
     *
     * CloudStream's NiceResponse.text refuses responses bigger than 5 MB to avoid OOM,
     * and the CineHD catalog is ~6.7 MB. Reading response.body directly bypasses that
     * guard and keeps the JSON out of the heap: it is streamed to a temp file and parsed
     * from disk afterwards.
     */
    private suspend fun fetchCatalog(url: String): CineHdCatalog = withContext(Dispatchers.IO) {
        val response = try {
            app.get(
                url,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "User-Agent" to CATALOG_USER_AGENT,
                ),
                timeout = 120,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ErrorLoadingException(
                "network error (${e.javaClass.simpleName}: ${e.message ?: "no details"})"
            )
        }

        if (!response.isSuccessful) {
            throw ErrorLoadingException("HTTP ${response.code}")
        }

        val body = response.body
            ?: throw ErrorLoadingException("empty response (HTTP ${response.code})")

        val tempFile = File.createTempFile("cinehd-catalog-", ".tmp", tempDirectory())
        try {
            var total = 0L
            body.byteStream().use { input ->
                tempFile.outputStream().buffered(DOWNLOAD_BUFFER).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_CATALOG_BYTES) {
                            throw ErrorLoadingException(
                                "catalog too large (over ${MAX_CATALOG_BYTES / (1024 * 1024)} MB)"
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (total < MIN_CATALOG_BYTES) {
                throw ErrorLoadingException("empty response (HTTP ${response.code})")
            }

            validateCatalogStart(tempFile, response.code)
            val catalog = parseCatalog(tempFile.inputStream().buffered())
            storeCache(tempFile)
            catalog
        } finally {
            tempFile.delete()
        }
    }

    private fun tempDirectory(): File {
        val dir = cacheDir
        if (dir != null && (dir.isDirectory || dir.mkdirs())) return dir
        return File(System.getProperty("java.io.tmpdir") ?: ".")
    }

    private fun validateCatalogStart(file: File, code: Int) {
        val buffer = ByteArray(256)
        val read = file.inputStream().use { it.read(buffer) }
        val prefix = if (read > 0) String(buffer, 0, read, StandardCharsets.UTF_8) else ""
        val trimmed = prefix.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith("{")) {
            val preview = prefix.take(120).replace('\n', ' ').replace('\r', ' ')
            throw ErrorLoadingException("unexpected response (HTTP $code): $preview")
        }
    }

    private fun storeCache(tempFile: File) {
        val dir = cacheDir ?: return
        runCatching {
            val target = File(dir, CACHE_FILE)
            if (target.exists()) target.delete()
            if (!tempFile.renameTo(target)) {
                tempFile.copyTo(target, overwrite = true)
            }
        }
    }

    private fun hostOf(url: String): String {
        return url.substringAfter("://").substringBefore('/')
    }

    private fun parseCatalog(input: InputStream): CineHdCatalog {
        val parsedChannels = LinkedHashMap<String, ParsedChannel>()
        val streamsByChannel = HashMap<String, MutableList<CineHdStream>>()
        val countryNames = HashMap<String, String>()

        try {
            JsonReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                reader.isLenient = true
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "channels" -> readChannels(reader, parsedChannels)
                        "streams" -> readStreams(reader, streamsByChannel)
                        "countries" -> readCountries(reader, countryNames)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        } catch (e: Throwable) {
            throw ErrorLoadingException(
                "invalid catalog data (${e.javaClass.simpleName}: ${e.message ?: "parse error"})"
            )
        }

        val channels = ArrayList<CineHdChannel>(parsedChannels.size)
        for (parsed in parsedChannels.values) {
            val streams = ArrayList<CineHdStream>()
            parsed.primaryStream?.let { streams.add(it) }
            streams.addAll(streamsByChannel[parsed.id].orEmpty())

            val uniqueStreams = streams
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
                .take(MAX_STREAMS)
            if (uniqueStreams.isEmpty()) continue

            channels.add(
                CineHdChannel(
                    id = parsed.id,
                    name = parsed.name,
                    altNames = parsed.altNames,
                    country = parsed.country,
                    categories = parsed.categories,
                    logo = parsed.logo,
                    streams = uniqueStreams,
                    searchText = normalize(
                        buildString {
                            append(parsed.name)
                            parsed.altNames.forEach { append(' ').append(it) }
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

    private fun readChannels(reader: JsonReader, out: MutableMap<String, ParsedChannel>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var id = ""
            var channelName = ""
            var altNames: List<String> = emptyList()
            var country: String? = null
            var categories: List<String> = emptyList()
            var logo: String? = null
            var primaryStream: CineHdStream? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = reader.nextStringValue()
                    "name" -> channelName = reader.nextStringValue()
                    "alt_names" -> altNames = reader.nextStringList()
                    "country" -> country = reader.nextStringValue().takeIf { it.isNotBlank() }
                    "categories" -> categories = reader.nextStringList()
                    "logo" -> logo = reader.nextStringValue().takeIf { it.isNotBlank() }
                    "stream" -> primaryStream = reader.nextStream()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (id.isNotBlank() && channelName.isNotBlank()) {
                out[id] = ParsedChannel(
                    id = id,
                    name = channelName,
                    altNames = altNames,
                    country = country?.uppercase(Locale.ROOT),
                    categories = categories,
                    logo = logo,
                    primaryStream = primaryStream,
                )
            }
        }
        reader.endArray()
    }

    private fun readStreams(
        reader: JsonReader,
        out: MutableMap<String, MutableList<CineHdStream>>,
    ) {
        reader.beginArray()
        while (reader.hasNext()) {
            var channel = ""
            var url = ""
            var quality: String? = null
            var feed: String? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "channel" -> channel = reader.nextStringValue()
                    "url" -> url = reader.nextStringValue()
                    "quality" -> quality = reader.nextStringValue().takeIf { it.isNotBlank() }
                    "feed" -> feed = reader.nextStringValue().takeIf { it.isNotBlank() }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (channel.isNotBlank() && url.isNotBlank()) {
                out.getOrPut(channel) { mutableListOf() }
                    .add(CineHdStream(url = url, quality = quality, feed = feed))
            }
        }
        reader.endArray()
    }

    private fun readCountries(reader: JsonReader, out: MutableMap<String, String>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var code = ""
            var countryName = ""

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "code" -> code = reader.nextStringValue()
                    "name" -> countryName = reader.nextStringValue()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (code.isNotBlank()) {
                out[code.uppercase(Locale.ROOT)] = countryName
            }
        }
        reader.endArray()
    }

    private fun JsonReader.nextStream(): CineHdStream? {
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipValue()
            return null
        }

        var url = ""
        var quality: String? = null
        var feed: String? = null

        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "url" -> url = nextStringValue()
                "quality" -> quality = nextStringValue().takeIf { it.isNotBlank() }
                "feed" -> feed = nextStringValue().takeIf { it.isNotBlank() }
                else -> skipValue()
            }
        }
        endObject()

        return if (url.isBlank()) null else CineHdStream(url, quality, feed)
    }

    private fun JsonReader.nextStringValue(): String {
        return when (peek()) {
            JsonToken.NULL -> {
                nextNull()
                ""
            }
            JsonToken.STRING, JsonToken.NUMBER -> nextString()
            JsonToken.BOOLEAN -> nextBoolean().toString()
            else -> {
                skipValue()
                ""
            }
        }
    }

    private fun JsonReader.nextStringList(): List<String> {
        if (peek() != JsonToken.BEGIN_ARRAY) {
            skipValue()
            return emptyList()
        }

        val values = ArrayList<String>()
        beginArray()
        while (hasNext()) {
            val value = nextStringValue()
            if (value.isNotBlank()) values.add(value)
        }
        endArray()
        return values
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

    private fun readCache(ignoreAge: Boolean = false): File? {
        val dir = cacheDir ?: return null
        val file = File(dir, CACHE_FILE)
        if (!file.isFile || file.length() < MIN_CATALOG_BYTES) return null
        if (!ignoreAge && System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) {
            return null
        }
        return file
    }
}

private data class ParsedChannel(
    val id: String,
    val name: String,
    val altNames: List<String>,
    val country: String?,
    val categories: List<String>,
    val logo: String?,
    val primaryStream: CineHdStream?,
)

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
