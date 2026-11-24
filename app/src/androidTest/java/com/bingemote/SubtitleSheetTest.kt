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

class SubtitleSheetTest {
    @get:Rule val failureDump = FailureDumpRule()
    @Test fun offThenFolderThenLoadedWithExtensionsHidden() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "subtitles").putExtra("subtitlepreview", true).putExtra("statuscase", "cur-e"))
        assertTrue(device.wait(Until.hasObject(By.text("Movie.en")), 30000))
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
        assertTrue(device.wait(Until.hasObject(By.text("Subtitles")), 30000))
        assertTrue(device.wait(Until.hasObject(By.text("Off")), 30000))
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

    @Test fun losingConnectionWithSheetOpenCollapsesToOnlyOff() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Rig: sheet opens connected with folder files and embedded tracks visible,
        // then the connection drops 2.5s in (frozen polling keeps it dropped).
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "subtitles").putExtra("statuscase", "cur-e")
            .putExtra("subtitlepreviewlost", true))
        // Connected starting state really shows the content and its dividers first.
        assertTrue(device.wait(Until.hasObject(By.text("Movie.en")), 30000))
        assertTrue(device.hasObject(By.text("English")))
        // After the drop: everything below Off must go, including the stale tracks
        // the last status still carries.
        assertTrue(device.wait(Until.gone(By.text("Movie.en")), 30000))
        Thread.sleep(1000)
        assertTrue(device.hasObject(By.text("Off")))
        assertFalse(device.hasObject(By.text("Movie.en")))
        assertFalse(device.hasObject(By.text("Movie.es")))
        assertFalse(device.hasObject(By.text("English")))
        assertFalse(device.hasObject(By.text("Spanish")))
        assertFalse(device.hasObject(By.textContains("Folder subtitles")))
        assertFalse(device.hasObject(By.text("Retry")))
        assertFalse(device.hasObject(By.text("Checking subtitle files...")))
        assertFalse(device.hasObject(By.text("No subtitle tracks or files found.")))
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-subtitle-sheet-lost.png"))
    }

    @Test fun staleFolderErrorClearedByNextFailedCheck() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Connected sheet (helper not actually answering here) with a leftover list
        // message seeded 3s in, after the opening check. The next failing quiet
        // check must clear it: no stale error line, no Retry.
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "subtitles").putExtra("statuscase", "cur-e")
            .putExtra("subtitleseedstaleerror", true))
        assertTrue(device.wait(Until.hasObject(By.text("Off")), 30000))
        assertTrue(device.wait(Until.hasObject(By.text("Can't check subtitle files right now.")), 30000))
        assertTrue(device.hasObject(By.text("Retry")))
        assertTrue(device.wait(Until.gone(By.text("Can't check subtitle files right now.")), 45000))
        assertFalse(device.hasObject(By.text("Retry")))
        assertTrue(device.hasObject(By.text("Off")))
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-subtitle-sheet-stale-cleared.png"))
    }

    @Test fun helperDownWithVlcUpShowsOnlyOff() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // VLC reachable, helper not answering (prod-e): subtitle picks cannot
        // complete, so the sheet shows only Off - no tracks, folder rows, error,
        // Retry or dividers, matching the owner's disconnected rule.
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", "subtitles").putExtra("subtitlepreview", true).putExtra("statuscase", "prod-e"))
        assertTrue(device.wait(Until.hasObject(By.text("Off")), 30000))
        Thread.sleep(1500)
        assertTrue(device.hasObject(By.text("Off")))
        assertFalse(device.hasObject(By.text("Movie.en")))
        assertFalse(device.hasObject(By.text("Movie.es")))
        assertFalse(device.hasObject(By.text("English")))
        assertFalse(device.hasObject(By.text("Spanish")))
        assertFalse(device.hasObject(By.text("Retry")))
        assertFalse(device.hasObject(By.text("Checking subtitle files...")))
        device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-subtitle-sheet-helperdown.png"))
    }
}
