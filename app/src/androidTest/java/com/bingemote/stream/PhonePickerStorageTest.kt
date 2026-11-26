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
import com.bingemote.FailureDumpRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REAL system picker path with no custom provider: Movies -> Phone -> Pick folder opens
 * the system DocumentsUI on a folder of the emulator's own shared storage, the test
 * taps USE THIS FOLDER (and Allow), and the listing must render as the app uid through
 * the persisted grant. Unlike the seeded-provider test this needs no roots drawer, so it
 * cannot skip: every step either happens or fails the test.
 */
@RunWith(AndroidJUnit4::class)
class PhonePickerStorageTest {
    @get:Rule val failureDump = FailureDumpRule()
    @Test
    fun realPickerGrantsSharedStorageFolderAndListsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val pkg = instrumentation.targetContext.packageName
        val dir = "/sdcard/Documents/ParityMovies"
        fun shot(tag: String) { device.executeShellCommand("screencap -p /sdcard/parity-storage-picker-$tag.png") }
        device.executeShellCommand("rm -rf $dir")
        device.executeShellCommand("mkdir -p $dir/Documentaries")
        device.executeShellCommand("touch $dir/Sample\\ Film.mp4")
        try {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("am start -n $pkg/com.bingemote.MainActivity --es screen movies" +
                " --es pickeruri content://com.android.externalstorage.documents/document/primary%3ADocuments%2FParityMovies")
            assertNotNull("Movies screen did not come up", device.wait(Until.findObject(By.text("Movies")), 10000))
            device.findObject(By.text("Phone"))?.click() ?: run { shot("no-origin"); fail("Phone origin segment not found") }
            // A grant left by an earlier class would skip the prompt; the test needs a fresh pick.
            instrumentation.targetContext.contentResolver.persistedUriPermissions.forEach {
                instrumentation.targetContext.contentResolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            com.bingemote.Store(instrumentation.targetContext).moviesPhoneTreeUri = ""
            val pick = device.wait(Until.findObject(By.text("Pick folder")), 10000)
            if (pick == null) { shot("no-pick"); fail("Pick folder prompt not shown") }
            pick.click()
            shot("picker")
            val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 15000)
            if (use == null) { shot("no-use"); fail("USE THIS FOLDER not shown by the system picker") }
            use.click()
            device.wait(Until.findObject(By.text(Pattern.compile("(?i)^allow$"))), 5000)?.click()
            val folder = device.wait(Until.findObject(By.text("Documentaries")), 15000)
            if (folder == null) { shot("after"); fail("phone listing did not render after the real picker grant") }
            shot("listed")
            val persisted = instrumentation.targetContext.contentResolver.persistedUriPermissions
                .any { it.uri.toString().contains("externalstorage") && it.isReadPermission }
            assertTrue("no persisted tree grant from the real picker on the app package", persisted)
        } finally {
            device.executeShellCommand("am force-stop com.android.documentsui")
            device.executeShellCommand("rm -rf $dir")
            instrumentation.targetContext.contentResolver.persistedUriPermissions.forEach {
                instrumentation.targetContext.contentResolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            com.bingemote.Store(instrumentation.targetContext).moviesPhoneTreeUri = ""
            device.pressHome()
        }
    }
}
