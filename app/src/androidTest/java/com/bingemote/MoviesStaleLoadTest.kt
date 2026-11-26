package com.bingemote

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
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

    private var savedPrefs: Map<String, Any?>? = null
    private var savedVlcPassword: String? = null
    private var savedHelperToken: String? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    // Leave no trace for the next class. This test seeds the shared "settings" prefs and the two secrets the VLC stub
    // needs (isConfigured requires a non-blank vlcPassword), and it opens MainActivity on the Movies screen. Snapshot
    // the prefs and the two secrets through the app's own Store accessors (kept in memory, never logged), restore them
    // afterwards, and finish the activity through its own ActivityScenario.
    @Before fun snapshotState() {
        savedPrefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).all.toMap()
        savedVlcPassword = Store(ctx).vlcPassword
        savedHelperToken = Store(ctx).helperToken
    }

    @After fun restoreStateAndFinishActivity() {
        scenario?.close(); scenario = null
        val snap = savedPrefs
        if (snap != null) {
            val e = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear()
            for ((k, v) in snap) when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
            e.commit()
            savedVlcPassword?.let { Store(ctx).vlcPassword = it }
            savedHelperToken?.let { Store(ctx).helperToken = it }
        }
    }

    private class TwoRequestVlc(val releaseA: CountDownLatch) : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val browseRequests = AtomicInteger()
        val aHeld = CountDownLatch(1)
        val aServed = CountDownLatch(1)
        private fun dirJson(prefix: String): String {
            val e = (1..5).joinToString(",") { """{"type":"dir","name":"Folder $prefix$it","path":"C:\\Movies\\$prefix$it","uri":"file:///x","size":0}""" }
            return """{"element":[$e]}"""
        }
        /** Send the headers, then one whitespace byte per second until released: the app's 4 s READ timeout (Vlc.get default)
         *  is per read, so the held request stays alive and ends as a real late SUCCESS, not as a timeout. Leading whitespace is valid JSON. */
        private fun dripUntilReleased(s: java.net.Socket) {
            val o = s.getOutputStream()
            o.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".toByteArray()); o.flush()
            while (!releaseA.await(1, TimeUnit.SECONDS)) { o.write(" ".toByteArray()); o.flush() }
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
                            var heldA = false
                            val body = if (line.contains("browse.json")) {
                                val n = browseRequests.incrementAndGet()
                                if (n == 1) { heldA = true; aHeld.countDown(); dripUntilReleased(s); dirJson("A") } // A: held, then the late reply
                                else dirJson("B")                                                        // B: immediate
                            } else """{"time":0,"length":0,"volume":256,"state":"stopped","information":{"category":{"meta":{}}}}"""
                            val o = s.getOutputStream()
                            if (heldA) { o.write(body.toByteArray()) } // headers were sent by the drip (no Content-Length, ends on close)
                            else o.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                            o.flush(); s.close()
                            if (heldA) aServed.countDown() // the full late reply was written and the socket closed without error
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
            scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val btn = device.wait(Until.findObject(By.desc("Movies")), 20000)
            assertNotNull("Home Movies button", btn)
            LoadGate.successes.clear()
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

            val before = LoadGate.successes.toList()
            releaseA.countDown() // the late, older reply is delivered now
            assertTrue("A's reply was written to the app", stub.aServed.await(10, TimeUnit.SECONDS)) // signal, not a sleep
            // Request-specific completion signal: the loader that fetched A's folder recorded its own SUCCESS (a timeout or
            // error would record nothing here), with its generation and whether the gate accepted it. Bounded poll on that record.
            val until = android.os.SystemClock.elapsedRealtime() + 10_000
            fun aRec() = LoadGate.successes.firstOrNull { it.firstRow == "Folder A1" }
            while (aRec() == null && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            val a = aRec()
            assertNotNull("A's late SUCCESS reached the loader (a timeout would leave no record)", a)
            assertEquals("loader kind", "vlc", a!!.kind)
            assertFalse("the gate REJECTED A's late success", a.accepted)
            val b = LoadGate.successes.firstOrNull { it.firstRow == "Folder B1" }
            assertNotNull("B's success was recorded", b)
            assertTrue("B accepted", b!!.accepted)
            assertTrue("A is the older generation", a.gen < b.gen)
            assertEquals("A's record was written after the release, not before", null, before.firstOrNull { it.firstRow == "Folder A1" })
            device.waitForIdle()
            assertFalse("the late older listing must not replace the newer one", device.hasObject(By.text("Folder A1")))
            assertNotNull("the newer listing is still shown", device.findObject(By.text("Folder B1")))
        }
    }
}
