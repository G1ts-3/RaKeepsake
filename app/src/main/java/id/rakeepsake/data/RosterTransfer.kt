package id.rakeepsake.data

import id.rakeepsake.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pengimpor dan pengeksport daftar akun/roster idol.
 * Kompatibel dengan format roster dyrecord (`members.json`), format preset.id, dan format native RaKeepsake.
 */
object RosterTransfer {

    const val DEFAULT_PRESET_URL = "https://jkt48.preset.id/members.json"

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
    fun parseJson(jsonStr: String): List<MemberEntry> {
        val trimmed = jsonStr.trim()
        val list = mutableListOf<MemberEntry>()

        try {
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.has("members")) {
                    val arr = obj.getJSONArray("members")
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        var name = m.optString("nama_panggilan").ifBlank {
                            m.optString("nama", m.optString("nama_lengkap", "Member ${i + 1}"))
                        }
                        val fullName = m.optString("nama_lengkap", name)

                        var sr = m.optString("media_showroom", "").trim()
                        var idn = m.optString("media_idn", "").trim()
                        var tt = m.optString("media_tiktok", "").trim()

                        // Periksa nested object "platform" jika ada (format preset.id)
                        if (m.has("platform")) {
                            val p = m.optJSONObject("platform")
                            if (p != null) {
                                if (sr.isBlank()) sr = p.optString("Showroom_user", p.optString("media_showroom", "")).trim()
                                if (idn.isBlank()) idn = p.optString("IDN_user", p.optString("media_idn", "")).trim()
                                if (tt.isBlank()) tt = p.optString("TikTok_user", p.optString("tiktok_user", p.optString("media_tiktok", ""))).trim()
                            }
                        }

                        // Bersihkan URL showroom menjadi key jika formatnya URL
                        if (sr.contains("/")) sr = sr.substringAfterLast("/").substringAfterLast("=")
                        if (sr.contains("?")) sr = sr.substringBefore("?")
                        idn = idn.removePrefix("@")
                        tt = tt.removePrefix("@")

                        if (sr.isNotBlank() || idn.isNotBlank() || tt.isNotBlank()) {
                            list.add(MemberEntry(name, fullName, sr, idn, tt))
                        }
                    }
                }
            } else if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val name = item.optString("displayName").ifBlank { item.optString("handle", "Akun ${i + 1}") }
                    val platform = item.optString("platform", "").uppercase()
                    val handle = item.optString("handle", "").trim().removePrefix("@")
                    when (platform) {
                        "TIKTOK" -> list.add(MemberEntry(name = name, tiktokUser = handle))
                        "SHOWROOM" -> list.add(MemberEntry(name = name, showroomKey = handle))
                        "IDN" -> list.add(MemberEntry(name = name, idnUser = handle))
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
        val root = JSONObject()
        val arr = JSONArray()

        for ((displayName, accs) in grouped) {
            val m = JSONObject()
            m.put("nama_panggilan", displayName)
            m.put("nama_lengkap", displayName)
            val platformObj = JSONObject()
            for (a in accs) {
                when (a.platform.uppercase()) {
                    "TIKTOK" -> {
                        m.put("media_tiktok", a.handle)
                        platformObj.put("TikTok_user", a.handle)
                    }
                    "SHOWROOM" -> {
                        m.put("media_showroom", a.handle)
                        platformObj.put("Showroom_user", a.handle)
                    }
                    "IDN" -> {
                        m.put("media_idn", a.handle)
                        platformObj.put("IDN_user", a.handle)
                    }
                }
            }
            m.put("platform", platformObj)
            arr.put(m)
        }
        root.put("members", arr)
        return root.toString(2)
    }
}
