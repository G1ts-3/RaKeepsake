package id.rakeepsake.data

import id.rakeepsake.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pengimpor dan pengeksport daftar akun/roster idol.
 * Kompatibel dengan format roster dyrecord (`members.json`), format preset.id, dan format native RaKeepsake.
 */
object RosterTransfer {

    const val DEFAULT_PRESET_URL = "https://api.crstlnz.my.id/api/member?group=jkt48"

    data class MemberEntry(
        val name: String,
        val fullName: String = "",
        val showroomKey: String = "",
        val idnUser: String = "",
        val tiktokUser: String = "",
    )

    /**
     * Membaca berkas preset default dari assets/members.json.
     */
    fun loadFromAsset(context: android.content.Context): List<MemberEntry> {
        return try {
            context.assets.open("members.json").bufferedReader().use {
                parseJson(it.readText())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Mengunduh roster JSON dari URL web secara asinkron.
     * Jika URL gagal atau 404, mencoba fallback ke aset lokal jika context disediakan.
     */
    fun fetchFromUrl(url: String, context: android.content.Context? = null): List<MemberEntry> {
        return try {
            val jsonStr = Http.getText(url.trim())
            val parsed = parseJson(jsonStr)
            if (parsed.isNotEmpty()) parsed
            else if (context != null) loadFromAsset(context)
            else emptyList()
        } catch (e: Exception) {
            if (context != null) {
                val fallback = loadFromAsset(context)
                if (fallback.isNotEmpty()) return fallback
            }
            throw e
        }
    }

    /**
     * Parse string JSON menjadi daftar MemberEntry.
     * Mendukung:
     * 1. Format dyrecord: {"members": [{"nama_panggilan": "...", "media_showroom": "...", "media_idn": "...", "media_tiktok": "..."}]}
     * 2. Format preset.id: {"members": [{"nama": "...", "platform": {"Showroom_user": "...", "IDN_user": "...", "TikTok_user": "..."}}]}
     * 3. Format list akun sederhana: [{"name": "...", "platform": "...", "handle": "..."}]
     */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private fun JsonObject.str(key: String, default: String = ""): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: default

    fun parseJson(jsonStr: String): List<MemberEntry> {
        val trimmed = jsonStr.trim()
        val list = mutableListOf<MemberEntry>()

        try {
            val parsed = json.parseToJsonElement(trimmed)
            if (parsed is JsonObject) {
                val arr = parsed["members"] as? JsonArray ?: return emptyList()
                for (i in arr.indices) {
                    val m = arr[i] as? JsonObject ?: continue
                    val name = m.str("nama_panggilan").ifBlank {
                        m.str("nama").ifBlank { m.str("nama_lengkap", "Member ${i + 1}") }
                    }
                    val fullName = m.str("nama_lengkap", name).ifBlank { name }

                    var sr = m.str("media_showroom").trim()
                    var idn = m.str("media_idn").trim()
                    var tt = m.str("media_tiktok").trim()

                    // Periksa nested object "platform" jika ada
                    val p = m["platform"] as? JsonObject
                    if (p != null) {
                        if (sr.isBlank()) sr = p.str("Showroom_user").ifBlank { p.str("media_showroom") }.trim()
                        if (idn.isBlank()) idn = p.str("IDN_user").ifBlank { p.str("media_idn") }.trim()
                        if (tt.isBlank()) tt = p.str("TikTok_user").ifBlank { p.str("tiktok_user").ifBlank { p.str("media_tiktok") } }.trim()
                    }

                    if (sr.contains("/")) sr = sr.substringAfterLast("/").substringAfterLast("=")
                    if (sr.contains("?")) sr = sr.substringBefore("?")
                    idn = idn.removePrefix("@")
                    tt = tt.removePrefix("@")

                    if (sr.isNotBlank() || idn.isNotBlank() || tt.isNotBlank()) {
                        list.add(MemberEntry(name, fullName, sr, idn, tt))
                    }
                }
            } else if (parsed is JsonArray) {
                for (i in parsed.indices) {
                    val item = parsed[i] as? JsonObject ?: continue
                    if (item.containsKey("socials") || item.containsKey("idn_username") || item.containsKey("nicknames")) {
                        val graduated = (item["is_graduate"] as? JsonPrimitive)?.booleanOrNull ?: false
                        if (graduated) continue
                        val fullName = item.str("name").trim()
                        val nicks = item["nicknames"] as? JsonArray
                        val nick = (nicks?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: fullName.substringBefore(' ').ifBlank { "Member ${i + 1}" }
                        var idn = item.str("idn_username").trim().removePrefix("@")
                        var sr = ""
                        var tt = ""
                        val socials = item["socials"] as? JsonArray
                        if (socials != null) {
                            for (el in socials) {
                                val s = el as? JsonObject ?: continue
                                val title = s.str("title").lowercase()
                                val surl = s.str("url").trim()
                                when {
                                    title.contains("tiktok") && tt.isBlank() -> {
                                        tt = Regex("tiktok\\.com/@([^/?#]+)", RegexOption.IGNORE_CASE)
                                            .find(surl)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                                    }
                                    title.contains("showroom") && sr.isBlank() -> {
                                        sr = Regex("showroom-live\\.com/(?:r/)?([^/?#]+)", RegexOption.IGNORE_CASE)
                                            .find(surl)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                                    }
                                    title.contains("idn") && idn.isBlank() -> {
                                        idn = Regex("idn\\.app/([^/?#]+)", RegexOption.IGNORE_CASE)
                                            .find(surl)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                                    }
                                }
                            }
                        }
                        if (sr.isNotBlank() || idn.isNotBlank() || tt.isNotBlank()) {
                            list.add(MemberEntry(nick, fullName.ifBlank { nick }, sr, idn, tt))
                        }
                    } else {
                        val name = item.str("displayName").ifBlank { item.str("handle", "Akun ${i + 1}") }
                        val platform = item.str("platform").uppercase()
                        val handle = item.str("handle").trim().removePrefix("@")
                        when (platform) {
                            "TIKTOK" -> list.add(MemberEntry(name = name, tiktokUser = handle))
                            "SHOWROOM" -> list.add(MemberEntry(name = name, showroomKey = handle))
                            "IDN" -> list.add(MemberEntry(name = name, idnUser = handle))
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return list
    }

    /**
     * Konversi daftar MemberEntry menjadi entitas Account Room.
     */
    fun toAccounts(entries: List<MemberEntry>): List<Account> {
        val out = mutableListOf<Account>()
        for (m in entries) {
            val displayName = m.name.ifBlank { m.fullName }
            if (m.tiktokUser.isNotBlank()) {
                out.add(
                    Account(
                        platform = Platform.TIKTOK.name,
                        handle = m.tiktokUser,
                        displayName = displayName,
                        recordLive = true,
                        watchPosts = true,
                    )
                )
            }
            if (m.showroomKey.isNotBlank()) {
                out.add(
                    Account(
                        platform = Platform.SHOWROOM.name,
                        handle = m.showroomKey,
                        displayName = displayName,
                        recordLive = true,
                        watchPosts = false,
                    )
                )
            }
            if (m.idnUser.isNotBlank()) {
                out.add(
                    Account(
                        platform = Platform.IDN.name,
                        handle = m.idnUser,
                        displayName = displayName,
                        recordLive = true,
                        watchPosts = false,
                    )
                )
            }
        }
        return out
    }

    /**
     * Memasukkan daftar MemberEntry ke database tanpa menduplikasi akun yang sudah ada.
     * Mengembalikan jumlah akun baru yang berhasil ditambahkan.
     */
    suspend fun importIntoDb(accountsDao: AccountDao, entries: List<MemberEntry>): Int {
        val existing = accountsDao.allOnce()
        val existingKeys = existing.map { "${it.platform.uppercase()}:${it.handle.lowercase()}" }.toSet()
        val toInsert = toAccounts(entries).filter {
            "${it.platform.uppercase()}:${it.handle.lowercase()}" !in existingKeys
        }
        toInsert.forEach { accountsDao.insert(it) }
        return toInsert.size
    }

    /**
     * Ekspor seluruh akun tersimpan menjadi string JSON dyrecord-compatible.
     */
    fun exportToJson(accounts: List<Account>): String {
        val grouped = accounts.groupBy { it.displayName.ifBlank { it.handle } }
        val root = buildJsonObject {
            put(
                "members",
                buildJsonArray {
                    for ((displayName, accs) in grouped) {
                        add(
                            buildJsonObject {
                                put("nama_panggilan", displayName)
                                put("nama_lengkap", displayName)
                                val tt = accs.firstOrNull { it.platform.equals("TIKTOK", true) }?.handle
                                val sr = accs.firstOrNull { it.platform.equals("SHOWROOM", true) }?.handle
                                val idn = accs.firstOrNull { it.platform.equals("IDN", true) }?.handle
                                tt?.let { put("media_tiktok", it) }
                                sr?.let { put("media_showroom", it) }
                                idn?.let { put("media_idn", it) }
                                put(
                                    "platform",
                                    buildJsonObject {
                                        tt?.let { put("TikTok_user", it) }
                                        sr?.let { put("Showroom_user", it) }
                                        idn?.let { put("IDN_user", it) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }
}
