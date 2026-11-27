package id.rakeepsake.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Storage {
    const val APP_DIR = "RaKeepsake"
    const val POST_DIR = "Postingan"
    const val STORY_DIR = "Story"
    const val REC_DIR = "Rekaman"

    fun detectKind(relativeDir: String, name: String): String {
        val r = relativeDir.lowercase()
        val n = name.lowercase()
        return when {
            n.contains("_story_") || r.contains("story") -> "STORY"
            n.contains("_live_") || r.contains("rekaman") || r.contains("live") -> "LIVE"
            n.contains("_post_") || r.contains("postingan") -> "POSTINGAN"
            else -> "POSTINGAN"
        }
    }

    /**
     * Membentuk nama berkas terstandar sesuai permintaan:
     * PlatformnyaApa(tt/idn/sr)_tipenya(post/story/live)_namanyaSiapa_hari_tanggal-bulan-tahun
     * Contoh: tt_post_sayoraraw_selasa_24-11-2026.mp4
     */
    fun buildFileName(
        platform: String,
        type: String,
        handle: String,
        timestampMs: Long = System.currentTimeMillis(),
        ext: String = "mp4",
        index: Int? = null,
        suffix: String? = null,
    ): String {
        val plat = when (platform.uppercase()) {
            "TIKTOK" -> "tt"
            "IDN" -> "idn"
            "SHOWROOM" -> "sr"
            else -> platform.lowercase().take(5)
        }
        val typ = when (type.uppercase()) {
            "STORY" -> "story"
            "LIVE" -> "live"
            else -> "post"
        }
        val cleanHandle = handle.removePrefix("@").trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")

        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val hari = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "minggu"
            Calendar.MONDAY -> "senin"
            Calendar.TUESDAY -> "selasa"
            Calendar.WEDNESDAY -> "rabu"
            Calendar.THURSDAY -> "kamis"
            Calendar.FRIDAY -> "jumat"
            Calendar.SATURDAY -> "sabtu"
            else -> "senin"
        }
        val tgl = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(timestampMs))

        val base = "${plat}_${typ}_${cleanHandle}_${hari}_${tgl}"

        return when {
            index != null -> "${base}_%02d.$ext".format(index)
            !suffix.isNullOrBlank() -> "${base}_$suffix.$ext"
            else -> "$base.$ext"
        }
    }

    /**
     * Menghasilkan nama berkas unik yang belum dipakai di Pustaka.
     * Jika berkas dengan nama dasar sudah ada, ditambahkan penomoran `_02`, `_03`, dst.
     */
    fun resolveUniqueFileName(
        context: Context,
        platform: String,
        type: String,
        handle: String,
        timestampMs: Long = System.currentTimeMillis(),
        ext: String = "mp4",
        index: Int? = null,
        suffix: String? = null,
    ): String {
        val baseName = buildFileName(platform, type, handle, timestampMs, ext, index, suffix)
        if (index != null || !suffix.isNullOrBlank()) {
            return baseName
        }

        val existingNames = runCatching {
            queryLibrary(context).map { it.name.lowercase() }.toSet()
        }.getOrDefault(emptySet())

        if (!existingNames.contains(baseName.lowercase())) {
            return baseName
        }

        val nameWithoutExt = baseName.substringBeforeLast(".")
        for (i in 2..99) {
            val candidate = "${nameWithoutExt}_%02d.$ext".format(i)
            if (!existingNames.contains(candidate.lowercase())) {
                return candidate
            }
        }
        return "${nameWithoutExt}_${System.currentTimeMillis() % 10000}.$ext"
    }

    fun sanitize(name: String, maxLen: Int = 80): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ").trim().trim('.')
        return if (cleaned.isBlank()) "tanpa-nama" else cleaned.take(maxLen)
    }

    fun freeBytes(dir: File = Environment.getExternalStorageDirectory()): Long =
        runCatching { android.os.StatFs(dir.absolutePath).availableBytes }.getOrDefault(dir.usableSpace)

    fun totalBytes(dir: File = Environment.getExternalStorageDirectory()): Long =
        runCatching { android.os.StatFs(dir.absolutePath).totalBytes }.getOrDefault(dir.totalSpace)

    /** Salin berkas sementara ke MediaStore (Movies/RaKeepsake/...). Kembalikan Uri. */
    fun publish(
        context: Context,
        file: File,
        relativeDir: String,
        displayName: String,
        mime: String,
    ): Uri? {
        val nowSec = System.currentTimeMillis() / 1000L
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$relativeDir")
            put(MediaStore.Video.Media.DATE_ADDED, nowSec)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowSec)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Tidak bisa membuka output untuk $displayName")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            // Trigger MediaScanner agar langsung terindeks di Galeri & Pustaka
            try {
                val realPath = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                if (!realPath.isNullOrBlank()) {
                    MediaScannerConnection.scanFile(context, arrayOf(realPath), arrayOf(mime), null)
                }
            } catch (_: Throwable) {}
            resolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
            resolver.notifyChange(uri, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    fun publishImage(context: Context, file: File, relativeDir: String, displayName: String): Uri? {
        val nowSec = System.currentTimeMillis() / 1000L
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$relativeDir")
            put(MediaStore.Images.Media.DATE_ADDED, nowSec)
            put(MediaStore.Images.Media.DATE_MODIFIED, nowSec)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Tidak bisa membuka output untuk $displayName")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            runCatching {
                val realPath = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                if (!realPath.isNullOrBlank()) {
                    MediaScannerConnection.scanFile(context, arrayOf(realPath), arrayOf("image/jpeg"), null)
                }
                resolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
                resolver.notifyChange(uri, null)
            }
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        else -> "application/octet-stream"
    }

    /** Salin berkas audio sementara ke MediaStore.Audio (Music/RaKeepsake/...). */
    fun publishAudio(context: Context, file: File, relativeDir: String, displayName: String): Uri? {
        val nowSec = System.currentTimeMillis() / 1000L
        val mime = mimeFor(displayName.substringAfterLast('.'))
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$relativeDir")
            put(MediaStore.Audio.Media.DATE_ADDED, nowSec)
            put(MediaStore.Audio.Media.DATE_MODIFIED, nowSec)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Tidak bisa membuka output untuk $displayName")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            runCatching {
                val realPath = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                if (!realPath.isNullOrBlank()) {
                    MediaScannerConnection.scanFile(context, arrayOf(realPath), arrayOf(mime), null)
                }
                resolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null)
                resolver.notifyChange(uri, null)
            }
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Salin isi satu Uri ke Uri lain (SAF / MediaStore / berkas luar). */
    fun copyUri(context: Context, src: Uri, dst: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(src)?.use { input ->
                context.contentResolver.openOutputStream(dst)?.use { output ->
                    input.copyTo(output)
                    true
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Daftar entri MediaStore di bawah Movies/RaKeepsake, Pictures/RaKeepsake, dan Music/RaKeepsake untuk pustaka. */
    @Immutable
    data class LibEntry(
        val uri: Uri,
        val name: String,
        val relativeDir: String,
        val size: Long,
        val dateModified: Long,
        val durationMs: Long,
        val mime: String,
        val kind: String = "POSTINGAN",
    )

    private val fallbackDurationCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun queryLibrary(context: Context): List<LibEntry> {
        val out = mutableListOf<LibEntry>()
        val resolver = context.contentResolver

        // 1. Kueri Video (Movies/RaKeepsake/...)
        val videoProjection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.IS_PENDING,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_TRASHED)
        }.toTypedArray()
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?",
            arrayOf("Movies/$APP_DIR%", "$APP_DIR%", "%/$APP_DIR/%"),
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            val pendingIdx = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val trashedIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1
            while (c.moveToNext()) {
                if ((pendingIdx >= 0 && c.getInt(pendingIdx) == 1) || (trashedIdx >= 0 && c.getInt(trashedIdx) == 1)) continue
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val path = runCatching { c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)) }.getOrNull()

                // Cek apakah berkas masih ada di storage
                val stillExists = if (!path.isNullOrBlank()) {
                    File(path).exists()
                } else {
                    runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
                }

                if (!stillExists) {
                    runCatching { resolver.delete(uri, null, null) }
                    continue
                }

                val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: ""
                val relDir = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)) ?: ""
                val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)) ?: "video/mp4"
                val size = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                val dateMod = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED))
                var duration = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                if (duration <= 0L && mime.startsWith("video/")) {
                    val durKey = "$uri|$size|$dateMod"
                    duration = fallbackDurationCache.getOrPut(durKey) {
                        runCatching {
                            val mmr = android.media.MediaMetadataRetriever()
                            try {
                                mmr.setDataSource(context, uri)
                                mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull() ?: 0L
                            } finally {
                                runCatching { mmr.release() }
                            }
                        }.getOrDefault(0L)
                    }
                }
                out.add(
                    LibEntry(
                        uri = uri,
                        name = name,
                        relativeDir = relDir,
                        size = size,
                        dateModified = dateMod,
                        durationMs = duration,
                        mime = mime,
                        kind = detectKind(relDir, name),
                    ),
                )
            }
        }

        // 2. Kueri Foto/Gambar (Pictures/RaKeepsake/...)
        val imageProjection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.IS_PENDING,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_TRASHED)
        }.toTypedArray()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?",
            arrayOf("Pictures/$APP_DIR%", "$APP_DIR%", "%/$APP_DIR/%"),
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            val pendingIdx = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val trashedIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1
            while (c.moveToNext()) {
                if ((pendingIdx >= 0 && c.getInt(pendingIdx) == 1) || (trashedIdx >= 0 && c.getInt(trashedIdx) == 1)) continue
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val path = runCatching { c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)) }.getOrNull()

                val stillExists = if (!path.isNullOrBlank()) {
                    File(path).exists()
                } else {
                    runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
                }

                if (!stillExists) {
                    runCatching { resolver.delete(uri, null, null) }
                    continue
                }

                val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: ""
                val relDir = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)) ?: ""
                out.add(
                    LibEntry(
                        uri = uri,
                        name = name,
                        relativeDir = relDir,
                        size = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
                        dateModified = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)),
                        durationMs = 0L,
                        mime = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/jpeg",
                        kind = detectKind(relDir, name),
                    ),
                )
            }
        }

        // 3. Kueri Audio (Music/RaKeepsake/...)
        val audioProjection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.IS_PENDING,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_TRASHED)
        }.toTypedArray()
        runCatching {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                audioProjection,
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf("Music/$APP_DIR%", "$APP_DIR%", "%/$APP_DIR/%"),
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",
            )?.use { c ->
                val pendingIdx = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                val trashedIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1
                while (c.moveToNext()) {
                    if ((pendingIdx >= 0 && c.getInt(pendingIdx) == 1) || (trashedIdx >= 0 && c.getInt(trashedIdx) == 1)) continue
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val path = runCatching { c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)) }.getOrNull()

                    val stillExists = if (!path.isNullOrBlank()) {
                        File(path).exists()
                    } else {
                        runCatching {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                        }.getOrDefault(false)
                    }

                    if (!stillExists) {
                        runCatching { resolver.delete(uri, null, null) }
                        continue
                    }

                    val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)) ?: ""
                    val relDir = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)) ?: ""
                    val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)) ?: "audio/mp4"
                    var duration = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                    if (duration <= 0L) {
                        duration = runCatching {
                            val mmr = android.media.MediaMetadataRetriever()
                            mmr.setDataSource(context, uri)
                            val dStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            mmr.release()
                            dStr?.toLongOrNull() ?: 0L
                        }.getOrDefault(0L)
                    }
                    out.add(
                        LibEntry(
                            uri = uri,
                            name = name,
                            relativeDir = relDir,
                            size = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)),
                            dateModified = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)),
                            durationMs = duration,
                            mime = mime,
                            kind = detectKind(relDir, name),
                        ),
                    )
                }
            }
        }

        return out.sortedByDescending { it.dateModified }
    }

    /** Hapus satu berkas lewat MediaStore (agar Galeri ikut terhapus) dan bersihkan sisa disk jika ada. */
    fun deleteEntry(context: Context, uri: Uri, name: String? = null, relativeDir: String? = null): Boolean {
        var deleted = false
        var physicalPath: String? = null

        // 1. Dapatkan path fisik berkas sebelum baris MediaStore dihapus
        runCatching {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    physicalPath = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                }
            }
        }

        val pathToDelete = physicalPath ?: run {
            if (name != null && relativeDir != null) {
                val cleanDir = if (relativeDir.endsWith("/")) relativeDir else "$relativeDir/"
                File(Environment.getExternalStorageDirectory(), "$cleanDir$name").absolutePath
            } else null
        }

        // 2. Hapus lewat MediaStore ContentResolver lebih dulu supaya sistem Android/Galeri langsung membuang entri & thumbnail-nya
        var mediaStoreException: Exception? = null
        try {
            if (context.contentResolver.delete(uri, null, null) > 0) {
                deleted = true
            }
        } catch (e: Exception) {
            mediaStoreException = e
        }

        // 3. Pastikan berkas fisik juga terhapus (mis. jika izin MANAGE_EXTERNAL_STORAGE tersedia atau MediaStore belum menghapus file fisik)
        if (pathToDelete != null) {
            runCatching {
                val f = File(pathToDelete)
                if (f.exists() && f.delete()) {
                    deleted = true
                }
            }
        }

        // Jika MediaStore menolak dan berkas fisik masih ada di disk, lemparkan agar Activity memunculkan dialog sistem createDeleteRequest
        val stillOnDisk = pathToDelete != null && File(pathToDelete).exists()
        if (!deleted && mediaStoreException != null && stillOnDisk) {
            throw mediaStoreException
        }
        if (mediaStoreException != null && !deleted && pathToDelete == null) {
            throw mediaStoreException
        }

        if (pathToDelete != null) {
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(pathToDelete), null, null)
            }
        }

        if (deleted) {
            runCatching {
                context.contentResolver.notifyChange(uri, null)
                context.contentResolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
                context.contentResolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
                context.contentResolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null)
            }
        }

        return deleted
    }

    /** Hapus seluruh berkas rekaman, postingan, dan foto dari Pustaka MediaStore dan disk. */
    fun clearLibrary(context: Context): Int {
        val resolver = context.contentResolver
        var count = 0
        // Hapus per entry yang terdeteksi di library
        val entries = queryLibrary(context)
        for (e in entries) {
            if (deleteEntry(context, e.uri, e.name, e.relativeDir)) {
                count++
            }
        }
        runCatching {
            resolver.delete(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Movies/$APP_DIR%"),
            )
        }
        runCatching {
            resolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Pictures/$APP_DIR%"),
            )
        }
        runCatching {
            resolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Music/$APP_DIR%"),
            )
        }
        runCatching {
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), APP_DIR)
            val picsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), APP_DIR)
            val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), APP_DIR)
            moviesDir.deleteRecursively()
            picsDir.deleteRecursively()
            musicDir.deleteRecursively()
            MediaScannerConnection.scanFile(context, arrayOf(moviesDir.absolutePath, picsDir.absolutePath, musicDir.absolutePath), null, null)
        }
        return count
    }

    /**
     * Penegakan kuota & retensi otomatis (mengikuti pola dyrecord GalleryStore.enforceQuota).
     * Menghapus rekaman tertua jika ukuran total melebihi `maxGb` atau umur file > `maxDays`.
     * Mengembalikan Pair(jumlahBerkasDihapus, jumlahByteDibebaskan).
     */
    fun enforceQuota(context: Context, maxGb: Int, maxDays: Int): Pair<Int, Long> {
        val list = queryLibrary(context)
        if (list.isEmpty()) return Pair(0, 0L)

        val maxBytes = if (maxGb > 0) maxGb.toLong() * 1024L * 1024L * 1024L else Long.MAX_VALUE
        val nowSec = System.currentTimeMillis() / 1000L
        val minTimestampSec = if (maxDays > 0) nowSec - (maxDays * 86400L) else 0L

        var totalSize = list.sumOf { it.size }
        var deletedCount = 0
        var freedBytes = 0L

        // Urutkan dari yang tertua ke yang terbaru
        val oldestFirst = list.sortedBy { it.dateModified }
        for (entry in oldestFirst) {
            val isTooOld = maxDays > 0 && entry.dateModified in 1 until minTimestampSec
            val isOverQuota = maxGb > 0 && totalSize > maxBytes
            if (isTooOld || isOverQuota) {
                if (deleteEntry(context, entry.uri)) {
                    deletedCount++
                    freedBytes += entry.size
                    totalSize -= entry.size
                }
            }
        }
        return Pair(deletedCount, freedBytes)
    }

    /**
     * Membersihkan berkas mentah sementara di app-private storage (berkas kosong atau kedaluwarsa > 3 hari),
     * sambil selalu melindungi berkas yang masih aktif direkam di [excludeFileNames].
     */
    fun cleanupTempRaw(context: Context, excludeFileNames: Set<String> = emptySet()) {
        val recDir = File(context.getExternalFilesDir(null), "rec")
        if (!recDir.exists()) return
        val threshold = System.currentTimeMillis() - (3L * 86400L * 1000L)
        recDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name in excludeFileNames) return@forEach
            if (file.length() == 0L || file.lastModified() < threshold) {
                file.delete()
            }
        }
    }

    /** Share intent kompatibel untuk file provider-free (video via MediaStore uri langsung). */
    fun shareIntent(context: Context, uri: Uri, mime: String): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
