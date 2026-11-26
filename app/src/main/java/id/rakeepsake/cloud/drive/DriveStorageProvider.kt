package id.rakeepsake.cloud.drive

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import id.rakeepsake.cloud.CloudSettings
import id.rakeepsake.cloud.StorageProvider
import id.rakeepsake.cloud.data.CloudUploadDao
import id.rakeepsake.net.Http
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NeedsReconnectException(
    message: String = "Izin Google Drive hilang. Hubungkan ulang.",
) : CloudException(message)

class QuotaFullException(
    message: String = "Penyimpanan Google Drive penuh. File lokal tidak dihapus.",
) : CloudException(message)

class TransientCloudException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : CloudException(message, cause)

class LocalFileMissingException(
    message: String = "File lokal tidak ditemukan.",
    cause: Throwable? = null,
) : CloudException(message, cause)

class DriveClientException(
    message: String,
    cause: Throwable? = null,
) : CloudException(message, cause)

internal data class UploadChunkRange(
    val start: Long,
    val endInclusive: Long,
    val length: Long,
    val totalBytes: Long,
) {
    fun toContentRangeHeader(): String = "bytes $start-$endInclusive/$totalBytes"
}

internal enum class DriveErrorKind {
    UNAUTHORIZED,
    QUOTA_FULL,
    RATE_LIMIT,
    SESSION_NOT_FOUND,
    SERVER_ERROR,
    CLIENT_ERROR,
}

internal data class MappedDriveError(
    val kind: DriveErrorKind,
    val reason: String?,
    val userMessage: String,
)

/**
 * Implementasi [StorageProvider] untuk Google Drive REST API v3 menggunakan OkHttp
 * dengan protokol resumable upload, kelanjutan sesi (`sessionUri`), dan verifikasi ukuran.
 */
class DriveStorageProvider(
    private val appContext: Context,
    private val cloudSettings: CloudSettings,
    private val cloudUploadDao: CloudUploadDao,
    private val authManager: GoogleAuthManager,
    baseHttpClient: OkHttpClient = Http.client,
) : StorageProvider {

    private val driveClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun isConnected(): Boolean {
        val snap = cloudSettings.snapshot()
        if (!snap.cloudEnabled) return false
        return authManager.getAccessTokenOrNull(appContext) != null
    }

    override suspend fun upload(
        contentResolver: ContentResolver,
        localUri: Uri,
        remoteName: String,
        mimeType: String,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ): Result<String> = runCatching {
        val localKey = localUri.toString()
        val totalBytes = resolveLocalSize(contentResolver, localUri)
        if (totalBytes <= 0L) {
            throw LocalFileMissingException("File lokal tidak ditemukan.")
        }

        // 1. Dapatkan access token (di memori saja); null berarti hasResolution / izin hilang.
        var token = authManager.getAccessTokenOrNull(appContext) ?: throw NeedsReconnectException()

        suspend fun <T> withAuthRetry(call: suspend (String) -> Pair<Int, T>): T {
            val (code1, res1) = call(token)
            if (code1 != 401) return res1
            authManager.invalidateAccessToken(appContext, token)
            token = authManager.getAccessTokenOrNull(appContext) ?: throw NeedsReconnectException()
            val (code2, res2) = call(token)
            if (code2 == 401) throw NeedsReconnectException()
            return res2
        }

        // 2. Pastikan folder "RaKeepsake" tersedia di Drive.
        val folderId = ensureRaKeepsakeFolder(::withAuthRetry)

        var sessionUri = cloudUploadDao.byLocalPath(localKey)?.sessionUri?.takeIf { it.isNotBlank() }
        var offset = 0L

        // 3. Jika sessionUri sudah ada, cek status sesi (6.4) untuk melanjutkan dari offset terakhir.
        if (sessionUri != null) {
            when (val statusRes = withAuthRetry { t -> querySessionStatus(t, sessionUri!!, totalBytes) }) {
                is SessionStatusOutcome.Completed -> {
                    onProgress(totalBytes, totalBytes)
                    return@runCatching statusRes.fileId
                }
                is SessionStatusOutcome.ResumeFrom -> {
                    offset = statusRes.nextByte
                }
                SessionStatusOutcome.InvalidSession -> {
                    cloudUploadDao.setSessionUri(localKey, null)
                    sessionUri = null
                    offset = 0L
                }
            }
        }

        // 4. Mulai sesi resumable baru bila belum ada / sesi lama kedaluwarsa (6.2).
        if (sessionUri == null) {
            sessionUri = withAuthRetry { t ->
                startResumableSession(t, folderId, remoteName, mimeType, totalBytes)
            }
            cloudUploadDao.setSessionUri(localKey, sessionUri)
            offset = 0L
        }

        // 5. Kirim data per potongan kelipatan 256 KiB (default 8 MiB) (6.3).
        var sessionResetCount = 0
        var zeroAdvanceCount = 0
        onProgress(offset, totalBytes)

        while (offset < totalBytes) {
            currentCoroutineContext().ensureActive()
            val activeSession = sessionUri ?: run {
                val created = withAuthRetry { t ->
                    startResumableSession(t, folderId, remoteName, mimeType, totalBytes)
                }
                cloudUploadDao.setSessionUri(localKey, created)
                offset = 0L
                sessionUri = created
                created
            }

            val chunk = computeChunkRange(offset, totalBytes, DEFAULT_CHUNK_SIZE)
            val chunkOutcome = withAuthRetry { t ->
                uploadChunk(
                    token = t,
                    sessionUri = activeSession,
                    contentResolver = contentResolver,
                    localUri = localUri,
                    chunk = chunk,
                    onProgress = onProgress,
                )
            }

            when (chunkOutcome) {
                is ChunkOutcome.Completed -> {
                    onProgress(totalBytes, totalBytes)
                    return@runCatching chunkOutcome.fileId
                }
                is ChunkOutcome.ResumeFrom -> {
                    val next = chunkOutcome.nextByte
                    if (next <= offset) {
                        zeroAdvanceCount++
                        if (zeroAdvanceCount >= 2) {
                            throw TransientCloudException("Server Drive tidak memajukan posisi unggahan.")
                        }
                    } else {
                        zeroAdvanceCount = 0
                    }
                    offset = next
                    onProgress(offset, totalBytes)
                }
                ChunkOutcome.SessionExpired -> {
                    sessionResetCount++
                    cloudUploadDao.setSessionUri(localKey, null)
                    sessionUri = null
                    offset = 0L
                    if (sessionResetCount > 2) {
                        throw TransientCloudException("Sesi upload Drive kedaluwarsa berulang kali.", httpCode = 404)
                    }
                }
                is ChunkOutcome.ServerError5xx -> {
                    // Sesuai 6.3 & 8: tanya status sesi (6.4) terlebih dahulu sebelum retry/backoff.
                    val statusCheck = runCatching {
                        withAuthRetry { t -> querySessionStatus(t, activeSession, totalBytes) }
                    }.getOrNull()
                    when (statusCheck) {
                        is SessionStatusOutcome.Completed -> {
                            onProgress(totalBytes, totalBytes)
                            return@runCatching statusCheck.fileId
                        }
                        is SessionStatusOutcome.ResumeFrom -> {
                            offset = statusCheck.nextByte
                            onProgress(offset, totalBytes)
                        }
                        else -> Unit
                    }
                    throw TransientCloudException(
                        "Server Google Drive bermasalah (HTTP ${chunkOutcome.httpCode}).",
                        httpCode = chunkOutcome.httpCode,
                    )
                }
            }
        }

        throw TransientCloudException("Unggahan selesai mengirim byte tetapi ID file Drive belum diterima.")
    }

    override suspend fun verify(remoteId: String, expectedSizeBytes: Long): Boolean {
        if (remoteId.isBlank() || expectedSizeBytes < 0L) return false
        var token = authManager.getAccessTokenOrNull(appContext) ?: return false
        val url = "$DRIVE_FILES_ENDPOINT/${Uri.encode(remoteId)}?fields=id,size,trashed"

        for (attempt in 0..1) {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val result = runCatching {
                driveClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val body = resp.body?.string().orEmpty()
                    code to body
                }
            }.getOrNull() ?: return false

            val (code, body) = result
            if (code == 401 && attempt == 0) {
                authManager.invalidateAccessToken(appContext, token)
                token = authManager.getAccessTokenOrNull(appContext) ?: return false
                continue
            }
            return parseVerifyResponse(code, body, expectedSizeBytes)
        }
        return false
    }

    override suspend fun disconnect() {
        authManager.disconnect()
    }

    // ==================== FOLDER & RESUMABLE HTTP ====================

    private suspend fun ensureRaKeepsakeFolder(
        withAuthRetry: suspend (suspend (String) -> Pair<Int, String?>) -> String?,
    ): String {
        val folderId = withAuthRetry { t -> queryOrCreateFolderWithToken(t) }
        if (!folderId.isNullOrBlank()) {
            cloudSettings.setDriveFolderId(folderId)
            return folderId
        }
        throw TransientCloudException("Gagal menyiapkan folder RaKeepsake di Drive.")
    }

    private suspend fun queryOrCreateFolderWithToken(token: String): Pair<Int, String?> {
        val savedId = cloudSettings.snapshot().driveFolderId
        if (!savedId.isNullOrBlank()) {
            val req = Request.Builder()
                .url("$DRIVE_FILES_ENDPOINT/${Uri.encode(savedId)}?fields=id,trashed")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val (code, body) = executeText(req)
            if (code == 401) return 401 to null
            if (code == 200) {
                val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                val trashed = obj?.get("trashed")?.jsonPrimitive?.booleanOrNull == true
                if (!trashed) return 200 to savedId
                cloudSettings.setDriveFolderId(null)
            } else if (code == 404) {
                cloudSettings.setDriveFolderId(null)
            } else {
                throw toCloudException(mapDriveHttpError(code, body))
            }
        }

        // Cari folder "RaKeepsake" (6.1)
        val searchReq = Request.Builder()
            .url(
                "$DRIVE_FILES_ENDPOINT" +
                    "?q=name%3D'RaKeepsake'+and+mimeType%3D'application%2Fvnd.google-apps.folder'+and+trashed%3Dfalse" +
                    "&fields=files(id,name)&spaces=drive",
            )
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val (searchCode, searchBody) = executeText(searchReq)
        if (searchCode == 401) return 401 to null
        if (searchCode != 200) throw toCloudException(mapDriveHttpError(searchCode, searchBody))

        val files = runCatching {
            (json.parseToJsonElement(searchBody) as? JsonObject)?.get("files") as? JsonArray
        }.getOrNull()
        val existingId = (files?.firstOrNull() as? JsonObject)
            ?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (existingId != null) return 200 to existingId

        // Buat folder "RaKeepsake" baru jika belum ada (6.1)
        val createJson = buildJsonObject {
            put("name", FOLDER_NAME)
            put("mimeType", FOLDER_MIME)
        }.toString()
        val createReq = Request.Builder()
            .url(DRIVE_FILES_ENDPOINT)
            .header("Authorization", "Bearer $token")
            .post(createJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (createCode, createBody) = executeText(createReq)
        if (createCode == 401) return 401 to null
        if (createCode !in 200..201) throw toCloudException(mapDriveHttpError(createCode, createBody))

        val newId = runCatching {
            (json.parseToJsonElement(createBody) as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: throw TransientCloudException("Respons pembuatan folder Drive tidak memuat ID.")
        return 200 to newId
    }

    private fun startResumableSession(
        token: String,
        folderId: String,
        remoteName: String,
        mimeType: String,
        totalBytes: Long,
    ): Pair<Int, String> {
        val metaJson = buildJsonObject {
            put("name", remoteName)
            put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(folderId)) })
        }.toString()
        val req = Request.Builder()
            .url("$DRIVE_UPLOAD_ENDPOINT?uploadType=resumable")
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", totalBytes.toString())
            .post(metaJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            driveClient.newCall(req).execute().use { resp ->
                val code = resp.code
                if (code == 401) return 401 to ""
                if (code !in 200..201) {
                    val body = resp.body?.string().orEmpty()
                    throw toCloudException(mapDriveHttpError(code, body))
                }
                val location = resp.header("Location")?.trim().orEmpty()
                if (location.isBlank()) {
                    throw TransientCloudException("Header Location tidak ditemukan pada sesi upload Drive.")
                }
                code to location
            }
        } catch (ce: CloudException) {
            throw ce
        } catch (io: IOException) {
            throw TransientCloudException("Gagal menghubungi Google Drive: ${io.message}", cause = io)
        }
    }

    private sealed interface SessionStatusOutcome {
        data class Completed(val fileId: String) : SessionStatusOutcome
        data class ResumeFrom(val nextByte: Long) : SessionStatusOutcome
        data object InvalidSession : SessionStatusOutcome
    }

    private fun querySessionStatus(
        token: String,
        sessionUri: String,
        totalBytes: Long,
    ): Pair<Int, SessionStatusOutcome> {
        val req = Request.Builder()
            .url(sessionUri)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes */$totalBytes")
            .put(EMPTY_BODY)
            .build()

        return try {
            driveClient.newCall(req).execute().use { resp ->
                val code = resp.code
                when {
                    code == 401 -> 401 to SessionStatusOutcome.InvalidSession
                    code == 308 -> {
                        val next = parseRangeNextByte(resp.header("Range"), totalBytes)
                        code to SessionStatusOutcome.ResumeFrom(next)
                    }
                    code == 200 || code == 201 -> {
                        val body = resp.body?.string().orEmpty()
                        val id = extractUploadedFileId(body)
                            ?: throw TransientCloudException("Respons selesai Drive tidak memuat ID file.")
                        code to SessionStatusOutcome.Completed(id)
                    }
                    code == 404 || code == 410 -> code to SessionStatusOutcome.InvalidSession
                    else -> {
                        val body = resp.body?.string().orEmpty()
                        throw toCloudException(mapDriveHttpError(code, body))
                    }
                }
            }
        } catch (ce: CloudException) {
            throw ce
        } catch (io: IOException) {
            throw TransientCloudException("Koneksi terputus saat memeriksa status unggahan.", cause = io)
        }
    }

    private sealed interface ChunkOutcome {
        data class Completed(val fileId: String) : ChunkOutcome
        data class ResumeFrom(val nextByte: Long) : ChunkOutcome
        data object SessionExpired : ChunkOutcome
        data class ServerError5xx(val httpCode: Int) : ChunkOutcome
    }

    private fun uploadChunk(
        token: String,
        sessionUri: String,
        contentResolver: ContentResolver,
        localUri: Uri,
        chunk: UploadChunkRange,
        onProgress: (Long, Long) -> Unit,
    ): Pair<Int, ChunkOutcome> {
        val reqBody = object : RequestBody() {
            override fun contentType() = OCTET_STREAM
            override fun contentLength(): Long = chunk.length
            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                streamUriSlice(
                    contentResolver = contentResolver,
                    uri = localUri,
                    startOffset = chunk.start,
                    length = chunk.length,
                    sink = sink,
                ) { sentInChunk ->
                    onProgress(chunk.start + sentInChunk, chunk.totalBytes)
                }
            }
        }

        val req = Request.Builder()
            .url(sessionUri)
            .header("Authorization", "Bearer $token")
            .header("Content-Range", chunk.toContentRangeHeader())
            .put(reqBody)
            .build()

        return try {
            driveClient.newCall(req).execute().use { resp ->
                val code = resp.code
                when {
                    code == 401 -> 401 to ChunkOutcome.SessionExpired
                    code == 308 -> {
                        val next = parseRangeNextByte(resp.header("Range"), chunk.totalBytes)
                        code to ChunkOutcome.ResumeFrom(next)
                    }
                    code == 200 || code == 201 -> {
                        val body = resp.body?.string().orEmpty()
                        val id = extractUploadedFileId(body)
                            ?: throw TransientCloudException("Respons selesai Drive tidak memuat ID file.")
                        code to ChunkOutcome.Completed(id)
                    }
                    code == 404 || code == 410 -> code to ChunkOutcome.SessionExpired
                    code in 500..599 -> code to ChunkOutcome.ServerError5xx(code)
                    else -> {
                        val body = resp.body?.string().orEmpty()
                        throw toCloudException(mapDriveHttpError(code, body))
                    }
                }
            }
        } catch (ce: CloudException) {
            throw ce
        } catch (io: IOException) {
            throw TransientCloudException("Koneksi terputus saat mengunggah ke Drive.", cause = io)
        }
    }

    private fun resolveLocalSize(contentResolver: ContentResolver, uri: Uri): Long {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst() && idx >= 0 && !c.isNull(idx)) {
                    val s = c.getLong(idx)
                    if (s > 0L) return s
                }
            }
        }
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrElse { err ->
            if (err is FileNotFoundException || err is SecurityException) {
                throw LocalFileMissingException("File lokal tidak ditemukan.", err)
            }
            -1L
        }
    }

    private fun streamUriSlice(
        contentResolver: ContentResolver,
        uri: Uri,
        startOffset: Long,
        length: Long,
        sink: BufferedSink,
        onChunkProgress: (Long) -> Unit,
    ) {
        val pfd = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            throw LocalFileMissingException("File lokal tidak ditemukan.", e)
        } catch (e: SecurityException) {
            throw LocalFileMissingException("Akses ke file lokal ditolak.", e)
        }

        if (pfd != null) {
            pfd.use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { fis ->
                    val seeked = runCatching {
                        fis.channel.position(startOffset)
                        true
                    }.getOrDefault(false)
                    if (!seeked) skipFully(fis, startOffset)
                    copyExactBytes(fis, length, sink, onChunkProgress)
                }
            }
            return
        }

        val input = contentResolver.openInputStream(uri)
            ?: throw LocalFileMissingException("File lokal tidak ditemukan.")
        input.use { stream ->
            skipFully(stream, startOffset)
            copyExactBytes(stream, length, sink, onChunkProgress)
        }
    }

    private fun skipFully(stream: java.io.InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discard = ByteArray(16 * 1024)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = stream.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read < 0) throw LocalFileMissingException("File lokal berubah ukuran atau terpotong.")
                remaining -= read
            }
        }
    }

    private fun copyExactBytes(
        stream: java.io.InputStream,
        length: Long,
        sink: BufferedSink,
        onChunkProgress: (Long) -> Unit,
    ) {
        val buf = ByteArray(64 * 1024)
        var sent = 0L
        var lastEmitAt = 0L
        while (sent < length) {
            val toRead = minOf(buf.size.toLong(), length - sent).toInt()
            val n = stream.read(buf, 0, toRead)
            if (n < 0) {
                throw LocalFileMissingException("Ukuran file lokal lebih kecil dari yang diharapkan.")
            }
            sink.write(buf, 0, n)
            sent += n
            val now = System.currentTimeMillis()
            if (now - lastEmitAt >= 150L || sent == length) {
                lastEmitAt = now
                onChunkProgress(sent)
            }
        }
    }

    private fun executeText(req: Request): Pair<Int, String> {
        return try {
            driveClient.newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
        } catch (io: IOException) {
            throw TransientCloudException("Gagal terhubung ke Google Drive: ${io.message}", cause = io)
        }
    }

    private fun toCloudException(mapped: MappedDriveError): CloudException = when (mapped.kind) {
        DriveErrorKind.UNAUTHORIZED -> NeedsReconnectException(mapped.userMessage)
        DriveErrorKind.QUOTA_FULL -> QuotaFullException(mapped.userMessage)
        DriveErrorKind.RATE_LIMIT, DriveErrorKind.SERVER_ERROR, DriveErrorKind.SESSION_NOT_FOUND ->
            TransientCloudException(mapped.userMessage)
        DriveErrorKind.CLIENT_ERROR -> DriveClientException(mapped.userMessage)
    }

    companion object {
        const val CHUNK_ALIGNMENT_BYTES: Long = 262_144L // 256 KiB wajib kelipatan oleh Drive resumable upload
        const val DEFAULT_CHUNK_SIZE: Long = 8L * 1024L * 1024L // 8 MiB (= 32 x 256 KiB)
        private const val FOLDER_NAME = "RaKeepsake"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val rangeHeaderRegex = Regex("""bytes=(\d+)-(\d+)""", RegexOption.IGNORE_CASE)

        /**
         * Mem-parse header `Range: bytes=0-<N>` menjadi byte awal berikutnya (`N + 1`).
         * Jika header `Range` kosong/tidak ada, mengembalikan `0L`.
         */
        internal fun parseRangeNextByte(rangeHeader: String?, totalBytes: Long): Long {
            if (rangeHeader.isNullOrBlank()) return 0L
            val m = rangeHeaderRegex.find(rangeHeader) ?: return 0L
            val endByte = m.groupValues[2].toLongOrNull() ?: return 0L
            return (endByte + 1L).coerceIn(0L, totalBytes.coerceAtLeast(0L))
        }

        /** Menghitung rentang potongan upload untuk offset saat ini. */
        internal fun computeChunkRange(
            offset: Long,
            totalBytes: Long,
            chunkSize: Long = DEFAULT_CHUNK_SIZE,
        ): UploadChunkRange {
            require(offset in 0 until totalBytes) { "Offset $offset di luar rentang [0, $totalBytes)" }
            require(chunkSize > 0L) { "chunkSize harus > 0" }
            val len = minOf(chunkSize, totalBytes - offset)
            return UploadChunkRange(
                start = offset,
                endInclusive = offset + len - 1L,
                length = len,
                totalBytes = totalBytes,
            )
        }

        internal fun extractUploadedFileId(body: String): String? = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)
                ?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()

        /** Memeriksa respons GET `/drive/v3/files/<id>?fields=id,size,trashed` sesuai 6.5. */
        internal fun parseVerifyResponse(
            httpCode: Int,
            responseBody: String,
            expectedSizeBytes: Long,
        ): Boolean {
            if (httpCode != 200 || responseBody.isBlank()) return false
            return runCatching {
                val obj = json.parseToJsonElement(responseBody) as? JsonObject ?: return false
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                val trashed = obj["trashed"]?.jsonPrimitive?.booleanOrNull ?: false
                val remoteSize = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: obj["size"]?.jsonPrimitive?.longOrNull
                !id.isNullOrBlank() && !trashed && remoteSize == expectedSizeBytes
            }.getOrDefault(false)
        }

        /** Memetakan respons error HTTP Google Drive API v3 sesuai Bagian 8 & Bagian 9. */
        internal fun mapDriveHttpError(httpCode: Int, errorBody: String): MappedDriveError {
            val reason = extractDriveErrorReason(errorBody)
            val reasonLow = reason?.lowercase().orEmpty()
            return when {
                httpCode == 401 -> MappedDriveError(
                    kind = DriveErrorKind.UNAUTHORIZED,
                    reason = reason,
                    userMessage = "Izin Google Drive hilang. Hubungkan ulang.",
                )
                httpCode == 403 && (reasonLow.contains("storagequotaexceeded") || reasonLow.contains("quotaexceeded") ||
                    errorBody.contains("storageQuotaExceeded", ignoreCase = true)) -> MappedDriveError(
                    kind = DriveErrorKind.QUOTA_FULL,
                    reason = reason,
                    userMessage = "Penyimpanan Google Drive penuh. File lokal tidak dihapus.",
                )
                httpCode == 429 || (httpCode == 403 && (reasonLow.contains("ratelimitexceeded") ||
                    reasonLow.contains("userratelimitexceeded"))) -> MappedDriveError(
                    kind = DriveErrorKind.RATE_LIMIT,
                    reason = reason,
                    userMessage = "Batas permintaan Google Drive tercapai sementara, mencoba lagi otomatis.",
                )
                httpCode == 403 -> MappedDriveError(
                    kind = DriveErrorKind.UNAUTHORIZED,
                    reason = reason,
                    userMessage = "Izin Google Drive hilang. Hubungkan ulang.",
                )
                httpCode == 404 || httpCode == 410 -> MappedDriveError(
                    kind = DriveErrorKind.SESSION_NOT_FOUND,
                    reason = reason,
                    userMessage = "Sesi unggahan tidak ditemukan.",
                )
                httpCode in 500..599 -> MappedDriveError(
                    kind = DriveErrorKind.SERVER_ERROR,
                    reason = reason,
                    userMessage = "Server Google Drive bermasalah (HTTP $httpCode).",
                )
                else -> MappedDriveError(
                    kind = DriveErrorKind.CLIENT_ERROR,
                    reason = reason,
                    userMessage = "Gagal mengunggah ke Google Drive (HTTP $httpCode).",
                )
            }
        }

        internal fun extractDriveErrorReason(errorBody: String): String? = runCatching {
            val root = json.parseToJsonElement(errorBody) as? JsonObject ?: return null
            val errObj = root["error"] as? JsonObject ?: return null
            val errors = errObj["errors"] as? JsonArray
            (errors?.firstOrNull() as? JsonObject)
                ?.get("reason")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: errObj["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
