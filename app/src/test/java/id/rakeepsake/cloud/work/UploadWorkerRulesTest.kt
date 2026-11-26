package id.rakeepsake.cloud.work

import id.rakeepsake.cloud.CloudSettings
import id.rakeepsake.cloud.data.CloudUpload
import id.rakeepsake.cloud.data.UploadState
import id.rakeepsake.cloud.data.UploadStateConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadWorkerRulesTest {

    @Test
    fun canDeleteLocalAfterUpload_requiresAllFiveConditions() {
        assertTrue(
            UploadWorker.canDeleteLocalAfterUpload(
                deleteAfterUploadSetting = true,
                currentState = UploadState.DONE,
                verifiedInSameAttempt = true,
                fileStillExists = true,
                currentSizeBytes = 1024L,
                registeredSizeBytes = 1024L,
                isCurrentlyInUse = false,
            ),
        )

        // 1. Opsi hapus mati
        assertFalse(
            UploadWorker.canDeleteLocalAfterUpload(
                deleteAfterUploadSetting = false,
                currentState = UploadState.DONE,
                verifiedInSameAttempt = true,
                fileStillExists = true,
                currentSizeBytes = 1024L,
                registeredSizeBytes = 1024L,
                isCurrentlyInUse = false,
            ),
        )
        // 2. State bukan DONE
        assertFalse(
            UploadWorker.canDeleteLocalAfterUpload(
                deleteAfterUploadSetting = true,
                currentState = UploadState.FAILED,
                verifiedInSameAttempt = true,
                fileStillExists = true,
                currentSizeBytes = 1024L,
                registeredSizeBytes = 1024L,
                isCurrentlyInUse = false,
            ),
        )
        // 3. Verifikasi bukan dari percobaan yang sama
        assertFalse(
            UploadWorker.canDeleteLocalAfterUpload(
                deleteAfterUploadSetting = true,
                currentState = UploadState.DONE,
                verifiedInSameAttempt = false,
                fileStillExists = true,
                currentSizeBytes = 1024L,
                registeredSizeBytes = 1024L,
                isCurrentlyInUse = false,
            ),
        )
        // 4. Ukuran file berubah sejak didaftarkan
        assertFalse(
            UploadWorker.canDeleteLocalAfterUpload(
                deleteAfterUploadSetting = true,
                currentState = UploadState.DONE,
                verifiedInSameAttempt = true,
                fileStillExists = true,
                currentSizeBytes = 2048L,
                registeredSizeBytes = 1024L,
                isCurrentlyInUse = false,
            ),
        )
        // 5. File sedang diputar / dipakai bagian lain
        assertFalse(
            UploadWorker.canDeleteLocalAfterUpload(
                deleteAfterUploadSetting = true,
                currentState = UploadState.DONE,
                verifiedInSameAttempt = true,
                fileStillExists = true,
                currentSizeBytes = 1024L,
                registeredSizeBytes = 1024L,
                isCurrentlyInUse = true,
            ),
        )
    }

    @Test
    fun isFileWriteStable_checksPendingSizeAndTimestamp() {
        assertTrue(UploadWorker.isFileWriteStable(5000L, 100L, 5000L, 100L, 0))
        assertFalse(UploadWorker.isFileWriteStable(5000L, 100L, 6000L, 100L, 0))
        assertFalse(UploadWorker.isFileWriteStable(5000L, 100L, 5000L, 101L, 0))
        assertFalse(UploadWorker.isFileWriteStable(5000L, 100L, 5000L, 100L, 1))
        assertFalse(UploadWorker.isFileWriteStable(0L, 100L, 0L, 100L, 0))
    }

    @Test
    fun cloudSettingsSnapshot_matchesSpecDefaults() {
        val def = CloudSettings.Snapshot()
        assertFalse(def.cloudEnabled)
        assertFalse(def.autoUpload)
        assertTrue(def.wifiOnly)
        assertFalse(def.chargingOnly)
        assertFalse(def.deleteAfterUpload)
        assertNull(def.driveFolderId)
        assertNull(def.connectedAccountEmail)
    }

    @Test
    fun schedulerUniqueName_isDeterministicPrefixAndConvertersSafe() {
        val n1 = CloudUploadScheduler.uniqueWorkName("content://media/external/video/media/42")
        val n2 = CloudUploadScheduler.uniqueWorkName("content://media/external/video/media/42")
        assertEquals(n1, n2)
        assertTrue(n1.startsWith("cloud_upload_"))

        val conv = UploadStateConverters()
        assertEquals(UploadState.QUOTA_FULL, conv.toUploadState("QUOTA_FULL"))
        assertEquals(UploadState.PENDING, conv.toUploadState("UNKNOWN_FUTURE_STATE"))

        val rows = listOf(
            CloudUpload("content://a", "a.mp4", "video/mp4", 10L, UploadState.QUEUED, updatedAt = 1L),
            CloudUpload("content://b", "b.mp4", "video/mp4", 20L, UploadState.UPLOADING, updatedAt = 2L),
        )
        assertEquals(1 to 2, UploadWorker.computeBatchPosition(rows, "content://a"))
        assertEquals(2 to 2, UploadWorker.computeBatchPosition(rows, "content://b"))
    }
}
