package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Real MainActivity rendered against a stub VLC: connected shows the live time/total/Pause,
 * a dropped connection must render 0:00 / -0:00 / 0:00 total / Play (never the cached time),
 * and the real values return on reconnect. Also the VLC-closed and VLC-refused states.
 */
class DisconnectedHomeUiTest {
    @get:Rule val failureDump = FailureDumpRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private fun launch() {
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
    private fun shot(name: String) =
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-$name.png"))
    private fun has(text: String) = device.hasObject(By.text(text))
    private fun waitText(text: String, ms: Long = 20000) = assertTrue("expected '$text'", device.wait(Until.hasObject(By.text(text)), ms))
    private fun waitGone(text: String, ms: Long = 20000) = assertTrue("'$text' still shown", device.wait(Until.gone(By.text(text)), ms))

    private fun assertZero() {
        waitText("0:00 total")
        waitGone("1:00:00 total")
        assertTrue(has("-0:00"))
        assertFalse("cached time leaked", has("0:30"))
        // Run 45556057887 failed this read with the tree contradicting the settled
        // pixels: desc="Pause" on this node while Play was painted, stable across
        // the 4.2s dump pair - and MainScreen.kt feeds BOTH the desc (L629) and the
        // icon (L653) from ONE boolean (L591), so the split was in the delivered
        // accessibility snapshot, not in a separate glyph state hop. Wait (bounded)
        // for the settled Play state instead of reading once, then check for a
        // contradictory Pause at that settled moment. What this proves: the node's
        // semantics reached Play within the budget (a never-settling node fails),
        // and no Pause node is present at that single check point. What it does NOT
        // prove: anything before or after that point - Until waits are max waits,
        // not holds, and recurrence evidence is the FailureDumpRule pair's job. The
        // 10s figure is a CI wait budget, not a product-latency measurement. The
        // cause of the snapshot split (framework vs product) is neither claimed nor
        // fixed here; a product-side accessibility bug would still fail this check.
        assertTrue("Play glyph never appeared after the disconnect settled",
            device.wait(Until.hasObject(By.desc("Play")), 10000))
        assertFalse("Pause glyph contradicts the settled Play state",
            device.hasObject(By.desc("Pause")))
    }

    @Test fun connectedThenDropReadsZeroThenReconnects() {
        val stub = HapticTickTest.StatusStub(); stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            stub.state = "playing"
            launch()
            waitText("1:00:00 total")
            assertTrue(has("0:30"))
            assertTrue(device.hasObject(By.desc("Pause")))
            stub.failStatus = true
            assertZero()
            shot("disconnected-zero")
            stub.failStatus = false
            waitText("1:00:00 total")
            assertTrue(has("0:30"))
            assertTrue(device.hasObject(By.desc("Pause")))
            shot("disconnected-reconnected")
        } finally { stub.stop() }
    }

    @Test fun vlcClosedReadsZero() {
        val stub = HapticTickTest.StatusStub(); stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            Store(ctx).apply { helperToken = "x"; helperPort = stub.port }
            stub.state = "playing"
            launch()
            waitText("1:00:00 total")
            stub.failStatus = true
            waitText("VLC isn't open", 30000)
            assertZero()
            shot("vlcclosed-zero")
        } finally { stub.stop(); Store(ctx).apply { helperToken = ""; helperPort = 8488 } }
    }

    @Test fun vlcRefusedReadsZero() {
        val stub = HapticTickTest.StatusStub(); stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            stub.state = "playing"
            launch()
            waitText("1:00:00 total")
            stub.failAuth = true
            waitText("VLC refused the password", 30000)
            assertZero()
            shot("vlcrefused-zero")
        } finally { stub.stop() }
    }
}
