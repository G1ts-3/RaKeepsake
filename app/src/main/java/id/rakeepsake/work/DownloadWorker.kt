package id.rakeepsake.work

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import id.rakeepsake.RaApp
import id.rakeepsake.cloud.work.CloudUploadScheduler
import id.rakeepsake.data.Post
import id.rakeepsake.net.Http
import id.rakeepsake.net.ResolveException
import id.rakeepsake.net.TiktokResolver
import id.rakeepsake.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

@Immutable
data class DownloadProgress(
    val postId: String,
    val currentFile: Int,
    val totalFiles: Int,
    val bytesRead: Long,
    val totalBytes: Long,
    val stage: String = "Mengunduh",
)

@Immutable
data class DownloadBatchProgress(
    val completed: Int = 0,
    val total: Int = 0,
)

/** Menyimpan progres unduhan per-post dan ringkasan antrean secara real-time di memori untuk UI. */
object DownloadProgressHolder {
    private val lock = Any()
    private val map = HashMap<String, DownloadProgress>()
    private val trackedIds = HashSet<String>()
    private val completedIds = HashSet<String>()

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    private val _batch = MutableStateFlow(DownloadBatchProgress())
    val batch: StateFlow<DownloadBatchProgress> = _batch.asStateFlow()

    fun onQueued(postId: String) {
        synchronized(lock) {
            if (trackedIds.isNotEmpty() && completedIds.size >= trackedIds.size && map.isEmpty()) {
                trackedIds.clear()
                completedIds.clear()
            }
            trackedIds.add(postId)
            completedIds.remove(postId)
            _batch.value = DownloadBatchProgress(completedIds.size, trackedIds.size)
        }
    }

    fun update(item: DownloadProgress) {
        synchronized(lock) {
            val added = trackedIds.add(item.postId)
            val reopened = completedIds.remove(item.postId)
            if (added || reopened) {
                _batch.value = DownloadBatchProgress(completedIds.size, trackedIds.size)
            }
            map[item.postId] = item
            _progress.value = map.toMap()
        }
    }

    fun finish(postId: String) {
        synchronized(lock) {
            map.remove(postId)
            _progress.value = map.toMap()
            if (trackedIds.contains(postId)) {
                completedIds.add(postId)
                _batch.value = DownloadBatchProgress(completedIds.size, trackedIds.size)
            }
        }
    }
}

/**
 * Unduh URL media ke MediaStore dengan resume (Range) dan dedupe via tabel Post.
 * Input data:
 *   postId (String), url (String), audioUrl (String, opsional), author, caption,
 *   type (VIDEO|PHOTO), extension, accountId.
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as RaApp).container
        val db = container.db
        val settings = container.settings

        val postId = inputData.getString(KEY_POST_ID) ?: return@withContext Result.failure()
        var url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        var author = inputData.getString(KEY_AUTHOR) ?: "tiktok"
        var caption = inputData.getString(KEY_CAPTION) ?: ""
        var type = inputData.getString(KEY_TYPE) ?: "VIDEO"
        var ext = inputData.getString(KEY_EXT) ?: "mp4"
        var audioUrl = inputData.getString(KEY_AUDIO_URL)
        val accountId = inputData.getLong(KEY_ACCOUNT_ID, 0L)
        var mediaCookies = inputData.getString(KEY_MEDIA_COOKIES)
        val downloadAudioAlso = if (inputData.keyValueMap.containsKey(KEY_AUDIO_ONLY)) {
            inputData.getBoolean(KEY_AUDIO_ONLY, false)
        } else {
            runCatching { settings.audioOnly.first() }.getOrDefault(false)
        }
        val cookie = runCatching { settings.tiktokSession.first() }.getOrNull()

        // Tautan halaman TikTok (video/photo/story/pendek): resolve via rantai resolver dulu.
        // URL media CDN hasil scrape tidak mengandung /video/<id15+> sehingga tidak di-resolve ulang.
        val isPageLink = Regex("/video/\\d{10,}").containsMatchIn(url) ||
            Regex("/photo/\\d{10,}").containsMatchIn(url) ||
            Regex("/story/\\d{10,}").containsMatchIn(url) ||
            url.contains("vm.tiktok.com") || url.contains("vt.tiktok.com") ||
            url.contains("/t/") || postId.startsWith("manual_")

        val sourceUrl = inputData.getString(KEY_SOURCE_URL)?.trim().orEmpty().ifBlank {
            if (isPageLink) url else ""
        }

        val existing = db.posts().byPostId(postId)
        if (existing?.status == "DONE" || existing?.status == "DELETED") {
            DownloadProgressHolder.finish(postId)
            return@withContext Result.success()
        }
        if (existing == null) {
            db.posts().insert(
                Post(
                    postId = postId,
                    accountId = accountId,
                    type = type,
                    caption = caption.take(200),
                    author = author,
                    status = "QUEUED",
                    sourceUrl = sourceUrl,
                ),
            )
        }

        val wifiOnly = settings.wifiOnly.first()
        if (wifiOnly && !Network.isUnmetered(applicationContext)) {
            db.posts().setStatus(postId, "QUEUED")
            DownloadProgressHolder.finish(postId)
            return@withContext Result.retry()
        }

        val minFreeBytes = settings.minFreeMb.first() * 1024L * 1024L
        if (Storage.freeBytes() < minFreeBytes) {
            db.posts().updateResult(
                postId, "FAILED",
                "Ruang penyimpanan kurang dari ${settings.minFreeMb.first()} MB",
                "", System.currentTimeMillis(),
            )
            DownloadProgressHolder.finish(postId)
            return@withContext Result.failure()
        }

        db.posts().setStatus(postId, "DOWNLOADING")

        if (isPageLink) {
            DownloadProgressHolder.update(
                DownloadProgress(postId, currentFile = 0, totalFiles = 1, bytesRead = 0L, totalBytes = -1L, stage = "Menyiapkan tautan…"),
            )
            val relay = settings.relayUrl.first()
            val resolved = try {
                TiktokResolver.resolve(url, relay, cookie)
            } catch (e: ResolveException) {
                android.util.Log.w(LOG_TAG, "resolve gagal utk $postId: ${e.message}")
                val errMsg = if (e.watermarkOnly) "Hanya versi ber-watermark tersedia. Belum disimpan (kebijakan tanpa watermark)."
                else e.message ?: e.javaClass.simpleName
                db.posts().updateResult(postId, "FAILED", errMsg, "", System.currentTimeMillis())
                DownloadProgressHolder.finish(postId)
                Notifier.downloadFailed(applicationContext, author, e.message ?: "resolve gagal")
                return@withContext Result.failure()
            }
            author = resolved.author.ifBlank { author }
            caption = resolved.caption
            audioUrl = resolved.audioUrl
            mediaCookies = resolved.mediaCookies
            url = resolved.mediaUrls.joinToString("\n")
            if (resolved.extension == "jpg") {
                type = "PHOTO"; ext = "jpg"
            } else if (resolved.via.startsWith("story")) {
                type = "STORY"; ext = resolved.extension
            }
            db.posts().updateResolvedMeta(
                postId = postId,
                accountId = accountId,
                type = type,
                caption = caption.take(200),
                author = author,
                sourceUrl = sourceUrl.ifBlank { url },
            )
        }

        val timestamp = existing?.remoteCreatedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        val isStory = type == "STORY" || postId.contains("story", ignoreCase = true) || sourceUrl.contains("/story/", ignoreCase = true)

        try {
            val res = executeMediaDownload(
                postId = postId,
                mediaUrlsRaw = url,
                audioSrc = audioUrl,
                author = author,
                isStory = isStory,
                isPhotoMedia = (type == "PHOTO" || ext == "jpg"),
                ext = ext,
                timestamp = timestamp,
                downloadAudioAlso = downloadAudioAlso,
                cookie = cookie,
                mediaCookies = mediaCookies,
            )
            db.posts().updateResult(postId, "DONE", null, res.joinedPaths, System.currentTimeMillis())
            enqueuePublishedForCloud(res.publishedItems)
            container.refreshLibrary()
            Notifier.downloadDone(applicationContext, author, caption)
            Result.success()
        } catch (e: Exception) {
            // Fallback: resolusi ulang via TiktokResolver menggunakan sourceUrl asli jika URL CDN 403/kadaluarsa
            val fallbackUrl = sourceUrl.ifBlank {
                val rawPostId = if (postId.startsWith("tt_${author}_")) postId.removePrefix("tt_${author}_") else ""
                if (rawPostId.length >= 10 && rawPostId.all { it.isDigit() }) {
                    if (isStory) "https://www.tiktok.com/@$author/story/$rawPostId"
                    else "https://www.tiktok.com/@$author/video/$rawPostId"
                } else ""
            }
            if (fallbackUrl.isNotBlank()) {
                try {
                    DownloadProgressHolder.update(
                        DownloadProgress(postId, currentFile = 0, totalFiles = 1, bytesRead = 0L, totalBytes = -1L, stage = "Mencoba sumber cadangan…"),
                    )
                    val resolved = TiktokResolver.resolve(fallbackUrl, settings.relayUrl.first(), cookie)
                    if (resolved.mediaUrls.isNotEmpty()) {
                        val nextAuthor = resolved.author.ifBlank { author }
                        val nextType = if (resolved.extension == "jpg") "PHOTO" else type
                        db.posts().updateResolvedMeta(
                            postId = postId,
                            accountId = accountId,
                            type = nextType,
                            caption = resolved.caption.take(200),
                            author = nextAuthor,
                            sourceUrl = fallbackUrl,
                        )
                        val res = executeMediaDownload(
                            postId = postId,
                            mediaUrlsRaw = resolved.mediaUrls.joinToString("\n"),
                            audioSrc = resolved.audioUrl ?: audioUrl,
                            author = nextAuthor,
                            isStory = isStory,
                            isPhotoMedia = (nextType == "PHOTO" || resolved.extension == "jpg"),
                            ext = resolved.extension,
                            timestamp = timestamp,
                            downloadAudioAlso = downloadAudioAlso,
                            cookie = cookie,
                            mediaCookies = resolved.mediaCookies ?: mediaCookies,
                        )
                        db.posts().updateResult(postId, "DONE", null, res.joinedPaths, System.currentTimeMillis())
                        enqueuePublishedForCloud(res.publishedItems)
                        container.refreshLibrary()
                        Notifier.downloadDone(applicationContext, nextAuthor, resolved.caption)
                        return@withContext Result.success()
                    }
                } catch (re: Exception) {
                    android.util.Log.w(LOG_TAG, "Fallback TiktokResolver juga gagal untuk $postId: ${re.message}")
                }
            }
            val msg = e.message ?: e.javaClass.simpleName
            android.util.Log.w(LOG_TAG, "unduhan gagal utk $postId: $msg")
            if (e is java.io.IOException && runAttemptCount < 1) {
                db.posts().setStatus(postId, "QUEUED")
                Result.retry()
            } else {
                db.posts().updateResult(postId, "FAILED", msg, "", System.currentTimeMillis())
                Notifier.downloadFailed(applicationContext, author, msg)
                Result.failure()
            }
        } finally {
            DownloadProgressHolder.finish(postId)
        }
    }

    private data class PublishedMediaItem(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
    )

    private data class DownloadOutcome(
        val joinedPaths: String,
        val publishedItems: List<PublishedMediaItem>,
    )

    private suspend fun enqueuePublishedForCloud(items: List<PublishedMediaItem>) {
        for (item in items) {
            runCatching {
                CloudUploadScheduler.enqueueIfAuto(
                    applicationContext,
                    item.uri,
                    item.displayName,
                    item.mimeType,
                )
            }
        }
    }

    private fun executeMediaDownload(
        postId: String,
        mediaUrlsRaw: String,
        audioSrc: String?,
        author: String,
        isStory: Boolean,
        isPhotoMedia: Boolean,
        ext: String,
        timestamp: Long,
        downloadAudioAlso: Boolean,
        cookie: String?,
        mediaCookies: String?,
    ): DownloadOutcome {
        val tmpDir = File(applicationContext.getExternalFilesDir(null), "tmp").apply { mkdirs() }
        val subFolder = if (isStory) Storage.STORY_DIR else Storage.POST_DIR
        val targetDir = "${Storage.APP_DIR}/$subFolder/$author"
        val paths = mutableListOf<String>()
        val publishedItems = mutableListOf<PublishedMediaItem>()

        val mediaUrls = mediaUrlsRaw.split("\n").filter { it.isNotBlank() }
        if (mediaUrls.isEmpty()) throw Exception("Daftar URL media kosong")
        val totalFiles = (if (isPhotoMedia) mediaUrls.size else 1) +
            (if (downloadAudioAlso && !audioSrc.isNullOrBlank()) 1 else 0)
        var currentFileIndex = 0

        if (isPhotoMedia) {
            mediaUrls.forEachIndexed { idx, u ->
                currentFileIndex++
                val fileNum = currentFileIndex
                val displayName = Storage.resolveUniqueFileName(
                    applicationContext, "TIKTOK", if (isStory) "story" else "post", author,
                    timestamp, "jpg", index = if (mediaUrls.size > 1) idx + 1 else 0,
                )
                val f = downloadTo(tmpDir, "tmp_img_${System.currentTimeMillis()}-$idx", u, cookie, mediaCookies) { read, total ->
                    DownloadProgressHolder.update(DownloadProgress(postId, fileNum, totalFiles, read, total))
                }
                val published = Storage.publishImage(applicationContext, f, targetDir, displayName)
                f.delete()
                if (published != null) {
                    publishedItems.add(PublishedMediaItem(published, displayName, "image/jpeg"))
                }
                paths.add(published?.toString() ?: "")
                paths.add(displayName)
            }
        } else {
            currentFileIndex++
            val fileNum = currentFileIndex
            val displayName = Storage.resolveUniqueFileName(
                applicationContext, "TIKTOK", if (isStory) "story" else "post", author,
                timestamp, ext,
            )
            val f = downloadTo(tmpDir, "tmp_vid_${System.currentTimeMillis()}", mediaUrls.first(), cookie, mediaCookies) { read, total ->
                DownloadProgressHolder.update(DownloadProgress(postId, fileNum, totalFiles, read, total))
            }
            val mime = Storage.mimeFor(ext)
            val published = Storage.publish(
                applicationContext, f, targetDir, displayName, mime,
            )
            f.delete()
            if (published != null) {
                publishedItems.add(PublishedMediaItem(published, displayName, mime))
            }
            paths.add(published?.toString() ?: "")
            paths.add(displayName)
        }

        // Unduh audio (.m4a) HANYA jika opsi "Download Audio juga" dinyalakan
        if (downloadAudioAlso && !audioSrc.isNullOrBlank()) {
            currentFileIndex++
            val fileNum = currentFileIndex
            runCatching {
                val audioDisplayName = Storage.resolveUniqueFileName(
                    applicationContext, "TIKTOK", if (isStory) "story" else "post", author,
                    timestamp, "m4a", suffix = "audio",
                )
                val af = downloadTo(tmpDir, "tmp_audio_opt_${System.currentTimeMillis()}", audioSrc, cookie, mediaCookies) { read, total ->
                    DownloadProgressHolder.update(DownloadProgress(postId, fileNum, totalFiles, read, total))
                }
                val pubAudio = Storage.publishAudio(applicationContext, af, targetDir, audioDisplayName)
                af.delete()
                if (pubAudio != null) {
                    publishedItems.add(PublishedMediaItem(pubAudio, audioDisplayName, Storage.mimeFor("m4a")))
                    paths.add(pubAudio.toString())
                    paths.add(audioDisplayName)
                }
            }
        }

        return DownloadOutcome(
            joinedPaths = paths.filter { it.isNotBlank() }.joinToString("\n"),
            publishedItems = publishedItems,
        )
    }

    /** Unduh dengan dukungan resume dan callback progres byte. */
    private fun downloadTo(
        tmpDir: File,
        base: String,
        url: String,
        cookie: String? = null,
        mediaCookies: String? = null,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        val target = File(tmpDir, Storage.sanitize(base) + ".part")
        var startFrom = if (target.exists()) target.length() else 0L
        val maxAttempts = 5
        var attempt = 0
        val cookieHeader = cookie?.let { if (it.contains("=")) it else "sessionid=$it; sessionid_ss=$it" }

        val parsedHost = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        val isTikTokDomain = parsedHost == "tiktok.com" || parsedHost.endsWith(".tiktok.com")
        // Host CDN publik non-webapp tidak boleh menerima sessionid, namun *.webapp-prime.tiktok.com butuh mediaCookies (tt_chain_token)
        val isExternalCdn = parsedHost.contains("tiktokcdn.com") ||
            parsedHost.contains("byteoversea.com") || parsedHost.contains("ibytedtos.com") || parsedHost.contains("musical.ly")
        val isWebappPrime = parsedHost.contains("webapp") && isTikTokDomain
        val effectiveCookies = when {
            isExternalCdn || !isTikTokDomain -> null
            isWebappPrime -> mediaCookies?.takeIf { it.isNotBlank() }
            else -> Http.mergeCookies(cookieHeader, mediaCookies).takeIf { it.isNotBlank() }
        }
        val chromeAndroidUa = "Mozilla/5.0 (Linux; Android 13; Redmi 9T) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        while (true) {
            attempt++
            if (attempt > maxAttempts) {
                target.delete()
                throw Exception("Unduhan gagal setelah $maxAttempts percobaan: $url")
            }
            val ua = if (attempt % 2 == 1) Http.UA_MOBILE else chromeAndroidUa
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Referer", "https://www.tiktok.com/")
                .apply {
                    if (!effectiveCookies.isNullOrBlank()) {
                        header("Cookie", effectiveCookies)
                    }
                    header("Range", "bytes=$startFrom-")
                }
            val req = reqBuilder.build()
            try {
                Http.downloadClient.newCall(req).execute().use { resp ->
                    if (resp.code == 416) { // sudah lengkap
                        return target
                    }
                    check(resp.isSuccessful) { "HTTP ${resp.code} saat mengunduh media" }
                    val body = resp.body ?: throw Exception("Respons kosong saat mengunduh")
                    val appending = resp.code == 206 && startFrom > 0
                    if (!appending) {
                        startFrom = 0
                        target.delete()
                    }
                    val total = body.contentLength()
                    val totalExpected = if (total > 0L) startFrom + total else -1L
                    var readTotal = startFrom
                    var lastReportAt = 0L
                    onProgress(readTotal, totalExpected)
                    val out = java.io.FileOutputStream(target, appending)
                    try {
                        val source = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = source.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readTotal += n
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt >= 80L) {
                                lastReportAt = now
                                onProgress(readTotal, totalExpected)
                            }
                        }
                        out.flush()
                        onProgress(readTotal, if (totalExpected > 0L) totalExpected else readTotal)
                    } finally {
                        out.close()
                    }
                }
                return target
            } catch (e: Exception) {
                if (e is IllegalStateException && e.message?.contains("403") == true && attempt < maxAttempts) {
                    android.util.Log.w(LOG_TAG, "HTTP 403 untuk $url (attempt $attempt), berganti UA dan mencoba kembali...")
                } else if (e is java.io.IOException) {
                    // Jaringan putus: coba resume dari panjang berkas saat ini.
                    startFrom = target.length()
                }
                if (attempt == maxAttempts) throw e
                Thread.sleep(1500L * attempt)
            }
        }
    }

    companion object {
        const val KEY_POST_ID = "post_id"
        const val KEY_URL = "url"
        const val KEY_AUDIO_URL = "audio_url"
        const val KEY_AUTHOR = "author"
        const val KEY_CAPTION = "caption"
        const val KEY_TYPE = "type"
        const val KEY_EXT = "ext"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_MEDIA_COOKIES = "media_cookies"
        const val KEY_SOURCE_URL = "source_url"
        const val TAG = "download"
        private const val LOG_TAG = "RaDownload"
    }
}

object Network {
    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
