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
 * on top since 1.9; track x 44..1396, center y ~913 at 1440x2807 @ 649dpi. The
 * circle's real bounds come from the "Pause" semantics node, not constants.
 *
 * Two hard-won rig rules (runs 39953888432, 39955727138, 39956293965):
 * 1. No in-test UiAutomation takeScreenshot/dumpWindowHierarchy: each one wedges
 *    the emulator's accessibility pipeline and freezes every later tree query,
 *    sometimes into the next instrument invocation. Evidence captures go through
 *    shell screencap instead, which never wedged anything.
 * 2. Counter assertions during/after a drag cannot rely on the tree alone: the
 *    live-counter check reads the drag handler's own DEBUG log (the same value
 *    the counter Text renders), and the seed-return check pairs a freshness
 *    gate with the tree text plus a shell-side pixel compare of the counter
 *    regions (screenshots/bm-cancel-pixels-*, bounds in bm-cancel-bounds-*).
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

    /** Shell-side pixel evidence (see the class doc for why not UiAutomation). */
    private fun capture(tag: String) {
        device.executeShellCommand("screencap -p /sdcard/bm-cancel-$tag.png")
    }

    private fun bmSeekLog(): String = device.executeShellCommand("logcat -d -s BMSeek:I")

    /** Seed counter bounds + a baseline shot, for the shell-side pixel compare. */
    private fun counterBaseline(tag: String) {
        val seedFwd = device.findObject(By.text("25:00"))
        val seedBack = device.findObject(By.text("-1:25:24"))
        assertNotNull("seed counters not found for the baseline", seedFwd)
        assertNotNull("seed back counter not found for the baseline", seedBack)
        val f = seedFwd.visibleBounds
        val b = seedBack.visibleBounds
        device.executeShellCommand(
            "printf 'fwd %d %d %d %d\\nback %d %d %d %d\\n'" +
                " ${f.left} ${f.top} ${f.right} ${f.bottom}" +
                " ${b.left} ${b.top} ${b.right} ${b.bottom}" +
                " > /sdcard/bm-cancel-bounds-$tag.txt"
        )
        device.executeShellCommand("screencap -p /sdcard/bm-cancel-pixels-$tag-before.png")
    }

    /**
     * The counters must actually come back. The freshness gate proves
     * accessibility events flowed after the gesture (so the tree text means
     * something); the shell compares the counter-region pixels against the
     * pre-gesture baseline, which no stale tree can fake.
     */
    private fun assertCountersRestored(tag: String, fresh: Boolean) {
        device.executeShellCommand("screencap -p /sdcard/bm-cancel-pixels-$tag-after.png")
        assertTrue(
            "no window update after the gesture ended: accessibility events not flowing, tree text unreliable",
            fresh,
        )
        assertNotNull(
            "counters did not return to the seed (see bm-cancel-pixels-$tag-*.png + shell pixel compare)",
            device.wait(Until.findObject(By.text("25:00")), 6000),
        )
    }

    private fun releaseInsideCancels(style: String) {
        device.executeShellCommand("logcat -c")
        launch(style)
        val pauseBtn = device.wait(Until.findObject(By.desc("Pause")), 8000)
        assertNotNull("play/pause button not found", pauseBtn)
        val b = pauseBtn.visibleBounds
        val cx = b.centerX().toFloat()
        val cy = b.centerY().toFloat()
        // The counters must stay live at the absolute-x target even with the
        // finger inside the circle; the in-circle freeze variant was dropped
        // in review.
        val expSec = ((cx - 44f) / 1352f * 6624f).toLong()
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
                inject(MotionEvent.ACTION_DOWN, 400f, 913f)
                while (SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(50) // past the 500ms hold
                for (i in 1..20) {
                    inject(MotionEvent.ACTION_MOVE, 400f + (cx - 400f) * i / 20, 913f + (cy - 913f) * i / 20)
                    Thread.sleep(75)
                }
                Thread.sleep(2000) // held stationary inside the circle: the drag must survive
                inject(MotionEvent.ACTION_UP, cx, cy)
            } catch (t: Throwable) { gestureError = t }
        }
        val cancelLabel = device.wait(Until.findObject(By.text("Release to cancel")), 6000)
        assertNull("gesture injection failed: $gestureError", gestureError)
        assertNotNull("cancel wording never appeared inside the circle", cancelLabel)
        capture("inside-$style")
        // Live counters inside the circle: the drag handler DEBUG-logs the same
        // value the counter Text renders, and logcat cannot freeze.
        val dragLog = bmSeekLog()
        assertTrue(
            "no live drag counter logged inside the circle (want sec~$expSec inCircle=true): $dragLog",
            dragLog.lines().any { line ->
                line.contains("inCircle=true") &&
                    (Regex("sec=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                        ?.let { kotlin.math.abs(it - expSec) <= 2 } == true)
            },
        )
        gesture.join()
        assertNull("gesture injection failed: $gestureError", gestureError)
        // Freshness gate before any tree text: the counter revert on release IS
        // a window content change, so catching it proves events are flowing.
        val fresh = device.waitForWindowUpdate(null, 5000)
        Thread.sleep(1000)
        capture("after-$style")
        val log = bmSeekLog()
        assertFalse("release inside the circle issued a seek: $log", log.contains("seekTo"))
        assertFalse("release inside the circle toggled play/pause: $log", log.contains("togglePause"))
        assertCountersRestored(style, fresh)
        assertNotNull(
            "play/pause flipped despite the cancel (no toggle allowed)",
            device.wait(Until.findObject(By.desc("Pause")), 2000),
        )
    }

    @Test
    fun releaseInsideCancelsRingOnly() = releaseInsideCancels("ring")

    @Test
    fun releaseInsideCancelsFillsIn() = releaseInsideCancels("fills")

    @Test
    fun interruptedDragDoesNothing() {
        device.executeShellCommand("logcat -c")
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
                inject(MotionEvent.ACTION_DOWN, 400f, 913f)
                while (SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(50)
                for (i in 1..10) {
                    inject(MotionEvent.ACTION_MOVE, 400f + i * 50f, 913f - i * 20f)
                    Thread.sleep(75)
                }
                inject(MotionEvent.ACTION_CANCEL, 900f, 713f)
            } catch (t: Throwable) { gestureError = t }
        }
        // The preview must go live first, or a no-seek result proves nothing.
        val moved = device.wait(Until.gone(By.text("25:00")), 8000)
        assertNull("gesture injection failed: $gestureError", gestureError)
        assertTrue("drag preview never went live before the interruption", moved)
        gesture.join()
        assertNull("gesture injection failed: $gestureError", gestureError)
        val fresh = device.waitForWindowUpdate(null, 5000)
        Thread.sleep(1000)
        capture("interrupted")
        val log = bmSeekLog()
        assertFalse("interrupted drag issued a seek: $log", log.contains("seekTo"))
        assertFalse("interrupted drag toggled play/pause: $log", log.contains("togglePause"))
        assertCountersRestored("interrupted", fresh)
    }
}
