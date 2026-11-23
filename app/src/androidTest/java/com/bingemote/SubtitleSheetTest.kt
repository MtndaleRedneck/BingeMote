package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SubtitleSheetTest {
    @Test fun offThenFolderThenLoadedWithExtensionsHidden() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "subtitles").putExtra("subtitlepreview", true).putExtra("statuscase", "cur-g"))
        assertTrue(device.wait(Until.hasObject(By.text("Movie.en")), 10000))
        assertFalse(device.hasObject(By.text("Movie.en.srt")))
        assertFalse(device.hasObject(By.text("Subtitle files in this folder")))
        assertFalse(device.hasObject(By.text("Built-in subtitles")))
        val off = device.findObject(By.text("Off")).visibleBounds
        val folder = device.findObject(By.text("Movie.en")).visibleBounds
        val second = device.findObject(By.text("Movie.es")).visibleBounds
        val loaded = device.findObject(By.text("English")).visibleBounds
        assertTrue(off.bottom < folder.top)
        assertTrue(folder.bottom < second.top)
        assertTrue(second.bottom < loaded.top)
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-subtitle-sheet.png"))
        device.findObject(By.text("Off")).click()
        assertTrue(device.hasObject(By.text("Subtitles")))
        device.executeShellCommand("settings put system font_scale 1.3")
        Thread.sleep(1200)
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-subtitle-sheet-large-font.png"))
        device.executeShellCommand("settings put system font_scale 1.0")
    }

    @Test fun disconnectedShowsOnlyOffNoErrorNoRetry() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "subtitles").putExtra("statuscase", "cur-g"))
        assertTrue(device.wait(Until.hasObject(By.text("Subtitles")), 10000))
        assertTrue(device.wait(Until.hasObject(By.text("Off")), 10000))
        // Let the first folder check fail and settle (no PC in this state), then wait
        // out another quiet poll cycle to catch any delayed error line.
        device.wait(Until.gone(By.text("Checking subtitle files...")), 15000)
        Thread.sleep(3500)
        // Owner's rule: disconnected shows Off like before folder subtitles existed.
        assertTrue(device.hasObject(By.text("Off")))
        assertFalse(device.hasObject(By.textContains("Folder subtitles")))
        assertFalse(device.hasObject(By.text("Retry")))
        assertFalse(device.hasObject(By.text("Checking subtitle files...")))
        assertFalse(device.hasObject(By.text("No subtitle tracks or files found.")))
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-subtitle-sheet-disconnected.png"))
    }
}
