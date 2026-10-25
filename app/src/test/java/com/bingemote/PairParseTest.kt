package com.bingemote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** The pair response parser: new helpers hand over the VLC password, old helpers don't. */
class PairParseTest {

    @Test
    fun newHelperCarriesTheVlcPassword() {
        val r = HelperClient.parsePairJson(
            """{"token":"abc123","name":"DESKTOP","vlcPassword":"f00dbabe","vlcPending":true}""",
        )
        assertEquals("abc123", r.token)
        assertEquals("DESKTOP", r.name)
        assertEquals("f00dbabe", r.vlcPassword)
        assertTrue(r.vlcPending)
    }

    @Test
    fun oldHelperLeavesThePasswordOut() {
        val r = HelperClient.parsePairJson("""{"token":"abc123","name":"DESKTOP"}""")
        assertEquals("abc123", r.token)
        assertNull(r.vlcPassword) // the phone falls back to the manual password card
        assertFalse(r.vlcPending)
    }

    @Test
    fun blankPasswordCountsAsMissing() {
        val r = HelperClient.parsePairJson("""{"token":"abc123","vlcPassword":"  "}""")
        assertNull(r.vlcPassword)
    }
}
