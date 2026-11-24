package com.bingemote

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RefinementsUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private fun launch(screen: String) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", screen).putExtra("pcdemo", "true").putExtra("statuscase", "cur-g"))
    }
    @Test fun yourPcCaptionAndPairButtonFit() {
        // Model an existing paired install, not the prior test's empty token.
        Store(ctx).helperToken = "fixture-paired-token"
        launch("yourpc")
        val caption = device.wait(Until.findObject(By.text("Attempting to reconnect in the background")), 30000)
        assertNotNull("caption missing/truncated", caption)
        val button = device.findObject(By.text("Pair again"))
        assertNotNull(button)
        assertTrue(caption.visibleBounds.left >= 0)
        assertTrue(caption.visibleBounds.right <= device.displayWidth)
        assertTrue(button.visibleBounds.top > caption.visibleBounds.bottom)
        assertTrue(device.hasObject(By.text("Only needed for a new phone, or if this phone was removed on the PC.")))
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-refinements-yourpc.png"))
        button.click()
        assertTrue("Pair again lost action", device.wait(Until.hasObject(By.text("Find your PC")), 30000))
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-refinements-after-pair-tap.png"))
    }
    @Test fun oldRingPreferenceRemovedAndFlashPreserved() {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        for (value in listOf("ring", "fills", "unknown")) {
            prefs.edit().putString("cancelStyle", value).putInt("buttonFlash", 0).commit()
            val store = Store(ctx)
            assertFalse(prefs.contains("cancelStyle"))
            assertEquals(0, store.buttonFlash)
        }
        launch("settings")
        // Scan with a deadline instead of a fixed swipe count: a loaded CI emulator
        // can need many seconds just to compose the screen before scrolling helps.
        val deadline = System.currentTimeMillis() + 30000
        while (!device.hasObject(By.text("Button flash")) && System.currentTimeMillis() < deadline) {
            device.swipe(device.displayWidth/2, device.displayHeight*4/5, device.displayWidth/2, device.displayHeight/3, 30)
            Thread.sleep(300)
        }
        assertTrue("Button flash removed", device.hasObject(By.text("Button flash")))
        assertFalse("retired selector remains", device.hasObject(By.text("Cancel button style")))
        assertTrue(device.hasObject(By.text("Skip rings only")))
        device.swipe(device.displayWidth/2, device.displayHeight*4/5, device.displayWidth/2, device.displayHeight*3/5, 20)
        Thread.sleep(300)
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-refinements-settings.png"))
    }
}
