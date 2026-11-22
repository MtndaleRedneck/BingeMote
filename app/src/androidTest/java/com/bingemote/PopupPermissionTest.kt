package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test

/** Notification-permission popup, isolated from the main suite. The denied
 * notification state is set up from OUTSIDE the app process (workflow: force-stop,
 * revoke, clear flags, then run this class) because revoking a granted runtime
 * permission SIGKILLs any running app process, and instrumented tests run inside
 * it - a revoke from a test kills its own runner (one run aborted that way: logcat
 * Zygote signal 9, 1.5s after the test started). This class never changes
 * permission state itself; it measures and asserts, and fails as a SETUP failure
 * when the expected state is missing. */
class PopupPermissionTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    @Test fun notificationSettingPermissionPopup() {
        val d=UiDevice.getInstance(inst);val s=Store(ctx)
        d.pressHome()
        // The production gate is the toggle turning on while
        // NotificationManagerCompat.areNotificationsEnabled() is false. sdk>=33 has
        // the runtime permission the workflow revokes; below 33 there is no runtime
        // permission and the POST_NOTIFICATION app op did not move the master state
        // in CI, so the denied-state popup path is UNEXERCISED there.
        val sdk=android.os.Build.VERSION.SDK_INT
        s.setupDone=true;s.lockScreenControls=false
        val again=InstrumentationRegistry.getArguments().getString("again")=="yes"
        ctx.getSharedPreferences("notification_permission",0).edit().putBoolean("asked",again).commit()
        val allowedPre=MediaControls.allowed(ctx)
        android.util.Log.i("BMSeek","popup-test sdk=$sdk allowedPre=$allowedPre")
        if(sdk<33 && allowedPre)
            android.util.Log.i("BMSeek","popup-test sdk$sdk denied-state popup path UNEXERCISED")
        assertFalse("setup: workflow must revoke POST_NOTIFICATIONS before this class runs; notifications still enabled on sdk$sdk",
            sdk>=33 && allowedPre)
        inst.runOnMainSync { ctx.startActivity(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val settings=d.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.desc("Settings")),10_000)
        if(settings==null) {
            d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"settings-entry-failure.png"))
            d.dumpWindowHierarchy(java.io.File(ctx.getExternalFilesDir(null),"settings-entry-failure.xml"))
        }
        assertNotNull("Settings button after Home/activity launch",settings);settings.click()
        // Bounded wait, not a bare findObject: waitForIdle can return during the
        // activity transition, and this assert has failed with no container while
        // other runs reached the label or passed (runs 45019910407 vs 45018522227).
        val scroll=d.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.scrollable(true)),10_000)
        if(scroll==null) {
            d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"settings-scroll-failure-$again-sdk$sdk.png"))
            d.dumpWindowHierarchy(java.io.File(ctx.getExternalFilesDir(null),"settings-scroll-failure-$again-sdk$sdk.xml"))
        }
        assertNotNull("Settings scroll container (hierarchy+shot dumped)",scroll)
        val target=androidx.test.uiautomator.By.text("Notification shade controls")
        scroll.setGestureMargin(d.displayWidth/8)
        scroll.scrollUntil(androidx.test.uiautomator.Direction.DOWN,androidx.test.uiautomator.Until.findObject(target))
        d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"settings-labels-$again-sdk$sdk.png"))
        d.dumpWindowHierarchy(java.io.File(ctx.getExternalFilesDir(null),"settings-labels-$again-sdk$sdk.xml"))
        assertFalse("settings list shows the 'No headset controls' hint (pair state leaked across legs?)",d.hasObject(androidx.test.uiautomator.By.textContains("No headset controls")))
        assertFalse("toggle must start OFF",Store(ctx).lockScreenControls)
        val toggle=d.findObject(androidx.test.uiautomator.By.text("Notification shade controls"));assertNotNull("toggle row 'Notification shade controls' not found after scrolling to it",toggle);toggle.click()
        val popupUp=d.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Enable notification controls?")),5_000)
        val flipped=Store(ctx).lockScreenControls
        assertTrue("toggle click did not flip the stored setting",flipped)
        if(!allowedPre) {
            // Gate conditions held at the click: toggle on + notifications off -> popup required.
            if(!popupUp) {
                val focus=d.executeShellCommand("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
                d.dumpWindowHierarchy(java.io.File(ctx.getExternalFilesDir(null),"popup-failure-$again-sdk$sdk.xml"))
                d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"popup-failure-$again-sdk$sdk.png"))
                android.util.Log.i("BMSeek","popup missing with gate satisfied: allowedPre=$allowedPre focus=$focus")
            }
            assertTrue("popup missing although gate held at click: allowedPre=false (hierarchy+shot dumped)",popupUp)
            assertTrue("popup screenshot write failed",d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"notification-permission-$again-sdk$sdk.png")))
            d.findObject(androidx.test.uiautomator.By.text("Enable notifications")).click()
            // Production helper: below 33 always app notification settings; sdk>=33 asks
            // once (permission controller) and opens settings when asked before.
            val expectPermissionController=sdk>=33 && !again
            val pkg=if(expectPermissionController) "com.google.android.permissioncontroller" else "com.android.settings"
            assertTrue("expected $pkg after Enable notifications (sdk=$sdk again=$again)",
                d.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(pkg)),8_000))
        } else {
            // Notifications enabled at the click: production suppresses the popup; the
            // toggle transition above is the behavior under test in this case.
            assertFalse("popup shown although notifications were enabled at the click (allowedPre=true)",popupUp)
        }
        s.lockScreenControls=false
        d.pressBack();d.pressHome()
    }

    // AGP uninstalls the app when each connectedDebugAndroidTest leg ends, wiping
    // getExternalFilesDir - a workflow-side pull after the leg finds nothing (seen
    // in run 45021496277: adb stat error). Stash the dumps to public Download from
    // the test (shell user can read the app dir and write there) before that.
    @org.junit.After fun stashDiag() {
        val dir = ctx.getExternalFilesDir(null) ?: return
        val d2 = UiDevice.getInstance(inst)
        // Two plain commands, no shell compounds: executeShellCommand in this rig
        // does not interpret shell operators (documented in CancelGestureTest),
        // so a compound mkdir&&copy could silently do nothing. The copy output is
        // logged so a silent stash failure is visible in the run log.
        d2.executeShellCommand("mkdir -p /sdcard/Download/bm-diag")
        val out = d2.executeShellCommand("cp -r " + dir.absolutePath + "/. /sdcard/Download/bm-diag/")
        val ls = d2.executeShellCommand("ls /sdcard/Download/bm-diag")
        android.util.Log.i("BMSeek", "popup-test diag stash: cp='${out.trim()}' files='${ls.trim()}'")
    }
}
