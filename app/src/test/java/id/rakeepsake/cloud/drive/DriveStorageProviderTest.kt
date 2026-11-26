package id.rakeepsake.cloud.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveStorageProviderTest {

    @Test
    fun parseRangeNextByte_handlesValidMissingAndClampedHeaders() {
        val total = 20_000_000L
        assertEquals(0L, DriveStorageProvider.parseRangeNextByte(null, total))
        assertEquals(0L, DriveStorageProvider.parseRangeNextByte("", total))
        assertEquals(8_388_608L, DriveStorageProvider.parseRangeNextByte("bytes=0-8388607", total))
        assertEquals(16_777_216L, DriveStorageProvider.parseRangeNextByte("Bytes=0-16777215", total))
        assertEquals(total, DriveStorageProvider.parseRangeNextByte("bytes=0-19999999", total))
        assertEquals(total, DriveStorageProvider.parseRangeNextByte("bytes=0-99999999", total))
    }

    @Test
    fun computeChunkRange_splitsIntoAlignedChunksAndFinalTail() {
        val total = 20_000_000L
        val chunk1 = DriveStorageProvider.computeChunkRange(0L, total)
        assertEquals(0L, chunk1.start)
        assertEquals(8_388_607L, chunk1.endInclusive)
        assertEquals(8_388_608L, chunk1.length)
        assertEquals("bytes 0-8388607/20000000", chunk1.toContentRangeHeader())

        val chunk2 = DriveStorageProvider.computeChunkRange(8_388_608L, total)
        assertEquals(8_388_608L, chunk2.start)
        assertEquals(16_777_215L, chunk2.endInclusive)
        assertEquals("bytes 8388608-16777215/20000000", chunk2.toContentRangeHeader())

        val chunk3 = DriveStorageProvider.computeChunkRange(16_777_216L, total)
        assertEquals(16_777_216L, chunk3.start)
        assertEquals(19_999_999L, chunk3.endInclusive)
        assertEquals(3_222_784L, chunk3.length)
        assertEquals("bytes 16777216-19999999/20000000", chunk3.toContentRangeHeader())
    }

    @Test
    fun mapDriveHttpError_mapsDriveReasonsToExpectedKinds() {
        val err401 = DriveStorageProvider.mapDriveHttpError(401, "{}")
        assertEquals(DriveErrorKind.UNAUTHORIZED, err401.kind)
        assertEquals("Izin Google Drive hilang. Hubungkan ulang.", err401.userMessage)

        val quotaJson = """{"error":{"code":403,"errors":[{"reason":"storageQuotaExceeded"}]}}"""
        val errQuota = DriveStorageProvider.mapDriveHttpError(403, quotaJson)
        assertEquals(DriveErrorKind.QUOTA_FULL, errQuota.kind)
        assertEquals("Penyimpanan Google Drive penuh. File lokal tidak dihapus.", errQuota.userMessage)

        val rateJson = """{"error":{"code":403,"errors":[{"reason":"userRateLimitExceeded"}]}}"""
        val errRate = DriveStorageProvider.mapDriveHttpError(403, rateJson)
        assertEquals(DriveErrorKind.RATE_LIMIT, errRate.kind)

        assertEquals(DriveErrorKind.RATE_LIMIT, DriveStorageProvider.mapDriveHttpError(429, "{}").kind)
        assertEquals(DriveErrorKind.SESSION_NOT_FOUND, DriveStorageProvider.mapDriveHttpError(404, "{}").kind)
        assertEquals(DriveErrorKind.SERVER_ERROR, DriveStorageProvider.mapDriveHttpError(503, "{}").kind)
    }

    @Test
    fun parseVerifyResponse_checksHttpStatusTrashedAndExactSize() {
        val okJson = """{"id":"file123","size":"20000000","trashed":false}"""
        assertTrue(DriveStorageProvider.parseVerifyResponse(200, okJson, 20_000_000L))
        assertFalse(DriveStorageProvider.parseVerifyResponse(200, okJson, 19_999_999L))
        assertFalse(DriveStorageProvider.parseVerifyResponse(404, okJson, 20_000_000L))

        val trashedJson = """{"id":"file123","size":"20000000","trashed":true}"""
        assertFalse(DriveStorageProvider.parseVerifyResponse(200, trashedJson, 20_000_000L))

        val missingSizeJson = """{"id":"file123","trashed":false}"""
        assertFalse(DriveStorageProvider.parseVerifyResponse(200, missingSizeJson, 20_000_000L))
    }
}
