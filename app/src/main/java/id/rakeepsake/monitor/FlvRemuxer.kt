package id.rakeepsake.monitor

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Normalizer berkas FLV (Flash Video).
 * Menghitung selisih timestamp siaran langsung (misal siaran sudah berjalan 27 menit)
 * dan menormalkan timestamp setiap tag FLV agar mulai dari 00:00 tanpa menyentuh kode C/native,
 * sehingga 100% aman, stabil, tidak pernah menyebabkan crash di libstagefright / MediaMuxer,
 * dan langsung bisa diputar serta di-scrub di ExoPlayer.
 */
object FlvRemuxer {
    private const val TAG = "FlvRemuxer"

    /**
     * Sesuaikan timestamp seluruh tag FLV di [srcFlv] ke [dstFlv] agar mulai dari 00:00.
     * Mengembalikan [dstFlv] jika berhasil, atau null bila gagal.
     */
    fun normalize(srcFlv: File, dstFlv: File): File? {
        if (!srcFlv.exists() || srcFlv.length() < 100) return null
        var fis: FileInputStream? = null
        var fos: FileOutputStream? = null
        return try {
            dstFlv.parentFile?.mkdirs()
            if (dstFlv.exists()) dstFlv.delete()

            fis = FileInputStream(srcFlv)
            val bis = BufferedInputStream(fis, 128 * 1024)

            fos = FileOutputStream(dstFlv)
            val bos = BufferedOutputStream(fos, 128 * 1024)

            val hdr = ByteArray(13) // 9 byte header + 4 byte PrevTagSize0
            if (readFully(bis, hdr) != 13) return null
            if (hdr[0] != 'F'.code.toByte() || hdr[1] != 'L'.code.toByte() || hdr[2] != 'V'.code.toByte()) {
                return null
            }
            bos.write(hdr)

            var firstPts: Long? = null
            var lastTs = 0

            while (true) {
                val tagHdr = ByteArray(11)
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
                val prevTagSize = ByteArray(4)
                if (readFully(bis, prevTagSize) != 4) break

                val isMediaFrame = (tagType == 9 && data.size >= 2 && data[1].toInt() == 1) ||
                        (tagType == 8 && data.size >= 2 && data[1].toInt() == 1)

                if (isMediaFrame && firstPts == null && rawTs > 0) {
                    firstPts = rawTs.toLong()
                }

                val base = firstPts ?: 0L
                var newTs = if (isMediaFrame && rawTs >= base) (rawTs - base).toInt() else 0
                if (newTs < lastTs && isMediaFrame) {
                    newTs = lastTs
                }
                if (isMediaFrame) {
                    lastTs = newTs
                }

                tagHdr[4] = ((newTs shr 16) and 0xFF).toByte()
                tagHdr[5] = ((newTs shr 8) and 0xFF).toByte()
                tagHdr[6] = (newTs and 0xFF).toByte()
                tagHdr[7] = ((newTs shr 24) and 0xFF).toByte()

                bos.write(tagHdr)
                bos.write(data)
                bos.write(prevTagSize)
            }

            bos.flush()
            if (dstFlv.exists() && dstFlv.length() > 0L) {
                Log.i(TAG, "Sukses normalisasi timestamp FLV: ${dstFlv.name} (${dstFlv.length()} bytes)")
                dstFlv
            } else {
                dstFlv.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal normalisasi timestamp FLV: ${e.message}", e)
            if (dstFlv.exists()) dstFlv.delete()
            null
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try { fos?.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
