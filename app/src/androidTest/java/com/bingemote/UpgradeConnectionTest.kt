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

/** Runs against signed release 1.12.4, then signed release 1.12.6 after adb install -r.
 * Test-only fixed credentials, never the user's data. No DEBUG seeding hooks used. */
class UpgradeConnectionTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val token = "upgrade-fixture-token"
    private val password = "upgrade-fixture-password"
    @Suppress("DEPRECATION_ERROR")
    private class Pc(private val token: String, private val password: String, private val helperCode: Int = 200, private val vlcCode: Int = 200) : AutoCloseable {
        val helper = AtomicInteger(); val vlc = AtomicInteger()
        // Minified-release boundary discipline: this fixture may only reference java.*,
        // androidx/kotlinx kept API, and kept app classes. kotlin.collections/text/io/
        // concurrent statics bind to package-private multifile part classes that the app
        // R8 merges or renames; applymapping cannot bind members that have no surviving
        // row (run 45382268903 NSME listOf), and pinning part names splits the
        // package-private superclass chain across packages (run 45386135883
        // IllegalAccessError at app startup). Plain java.* equivalents below carry none
        // of those references; inline-only stdlib extensions are fine (no static ref).
        private fun utf8(s: String): ByteArray {
            val bb = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(s))
            return java.util.Arrays.copyOf(bb.array(), bb.remaining())
        }
        private fun jtrim(s: String): String { var a=0; var b=s.length; while(a<b && s[a]<=' ') a++; while(b>a && s[b-1]<=' ') b--; return s.substring(a,b) }
        private fun jindexOf(s: String, c: Char): Int { var i=0; while(i<s.length) { if(s[i]==c) return i; i++ }; return -1 }
        private fun jindexOf(s: String, needle: String): Int { val n=needle.length; if(n==0) return 0; val lim=s.length-n; var i=0; while(i<=lim) { var j=0; while(j<n && s[i+j]==needle[j]) j++; if(j==n) return i; i++ }; return -1 }
        private val servers = java.util.ArrayList<ServerSocket>().also { it.add(ServerSocket(18488,50,InetAddress.getByName("127.0.0.1"))); it.add(ServerSocket(18080,50,InetAddress.getByName("127.0.0.1"))) }
        @Volatile private var live = true
        init { var index=0; for(server in servers) { val i=index++; Thread {
            while(live) try { val socket=server.accept(); Thread {
                var cause: Throwable? = null
                try {
                    val reader=java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); val request=reader.readLine()
                    if(request!=null) {
                        val headers=java.util.LinkedHashMap<String,String>()
                        while(true) { val h=reader.readLine() ?: break; if(h.isEmpty()) break; val ci=jindexOf(h,':'); headers[if(ci>=0) h.substring(0,ci).toLowerCase(java.util.Locale.ROOT) else h.toLowerCase(java.util.Locale.ROOT)]=if(ci>=0) jtrim(h.substring(ci+1)) else "" }
                        val good=if(i==0) headers["x-bingemote-token"]==token else headers["authorization"]=="Basic "+android.util.Base64.encodeToString(utf8(":"+password),android.util.Base64.NO_WRAP)
                        val body=if(!good) "{}" else if(i==0) {
                            helper.incrementAndGet(); if(jindexOf(request,"/volume/get")>=0) """{"volume":50,"mute":false}""" else """{"ok":true}"""
                        } else { vlc.incrementAndGet(); """{"time":30,"length":3600,"volume":128,"state":"playing","information":{"category":{"meta":{"filename":"fixture.mkv"}}}}""" }
                        val code = if(!good) 401 else if(i==0) helperCode else vlcCode
                        val bytes=utf8(body);val out=socket.getOutputStream();out.write(utf8("HTTP/1.1 "+"$code Fixture"+"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"));out.write(bytes)
                    }
                } catch (e: Throwable) { cause = e; throw e
                } finally { if(cause==null) socket.close() else try { socket.close() } catch (ce: Throwable) { } }
            }.also { it.isDaemon=true; it.start() } } catch (_:Exception) { }
        }.also { it.isDaemon=true; it.start() } } }
        override fun close() { live=false; servers.forEach { it.close() } }
    }
    @Test fun seedOldRelease() {
        val s=Store(ctx); s.pcName="Upgrade Fixture PC";s.host="127.0.0.1";s.helperPort=18488;s.vlcPort=18080;s.helperToken=token;s.vlcPassword=password;s.setupDone=true;s.lockScreenControls=true;s.demoControls=false
        val pc=Pc(token,password)
        var pcCause: Throwable? = null
        try {
            assertTrue("old release helper auth",HelperClient.ping(s.host,s.helperPort,s.helperToken))
            assertTrue("old release VLC auth",Vlc.status(s.host,s.vlcPort,s.vlcPassword).playing)
        } catch (e: Throwable) { pcCause = e; throw e
        } finally { if(pcCause==null) pc.close() else try { pc.close() } catch (ce: Throwable) { } }
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
        val pc=Pc(token,password)
        var pcCause: Throwable? = null
        try {
            assertTrue("updated helper auth",HelperClient.ping(s.host,s.helperPort,s.helperToken))
            assertTrue("updated VLC auth",Vlc.status(s.host,s.vlcPort,s.vlcPassword).playing)
            val device=UiDevice.getInstance(inst)
            for(granted in java.util.Collections.singletonList(InstrumentationRegistry.getArguments().getString("permission") == "granted")) {
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
        } catch (e: Throwable) { pcCause = e; throw e
        } finally { if(pcCause==null) pc.close() else try { pc.close() } catch (ce: Throwable) { } }
    }

    @Test fun notificationSettingPermissionPopup() {
        val d=UiDevice.getInstance(inst);val s=Store(ctx)
        d.pressHome()
        s.setupDone=true;s.lockScreenControls=false
        val again=InstrumentationRegistry.getArguments().getString("again")=="yes"
        ctx.getSharedPreferences("notification_permission",0).edit().putBoolean("asked",again).commit()
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
        val toggle=d.findObject(androidx.test.uiautomator.By.text("Notification shade controls"));assertNotNull(toggle);toggle.click()
        assertTrue(d.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Enable notification controls?")),5_000))
        assertTrue(d.takeScreenshot(java.io.File(ctx.getExternalFilesDir(null),"notification-permission-$again.png")))
        d.findObject(androidx.test.uiautomator.By.text("Enable notifications")).click()
        if(again) assertTrue(d.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg("com.android.settings")),8_000))
        else assertTrue(d.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg("com.google.android.permissioncontroller")),8_000))
        d.pressBack();d.pressHome()
    }

}
