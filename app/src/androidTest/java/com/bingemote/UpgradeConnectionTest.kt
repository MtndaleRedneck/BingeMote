package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Runs against signed release 1.12.4, then signed release 1.12.6 after adb install -r.
 * Test-only fixed credentials, never the user's data. No DEBUG seeding hooks used. */
class UpgradeConnectionTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val token = "upgrade-fixture-token"
    private val password = "upgrade-fixture-password"
    private class Pc(private val token: String, private val password: String, private val helperCode: Int = 200, private val vlcCode: Int = 200) : AutoCloseable {
        val helper = AtomicInteger(); val vlc = AtomicInteger()
        private val servers = listOf(18488,18080).map { ServerSocket(it, 50, InetAddress.getByName("127.0.0.1")) }
        @Volatile private var live = true
        init { servers.forEachIndexed { index, server -> thread(isDaemon=true) {
            while(live) try { val socket=server.accept(); thread(isDaemon=true) {
                socket.use { sock ->
                    val reader=sock.getInputStream().bufferedReader(); val request=reader.readLine() ?: return@use
                    val headers=mutableMapOf<String,String>()
                    while(true) { val h=reader.readLine() ?: break; if(h.isEmpty()) break; headers[h.substringBefore(':').lowercase()]=h.substringAfter(':').trim() }
                    val good=if(index==0) headers["x-bingemote-token"]==token else headers["authorization"]=="Basic "+android.util.Base64.encodeToString(":$password".toByteArray(),android.util.Base64.NO_WRAP)
                    val body=if(!good) "{}" else if(index==0) {
                        helper.incrementAndGet(); if(request.contains("/volume/get")) """{"volume":50,"mute":false}""" else """{"ok":true}"""
                    } else { vlc.incrementAndGet(); """{"time":30,"length":3600,"volume":128,"state":"playing","information":{"category":{"meta":{"filename":"fixture.mkv"}}}}""" }
                    val code = if(!good) 401 else if(index==0) helperCode else vlcCode
                    val bytes=body.toByteArray();sock.getOutputStream().write(("HTTP/1.1 "+"$code Fixture"+"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
                }
            } } catch (_:Exception) { }
        } } }
        override fun close() { live=false; servers.forEach { it.close() } }
    }
    @Test fun seedOldRelease() {
        val s=Store(ctx); s.pcName="Upgrade Fixture PC";s.host="127.0.0.1";s.helperPort=18488;s.vlcPort=18080;s.helperToken=token;s.vlcPassword=password;s.setupDone=true;s.lockScreenControls=true;s.demoControls=false
        Pc(token,password).use {
            assertTrue("old release helper auth",HelperClient.ping(s.host,s.helperPort,s.helperToken))
            assertTrue("old release VLC auth",Vlc.status(s.host,s.vlcPort,s.vlcPassword).playing)
        }
        // Sync disk before the package replacement. Values remain encrypted at rest.
        ctx.getSharedPreferences("settings",0).edit().putString("upgradeFixture","seeded124").commit()
        Thread.sleep(500)
        println("UPGRADE: prior release seeded ordinary+encrypted preferences; helper/VLC auth passed")
    }
    @Test fun verifyNewReleaseAfterUpdate() {
        val s=Store(ctx)
        assertEquals("Upgrade Fixture PC",s.pcName);assertEquals("127.0.0.1",s.host)
        assertEquals(18488,s.helperPort);assertEquals(18080,s.vlcPort)
        assertEquals("encrypted helper token retained",token,s.helperToken)
        assertEquals("encrypted VLC password retained",password,s.vlcPassword)
        assertTrue(s.setupDone);assertTrue(s.lockScreenControls);assertTrue(s.isConfigured)
        assertEquals("seeded124",ctx.getSharedPreferences("settings",0).getString("upgradeFixture",null))
        Pc(token,password).use { pc ->
            assertTrue("updated helper auth",HelperClient.ping(s.host,s.helperPort,s.helperToken))
            assertTrue("updated VLC auth",Vlc.status(s.host,s.vlcPort,s.vlcPassword).playing)
            val device=UiDevice.getInstance(inst)
            for(granted in listOf(InstrumentationRegistry.getArguments().getString("permission") == "granted")) {
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
                val remote=RemoteState(ctx,s,scope)
                try {
                    runBlocking { remote.pollOnce();remote.pollOnce() }
                    assertFalse("poll connects with notification permission=$granted",remote.unreachable)
                    assertFalse(remote.helperDown);assertFalse(remote.unpaired)
                    assertEquals("fixture.mkv",remote.status?.title)
                    inst.runOnMainSync { ctx.startActivity(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    val h=pc.helper.get(); val v=pc.vlc.get();Thread.sleep(6_000)
                    assertTrue("activity helper poll continues permission=$granted",pc.helper.get()>h)
                    assertTrue("activity VLC poll continues permission=$granted",pc.vlc.get()>v)
                    println("UPGRADE: retained credentials authenticate; activity helper+VLC polls permission=$granted")
                    device.pressHome();Thread.sleep(500)
                } finally { scope.cancel() }
            }
        }
    }

    @Test fun notificationSettingPermissionPopup() {
        val d=UiDevice.getInstance(inst);val s=Store(ctx)
        d.pressHome()
        // The production gate is the toggle turning on while
        // NotificationManagerCompat.areNotificationsEnabled() is false. Establish that
        // predicate first: sdk>=33 has the runtime permission; below 33 the master
        // toggle is the POST_NOTIFICATION app op (no S). If neither lands, the
        // denied-state branch is unexercised, not passed. Force-stop first: revoking a
        // granted runtime permission SIGKILLs the running app (logcat: Zygote signal 9
        // 1.5s after this test started), which aborts the whole instrumentation run.
        val sdk=android.os.Build.VERSION.SDK_INT
        d.executeShellCommand("am force-stop com.bingemote")
        val revokeOut=if(sdk>=33)
            d.executeShellCommand("pm revoke com.bingemote android.permission.POST_NOTIFICATIONS").trim()
        else
            d.executeShellCommand("cmd appops set com.bingemote POST_NOTIFICATION ignore").trim()
        s.setupDone=true;s.lockScreenControls=false
        val again=InstrumentationRegistry.getArguments().getString("again")=="yes"
        ctx.getSharedPreferences("notification_permission",0).edit().putBoolean("asked",again).commit()
        val allowedPre=MediaControls.allowed(ctx)
        android.util.Log.i("BMSeek","popup-test sdk=$sdk allowedPre=$allowedPre revokeOut='$revokeOut'")
        assertFalse("setup: notifications still enabled after revoke on sdk$sdk revokeOut='$revokeOut'",
            sdk>=33 && allowedPre)
        if(sdk<33 && allowedPre)
            android.util.Log.i("BMSeek","popup-test sdk$sdk denied-state popup path UNEXERCISED: appops could not disable notifications")
        inst.runOnMainSync { ctx.startActivity(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val settings=d.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.desc("Settings")),10_000)
        if(settings==null) {
            d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"settings-entry-failure.png"))
            d.dumpWindowHierarchy(java.io.File(ctx.getExternalFilesDir(null),"settings-entry-failure.xml"))
        }
        assertNotNull("Settings button after Home/activity launch",settings);settings.click()
        d.waitForIdle()
        val scroll=d.findObject(androidx.test.uiautomator.By.scrollable(true))
        assertNotNull("Settings scroll container",scroll)
        val target=androidx.test.uiautomator.By.text("Notification shade controls")
        scroll.setGestureMargin(d.displayWidth/8)
        scroll.scrollUntil(androidx.test.uiautomator.Direction.DOWN,androidx.test.uiautomator.Until.findObject(target))
        d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"settings-labels-$again.png"))
        d.dumpWindowHierarchy(java.io.File(ctx.getExternalFilesDir(null),"settings-labels-$again.xml"))
        assertFalse(d.hasObject(androidx.test.uiautomator.By.textContains("No headset controls")))
        assertFalse("toggle must start OFF",Store(ctx).lockScreenControls)
        val toggle=d.findObject(androidx.test.uiautomator.By.text("Notification shade controls"));assertNotNull(toggle);toggle.click()
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
            assertTrue(d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"notification-permission-$again-sdk$sdk.png")))
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
        if(sdk<33) d.executeShellCommand("cmd appops set com.bingemote POST_NOTIFICATION allow")
        d.pressBack();d.pressHome()
    }

}
