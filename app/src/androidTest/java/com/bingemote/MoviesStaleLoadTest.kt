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
 * F3 call-site proof (real MoviesScreen, real loadVlc, real LoadGate). Two PC list loads overlap: request A (the
 * entry load) is HELD by the stub, the user goes to Phone and back to PC, which starts request B; B answers at once and its
 * rows ("Folder B1..") are on screen; THEN the held A reply is delivered. Its rows ("Folder A1..") are a different set,
 * so a late write by A is visible. Without the gate, show() would replace B's rows with A's. With the gate, A is dropped.
 * Deterministic: the order is forced by the latch, not by sleeps. (An earlier Phone-only variant was rejected in review:
 * the Phone error card masks stale rows, so it could not fail.)
 */
class MoviesStaleLoadTest {
    @get:Rule val failureDump = FailureDumpRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private class TwoRequestVlc(val releaseA: CountDownLatch) : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val browseRequests = AtomicInteger()
        val aHeld = CountDownLatch(1)
        private fun dirJson(prefix: String): String {
            val e = (1..5).joinToString(",") { """{"type":"dir","name":"Folder $prefix$it","path":"C:\\Movies\\$prefix$it","uri":"file:///x","size":0}""" }
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
                                val n = browseRequests.incrementAndGet()
                                if (n == 1) { aHeld.countDown(); releaseA.await(60, TimeUnit.SECONDS); dirJson("A") } // A: held, then the late reply
                                else dirJson("B")                                                                       // B: immediate
                            } else """{"time":0,"length":0,"volume":256,"state":"stopped","information":{"category":{"meta":{}}}}"""
                            val o = s.getOutputStream()
                            o.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                            o.flush(); s.close()
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
        override fun close() { running.set(false); releaseA.countDown(); server.close() }
    }

    @Test fun lateOlderPcListingNeverOverwritesTheNewerPcListing() {
        val releaseA = CountDownLatch(1)
        TwoRequestVlc(releaseA).use { stub ->
            stub.start()
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            Store(ctx).moviesPath = "C:\\Movies"
            Store(ctx).moviesPhoneTreeUri = ""
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val btn = device.wait(Until.findObject(By.desc("Movies")), 20000)
            assertNotNull("Home Movies button", btn)
            btn.click()
            assertTrue("request A is in flight (held by the stub)", stub.aHeld.await(15, TimeUnit.SECONDS))

            val phone = device.wait(Until.findObject(By.text("Phone")), 10000)
            assertNotNull("PC | Phone selector shows Phone", phone)
            phone.click()
            assertTrue("Phone state shown", device.wait(Until.hasObject(By.text("Pick the Android movies folder first.")), 10000))
            val pc = device.wait(Until.findObject(By.text("PC")), 10000)
            assertNotNull("PC | Phone selector shows PC", pc)
            pc.click() // starts request B
            assertTrue("B's rows are on screen", device.wait(Until.hasObject(By.text("Folder B1")), 15000))
            assertEquals("exactly A and B were requested", 2, stub.browseRequests.get())
            assertNull("A has not answered yet", device.findObject(By.text("Folder A1")))

            releaseA.countDown() // the late, older reply is delivered now
            Thread.sleep(2500)   // long enough for it to arrive and (wrongly) render, if it were going to
            device.waitForIdle()
            assertNull("the late older listing must not replace the newer one", device.findObject(By.text("Folder A1")))
            assertNotNull("the newer listing is still shown", device.findObject(By.text("Folder B1")))
        }
    }
}
