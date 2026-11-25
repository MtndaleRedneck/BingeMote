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

    // Original contract, unchanged: one-shot reads right after the text appears (no added wait).
    // Failures are explained by the pre-stop observer, not hidden by synchronization.
    private fun awaitPlayingGlyph(phase: String) {
        assertTrue(has("0:30"))
        assertTrue(device.hasObject(By.desc("Pause")))
    }

    @Test fun connectedThenDropReadsZeroThenReconnects() {
        val stub = HapticTickTest.StatusStub(); stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            stub.state = "playing"
            stub.mark("launch")
            launch()
            try {
                waitText("1:00:00 total")
                stub.mark("connected-seen")
                awaitPlayingGlyph("connected")
                stub.mark("failStatus=true")
                stub.failStatus = true
                assertZero()
                stub.mark("zero-seen")
                shot("disconnected-zero")
                stub.mark("failStatus=false")
                stub.failStatus = false
                waitText("1:00:00 total")
                stub.mark("reconnected-seen")
                awaitPlayingGlyph("reconnected")
                shot("disconnected-reconnected")
            } catch (e: Throwable) {
                // OBSERVER ONLY: runs BEFORE the finally below, i.e. while the stub is still up.
                stub.mark("FAILED ${e.javaClass.simpleName} ${e.stackTrace.firstOrNull { it.className.endsWith("DisconnectedHomeUiTest") }}")
                observe(stub)
                throw e
            }
        } finally {
            stub.mark("STOP")
            diagWriteLog(stub)
            stub.stop()
        }
    }

    // OBSERVER (diagnostic only, no assertion or product change). Every step is wall-clock
    // stamped (start/end) so capture order and latency are known; queries and captures read
    // the live UI before the stub is stopped.
    private val obs = StringBuilder()
    private fun ts(what: String, block: () -> String = { "" }) {
        val a = System.currentTimeMillis()
        val r = runCatching(block).getOrElse { "ERR " + it }
        obs.append("$a-${System.currentTimeMillis()} $what $r\n")
    }
    private fun probe(tag: String) {
        ts("$tag hasPause(desc)") { device.hasObject(By.desc("Pause")).toString() }
        ts("$tag hasPlay(desc)") { device.hasObject(By.desc("Play")).toString() }
        ts("$tag has(1:00:00 total)") { has("1:00:00 total").toString() }
        ts("$tag has(0:00 total)") { has("0:00 total").toString() }
        ts("$tag has(Connected)") { has("Connected").toString() }
    }
    private fun observe(stub: HapticTickTest.StatusStub) {
        val dir = ctx.getExternalFilesDir(null) ?: return
        // No-sleep repeat reads of the play-control desc first (single-shot skew vs persistent),
        // then the stub's last request, then the full probe.
        ts("t0 desc Pause read1") { device.hasObject(By.desc("Pause")).toString() }
        ts("t0 desc Pause read2") { device.hasObject(By.desc("Pause")).toString() }
        ts("t0 desc Play read1") { device.hasObject(By.desc("Play")).toString() }
        ts("stub last req") { stub.reqLog.lastOrNull().toString() + " flags: " + stub.reqLog.takeLast(1).size }
        ts("lifecycle") { device.executeShellCommand("dumpsys activity activities").lineSequence().filter { it.contains("com.bingemote") && (it.contains("state=") || it.contains("mResumedActivity") || it.contains("topResumedActivity")) }.take(12).joinToString(" | ") }
        probe("t0")
        ts("png1") { device.takeScreenshot(File(dir, "bm-obs-1.png")).toString() }
        ts("uix1") { device.dumpWindowHierarchy(File(dir, "bm-obs-1.uix")); "" }
        ts("uix2") { device.dumpWindowHierarchy(File(dir, "bm-obs-2.uix")); "" }
        ts("png2") { device.takeScreenshot(File(dir, "bm-obs-2.png")).toString() }
        Thread.sleep(1000)
        probe("t+1s")
        ts("png3") { device.takeScreenshot(File(dir, "bm-obs-3.png")).toString() }
        ts("uix3") { device.dumpWindowHierarchy(File(dir, "bm-obs-3.uix")); "" }
        Thread.sleep(2000)
        probe("t+3s")
        ts("png4") { device.takeScreenshot(File(dir, "bm-obs-4.png")).toString() }
        ts("uix4") { device.dumpWindowHierarchy(File(dir, "bm-obs-4.uix")); "" }
        stub.mark("observer-done")
    }
    private fun diagWriteLog(stub: HapticTickTest.StatusStub) {
        val dir = ctx.getExternalFilesDir(null) ?: return
        runCatching { File(dir, "bm-stublog-connectedDrop.txt").writeText(stub.reqLog.joinToString("\n") + "\n") }
        runCatching { File(dir, "bm-obs-timeline.txt").writeText(obs.toString()) }
        runCatching {
            val out = device.executeShellCommand("logcat -d -v threadtime -t 4000")
            File(dir, "bm-logcat-connectedDrop.txt").writeText(out)
        }
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
