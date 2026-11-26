package com.bingemote.stream

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backgrounded-before-bind: the app is sent Home, the test waits (bounded) until the process is no longer foreground or
 * visible, then calls StreamCoordinator.bind. Whatever the platform decides, the app process must survive (running this
 * test to the end is that check), a failure must be a plain exception (not a crash), and no StreamService may be left
 * running afterwards. The test RECORDS the outcome and importance it actually observed (logcat tag FgBindBackground) and
 * does not assume a denial: an instrumentation process may keep an exemption, in which case the bind simply succeeds.
 */
@RunWith(AndroidJUnit4::class)
class StreamServiceBackgroundBindAndroidTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private fun importance(): Int { val i = ActivityManager.RunningAppProcessInfo(); ActivityManager.getMyMemoryState(i); return i.importance }
    private fun serviceRunning(): Boolean {
        @Suppress("DEPRECATION")
        return (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(100).any { it.service.className == StreamService::class.java.name }
    }

    @Test fun bindWhileBackgrounded_failsPlainlyOrSucceeds_processSurvives_noServiceLeft() {
        ctx.startActivity(Intent(ctx, com.bingemote.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("app is on screen first", device.wait(Until.hasObject(By.pkg(ctx.packageName).depth(0)), 20000))
        device.pressHome()
        val until = android.os.SystemClock.elapsedRealtime() + 15_000
        while (importance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(250)
        val imp = importance()
        var outcome: String
        try {
            val bound = runBlocking { StreamCoordinator.bind(ctx) }
            outcome = "bind SUCCEEDED while importance=$imp"
            StreamCoordinator.release(ctx, bound)
        } catch (e: Exception) {
            outcome = "bind FAILED while importance=$imp: ${e.javaClass.simpleName}: ${e.message}"
        }
        android.util.Log.i("FgBindBackground", outcome)
        println("FgBindBackground: $outcome")
        ctx.stopService(Intent(ctx, StreamService::class.java))
        val end = android.os.SystemClock.elapsedRealtime() + 15_000
        while (serviceRunning() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(250)
        assertFalse("no StreamService left running after the attempt ($outcome)", serviceRunning())
    }
}
