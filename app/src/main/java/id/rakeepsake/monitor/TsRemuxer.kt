package id.rakeepsake.monitor

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Pengubah format (transmux/remux) berkas MPEG-TS menjadi MP4 standar tanpa re-encode.
 * Menggunakan MediaExtractor untuk demux dan Media3 Mp4Muxer untuk menulis MP4 dengan dukungan
 * penuh B-frame (`ctts` v1 + `stts` tersinkron), sehingga mulus di ExoPlayer & decoder perangkat
 * MediaTek/Samsung/Android lintas versi tanpa macet di `00:00`.
 */
@OptIn(UnstableApi::class)
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
        var muxer: Mp4Muxer? = null
        var fos: FileOutputStream? = null

        try {
            extractor.setDataSource(srcTs.absolutePath)
            val trackCount = extractor.trackCount
            if (trackCount == 0) {
                Log.w(TAG, "Tidak ada trek ditemukan pada ${srcTs.name}")
                return null
            }

            dstMp4.parentFile?.mkdirs()
            if (dstMp4.exists()) dstMp4.delete()

            fos = FileOutputStream(dstMp4)
            // Matikan penulisan ulang `moov` berkala setiap 1 detik agar remux rekaman panjang tetap O(N) & ringan di penyimpanan penuh
            muxer = Mp4Muxer.Builder(fos)
                .setAttemptStreamableOutputEnabled(false)
                .build()

            val trackMap = HashMap<Int, Muxer.TrackToken>()
            val trackIsVideo = HashMap<Int, Boolean>()
            val trackStepUs = HashMap<Int, Long>()
            var videoTrackIndex = -1

            for (i in 0 until trackCount) {
                val rawFormat = extractor.getTrackFormat(i)
                val mime = rawFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val m3Format = buildMedia3Format(rawFormat, mime)
                    if (m3Format == null) {
                        Log.w(TAG, "Lewati trek #$i ($mime): CSD/metadata tidak lengkap")
                        continue
                    }
                    extractor.selectTrack(i)
                    val token = muxer.addTrack(m3Format)
                    trackMap[i] = token
                    val isVideo = mime.startsWith("video/")
                    trackIsVideo[i] = isVideo
                    trackStepUs[i] = if (isVideo) 40_000L else 42_667L
                    if (isVideo && videoTrackIndex < 0) videoTrackIndex = i
                    Log.d(TAG, "Trek terpilih #$i: $mime")
                }
            }

            if (trackMap.isEmpty()) {
                Log.w(TAG, "Tidak ada trek audio/video yang dapat dimux dari ${srcTs.name}")
                runCatching { muxer.close() }
                runCatching { fos.close() }
                dstMp4.delete()
                return null
            }

            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            val normalizer = RemuxTimelineNormalizer()
            var seenVideoKeyframe = (videoTrackIndex < 0)

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val token = trackMap[trackIndex]
                if (token == null) {
                    extractor.advance()
                    continue
                }

                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                if (sampleSize == 0) {
                    extractor.advance()
                    continue
                }

                val isVideo = trackIsVideo[trackIndex] == true
                val rawFlags = extractor.sampleFlags
                val isKeyframe = (rawFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0

                if (isVideo && !seenVideoKeyframe) {
                    if (!isKeyframe) {
                        extractor.advance()
                        continue
                    }
                    seenVideoKeyframe = true
                }

                val stepUs = trackStepUs[trackIndex] ?: 40_000L
                val adjustedTime = normalizer.nextPtsUs(
                    trackId = trackIndex,
                    rawTimeUs = extractor.sampleTime,
                    isVideo = isVideo,
                    stepUs = stepUs,
                )
                if (adjustedTime == null) {
                    extractor.advance()
                    continue
                }

                buffer.position(0)
                buffer.limit(sampleSize)
                val muxFlags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                bufferInfo.set(0, sampleSize, adjustedTime, muxFlags)

                muxer.writeSampleData(token, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.close()
            muxer = null
            fos.close()
            fos = null
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
            runCatching { muxer?.close() }
            runCatching { fos?.close() }
            runCatching { extractor.release() }
            if (dstMp4.exists()) dstMp4.delete()
            return null
        }
    }

    private fun buildMedia3Format(src: MediaFormat, mime: String): Format? {
        val builder = Format.Builder().setSampleMimeType(mime)
        return when {
            mime.startsWith("video/") -> {
                val w = if (src.containsKey(MediaFormat.KEY_WIDTH)) src.getInteger(MediaFormat.KEY_WIDTH) else return null
                val h = if (src.containsKey(MediaFormat.KEY_HEIGHT)) src.getInteger(MediaFormat.KEY_HEIGHT) else return null
                builder.setWidth(w).setHeight(h)
                if (src.containsKey(MediaFormat.KEY_ROTATION)) {
                    builder.setRotationDegrees(src.getInteger(MediaFormat.KEY_ROTATION))
                }
                val initData = extractVideoInitData(src, mime) ?: return null
                builder.setInitializationData(initData).build()
            }
            mime == MimeTypes.AUDIO_AAC -> {
                val sr = if (src.containsKey(MediaFormat.KEY_SAMPLE_RATE)) src.getInteger(MediaFormat.KEY_SAMPLE_RATE) else return null
                val ch = if (src.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) src.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else return null
                val csd0 = readCsdBytes(src, "csd-0") ?: return null
                builder
                    .setSampleRate(sr)
                    .setChannelCount(ch)
                    .setInitializationData(listOf(csd0))
                    .build()
            }
            else -> null
        }
    }

    private fun extractVideoInitData(src: MediaFormat, mime: String): List<ByteArray>? {
        val csd0 = readCsdBytes(src, "csd-0") ?: return null
        if (mime != MimeTypes.VIDEO_H264) {
            val csd1 = readCsdBytes(src, "csd-1")
            return if (csd1 != null) listOf(csd0, csd1) else listOf(csd0)
        }
        val csd1 = readCsdBytes(src, "csd-1")
        if (csd1 != null) {
            return listOf(ensureAnnexBPrefix(csd0), ensureAnnexBPrefix(csd1))
        }
        val (sps, pps) = splitAvcAnnexBCsd(csd0)
        if (sps == null || pps == null) return null
        return listOf(sps, pps)
    }

    private fun ensureAnnexBPrefix(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            ((data[2] == 0.toByte() && data[3] == 1.toByte()) || data[2] == 1.toByte())
        ) {
            return data
        }
        return byteArrayOf(0, 0, 0, 1) + data
    }

    private fun readCsdBytes(src: MediaFormat, key: String): ByteArray? {
        if (!src.containsKey(key)) return null
        val buf = src.getByteBuffer(key) ?: return null
        val dup = buf.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out.takeIf { it.isNotEmpty() }
    }

    internal fun splitAvcAnnexBCsd(data: ByteArray): Pair<ByteArray?, ByteArray?> {
        val nalOffsets = ArrayList<Int>()
        var i = 0
        while (i <= data.size - 3) {
            if (i <= data.size - 4 &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                nalOffsets.add(i)
                i += 4
                continue
            }
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                nalOffsets.add(i)
                i += 3
                continue
            }
            i++
        }
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (idx in nalOffsets.indices) {
            val start = nalOffsets[idx]
            val scLen = if (start <= data.size - 4 && data[start + 2] == 0.toByte() && data[start + 3] == 1.toByte()) 4 else 3
            val end = if (idx + 1 < nalOffsets.size) nalOffsets[idx + 1] else data.size
            val bodyStart = start + scLen
            if (bodyStart >= end) continue
            val nalType = data[bodyStart].toInt() and 0x1F
            // Trim trailing zero padding sebelum start code berikutnya (misal byte 0x00 tambahan)
            var trimEnd = end
            while (trimEnd > bodyStart + 1 && data[trimEnd - 1] == 0.toByte() && idx + 1 < nalOffsets.size) {
                trimEnd--
            }
            val nalu = byteArrayOf(0, 0, 0, 1) + data.copyOfRange(bodyStart, trimEnd)
            if (nalType == 7 && sps == null) sps = nalu
            if (nalType == 8 && pps == null) pps = nalu
        }
        return sps to pps
    }
}
