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

}
