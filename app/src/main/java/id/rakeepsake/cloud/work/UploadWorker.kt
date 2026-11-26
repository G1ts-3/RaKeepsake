package id.rakeepsake.cloud.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.rakeepsake.MainActivity
import id.rakeepsake.RaApp
import id.rakeepsake.cloud.data.UploadState
import id.rakeepsake.cloud.drive.GoogleAuthManager
import id.rakeepsake.cloud.drive.LocalFileMissingException
import id.rakeepsake.cloud.drive.NeedsReconnectException
import id.rakeepsake.cloud.drive.QuotaFullException
import id.rakeepsake.cloud.drive.TransientCloudException
import id.rakeepsake.monitor.LiveStateHolder
import id.rakeepsake.util.Storage
import id.rakeepsake.work.DownloadProgressHolder
import id.rakeepsake.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Worker latar belakang untuk mengunggah file lokal ke Google Drive, memverifikasi hasil,
 * serta menghapus file lokal secara aman apabila semua aturan Bagian 7.3 terpenuhi.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val localPath = inputData.getString(KEY_LOCAL_PATH) ?: return@withContext Result.failure()
        val container = (applicationContext as RaApp).container
        val dao = container.db.cloudUploads()

        val entry = dao.byLocalPath(localPath) ?: return@withContext Result.failure()
        val settingsSnap = container.cloudSettings.snapshot()
        if (!settingsSnap.cloudEnabled) {
            dao.markUnfinishedAs(UploadState.NEEDS_RECONNECT, MSG_RECONNECT)
            return@withContext Result.failure()
        }

        if (entry.attempts >= MAX_ATTEMPTS) {
            dao.setState(
                localPath,
                UploadState.FAILED,
                entry.lastError ?: "Unggahan gagal setelah $MAX_ATTEMPTS percobaan.",
            )
            return@withContext Result.failure()
        }

        val (batchIndex, batchTotal) = computeBatchPosition(dao.allOnce(), localPath)
        val notifId = Notifier.ID_CLOUD_UPLOAD_BASE + (localPath.hashCode() and 0x3FF)
        runCatching {
            setForeground(createForegroundInfo(notifId, batchIndex, batchTotal, 0, entry.displayName))
        }

        val uri = Uri.parse(localPath)
        val initialMeta = CloudUploadScheduler.queryLocalMediaMeta(applicationContext.contentResolver, uri)
        if (initialMeta == null || initialMeta.sizeBytes <= 0L) {
            dao.setState(localPath, UploadState.FAILED, MSG_FILE_MISSING)
            return@withContext Result.failure()
        }

        // Bagian 7.4: deteksi apakah berkas masih ditulis (flag aktif/IS_PENDING atau stabil >= 10 detik)
        if (initialMeta.isPending == 1 || isActivelyWrittenInApp(entry.displayName)) {
            dao.setState(localPath, UploadState.QUEUED, null)
            return@withContext Result.retry()
        }

        val modifiedMs = initialMeta.dateModifiedSec * 1000L
        val ageMs = if (initialMeta.dateModifiedSec > 0L) System.currentTimeMillis() - modifiedMs else FILE_STABILITY_WINDOW_MS
        if (ageMs < FILE_STABILITY_WINDOW_MS) {
            val waitMs = (FILE_STABILITY_WINDOW_MS - ageMs).coerceIn(1000L, FILE_STABILITY_WINDOW_MS)
            delay(waitMs)
            val recheck = CloudUploadScheduler.queryLocalMediaMeta(applicationContext.contentResolver, uri)
            if (recheck == null || recheck.sizeBytes <= 0L) {
                dao.setState(localPath, UploadState.FAILED, MSG_FILE_MISSING)
                return@withContext Result.failure()
            }
            if (!isFileWriteStable(
                    sizeBefore = initialMeta.sizeBytes,
                    modifiedSecBefore = initialMeta.dateModifiedSec,
                    sizeAfter = recheck.sizeBytes,
                    modifiedSecAfter = recheck.dateModifiedSec,
                    isPendingAfter = recheck.isPending,
                )
            ) {
                dao.setState(localPath, UploadState.QUEUED, null)
                return@withContext Result.retry()
            }
        }

        val registeredSize = if (entry.sizeBytes > 0L) entry.sizeBytes else initialMeta.sizeBytes
        val nextAttempt = entry.attempts + 1
        dao.upsert(
            entry.copy(
                sizeBytes = registeredSize,
                state = UploadState.UPLOADING,
                attempts = nextAttempt,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        var lastNotifAt = 0L
        CloudUploadProgressHolder.update(localPath, 0L, registeredSize)

        return@withContext try {
            val uploadResult = container.storageProvider.upload(
                contentResolver = applicationContext.contentResolver,
                localUri = uri,
                remoteName = entry.displayName,
                mimeType = entry.mimeType,
            ) { sentBytes, totalBytes ->
                CloudUploadProgressHolder.update(localPath, sentBytes, totalBytes)
                val now = System.currentTimeMillis()
                if (now - lastNotifAt >= 800L || sentBytes >= totalBytes) {
                    lastNotifAt = now
                    val pct = if (totalBytes > 0L) ((sentBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                    setForegroundAsync(createForegroundInfo(notifId, batchIndex, batchTotal, pct, entry.displayName))
                }
            }

            if (uploadResult.isFailure) {
                return@withContext handleUploadFailure(
                    dao = dao,
                    localPath = localPath,
                    attempt = nextAttempt,
                    err = uploadResult.exceptionOrNull(),
                )
            }

            val remoteId = uploadResult.getOrThrow()
            val verifiedInAttempt = container.storageProvider.verify(remoteId, registeredSize)
            if (!verifiedInAttempt) {
                dao.setState(localPath, UploadState.FAILED, MSG_VERIFY_FAILED)
                if (nextAttempt >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            } else {
                val latestBeforeDone = dao.byLocalPath(localPath) ?: entry
                dao.upsert(
                    latestBeforeDone.copy(
                        sizeBytes = registeredSize,
                        state = UploadState.DONE,
                        remoteId = remoteId,
                        sessionUri = null,
                        attempts = nextAttempt,
                        lastError = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                tryDeleteLocalIfAllowed(
                    container = container,
                    localPath = localPath,
                    uri = uri,
                    displayName = entry.displayName,
                    registeredSize = registeredSize,
                    relativeDir = initialMeta.relativeDir,
                    verifiedInSameAttempt = true,
                )
                Result.success()
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            handleUploadFailure(dao, localPath, nextAttempt, t)
        } finally {
            CloudUploadProgressHolder.clear(localPath)
        }
    }

    private suspend fun handleUploadFailure(
        dao: id.rakeepsake.cloud.data.CloudUploadDao,
        localPath: String,
        attempt: Int,
        err: Throwable?,
    ): Result {
        return when (err) {
            is LocalFileMissingException -> {
                dao.setState(localPath, UploadState.FAILED, MSG_FILE_MISSING)
                Result.failure()
            }
            is NeedsReconnectException -> {
                dao.markUnfinishedAs(UploadState.NEEDS_RECONNECT, MSG_RECONNECT)
                Result.failure()
            }
            is QuotaFullException -> {
                dao.markUnfinishedAs(UploadState.QUOTA_FULL, MSG_QUOTA_FULL)
                WorkManager.getInstance(applicationContext).cancelAllWorkByTag(GoogleAuthManager.CLOUD_UPLOAD_WORK_TAG)
                Result.failure()
            }
            is TransientCloudException, is IOException -> {
                if (attempt >= MAX_ATTEMPTS) {
                    dao.setState(
                        localPath,
                        UploadState.FAILED,
                        err?.message ?: "Unggahan gagal setelah $MAX_ATTEMPTS percobaan.",
                    )
                    Result.failure()
                } else {
                    dao.setState(localPath, UploadState.QUEUED, err?.message)
                    Result.retry()
                }
            }
            else -> {
                val msg = err?.message ?: "Gagal mengunggah berkas."
                if (attempt >= MAX_ATTEMPTS) {
                    dao.setState(localPath, UploadState.FAILED, msg)
                    Result.failure()
                } else {
                    dao.setState(localPath, UploadState.QUEUED, msg)
                    Result.retry()
                }
            }
        }
    }

    private suspend fun tryDeleteLocalIfAllowed(
        container: id.rakeepsake.data.AppContainer,
        localPath: String,
        uri: Uri,
        displayName: String,
        registeredSize: Long,
        relativeDir: String,
        verifiedInSameAttempt: Boolean,
    ) {
        val freshSettings = container.cloudSettings.snapshot()
        val dbRow = container.db.cloudUploads().byLocalPath(localPath)
        val currentMeta = CloudUploadScheduler.queryLocalMediaMeta(applicationContext.contentResolver, uri)

        val allowed = canDeleteLocalAfterUpload(
            deleteAfterUploadSetting = freshSettings.deleteAfterUpload,
            currentState = dbRow?.state ?: UploadState.FAILED,
            verifiedInSameAttempt = verifiedInSameAttempt,
            fileStillExists = currentMeta != null,
            currentSizeBytes = currentMeta?.sizeBytes ?: -1L,
            registeredSizeBytes = registeredSize,
            isCurrentlyInUse = CloudUploadPlaybackGuard.isPlaying(localPath) ||
                currentMeta?.isPending == 1 ||
                isActivelyWrittenInApp(displayName),
        )
        if (!allowed) return

        val deleteResult = runCatching {
            Storage.deleteEntry(applicationContext, uri, displayName, relativeDir)
        }
        val deleted = deleteResult.getOrDefault(false)
        if (deleted) {
            Log.i(TAG, "File lokal dihapus setelah terunggah & terverifikasi: $displayName")
            runCatching {
                val posts = container.db.posts().allOnce()
                for (p in posts) {
                    if (p.status == "DELETED") continue
                    if (p.filePaths.isNotBlank() && (p.filePaths.contains(localPath) || p.filePaths.contains(displayName))) {
                        container.db.posts().markDeleted(p.id)
                    }
                }
            }
            container.refreshLibrary()
        } else {
            val errMsg = deleteResult.exceptionOrNull()?.message
                ?: "Gagal menghapus file lokal secara otomatis."
            Log.w(TAG, "File lokal tetap dipertahankan (DONE): $displayName ($errMsg)")
            container.db.cloudUploads().setState(
                localPath,
                UploadState.DONE,
                "Tersimpan di Drive, tetapi file lokal tidak dapat dihapus otomatis.",
            )
        }
    }

    private fun isActivelyWrittenInApp(displayName: String): Boolean {
        val activeRec = LiveStateHolder.recording.values.any {
            displayName.contains(it.handle, ignoreCase = true)
        }
        if (activeRec) return true
        return DownloadProgressHolder.progress.value.values.any { prog ->
            displayName.contains(prog.postId, ignoreCase = true)
        }
    }

    private fun createForegroundInfo(
        notifId: Int,
        n: Int,
        total: Int,
        progressPct: Int,
        fileName: String,
    ): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("tab", 1)
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Mengunggah ke Drive ($n dari $total)"
        val notif = NotificationCompat.Builder(applicationContext, Notifier.CH_CLOUD_UPLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(fileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPct, progressPct == 0)
            .setContentIntent(pi)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notif)
        }
    }

    companion object {
        const val KEY_LOCAL_PATH = "local_path"
        const val MAX_ATTEMPTS = 5
        const val FILE_STABILITY_WINDOW_MS = 10_000L
        private const val TAG = "RaCloudUpload"

        private const val MSG_RECONNECT = "Izin Google Drive hilang. Hubungkan ulang."
        private const val MSG_QUOTA_FULL = "Penyimpanan Google Drive penuh. File lokal tidak dihapus."
        private const val MSG_FILE_MISSING = "File lokal tidak ditemukan."
        private const val MSG_VERIFY_FAILED = "Verifikasi gagal. File lokal tidak dihapus."

        internal fun computeBatchPosition(
            allRows: List<id.rakeepsake.cloud.data.CloudUpload>,
            currentPath: String,
        ): Pair<Int, Int> {
            val active = allRows.filter {
                it.localPath == currentPath ||
                    it.state == UploadState.QUEUED ||
                    it.state == UploadState.UPLOADING
            }.sortedBy { it.updatedAt }
            val total = active.size.coerceAtLeast(1)
            val idx = (active.indexOfFirst { it.localPath == currentPath }.takeIf { it >= 0 } ?: 0) + 1
            return idx to total
        }

        /** Memeriksa apakah berkas tidak lagi berubah ukuran maupun waktu modifikasi (Bagian 7.4). */
        internal fun isFileWriteStable(
            sizeBefore: Long,
            modifiedSecBefore: Long,
            sizeAfter: Long,
            modifiedSecAfter: Long,
            isPendingAfter: Int,
        ): Boolean = isPendingAfter == 0 &&
            sizeAfter > 0L &&
            sizeAfter == sizeBefore &&
            modifiedSecAfter == modifiedSecBefore

        /** Aturan ketat penghapusan file lokal (Bagian 7.3 — seluruh 5 syarat wajib terpenuhi). */
        internal fun canDeleteLocalAfterUpload(
            deleteAfterUploadSetting: Boolean,
            currentState: UploadState,
            verifiedInSameAttempt: Boolean,
            fileStillExists: Boolean,
            currentSizeBytes: Long,
            registeredSizeBytes: Long,
            isCurrentlyInUse: Boolean,
        ): Boolean = deleteAfterUploadSetting &&
            currentState == UploadState.DONE &&
            verifiedInSameAttempt &&
            fileStillExists &&
            registeredSizeBytes > 0L &&
            currentSizeBytes == registeredSizeBytes &&
            !isCurrentlyInUse
    }
}
