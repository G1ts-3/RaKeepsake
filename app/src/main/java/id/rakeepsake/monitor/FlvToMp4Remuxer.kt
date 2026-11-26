package id.rakeepsake.monitor

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Pengubah format (transmux/remux) berkas FLV (Flash Video) siaran langsung TikTok
 * menjadi berkas MP4 standar tanpa re-encode.
 *
 * Menggunakan MediaMuxer bawaan Android (API 26+) dengan mengekstrak NAL unit H.264
 * dan frame audio AAC dari tag-tag FLV, serta menormalkan PTS agar dimulai dari 00:00.
 */
object FlvToMp4Remuxer {
    private const val TAG = "FlvToMp4Remuxer"

    data class Headers(
        val sps: ByteArray,
        val pps: ByteArray,
        val width: Int,
        val height: Int,
        val asc: ByteArray?,
        val sampleRate: Int,
        val channels: Int,
    )

    /**
     * Remux berkas FLV [srcFlv] menjadi MP4 di [dstMp4].
     * @return File MP4 yang berhasil dibuat, atau null bila gagal (agar sistem bisa fallback ke .flv).
     */
    fun remuxToMp4(srcFlv: File, dstMp4: File): File? {
        if (!srcFlv.exists() || srcFlv.length() < 100) {
            Log.w(TAG, "Berkas sumber tidak ada atau terlalu kecil: ${srcFlv.path}")
            return null
        }

        dstMp4.parentFile?.mkdirs()
        if (dstMp4.exists()) dstMp4.delete()

        var muxer: MediaMuxer? = null
        return try {
            val headers = scanHeaders(srcFlv) ?: run {
                Log.w(TAG, "Header video/audio tidak ditemukan pada ${srcFlv.name}")
                return null
            }

            muxer = MediaMuxer(dstMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 1. Setup trek video (H.264 / AVC)
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                headers.width,
                headers.height,
            )
            val spsHdr = byteArrayOf(0, 0, 0, 1) + headers.sps
            val ppsHdr = byteArrayOf(0, 0, 0, 1) + headers.pps
            videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(spsHdr))
            videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(ppsHdr))
            val videoTrack = muxer.addTrack(videoFormat)

            // 2. Setup trek audio (AAC) jika tersedia
            var audioTrack = -1
            if (headers.asc != null) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    headers.sampleRate,
                    headers.channels,
                )
                audioFormat.setByteBuffer("csd-0", ByteBuffer.wrap(headers.asc))
                audioTrack = muxer.addTrack(audioFormat)
            }

            muxer.start()

            // 3. Streaming demuxing & remuxing seluruh tag FLV
            var sampleBuffer = ByteBuffer.allocateDirect(4 * 1024 * 1024) // 4 MB buffer
            val bufferInfo = MediaCodec.BufferInfo()

            var firstPtsUs: Long? = null
            var lastVideoPtsUs = 0L
            var lastAudioPtsUs = 0L
            var hasSeenKeyframe = false

            FileInputStream(srcFlv).buffered(256 * 1024).use { bis ->
                val skipHdr = ByteArray(13)
                if (readFully(bis, skipHdr) != 13) return null

                val tagHdr = ByteArray(11)
                val prevTagSize = ByteArray(4)

                while (true) {
                    if (readFully(bis, tagHdr) != 11) break
                    val tagType = tagHdr[0].toInt() and 0xFF
                    val dataSize = ((tagHdr[1].toInt() and 0xFF) shl 16) or
                            ((tagHdr[2].toInt() and 0xFF) shl 8) or
                            (tagHdr[3].toInt() and 0xFF)
                    val rawTs = ((tagHdr[7].toInt() and 0xFF) shl 24) or
                            ((tagHdr[4].toInt() and 0xFF) shl 16) or
                            ((tagHdr[5].toInt() and 0xFF) shl 8) or
                            (tagHdr[6].toInt() and 0xFF)

                    val data = ByteArray(dataSize)
                    if (readFully(bis, data) != dataSize) break
                    if (readFully(bis, prevTagSize) != 4) break

                    if (tagType == 9 && data.size >= 5) { // Video
                        val frameType = (data[0].toInt() shr 4) and 0x0F
                        val codecId = data[0].toInt() and 0x0F
                        val packetType = data[1].toInt() and 0xFF

                        if (codecId == 7 && packetType == 1) { // AVC NALU
                            val isKeyframe = (frameType == 1)
                            if (isKeyframe) hasSeenKeyframe = true
                            if (!hasSeenKeyframe) continue // Lewati P-frame sebelum IDR pertama

                            val b2 = data[2].toInt() and 0xFF
                            val b3 = data[3].toInt() and 0xFF
                            val b4 = data[4].toInt() and 0xFF
                            var cts = (b2 shl 16) or (b3 shl 8) or b4
                            if ((cts and 0x800000) != 0) cts -= 0x1000000

                            val calcPtsMs = rawTs + cts
                            if (firstPtsUs == null) {
                                firstPtsUs = calcPtsMs * 1000L
                            }

                            val base = firstPtsUs ?: 0L
                            var ptsUs = (calcPtsMs * 1000L) - base
                            if (ptsUs < 0) ptsUs = 0
                            if (ptsUs <= lastVideoPtsUs) {
                                ptsUs = lastVideoPtsUs + 1000L
                            }
                            lastVideoPtsUs = ptsUs

                            // Konversi AVCC NALUs (4-byte length) menjadi Annex-B (00 00 00 01)
                            if (sampleBuffer.capacity() < data.size + 128) {
                                sampleBuffer = ByteBuffer.allocateDirect(data.size * 2)
                            }
                            sampleBuffer.clear()
                            var pos = 5
                            while (pos + 4 < data.size) {
                                val nalLen = ((data[pos].toInt() and 0xFF) shl 24) or
                                        ((data[pos + 1].toInt() and 0xFF) shl 16) or
                                        ((data[pos + 2].toInt() and 0xFF) shl 8) or
                                        (data[pos + 3].toInt() and 0xFF)
                                pos += 4
                                if (nalLen <= 0 || pos + nalLen > data.size) break

                                sampleBuffer.put(0.toByte())
                                sampleBuffer.put(0.toByte())
                                sampleBuffer.put(0.toByte())
                                sampleBuffer.put(1.toByte())
                                sampleBuffer.put(data, pos, nalLen)
                                pos += nalLen
                            }

                            val totalLen = sampleBuffer.position()
                            if (totalLen > 0) {
                                sampleBuffer.flip()
                                bufferInfo.offset = 0
                                bufferInfo.size = totalLen
                                bufferInfo.presentationTimeUs = ptsUs
                                bufferInfo.flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                                muxer.writeSampleData(videoTrack, sampleBuffer, bufferInfo)
                            }
                        }
                    } else if (tagType == 8 && audioTrack != -1 && data.size >= 2) { // Audio
                        val soundFormat = (data[0].toInt() shr 4) and 0x0F
                        val packetType = data[1].toInt() and 0xFF

                        if (soundFormat == 10 && packetType == 1) { // Raw AAC frame
                            val aacLen = data.size - 2
                            if (aacLen > 0 && hasSeenKeyframe) {
                                val calcPtsMs = rawTs.toLong()
                                if (firstPtsUs == null) {
                                    firstPtsUs = calcPtsMs * 1000L
                                }

                                val base = firstPtsUs ?: 0L
                                var ptsUs = (calcPtsMs * 1000L) - base
                                if (ptsUs < 0) ptsUs = 0
                                if (ptsUs <= lastAudioPtsUs) {
                                    ptsUs = lastAudioPtsUs + 1000L
                                }
                                lastAudioPtsUs = ptsUs

                            if (sampleBuffer.capacity() < aacLen + 64) {
                                sampleBuffer = ByteBuffer.allocateDirect(aacLen * 2)
                            }
                            sampleBuffer.clear()
                            sampleBuffer.put(data, 2, aacLen)
                            sampleBuffer.flip()

                            bufferInfo.offset = 0
                            bufferInfo.size = aacLen
                            bufferInfo.presentationTimeUs = ptsUs
                            bufferInfo.flags = 0
                            muxer.writeSampleData(audioTrack, sampleBuffer, bufferInfo)
                        }
                    }
                }
            }
        }

            muxer.stop()
            muxer.release()
            muxer = null

            if (dstMp4.exists() && dstMp4.length() > 0L) {
                Log.i(TAG, "Sukses remux FLV ke MP4: ${dstMp4.name} (${dstMp4.length()} bytes)")
                dstMp4
            } else {
                dstMp4.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal remux FLV ke MP4: ${e.message}", e)
            try { muxer?.release() } catch (_: Exception) {}
            if (dstMp4.exists()) dstMp4.delete()
            null
        }
    }

    private fun scanHeaders(srcFlv: File): Headers? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var asc: ByteArray? = null
        var sampleRate = 48000
        var channels = 2

        FileInputStream(srcFlv).buffered(64 * 1024).use { bis ->
            val skipHdr = ByteArray(13)
            if (readFully(bis, skipHdr) != 13) return null

            val tagHdr = ByteArray(11)
            val prevTagSize = ByteArray(4)
            var count = 0

            while (count < 2000) {
                count++
                if (readFully(bis, tagHdr) != 11) break
                val tagType = tagHdr[0].toInt() and 0xFF
                val dataSize = ((tagHdr[1].toInt() and 0xFF) shl 16) or
                        ((tagHdr[2].toInt() and 0xFF) shl 8) or
                        (tagHdr[3].toInt() and 0xFF)

                val data = ByteArray(dataSize)
                if (readFully(bis, data) != dataSize) break
                if (readFully(bis, prevTagSize) != 4) break

                if (tagType == 9 && data.size >= 13) {
                    val codecId = data[0].toInt() and 0x0F
                    val packetType = data[1].toInt() and 0xFF
                    if (codecId == 7 && packetType == 0) { // AVC sequence header
                        val avcc = data.copyOfRange(5, data.size)
                        if (avcc.size >= 8) {
                            val spsLen = ((avcc[6].toInt() and 0xFF) shl 8) or (avcc[7].toInt() and 0xFF)
                            if (avcc.size >= 8 + spsLen + 3) {
                                sps = avcc.copyOfRange(8, 8 + spsLen)
                                val ppsOffset = 8 + spsLen
                                val ppsLen = ((avcc[ppsOffset + 1].toInt() and 0xFF) shl 8) or (avcc[ppsOffset + 2].toInt() and 0xFF)
                                if (avcc.size >= ppsOffset + 3 + ppsLen) {
                                    pps = avcc.copyOfRange(ppsOffset + 3, ppsOffset + 3 + ppsLen)
                                }
                            }
                        }
                    }
                } else if (tagType == 8 && data.size >= 4) {
                    val soundFormat = (data[0].toInt() shr 4) and 0x0F
                    val packetType = data[1].toInt() and 0xFF
                    if (soundFormat == 10 && packetType == 0) { // AAC sequence header
                        asc = data.copyOfRange(2, data.size)
                        val parsed = parseAsc(asc)
                        sampleRate = parsed.first
                        channels = parsed.second
                    }
                }

                if (sps != null && pps != null && asc != null) break
            }
        }

        if (sps == null || pps == null) return null
        val dim = runCatching { H264SpsParser.parse(sps) }.getOrDefault(H264SpsParser.Dimensions(720, 1280))
        return Headers(sps, pps, dim.width, dim.height, asc, sampleRate, channels)
    }

    private fun parseAsc(asc: ByteArray): Pair<Int, Int> {
        if (asc.size < 2) return Pair(44100, 2)
        val b0 = asc[0].toInt() and 0xFF
        val b1 = asc[1].toInt() and 0xFF
        val freqIdx = ((b0 and 0x07) shl 1) or (b1 shr 7)
        val channel = (b1 shr 3) and 0x0F
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        var rate = if (freqIdx < rates.size) rates[freqIdx] else 44100
        if (asc.size >= 5 && rate <= 24000) {
            rate *= 2
        }
        return Pair(rate, if (channel > 0) channel else 2)
    }

    private fun readFully(stream: InputStream, b: ByteArray): Int {
        var read = 0
        while (read < b.size) {
            val r = stream.read(b, read, b.size - read)
            if (r == -1) break
            read += r
        }
        return read
    }

    object H264SpsParser {
        data class Dimensions(val width: Int, val height: Int)

        fun parse(sps: ByteArray): Dimensions {
            val br = BitReader(sps)
            val profile = br.readBits(8)
            br.readBits(8) // compat
            br.readBits(8) // level
            br.readUe()    // sps_id
            if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chroma = br.readUe()
                if (chroma == 3) br.readBit()
                br.readUe() // bit_depth_luma_minus8
                br.readUe() // bit_depth_chroma_minus8
                br.readBit() // qpprime_y_zero_transform_bypass_flag
                if (br.readBit() == 1) { // seq_scaling_matrix_present_flag
                    val count = if (chroma != 3) 8 else 12
                    for (i in 0 until count) {
                        if (br.readBit() == 1) {
                            skipScalingList(br, if (i < 6) 16 else 64)
                        }
                    }
                }
            }
            br.readUe() // log2_max_frame_num_minus4
            val pocType = br.readUe()
            if (pocType == 0) {
                br.readUe() // log2_max_pic_order_cnt_lsb_minus4
            } else if (pocType == 1) {
                br.readBit()
                br.readSe()
                br.readSe()
                val numCycles = br.readUe()
                for (i in 0 until numCycles) br.readSe()
            }
            br.readUe() // max_num_ref_frames
            br.readBit() // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbsMinus1 = br.readUe()
            val picHeightInMapUnitsMinus1 = br.readUe()
            val frameMbsOnlyFlag = br.readBit()
            if (frameMbsOnlyFlag == 0) br.readBit()
            br.readBit()
            val frameCroppingFlag = br.readBit()
            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (frameCroppingFlag == 1) {
                cropLeft = br.readUe()
                cropRight = br.readUe()
                cropTop = br.readUe()
                cropBottom = br.readUe()
            }
            val width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * 2
            val height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16 - (cropTop + cropBottom) * 2
            return Dimensions(if (width > 0) width else 720, if (height > 0) height else 1280)
        }

        private fun skipScalingList(br: BitReader, size: Int) {
            var lastScale = 8
            var nextScale = 8
            for (j in 0 until size) {
                if (nextScale != 0) {
                    val deltaScale = br.readSe()
                    nextScale = (lastScale + deltaScale + 256) % 256
                }
                lastScale = if (nextScale == 0) lastScale else nextScale
            }
        }

        class BitReader(private val data: ByteArray) {
            private var byteIdx = 0
            private var bitIdx = 0

            fun readBit(): Int {
                if (byteIdx >= data.size) return 0
                val b = (data[byteIdx].toInt() shr (7 - bitIdx)) and 1
                bitIdx++
                if (bitIdx == 8) {
                    bitIdx = 0
                    byteIdx++
                }
                return b
            }

            fun readBits(n: Int): Int {
                var v = 0
                for (i in 0 until n) v = (v shl 1) or readBit()
                return v
            }

            fun readUe(): Int {
                var zeros = 0
                while (readBit() == 0 && zeros < 32) zeros++
                return (1 shl zeros) - 1 + readBits(zeros)
            }

            fun readSe(): Int {
                val v = readUe()
                val sign = if ((v and 1) == 0) -1 else 1
                return sign * ((v + 1) / 2)
            }
        }
    }
}
