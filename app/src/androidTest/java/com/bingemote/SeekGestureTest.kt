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
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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
            // [start, dwell, end] at 300 steps/segment (~5ms each): ~1.5s dwell past
            // the 850ms in-press unlock, then a ~1.5s slide - one unbroken touch.
            // An injector exception must fail the test, not crash the process.
            try {
                device.swipe(arrayOf(start, dwell, end), 300)
            } catch (t: Throwable) {
                gestureError = t
            }
        }
        // ~0.5s into the slide: mid-gesture, past the in-press unlock.
        Thread.sleep(2000)
        val unlocked = device.wait(Until.findObject(By.textContains("Unlocked")), 1500)
        assertNotNull("in-press hold did not unlock during the slide", unlocked)
        // The seek block must be rendered, and both counters must be off the seed
        // values (UiAutomator By here has no regex selector, so assert absence of
        // the seed texts plus presence of the block's total label).
        val block = device.wait(Until.findObject(By.textContains("total")), 1500)
        assertNotNull("seek block not rendered mid-slide", block)
        assertNull(
            "continuous drag elapsed preview stayed at seed time",
            device.wait(Until.findObject(By.text("25:00")), 500),
        )
        assertNull(
            "continuous drag remaining preview stayed at seed time",
            device.wait(Until.findObject(By.text("-1:25:24")), 500),
        )
        gesture.join()
        assertNull("gesture injection failed: $gestureError", gestureError)
        // Post-release the static demo poll restores the seed counters.
        val post = device.wait(Until.findObject(By.text("25:00")), 3000)
        assertNotNull("post-release elapsed did not return to the seed poll", post)
    }
}
