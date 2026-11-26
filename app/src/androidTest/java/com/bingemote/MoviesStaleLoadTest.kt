package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * F3 call-site proof (real MoviesScreen, real loadVlc, real LoadGate): the PC list load is still in flight when the
 * user switches to Phone. The late PC answer must NOT overwrite the Phone screen (items, loading, error).
 * Deterministic: the local VLC stub HOLDS the browse.json reply on a latch the test releases, so the order is
 * (1) PC load starts, (2) Phone selected, (3) late PC reply is delivered, (4) screen must still be the Phone state.
 * Without the gate the late reply would put "Folder 01" rows over the Phone screen.
 */
class MoviesStaleLoadTest {
    @get:Rule val failureDump = FailureDumpRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private class HeldVlc(val release: CountDownLatch) : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val browseRequests = AtomicInteger()
        val browseHeld = CountDownLatch(1)
        private fun dirJson(): String {
            val e = (1..5).joinToString(",") { """{"type":"dir","name":"Folder 0$it","path":"C:\\Movies\\$it","uri":"file:///x","size":0}""" }
            return """{"element":[$e]}"""
        }
        fun start() = thread {
            while (running.get()) {
                try {
                    val s = server.accept()
                    thread {
                        try {
                            val r = BufferedReader(InputStreamReader(s.getInputStream()))
                            val line = r.readLine() ?: ""
                            while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
                            val body = if (line.contains("browse.json")) {
                                browseRequests.incrementAndGet(); browseHeld.countDown()
                                release.await(60, TimeUnit.SECONDS) // the late reply
                                dirJson()
                            } else """{"time":0,"length":0,"volume":256,"state":"stopped","information":{"category":{"meta":{}}}}"""
                            val o = s.getOutputStream()
                            o.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                            o.flush(); s.close()
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
        override fun close() { running.set(false); release.countDown(); server.close() }
    }

    @Test fun lateVlcListingNeverOverwritesThePhoneScreenAfterSwitching() {
        val release = CountDownLatch(1)
        HeldVlc(release).use { stub ->
            stub.start()
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            Store(ctx).moviesPath = "C:\\Movies"
            Store(ctx).moviesPhoneTreeUri = "" // no phone folder picked: the Phone screen is the "Pick the Android movies folder first." state
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val btn = device.wait(Until.findObject(By.desc("Movies")), 20000)
            assertNotNull("Home Movies button", btn)
            btn.click()
            assertTrue("the PC list load is in flight (stub holds the reply)", stub.browseHeld.await(15, TimeUnit.SECONDS))
            assertTrue("loading shown", device.wait(Until.hasObject(By.textStartsWith("Loading")), 10000))

            val phone = device.wait(Until.findObject(By.text("Phone")), 10000)
            assertNotNull("PC | Phone selector shows Phone", phone)
            phone.click()
            assertTrue("Phone screen state shown", device.wait(Until.hasObject(By.text("Pick the Android movies folder first.")), 10000))
            assertEquals("exactly one held PC browse so far", 1, stub.browseRequests.get())

            release.countDown() // the late PC reply is delivered now
            Thread.sleep(2500) // let it arrive and (wrongly) render, if it were going to
            device.waitForIdle()
            assertNull("late PC rows must not appear over the Phone screen", device.findObject(By.text("Folder 01")))
            assertNotNull("Phone screen state still shown", device.findObject(By.text("Pick the Android movies folder first.")))
            assertNull("no Loading left over the Phone screen", device.findObject(By.textStartsWith("Loading")))
        }
    }
}
