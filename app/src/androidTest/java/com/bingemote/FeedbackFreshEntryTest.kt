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
 *  opening a page fresh starts at the top; Back to a
 * parent keeps its place. Real MainActivity, real taps. Every check is a bounds query on the live
 * hierarchy; a missing node counts as "not on screen", and every positive step asserts presence,
 * so an unreadable screen fails instead of passing.
 */
class FeedbackFreshEntryTest {
    @get:Rule val failureDump = FailureDumpRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val row = "Send feedback, report a bug, or suggest a feature"

    private fun onScreen(text: String): Boolean {
        val o = device.findObject(By.text(text)) ?: return false
        val b = o.visibleBounds
        return b.top >= 0 && b.bottom <= device.displayHeight && b.height() > 0
    }
    private fun shot(name: String) =
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-$name.png"))

    @Test fun feedbackReopensAtTopButBackKeepsSettingsPlace() {
        val stub = HapticTickTest.StatusStub(); stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val gear = device.wait(Until.findObject(By.desc("Settings")), 20000)
            assertNotNull("Home gear", gear)
            gear.click()
            assertTrue(device.wait(Until.hasObject(By.text("Your PC")), 10000))
            var n = 0
            while (!onScreen(row) && n++ < 12) device.swipe(540, 1700, 540, 600, 12)
            assertTrue("feedback row reachable", onScreen(row))

            // First visit: opens at top, then scroll to the bottom of the page.
            device.findObject(By.text(row)).click()
            assertTrue(device.wait(Until.hasObject(By.text("Your message")), 10000))
            device.waitForIdle()
            assertTrue("first visit at top", onScreen("Feedback") && onScreen("Your message"))
            shot("fb-first-top")
            n = 0
            while (!onScreen("Open private form") && n++ < 8) device.swipe(540, 1700, 540, 500, 12)
            assertTrue("scrolled to bottom", onScreen("Open private form"))
            assertFalse("title scrolled away", onScreen("Feedback"))
            shot("fb-scrolled-bottom")

            // Back keeps the Settings position.
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("Feedback & bugs")), 10000))
            device.waitForIdle()
            assertTrue("Settings position kept after Back", onScreen(row))
            assertFalse("Settings did not jump to top", onScreen("Your PC"))
            shot("fb-back-settings-kept")

            // Fresh entry again: Feedback must start at the top, not at the old offset.
            device.findObject(By.text(row)).click()
            assertTrue(device.wait(Until.hasObject(By.text("Your message")), 10000))
            device.waitForIdle()
            assertTrue("fresh entry at top (title)", onScreen("Feedback"))
            assertTrue("fresh entry at top (field)", onScreen("Your message"))
            assertFalse("not left at the bottom", onScreen("Open private form"))
            shot("fb-reentry-top")
        } finally { stub.stop() }
    }
}
