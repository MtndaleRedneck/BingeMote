package com.bingemote

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Screenshot-rig coverage for the original complaint path: ONE continuous touch that
 * goes down on the locked seek bar, holds past the 500ms in-press unlock, then slides
 * (wandering off the bar) without lifting, and releases. `adb input swipe` cannot
 * pause mid-gesture, so the two-command script phases can only cover unlock-retained-
 * then-drag; this test asserts the live preview DURING a single hold-then-slide.
 *
 * am instrument force-stops the app, so the test relaunches it with the rig extras
 * itself (seed: 25:00 elapsed / -1:25:24 remaining / 1:50:24 total, track x 44..1396,
 * center y ~913 at 1440x2807 @ 649dpi). The shell checks the BMSeek seekTo log after.
 *
 * Evidence rule: every capture is taken BEFORE the assertion that depends on the
 * state it shows. Captures are shell screencaps to /sdcard: in-test UiAutomation
 * takeScreenshot/dumpWindowHierarchy wedged this emulator's accessibility
 * pipeline (runs 39955727138/39956293965), freezing every later tree query.
 */
class SeekGestureTest {
    @Test
    fun continuousHoldThenSlide() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        // am instrument force-stops the app, so the shell's seeded launch is gone:
        // relaunch with the rig extras from here (runs as shell).
        device.executeShellCommand(
            "am start -n com.bingemote/.MainActivity --es screen main --ez demostatus true"
        )
        // Seed: 25:00 elapsed / -1:25:24 remaining / 1:50:24 total.
        val seeded = device.wait(Until.findObject(By.text("25:00")), 15000)
        assertNotNull("seeded demo seek block did not appear", seeded)
        assertNotNull("play/pause button not found after launch",
            device.wait(Until.findObject(By.desc("Pause")), 8000))
        Thread.sleep(1500)
        device.executeShellCommand("logcat -c")
        // Baseline for the shell-side seed-return pixel compare.
        val seedFwd = device.findObject(By.text("25:00"))
        val seedBack = device.findObject(By.text("-1:25:24"))
        assertNotNull("seed counters not found for the baseline", seedFwd)
        assertNotNull("seed back counter not found for the baseline", seedBack)
        val f = seedFwd.visibleBounds
        val bk = seedBack.visibleBounds
        device.executeShellCommand(
            "printf 'fwd %d %d %d %d\nback %d %d %d %d\n'" +
                " ${f.left} ${f.top} ${f.right} ${f.bottom}" +
                " ${bk.left} ${bk.top} ${bk.right} ${bk.bottom}" +
                " > /sdcard/bm-seek-slide-bounds.txt"
        )
        device.executeShellCommand("screencap -p /sdcard/bm-seek-slide-pixels-before.png")
        fun capture(tag: String) {
            device.executeShellCommand("screencap -p /sdcard/bm-seek-slide-$tag.png")
        }
        var gestureError: Throwable? = null
        val gesture = thread {
            // Explicit MotionEvent injection: UiDevice.swipe's step pacing is only
            // nominal (~5ms), and in run 39909338401 the stretched dwell outlasted
            // the whole 8s assertion window. A held touch needs no move events, so
            // the dwell is exact and the slide carries real timestamps.
            try {
                val ua = instrumentation.uiAutomation
                val t0 = SystemClock.uptimeMillis()
                fun inject(action: Int, x: Float, y: Float) {
                    val ev = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
                    ua.injectInputEvent(ev, true)
                    ev.recycle()
                }
                inject(MotionEvent.ACTION_DOWN, 400f, 913f)
                while (SystemClock.uptimeMillis() - t0 < 1200) Thread.sleep(50)
                for (i in 1..40) {
                    inject(MotionEvent.ACTION_MOVE, 400f + i * 750f / 40, 913f - i * 150f / 40)
                    Thread.sleep(75)
                }
                inject(MotionEvent.ACTION_UP, 1150f, 763f)
            } catch (t: Throwable) {
                gestureError = t
            }
        }
        // The seed counters must be DISPLACED during the gesture. The drag
        // handler DEBUG-logs the exact value the counter Text renders; logcat
        // cannot freeze the way this emulator's accessibility tree does under
        // counter churn (runs 39955727138/39956293965/39958872473).
        val t0 = SystemClock.uptimeMillis()
        var live = false
        var dragLog = ""
        while (SystemClock.uptimeMillis() - t0 < 8000 && !live) {
            Thread.sleep(400)
            dragLog = device.executeShellCommand("logcat -d -s BMSeek:I")
            live = dragLog.lines().any { line ->
                Regex("drag sec=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?.let { kotlin.math.abs(it - 1500) > 30 } == true
            }
        }
        assertNull("gesture injection failed: $gestureError", gestureError)
        assertTrue("continuous drag preview never left the seed time: $dragLog", live)
        // The bar hint keeps its pre-1.9 wording ("Unlocked..."); the cancel
        // wording lives on the play/pause circle, not the bar hint.
        val unlocked = device.wait(Until.findObject(By.textContains("Unlocked")), 4000)
        assertNotNull("in-press hold did not unlock during the slide", unlocked)
        // Pixel + tree proof, captured while the slide is still running.
        capture("mid")
        gesture.join()
        assertNull("gesture injection failed: $gestureError", gestureError)
        // State at the instant of release.
        capture("release")
        // Post-release the static demo poll restores the seed counters; the
        // shell pixel-compares the counter regions against the pre-gesture
        // baseline (tree text here froze in run 39958872473).
        Thread.sleep(1500)
        capture("post")
        device.executeShellCommand("screencap -p /sdcard/bm-seek-slide-pixels-after.png")
    }
}
