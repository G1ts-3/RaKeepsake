package id.rakeepsake.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    private fun fakeInfo(versionName: String, versionCode: Int? = null) = AppUpdater.ReleaseInfo(
        versionName = versionName,
        versionCode = versionCode,
        tagName = "v$versionName",
        changelog = "",
        apkUrl = "https://example.com/app.apk",
        apkFileName = "app.apk",
        sizeBytes = 1234L,
        htmlUrl = "https://github.com/G1ts-3/RaKeepsake/releases",
    )

    @Test
    fun parseSource_handlesGitUrlAndSlugAndJson() {
        assertEquals(
            AppUpdater.SourceSpec.GitHub("G1ts-3/RaKeepsake"),
            AppUpdater.parseSource("https://github.com/G1ts-3/RaKeepsake.git"),
        )
        assertEquals(
            AppUpdater.SourceSpec.GitHub("G1ts-3/RaKeepsake"),
            AppUpdater.parseSource("https://github.com/G1ts-3/RaKeepsake/releases/latest"),
        )
        assertEquals(
            AppUpdater.SourceSpec.GitHub("G1ts-3/RaKeepsake"),
            AppUpdater.parseSource("G1ts-3/RaKeepsake"),
        )
        assertEquals(
            AppUpdater.SourceSpec.JsonManifest("https://example.com/version.json"),
            AppUpdater.parseSource("https://example.com/version.json"),
        )
        assertNull(AppUpdater.parseSource("   "))
    }

    @Test
    fun parseTagVersionAndCode_extractsBuildNumber() {
        assertEquals("0.2.1" to null, AppUpdater.parseTagVersionAndCode("v0.2.1"))
        assertEquals("0.2.1" to 3, AppUpdater.parseTagVersionAndCode("v0.2.1+3"))
        assertEquals("1.0.0" to 10, AppUpdater.parseTagVersionAndCode("V1.0.0+10"))
    }

    @Test
    fun isRemoteNewer_comparesSemverAndCodeCorrectly() {
        assertTrue(AppUpdater.isRemoteNewer(fakeInfo("0.2.1"), 2, "0.2.0"))
        assertTrue(AppUpdater.isRemoteNewer(fakeInfo("0.10.0"), 2, "0.2.0"))
        assertTrue(AppUpdater.isRemoteNewer(fakeInfo("0.2.0", 3), 2, "0.2.0"))
        assertFalse(AppUpdater.isRemoteNewer(fakeInfo("0.2.0"), 2, "0.2.0"))
        assertFalse(AppUpdater.isRemoteNewer(fakeInfo("0.2.0", 2), 2, "0.2.0"))
        assertFalse(AppUpdater.isRemoteNewer(fakeInfo("0.1.9"), 2, "0.2.0"))
        assertFalse(AppUpdater.isRemoteNewer(fakeInfo("0.2.0-beta.1"), 2, "0.2.0"))
        assertTrue(AppUpdater.isRemoteNewer(fakeInfo("0.2.1-beta.1"), 2, "0.2.0"))
    }
}
