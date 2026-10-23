package com.bingemote

import android.graphics.Point
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Screenshot-rig coverage for the original complaint path: ONE continuous touch that
 * goes down on the locked seek bar, holds past the 850ms in-press unlock, then slides
 * (wandering off the bar) without lifting, and releases. `adb input swipe` cannot
 * pause mid-gesture, so the two-command script phases can only cover unlock-retained-
 * then-drag; this test asserts the live preview DURING a single hold-then-slide.
 *
 * The shell script launches the app first with --ez seektop true --ez demostatus true
 * (seed: 25:00 elapsed / -1:25:24 remaining / 1:50:24 total, track x 44..1396,
 * center y ~913 at 1440x2807 @ 649dpi) and checks the BMSeek seekTo log afterwards.
 */
class SeekGestureTest {
    @Test
    fun continuousHoldThenSlide() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        // am instrument force-stops the app, so the shell's seeded launch is gone:
        // relaunch with the rig extras from here (runs as shell).
        device.executeShellCommand(
            "am start -n com.bingemote/.MainActivity --es screen main --ez seektop true --ez demostatus true"
        )
        // Seed: 25:00 elapsed / -1:25:24 remaining / 1:50:24 total.
        val seeded = device.wait(Until.findObject(By.text("25:00")), 15000)
        assertNotNull("seeded demo seek block did not appear", seeded)
        val start = Point(400, 913)
        val dwell = Point(401, 913) // 1px: a non-degenerate segment, under the 14px move threshold
        val end = Point(1150, 760)
        var gestureError: Throwable? = null
        val gesture = thread {
            // [start, dwell, end] at 400 steps/segment (~5ms each): ~2s dwell past
            // the 850ms in-press unlock, then a ~2s slide - one unbroken touch.
            // An injector exception must fail the test, not crash the process.
            try {
                device.swipe(arrayOf(start, dwell, end), 400)
            } catch (t: Throwable) {
                gestureError = t
            }
        }
        // Step timing under injection is not exact, so do not sample at a fixed
        // offset (a 2s sample landed inside the dwell in run 39908157099). The
        // seed counters must be DISPLACED at some point during the gesture; they
        // only return to the seed on release (static demo poll), so a gone event
        // can only mean the preview went live mid-gesture.
        val elapsedMoved = device.wait(Until.gone(By.text("25:00")), 8000)
        assertTrue("continuous drag elapsed preview never left the seed time", elapsedMoved)
        val remainingMoved = device.wait(Until.gone(By.text("-1:25:24")), 3000)
        assertTrue("continuous drag remaining preview never left the seed time", remainingMoved)
        val unlocked = device.wait(Until.findObject(By.textContains("Unlocked")), 1500)
        assertNotNull("in-press hold did not unlock during the slide", unlocked)
        // Pixel proof, captured while the slide is still running.
        val shot = java.io.File(instrumentation.context.getExternalFilesDir(null), "seek-slide-mid.png")
        device.takeScreenshot(shot)
        gesture.join()
        assertNull("gesture injection failed: $gestureError", gestureError)
        // Post-release the static demo poll restores the seed counters.
        val post = device.wait(Until.findObject(By.text("25:00")), 3000)
        assertNotNull("post-release elapsed did not return to the seed poll", post)
    }
}
