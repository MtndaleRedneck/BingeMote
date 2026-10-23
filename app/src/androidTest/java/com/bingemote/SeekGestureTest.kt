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
import java.io.File
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
 * state it shows, and a failed capture fails loudly - a missing screenshot is a
 * rig defect to diagnose, never a silent NOTE.
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
        // External storage is unavailable to the test package on this AVD
        // (getExternalFilesDir returned null, run 39913166669); the target app's
        // internal files dir always exists and the shell reads it back via run-as.
        val evidenceDir = instrumentation.targetContext.filesDir
        assertTrue("evidence dir unusable: ${evidenceDir.absolutePath}",
            evidenceDir.isDirectory || evidenceDir.mkdirs())
        fun capture(tag: String) {
            val shot = File(evidenceDir, "seek-slide-$tag.png")
            assertTrue("screenshot capture failed for '$tag' (path=${shot.absolutePath})",
                device.takeScreenshot(shot))
            device.dumpWindowHierarchy(File(evidenceDir, "seek-slide-$tag.xml"))
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
        // The seed counters must be DISPLACED at some point during the gesture;
        // they only return to the seed on release (static demo poll), so a gone
        // event can only mean the preview went live mid-gesture. The gesture is
        // exactly 1.2s dwell + 3s slide, well inside the 8s window.
        val elapsedMoved = device.wait(Until.gone(By.text("25:00")), 8000)
        assertNull("gesture injection failed: $gestureError", gestureError)
        assertTrue("continuous drag elapsed preview never left the seed time", elapsedMoved)
        val remainingMoved = device.wait(Until.gone(By.text("-1:25:24")), 3000)
        assertTrue("continuous drag remaining preview never left the seed time", remainingMoved)
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
        // Post-release the static demo poll restores the seed counters; capture the
        // state the assertion judges BEFORE judging it, pass or fail.
        val post = device.wait(Until.findObject(By.text("25:00")), 3000)
        capture("post")
        assertNotNull("post-release elapsed did not return to the seed poll (see seek-slide-post.png/xml)", post)
    }
}
