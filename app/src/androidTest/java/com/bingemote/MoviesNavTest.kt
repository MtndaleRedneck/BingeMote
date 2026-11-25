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
 * Navigation rule: opening Movies fresh starts at the top; going into a folder and
 * coming back (Back) lands on the folder row you left. Deterministic local VLC stub: status.json plus
 * browse.json for C:\Movies (30 folders) and one subfolder. Fail-closed bounds checks.
 */
class MoviesNavTest {
    @get:Rule val failureDump = FailureDumpRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val root = NavStub.ROOT

    private fun onScreen(text: String): Boolean {
        val o = device.findObject(By.text(text)) ?: return false
        val b = o.visibleBounds
        return b.top >= 0 && b.bottom <= device.displayHeight && b.height() > 0
    }
    private fun shot(name: String) = device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-$name.png"))

    @Test fun moviesFreshEntryTopAndFolderBackKeepsPlace() {
        val stub = NavStub(false)
        try {
            stub.start()
            NavStub.seed(ctx, stub.port)
            Store(ctx).moviesPath = root
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val btn = device.wait(Until.findObject(By.desc("Movies")), 20000)
            assertNotNull("Home Movies button", btn)
            btn.click()
            assertTrue("list loaded", device.wait(Until.hasObject(By.text("Folder 01")), 15000))
            device.waitForIdle()
            assertTrue("first entry at top", onScreen("Folder 01"))
            shot("mv-first-top")

            // Scroll down to a deep folder row.
            var n = 0
            while (!onScreen("Folder 25") && n++ < 25) device.swipe(540, 1700, 540, 700, 12)
            assertTrue("deep folder reachable", onScreen("Folder 25"))
            assertFalse("scrolled off the top", onScreen("Folder 01"))
            shot("mv-scrolled")

            // Into the folder, then Back: same place.
            device.findObject(By.text("Folder 25")).click()
            assertTrue("inside folder", device.wait(Until.hasObject(By.text("inner movie")), 10000))
            device.pressBack()
            assertTrue("back in the parent list", device.wait(Until.hasObject(By.text("Folder 25")), 10000))
            device.waitForIdle()
            assertTrue("Back keeps place", onScreen("Folder 25"))
            assertFalse("did not jump to top", onScreen("Folder 01"))
            shot("mv-folder-back-kept")

            // Leave Movies to Home and open it again: top.
            device.pressBack()
            val again = device.wait(Until.findObject(By.desc("Movies")), 10000)
            assertNotNull("Home again", again)
            again.click()
            assertTrue(device.wait(Until.hasObject(By.text("Folder 01")), 15000))
            device.waitForIdle()
            assertTrue("fresh entry at top", onScreen("Folder 01"))
            assertFalse("not left deep", onScreen("Folder 25"))
            shot("mv-reentry-top")
        } finally { stub.close() }
    }
}
