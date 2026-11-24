package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pre-finally evidence for precondition (service/poll) timeouts: captures the
 * actual state AT the failure point, before the test's finally block tears the
 * service and hooks down. A post-teardown rule dump cannot show this state.
 * Assertions are untouched - the capture runs on the timeout path and the
 * original assertion still fails red. Every section is best-effort: an absent
 * section is no-information, never claimed as evidence either way.
 */
object PreconditionDiag {
    fun capture(tag: String, what: String, waitStartedAt: Long, extras: String = "") {
        runCatching {
            val inst = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(inst)
            val dir = inst.targetContext.getExternalFilesDir(null) ?: return
            val now = System.currentTimeMillis()
            val anchor = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(waitStartedAt))
            val sb = StringBuilder()
            sb.append("precondition timeout: ").append(what).append('\n')
            sb.append("waitStartedAt=").append(waitStartedAt)
                .append(" capturedAt=").append(now)
                .append(" waitedMs=").append(now - waitStartedAt).append('\n')
            if (extras.isNotEmpty()) sb.append("extras: ").append(extras).append('\n')
            runCatching {
                sb.append("== services ==\n")
                sb.append(device.executeShellCommand("dumpsys activity services com.bingemote"))
            }
            runCatching {
                sb.append("== process state ==\n")
                sb.append(device.executeShellCommand("dumpsys activity lru | grep -i bingemote"))
            }
            runCatching {
                sb.append("== keyguard ==\n")
                sb.append(device.executeShellCommand("dumpsys keyguard | grep -iE 'mShowing|mOccluded' | head -15"))
            }
            runCatching {
                sb.append("== our active notifications ==\n")
                sb.append(device.executeShellCommand("dumpsys notification --noredact | grep -i bingemote | head -30"))
            }
            runCatching {
                sb.append("== anr dir ==\n")
                sb.append(device.executeShellCommand("ls -l /data/anr/ 2>/dev/null"))
            }
            runCatching {
                // Anchored at the wait's own start with UiDevice selector spam
                // filtered: the window covers the precondition period itself. The
                // failure rule's 250-line tail covered only ~3-22s, mostly polling.
                sb.append("== logcat since wait start ==\n")
                sb.append(device.executeShellCommand("logcat -d -T '$anchor' | grep -v UiDevice"))
            }
            runCatching { device.takeScreenshot(File(dir, "bm-precond-$tag.png")) }
            File(dir, "bm-precond-$tag.txt").writeText(sb.toString())
        }
    }
}
