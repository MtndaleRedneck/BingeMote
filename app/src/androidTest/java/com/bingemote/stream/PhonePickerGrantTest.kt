package com.bingemote.stream

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end SAF grant flow as the APP uid with the REAL system picker, and it cannot skip:
 * Movies -> Phone origin -> Pick folder opens DocumentsUI on a fixture folder of the emulator's
 * own shared storage (DocumentsUI opens there reliably through EXTRA_INITIAL_URI; the roots
 * drawer is not reachable on this image, CI-verified), the test taps USE THIS FOLDER and Allow,
 * and the listing must render through the persisted picker grant.
 *
 * This test ESTABLISHES the persisted grant (and leaves the fixture folder and the grant in
 * place) that PhoneMoviesAndroidTest then lists. Every step either happens or fails the test.
 */
@RunWith(AndroidJUnit4::class)
class PhonePickerGrantTest {
    companion object {
        const val DIR = "/sdcard/Documents/ParityMoviesSaf"
        const val DOC_ID = "primary:Documents/ParityMoviesSaf"
    }

    @Test
    fun pickerGrantListsMoviesAsApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val pkg = instrumentation.targetContext.packageName
        val resolver = instrumentation.targetContext.contentResolver
        // Fresh state: a grant left by an earlier class would hide the prompt.
        resolver.persistedUriPermissions.forEach {
            resolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        com.bingemote.Store(instrumentation.targetContext).moviesPhoneTreeUri = ""
        fun shot(tag: String) { device.executeShellCommand("screencap -p /sdcard/parity-saf-grant-$tag.png") }
        device.executeShellCommand("rm -rf $DIR")
        device.executeShellCommand("mkdir -p $DIR/Documentaries")
        // No spaces or quotes in fixture names: executeShellCommand does not quote (the first CI attempt created nothing).
        device.executeShellCommand("dd if=/dev/zero of=$DIR/The_Movie_A_2024.mkv bs=1234 count=1")
        device.executeShellCommand("dd if=/dev/zero of=$DIR/The_Movie_A_2024.srt bs=100 count=1")
        device.executeShellCommand("dd if=/dev/zero of=$DIR/Other_Film.mp4 bs=5678 count=1")
        device.executeShellCommand("dd if=/dev/zero of=$DIR/Unrelated.srt bs=100 count=1")
        val made = device.executeShellCommand("ls -l $DIR")
        assertTrue("fixture files were not created: $made", made.contains("The_Movie_A_2024.mkv") && made.contains("Other_Film.mp4") && made.contains("Unrelated.srt"))
        try {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("am start -n $pkg/com.bingemote.MainActivity --es screen movies" +
                " --es pickeruri content://com.android.externalstorage.documents/document/primary%3ADocuments%2FParityMoviesSaf")
            assertNotNull("Movies screen did not come up", device.wait(Until.findObject(By.text("Movies")), 10000))
            device.findObject(By.text("Phone"))?.click() ?: run { shot("no-origin"); fail("Phone origin segment not found") }
            val pick = device.wait(Until.findObject(By.text("Pick folder")), 10000)
            if (pick == null) { shot("no-pick"); fail("Pick folder prompt not shown on a fresh state") }
            pick.click()
            // Gate on the fixture's own child so the storage root's USE THIS FOLDER cannot be mistaken for ours.
            if (device.wait(Until.findObject(By.text("Documentaries")), 15000) == null) { shot("not-at-fixture"); fail("system picker did not open on the fixture folder") }
            shot("picker")
            val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 10000)
            if (use == null) { shot("no-use"); fail("USE THIS FOLDER not shown by the system picker") }
            use.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)^allow$"))), 5000)?.click()
            // The listing must render as the app uid: the folder and the movie rows.
            if (device.wait(Until.findObject(By.text("Documentaries")), 15000) == null) { shot("after"); fail("phone listing did not render after the picker grant") }
            if (device.wait(Until.findObject(By.textContains("The_Movie_A_2024")), 8000) == null) { shot("no-movie"); fail("movie row not listed after the picker grant") }
            shot("listed")
            val persisted = resolver.persistedUriPermissions.any {
                it.uri.toString().contains("externalstorage") && it.uri.toString().contains("ParityMoviesSaf") && it.isReadPermission }
            assertTrue("no persisted tree grant for the fixture folder on the app package", persisted)
        } finally {
            device.executeShellCommand("am force-stop com.android.documentsui")
            device.pressHome()
        }
    }
}
