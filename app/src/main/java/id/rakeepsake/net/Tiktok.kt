package id.rakeepsake.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Hasil resolusi media dari sebuah tautan postingan. */
data class ResolvedMedia(
    val postId: String,
    val author: String,
    val caption: String,
    /** Daftar URL yang diunduh: video/foto. Kosong jika tidak ada media. */
    val mediaUrls: List<String>,
    /** URL audio terpisah (untuk slideshow maupun video jika opsi audio aktif). */
    val audioUrl: String? = null,
    val extension: String = "mp4",
    /** true bila hanya versi ber-watermark yang tersedia. */
    val watermarkOnly: Boolean = false,
    /** Cookie sesi web (`tt_chain_token`, `ttwid`, dll.) dari halaman target untuk host *.tiktok.com. */
    val mediaCookies: String? = null,
    /** resolver yang berhasil, untuk diagnostik. */
    val via: String = "",
)

class ResolveException(
    message: String,
    val watermarkOnly: Boolean = false,
    val unavailable: Boolean = false,
) : Exception(message)

/** Rantai resolver tanpa-watermark: WebPage (+Set-Cookie) -> Relay/TikWM -> Mobile API -> Story. */
object TiktokResolver {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val TIKWM = "https://www.tikwm.com/api/?url="

    /** Ekstrak tautan TikTok murni bila teks tempelan membawa kalimat promosi aplikasi. */
    fun extractUrl(raw: String): String {
        val trimmed = raw.trim().trim('"', '\'', '<', '>')
        val match = Regex("https?://[^\\s\"'<>]*tiktok\\.com[^\\s\"'<>]*", RegexOption.IGNORE_CASE)
            .find(trimmed)
        return (match?.value ?: trimmed).trimEnd('.', ',', ')', ']')
    }

    fun extractAuthorHandle(url: String): String? =
        Regex("@([a-zA-Z0-9._]+)").find(url)?.groupValues?.getOrNull(1)

    fun videoIdFrom(url: String): String? {
        val regexes = listOf(
            Regex("/video/(\\d+)"),
            Regex("/photo/(\\d+)"),
            Regex("/story/(\\d+)"),
            Regex("/v/(\\d+)"),
            Regex("item_id=(\\d+)"),
            Regex("(\\d{18,20})"),
        )
        return regexes.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    /** Ikuti pengalihan tautan pendek vm./vt./t. menjadi URL kanonis. */
    fun normalize(raw: String): String {
        val url = extractUrl(raw)
        if (!url.contains("vm.tiktok.com") && !url.contains("vt.tiktok.com") && !url.contains("/t/")) {
            return url
        }
        return try {
            Http.client.newCall(
                okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", Http.UA_MOBILE)
                    .build(),
            ).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (_: Exception) {
            url
        }
    }

    fun resolve(rawUrl: String, relayUrl: String = "", cookie: String? = null): ResolvedMedia {
        val canonical = normalize(rawUrl)
        val postId = videoIdFrom(canonical)
            ?: throw ResolveException("Tautan TikTok tidak dikenali. Tempel tautan video/foto yang valid.")

        val errors = mutableListOf<String>()
        var unavailableErr: ResolveException? = null

        // Khusus story atau tautan bertanda story: coba resolver story lebih dulu
        if (canonical.contains("/story/") || canonical.contains("story")) {
            runCatching { resolveViaStory(canonical, postId, cookie) }
                .onSuccess { android.util.Log.d(TAG, "resolver Story sukses"); return it }
                .onFailure {
                    android.util.Log.w(TAG, "resolver Story gagal: ${it.message}")
                    if (it is ResolveException && it.unavailable) unavailableErr = it
                    errors.add("Story: ${it.message}")
                }
        }

        // Bila pengguna menyetel relay khusus, utamakan relay dulu
        if (relayUrl.isNotBlank()) {
            runCatching { resolveViaTikwm(canonical, postId, relayUrl) }
                .onSuccess { android.util.Log.d(TAG, "resolver Relay sukses"); return it }
                .onFailure {
                    android.util.Log.w(TAG, "resolver Relay gagal: ${it.message}")
                    errors.add("Relay: ${it.message}")
                }
        }

        // Resolver C (Utama): parse JSON halaman publik + tangkap Set-Cookie (`tt_chain_token`) agar CDN 200 OK
        runCatching { resolveViaWebPage(canonical, postId, cookie) }
            .onSuccess { android.util.Log.d(TAG, "resolver WebPage sukses"); return it }
            .onFailure {
                android.util.Log.w(TAG, "resolver WebPage gagal: ${it.message}")
                if (it is ResolveException && it.unavailable) {
                    unavailableErr = it
                }
                errors.add("Web: ${it.message}")
            }

        // Bila TikTok resmi melaporkan video dihapus / privat dan tidak ada relay kustom, langsung beri alasan jelas
        unavailableErr?.let { err ->
            if (relayUrl.isBlank() && !canonical.contains("/story/")) {
                throw err
            }
        }

        // Resolver A: tikwm publik (cadangan bila halaman web diblokir)
        if (relayUrl.isBlank()) {
            runCatching { resolveViaTikwm(canonical, postId, "") }
                .onSuccess { android.util.Log.d(TAG, "resolver A (tikwm) sukses"); return it }
                .onFailure {
                    android.util.Log.w(TAG, "resolver A gagal: ${it.message}")
                    errors.add("A: ${it.message}")
                }
        }

        // Resolver B: API feed mobile TikTok
        runCatching { resolveViaMobileApi(postId) }
            .onSuccess { android.util.Log.d(TAG, "resolver B (mobile) sukses"); return it }
            .onFailure {
                android.util.Log.w(TAG, "resolver B gagal: ${it.message}")
                errors.add("B: ${it.message}")
            }

        // Resolver D: upaya terakhir via API Story jika ada cookie
        if (!canonical.contains("/story/") && !cookie.isNullOrBlank()) {
            runCatching { resolveViaStory(canonical, postId, cookie) }
                .onSuccess { android.util.Log.d(TAG, "resolver Story cadangan sukses"); return it }
                .onFailure { errors.add("StoryFallback: ${it.message}") }
        }

        unavailableErr?.let { throw it }

        val combined = errors.joinToString("\n")
        android.util.Log.w(TAG, "Semua resolver gagal untuk $canonical:\n$combined")
        val wmOnly = combined.contains("watermark", ignoreCase = true)
        if (wmOnly) {
            throw ResolveException(
                "Hanya tersedia versi ber-watermark. Tidak disimpan (kebijakan tanpa watermark).",
                watermarkOnly = true,
            )
        }
        val netErr = combined.contains("UnknownHost", ignoreCase = true) ||
            combined.contains("timeout", ignoreCase = true) ||
            combined.contains("Failed to connect", ignoreCase = true) ||
            combined.contains("Network", ignoreCase = true)
        if (netErr) {
            throw ResolveException("Gagal terhubung ke server TikTok. Periksa koneksi internet lalu coba lagi.")
        }
        if (combined.contains("HTTP 403", ignoreCase = true) || combined.contains("HTTP 429", ignoreCase = true)) {
            throw ResolveException("Server TikTok membatasi akses sementara. Tunggu sebentar lalu ketuk Coba Lagi.")
        }
        throw ResolveException("Media tidak dapat diambil. Kemungkinan video sudah dihapus, privat, atau tautan tidak aktif lagi.")
    }

    private const val TAG = "RaResolver"

    private val MOBILE_HOSTS = listOf(
        "https://api16-normal-c-useast1a.tiktokv.com",
        "https://api16-normal-c-useast2a.tiktokv.com",
        "https://api22-normal-c-useast1a.tiktokv.com",
        "https://api19-normal-c-useast1a.tiktokv.com",
    )

    /** Resolver B: endpoint feed mobile TikTok yang melayani tanpa tanda tangan. */
    private fun resolveViaMobileApi(postId: String): ResolvedMedia {
        val device = (1000000000L..8999999999L).random().toString()
        var lastError: Exception? = null
        for (host in MOBILE_HOSTS) {
            try {
                val url = "$host/aweme/v1/feed/?aweme_id=$postId" +
                    "&version_code=2613&app_name=musical_lyrics&channel=App&device_id=$device&os_version=9"
                return parseMobileFeed(Http.getText(url), postId)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("feed API tanpa host yang merespons")
    }

    private fun parseMobileFeed(body: String, postId: String): ResolvedMedia {
        val root = json.parseToJsonElement(body).jsonObject
        val statusCode = root["status_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val aweme = (root["aweme_list"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull() as? JsonObject
            ?: throw Exception("feed API status_code=$statusCode tanpa aweme")
        val desc = aweme["desc"]?.jsonPrimitive?.content ?: ""
        val authorObj = aweme["author"] as? JsonObject
        val author = (authorObj?.get("unique_id") ?: authorObj?.get("uniqueId"))
            ?.jsonPrimitive?.content ?: "tiktok"

        // Slideshow foto.
        val images: List<String> = runCatching {
            val infos = (aweme["image_post_info"] as? JsonObject)?.get("images")
                as? kotlinx.serialization.json.JsonArray ?: return@runCatching emptyList<String>()
            infos.mapNotNull { info ->
                val urls = (info as? JsonObject)?.get("display_image")
                    ?.jsonObject?.get("url_list") as? kotlinx.serialization.json.JsonArray
                urls?.mapNotNull { u -> (u as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            }.flatten()
        }.getOrNull() ?: emptyList()

        val play = urlListFrom(aweme["video"] as? JsonObject, "play_addr")
            .firstOrNull { it.startsWith("http") }
        val music = urlListFrom(aweme["music"] as? JsonObject, "play_url")
            .firstOrNull { it.startsWith("http") }

        if (images.isNotEmpty()) {
            return ResolvedMedia(
                postId = postId, author = author, caption = desc,
                mediaUrls = images, audioUrl = music,
                extension = "jpg", via = "mobileapi-slideshow",
            )
        }
        if (play.isNullOrBlank()) throw Exception("feed API: play_addr kosong")
        return ResolvedMedia(
            postId = postId, author = author, caption = desc,
            mediaUrls = listOf(play), audioUrl = music, extension = "mp4", via = "mobileapi",
        )
    }

    /** Ambil daftar string dari objek.url_list[addrKey]. */
    private fun urlListFrom(parent: JsonObject?, addrKey: String): List<String> {
        val list = parent?.get(addrKey)?.jsonObject?.get("url_list")
            as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return list.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    }

    private fun resolveViaTikwm(canonical: String, postId: String, relayUrl: String): ResolvedMedia {
        val base = if (relayUrl.isNotBlank()) relayUrl.removeSuffix("/") else "https://www.tikwm.com"
        val endpoint = "$base/api/?url=${java.net.URLEncoder.encode(canonical, "UTF-8")}&hd=1"
        val body = Http.getText(endpoint, referer = "https://www.tikwm.com/")
        val root = json.parseToJsonElement(body).jsonObject
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code != 0) throw Exception(root["msg"]?.jsonPrimitive?.content ?: "tikwm kode $code")
        val data = root["data"]?.jsonObject ?: throw Exception("tikwm: data kosong")

        val author = data["author"]?.jsonObject?.get("unique_id")?.jsonPrimitive?.content ?: "tiktok"
        val caption = data["title"]?.jsonPrimitive?.content ?: ""
        val images = runCatching { data["images"]?.jsonArray?.map { it.jsonPrimitive.content } }
            .getOrNull() ?: emptyList()
        val play = data["play"]?.jsonPrimitive?.content
        val wmplay = data["wm_play"]?.jsonPrimitive?.content
        val music = data["music"]?.jsonPrimitive?.content

        if (images.isNotEmpty()) {
            return ResolvedMedia(
                postId = postId, author = author, caption = caption,
                mediaUrls = images.map { absolute(it) },
                audioUrl = music?.let { absolute(it) },
                extension = "jpg", via = "tikwm-slideshow",
            )
        }
        if (play.isNullOrBlank()) {
            if (!wmplay.isNullOrBlank()) {
                throw Exception("hanya tersedia versi watermark")
            }
            throw Exception("tikwm: tanpa URL media")
        }
        return ResolvedMedia(
            postId = postId, author = author, caption = caption,
            mediaUrls = listOf(absolute(play)),
            audioUrl = music?.let { absolute(it) },
            extension = "mp4", via = "tikwm",
        )
    }

    private fun extractScriptJson(html: String, scriptId: String): JsonObject? {
        val needle = "id=\"$scriptId\""
        var idx = html.indexOf(needle)
        if (idx < 0) idx = html.indexOf(scriptId)
        if (idx < 0) return null
        val first = html.indexOf('>', idx)
        if (first < 0) return null
        val last = html.indexOf("</script>", first)
        if (last <= first + 1) return null
        val payload = html.substring(first + 1, last)
        return runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
    }

    private data class WebPageParseResult(val item: JsonObject?, val statusCode: Int?)

    private fun parseUniversalItem(html: String, postId: String, attemptLabel: String): WebPageParseResult {
        var observedStatus: Int? = null
        extractScriptJson(html, "api-data")?.let { apiRoot ->
            val detail = apiRoot["videoDetail"] as? JsonObject
            val code = detail?.get("statusCode")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (code != null) observedStatus = code
            deepFindItem(apiRoot, postId)?.let { return WebPageParseResult(it, code) }
        }
        extractScriptJson(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__")?.let { uniRoot ->
            val detail = (uniRoot["__DEFAULT_SCOPE__"] as? JsonObject)
                ?.get("webapp.video-detail") as? JsonObject
            val code = detail?.get("statusCode")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (observedStatus == null && code != null) observedStatus = code
            deepFindItem(uniRoot, postId)?.let { return WebPageParseResult(it, code ?: observedStatus) }
        }
        android.util.Log.w(TAG, "WebPage[$attemptLabel] itemStruct null (len=${html.length}, statusCode=$observedStatus)")
        return WebPageParseResult(null, observedStatus)
    }

    private fun resolveViaWebPage(canonical: String, postId: String, cookie: String? = null): ResolvedMedia {
        val cookieHeader = cookie?.let { if (it.contains("=")) it else "sessionid=$it; sessionid_ss=$it" }
        val baseHeaders = mapOf(
            "User-Agent" to Http.UA_MOBILE,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://www.tiktok.com/",
        )
        var fetched = Http.getTextWithCookies(canonical, referer = "https://www.tiktok.com/", headers = baseHeaders)
        var pageCookies = fetched.cookies
        var parsed = parseUniversalItem(fetched.body, postId, "mobile-nocookie")
        var observedStatus = parsed.statusCode

        if (parsed.item == null && !cookieHeader.isNullOrBlank()) {
            val authHeaders = baseHeaders.toMutableMap().apply { put("Cookie", cookieHeader) }
            fetched = Http.getTextWithCookies(canonical, referer = "https://www.tiktok.com/", headers = authHeaders)
            pageCookies = Http.mergeCookies(fetched.cookies, cookieHeader)
            parsed = parseUniversalItem(fetched.body, postId, "mobile-cookie")
            if (parsed.statusCode != null) observedStatus = parsed.statusCode
        }
        // Jangan coba UA tambahan bila TikTok resmi menyatakan video tidak ada (10204)
        if (parsed.item == null && observedStatus !in setOf(10203, 10204, 10221, 10223)) {
            val chromeHeaders = baseHeaders.toMutableMap().apply {
                put("User-Agent", "Mozilla/5.0 (Linux; Android 13; Redmi 9T) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            }
            fetched = Http.getTextWithCookies(canonical, referer = "https://www.tiktok.com/", headers = chromeHeaders)
            pageCookies = fetched.cookies
            parsed = parseUniversalItem(fetched.body, postId, "chrome-android")
            if (parsed.statusCode != null) observedStatus = parsed.statusCode
        }
        val item = parsed.item ?: when (observedStatus) {
            10203, 10204, 10221, 10223 -> throw ResolveException(
                "Video tidak tersedia di TikTok — kemungkinan sudah dihapus oleh pemilik atau tautan salah (kode $observedStatus). Bukan error aplikasi.",
                unavailable = true,
            )
            10215, 10216, 10217, 10222, 10231, 10232 -> throw ResolveException(
                "Video tidak dapat diunduh karena bersifat privat atau dibatasi oleh pemilik akun (kode $observedStatus). Bukan error aplikasi.",
                unavailable = true,
            )
            else -> throw Exception(
                if (observedStatus != null) "halaman TikTok status $observedStatus"
                else "postingan tidak ditemukan di halaman",
            )
        }
        val author = item["author"]?.jsonObject?.get("uniqueId")?.jsonPrimitive?.content ?: "tiktok"
        val caption = item["desc"]?.jsonPrimitive?.content ?: ""
        val video = item["video"]?.jsonObject
        val imageList = runCatching {
            item["imagePost"]?.jsonObject?.get("images")?.jsonArray?.mapNotNull { el ->
                val imgObj = el as? JsonObject ?: return@mapNotNull null
                val imgUrlEl = imgObj["imageURL"]
                (imgUrlEl as? JsonObject)?.get("urlList")?.jsonArray
                    ?.firstOrNull()?.jsonPrimitive?.contentOrNull
                    ?: (imgUrlEl as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }
        }.getOrNull() ?: emptyList()
        val musicUrl = item["music"]?.jsonObject?.get("playUrl")?.jsonPrimitive?.content

        if (imageList.isNotEmpty()) {
            return ResolvedMedia(
                postId = postId, author = author, caption = caption,
                mediaUrls = imageList, audioUrl = musicUrl,
                extension = "jpg", mediaCookies = pageCookies.ifBlank { null }, via = "webpage-slideshow",
            )
        }
        val playAddr = video?.get("playAddr")?.jsonPrimitive?.content
        if (playAddr.isNullOrBlank()) throw Exception("halaman: playAddr kosong")
        return ResolvedMedia(
            postId = postId, author = author, caption = caption,
            mediaUrls = listOf(playAddr), audioUrl = musicUrl,
            extension = "mp4", mediaCookies = pageCookies.ifBlank { null }, via = "webpage",
        )
    }

    private fun resolveViaStory(canonical: String, postId: String, cookie: String?): ResolvedMedia {
        val handleMatch = Regex("@([a-zA-Z0-9._]+)").find(canonical)
        val handle = handleMatch?.groupValues?.get(1) ?: throw Exception("Username tidak ditemukan di tautan")
        val cookieHeader = cookie?.let { if (it.contains("=")) it else "sessionid=$it; sessionid_ss=$it" }

        val headers = mutableMapOf(
            "User-Agent" to Http.UA_MOBILE,
            "Accept" to "*/*",
            "Referer" to "https://www.tiktok.com/@$handle",
        )
        if (!cookieHeader.isNullOrBlank()) {
            headers["Cookie"] = cookieHeader
        }

        val profFetched = Http.getTextWithCookies("https://www.tiktok.com/@$handle", referer = "https://www.tiktok.com/", headers = headers)
        val profHtml = profFetched.body
        val marker = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
        val start = profHtml.indexOf(marker)
        if (start < 0) throw Exception("Gagal membaca profil TikTok")
        val first = profHtml.indexOf('>', start)
        val last = profHtml.indexOf("</script>", first)
        val payload = profHtml.substring(first + 1, last)
        val root = json.parseToJsonElement(payload).jsonObject
        val scope = root["__DEFAULT_SCOPE__"] as? JsonObject
        val userDetail = scope?.get("webapp.user-detail") as? JsonObject
        val userInfo = userDetail?.get("userInfo") as? JsonObject
        val user = userInfo?.get("user") as? JsonObject
        val userId = user?.get("id")?.jsonPrimitive?.content ?: throw Exception("ID user TikTok tidak ditemukan")

        val storyHeaders = headers.toMutableMap().apply {
            val merged = Http.mergeCookies(cookieHeader, profFetched.cookies)
            if (merged.isNotBlank()) put("Cookie", merged)
        }
        val storyUrl = "https://www.tiktok.com/api/story/item_list/?aid=1988&app_language=en&app_name=tiktok_web" +
            "&device_platform=web_pc&cursor=0&authorId=$userId&loadBackward=false&count=20"
        val storyFetched = Http.getTextWithCookies(storyUrl, referer = "https://www.tiktok.com/@$handle", headers = storyHeaders)
        val mergedCookies = Http.mergeCookies(profFetched.cookies, storyFetched.cookies)
        val sRoot = json.parseToJsonElement(storyFetched.body).jsonObject
        val itemList = sRoot["itemList"] as? kotlinx.serialization.json.JsonArray
            ?: throw ResolveException("Story sudah dihapus atau melewati batas 24 jam (tidak tersedia di TikTok). Bukan error aplikasi.", unavailable = true)

        val matching = itemList.mapNotNull { it as? JsonObject }.firstOrNull {
            it["id"]?.jsonPrimitive?.content == postId
        } ?: itemList.firstOrNull() as? JsonObject
            ?: throw ResolveException("Story tidak ditemukan (kemungkinan sudah kedaluwarsa >24 jam atau dihapus pemilik). Bukan error aplikasi.", unavailable = true)

        val targetId = matching["id"]?.jsonPrimitive?.content ?: postId
        val desc = matching["desc"]?.jsonPrimitive?.content ?: ""
        val imgPost = matching["imagePost"] as? JsonObject
        val isPhoto = imgPost != null
        val mediaUrls = mutableListOf<String>()
        val audioUrl = (matching["music"] as? JsonObject)?.get("playUrl")?.jsonPrimitive?.content

        if (isPhoto) {
            val imgs = imgPost?.get("images") as? kotlinx.serialization.json.JsonArray
            imgs?.forEach { im ->
                val uList = ((im as? JsonObject)?.get("imageURL") as? JsonObject)?.get("urlList") as? kotlinx.serialization.json.JsonArray
                val firstUrl = uList?.firstOrNull()?.jsonPrimitive?.content
                if (firstUrl != null) mediaUrls.add(firstUrl)
            }
            if (mediaUrls.isEmpty()) {
                val cover = imgPost?.get("cover") as? JsonObject
                val uList = (cover?.get("imageURL") as? JsonObject)?.get("urlList") as? kotlinx.serialization.json.JsonArray
                val firstUrl = uList?.firstOrNull()?.jsonPrimitive?.content
                if (firstUrl != null) mediaUrls.add(firstUrl)
            }
            return ResolvedMedia(
                postId = targetId, author = handle, caption = desc,
                mediaUrls = mediaUrls, audioUrl = audioUrl,
                extension = "jpg", mediaCookies = mergedCookies.ifBlank { null }, via = "story-photo",
            )
        } else {
            val vid = matching["video"] as? JsonObject
            val play = vid?.get("playAddr")?.jsonPrimitive?.content
                ?: vid?.get("downloadAddr")?.jsonPrimitive?.content
                ?: throw Exception("URL video story tidak ditemukan")
            return ResolvedMedia(
                postId = targetId, author = handle, caption = desc,
                mediaUrls = listOf(play), audioUrl = audioUrl,
                extension = "mp4", mediaCookies = mergedCookies.ifBlank { null }, via = "story-video",
            )
        }
    }

    private fun deepFindItem(root: JsonObject, postId: String): JsonObject? {
        // ItemModule di __UNIVERSAL_DATA__ memetakan id -> itemStruct.
        val wanted = setOf(postId)
        fun walk(el: kotlinx.serialization.json.JsonElement): JsonObject? {
            if (el is JsonObject) {
                for ((k, v) in el) {
                    if (k == "itemStruct" && v is JsonObject) {
                        val idStr = v["id"]?.jsonPrimitive?.content
                        if (idStr != null && idStr in wanted) return v
                    }
                }
                for (v in el.values) {
                    walk(v)?.let { return it }
                }
            } else if (el is kotlinx.serialization.json.JsonArray) {
                for (v in el) walk(v)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    private fun absolute(url: String): String =
        if (url.startsWith("http")) url else "https://www.tikwm.com$url"
}

/** Validasi akun TikTok: tikwm user-posts dulu, lalu halaman profil sebagai cadangan. */
object TiktokAccount {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun check(handle: String): Pair<String, String?> {
        val h = handle.removePrefix("@").trim()

        // 1) tikwm user-posts: akun valid walaupun belum punya postingan (videos bisa kosong).
        runCatching {
            val body = Http.getText(
                "https://www.tikwm.com/api/user/posts?unique_id=$h&count=1",
                referer = "https://www.tikwm.com/",
            )
            val root = json.parseToJsonElement(body).jsonObject
            val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            if (code == 0) {
                val videos = root["data"]?.jsonObject?.get("videos") as? kotlinx.serialization.json.JsonArray
                val first = videos?.firstOrNull() as? JsonObject
                val nickname = first?.jsonObject?.get("author")?.jsonObject?.get("nickname")
                    ?.jsonPrimitive?.content
                return h to nickname
            }
            // kode != 0 biasanya berarti user tidak ditemukan.
            throw Exception(root["msg"]?.jsonPrimitive?.content ?: "Akun TikTok tidak ditemukan")
        }.onFailure { if (it.message?.contains("ditemukan", ignoreCase = true) == true) throw it }

        // 2) Halaman profil publik: valid bila halaman nyata (bukan tantangan/bukan 404).
        val html = Http.getText("https://www.tiktok.com/@$h")
        if (html.length < 5000 || html.contains("Couldn't find this account", ignoreCase = true)) {
            throw Exception("Akun TikTok tidak ditemukan atau tidak dapat diakses")
        }
        val og = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"")
            .find(html)?.groupValues?.get(1)
        return h to (og?.takeIf { it.isNotBlank() } ?: h)
    }
}
