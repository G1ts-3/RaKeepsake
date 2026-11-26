package id.rakeepsake.monitor

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Pengubah format (transmux/remux) berkas MPEG-TS menjadi MP4 standar tanpa re-encode.
 * Menggunakan MediaExtractor dan MediaMuxer bawaan Android (API 26+).
 * Mengatasi lonjakan timestamp/PTS antar-segmen HLS agar berkas MP4 mulus dan bisa diputar
 * di seluruh pemutar media (ExoPlayer, Galeri, WhatsApp, Instagram).
 */
object TsRemuxer {
    private const val TAG = "TsRemuxer"
    private const val BUFFER_SIZE = 1 * 1024 * 1024 // 1 MB buffer per chunk

    /**
     * Remux berkas MPEG-TS [srcTs] menjadi MP4 di [dstMp4].
     * @return File MP4 yang berhasil dibuat, atau null bila gagal (agar sistem bisa fallback ke .ts).
     */
    fun remuxToMp4(srcTs: File, dstMp4: File): File? {
        if (!srcTs.exists() || srcTs.length() == 0L) {
            Log.w(TAG, "Berkas sumber tidak ada atau kosong: ${srcTs.path}")
            return null
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(srcTs.absolutePath)
            val trackCount = extractor.trackCount
            if (trackCount == 0) {
                Log.w(TAG, "Tidak ada trek ditemukan pada ${srcTs.name}")
                return null
            }

            dstMp4.parentFile?.mkdirs()
            if (dstMp4.exists()) dstMp4.delete()

            muxer = MediaMuxer(dstMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>() // extractorTrackIndex -> muxerTrackIndex

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                    Log.d(TAG, "Trek terpilih #$i: $mime -> muxer #$muxerTrack")
                }
            }

            if (trackMap.isEmpty()) {
                Log.w(TAG, "Tidak ada trek audio/video yang dapat dimux dari ${srcTs.name}")
                return null
            }

            muxer.start()
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            // Pelacak timestamp agar monoton naik dan tidak terjadi nilai mundur antar-segmen
            val lastPts = HashMap<Int, Long>()
            val offsetPts = HashMap<Int, Long>()
            val firstPts = HashMap<Int, Long>()

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val muxerTrackIndex = trackMap[trackIndex]
                if (muxerTrackIndex == null) {
                    extractor.advance()
                    continue
                }

                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val rawTimeUs = extractor.sampleTime
                if (!firstPts.containsKey(trackIndex)) {
                    firstPts[trackIndex] = rawTimeUs
                    offsetPts[trackIndex] = 0L
                    lastPts[trackIndex] = 0L
                }

                val baseTime = firstPts[trackIndex] ?: 0L
                var adjustedTime = (rawTimeUs - baseTime) + (offsetPts[trackIndex] ?: 0L)

                val prev = lastPts[trackIndex] ?: 0L
                if (adjustedTime <= prev) {
                    // Deteksi timestamp melompat mundur (discontinuity)
                    val step = 33_333L // ~30 fps fallback step
                    val correction = (prev - adjustedTime) + step
                    val currentOffset = offsetPts[trackIndex] ?: 0L
                    offsetPts[trackIndex] = currentOffset + correction
                    adjustedTime += correction
                }
                lastPts[trackIndex] = adjustedTime

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = adjustedTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()

            if (dstMp4.exists() && dstMp4.length() > 0L) {
                Log.i(TAG, "Sukses remux ${srcTs.name} -> ${dstMp4.name} (${dstMp4.length()} bytes)")
                return dstMp4
            } else {
                dstMp4.delete()
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal remux ${srcTs.name} ke MP4: ${e.message}", e)
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (dstMp4.exists()) dstMp4.delete()
            return null
        }
    }
}
