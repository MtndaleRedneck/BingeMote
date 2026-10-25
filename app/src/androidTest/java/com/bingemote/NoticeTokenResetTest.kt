package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The helper-notice state (unpaired / helperDown / helperNotice) is learned from a
 * pairing. When the pairing token changes - re-pair, or Start over clearing it - the
 * old notice must not survive and demand pairing (or warn) about the NEW pairing. */
class NoticeTokenResetTest {
    private fun freshRemote(): Pair<Store, RemoteState> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val store = Store(ctx)
        return store to RemoteState(ctx, store, CoroutineScope(SupervisorJob()))
    }

    @Test
    fun tokenChangeClearsNoticeState() = runBlocking {
        val (store, remote) = freshRemote()
        remote.pollOnce() // settles the notice tracker onto the current token
        remote.debugForceStatus(unreachable = false, helperDown = true, helperNotice = true, unpaired = true)
        assertTrue(remote.unpaired)
        store.helperToken = "repaired-token" // re-pairing saves a new token
        remote.pollOnce()
        assertFalse("pair-again notice survived re-pairing", remote.unpaired)
        assertFalse("helper-down notice survived re-pairing", remote.helperNotice)
        assertFalse("helper-down volume fallback survived re-pairing", remote.helperDown)
    }

    @Test
    fun clearedTokenClearsNoticeState() = runBlocking {
        val (store, remote) = freshRemote()
        store.helperToken = "old-token"
        remote.pollOnce() // settles noticeToken onto "old-token"
        remote.debugForceStatus(unreachable = false, helperDown = true, helperNotice = true, unpaired = true)
        store.helperToken = "" // Start over clears the pairing
        remote.pollOnce()
        assertFalse("pair-again notice survived Start over", remote.unpaired)
        assertFalse("helper-down notice survived Start over", remote.helperNotice)
        assertFalse("helper-down volume fallback survived Start over", remote.helperDown)
    }

    @Test
    fun unchangedTokenKeepsNoticeState() = runBlocking {
        val (store, remote) = freshRemote()
        remote.pollOnce() // settles the notice tracker onto the current token
        remote.debugForceStatus(unreachable = false, helperDown = true, helperNotice = true, unpaired = true)
        remote.pollOnce() // same token: no reset (the unconfigured early-return leaves the state alone)
        assertTrue(remote.unpaired)
        assertTrue(remote.helperNotice)
        assertTrue(remote.helperDown)
    }
}
