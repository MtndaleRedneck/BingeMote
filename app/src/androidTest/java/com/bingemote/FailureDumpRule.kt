package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * Diagnostic + harness-robustness rule for UI tests.
 *
 * starting(): if a system "isn't responding" dialog is present, capture it FIRST
 * (identity, timestamp, screenshot, hierarchy), then act by identity:
 *  - "Pixel Launcher ..." (identified system-app offender on the loaded CI
 *    emulator): tap "Wait", bounded to 15s total, log each dismissal.
 *  - anything else (BingeMote itself, unknown, third-party): fail the test
 *    explicitly, dialog left up; our own ANR must never be dismissed into green.
 *  - still present after the bounded window: fail explicitly.
 * Every capture lands in the app's external files dir and is pulled by the
 * workflow, so a recovered run keeps before/after/identity/timing evidence.
 *
 * failed(): capture ground truth (resumed activity, focus, logcat, screenshot,
 * hierarchy) at the moment of failure.
 */
class FailureDumpRule : TestWatcher() {
    override fun starting(description: Description) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        val dir = inst.targetContext.getExternalFilesDir(null) ?: return
        val tag = description.methodName ?: "unknown"
        val t0 = System.currentTimeMillis() // rule/test start, not app launch
        var n = 0
        while (System.currentTimeMillis() - t0 < 15000) {
            val dialog = device.findObject(By.textContains("isn't responding")) ?: break
            val title = dialog.text ?: "unknown"
            val pkg = dialog.applicationPackage ?: "unknown"
            n += 1
            // BEFORE capture for every dialog seen at test start, any identity.
            runCatching { device.takeScreenshot(File(dir, "bm-andialog-$tag-$n-before.png")) }
            runCatching { device.dumpWindowHierarchy(File(dir, "bm-andialog-$tag-$n-before.uix")) }
            runCatching {
                File(dir, "bm-dismissed-dialogs.txt").appendText(
                    "test=$tag tSinceTestStart=${System.currentTimeMillis() - t0}ms title=$title pkg=$pkg\n")
            }
            // Exact identity: the known system ANR dialog for the emulator's Pixel
            // Launcher, served by the system ("android" package). Anything else -
            // BingeMote, another app, unknown title or source - fails explicitly.
            if (title != "Pixel Launcher isn't responding" || pkg != "android") {
                throw AssertionError("ANR dialog at test start, not dismissed (captured): title=$title pkg=$pkg")
            }
            device.findObject(By.text("Wait"))?.click()
            Thread.sleep(500)
        }
        device.findObject(By.textContains("isn't responding"))?.let { dialog ->
            runCatching { device.takeScreenshot(File(dir, "bm-andialog-$tag-persistent.png")) }
            throw AssertionError("ANR dialog persists after bounded dismissal: ${dialog.text}")
        }
        if (n > 0) {
            // AFTER capture once the dialog cleared, so recovery is evidenced too.
            runCatching { device.takeScreenshot(File(dir, "bm-andialog-$tag-after.png")) }
        }
    }

    override fun failed(e: Throwable?, description: Description) {
        try {
            val inst = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(inst)
            val dir = inst.targetContext.getExternalFilesDir(null) ?: return
            val tag = description.methodName ?: "unknown"
            val sb = StringBuilder()
            sb.append("failure: ").append(e?.toString() ?: "?").append('\n')
            runCatching {
                sb.append("== resumed activity ==\n")
                sb.append(device.executeShellCommand("dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity'"))
            }
            runCatching {
                sb.append("== current focus ==\n")
                sb.append(device.executeShellCommand("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"))
            }
            runCatching {
                sb.append("== recent app logcat ==\n")
                sb.append(device.executeShellCommand("logcat -d -t 250"))
            }
            File(dir, "bm-dump-$tag.txt").writeText(sb.toString())
            runCatching { device.takeScreenshot(File(dir, "bm-dump-$tag.png")) }
            runCatching { device.dumpWindowHierarchy(File(dir, "bm-dump-$tag.uix")) }
        } catch (_: Throwable) { }
    }
}
