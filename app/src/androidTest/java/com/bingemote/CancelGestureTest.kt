package com.bingemote

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * The 1.9 cancel circle: during a seek drag the play/pause circle becomes the
 * cancel target. Release inside = keep the place (no seekTo, no play/pause
 * toggle, counters return to the polled time); an interrupted drag does nothing.
 *
 * Rig: demo seed 25:00 / -1:25:24 of 1:50:24 (length 6624s); the bar is always
 * on top since 1.9; track x 33..1033, center y ~675 at 1080x2310 @ 480dpi (225dp). The
 * circle's real bounds come from the "Pause" semantics node, not constants.
 *
 * Hard-won rig rules (runs 39953888432, 39955727138, 39956293965, 39958872473):
 * the emulator's accessibility tree freezes under rapid counter churn and stays
 * frozen for seconds regardless of how state is captured, so EVERY assertion
 * about a fast-changing counter/drag state lives on logcat + pixels instead:
 * the drag handler DEBUG-logs the exact value the counter Text renders, and the
 * shell pixel-compares counter regions against a pre-gesture baseline. The tree
 * is used only for static states (seed at launch, transport button present).
 * An injected ACTION_CANCEL is not a reliable system-cancel simulation, so the
 * interruption test takes focus away with a real activity switch instead.
 */
class CancelGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private fun launch(style: String) {
        device.executeShellCommand(
            "am start -n com.bingemote/.MainActivity --es screen main --es cancelstyle $style --ez demostatus true"
        )
        assertNotNull(
            "seeded demo seek block did not appear",
            device.wait(Until.findObject(By.text("25:00")), 15000),
        )
        // Cold launches place the measured layout a beat after the first text
        // composes; a gesture fired at the not-yet-placed bar lands on whatever
        // is transiently there and no drag starts. Wait for the transport and
        // let placement settle.
        assertNotNull("play/pause button not found after launch",
            device.wait(Until.findObject(By.desc("Pause")), 8000))
        Thread.sleep(1500)
    }

    /** Shell-side pixel evidence (the tree freezes; screencap never does). */
    private fun capture(tag: String) {
        device.executeShellCommand("screencap -p /sdcard/bm-cancel-$tag.png")
    }

    /** BMSeek lines after this test's last BEGIN marker. Tests no longer run
     * logcat -c: a later test's clear wiped earlier tests' bounds lines before
     * the single end-of-run host dump (run 39964355071), so isolation is by
     * marker instead. */
    private fun bmSeekLog(marker: String): String {
        val log = device.executeShellCommand("logcat -d -s BMSeek:I")
        val i = log.lastIndexOf("BEGIN $marker")
        return if (i >= 0) log.substring(i) else log
    }

    /** Seed counter bounds + a baseline shot, for the shell-side pixel compare. */
    private fun counterBaseline(tag: String) {
        val seedFwd = device.findObject(By.text("25:00"))
        val seedBack = device.findObject(By.text("-1:25:24"))
        assertNotNull("seed counters not found for the baseline", seedFwd)
        assertNotNull("seed back counter not found for the baseline", seedBack)
        val f = seedFwd.visibleBounds
        val b = seedBack.visibleBounds
        // Bounds travel over logcat, not a /sdcard printf: UiAutomation's
        // executeShellCommand does not interpret shell redirection on this
        // emulator (run 39960953492 pulled "cat: No such file" into every
        // bounds file, which the compare then silently skipped). The CI
        // script extracts these lines into the bounds files host-side.
        android.util.Log.i("BMSeek", "bounds $tag fwd ${f.left} ${f.top} ${f.right} ${f.bottom}")
        android.util.Log.i("BMSeek", "bounds $tag back ${b.left} ${b.top} ${b.right} ${b.bottom}")
        device.executeShellCommand("screencap -p /sdcard/bm-cancel-pixels-$tag-before.png")
    }

    /** Any drag log line with a counter value within tol of sec and the given inCircle. */
    private fun dragLogHas(log: String, sec: Long, tol: Long, inCircle: Boolean): Boolean =
        log.lines().any { line ->
            line.contains("inCircle=$inCircle") &&
                (Regex("sec=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?.let { kotlin.math.abs(it - sec) <= tol } == true)
        }

    private fun releaseInsideCancels(style: String) {
        android.util.Log.i("BMSeek", "BEGIN releaseInside-$style")
        launch(style)
        val pauseBtn = device.wait(Until.findObject(By.desc("Pause")), 8000)
        assertNotNull("play/pause button not found", pauseBtn)
        val b = pauseBtn.visibleBounds
        val cx = b.centerX().toFloat()
        val cy = b.centerY().toFloat()
        // The counters must stay live at the absolute-x target even with the
        // finger inside the circle; the in-circle freeze variant was dropped
        // in review.
        val expSec = ((cx - 33f) / 1000f * 6624f).toLong()
        counterBaseline(style)

        var gestureError: Throwable? = null
        val gesture = thread {
            try {
                val ua = instrumentation.uiAutomation
                val t0 = SystemClock.uptimeMillis()
                fun inject(action: Int, x: Float, y: Float) {
                    val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
                    ua.injectInputEvent(ev, true)
                    ev.recycle()
                }
                inject(MotionEvent.ACTION_DOWN, 296f, 675f)
                while (SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(50) // past the 500ms hold
                for (i in 1..20) {
                    inject(MotionEvent.ACTION_MOVE, 296f + (cx - 296f) * i / 20, 675f + (cy - 675f) * i / 20)
                    Thread.sleep(75)
                }
                Thread.sleep(2000) // held stationary inside the circle: the drag must survive
                inject(MotionEvent.ACTION_UP, cx, cy)
            } catch (t: Throwable) { gestureError = t }
        }
        // Wait until the finger is inside the circle (drag log; the cancel label
        // is a pure function of inCircle and is checked visually on the shots).
        val t0 = SystemClock.uptimeMillis()
        var dragLog = ""
        while (SystemClock.uptimeMillis() - t0 < 8000) {
            Thread.sleep(400)
            dragLog = bmSeekLog("releaseInside-$style")
            if (dragLogHas(dragLog, expSec, 2, inCircle = true)) break
        }
        assertNull("gesture injection failed: $gestureError", gestureError)
        capture("inside-$style")
        assertTrue(
            "counters not live at the drag target inside the circle (want sec~$expSec inCircle=true): $dragLog",
            dragLogHas(dragLog, expSec, 2, inCircle = true),
        )
        gesture.join()
        assertNull("gesture injection failed: $gestureError", gestureError)
        Thread.sleep(1000)
        capture("after-$style")
        device.executeShellCommand("screencap -p /sdcard/bm-cancel-pixels-$style-after.png")
        val log = bmSeekLog("releaseInside-$style")
        assertFalse("release inside the circle issued a seek: $log", log.contains("seekTo"))
        assertFalse("release inside the circle toggled play/pause: $log", log.contains("togglePause"))
        // Seed-return is asserted by the shell: pixel compare of the counter
        // regions against the baseline (bm-cancel-pixels-$style-*.png).
    }

    @Test
    fun releaseInsideCancelsRingOnly() = releaseInsideCancels("ring")

    @Test
    fun releaseInsideCancelsFillsIn() = releaseInsideCancels("fills")

    @Test
    fun interruptedDragDoesNothing() {
        android.util.Log.i("BMSeek", "BEGIN interrupted")
        launch("ring")
        counterBaseline("interrupted")
        var gestureError: Throwable? = null
        val gesture = thread {
            try {
                val ua = instrumentation.uiAutomation
                val t0 = SystemClock.uptimeMillis()
                fun inject(action: Int, x: Float, y: Float) {
                    val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
                    ua.injectInputEvent(ev, true)
                    ev.recycle()
                }
                inject(MotionEvent.ACTION_DOWN, 296f, 675f)
                while (SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(50)
                for (i in 1..10) {
                    inject(MotionEvent.ACTION_MOVE, 296f + i * 37f, 675f - i * 15f)
                    Thread.sleep(75)
                }
                Thread.sleep(5000) // finger held; the system takes over mid-drag
            } catch (t: Throwable) { gestureError = t }
        }
        // The preview must go live first, or a no-seek result proves nothing
        // (drag log: any counter value away from the 1500s seed).
        val t0 = SystemClock.uptimeMillis()
        var live = false
        var dragLog = ""
        while (SystemClock.uptimeMillis() - t0 < 8000 && !live) {
            Thread.sleep(400)
            dragLog = bmSeekLog("interrupted")
            live = dragLog.lines().any { line ->
                Regex("drag sec=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?.let { kotlin.math.abs(it - 1500) > 30 } == true
            }
        }
        assertNull("gesture injection failed: $gestureError", gestureError)
        assertTrue("drag preview never went live before the interruption: $dragLog", live)
        // A real interruption: another activity takes focus mid-drag, which is
        // how the system actually cancels a touch stream. The injected-cancel
        // probe below characterizes what the dispatcher does with an injected
        // ACTION_CANCEL; this activity switch is the faithful case.
        device.executeShellCommand("am start -a android.settings.SETTINGS")
        Thread.sleep(2500)
        capture("interrupted") // foreground = settings: the interruption itself
        gesture.join()
        val log = bmSeekLog("interrupted")
        assertFalse("interrupted drag issued a seek: $log", log.contains("seekTo"))
        assertFalse("interrupted drag toggled play/pause: $log", log.contains("togglePause"))
        // Back to the seeded main screen; the counters sit at the polled seed.
        device.executeShellCommand(
            "am start -n com.bingemote/.MainActivity --es screen main --es cancelstyle ring --ez demostatus true"
        )
        assertNotNull(
            "main screen did not come back after the interruption",
            device.wait(Until.findObject(By.text("25:00")), 15000),
        )
        device.executeShellCommand("screencap -p /sdcard/bm-cancel-pixels-interrupted-after.png")
    }

    /**
     * The cancel target is the VISIBLE circle, not the button's square layout
     * rect: a release at a rect corner (inside the rect, outside the ring)
     * must seek, and the drag must never enter the circle on the way (no
     * entry buzz, no "Release to cancel" wording). The approach drops
     * vertically onto the corner at a constant x outside the inscribed
     * circle, so the path itself never clips the ring.
     */
    @Test
    fun releaseAtRectCornerSeeksNotCancels() {
        android.util.Log.i("BMSeek", "BEGIN cornerRelease")
        launch("ring")
        val pauseBtn = device.wait(Until.findObject(By.desc("Pause")), 8000)
        assertNotNull("play/pause button not found", pauseBtn)
        val b = pauseBtn.visibleBounds
        // 6px off the rect's top-left corner: inside the layout rect, while
        // the point and the whole vertical approach stay ~10px+ outside the
        // inscribed circle (corner distance is ~41% beyond the radius).
        val px = (b.left + 6).toFloat()
        val py = (b.top + 6).toFloat()
        val expSec = ((px - 33f) / 1000f * 6624f).toLong()
        var gestureError: Throwable? = null
        val gesture = thread {
            try {
                val ua = instrumentation.uiAutomation
                val t0 = SystemClock.uptimeMillis()
                fun inject(action: Int, x: Float, y: Float) {
                    val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
                    ua.injectInputEvent(ev, true)
                    ev.recycle()
                }
                inject(MotionEvent.ACTION_DOWN, 296f, 675f)
                while (SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(50)
                for (i in 1..15) { // along the bar row to the corner's x
                    inject(MotionEvent.ACTION_MOVE, 296f + (px - 296f) * i / 15, 675f)
                    Thread.sleep(75)
                }
                for (i in 1..10) { // straight down onto the corner
                    inject(MotionEvent.ACTION_MOVE, px, 675f + (py - 675f) * i / 10)
                    Thread.sleep(75)
                }
                Thread.sleep(1000)
                inject(MotionEvent.ACTION_UP, px, py)
            } catch (t: Throwable) { gestureError = t }
        }
        val t0 = SystemClock.uptimeMillis()
        var dragLog = ""
        while (SystemClock.uptimeMillis() - t0 < 8000) {
            Thread.sleep(400)
            dragLog = bmSeekLog("cornerRelease")
            if (dragLogHas(dragLog, expSec, 2, inCircle = false)) break
        }
        gesture.join()
        assertNull("gesture injection failed: $gestureError", gestureError)
        Thread.sleep(1000)
        val log = bmSeekLog("cornerRelease")
        assertTrue("drag preview never reached the corner target (want sec~$expSec inCircle=false): $log",
            dragLogHas(log, expSec, 2, inCircle = false))
        assertFalse("finger at the rect corner entered the circle (buzz + cancel wording): $log",
            log.contains("inCircle=true"))
        val sought = Regex("seekTo seconds=(\\d+)").find(log)?.groupValues?.get(1)?.toLongOrNull()
        assertTrue("release at the rect corner issued no seek: $log", sought != null)
        assertTrue("release-at-corner seek $sought missed the drag target ~$expSec: $log",
            sought != null && kotlin.math.abs(sought - expSec) <= 2)
        assertFalse("release at the rect corner toggled play/pause: $log", log.contains("togglePause"))
    }

    /**
     * Characterizes the injected ACTION_CANCEL path next to the faithful
     * activity-switch test above. The raw stream is hooked at
     * MainActivity.dispatchTouchEvent (DEBUG "raw action=" lines), so the
     * outcome map is: action=3 logged + no seek = the gate works and the
     * dispatcher forwards injected cancels faithfully; action=1 + seek = the
     * dispatcher converts injected cancels to UP (injection artifact, not an
     * app bug); no raw line after the moves = the dispatcher swallowed the
     * event entirely (inconclusive, reported as a failure with evidence).
     */
    @Test
    fun injectedCancelDoesNotSeek() {
        android.util.Log.i("BMSeek", "BEGIN injectedCancel")
        launch("ring")
        assertNotNull("play/pause button not found",
            device.wait(Until.findObject(By.desc("Pause")), 8000))
        var gestureError: Throwable? = null
        val gesture = thread {
            try {
                val ua = instrumentation.uiAutomation
                val t0 = SystemClock.uptimeMillis()
                fun inject(action: Int, x: Float, y: Float) {
                    val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
                    ua.injectInputEvent(ev, true)
                    ev.recycle()
                }
                inject(MotionEvent.ACTION_DOWN, 296f, 675f)
                while (SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(50)
                for (i in 1..10) {
                    inject(MotionEvent.ACTION_MOVE, 296f + i * 37f, 675f - i * 15f)
                    Thread.sleep(75)
                }
                Thread.sleep(400)
                inject(MotionEvent.ACTION_CANCEL, 666f, 527f)
            } catch (t: Throwable) { gestureError = t }
        }
        val t0 = SystemClock.uptimeMillis()
        var live = false
        var dragLog = ""
        while (SystemClock.uptimeMillis() - t0 < 8000 && !live) {
            Thread.sleep(400)
            dragLog = bmSeekLog("injectedCancel")
            live = dragLog.lines().any { line ->
                Regex("drag sec=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?.let { kotlin.math.abs(it - 1500) > 30 } == true
            }
        }
        assertNull("gesture injection failed: $gestureError", gestureError)
        assertTrue("drag preview never went live before the injected cancel: $dragLog", live)
        gesture.join()
        Thread.sleep(1000)
        val log = bmSeekLog("injectedCancel")
        val rawAfterDrag = log.lines().map { it.trim() }
            .filter { Regex("raw action=\\d+").containsMatchIn(it) }
        // A DOWN must be there from the gesture start; the diagnostic is the
        // tail AFTER the drag went live (what the cancel became).
        assertTrue("no raw DOWN/UP/CANCEL reached MainActivity at all: $log",
            rawAfterDrag.isNotEmpty())
        val tail = rawAfterDrag.last()
        assertFalse("injected cancel led to a seek (raw tail=$tail): $log",
            log.contains("seekTo"))
        assertFalse("injected cancel toggled play/pause (raw tail=$tail): $log",
            log.contains("togglePause"))
    }
}
