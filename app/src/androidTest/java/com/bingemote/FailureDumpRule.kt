package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * Diagnostic only: on any assertion failure, capture ground truth about what the
 * device actually shows - window hierarchy, resumed activity, recent app logcat,
 * and a screenshot - into the app's external files dir for the workflow to pull.
 */
class FailureDumpRule : TestWatcher() {
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
