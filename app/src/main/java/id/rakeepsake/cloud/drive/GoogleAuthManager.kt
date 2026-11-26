package id.rakeepsake.cloud.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import id.rakeepsake.cloud.CloudSettings
import id.rakeepsake.cloud.data.CloudUploadDao
import id.rakeepsake.cloud.data.UploadState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pengelola otorisasi Google Identity (`drive.file` saja) untuk RaKeepsake Cloud.
 * Access token hanya disimpan di memori sesaat saat dipakai dan tidak pernah ditulis ke disk/log.
 */
class GoogleAuthManager(
    private val appContext: Context,
    private val cloudSettings: CloudSettings,
    private val cloudUploadDao: CloudUploadDao,
) {
    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val CLOUD_UPLOAD_WORK_TAG = "cloud_upload"
        private const val MSG_AUTH_LOST = "Izin Google Drive hilang. Hubungkan ulang."
    }

    /**
     * Meminta token akses Google Drive (`drive.file`) secara non-interaktif.
     * - Jika token tersedia tanpa perlu resolusi UI, mengembalikan access token di memori.
     * - Jika `hasResolution()` true (perlu interaksi pengguna), TIDAK memunculkan Activity,
     *   menandai antrean belum selesai sebagai [UploadState.NEEDS_RECONNECT], dan mengembalikan null.
     */
    suspend fun getAccessTokenOrNull(context: Context = appContext): String? {
        val snap = cloudSettings.snapshot()
        val result = runCatching {
            authorizeInternal(context, snap.connectedAccountEmail)
        }.getOrElse {
            return null
        }

        if (result.hasResolution()) {
            cloudUploadDao.markUnfinishedAs(UploadState.NEEDS_RECONNECT, MSG_AUTH_LOST)
            return null
        }

        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: return null
        persistAccountEmailIfPresent(result)
        return token
    }

    /** Memulai permintaan otorisasi penuh (dipakai dari UI saat pengguna menekan Hubungkan). */
    suspend fun requestAuthorization(context: Context = appContext): AuthorizationResult =
        authorizeInternal(context, emailHint = null)

    /** Mengubah [AuthorizationResult] berstatus `hasResolution()` menjadi [IntentSender] untuk Activity launcher. */
    fun buildResolutionIntentSender(result: AuthorizationResult): IntentSender {
        val pending = result.pendingIntent
            ?: throw IllegalStateException("PendingIntent otorisasi Google tidak tersedia.")
        return pending.intentSender
    }

    /** Mem-parse hasil Intent balik dari `ActivityResultContracts.StartIntentSenderForResult`. */
    fun getAuthorizationResultFromIntent(context: Context, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    /** Menyimpan email akun Google ke [CloudSettings] hanya untuk tampilan status UI. */
    suspend fun saveConnectedAccountEmail(email: String?) {
        cloudSettings.setConnectedAccountEmail(email)
    }

    /** Menandai sesi terhubung & menyimpan email akun (jika tersedia) setelah otorisasi UI berhasil. */
    suspend fun onAuthorizationGranted(result: AuthorizationResult): Boolean {
        val tokenPresent = !result.accessToken.isNullOrBlank()
        if (!tokenPresent) return false
        persistAccountEmailIfPresent(result)
        cloudSettings.setCloudEnabled(true)
        cloudUploadDao.resetState(UploadState.NEEDS_RECONNECT, UploadState.PENDING)
        return true
    }

    /** Menghapus cache token akses di Google Play Services (dipakai saat HTTP 401 untuk 1x refresh). */
    suspend fun invalidateAccessToken(context: Context = appContext, accessToken: String) {
        if (accessToken.isBlank()) return
        runCatching {
            val req = ClearTokenRequest.builder().setToken(accessToken).build()
            Identity.getAuthorizationClient(context).clearToken(req).awaitTask()
        }
    }

    /**
     * Memutus koneksi Google Drive:
     * hapus status terhubung di [CloudSettings], batalkan seluruh worker upload,
     * dan ubah antrean yang belum `DONE` menjadi [UploadState.NEEDS_RECONNECT].
     */
    suspend fun disconnect() {
        val email = cloudSettings.snapshot().connectedAccountEmail
        if (!email.isNullOrBlank()) {
            runCatching {
                val req = RevokeAccessRequest.builder()
                    .setScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                    .setAccount(Account(email, "com.google"))
                    .build()
                Identity.getAuthorizationClient(appContext).revokeAccess(req).awaitTask()
            }
        }
        WorkManager.getInstance(appContext).cancelAllWorkByTag(CLOUD_UPLOAD_WORK_TAG)
        cloudSettings.clearConnection()
        cloudUploadDao.markUnfinishedAs(UploadState.NEEDS_RECONNECT, MSG_AUTH_LOST)
    }

    private suspend fun authorizeInternal(context: Context, emailHint: String?): AuthorizationResult {
        val reqBuilder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        if (!emailHint.isNullOrBlank()) {
            reqBuilder.setAccount(Account(emailHint, "com.google"))
        }
        val req = reqBuilder.build()
        return Identity.getAuthorizationClient(context).authorize(req).awaitTask()
    }

    private suspend fun persistAccountEmailIfPresent(result: AuthorizationResult) {
        val email = runCatching { result.toGoogleSignInAccount()?.email }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (email != null) {
            saveConnectedAccountEmail(email)
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value ->
            if (cont.isActive) cont.resume(value)
        }
        addOnFailureListener { err ->
            if (cont.isActive) cont.resumeWithException(err)
        }
        addOnCanceledListener {
            cont.cancel()
        }
    }
}
