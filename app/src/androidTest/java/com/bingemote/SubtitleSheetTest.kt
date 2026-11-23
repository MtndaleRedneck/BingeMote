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
    }
}
