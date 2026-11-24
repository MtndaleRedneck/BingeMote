package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test

class AutomaticOnlySetupTest {
    @org.junit.Before fun dismissSystemDialogs() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val wait = device.findObject(By.text("Wait"))
        wait?.click()
        device.waitForIdle()
    }

    @Test fun updateSuccessSharesVersionRowAndExpires() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "settings").putExtra("updateResult", "You're up to date."))
        // Rig intentionally starts at bottom, so the Settings header is offscreen.
        assertTrue(device.wait(Until.hasObject(By.text("Version " + BuildConfig.VERSION_NAME)), 4000))
        val version = device.findObject(By.text("Version " + BuildConfig.VERSION_NAME))
        val success = device.findObject(By.text("You're up to date."))
        assertFalse(device.hasObject(By.text("Updates")))
        assertNotNull(version)
        assertNotNull(success)
        assertEquals(version.visibleBounds.centerY(), success.visibleBounds.centerY())
        assertTrue(success.visibleBounds.left > version.visibleBounds.right)
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-updates-success.png"))
        assertTrue(device.wait(Until.gone(By.text("You're up to date.")), 7000))
        assertTrue(device.hasObject(By.text("Version " + BuildConfig.VERSION_NAME)))
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-updates-expired.png"))
    }

    @Test fun cancelReturnsToSettingsAndBottomBackIsAbsent() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Store(ctx).setupDone = true
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "setup").putExtra("pairdemo", "true"))
        assertTrue(device.wait(Until.hasObject(By.text("Cancel")), 10000))
        assertFalse(device.hasObject(By.text("Back")))
        device.findObject(By.text("Cancel")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Settings")), 5000))
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-cancel-settings.png"))
    }

    @Test fun pairEntryNeedsSelectedPcOrConfirmedAddress() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "setup").putExtra("pairdemo", "true"))
        assertTrue(device.wait(Until.hasObject(By.text("Find your PC")), 10000))
        assertFalse(device.hasObject(By.text("Code from your PC")))
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-before-selection.png"))
        device.findObject(By.textContains("MY-PC  (")).click()
        // Condition-driven reveal (review-driven): the pair-code section can render
        // without any scroll on large screens and can need a scroll on others, so
        // alternate a bounded render wait with a conditional scroll instead of fixed
        // swipes/sleeps. Run 45435505970 failed this assert with all four captures
        // absent (adb "No such file" stubs in the artifact), so on failure also
        // capture the actual screen state and hierarchy as evidence.
        val revealBy = System.currentTimeMillis() + 10000
        while (!device.hasObject(By.text("Code from your PC")) && System.currentTimeMillis() < revealBy) {
            device.wait(Until.hasObject(By.text("Code from your PC")), 700)
            if (!device.hasObject(By.text("Code from your PC"))) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 2, 20)
            }
        }
        if (!device.hasObject(By.text("Code from your PC"))) {
            device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-selected-pc-fail.png"))
            device.dumpWindowHierarchy(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-selected-pc-fail.xml"))
        }
        assertTrue(device.hasObject(By.text("Code from your PC")))
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-selected-pc.png"))
        device.swipe(device.displayWidth / 2, device.displayHeight / 3,
            device.displayWidth / 2, device.displayHeight * 4 / 5, 25)
        Thread.sleep(400)
        repeat(4) {
            if (!device.hasObject(By.text("Or type the PC's address:"))) {
                device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                    device.displayWidth / 2, device.displayHeight * 4 / 5, 20)
                Thread.sleep(200)
            }
        }
        assertTrue(device.hasObject(By.text("Or type the PC's address:")))
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        val addressField = fields.minBy { it.visibleBounds.top }
        addressField.text = "192.0.2.20"
        // ACTION_SET_TEXT applies asynchronously through the IME/accessibility path, so a
        // read on the very next frame can race the state update (saw one CI run read back
        // empty while the identical tree passed in its sibling run). Poll briefly instead.
        val textDeadline = System.currentTimeMillis() + 3000
        while (addressField.text != "192.0.2.20" && System.currentTimeMillis() < textDeadline) Thread.sleep(100)
        assertEquals("192.0.2.20", addressField.text)
        // Same async timing class as the setText read-back above: the confirmed-address
        // state update is not instant, so wait (bounded) for the pair-code entry to go
        // instead of checking once after a fixed sleep (run 45424136265 failed this one-shot check while two same-tree runs passed it).
        assertTrue(device.wait(Until.gone(By.text("Code from your PC")), 3000))
        if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
        repeat(3) {
            if (!device.hasObject(By.text("Use this address"))) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 3, 20)
                Thread.sleep(250)
            }
        }
        assertTrue(device.wait(Until.hasObject(By.text("Use this address")), 3000))
        device.findObject(By.text("Use this address")).click()
        Thread.sleep(500)
        repeat(4) {
            if (!device.hasObject(By.text("Code from your PC"))) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 2, 20)
                Thread.sleep(200)
            }
        }
        assertTrue(device.hasObject(By.text("Code from your PC")))
        assertTrue(device.hasObject(By.text("Selected: 192.0.2.20:8488")))
        device.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null), "bm-setup-selected-address.png"))
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
