package id.rakeepsake.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Status live untuk satu akun. */
data class LiveStatus(
    val live: Boolean,
    val streamUrl: String? = null,
    val title: String? = null,
    val viewers: Long? = null,
    val avatarUrl: String? = null,
)

/** Keluaran cek akun. */
data class AccountCheck(val handle: String, val displayName: String, val avatarUrl: String?)

internal data class TikTokWebProfile(
    val uniqueId: String,
    val nickname: String,
    val userId: String?,
    val secUid: String?,
    val avatarUrl: String?,
)

internal data class TikTokHtmlParseResult(
    val profile: TikTokWebProfile?,
    val statusCode: Int?,
)

class PlatformException(message: String) : Exception(message)

object PlatformApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val expiresRegex = Regex("""(?:^|[?&])x-expires=(\d+)""", RegexOption.IGNORE_CASE)
    private val terminalMissingCodes = setOf(10203, 10204, 10221, 10223)

    /** Mengembalikan true bila URL avatar kosong atau token `x-expires` sudah/akan habis (< 1 jam). */
    fun isAvatarUrlExpiredOrBlank(url: String?, nowEpochSec: Long = System.currentTimeMillis() / 1000L): Boolean {
        if (url.isNullOrBlank()) return true
        val exp = expiresRegex.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return false
        return nowEpochSec >= exp - 3600L
    }

    /** Ambil URL gambar avatar terbaik dari objek JSON TikTok (mendukung string maupun url_list). */
    internal fun extractAvatarUrl(obj: JsonObject?): String? {
        if (obj == null) return null
        val keys = listOf("avatarLarger", "avatarMedium", "avatarThumb", "avatar")
        for (k in keys) {
            val el = obj[k] ?: continue
            val candidate = when (el) {
                is JsonPrimitive -> el.contentOrNull?.takeIf { it.startsWith("http", ignoreCase = true) }
                is JsonObject -> (el["url_list"] as? JsonArray ?: el["urlList"] as? JsonArray)
                    ?.firstNotNullOfOrNull { item ->
                        (item as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http", ignoreCase = true) }
                    }
                is JsonArray -> el.firstNotNullOfOrNull { item ->
                    (item as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http", ignoreCase = true) }
                }
                else -> null
            }
            if (!candidate.isNullOrBlank()) return candidate
        }
        return null
    }

    /** Parse payload `__UNIVERSAL_DATA_FOR_REHYDRATION__` dari HTML halaman profil TikTok. */
    internal fun parseTikTokUserDetailFromHtml(html: String, expectedHandle: String): TikTokHtmlParseResult {
        val marker = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
        val start = html.indexOf(marker)
        if (start < 0) return TikTokHtmlParseResult(null, null)
        val first = html.indexOf('>', start)
        if (first < 0) return TikTokHtmlParseResult(null, null)
        val last = html.indexOf("</script>", first)
        if (last <= first + 1) return TikTokHtmlParseResult(null, null)

        return runCatching {
            val payload = html.substring(first + 1, last)
            val root = json.parseToJsonElement(payload).jsonObject
            val scope = root["__DEFAULT_SCOPE__"] as? JsonObject
            val userDetail = scope?.get("webapp.user-detail") as? JsonObject
            val statusCode = userDetail?.get("statusCode")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val userInfo = userDetail?.get("userInfo") as? JsonObject
            val userObj = userInfo?.get("user") as? JsonObject ?: return@runCatching TikTokHtmlParseResult(null, statusCode)

            val uniqueId = userObj["uniqueId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (uniqueId.isBlank() || !uniqueId.equals(expectedHandle, ignoreCase = true)) {
                if (uniqueId.isNotBlank()) {
                    runCatching {
                        android.util.Log.w("RaPlatformApi", "Profil @$expectedHandle: uniqueId '$uniqueId' != handle '$expectedHandle', abaikan!")
                    }
                }
                return@runCatching TikTokHtmlParseResult(null, statusCode)
            }

            val id = userObj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val secUid = userObj["secUid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val nickname = userObj["nickname"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: uniqueId
            val avatar = extractAvatarUrl(userObj)
            TikTokHtmlParseResult(
                profile = TikTokWebProfile(
                    uniqueId = uniqueId,
                    nickname = nickname,
                    userId = id,
                    secUid = secUid,
                    avatarUrl = avatar,
                ),
                statusCode = statusCode,
            )
        }.getOrElse {
            TikTokHtmlParseResult(null, null)
        }
    }

    // ---------- TikTok live ----------
    fun tiktokLive(handle: String, cookie: String? = null): LiveStatus {
        val h = handle.removePrefix("@").trim()
        val cookieHeader = cookie?.let { c ->
            if (c.contains("=")) c else "sessionid=$c; sessionid_ss=$c; sid_tt=$c"
        }

        val url = "https://www.tiktok.com/api-live/user/room/?aid=1988&app_name=tiktok_web" +
            "&device_platform=web_pc&uniqueId=$h&sourceType=54"
        val headers = mutableMapOf(
            "User-Agent" to Http.UA_WEB,
            "Referer" to "https://www.tiktok.com/@$h/live",
            "Accept" to "*/*",
        )
        if (!cookieHeader.isNullOrBlank()) {
            headers["Cookie"] = cookieHeader
        }

        val body = try {
            Http.getText(url, referer = "https://www.tiktok.com/@$h/live", headers = headers)
        } catch (e: Exception) {
            android.util.Log.w("RaPlatformApi", "Gagal panggil api-live @$h: ${e.message}")
            return LiveStatus(false)
        }

        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"] as? JsonObject ?: return LiveStatus(false)
            val liveRoom = data["liveRoom"] as? JsonObject
            val user = data["user"] as? JsonObject

            // Status live di TikTok: 2 = LIVING (Live sedang berlangsung), 4 = FINISH (Offline)
            val status = liveRoom?.get("status")?.jsonPrimitive?.content?.toIntOrNull()
                ?: user?.get("status")?.jsonPrimitive?.content?.toIntOrNull()
                ?: data["status"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: 0

            val roomId = user?.get("roomId")?.jsonPrimitive?.content
                ?: liveRoom?.get("roomId")?.jsonPrimitive?.content

            val userAvatar = extractAvatarUrl(user)

            android.util.Log.d("RaPlatformApi", "Cek live @$h: status=$status (2=LIVE), roomId=$roomId")

            if (status != 2) return LiveStatus(false, avatarUrl = userAvatar)

            val title = liveRoom?.get("title")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: "${user?.get("nickname")?.jsonPrimitive?.content ?: h} Live"
            val viewers = (liveRoom?.get("liveRoomStats") as? JsonObject)?.get("userCount")?.jsonPrimitive?.content?.toLongOrNull()
                ?: (data["stats"] as? JsonObject)?.get("followerCount")?.jsonPrimitive?.content?.toLongOrNull()

            val streamUrl = extractTiktokLiveStream(liveRoom, data)
                ?: throw PlatformException("Live terdeteksi pada @$h tapi URL stream tidak tersedia.")

            android.util.Log.i("RaPlatformApi", "LIVE AKTIF @$h: title='$title', streamUrl=$streamUrl")
            LiveStatus(true, streamUrl, title, viewers, userAvatar)
        } catch (e: Exception) {
            android.util.Log.w("RaPlatformApi", "Error parse live @$h: ${e.message}")
            LiveStatus(false)
        }
    }

    private fun extractTiktokLiveStream(liveRoom: JsonObject?, data: JsonObject?): String? {
        if (liveRoom != null) {
            // 1. Ekstraksi dari streamData.pull_data.stream_data (Format modern TikTok Webcast)
            val streamDataObj = (liveRoom["streamData"] as? JsonObject)
                ?: (liveRoom["hevcStreamData"] as? JsonObject)
            val pullData = streamDataObj?.get("pull_data") as? JsonObject
            val streamDataRaw = pullData?.get("stream_data")?.jsonPrimitive?.content
            if (!streamDataRaw.isNullOrBlank()) {
                try {
                    val sdRoot = json.parseToJsonElement(streamDataRaw).jsonObject
                    val sdData = sdRoot["data"] as? JsonObject
                    if (sdData != null) {
                        val preferredQualities = listOf("hd", "origin", "uhd_60", "hd_60", "sd", "ld", "auto")
                        val streamSources = listOf("main", "backup")
                        // 1. Cari HLS (.m3u8) terlebih dahulu (kompatibel penuh dengan pemutar HP)
                        for (q in preferredQualities) {
                            val qObj = sdData[q] as? JsonObject ?: continue
                            for (src in streamSources) {
                                val sObj = qObj[src] as? JsonObject ?: continue
                                val hls = sObj["hls"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                                if (hls != null) return hls
                            }
                        }
                        for ((_, qElem) in sdData) {
                            val qObj = qElem as? JsonObject ?: continue
                            for (src in streamSources) {
                                val sObj = qObj[src] as? JsonObject ?: continue
                                val hls = sObj["hls"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                                if (hls != null) return hls
                            }
                        }
                        // 2. Cek format streamUrl alternatif
                        val sUrl = (liveRoom["streamUrl"] as? JsonObject) ?: (liveRoom["stream_url"] as? JsonObject)
                        sUrl?.get("hls_pull_url")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }

                        // 3. Fallback ke FLV jika HLS tidak ditemukan
                        for (q in preferredQualities) {
                            val qObj = sdData[q] as? JsonObject ?: continue
                            for (src in streamSources) {
                                val sObj = qObj[src] as? JsonObject ?: continue
                                val flv = sObj["flv"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                                if (flv != null) return flv
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("RaPlatformApi", "Gagal parse stream_data: ${e.message}")
                }
            }

            // 2. Format streamUrl alternatif
            val sUrl = (liveRoom["streamUrl"] as? JsonObject) ?: (liveRoom["stream_url"] as? JsonObject)
            streamUrlFrom(sUrl)?.let { return it }
        }

        // 3. Fallback data["stream_url"]
        return streamUrlFrom(data?.get("stream_url") as? JsonObject)
    }

    private fun streamUrlFrom(o: JsonObject?): String? {
        if (o == null) return null
        o["hls_pull_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }
        val flv = o["flv_pull_url"] as? JsonObject
        flv?.values?.firstOrNull()?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }
        o["flv_pull_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { return it }
        return o["rtmp_pull_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    private val tiktokUserCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    fun fetchAccountAvatar(platform: String, handle: String): String? = when (platform.uppercase()) {
        "TIKTOK" -> tiktokCheck(handle).avatarUrl
        "SHOWROOM" -> showroomCheck(handle).avatarUrl
        "IDN" -> idnCheck(handle).avatarUrl
        else -> null
    }

    private fun fetchTikTokWebProfile(
        handle: String,
        cookieHeader: String? = null,
        allowCookieFallback: Boolean = false,
    ): Pair<TikTokHtmlParseResult, String> {
        val h = handle.removePrefix("@").trim()
        val baseHeaders = mapOf(
            "User-Agent" to Http.UA_MOBILE,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "https://www.tiktok.com/",
        )
        var cookies = ""
        var result = TikTokHtmlParseResult(null, null)

        // JANGAN menyertakan Cookie pengguna di percobaan pertama saat scraping profil publik,
        // agar profil target tidak tercemar data sesi akun yang sedang login (app-context.user).
        runCatching {
            val fetched = Http.getTextWithCookies("https://www.tiktok.com/@$h", referer = "https://www.tiktok.com/", headers = baseHeaders)
            cookies = Http.mergeCookies(cookies, fetched.cookies)
            result = parseTikTokUserDetailFromHtml(fetched.body, h)
        }.onFailure {
            android.util.Log.w("RaPlatformApi", "Gagal scrape profil (mobile) @$h: ${it.message}")
        }

        // Bila masih kosong dan status bukan akun nonaktif, coba UA web
        if (result.profile == null && result.statusCode !in terminalMissingCodes) {
            runCatching {
                val webHeaders = baseHeaders.toMutableMap().apply { put("User-Agent", Http.UA_WEB) }
                val fetched = Http.getTextWithCookies("https://www.tiktok.com/@$h", referer = "https://www.tiktok.com/", headers = webHeaders)
                cookies = Http.mergeCookies(cookies, fetched.cookies)
                result = parseTikTokUserDetailFromHtml(fetched.body, h)
            }.onFailure {
                android.util.Log.w("RaPlatformApi", "Gagal scrape profil (web) @$h: ${it.message}")
            }
        }

        // Cadangan dengan cookie sesi bila profil privat / dibatasi (khusus pemantau konten)
        if (result.profile == null && allowCookieFallback && !cookieHeader.isNullOrBlank() && result.statusCode !in terminalMissingCodes) {
            runCatching {
                val authHeaders = baseHeaders.toMutableMap().apply { put("Cookie", cookieHeader) }
                val fetched = Http.getTextWithCookies("https://www.tiktok.com/@$h", referer = "https://www.tiktok.com/", headers = authHeaders)
                cookies = Http.mergeCookies(cookies, fetched.cookies)
                result = parseTikTokUserDetailFromHtml(fetched.body, h)
            }.onFailure {
                android.util.Log.w("RaPlatformApi", "Gagal scrape profil (cookie) @$h: ${it.message}")
            }
        }

        result.profile?.let { prof ->
            if (!prof.userId.isNullOrBlank() && !prof.secUid.isNullOrBlank()) {
                tiktokUserCache[h.lowercase()] = Pair(prof.userId, prof.secUid)
            }
        }
        return result to cookies
    }

    fun tiktokCheck(handle: String): AccountCheck {
        val h = handle.removePrefix("@").trim()
        if (h.isBlank()) throw PlatformException("Username TikTok tidak boleh kosong.")

        var bestHandle = h
        var bestNick: String? = null
        var webSecUid: String? = null

        // 1. Utama: scraping halaman profil publik TikTok (@handle) untuk nickname + foto profil + secUid
        val (webRes, _) = fetchTikTokWebProfile(h)
        webRes.profile?.let { prof ->
            bestHandle = prof.uniqueId.ifBlank { h }
            bestNick = prof.nickname
            webSecUid = prof.secUid
            if (!prof.avatarUrl.isNullOrBlank()) {
                return AccountCheck(bestHandle, prof.nickname, prof.avatarUrl)
            }
        }

        // 2. Cadangan: api-live/user/room (mendapatkan nickname dan avatar bila tersedia)
        try {
            val url = "https://www.tiktok.com/api-live/user/room/?aid=1988&app_name=tiktok_web&device_platform=web_pc&uniqueId=$h&sourceType=54"
            val body = Http.getText(url, referer = "https://www.tiktok.com/@$h/live")
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"] as? JsonObject
            val user = data?.get("user") as? JsonObject
            if (user != null) {
                val nickname = user["nickname"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: bestNick ?: h
                val avatar = extractAvatarUrl(user)
                if (bestNick == null) bestNick = nickname
                if (!avatar.isNullOrBlank()) {
                    return AccountCheck(bestHandle, nickname, avatar)
                }
            }
        } catch (_: Exception) {
        }

        // 3. Cadangan: /api/creator/item_list/ via secUid dari profil / cache
        val cachedSecUid = webSecUid ?: tiktokUserCache[h.lowercase()]?.second
        if (!cachedSecUid.isNullOrBlank()) {
            try {
                val creatorUrl = "https://www.tiktok.com/api/creator/item_list/?aid=1988&app_language=en&app_name=tiktok_web&count=1&cursor=0&device_platform=web_pc&secUid=$cachedSecUid&type=1"
                val body = Http.getText(creatorUrl, referer = "https://www.tiktok.com/@$h")
                val root = json.parseToJsonElement(body).jsonObject
                val items = root["itemList"] as? JsonArray
                val first = items?.firstOrNull() as? JsonObject
                val author = first?.get("author") as? JsonObject
                if (author != null) {
                    val nickname = author["nickname"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: bestNick ?: h
                    val avatar = extractAvatarUrl(author)
                    if (bestNick == null) bestNick = nickname
                    if (!avatar.isNullOrBlank()) {
                        return AccountCheck(bestHandle, nickname, avatar)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 4. Cadangan: TikWM user/info & user/posts (memuat foto profil kreator bila web TikTok diblokir)
        runCatching {
            val infoBody = Http.getText("https://www.tikwm.com/api/user/info?unique_id=$h", referer = "https://www.tikwm.com/")
            val infoRoot = json.parseToJsonElement(infoBody).jsonObject
            if (infoRoot["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() == 0) {
                val uObj = (infoRoot["data"] as? JsonObject)?.get("user") as? JsonObject
                if (uObj != null) {
                    val nickname = uObj["nickname"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: bestNick ?: h
                    val avatar = extractAvatarUrl(uObj)
                    if (bestNick == null) bestNick = nickname
                    if (!avatar.isNullOrBlank()) {
                        return AccountCheck(bestHandle, nickname, avatar)
                    }
                }
            }
        }

        runCatching {
            val postsBody = Http.getText("https://www.tikwm.com/api/user/posts?unique_id=$h&count=1", referer = "https://www.tikwm.com/")
            val postsRoot = json.parseToJsonElement(postsBody).jsonObject
            if (postsRoot["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() == 0) {
                val videos = (postsRoot["data"] as? JsonObject)?.get("videos") as? JsonArray
                val author = (videos?.firstOrNull() as? JsonObject)?.get("author") as? JsonObject
                val nickname = author?.get("nickname")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: bestNick ?: h
                val avatar = extractAvatarUrl(author)
                if (bestNick == null) bestNick = nickname
                if (!avatar.isNullOrBlank()) {
                    return AccountCheck(bestHandle, nickname, avatar)
                }
            }
        }

        // 5. Cadangan oEmbed resmi TikTok (gunakan lowercase agar handle kapital tidak ditolak 400)
        try {
            val body = Http.getText("https://www.tiktok.com/oembed?url=https://www.tiktok.com/@${h.lowercase()}")
            val root = json.parseToJsonElement(body).jsonObject
            val name = root["author_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: bestNick ?: h
            return AccountCheck(bestHandle, name, null)
        } catch (_: Exception) {
        }

        if (bestNick != null) {
            return AccountCheck(bestHandle, bestNick!!, null)
        }

        // 6. Cadangan terakhir: TiktokAccount.check
        try {
            val (h2, name) = TiktokAccount.check(h)
            return AccountCheck(h2, name ?: h2, null)
        } catch (_: Exception) {
            throw PlatformException("Akun TikTok @$h tidak ditemukan.")
        }
    }

    /** Postingan / story terakhir akun TikTok: id kunci (leksikografis menaik) + deskripsi + media. */
    data class PostRef(
        val id: String,
        val caption: String,
        val createdAt: Long,
        val isStory: Boolean = false,
        val isPhoto: Boolean = false,
        val mediaUrls: List<String> = emptyList(),
        val audioUrl: String? = null,
        val mediaCookies: String? = null,
    )

    fun tiktokRecentPosts(handle: String, limit: Int = 15, cookie: String? = null): List<PostRef> {
        val h = handle.removePrefix("@").trim()
        val cacheKey = h.lowercase()
        val cookieHeader = cookie?.let { c ->
            if (c.contains("=")) c else "sessionid=$c; sessionid_ss=$c; sid_tt=$c"
        }

        val items = mutableListOf<PostRef>()
        var webChainCookies = ""
        var userId: String? = tiktokUserCache[cacheKey]?.first
        var secUid: String? = tiktokUserCache[cacheKey]?.second

        // 1. Scraping Data Halaman Profil TikTok jika belum ada di cache
        if (userId.isNullOrBlank() || secUid.isNullOrBlank()) {
            val (webRes, scrapedCookies) = fetchTikTokWebProfile(
                handle = h,
                cookieHeader = cookieHeader,
                allowCookieFallback = true,
            )
            webChainCookies = Http.mergeCookies(webChainCookies, scrapedCookies)
            val statusCode = webRes.statusCode ?: 0
            if (statusCode != 0) {
                android.util.Log.w("RaPlatformApi", "Profil @$h: user-detail statusCode=$statusCode (akun mungkin tidak ada / ganti username)")
            }
            webRes.profile?.let { prof ->
                userId = prof.userId
                secUid = prof.secUid
            }
        }

        android.util.Log.d("RaPlatformApi", "Profil @$h: userId=$userId, secUid=$secUid")

        // 2. Ambil Story pengguna menggunakan API resmi internal TikTok (/api/story/item_list/)
        if (!userId.isNullOrBlank()) {
            runCatching {
                val storyUrl = "https://www.tiktok.com/api/story/item_list/?aid=1988&app_language=en&app_name=tiktok_web" +
                    "&device_platform=web_pc&cursor=0&authorId=$userId&loadBackward=false&count=20"
                val storyHeaders = mutableMapOf(
                    "User-Agent" to Http.UA_WEB,
                    "Accept" to "*/*",
                    "Referer" to "https://www.tiktok.com/@$h",
                )
                if (!cookieHeader.isNullOrBlank()) {
                    storyHeaders["Cookie"] = cookieHeader
                }
                val storyFetched = Http.getTextWithCookies(storyUrl, referer = "https://www.tiktok.com/@$h", headers = storyHeaders)
                val storyCookies = Http.mergeCookies(webChainCookies, storyFetched.cookies).ifBlank { null }
                val sRoot = json.parseToJsonElement(storyFetched.body).jsonObject
                val itemList = sRoot["itemList"] as? kotlinx.serialization.json.JsonArray
                if (itemList != null) {
                    for (el in itemList) {
                        val obj = el as? JsonObject ?: continue
                        // Verifikasi kepemilikan author agar tidak tercampur akun lain
                        val authorObj = obj["author"] as? JsonObject
                        val authorUniqueId = authorObj?.get("uniqueId")?.jsonPrimitive?.content
                        if (authorUniqueId != null && !authorUniqueId.equals(h, ignoreCase = true)) {
                            android.util.Log.w("RaPlatformApi", "Story id=${obj["id"]} author '$authorUniqueId' != target '$h'. Abaikan!")
                            continue
                        }

                        val storyId = obj["id"]?.jsonPrimitive?.content ?: continue
                        val desc = obj["desc"]?.jsonPrimitive?.content ?: ""
                        val createTime = (obj["createTime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) * 1000

                        val imgPost = obj["imagePost"] as? JsonObject
                        val isPhoto = imgPost != null
                        val mediaUrls = mutableListOf<String>()

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
                        } else {
                            val vid = obj["video"] as? JsonObject
                            val playAddr = vid?.get("playAddr")?.jsonPrimitive?.content
                            if (playAddr != null) {
                                mediaUrls.add(playAddr)
                            } else {
                                val dlAddr = vid?.get("downloadAddr")?.jsonPrimitive?.content
                                if (dlAddr != null) mediaUrls.add(dlAddr)
                            }
                        }

                        val music = obj["music"] as? JsonObject
                        val audioUrl = music?.get("playUrl")?.jsonPrimitive?.content

                        items.add(
                            PostRef(
                                id = storyId,
                                caption = if (desc.isNotBlank()) desc else "Story @$h",
                                createdAt = createTime,
                                isStory = true,
                                isPhoto = isPhoto,
                                mediaUrls = mediaUrls,
                                audioUrl = audioUrl,
                                mediaCookies = storyCookies,
                            ),
                        )
                    }
                }
            }.onFailure {
                android.util.Log.w("RaPlatformApi", "Gagal ambil story @$h: ${it.message}")
            }
        }

        // 3. Ambil Postingan Reguler (Video & Foto) via /api/creator/item_list/
        if (!secUid.isNullOrBlank()) {
            runCatching {
                val rndDeviceId = (7000000000000000000L + (Math.random() * 999999999999999999L).toLong()).toString()
                val cursor = System.currentTimeMillis()
                val creatorUrl = "https://www.tiktok.com/api/creator/item_list/?aid=1988&app_language=en&app_name=tiktok_web" +
                    "&count=$limit&cursor=$cursor&device_id=$rndDeviceId&device_platform=web_pc&secUid=$secUid&type=1"
                val creatorHeaders = mutableMapOf(
                    "User-Agent" to Http.UA_WEB,
                    "Accept" to "*/*",
                    "Referer" to "https://www.tiktok.com/@$h",
                )
                if (!cookieHeader.isNullOrBlank()) {
                    creatorHeaders["Cookie"] = cookieHeader
                }
                val respFetched = Http.getTextWithCookies(creatorUrl, referer = "https://www.tiktok.com/@$h", headers = creatorHeaders)
                val creatorCookies = Http.mergeCookies(webChainCookies, respFetched.cookies).ifBlank { null }
                val root = json.parseToJsonElement(respFetched.body).jsonObject
                val itemList = root["itemList"] as? kotlinx.serialization.json.JsonArray
                if (itemList != null) {
                    for (el in itemList) {
                        val obj = el as? JsonObject ?: continue
                        // Verifikasi kepemilikan author agar tidak pernah mengunduh konten akun lain
                        val authorObj = obj["author"] as? JsonObject
                        val authorUniqueId = authorObj?.get("uniqueId")?.jsonPrimitive?.content
                        if (authorUniqueId != null && !authorUniqueId.equals(h, ignoreCase = true)) {
                            android.util.Log.w("RaPlatformApi", "Item id=${obj["id"]} author '$authorUniqueId' != target '$h'. Abaikan!")
                            continue
                        }

                        val postId = obj["id"]?.jsonPrimitive?.content ?: continue
                        val desc = obj["desc"]?.jsonPrimitive?.content ?: ""
                        val createTime = (obj["createTime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) * 1000

                        val imgPost = obj["imagePost"] as? JsonObject
                        val isPhoto = imgPost != null
                        val mediaUrls = mutableListOf<String>()

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
                        } else {
                            val vid = obj["video"] as? JsonObject
                            val playAddr = vid?.get("playAddr")?.jsonPrimitive?.content
                            if (playAddr != null) {
                                mediaUrls.add(playAddr)
                            } else {
                                val dlAddr = vid?.get("downloadAddr")?.jsonPrimitive?.content
                                if (dlAddr != null) mediaUrls.add(dlAddr)
                            }
                        }

                        val music = obj["music"] as? JsonObject
                        val audioUrl = music?.get("playUrl")?.jsonPrimitive?.content

                        items.add(
                            PostRef(
                                id = postId,
                                caption = if (desc.isNotBlank()) desc else "Postingan @$h",
                                createdAt = createTime,
                                isStory = false,
                                isPhoto = isPhoto,
                                mediaUrls = mediaUrls,
                                audioUrl = audioUrl,
                                mediaCookies = creatorCookies,
                            ),
                        )
                    }
                }
            }.onFailure {
                android.util.Log.w("RaPlatformApi", "Gagal ambil creator items @$h: ${it.message}")
            }
        }

        // 4. Cadangan TikWM API jika belum ada feed post sama sekali
        val hasFeedPosts = items.any { !it.isStory }
        if (!hasFeedPosts) {
            runCatching {
                val body = Http.getText(
                    "https://www.tikwm.com/api/user/posts?unique_id=$h&count=$limit",
                    referer = "https://www.tikwm.com/",
                )
                val root = json.parseToJsonElement(body).jsonObject
                val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                if (code == 0) {
                    val videos = root["data"]?.jsonObject?.get("videos") as? kotlinx.serialization.json.JsonArray
                    if (videos != null && videos.isNotEmpty()) {
                        for (el in videos) {
                            val v = el as? JsonObject ?: continue
                            val authorUniqueId = (v["author"] as? JsonObject)?.get("unique_id")?.jsonPrimitive?.content
                            if (authorUniqueId != null && !authorUniqueId.equals(h, ignoreCase = true)) {
                                continue
                            }
                            val id = v["video_id"]?.jsonPrimitive?.content ?: continue
                            items.add(
                                PostRef(
                                    id = id,
                                    caption = v["title"]?.jsonPrimitive?.content ?: "",
                                    createdAt = (v["create_time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) * 1000,
                                    isStory = false,
                                    isPhoto = false,
                                ),
                            )
                        }
                    }
                }
            }
        }

        val uniqueItems = items.distinctBy { it.id }
        return uniqueItems.sortedByDescending { it.createdAt }
    }

    /** Hasil deteksi URL atau handle otomatis untuk pendaftaran akun. */
    data class ResolvedAccountInfo(
        val platform: id.rakeepsake.data.Platform,
        val handle: String,
        val displayName: String,
        val avatarUrl: String? = null,
    )

    fun detectPlatformHint(input: String): id.rakeepsake.data.Platform? {
        val s = input.trim().lowercase()
        return when {
            s.contains("tiktok.com") || s.contains("vt.tiktok") || s.contains("vm.tiktok") || s.startsWith("@") -> id.rakeepsake.data.Platform.TIKTOK
            s.contains("showroom-live.com") -> id.rakeepsake.data.Platform.SHOWROOM
            s.contains("idn.app") || s.contains("idntimes.com") -> id.rakeepsake.data.Platform.IDN
            else -> null
        }
    }

    suspend fun resolveAccountInput(
        rawInput: String,
        fallbackPlatform: id.rakeepsake.data.Platform = id.rakeepsake.data.Platform.TIKTOK,
    ): ResolvedAccountInfo {
        val input = rawInput.trim().trim('"', '\'', '<', '>')
        if (input.isBlank()) throw PlatformException("Tautan atau username tidak boleh kosong")

        val isHttp = input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)
        val hasDomain = input.contains("tiktok.com") || input.contains("showroom-live.com") || input.contains("idn.app") || input.contains("idntimes.com")

        if (isHttp || hasDomain) {
            var url = if (!input.startsWith("http://", ignoreCase = true) && !input.startsWith("https://", ignoreCase = true)) {
                "https://$input"
            } else {
                input
            }

            // TikTok
            if (url.contains("tiktok.com", ignoreCase = true)) {
                if (url.contains("vt.tiktok.com") || url.contains("vm.tiktok.com") || url.contains("/t/")) {
                    url = TiktokResolver.normalize(url)
                }
                val handleMatch = Regex("@([a-zA-Z0-9._]+)").find(url)
                val handle = handleMatch?.groupValues?.get(1)
                    ?: run {
                        val path = url.substringAfter("tiktok.com/").substringBefore("?").removePrefix("@").substringBefore("/")
                        if (path.isNotBlank() && path.matches(Regex("[a-zA-Z0-9._]+"))) path else null
                    }
                    ?: throw PlatformException("Tidak dapat menemukan username TikTok dari tautan tersebut.")
                val check = tiktokCheck(handle)
                return ResolvedAccountInfo(id.rakeepsake.data.Platform.TIKTOK, check.handle, check.displayName, check.avatarUrl)
            }

            // SHOWROOM
            if (url.contains("showroom-live.com", ignoreCase = true)) {
                val key = url.substringAfter("showroom-live.com/")
                    .substringBefore("?")
                    .removePrefix("r/")
                    .trim('/')
                if (key.isBlank()) throw PlatformException("Tautan SHOWROOM tidak valid.")
                val check = showroomCheck(key)
                return ResolvedAccountInfo(id.rakeepsake.data.Platform.SHOWROOM, check.handle, check.displayName, check.avatarUrl)
            }

            // IDN
            if (url.contains("idn.app", ignoreCase = true) || url.contains("idntimes.com", ignoreCase = true)) {
                val slug = url.substringAfterLast("/")
                    .substringBefore("?")
                    .removePrefix("@")
                    .trim('/')
                if (slug.isBlank()) throw PlatformException("Tautan IDN tidak valid.")
                val check = idnCheck(slug)
                return ResolvedAccountInfo(id.rakeepsake.data.Platform.IDN, check.handle, check.displayName, check.avatarUrl)
            }
        }

        // Teks biasa (username atau room key)
        val clean = input.removePrefix("@").trim()
        return when (fallbackPlatform) {
            id.rakeepsake.data.Platform.SHOWROOM -> {
                val check = showroomCheck(clean)
                ResolvedAccountInfo(id.rakeepsake.data.Platform.SHOWROOM, check.handle, check.displayName, check.avatarUrl)
            }
            id.rakeepsake.data.Platform.IDN -> {
                val check = idnCheck(clean)
                ResolvedAccountInfo(id.rakeepsake.data.Platform.IDN, check.handle, check.displayName, check.avatarUrl)
            }
            else -> {
                val check = tiktokCheck(clean)
                ResolvedAccountInfo(id.rakeepsake.data.Platform.TIKTOK, check.handle, check.displayName, check.avatarUrl)
            }
        }
    }

    // ---------- SHOWROOM ----------
    fun showroomLive(roomKey: String): LiveStatus {
        val body = try {
            Http.getText("https://www.showroom-live.com/api/room/status?room_url_key=$roomKey")
        } catch (e: Exception) {
            return LiveStatus(false)
        }
        val root = json.parseToJsonElement(body).jsonObject
        val room = root["room"] as? JsonObject
        val liveStatus = room?.get("live_status")?.jsonPrimitive?.content?.toIntOrNull()
            ?: root["live_status"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: 0
        if (liveStatus != 2) return LiveStatus(false)
        val roomId = room?.get("room_id")?.jsonPrimitive?.content?.toLongOrNull()
            ?: root["room_id"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw PlatformException("room_id kosong")
        val title = room?.get("room_name")?.jsonPrimitive?.content
            ?: root["room_name"]?.jsonPrimitive?.content
        val streams = Http.getText("https://www.showroom-live.com/api/live/streaming_url?room_id=$roomId")
        val sroot = json.parseToJsonElement(streams).jsonObject
        val surl = sroot["live_streaming_url"]?.jsonObject
        val hls = (surl?.get("hls_all") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("url")?.jsonPrimitive?.content }
            ?.firstOrNull { it.isNotBlank() }
            ?: throw PlatformException("SHOWROOM live tanpa URL HLS")
        return LiveStatus(true, hls, title)
    }

    fun showroomCheck(roomKey: String): AccountCheck {
        val clean = roomKey.trim()
        if (clean.isBlank()) throw PlatformException("Room key SHOWROOM tidak boleh kosong.")
        val body = try {
            Http.getText("https://www.showroom-live.com/api/room/status?room_url_key=$clean")
        } catch (e: Exception) {
            throw PlatformException("Room SHOWROOM '$clean' tidak ditemukan.")
        }
        val root = json.parseToJsonElement(body).jsonObject
        val errors = root["errors"] as? kotlinx.serialization.json.JsonArray
        if (errors != null && errors.isNotEmpty()) {
            throw PlatformException("Room SHOWROOM '$clean' tidak ditemukan.")
        }
        val room = root["room"] as? JsonObject
        val roomId = room?.get("room_id")?.jsonPrimitive?.content?.toLongOrNull()
            ?: root["room_id"]?.jsonPrimitive?.content?.toLongOrNull()
        if (roomId == null || roomId <= 0) {
            throw PlatformException("Room SHOWROOM '$clean' tidak ditemukan.")
        }
        val name = room?.get("main_name")?.jsonPrimitive?.content
            ?: room?.get("room_name")?.jsonPrimitive?.content
            ?: root["room_name"]?.jsonPrimitive?.content
            ?: clean
        val avatar = room?.get("image")?.jsonPrimitive?.content
            ?: root["image_s"]?.jsonPrimitive?.content
        return AccountCheck(clean, name, avatar)
    }

    // ---------- IDN ----------
    private const val IDN_CLIENT_ID = "7b414850-3e65-4a46-8e89-c0fa9e275915"
    private const val IDN_QUERY = "{\"query\":\"query{getLivestreams{slug playback_url status live_type title creator{username name avatar}}}\"}"

    fun idnLive(slugOrUser: String): LiveStatus {
        val target = slugOrUser.trim().lowercase().removePrefix("@")
        val body = Http.postJson("https://api.idn.app/graphql", IDN_QUERY, mapOf("Client-ID" to IDN_CLIENT_ID))
        val root = json.parseToJsonElement(body).jsonObject
        val list = root["data"]?.jsonObject?.get("getLivestreams") as? kotlinx.serialization.json.JsonArray
            ?: return LiveStatus(false)
        val mine = list.firstOrNull { el ->
            val obj = el as? JsonObject ?: return@firstOrNull false
            val slug = obj["slug"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val creatorUser = obj["creator"]?.jsonObject?.get("username")?.jsonPrimitive?.content?.lowercase() ?: ""
            slug == target || creatorUser == target || slug.contains(target) || creatorUser.contains(target)
        } as? JsonObject ?: return LiveStatus(false)

        val st = mine["status"]?.jsonPrimitive?.content ?: ""
        val live = (st.contains("LIVE", ignoreCase = true) || st.contains("on_air", ignoreCase = true) || st.contains("streaming", ignoreCase = true)) && !st.contains("END", ignoreCase = true)
        if (!live) return LiveStatus(false)
        val url = mine["playback_url"]?.jsonPrimitive?.content
            ?: throw PlatformException("IDN live tanpa playback_url")
        return LiveStatus(true, url, mine["title"]?.jsonPrimitive?.content)
    }

    fun idnCheck(slug: String): AccountCheck {
        val clean = slug.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) throw PlatformException("Username IDN tidak boleh kosong.")

        // 1. Coba GraphQL getPublicProfileByUsername
        try {
            val q = "{\"query\":\"query(\$u:String!){getPublicProfileByUsername(username:\$u){username name avatar}}\",\"variables\":{\"u\":\"$clean\"}}"
            val body = Http.postJson("https://api.idn.app/graphql", q, mapOf("Client-ID" to IDN_CLIENT_ID))
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"] as? JsonObject
            val prof = data?.get("getPublicProfileByUsername") as? JsonObject
            if (prof != null) {
                val username = prof["username"]?.jsonPrimitive?.content ?: clean
                val name = prof["name"]?.jsonPrimitive?.content ?: username
                val avatar = prof["avatar"]?.jsonPrimitive?.content
                return AccountCheck(username, name, avatar)
            }
        } catch (_: Exception) {
            // fallback
        }

        // 2. Coba mobile api livestream
        try {
            val body = Http.getText("https://mobile-api.idntimes.com/v3.1.1/livestream/$clean")
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"] as? JsonObject
            if (data != null) {
                val name = data["title"]?.jsonPrimitive?.content ?: data["creatorName"]?.jsonPrimitive?.content ?: clean
                val avatar = data["creatorPhoto"]?.jsonPrimitive?.content ?: data["image"]?.jsonPrimitive?.content
                return AccountCheck(clean, name, avatar)
            }
        } catch (_: Exception) {
            // fallback
        }

        throw PlatformException("Akun IDN '@$clean' tidak ditemukan.")
    }
}
