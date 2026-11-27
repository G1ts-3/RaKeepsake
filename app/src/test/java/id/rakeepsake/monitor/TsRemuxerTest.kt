package id.rakeepsake.monitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TsRemuxerTest {

    @Test
    fun splitAvcAnnexBCsd_splitsFourByteSpsAndPps() {
        val combined = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x4D, 0x40, 0x28, 0x95.toByte(),
            0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C, 0x80.toByte(),
        )

        val (sps, pps) = TsRemuxer.splitAvcAnnexBCsd(combined)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x4D, 0x40, 0x28, 0x95.toByte()),
            sps,
        )
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C, 0x80.toByte()),
            pps,
        )
    }

    @Test
    fun splitAvcAnnexBCsd_normalizesThreeByteStartCodesAndZeroPadding() {
        // Menguji format TS Android saat ada byte padding 0x00 tambahan sebelum start code PPS
        val combined = byteArrayOf(
            0, 0, 1, 0x27, 0x64, 0x00, 0x1F, 0x00,
            0, 0, 0, 1, 0x28, 0xEB.toByte(), 0xEF.toByte(), 0x20,
        )

        val (sps, pps) = TsRemuxer.splitAvcAnnexBCsd(combined)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x27, 0x64, 0x00, 0x1F),
            sps,
        )
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x28, 0xEB.toByte(), 0xEF.toByte(), 0x20),
            pps,
        )
    }

    @Test
    fun splitAvcAnnexBCsd_handlesMissingPpsOrEmptyInput() {
        val onlySps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E)
        val (spsOnly, ppsMissing) = TsRemuxer.splitAvcAnnexBCsd(onlySps)
        assertArrayEquals(onlySps, spsOnly)
        assertNull(ppsMissing)

        val (emptySps, emptyPps) = TsRemuxer.splitAvcAnnexBCsd(byteArrayOf())
        assertNull(emptySps)
        assertNull(emptyPps)
    }

    @Test
    fun normalizer_preservesTrueBFrameOrderInDecodeSequence() {
        val normalizer = RemuxTimelineNormalizer()
        // Urutan decode nyata live TikTok: I(142.42), P(142.62), B(142.50), B(142.46), B(142.58), P(142.82), B(142.70), B(142.66)
        val rawPts = longArrayOf(
            142_420_000L,
            142_620_000L,
            142_500_000L,
            142_460_000L,
            142_580_000L,
            142_820_000L,
            142_700_000L,
            142_660_000L,
        )

        val outPts = rawPts.map { normalizer.nextPtsUs(0, it, isVideo = true, stepUs = 40_000L)!! }

        // Tidak boleh menimpa B-frame menjadi prev + 1us; selisih display PTS harus akurat 40ms/80ms/200ms
        assertEquals(
            listOf(0L, 200_000L, 80_000L, 40_000L, 160_000L, 400_000L, 280_000L, 240_000L),
            outPts,
        )
    }

    @Test
    fun normalizer_synthesizesMissingPesPtsWithoutCollidingWithBFrames() {
        val normalizer = RemuxTimelineNormalizer()
        val f0 = normalizer.nextPtsUs(0, 142_420_000L, isVideo = true, stepUs = 40_000L)
        val f1Missing = normalizer.nextPtsUs(0, -1L, isVideo = true, stepUs = 40_000L)
        // Meski frame B berikutnya kebetulan jatuh pada 142.460s, PTS unik tetap terjaga (tanpa stts 0-durasi)
        val f2 = normalizer.nextPtsUs(0, 142_460_000L, isVideo = true, stepUs = 40_000L)

        assertEquals(0L, f0)
        assertEquals(40_000L, f1Missing)
        assertEquals(41_000L, f2)
    }

    @Test
    fun normalizer_collapsesHlsGapWhileKeepingAudioVideoSynced() {
        val normalizer = RemuxTimelineNormalizer()
        // Segmen 1 dimulai di 100.000s (audio) dan 100.120s (video)
        assertEquals(0L, normalizer.nextPtsUs(1, 100_000_000L, isVideo = false, stepUs = 42_667L))
        assertEquals(120_000L, normalizer.nextPtsUs(0, 100_120_000L, isVideo = true, stepUs = 40_000L))
        assertEquals(1_000_000L, normalizer.nextPtsUs(1, 101_000_000L, isVideo = false, stepUs = 42_667L))
        assertEquals(1_120_000L, normalizer.nextPtsUs(0, 101_120_000L, isVideo = true, stepUs = 40_000L))

        // Jeda 6 detik akibat segmen hilang pada HLS (kedua trek melompat ke ~107s)
        val audioAfterGap = normalizer.nextPtsUs(1, 107_000_000L, isVideo = false, stepUs = 42_667L)!!
        val videoAfterGap = normalizer.nextPtsUs(0, 107_120_000L, isVideo = true, stepUs = 40_000L)!!

        // Jeda dirapatkan ke ~1 detik, dan A/V menjaga jarak asli +120ms tanpa drift
        assertTrue(audioAfterGap in 1_040_000L..1_100_000L)
        assertEquals(120_000L, videoAfterGap - audioAfterGap)
    }

    @Test
    fun normalizer_handlesFlvReconnectTimestampReset() {
        val normalizer = RemuxTimelineNormalizer()
        // Aliran normal 5 detik (step <= 2s agar tidak dianggap gap hilang)
        for (sec in 0..5) {
            assertEquals(sec * 1_000_000L, normalizer.nextPtsUs(0, sec * 1_000_000L, isVideo = true, stepUs = 40_000L))
        }

        // Koneksi ulang FLV membuat rawTs reset kembali ke 0
        val afterReconnect = normalizer.nextPtsUs(0, 0L, isVideo = true, stepUs = 40_000L)!!
        assertEquals(5_040_000L, afterReconnect)
        assertEquals(5_080_000L, normalizer.nextPtsUs(0, 40_000L, isVideo = true, stepUs = 40_000L))
    }
}
