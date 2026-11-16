package com.bingemote

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Before
import org.junit.Assert.assertFalse
import androidx.test.uiautomator.UiSelector
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * End-to-end proof for Ezzy's "the notification altogether doesn't work" on 1.12.2:
 * the five card buttons must REACH the PC and do their job, and the card must follow
 * the PC's state. A stub VLC on localhost records every request; the test fires each
 * visible notification button by label and asserts the exact VLC command
 * arrived. A second test 401s every poll and asserts the failure is LOUD in BMBar
 * (1.12.2 swallowed it, which is why a dead card looked fine).
 */
class BarActionsTest {
    @Before fun notificationPermission() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        device.executeShellCommand("pm grant com.bingemote android.permission.POST_NOTIFICATIONS")
    }


    private class StubVlc {
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var state = "playing"
        @Volatile var time = 30L
        @Volatile var volume = 128
        @Volatile var authFail = false
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort

        fun start() {
            thread {
                while (running.get()) {
                    try {
                        val sock = server.accept()
                        thread {
                            try {
                                val r = BufferedReader(InputStreamReader(sock.getInputStream()))
                                val line = r.readLine() ?: ""
                                while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
                                val out = sock.getOutputStream()
                                if (authFail) {
                                    val body = "{}"
                                    out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                                    out.flush(); sock.close(); return@thread
                                }
                                val query = line.substringAfter('?', "")
                                if (line.contains("command=")) {
                                    // Mutate BEFORE recording: the test wakes on the recorded
                                    // request, so the state must already be applied. The request
                                    // line ends with " HTTP/1.1", so val= needs substringBefore(' ')
                                    // as well as '&' - without it toLongOrNull fails and the seek
                                    // silently never applies (the forward seek then reads the OLD
                                    // clock and lands 5s past the expected target).
                                    when {
                                        query.contains("pl_forcepause") -> state = "paused"
                                        query.contains("pl_forceresume") -> state = "playing"
                                        query.contains("command=seek") -> query.substringAfter("val=", "").substringBefore('&').substringBefore(' ').toLongOrNull()?.let { time = it }
                                        query.contains("command=volume") -> query.substringAfter("val=", "").substringBefore('&').substringBefore(' ').toIntOrNull()?.let { volume = it }
                                    }
                                    requests += query
                                } else {
                                    requests += "status"
                                }
                                val body = """{"time":$time,"length":3600,"volume":$volume,"state":"$state","information":{"category":{"meta":{"filename":"demo.mkv"}}}}"""
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                                out.flush(); sock.close()
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        fun stop() { running.set(false); try { server.close() } catch (_: Exception) {} }
    }

    private fun waitFor(what: String, timeoutMs: Long, diag: () -> String = { "" }, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(250)
        }
        fail("timeout waiting for: $what | ${diag()}")
    }

    @Test
    fun buttonsReachStubPcAndCardFollowsState() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val stub = StubVlc(); stub.start()
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("setupDone", true)
            .putString("host", "127.0.0.1")
            .putInt("vlcPort", stub.port)
            .putBoolean("lockScreenControls", true)
            .putBoolean("demoControls", false)
            .putInt("skipMainSec", 5)
            .apply()
        Store(ctx).vlcPassword = "x"
        ctx.startService(Intent(ctx, MediaControlsService::class.java))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun bar() = nm.activeNotifications.firstOrNull { it.id == 101 }?.notification
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun captureFailure() {
            device.dumpWindowHierarchy(java.io.File("/sdcard/bar-actions-failure-ui.xml"))
            device.takeScreenshot(java.io.File("/sdcard/bar-actions-failure.png"))
        }
        fun fire(title: String) {
            device.openNotification()
            val button = device.findObject(UiSelector().description(title))
            if (!button.waitForExists(10_000)) { captureFailure(); fail("no visible custom-row button: $title"); return }
            button.click()
        }
        fun hasLabel(title: String): Boolean {
            device.openNotification()
            return device.findObject(UiSelector().description(title)).waitForExists(5_000)
        }
        try {
            waitFor("custom notification row", 20_000) { bar()?.contentView != null }
            assertFalse("no media token", bar()!!.extras.containsKey("android.mediaSession"))
            listOf("Back 5 seconds", "Volume down", "Pause", "Volume up", "Forward 5 seconds").forEach {
                if (!hasLabel(it)) { captureFailure(); fail("missing visible button: $it") }
            }
            // Pause: playing -> pl_forcepause must reach the PC.
            fire("Pause")
            waitFor("pl_forcepause at stub", 10_000) { stub.requests.any { it.contains("pl_forcepause") } }
            // The card must follow the PC's state: stub is paused, the label flips to Play.
            waitFor("label flips to Play", 15_000) { hasLabel("Play") }
            fire("Play")
            waitFor("pl_forceresume at stub", 10_000) { stub.requests.any { it.contains("pl_forceresume") } }
            waitFor("label flips to Pause", 15_000) { hasLabel("Pause") }
            // Skips use his 5s interval (absolute seek from the stub's clock).
            fire("Back 5 seconds")
            waitFor("seek back at stub", 10_000, { stub.requests.joinToString() }) { stub.requests.any { it.contains("command=seek&val=25") } }
            fire("Forward 5 seconds")
            waitFor("seek fwd at stub", 10_000, { stub.requests.joinToString() }) { stub.requests.any { it.contains("command=seek&val=30") } }
            // Volume: 128 -> 115 -> 128 in raw VLC units.
            fire("Volume down")
            waitFor("volume down at stub", 10_000, { stub.requests.joinToString() }) { stub.requests.any { it.contains("command=volume&val=115") } }
            fire("Volume up")
            waitFor("volume up at stub", 10_000, { stub.requests.joinToString() }) { stub.requests.any { it.contains("command=volume&val=128") } }
            assertFalse("row must not create a media token", bar()!!.extras.containsKey("android.mediaSession"))
        } finally {
            ctx.stopService(Intent(ctx, MediaControlsService::class.java))
            stub.stop()
        }
    }

    @Test
    fun pollFailuresAreLoud() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val stub = StubVlc(); stub.start(); stub.authFail = true
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("setupDone", true)
            .putString("host", "127.0.0.1")
            .putInt("vlcPort", stub.port)
            .putBoolean("lockScreenControls", true)
            .putBoolean("demoControls", false)
            .apply()
        Store(ctx).vlcPassword = "wrong"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("logcat -c")
        ctx.startService(Intent(ctx, MediaControlsService::class.java))
        try {
            waitFor("BMBar auth failure in logcat", 30_000) {
                val out = device.executeShellCommand("logcat -d -s BMBar:W")
                out.contains("poll auth FAILED") || out.contains("poll FAILED")
            }
        } finally {
            ctx.stopService(Intent(ctx, MediaControlsService::class.java))
            stub.stop()
        }
    }
}
