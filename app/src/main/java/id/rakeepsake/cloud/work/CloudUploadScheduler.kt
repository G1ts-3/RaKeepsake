package id.rakeepsake.cloud.work

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import id.rakeepsake.RaApp
import id.rakeepsake.cloud.data.CloudUpload
import id.rakeepsake.cloud.data.UploadState
import id.rakeepsake.cloud.drive.GoogleAuthManager
import id.rakeepsake.util.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Immutable
data class CloudUploadProgress(
    val localPath: String,
    val sentBytes: Long,
    val totalBytes: Long,
)

/** Menyimpan progres unggahan aktif di memori agar UI daftar Pustaka bisa menampilkan persentase. */
object CloudUploadProgressHolder {
    private val map = ConcurrentHashMap<String, CloudUploadProgress>()
    private val _progress = MutableStateFlow<Map<String, CloudUploadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, CloudUploadProgress>> = _progress.asStateFlow()

    fun update(localPath: String, sentBytes: Long, totalBytes: Long) {
        map[localPath] = CloudUploadProgress(localPath, sentBytes, totalBytes)
        _progress.value = map.toMap()
    }

    fun clear(localPath: String) {
        if (map.remove(localPath) != null) {
            _progress.value = map.toMap()
        }
    }
}

/** Penjaga URI yang sedang diputar/dilihat di aplikasi agar tidak dihapus selama upload selesai. */
object CloudUploadPlaybackGuard {
    private val activeUris = ConcurrentHashMap.newKeySet<String>()

    fun markPlaying(uriString: String) {
        activeUris.add(uriString)
    }

    fun unmarkPlaying(uriString: String) {
        activeUris.remove(uriString)
    }

    fun isPlaying(uriString: String): Boolean = activeUris.contains(uriString)
}

internal data class LocalMediaMeta(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val relativeDir: String,
    val isPending: Int,
)

object CloudUploadScheduler {

    internal fun uniqueWorkName(localPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(localPath.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(24)
        return "cloud_upload_$hex"
    }

    /** Jadwalkan unggahan satu file bila fitur Cloud aktif dan `auto_upload` menyala. */
    suspend fun enqueueIfAuto(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
    ) {
        val container = (context.applicationContext as RaApp).container
        val snap = container.cloudSettings.snapshot()
        if (!snap.cloudEnabled || !snap.autoUpload) return
        enqueue(
            context = context,
            localPath = uri.toString(),
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    /** Memasukkan satu file ke antrean `cloud_upload` dan menjadwalkan WorkManager unik. */
    suspend fun enqueue(
        context: Context,
        localPath: String,
        displayName: String? = null,
        mimeType: String? = null,
        sizeBytes: Long? = null,
        forceReset: Boolean = false,
    ): Boolean {
        val appCtx = context.applicationContext
        val container = (appCtx as RaApp).container
        val snap = container.cloudSettings.snapshot()
        if (!snap.cloudEnabled) return false

        val uri = Uri.parse(localPath)
        val meta = queryLocalMediaMeta(appCtx.contentResolver, uri)
        val resolvedName = displayName?.takeIf { it.isNotBlank() }
            ?: meta?.displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "rakeepsake_media"
        val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
            ?: meta?.mimeType?.takeIf { it.isNotBlank() }
            ?: Storage.mimeFor(resolvedName.substringAfterLast('.', "mp4"))
        val resolvedSize = sizeBytes?.takeIf { it > 0L }
            ?: meta?.sizeBytes?.takeIf { it > 0L }
            ?: 0L

        val dao = container.db.cloudUploads()
        val existing = dao.byLocalPath(localPath)

        if (meta == null && resolvedSize <= 0L) {
            dao.upsert(
                CloudUpload(
                    localPath = localPath,
                    displayName = resolvedName,
                    mimeType = resolvedMime,
                    sizeBytes = existing?.sizeBytes ?: 0L,
                    state = UploadState.FAILED,
                    remoteId = existing?.remoteId,
                    sessionUri = existing?.sessionUri,
                    attempts = existing?.attempts ?: 0,
                    lastError = "File lokal tidak ditemukan.",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return false
        }

        if (existing?.state == UploadState.DONE && !forceReset) {
            return false
        }
        if (existing?.state == UploadState.UPLOADING && !forceReset) {
            return false
        }

        val shouldResetAttempts = forceReset || existing == null || existing.state in setOf(
            UploadState.FAILED,
            UploadState.NEEDS_RECONNECT,
            UploadState.QUOTA_FULL,
        )
        dao.upsert(
            CloudUpload(
                localPath = localPath,
                displayName = resolvedName,
                mimeType = resolvedMime,
                sizeBytes = resolvedSize,
                state = UploadState.QUEUED,
                remoteId = if (forceReset) null else existing?.remoteId,
                sessionUri = existing?.sessionUri,
                attempts = if (shouldResetAttempts) 0 else existing!!.attempts,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (snap.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(snap.chargingOnly)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_LOCAL_PATH to localPath))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(GoogleAuthManager.CLOUD_UPLOAD_WORK_TAG)
            .build()

        WorkManager.getInstance(appCtx).enqueueUniqueWork(
            uniqueWorkName(localPath),
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    /** Menjadwalkan semua file di Pustaka yang belum berstatus `DONE`, `QUEUED`, atau `UPLOADING`. */
    suspend fun enqueueAllPending(context: Context): Int {
        val appCtx = context.applicationContext
        val container = (appCtx as RaApp).container
        if (!container.cloudSettings.snapshot().cloudEnabled) return 0

        val entries = Storage.queryLibrary(appCtx)
        val existingByPath = container.db.cloudUploads().allOnce().associateBy { it.localPath }
        var queuedCount = 0
        for (entry in entries) {
            val key = entry.uri.toString()
            val cur = existingByPath[key]
            val skip = cur != null && cur.state in setOf(
                UploadState.DONE,
                UploadState.QUEUED,
                UploadState.UPLOADING,
            )
            if (skip) continue
            if (enqueue(appCtx, key, entry.name, entry.mime, entry.size, forceReset = false)) {
                queuedCount++
            }
        }
        return queuedCount
    }

    internal fun queryLocalMediaMeta(contentResolver: ContentResolver, uri: Uri): LocalMediaMeta? {
        val proj = mutableListOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
        ).toTypedArray()

        val queried = runCatching {
            contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val relIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val pendIdx = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)

                val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx).orEmpty() else ""
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
                val mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) c.getString(mimeIdx).orEmpty() else ""
                val mod = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                val rel = if (relIdx >= 0 && !c.isNull(relIdx)) c.getString(relIdx).orEmpty() else ""
                val pend = if (pendIdx >= 0 && !c.isNull(pendIdx)) c.getInt(pendIdx) else 0
                LocalMediaMeta(name, mime, size, mod, rel, pend)
            }
        }.getOrNull()

        if (queried != null && queried.sizeBytes > 0L) return queried

        val fdSize = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (fdSize <= 0L) return null

        return (queried ?: LocalMediaMeta("", "", fdSize, 0L, "", 0)).copy(sizeBytes = fdSize)
    }
}
