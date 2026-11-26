package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The top bar of Home, Movies and Settings has the same height: the header button sits at the same
 *  y on all three screens. Also: with the PC unreachable, switching the Movies origin from Phone back
 *  to PC must not leave the phone rows on screen. */
class TopBarParityTest {
    @get:Rule val failureDump = FailureDumpRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun launch(screen: String) {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", screen))
    }

    private fun buttonBounds(desc: String): android.graphics.Rect {
        var last: Exception? = null
        repeat(5) {
            val node = device.wait(Until.findObject(By.desc(desc)), 15000)
            assertNotNull("'$desc' button not found", node)
            try { return node!!.visibleBounds } catch (e: androidx.test.uiautomator.StaleObjectException) { last = e; Thread.sleep(500) }
        }
        throw last!!
    }

    @Test fun headerButtonSitsAtTheSameHeightOnHomeMoviesAndSettings() {
        launch("main")
        val home = buttonBounds("Settings")
        launch("movies")
        val movies = buttonBounds("Back")
        launch("settings")
        val settings = buttonBounds("Back")
        device.executeShellCommand("screencap -p /sdcard/topbar-parity.png")
        assertEquals("Movies back button top vs Home gear top", home.top, movies.top)
        assertEquals("Settings back button top vs Home gear top", home.top, settings.top)
        assertEquals("button heights", home.height(), movies.height())
        assertEquals("button heights", home.height(), settings.height())
    }

    @Test fun switchingBackToAnUnreachablePcClearsThePhoneRows() {
        launch("movies-phone")
        assertNotNull("demo phone rows absent", device.wait(Until.findObject(By.text("Another Sample Film")), 15000))
        device.findObject(By.text("PC")).click()
        assertTrue("phone rows still on screen after switching to PC",
            device.wait(Until.gone(By.text("Another Sample Film")), 8000))
        device.findObject(By.text("Phone")).click()
        assertNotNull("phone rows did not come back", device.wait(Until.findObject(By.text("Another Sample Film")), 8000))
        device.pressHome()
    }
}
