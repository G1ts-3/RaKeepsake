package id.rakeepsake.util

import androidx.compose.runtime.Immutable
import id.rakeepsake.data.Account
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class MediaType { LIVE, STORY, POST, PHOTO, AUDIO }

/** Utilitas pemformatan judul manusiawi, tanggal kelompok, dan waktu relatif untuk UI penggemar. */
object Humanizer {

    @Immutable
    data class HumanMediaInfo(
        val title: String,
        val creatorHandle: String,
        val creatorLabel: String,
        val versionTag: String?,
        val dateTimeLabel: String,
        val platformLabel: String,
        val mediaType: MediaType,
    )

    private val STD_REGEX = Regex(
        "^(tt|idn|sr)_(live|story|post)_(.+?)_(minggu|senin|selasa|rabu|kamis|jumat|sabtu)_(\\d{2}-\\d{2}-\\d{4})(?:_(.+))?$",
        RegexOption.IGNORE_CASE,
    )
    private val DUP_PAREN_REGEX = Regex("\\((\\d+)\\)$")

    fun mediaTypeOf(entry: Storage.LibEntry): MediaType = when {
        entry.mime.startsWith("audio/") -> MediaType.AUDIO
        entry.mime.startsWith("image/") -> MediaType.PHOTO
        entry.kind == "LIVE" -> MediaType.LIVE
        entry.kind == "STORY" -> MediaType.STORY
        else -> MediaType.POST
    }

    fun humanizeEntry(entry: Storage.LibEntry, accountByHandle: Map<String, Account>): HumanMediaInfo {
        val mediaType = mediaTypeOf(entry)
        val rawStem = entry.name.substringBeforeLast('.').trim()

        // Tangani akhiran duplikat sistem Android seperti " (1)", " (2)"
        val dupMatch = DUP_PAREN_REGEX.find(rawStem)
        var versionTag: String? = dupMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { "Versi ${it + 1}" }
        val cleanStem = rawStem.replace(DUP_PAREN_REGEX, "").trim()

        val timeFormatted = SimpleDateFormat("dd MMM · HH:mm", Locale("id", "ID"))
            .format(Date(entry.dateModified * 1000L))

        val std = STD_REGEX.find(cleanStem)
        if (std != null) {
            val platCode = std.groupValues[1].lowercase()
            val typeCode = std.groupValues[2].lowercase()
            val handle = std.groupValues[3]
            val extra = std.groupValues[6].trim()

            val platformLabel = when (platCode) {
                "tt" -> "TikTok"
                "sr" -> "SHOWROOM"
                "idn" -> "IDN Live"
                else -> "TikTok"
            }

            val acc = accountByHandle[handle.lowercase()]
                ?: accountByHandle.entries.firstOrNull { it.key.equals(handle, ignoreCase = true) }?.value
            val creatorLabel = acc?.displayName?.takeIf { it.isNotBlank() } ?: "@$handle"

            val isAudio = entry.mime.startsWith("audio/") || extra.contains("audio", ignoreCase = true)
            val isPhoto = entry.mime.startsWith("image/")

            // Cek nomor urut carousel atau versi tambahan (_02, _audio_02)
            val tailNum = Regex("(?:^|_)(\\d{2})$").find(extra)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (versionTag == null && tailNum != null) {
                versionTag = if (isPhoto) "Foto #$tailNum" else "Bagian #$tailNum"
            }

            val baseVerb = when {
                isAudio && typeCode == "story" -> "Audio Story"
                isAudio -> "Audio Postingan"
                typeCode == "live" -> "Live"
                typeCode == "story" -> "Story"
                isPhoto -> "Foto"
                else -> "Postingan"
            }

            val dayWord = std.groupValues[4].replaceFirstChar { it.uppercase() }
            val dateParts = std.groupValues[5].split("-")
            val shortDate = if (dateParts.size == 3) {
                val monthName = monthShort(dateParts[1].toIntOrNull() ?: 1)
                "${dateParts[0].toIntOrNull() ?: dateParts[0]} $monthName"
            } else ""

            val title = buildString {
                append("$baseVerb $creatorLabel")
                if (shortDate.isNotBlank()) append(" · $dayWord, $shortDate")
            }

            return HumanMediaInfo(
                title = title,
                creatorHandle = handle,
                creatorLabel = creatorLabel,
                versionTag = versionTag,
                dateTimeLabel = timeFormatted,
                platformLabel = platformLabel,
                mediaType = mediaType,
            )
        }

        // Fallback untuk berkas lama atau dari folder khusus
        val folderHandle = entry.relativeDir
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() && !it.equals(Storage.APP_DIR, true) && !it.equals(Storage.REC_DIR, true) }
            ?: ""
        val acc = accountByHandle[folderHandle.lowercase()]
        val creatorLabel = acc?.displayName?.takeIf { it.isNotBlank() }
            ?: if (folderHandle.isNotBlank()) "@$folderHandle" else "Arsip"

        val title = cleanStem
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { entry.name }

        return HumanMediaInfo(
            title = title,
            creatorHandle = folderHandle,
            creatorLabel = creatorLabel,
            versionTag = versionTag,
            dateTimeLabel = timeFormatted,
            platformLabel = if (entry.kind == "LIVE") "Live" else "TikTok",
            mediaType = mediaType,
        )
    }

    fun formatDateSectionHeader(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "Tanggal Tidak Diketahui"
        val itemCal = Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000L }
        val nowCal = Calendar.getInstance()

        if (isSameDay(itemCal, nowCal)) return "Hari ini"

        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (isSameDay(itemCal, yesterdayCal)) return "Kemarin"

        return SimpleDateFormat("EEEE, d MMM yyyy", Locale("id", "ID")).format(itemCal.time)
            .replaceFirstChar { it.uppercase() }
    }

    fun formatRelativeLastLive(lastLiveAtMs: Long): String {
        if (lastLiveAtMs <= 0L) return "Offline"
        val diffMs = System.currentTimeMillis() - lastLiveAtMs
        if (diffMs < 60_000L) return "Offline · baru saja live"
        val mins = diffMs / 60_000L
        if (mins < 60) return "Offline · live $mins mnt lalu"
        val hours = mins / 60
        if (hours < 24) return "Offline · live $hours jam lalu"
        val days = hours / 24
        if (days == 1L) return "Offline · live kemarin"
        if (days < 7) return "Offline · live $days hari lalu"
        val dateStr = SimpleDateFormat("d MMM", Locale("id", "ID")).format(Date(lastLiveAtMs))
        return "Offline · live $dateStr"
    }

    fun formatElapsedDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun formatLongHoursMinutes(totalMs: Long): String {
        val mins = (totalMs / 60_000L).coerceAtLeast(0L)
        val h = mins / 60
        val m = mins % 60
        return when {
            h > 0 && m > 0 -> "$h jam $m mnt"
            h > 0 -> "$h jam"
            m > 0 -> "$m mnt"
            else -> "< 1 mnt"
        }
    }

    private fun isSameDay(c1: Calendar, c2: Calendar): Boolean =
        c1.get(Calendar.ERA) == c2.get(Calendar.ERA) &&
            c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)

    private fun monthShort(m: Int): String = when (m) {
        1 -> "Jan"
        2 -> "Feb"
        3 -> "Mar"
        4 -> "Apr"
        5 -> "Mei"
        6 -> "Jun"
        7 -> "Jul"
        8 -> "Agu"
        9 -> "Sep"
        10 -> "Okt"
        11 -> "Nov"
        12 -> "Des"
        else -> ""
    }
}
