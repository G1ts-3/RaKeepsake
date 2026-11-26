package id.rakeepsake.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformApiTest {

    @Test
    fun parseTikTokUserDetailFromHtml_extractsNicknameAvatarAndIds() {
        val html = """
            <!doctype html><html><head></head><body>
            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {
              "__DEFAULT_SCOPE__": {
                "webapp.user-detail": {
                  "statusCode": 0,
                  "userInfo": {
                    "user": {
                      "id": "6824893042371331073",
                      "secUid": "MS4wLjABAAAA_test_secuid",
                      "uniqueId": "freyajkt48",
                      "nickname": "Freya JKT48",
                      "avatarLarger": "https://p16-common-sign.tiktokcdn.com/avt~1080.jpeg?x-expires=1895867200",
                      "avatarMedium": "https://p16-common-sign.tiktokcdn.com/avt~720.jpeg?x-expires=1895867200",
                      "avatarThumb": "https://p16-common-sign.tiktokcdn.com/avt~100.jpeg?x-expires=1895867200"
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val parsed = PlatformApi.parseTikTokUserDetailFromHtml(html, "FreyaJKT48")
        assertEquals(0, parsed.statusCode)
        val prof = parsed.profile
        assertNotNull(prof)
        assertEquals("freyajkt48", prof!!.uniqueId)
        assertEquals("Freya JKT48", prof.nickname)
        assertEquals("6824893042371331073", prof.userId)
        assertEquals("MS4wLjABAAAA_test_secuid", prof.secUid)
        assertEquals("https://p16-common-sign.tiktokcdn.com/avt~1080.jpeg?x-expires=1895867200", prof.avatarUrl)

        // Handle berbeda harus diabaikan agar data profil tidak tertukar
        val mismatch = PlatformApi.parseTikTokUserDetailFromHtml(html, "other_user")
        assertEquals(0, mismatch.statusCode)
        assertNull(mismatch.profile)
    }

    @Test
    fun parseTikTokUserDetailFromHtml_handlesMissingUserAndInvalidHtml() {
        val missingHtml = """
            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
            {"__DEFAULT_SCOPE__":{"webapp.user-detail":{"statusCode":10221,"statusMsg":"user banned"}}}
            </script>
        """.trimIndent()

        val res = PlatformApi.parseTikTokUserDetailFromHtml(missingHtml, "tidak_ada")
        assertEquals(10221, res.statusCode)
        assertNull(res.profile)

        val empty = PlatformApi.parseTikTokUserDetailFromHtml("<html><body>no json</body></html>", "freyajkt48")
        assertNull(empty.statusCode)
        assertNull(empty.profile)
    }

    @Test
    fun isAvatarUrlExpiredOrBlank_detectsBlankExpiredAndFreshUrls() {
        val now = 1_795_800_000L
        assertTrue(PlatformApi.isAvatarUrlExpiredOrBlank(null, now))
        assertTrue(PlatformApi.isAvatarUrlExpiredOrBlank("   ", now))
        // Sudah kedaluwarsa atau sisa < 1 jam
        assertTrue(PlatformApi.isAvatarUrlExpiredOrBlank("https://p16.tiktokcdn.com/a.jpg?x-expires=1795801000&sig=abc", now))
        assertTrue(PlatformApi.isAvatarUrlExpiredOrBlank("https://p16.tiktokcdn.com/a.jpg?x-expires=1795790000&sig=abc", now))
        // Masih valid (> 1 jam)
        assertFalse(PlatformApi.isAvatarUrlExpiredOrBlank("https://p16.tiktokcdn.com/a.jpg?x-expires=1795867200&sig=abc", now))
        // URL tanpa parameter x-expires (mis. SHOWROOM/IDN)
        assertFalse(PlatformApi.isAvatarUrlExpiredOrBlank("https://cdn.idn.media/avatar.webp?v=123", now))
    }
}
