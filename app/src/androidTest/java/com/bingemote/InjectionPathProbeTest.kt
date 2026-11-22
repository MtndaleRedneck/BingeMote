package com.bingemote

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNRESOLVED injection-path probe, kept deliberately separate from the gesture
 * suites (which migrated to sendPointerSync): on the api-31 CI emulator a drag
 * driven through uiAutomation.injectInputEvent was queue-accepted (every call
 * returned true) yet never produced app-side drag handling, while the same
 * stream through sendPointerSync worked - and both paths work on api 34.
 * Queue acceptance is not app receipt: this test does not decide where the
 * break lives (OS input pipeline vs app event/source handling); it pins the
 * observable difference so it stays visible instead of being hidden by the
 * migration. RED on api 31 is the expected, documented state while unresolved.
 */
class InjectionPathProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private fun bmSeekSince(marker: String): String {
        val log = device.executeShellCommand("logcat -d -s BMSeek:I")
        val i = log.lastIndexOf("BEGIN $marker")
        return if (i >= 0) log.substring(i) else ""
    }

    private fun dragLines(log: String) = log.lines().count { it.contains("drag sec=") }

    private fun drag(viaUiAutomation: Boolean, marker: String): Int {
        android.util.Log.i("BMSeek", "BEGIN $marker")
        device.executeShellCommand(
            "am start -n com.bingemote/.MainActivity --es screen main --es cancelstyle ring --ez demostatus true"
        )
        assertNotNull("seeded demo seek block did not appear", device.wait(Until.findObject(By.text("25:00")), 15000))
        assertNotNull("play/pause button not found", device.wait(Until.findObject(By.desc("Pause")), 8000))
        Thread.sleep(1500)
        val bar = device.wait(Until.findObject(By.res("seekbar")), 8000)
        assertNotNull("seek bar not found by test tag", bar)
        val b = bar.visibleBounds
        val sx = b.left + b.width() * 0.263f
        val sy = b.centerY().toFloat()
        val ex = b.left + b.width() * 0.75f
        val t0 = SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, y: Float) = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
        var allAccepted = true
        fun inject(e: MotionEvent) {
            if (viaUiAutomation) {
                val ok = instrumentation.uiAutomation.injectInputEvent(e, true)
                if (!ok) allAccepted = false
            } else {
                instrumentation.sendPointerSync(e)
            }
            e.recycle()
        }
        inject(ev(MotionEvent.ACTION_DOWN, sx, sy))
        while (SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(50) // past the 500ms hold
        for (i in 1..20) {
            inject(ev(MotionEvent.ACTION_MOVE, sx + (ex - sx) * i / 20, sy))
            Thread.sleep(75)
        }
        inject(ev(MotionEvent.ACTION_UP, ex, sy))
        Thread.sleep(1000)
        val log = bmSeekSince(marker)
        android.util.Log.i("BMSeek", "probe $marker allAccepted=$allAccepted dragLines=${dragLines(log)}")
        return dragLines(log)
    }

    @Test
    fun sendPointerSyncStartsADrag() {
        // The migration target: must work everywhere the suite runs.
        assertTrue("sendPointerSync drag produced no app-side drag lines", drag(false, "probe-sync") > 0)
    }

    @Test
    fun injectInputEventStartsADrag() {
        // The unresolved path: queue-accepted but no drag on api 31 (red there on
        // purpose until the cause is understood); green on api 34.
        assertTrue("injectInputEvent drag produced no app-side drag lines (known api-31 anomaly)", drag(true, "probe-uia") > 0)
    }
}
