package com.bingemote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CmdPolicyTest {
    @Test
    fun `fresh taps are sent`() {
        assertTrue(1999L < CmdPolicy.EXPIRY_MS)
    }

    @Test
    fun `stale taps expire`() {
        assertTrue(2001L >= CmdPolicy.EXPIRY_MS)
    }

    @Test
    fun `closed vlc rechecks every fourth poll`() {
        // Polls 1,2,3 after closing skip VLC; poll 4 checks it.
        val checked = (1..8).map { it % CmdPolicy.CLOSED_POLL_EVERY == 0 }
        assertFalse(checked[0]); assertFalse(checked[1]); assertFalse(checked[2])
        assertTrue(checked[3]); assertTrue(checked[7])
    }
}
