package id.rakeepsake.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.rakeepsake.RaApp
import id.rakeepsake.data.AppContainer
import id.rakeepsake.data.Platform
import id.rakeepsake.data.Post
import id.rakeepsake.net.PlatformApi
import id.rakeepsake.data.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Pemantau postingan dan story baru TikTok per akun (FR-14): bandingkan id terbaru dengan
 * lastPostId, antrekan DownloadWorker untuk yang baru, perbarui lastPostId.
 */
class PostWatchWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as RaApp).container
        checkAccounts(applicationContext, container)
        return Result.success()
    }

    companion object {
        suspend fun checkSingleAccount(
            context: Context,
            container: AppContainer,
            account: Account,
            sessionCookie: String? = null,
        ): Int {
            if (account.platform != Platform.TIKTOK.name) return 0
            val cookie = sessionCookie ?: runCatching { container.settings.tiktokSession.first() }.getOrNull()
            val downloadAudioAlso = runCatching { container.settings.audioOnly.first() }.getOrDefault(false)
            android.util.Log.d("RaPostWatch", "Memeriksa akun: ${account.handle}")
            val posts = PlatformApi.tiktokRecentPosts(account.handle, limit = 12, cookie = cookie)
            android.util.Log.d("RaPostWatch", "Akun ${account.handle} dapat ${posts.size} post/story")
            if (posts.isEmpty()) return 0

            var newCount = 0
            val newest = posts.maxByOrNull { it.createdAt } ?: return 0
            val now = System.currentTimeMillis()
            for (ref in posts) {
                val postId = "tt_${account.handle}_${ref.id}"
                val exists = container.db.posts().byPostId(postId)
                android.util.Log.d("RaPostWatch", "Post $postId exists=${exists != null} status=${exists?.status} isStory=${ref.isStory} createdAt=${ref.createdAt}")
                if (exists == null) {
                    // Unduh otomatis postingan/story baru yang diunggah dalam 24 jam terakhir atau hari ini
                    val ageMs = now - ref.createdAt
                    val isRecentUpload = ref.createdAt > 0L && (ref.isStory || isSameDay(ref.createdAt, now) || ageMs in -60_000L..(24 * 3600_000L))

                    if (!isRecentUpload) {
                        android.util.Log.d("RaPostWatch", "Abaikan postingan lama (>24 jam): $postId (createdAt=${ref.createdAt}, ageMs=$ageMs)")
                        continue
                    }

                    val type = if (ref.isStory) "STORY" else if (ref.isPhoto) "PHOTO" else "VIDEO"
                    val caption = ref.caption.ifBlank { if (ref.isStory) "Story @${account.handle}" else "Postingan @${account.handle}" }
                    val sourcePageUrl = if (ref.isStory) {
                        "https://www.tiktok.com/@${account.handle}/story/${ref.id}"
                    } else {
                        "https://www.tiktok.com/@${account.handle}/video/${ref.id}"
                    }
                    container.db.posts().insert(
                        Post(
                            postId = postId,
                            accountId = account.id,
                            type = type,
                            caption = caption.take(200),
                            author = account.handle,
                            remoteCreatedAt = ref.createdAt,
                            status = "QUEUED",
                            sourceUrl = sourcePageUrl,
                        ),
                    )
                    val downloadUrl = if (ref.mediaUrls.isNotEmpty()) {
                        ref.mediaUrls.joinToString("\n")
                    } else {
                        sourcePageUrl
                    }
                    val ext = if (ref.isPhoto) "jpg" else "mp4"
                    android.util.Log.d("RaPostWatch", "Enqueue download untuk $postId url: $downloadUrl")
                    enqueueDownload(
                        context,
                        postId = postId,
                        url = downloadUrl,
                        author = account.handle,
                        caption = caption,
                        accountId = account.id,
                        type = type,
                        ext = ext,
                        audioUrl = ref.audioUrl,
                        audioOnly = downloadAudioAlso,
                        mediaCookies = ref.mediaCookies,
                        sourceUrl = sourcePageUrl,
                    )
                    newCount++
                }
            }
            container.db.accounts().setLastPostId(account.id, newest.id)
            return newCount
        }

        suspend fun checkAccounts(context: Context, container: AppContainer) = coroutineScope {
            val sessionCookie = runCatching { container.settings.tiktokSession.first() }.getOrNull()
            android.util.Log.d("RaPostWatch", "checkAccounts dipanggil. Sesi cookie: ${sessionCookie?.take(8)}...")
            val accounts = container.db.accounts().allOnce()
                .filter { it.platform == Platform.TIKTOK.name && it.watchPosts }
            android.util.Log.d("RaPostWatch", "Jumlah akun TikTok watchPosts: ${accounts.size}")

            val semaphore = Semaphore(3)
            val jobs = accounts.map { account ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            checkSingleAccount(context, container, account, sessionCookie)
                        } catch (e: Exception) {
                            android.util.Log.w("RaPostWatch", "Gagal cek akun ${account.handle}: ${e.message}", e)
                            0
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
        fun enqueueDownload(
            context: Context,
            postId: String,
            url: String,
            author: String,
            caption: String,
            accountId: Long = 0,
            type: String = "VIDEO",
            ext: String = "mp4",
            audioUrl: String? = null,
            audioOnly: Boolean = false,
            mediaCookies: String? = null,
            sourceUrl: String = "",
        ) {
            val data = Data.Builder()
                .putString(DownloadWorker.KEY_POST_ID, postId)
                .putString(DownloadWorker.KEY_URL, url)
                .putString(DownloadWorker.KEY_AUTHOR, author)
                .putString(DownloadWorker.KEY_CAPTION, caption)
                .putLong(DownloadWorker.KEY_ACCOUNT_ID, accountId)
                .putString(DownloadWorker.KEY_TYPE, type)
                .putString(DownloadWorker.KEY_EXT, ext)
                .apply {
                    audioUrl?.let { putString(DownloadWorker.KEY_AUDIO_URL, it) }
                    putBoolean(DownloadWorker.KEY_AUDIO_ONLY, audioOnly)
                    if (!mediaCookies.isNullOrBlank()) putString(DownloadWorker.KEY_MEDIA_COOKIES, mediaCookies)
                    if (sourceUrl.isNotBlank()) putString(DownloadWorker.KEY_SOURCE_URL, sourceUrl)
                }
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .addTag(DownloadWorker.TAG)
                .build()
            DownloadProgressHolder.onQueued(postId)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "dl-$postId", ExistingWorkPolicy.REPLACE, request,
            )
        }

        fun isSameDay(time1: Long, time2: Long): Boolean {
            if (time1 <= 0L || time2 <= 0L) return false
            val c1 = java.util.Calendar.getInstance().apply { timeInMillis = time1 }
            val c2 = java.util.Calendar.getInstance().apply { timeInMillis = time2 }
            return c1.get(java.util.Calendar.ERA) == c2.get(java.util.Calendar.ERA) &&
                   c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR) &&
                   c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR)
        }

        fun isSameHour(time1: Long, time2: Long): Boolean {
            if (time1 <= 0L || time2 <= 0L) return false
            val c1 = java.util.Calendar.getInstance().apply { timeInMillis = time1 }
            val c2 = java.util.Calendar.getInstance().apply { timeInMillis = time2 }
            return isSameDay(time1, time2) &&
                   c1.get(java.util.Calendar.HOUR_OF_DAY) == c2.get(java.util.Calendar.HOUR_OF_DAY)
        }
    }
}
