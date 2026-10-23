package com.bingemote

import android.graphics.BitmapFactory
import android.graphics.Rect
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
import java.io.File
import kotlin.concurrent.thread

/**
 * The 1.9 cancel circle: during a seek drag the play/pause circle becomes the
 * cancel target. Release inside = keep the place (no seekTo, no play/pause
 * toggle, counters return to the polled time); an interrupted drag does nothing.
 *
 * Rig: demo seed 25:00 / -1:25:24 of 1:50:24 (length 6624s); the bar is always
 * on top since 1.9; track x 44..1396, center y ~913 at 1440x2807 @ 649dpi. The
 * circle's real bounds come from the "Pause" semantics node, not constants.
 * Evidence is captured BEFORE the assertion that depends on it.
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
        // is transiently there and no drag starts (interruptedDragDoesNothing,
        // run 39953888432). Wait for the transport and let placement settle.
        assertNotNull("play/pause button not found after launch",
            device.wait(Until.findObject(By.desc("Pause")), 8000))
        Thread.sleep(1500)
    }

    private fun capture(tag: String) {
        val dir = instrumentation.targetContext.filesDir
        val shot = File(dir, "cancel-$tag.png")
        assertTrue("screenshot capture failed for '$tag' (${shot.absolutePath})", device.takeScreenshot(shot))
        device.dumpWindowHierarchy(File(dir, "cancel-$tag.xml"))
    }

    private fun bmSeekLog(): String = device.executeShellCommand("logcat -d -s BMSeek:I")

    /** Pixel-compare one region of two screenshots (actual pixels, no tree). */
    private fun regionMatches(a: File, b: File, r: Rect, toleranceFraction: Double = 0.01): Boolean {
        val ba = BitmapFactory.decodeFile(a.path) ?: return false
        val bb = BitmapFactory.decodeFile(b.path) ?: return false
        var total = 0
        var diff = 0
        for (x in r.left until r.right) {
            for (y in r.top until r.bottom) {
                if (x >= ba.width || y >= ba.height || x >= bb.width || y >= bb.height) continue
                total++
                if (ba.getPixel(x, y) != bb.getPixel(x, y)) diff++
            }
        }
        return total > 0 && diff <= (total * toleranceFraction).toInt()
    }

    /** Seed counter bounds + baseline pixels, captured before any gesture. */
    private fun counterBaseline(tag: String): Triple<Rect, Rect, File> {
        val seedFwd = device.findObject(By.text("25:00"))
        val seedBack = device.findObject(By.text("-1:25:24"))
        assertNotNull("seed counters not found for the baseline", seedFwd)
        assertNotNull("seed back counter not found for the baseline", seedBack)
        val before = File(instrumentation.targetContext.filesDir, "cancel-pixels-$tag-before.png")
        assertTrue("baseline counter screenshot failed", device.takeScreenshot(before))
        return Triple(seedFwd.visibleBounds, seedBack.visibleBounds, before)
    }

    /**
     * The counters must actually come back - verified on real pixels, which the
     * stale accessibility tree cannot fake (run 39953888432: byte-identical XML
     * dumps across states). The tree-text assertion stays as the primary check;
     * this is the tree-independent supplement.
     */
    private fun assertCountersRestored(tag: String, baseline: Triple<Rect, Rect, File>, fresh: Boolean) {
        val after = File(instrumentation.targetContext.filesDir, "cancel-pixels-$tag-after.png")
        assertTrue("post-gesture counter screenshot failed", device.takeScreenshot(after))
        assertTrue(
            "forward counter pixels differ from the seed (see cancel-pixels-$tag-*.png)",
            regionMatches(baseline.third, after, baseline.first),
        )
        assertTrue(
            "back counter pixels differ from the seed (see cancel-pixels-$tag-*.png)",
            regionMatches(baseline.third, after, baseline.second),
        )
        assertTrue(
            "no window update after the gesture ended: accessibility events not flowing, tree text unreliable",
            fresh,
        )
        assertNotNull(
            "counters did not return to the seed (see cancel-pixels-$tag-*.png)",
            device.wait(Until.findObject(By.text("25:00")), 6000),
        )
    }

    /** "1:14:02" or "14:02", matching the app's formatTime. */
    private fun fmt(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
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
        // finger inside the circle (owner-approved; the freeze idea was rejected).
        val expSec = ((cx - 44f) / 1352f * 6624f).toLong()
        val baseline = counterBaseline(style)

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
        val liveCounter = device.wait(Until.findObject(By.text(fmt(expSec))), 3000)
        capture("inside-$style")
        assertNotNull("counters not live at the drag target inside the circle (see cancel-inside-$style.png/xml)", liveCounter)
        gesture.join()
        assertNull("gesture injection failed: $gestureError", gestureError)
        // Freshness gate first: the counter revert on release IS a window content
        // change, so catching it proves accessibility events are flowing before
        // any tree text below is trusted. Asserted inside assertCountersRestored,
        // after the pixel checks, so a stale tree cannot hide the pixel verdict.
        val fresh = device.waitForWindowUpdate(null, 5000)
        Thread.sleep(1000)
        capture("after-$style")
        val log = bmSeekLog()
        assertFalse("release inside the circle issued a seek: $log", log.contains("seekTo"))
        assertFalse("release inside the circle toggled play/pause: $log", log.contains("togglePause"))
        assertCountersRestored(style, baseline, fresh)
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
        val baseline = counterBaseline("interrupted")
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
        assertCountersRestored("interrupted", baseline, fresh)
    }
}
