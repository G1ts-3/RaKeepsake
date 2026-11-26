package id.rakeepsake.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterTransferTest {

    @Test
    fun parseJson_parsesMembersObjectAndCrstlnzArray() {
        val bundled = """
            {
              "members": [
                {
                  "nama_panggilan": "Ella",
                  "nama_lengkap": "Gabriela Abigail Mewengkang",
                  "media_showroom": "JKT48_Ella",
                  "media_idn": "jkt48_ella",
                  "media_tiktok": "jkt48.ella.a"
                }
              ]
            }
        """.trimIndent()
        val parsedBundled = RosterTransfer.parseJson(bundled)
        assertEquals(1, parsedBundled.size)
        assertEquals("Ella", parsedBundled[0].name)
        assertEquals("JKT48_Ella", parsedBundled[0].showroomKey)
        assertEquals("jkt48_ella", parsedBundled[0].idnUser)
        assertEquals("jkt48.ella.a", parsedBundled[0].tiktokUser)

        val apiArray = """
            [
              {
                "name": "Abigail Rachel",
                "nicknames": ["Aralie"],
                "is_graduate": false,
                "idn_username": "jkt48_aralie",
                "socials": [
                  {"title": "TikTok", "url": "https://www.tiktok.com/@jkt48.aralie/"},
                  {"title": "SHOWROOM", "url": "https://www.showroom-live.com/JKT48_Aralie"}
                ]
              },
              {
                "name": "Graduated Member",
                "nicknames": ["Old"],
                "is_graduate": true,
                "idn_username": "old_idn",
                "socials": []
              }
            ]
        """.trimIndent()
        val parsedApi = RosterTransfer.parseJson(apiArray)
        assertEquals(1, parsedApi.size)
        assertEquals("Aralie", parsedApi[0].name)
        assertEquals("Abigail Rachel", parsedApi[0].fullName)
        assertEquals("jkt48_aralie", parsedApi[0].idnUser)
        assertEquals("JKT48_Aralie", parsedApi[0].showroomKey)
        assertEquals("jkt48.aralie", parsedApi[0].tiktokUser)
        assertTrue(parsedApi.none { it.name == "Old" })
    }
}
