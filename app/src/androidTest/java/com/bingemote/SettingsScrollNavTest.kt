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
 * back from a Settings subpage keeps the scroll position; leaving Settings
 * and re-entering from Home starts at the top. Real MainActivity, real taps.
 * On-screen checks use bounds, since scrolled-off Compose nodes can still be in the tree.
 */
class SettingsScrollNavTest {
    @get:Rule val failureDump = FailureDumpRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private fun onScreen(text: String): Boolean {
        val o = device.findObject(By.text(text)) ?: return false
        val b = o.visibleBounds
        return b.top >= 0 && b.bottom <= device.displayHeight && b.height() > 0
    }
    private fun shot(name: String) =
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-$name.png"))
    private fun scrollToFeedback() {
        var n = 0
        while (!onScreen("Send feedback, report a bug, or suggest a feature") && n++ < 12)
            device.swipe(540, 1700, 540, 600, 12)
        assertTrue("feedback row reachable", onScreen("Send feedback, report a bug, or suggest a feature"))
    }
    private fun openSettingsFromHome() {
        val gear = device.wait(Until.findObject(By.desc("Settings")), 20000)
        assertNotNull("Home gear", gear)
        gear.click()
        assertTrue(device.wait(Until.hasObject(By.text("Your PC")), 10000))
    }

    @Test fun backFromSubpageKeepsPositionAndReentryResetsToTop() {
        val stub = NavStub(true); stub.start()
        try {
            NavStub.seed(ctx, stub.port)
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            openSettingsFromHome()
            assertTrue(onScreen("Your PC"))
            scrollToFeedback()
            assertFalse("scrolled away from top", onScreen("Your PC"))
            device.findObject(By.text("Send feedback, report a bug, or suggest a feature")).click()
            assertTrue(device.wait(Until.gone(By.text("Skip buttons")), 10000))
            device.pressBack() // system back from the subpage
            assertTrue(device.wait(Until.hasObject(By.text("Feedback & bugs")), 10000))
            device.waitForIdle()
            assertTrue("position kept after back", onScreen("Send feedback, report a bug, or suggest a feature"))
            assertFalse("did not jump to top", onScreen("Your PC"))
            shot("settings-nav-kept")
            // leave Settings entirely (back to Home), then re-enter: starts at top
            device.pressBack()
            val gear = device.wait(Until.findObject(By.desc("Settings")), 10000)
            assertNotNull("back on Home", gear)
            gear.click()
            assertTrue(device.wait(Until.hasObject(By.text("Your PC")), 10000))
            device.waitForIdle()
            assertTrue("re-entry starts at top", onScreen("Your PC"))
            shot("settings-nav-top")
        } finally { stub.close() }
    }
}
