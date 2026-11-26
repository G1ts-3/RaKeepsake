package id.rakeepsake.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Cache thumbnail video ringan (RAM LruCache + Disk cache JPEG berukuran kecil <= 256px)
 * sehingga scroll di Pustaka tetap mulus 60 fps tanpa membebani GPU/GC.
 */
object ThumbCache {

    private val memoryCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun getCachedMemory(uri: Uri): Bitmap? = memoryCache.get(uri.toString())

    suspend fun loadVideoThumb(context: Context, uri: Uri): Bitmap? {
        val key = uri.toString()
        memoryCache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val thumbDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
            val diskFile = File(thumbDir, "${key.hashCode().toUInt()}.jpg")
            if (diskFile.exists() && diskFile.length() > 0L) {
                runCatching {
                    BitmapFactory.decodeFile(diskFile.absolutePath)?.let { bmp ->
                        memoryCache.put(key, bmp)
                        return@withContext bmp
                    }
                }
            }

            var rawBmp: Bitmap? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                rawBmp = runCatching {
                    context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                }.getOrNull()
            }
            if (rawBmp == null) {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, uri)
                    rawBmp = mmr.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: mmr.getFrameAtTime(0L)
                } catch (_: Throwable) {
                } finally {
                    runCatching { mmr.release() }
                }
            }

            val finalBmp = rawBmp?.let { downscaleIfNeeded(it, 256) }
            if (finalBmp != null) {
                memoryCache.put(key, finalBmp)
                runCatching {
                    FileOutputStream(diskFile).use { out ->
                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
                    }
                }
            }
            finalBmp
        }
    }

    private fun downscaleIfNeeded(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)
        if (scaled !== src) src.recycle()
        return scaled
    }
}
