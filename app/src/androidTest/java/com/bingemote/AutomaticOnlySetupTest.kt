package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test

class AutomaticOnlySetupTest {
    @Test fun pairEntryNeedsSelectedPcOrConfirmedAddress() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "setup").putExtra("pairdemo", "true"))
        assertTrue(device.wait(Until.hasObject(By.text("Find your PC")), 10000))
        assertFalse(device.hasObject(By.text("Code from your PC")))
        device.takeScreenshot(java.io.File("/sdcard/bm-setup-before-selection.png"))
        device.findObject(By.textContains("MY-PC  (")).click()
        Thread.sleep(500)
        device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
            device.displayWidth / 2, device.displayHeight / 3, 25)
        Thread.sleep(400)
        assertTrue(device.hasObject(By.text("Code from your PC")))
        device.takeScreenshot(java.io.File("/sdcard/bm-setup-selected-pc.png"))
        device.swipe(device.displayWidth / 2, device.displayHeight / 3,
            device.displayWidth / 2, device.displayHeight * 4 / 5, 25)
        Thread.sleep(400)
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        fields.first().text = "192.0.2.20"
        Thread.sleep(500)
        assertFalse(device.hasObject(By.text("Code from your PC")))
        device.pressBack()
        device.findObject(By.text("Use this address")).click()
        Thread.sleep(500)
        device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
            device.displayWidth / 2, device.displayHeight / 2, 25)
        Thread.sleep(400)
        assertTrue(device.hasObject(By.text("Code from your PC")))
        assertTrue(device.hasObject(By.text("Selected: 192.0.2.20:8488")))
        device.takeScreenshot(java.io.File("/sdcard/bm-setup-selected-address.png"))
    }

    @Test fun pendingAutomaticSetupHasDoneAndNoManualVlcFields() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "setup").putExtra("vlcpending", "true"))
        assertTrue(device.wait(Until.hasObject(By.text("Find your PC")), 10000))
        assertTrue(device.hasObject(By.text("Or type the PC's address:")))
        var foundDone = false
        repeat(7) {
            assertFalse(device.hasObject(By.text("VLC password")))
            assertFalse(device.hasObject(By.text("Turn on VLC's web remote")))
            assertFalse(device.hasObject(By.text("Set the password by hand instead")))
            assertFalse(device.hasObject(By.text("Hide the manual setup")))
            if (device.hasObject(By.text("Done"))) foundDone = true
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 4, 25)
            Thread.sleep(300)
        }
        assertTrue("Automatic deferred setup still needs a Done action", foundDone)
    }
}
