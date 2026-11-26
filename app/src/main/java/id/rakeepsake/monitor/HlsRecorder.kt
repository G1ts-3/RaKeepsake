package id.rakeepsake.monitor

import id.rakeepsake.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Perekam HLS: unduh segmen playlist media dan gabungkan (concat) menjadi satu berkas
 * per live. Mengikuti konstanta terbukti dyrecord:
 *  - sambung ulang tiap 10 detik;
 *  - berhenti setelah 5 menit tidak bisa mendapat stream (grace offline);
 *  - konfirmasi akhir live 6× cek @ 3 detik (ENDLIST stabil) sebelum menutup berkas;
 *  - berhenti dengan rapi saat ruang disk di bawah ambang.
 * Output: .ts (MPEG-TS concat) atau .mp4 (fMP4: init segment + segmen .m4s).
 */
class HlsRecorder(
    private val urlProvider: suspend () -> String?,
    private val outputFile: File,
    private val minFreeBytes: Long,
    private val quality: String = "ASLI", // ASLI | 1080 | 720 | 480
    private val onProgress: (bytes: Long) -> Unit = {},
) {
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile var lastError: String? = null
        private set

    /** Status keluar: DONE_ENDLIST, STOPPED_LOW_SPACE, FAILED_OFFLINE, STOPPED_MANUAL. */
    var outcome: String = "FAILED"
        private set

    fun requestStop() {
        stopRequested.set(true)
        outcome = "STOPPED_MANUAL"
        try {
            activeCall?.cancel()
        } catch (_: Throwable) {}
    }

    /** Jalankan di thread kerja; memblokir hingga selesai. */
    fun run() {
        val playlist = Playlist()
        val downloadedKeys = HashSet<String>()
        val recentCrcs = LinkedHashSet<Long>()
        var lastSequenceNumber = -1L
        var haveInit = false
        var endConfirm = 0
        var lastGoodAt = System.currentTimeMillis()
        var noNewSegmentsSince = 0L
        var consecutiveFail = 0
        val startedAt = System.currentTimeMillis()

        var streamUrl: String? = null
        try {
            while (!stopRequested.get()) {
                val freeSpace = Storage_checkFree()
                if (freeSpace in 1 until minFreeBytes) {
                    outcome = "STOPPED_LOW_SPACE"
                    lastError = "Ruang penyimpanan tersisa hanya ${freeSpace / (1024 * 1024)} MB; rekaman dihentikan."
                    break
                }

                // Dapatkan atau perbarui URL stream hanya jika belum ada
                if (streamUrl.isNullOrBlank()) {
                    streamUrl = runCatching { kotlinx.coroutines.runBlocking { urlProvider() } }.getOrNull()
                    if (streamUrl.isNullOrBlank()) {
                        consecutiveFail++
                        if (offlineTooLong(lastGoodAt)) {
                            android.util.Log.i("HlsRecorder", "URL stream kosong selama grace offline (${OFFLINE_GRACE_MS / 1000}s), menyelesaikan rekaman.")
                            outcome = "DONE_ENDLIST"
                            break
                        }
                        sleepMs(2_000L)
                        continue
                    }
                }

                val currentUrl = streamUrl ?: continue

                // Jika stream langsung (bukan playlist HLS .m3u8)
                if (currentUrl.contains(".flv") || (!currentUrl.contains(".m3u8") && !currentUrl.contains("/hls/"))) {
                    recordDirectStream(currentUrl)
                    return
                }

                val fetched = fetchPlaylist(playlist, currentUrl)
                if (fetched == null) {
                    consecutiveFail++
                    if (consecutiveFail >= 3) {
                        // URL mungkin kadaluarsa, reset agar mencoba meminta URL stream baru
                        streamUrl = null
                    }
                    if (offlineTooLong(lastGoodAt)) {
                        android.util.Log.i("HlsRecorder", "Gagal fetch playlist selama grace offline (${OFFLINE_GRACE_MS / 1000}s), menyelesaikan rekaman.")
                        outcome = "DONE_ENDLIST"
                        break
                    }
                    sleepMs(2_000L)
                    continue
                }
                consecutiveFail = 0
                lastGoodAt = System.currentTimeMillis()

                val (masterUrl, mediaUrl, segments, endList, mapUri, hasMediaSequence) = fetched
                if (!haveInit && mapUri != null) {
                    val init = fetchBytes(resolve(mediaUrl ?: masterUrl, mapUri))
                    if (init != null) {
                        FileOutputStream(outputFile, true).use { it.write(init) }
                        haveInit = true
                    }
                }
                var appended = 0L
                for (seg in segments) {
                    // Cek 1: Jika playlist memiliki #EXT-X-MEDIA-SEQUENCE, lewati segmen lama
                    if (hasMediaSequence && lastSequenceNumber >= 0L && seg.sequenceNumber <= lastSequenceNumber) {
                        continue
                    }
                    // Cek 2: Lewati jika key kanonikal segmen (nama berkas tanpa token dinamis) sudah pernah diunduh
                    if (downloadedKeys.contains(seg.key)) {
                        continue
                    }
                    val baseFilename = seg.uri.substringBefore('?').substringAfterLast('/')
                    if (baseFilename.isNotBlank() && downloadedKeys.contains(baseFilename)) {
                        continue
                    }
                    if (downloadedKeys.contains(seg.uri)) {
                        continue
                    }

                    val targetUrl = resolve(mediaUrl ?: masterUrl, seg.uri)
                    val bytes = fetchBytes(targetUrl) ?: continue

                    // Cek 3: Verifikasi CRC32 konten byte agar tidak ada segmen berulang secara identik
                    val crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    if (recentCrcs.contains(crc)) {
                        android.util.Log.d("HlsRecorder", "Segmen duplikat byte dilewati: ${seg.key} (CRC $crc)")
                        downloadedKeys.add(seg.key)
                        continue
                    }
                    if (recentCrcs.size > 200) {
                        val first = recentCrcs.first()
                        recentCrcs.remove(first)
                    }
                    recentCrcs.add(crc)

                    downloadedKeys.add(seg.key)
                    if (baseFilename.isNotBlank()) downloadedKeys.add(baseFilename)
                    downloadedKeys.add(seg.uri)
                    if (hasMediaSequence && seg.sequenceNumber > lastSequenceNumber) {
                        lastSequenceNumber = seg.sequenceNumber
                    }

                    FileOutputStream(outputFile, true).use { it.write(bytes) }
                    appended += bytes.size
                }
                if (appended > 0) {
                    noNewSegmentsSince = 0L
                    onProgress(outputFile.length())
                } else {
                    if (noNewSegmentsSince == 0L) {
                        noNewSegmentsSince = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - noNewSegmentsSince > OFFLINE_GRACE_MS) {
                        // Tidak ada segmen baru selama grace period -> siaran selesai
                        android.util.Log.i("HlsRecorder", "Tidak ada segmen baru selama ${OFFLINE_GRACE_MS / 1000}s, siaran selesai.")
                        outcome = "DONE_ENDLIST"
                        break
                    }
                }

                if (endList) {
                    // Konfirmasi akhir: 6 cek @3 dtk agar segmen terakhir tersempurna.
                    if (endConfirm >= END_CONFIRM_CHECKS) { outcome = "DONE_ENDLIST"; break }
                    endConfirm++
                    sleepMs(END_CONFIRM_MS)
                } else {
                    endConfirm = 0
                    sleepMs(POLL_MEDIA_MS)
                }
            }
            if (outcome == "FAILED") outcome = "DONE_ENDLIST"
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            outcome = "FAILED"
        } finally {
            onProgress(outputFile.length())
        }
    }

    private fun recordDirectStream(initialUrl: String) {
        android.util.Log.i("HlsRecorder", "Merekam direct stream: $initialUrl ke ${outputFile.name}")
        var currentUrl = initialUrl
        var consecutiveErrors = 0
        var lastDataReceivedAt = System.currentTimeMillis()
        var isFirstConnection = (outputFile.length() == 0L)

        while (!stopRequested.get()) {
            val freeSpace = Storage_checkFree()
            if (freeSpace in 1 until minFreeBytes) {
                outcome = "STOPPED_LOW_SPACE"
                lastError = "Ruang penyimpanan tersisa hanya ${freeSpace / (1024 * 1024)} MB; rekaman dihentikan."
                break
            }

            try {
                val req = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", Http.UA_WEB)
                    .header("Referer", "https://www.tiktok.com/")
                    .build()
                val call = Http.client.newCall(req)
                activeCall = call
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            lastError = "HTTP ${resp.code} saat membuka stream langsung"
                            android.util.Log.w("HlsRecorder", lastError!!)
                            consecutiveErrors++
                        } else {
                            val body = resp.body
                            if (body != null) {
                                body.byteStream().use { input ->
                                    FileOutputStream(outputFile, true).use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        var bytesRead: Int
                                        var lastProgress = outputFile.length()

                                        // Jika sambung ulang dan berkas sudah memiliki data, lewati 13-byte FLV header agar berkas valid
                                        if (!isFirstConnection && (currentUrl.contains(".flv") || outputFile.name.endsWith(".bin"))) {
                                            val skippedHeader = ByteArray(13)
                                            var totalSkipped = 0
                                            while (totalSkipped < 13) {
                                                val r = input.read(skippedHeader, totalSkipped, 13 - totalSkipped)
                                                if (r == -1) break
                                                totalSkipped += r
                                            }
                                            android.util.Log.i("HlsRecorder", "Sambung ulang FLV: $totalSkipped byte header dilewati.")
                                        }

                                        while (!stopRequested.get()) {
                                            val free = Storage_checkFree()
                                            if (free in 1 until minFreeBytes) {
                                                outcome = "STOPPED_LOW_SPACE"
                                                return@recordDirectStream
                                            }
                                            bytesRead = input.read(buffer)
                                            if (bytesRead == -1) {
                                                android.util.Log.i("HlsRecorder", "Direct stream koneksi selesai (EOF dari server).")
                                                break
                                            }
                                            output.write(buffer, 0, bytesRead)
                                            lastDataReceivedAt = System.currentTimeMillis()
                                            consecutiveErrors = 0
                                            isFirstConnection = false
                                            val cur = outputFile.length()
                                            if (cur - lastProgress >= 128 * 1024) {
                                                lastProgress = cur
                                                onProgress(cur)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    activeCall = null
                }
            } catch (t: Throwable) {
                if (stopRequested.get()) {
                    outcome = "STOPPED_MANUAL"
                    return
                }
                android.util.Log.w("HlsRecorder", "Direct stream koneksi terputus: ${t.message}. Memeriksa status live...")
                consecutiveErrors++
            }

            if (stopRequested.get()) {
                outcome = "STOPPED_MANUAL"
                break
            }

            // Koneksi terputus: periksa apakah streamer masih live sebelum mengakhiri rekaman
            sleepMs(2000L)
            val freshUrl = runCatching { kotlinx.coroutines.runBlocking { urlProvider() } }.getOrNull()
            if (!freshUrl.isNullOrBlank()) {
                android.util.Log.i("HlsRecorder", "Streamer masih live, menyambung ulang koneksi stream...")
                currentUrl = freshUrl
                lastDataReceivedAt = System.currentTimeMillis()
                consecutiveErrors = 0
                continue
            }

            // Jika streamer offline atau gagal fetch URL selama lebih dari grace offline:
            if (System.currentTimeMillis() - lastDataReceivedAt > OFFLINE_GRACE_MS || consecutiveErrors >= 30) {
                android.util.Log.i("HlsRecorder", "Stream offline atau tidak dapat disambung (>${OFFLINE_GRACE_MS / 1000}s), rekaman selesai.")
                outcome = "DONE_ENDLIST"
                break
            }
            sleepMs(3000L)
        }

        if (outcome == "FAILED") outcome = "DONE_ENDLIST"
        onProgress(outputFile.length())
    }

    private fun offlineTooLong(lastGoodAt: Long): Boolean =
        System.currentTimeMillis() - lastGoodAt > OFFLINE_GRACE_MS

    private fun Storage_checkFree(): Long {
        val target = outputFile.parentFile?.takeIf { it.exists() } ?: outputFile
        val usable = target.usableSpace
        return if (usable > 0L) usable else target.freeSpace
    }

    data class SegmentRef(
        val uri: String,
        val sequenceNumber: Long,
        val key: String,
    )

    data class Fetched(
        val masterUrl: String,
        val mediaUrl: String?,
        val segments: List<SegmentRef>,
        val endList: Boolean,
        val mapUri: String?,
        val hasMediaSequence: Boolean,
    )

    /** Ambil playlist; pilih varian ber-bandwidth tertinggi bila master. */
    private fun fetchPlaylist(prev: Playlist, url: String): Fetched? {
        return try {
            val referer = when {
                url.contains("showroom") -> "https://www.showroom-live.com/"
                url.contains("idn") -> "https://www.idn.app/"
                else -> "https://www.tiktok.com/"
            }
            val body = Http.getText(url, referer = referer)
            if (body.contains("#EXT-X-STREAM-INF")) {
                val best = pickVariant(body)
                val variantUrl = resolve(url, best)
                val mediaBody = Http.getText(variantUrl, referer = referer)
                prev.ingest(variantUrl, mediaBody)
            } else {
                prev.ingest(url, body)
            }
        } catch (t: Throwable) {
            lastError = "playlist: ${t.message ?: t.javaClass.simpleName}"
            null
        }
    }

    private fun pickVariant(master: String): String {
        val lines = master.lines()
        data class Var(val bw: Long, val height: Int, val uri: String)
        val variants = mutableListOf<Var>()
        var pendingBw = 0L
        var pendingH = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                pendingBw = Regex("BANDWIDTH=(\\d+)").find(trimmed)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                pendingH = Regex("RESOLUTION=\\d+x(\\d+)").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                variants.add(Var(pendingBw, pendingH, trimmed))
                pendingBw = 0
                pendingH = 0
            }
        }
        if (variants.isEmpty()) {
            return lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: throw Exception("master tanpa varian")
        }
        // ASLI = varian tertinggi; 1080/720/480 = varian tertinggi yang tingginya <= target.
        val chosen: Var = if (quality == "ASLI" || variants.none { it.height > 0 }) {
            variants.maxByOrNull { it.bw }!!
        } else {
            val target = quality.toIntOrNull() ?: 1080
            (variants.filter { it.height in 1..target }.maxByOrNull { it.height }
                ?: variants.minByOrNull { it.height })!!
        }
        return chosen.uri
    }

    private fun resolve(base: String, ref: String): String {
        val resolved = if (ref.startsWith("http")) {
            ref
        } else {
            try {
                base.toHttpUrl().resolve(ref)?.toString() ?: ref
            } catch (_: Exception) {
                ref
            }
        }
        // Wariskan query string token keamanan jika ref tidak memiliki query
        return try {
            val baseHttp = base.toHttpUrl()
            val baseQuery = baseHttp.query
            if (!baseQuery.isNullOrBlank() && !resolved.contains("?")) {
                "$resolved?$baseQuery"
            } else {
                resolved
            }
        } catch (_: Exception) {
            resolved
        }
    }

    private fun fetchBytes(url: String): ByteArray? = try {
        Http.client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", Http.UA_WEB)
                .header("Referer", "https://www.tiktok.com/")
                .build(),
        ).execute().use { resp ->
            if (resp.isSuccessful) {
                resp.body?.bytes()
            } else {
                lastError = "HTTP ${resp.code} saat unduh segmen: $url"
                android.util.Log.w("HlsRecorder", "Gagal unduh segmen: code=${resp.code}, url=$url")
                null
            }
        }
    } catch (t: Throwable) {
        lastError = "segmen: ${t.message ?: t.javaClass.simpleName}"
        android.util.Log.w("HlsRecorder", "Error fetchBytes $url: ${t.message}")
        null
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Parser playlist media sederhana: segmen berurutan + ENDLIST + EXT-X-MAP + MEDIA-SEQUENCE. */
    class Playlist {
        fun ingest(baseUrl: String, body: String): Fetched {
            var mapUri: String? = null
            val segments = mutableListOf<SegmentRef>()
            var endList = false
            var pending: String? = null
            var mediaSequence = 0L
            var hasExplicitSeq = false

            val lines = body.lines()
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    mediaSequence = line.removePrefix("#EXT-X-MEDIA-SEQUENCE:").trim().toLongOrNull() ?: 0L
                    hasExplicitSeq = true
                    break
                }
            }

            var currentSeq = mediaSequence
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-MAP:") -> {
                        mapUri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    }
                    line.startsWith("#EXTINF") -> { pending = "next" }
                    line.startsWith("#EXT-X-ENDLIST") -> { endList = true }
                    line.startsWith("#") -> { /* abaikan komentar lain */ }
                    pending == "next" -> {
                        // Ekstraksi nama file / path tanpa query parameter dinamis (?expire=...&sign=...)
                        val cleanPath = line.substringBefore('?')
                        val filename = cleanPath.substringAfterLast('/').ifBlank { cleanPath }
                        val key = if (hasExplicitSeq) {
                            "seq_${currentSeq}_$filename"
                        } else {
                            filename
                        }
                        segments.add(SegmentRef(uri = line, sequenceNumber = currentSeq, key = key))
                        pending = null
                        currentSeq++
                    }
                }
            }
            return Fetched(baseUrl, baseUrl, segments, endList, mapUri, hasExplicitSeq)
        }
    }

    companion object {
        const val RECONNECT_MS = 10_000L
        const val OFFLINE_GRACE_MS = 5 * 60_000L
        const val END_CONFIRM_CHECKS = 6
        const val END_CONFIRM_MS = 3_000L
        const val POLL_MEDIA_MS = 3_000L
    }
}
