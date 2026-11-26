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
                val rawFormat = extractor.getTrackFormat(i)
                val mime = rawFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxFormat = prepareMuxFormat(rawFormat, mime)
                    extractor.selectTrack(i)
                    val muxerTrack = muxer.addTrack(muxFormat)
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

            // Pelacak timestamp per trek: bedakan B-frame reorder vs discontinuity/jeda segmen HLS
            val firstRawPts = HashMap<Int, Long>()
            val maxRawPts = HashMap<Int, Long>()
            val segmentOffsetUs = HashMap<Int, Long>()
            val lastMuxPts = HashMap<Int, Long>()
            val trackStepUs = HashMap<Int, Long>()
            for (i in 0 until trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                trackStepUs[i] = if (mime.startsWith("audio/")) 42_667L else 40_000L
            }
            var globalBaseRawPts: Long? = null

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
                if (sampleSize == 0) {
                    extractor.advance()
                    continue
                }

                val rawTimeUs = extractor.sampleTime
                if (rawTimeUs < 0L) {
                    extractor.advance()
                    continue
                }

                if (globalBaseRawPts == null) {
                    globalBaseRawPts = rawTimeUs
                }
                if (!firstRawPts.containsKey(trackIndex)) {
                    firstRawPts[trackIndex] = rawTimeUs
                    maxRawPts[trackIndex] = rawTimeUs
                    segmentOffsetUs[trackIndex] = (rawTimeUs - (globalBaseRawPts ?: rawTimeUs)).coerceAtLeast(0L)
                    lastMuxPts[trackIndex] = -1L
                }

                val maxSeenRaw = maxRawPts[trackIndex] ?: rawTimeUs
                val baseRaw = firstRawPts[trackIndex] ?: rawTimeUs
                var offset = segmentOffsetUs[trackIndex] ?: 0L
                val stepUs = trackStepUs[trackIndex] ?: 40_000L

                // Deteksi reset / jeda segmen HLS (> 0.5 detik) agar audio (CBR MP4) & video tidak desinkron
                val jumpBackUs = maxSeenRaw - rawTimeUs
                val jumpForwardUs = rawTimeUs - maxSeenRaw
                if (jumpBackUs > 1_500_000L || jumpForwardUs > 500_000L) {
                    val prevDuration = (maxSeenRaw - baseRaw).coerceAtLeast(0L)
                    offset += prevDuration + stepUs
                    segmentOffsetUs[trackIndex] = offset
                    firstRawPts[trackIndex] = rawTimeUs
                    maxRawPts[trackIndex] = rawTimeUs
                } else if (rawTimeUs > maxSeenRaw) {
                    maxRawPts[trackIndex] = rawTimeUs
                }

                val currentBase = firstRawPts[trackIndex] ?: rawTimeUs
                var adjustedTime = (rawTimeUs - currentBase) + offset
                val prevMux = lastMuxPts[trackIndex] ?: -1L
                // Pastikan monotonically non-decreasing untuk kompatibilitas MediaMuxer lintas perangkat
                if (adjustedTime <= prevMux) {
                    adjustedTime = prevMux + 1L
                }
                lastMuxPts[trackIndex] = adjustedTime

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

    /**
     * Memecah gabungan `csd-0` Annex-B (SPS+PPS) milik MPEG-TS extractor menjadi `csd-0` (SPS) dan
     * `csd-1` (PPS) terpisah untuk H.264/AVC agar `MediaMuxer` tidak gagal `Missing codec specific data`.
     */
    private fun prepareMuxFormat(src: MediaFormat, mime: String): MediaFormat {
        if (mime != MediaFormat.MIMETYPE_VIDEO_AVC) return src
        if (src.containsKey("csd-1")) return src
        val csd0Buf = src.getByteBuffer("csd-0") ?: return src
        val raw = ByteArray(csd0Buf.remaining())
        csd0Buf.duplicate().get(raw)
        val (sps, pps) = splitAvcAnnexBCsd(raw)
        if (sps == null || pps == null) return src

        val w = if (src.containsKey(MediaFormat.KEY_WIDTH)) src.getInteger(MediaFormat.KEY_WIDTH) else return src
        val h = if (src.containsKey(MediaFormat.KEY_HEIGHT)) src.getInteger(MediaFormat.KEY_HEIGHT) else return src
        return MediaFormat.createVideoFormat(mime, w, h).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            if (src.containsKey(MediaFormat.KEY_ROTATION)) {
                setInteger(MediaFormat.KEY_ROTATION, src.getInteger(MediaFormat.KEY_ROTATION))
            }
            if (src.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, src.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
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
