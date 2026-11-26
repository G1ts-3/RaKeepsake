package id.rakeepsake.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

class PlatformException(message: String) : Exception(message)

object PlatformApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

            val userAvatar = user?.get("avatarLarger")?.jsonPrimitive?.content
                ?: user?.get("avatarMedium")?.jsonPrimitive?.content
                ?: user?.get("avatarThumb")?.jsonPrimitive?.content

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

    fun tiktokCheck(handle: String): AccountCheck = run {
        val h = handle.removePrefix("@").trim()
        if (h.isBlank()) throw PlatformException("Username TikTok tidak boleh kosong.")

        // 1. Coba api-live/user/room (mendapatkan nickname dan avatar kualitas tinggi resmi)
        try {
            val url = "https://www.tiktok.com/api-live/user/room/?aid=1988&app_name=tiktok_web&device_platform=web_pc&uniqueId=$h&sourceType=54"
            val body = Http.getText(url, referer = "https://www.tiktok.com/@$h/live")
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"] as? JsonObject
            val user = data?.get("user") as? JsonObject
            if (user != null) {
                val nickname = user["nickname"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: h
                val avatar = user["avatarLarger"]?.jsonPrimitive?.content
                    ?: user["avatarMedium"]?.jsonPrimitive?.content
                    ?: user["avatarThumb"]?.jsonPrimitive?.content
                return AccountCheck(h, nickname, avatar)
            }
        } catch (_: Exception) {
        }

        // 2. Coba via secUid (terutama jika ada di cache atau akun khusus)
        val cachedSecUid = tiktokUserCache[h]?.second ?: when {
            h.equals("jkt48.ella.a", ignoreCase = true) -> "MS4wLjABAAAAXGnR-8ZJ_YICXhh1_oUpnVnqtiM2_eu634dgHj1GcW3AT3Sm0BP-6gpY1zUW71Oc"
            h.equals("cathleenixie8", ignoreCase = true) -> "MS4wLjABAAAACeqUz2zueObNqQkeTD4Lip36c_gksNpjRPm2-C_CZGZXjA1z3M5UFFnmtOG3fh8F"
            h.equals("g1ttt", ignoreCase = true) -> "MS4wLjABAAAA5kfF46q_shaT0enyiRanEDcUx53BuLQWo-Qs0xxBUX3N206VOt44N_fcX5a1xfAt"
            else -> null
        }
        if (!cachedSecUid.isNullOrBlank()) {
            try {
                val creatorUrl = "https://www.tiktok.com/api/creator/item_list/?aid=1988&app_language=en&app_name=tiktok_web&count=1&cursor=0&device_platform=web_pc&secUid=$cachedSecUid&type=1"
                val body = Http.getText(creatorUrl, referer = "https://www.tiktok.com/@$h")
                val root = json.parseToJsonElement(body).jsonObject
                val items = root["itemList"] as? kotlinx.serialization.json.JsonArray
                val first = items?.firstOrNull() as? JsonObject
                val author = first?.get("author") as? JsonObject
                if (author != null) {
                    val nickname = author["nickname"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: h
                    val avatar = author["avatarLarger"]?.jsonPrimitive?.content
                        ?: author["avatarMedium"]?.jsonPrimitive?.content
                        ?: author["avatarThumb"]?.jsonPrimitive?.content
                    return AccountCheck(h, nickname, avatar)
                }
            } catch (_: Exception) {}
        }

        // 3. Coba oEmbed resmi TikTok
        try {
            val body = Http.getText("https://www.tiktok.com/oembed?url=https://www.tiktok.com/@$h")
            val root = json.parseToJsonElement(body).jsonObject
            val name = root["author_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: h
            return AccountCheck(h, name, null)
        } catch (_: Exception) {
            // oEmbed gagal
        }

        // 4. Cadangan: TiktokAccount.check
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

    private val tiktokUserCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    fun tiktokRecentPosts(handle: String, limit: Int = 15, cookie: String? = null): List<PostRef> {
        val h = handle.removePrefix("@").trim()
        val cookieHeader = cookie?.let { c ->
            if (c.contains("=")) c else "sessionid=$c; sessionid_ss=$c; sid_tt=$c"
        }

        val items = mutableListOf<PostRef>()
        var webChainCookies = ""
        var userId: String? = tiktokUserCache[h]?.first
        var secUid: String? = tiktokUserCache[h]?.second

        // 1. Scraping Data Halaman Profil TikTok jika belum ada di cache
        if (userId.isNullOrBlank() || secUid.isNullOrBlank()) {
            val headers = mutableMapOf(
                "User-Agent" to Http.UA_MOBILE,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "https://www.tiktok.com/",
            )
            // JANGAN menyertakan Cookie pengguna saat scraping profil publik,
            // agar profil target tidak tercemar oleh data sesi akun yang sedang login (app-context.user)

            runCatching {
                val fetched = Http.getTextWithCookies("https://www.tiktok.com/@$h", referer = "https://www.tiktok.com/", headers = headers)
                webChainCookies = Http.mergeCookies(webChainCookies, fetched.cookies)
                val html = fetched.body
                val marker = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
                val start = html.indexOf(marker)
                if (start >= 0) {
                    val first = html.indexOf('>', start)
                    val last = html.indexOf("</script>", first)
                    if (first in 0 until last) {
                        val payload = html.substring(first + 1, last)
                        val root = json.parseToJsonElement(payload).jsonObject

                        val scope = root["__DEFAULT_SCOPE__"] as? JsonObject
                        val userDetail = scope?.get("webapp.user-detail") as? JsonObject
                        val statusCode = userDetail?.get("statusCode")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        if (statusCode != 0) {
                            android.util.Log.w("RaPlatformApi", "Profil @$h: user-detail statusCode=$statusCode (akun mungkin tidak ada / ganti username)")
                        }
                        val userInfo = userDetail?.get("userInfo") as? JsonObject
                        val userObj = userInfo?.get("user") as? JsonObject
                        if (userObj != null) {
                            val uniqueId = userObj["uniqueId"]?.jsonPrimitive?.content
                            // Verifikasi ketat: pastikan uniqueId dari halaman cocok dengan handle yang diminta ($h)!
                            if (uniqueId != null && uniqueId.equals(h, ignoreCase = true)) {
                                userId = userObj["id"]?.jsonPrimitive?.content
                                secUid = userObj["secUid"]?.jsonPrimitive?.content
                            } else {
                                android.util.Log.w("RaPlatformApi", "Profil @$h: uniqueId '$uniqueId' != handle '$h', abaikan!")
                            }
                        }
                    }
                }
                if (!userId.isNullOrBlank() && !secUid.isNullOrBlank()) {
                    tiktokUserCache[h] = Pair(userId!!, secUid!!)
                }
            }.onFailure {
                android.util.Log.w("RaPlatformApi", "Gagal scrape profil @$h: ${it.message}")
            }

            // Fallback scraping dengan cookie bila profil privat / 403
            if ((userId.isNullOrBlank() || secUid.isNullOrBlank()) && !cookieHeader.isNullOrBlank()) {
                runCatching {
                    val authHeaders = headers.toMutableMap().apply { put("Cookie", cookieHeader) }
                    val html = Http.getText("https://www.tiktok.com/@$h", referer = "https://www.tiktok.com/", headers = authHeaders)
                    val marker = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
                    val start = html.indexOf(marker)
                    if (start >= 0) {
                        val first = html.indexOf('>', start)
                        val last = html.indexOf("</script>", first)
                        if (first in 0 until last) {
                            val payload = html.substring(first + 1, last)
                            val root = json.parseToJsonElement(payload).jsonObject
                            val scope = root["__DEFAULT_SCOPE__"] as? JsonObject
                            val userDetail = scope?.get("webapp.user-detail") as? JsonObject
                            val userInfo = userDetail?.get("userInfo") as? JsonObject
                            val userObj = userInfo?.get("user") as? JsonObject
                            if (userObj != null) {
                                val uniqueId = userObj["uniqueId"]?.jsonPrimitive?.content
                                if (uniqueId != null && uniqueId.equals(h, ignoreCase = true)) {
                                    userId = userObj["id"]?.jsonPrimitive?.content
                                    secUid = userObj["secUid"]?.jsonPrimitive?.content
                                }
                            }
                        }
                    }
                    if (!userId.isNullOrBlank() && !secUid.isNullOrBlank()) {
                        tiktokUserCache[h] = Pair(userId!!, secUid!!)
                    }
                }.onFailure {
                    android.util.Log.w("RaPlatformApi", "Gagal scrape profil @$h dengan cookie: ${it.message}")
                }
            }

            // Fallback id & secUid bawaan untuk akun idol & user jika masih null
            if (userId.isNullOrBlank()) {
                when {
                    h.equals("jkt48.ella.a", ignoreCase = true) -> {
                        userId = "7059270147147744282"
                        secUid = "MS4wLjABAAAAXGnR-8ZJ_YICXhh1_oUpnVnqtiM2_eu634dgHj1GcW3AT3Sm0BP-6gpY1zUW71Oc"
                        tiktokUserCache[h] = Pair(userId!!, secUid!!)
                        android.util.Log.d("RaPlatformApi", "Menggunakan info profil tersimpan untuk @jkt48.ella.a")
                    }
                    h.equals("cathleenixie8", ignoreCase = true) -> {
                        userId = "7160634166081111067"
                        secUid = "MS4wLjABAAAACeqUz2zueObNqQkeTD4Lip36c_gksNpjRPm2-C_CZGZXjA1z3M5UFFnmtOG3fh8F"
                        tiktokUserCache[h] = Pair(userId!!, secUid!!)
                        android.util.Log.d("RaPlatformApi", "Menggunakan info profil tersimpan untuk @cathleenixie8")
                    }
                    h.equals("g1ttt", ignoreCase = true) -> {
                        userId = "6913036140283888642"
                        secUid = "MS4wLjABAAAA5kfF46q_shaT0enyiRanEDcUx53BuLQWo-Qs0xxBUX3N206VOt44N_fcX5a1xfAt"
                        tiktokUserCache[h] = Pair(userId!!, secUid!!)
                        android.util.Log.d("RaPlatformApi", "Menggunakan info profil tersimpan untuk @g1ttt")
                    }
                }
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
