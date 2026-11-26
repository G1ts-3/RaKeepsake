package id.rakeepsake.monitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
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
}
