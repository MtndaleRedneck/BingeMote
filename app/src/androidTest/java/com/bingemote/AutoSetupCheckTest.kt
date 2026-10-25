package com.bingemote

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The step-3 automatic check driven for real: AutoSetupCheck runs the production
 * HelperClient / Vlc / VlcAuthRepair code over actual HTTP against fake PC and VLC
 * servers on localhost. No demo states, no assigned UI state - the same calls the
 * setup screen's LaunchedEffect makes.
 */
class AutoSetupCheckTest {

    /** Minimal threaded HTTP fake: the handler maps (method, path, authorization, body) to (code, body). */
    private class FakeServer(private val handler: (String, String, String?, String) -> Pair<Int, String>) {
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        @Volatile private var stopped = false
        private val thread = Thread {
            while (!stopped) {
                val s = try { socket.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        s.soTimeout = 5000
                        val r = BufferedReader(InputStreamReader(s.getInputStream()))
                        val parts = (r.readLine() ?: "").split(" ")
                        var auth: String? = null
                        var length = 0
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", ignoreCase = true)) auth = line.substringAfter(':').trim()
                            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val chars = CharArray(length)
                        if (length > 0) r.read(chars)
                        val (code, body) = handler(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, auth, String(chars))
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        s.getOutputStream().apply {
                            write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                        s.close()
                    } catch (e: Exception) {
                        try { s.close() } catch (_: Exception) {}
                    }
                }.start()
            }
        }

        fun start(): FakeServer { thread.start(); return this }
        fun stop() { stopped = true; try { socket.close() } catch (_: Exception) {} }
    }

    private fun freshStore(): Store {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().clear().commit()
        return Store(ctx)
    }

    /** VLC's status endpoint behind a fixed password; 401 for anything else. */
    private fun vlcServer(password: String, hits: AtomicInteger? = null): FakeServer =
        FakeServer { _, path, auth, _ ->
            hits?.incrementAndGet()
            val expected = android.util.Base64.encodeToString(":$password".toByteArray(), android.util.Base64.NO_WRAP)
            if (path.startsWith("/requests/status.json") && auth == "Basic $expected") 200 to "{}"
            else 401 to "{}"
        }.start()

    private fun helperServer(configured: Boolean, pending: Boolean, adoptPassword: String? = null, adoptCalls: AtomicInteger? = null): FakeServer =
        FakeServer { _, path, _, body ->
            when {
                path == "/vlc/setup-status" -> 200 to """{"configured":$configured,"pending":$pending}"""
                path == "/vlc/adopt" && adoptPassword != null && !body.contains("\"ack\":true") -> {
                    adoptCalls?.incrementAndGet()
                    200 to """{"password":"$adoptPassword"}"""
                }
                path == "/vlc/adopt" && adoptPassword != null -> 200 to """{"ok":true}"""
                path == "/vlc/adopt" -> 410 to """{"error":"adopt spent"}"""
                else -> 404 to "{}"
            }
        }.start()

    @Test
    fun configuredAndVlcAnswersIsOk() {
        val store = freshStore()
        val helper = helperServer(configured = true, pending = false)
        val vlc = vlcServer("pw-1")
        try {
            store.host = "127.0.0.1"; store.helperPort = helper.port; store.vlcPort = vlc.port
            store.helperToken = "tok"; store.vlcPassword = "pw-1"
            assertEquals(AutoSetupCheck.Outcome.OK, AutoSetupCheck.run(store))
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun pendingAttestationIsDeferredWithoutPokingVlc() {
        val store = freshStore()
        val vlcHits = AtomicInteger(0)
        val helper = helperServer(configured = false, pending = true)
        val vlc = vlcServer("pw-1", vlcHits) // must never be contacted: old settings still live
        try {
            store.host = "127.0.0.1"; store.helperPort = helper.port; store.vlcPort = vlc.port
            store.helperToken = "tok"; store.vlcPassword = "pw-1"
            assertEquals(AutoSetupCheck.Outcome.OK_DEFERRED, AutoSetupCheck.run(store))
            assertEquals(0, vlcHits.get())
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun configuredButVlcClosedIsOkNotOpen() {
        val store = freshStore()
        val helper = helperServer(configured = true, pending = false)
        val deadPort = ServerSocket(0).use { it.localPort } // closed the moment the socket frees
        try {
            store.host = "127.0.0.1"; store.helperPort = helper.port; store.vlcPort = deadPort
            store.helperToken = "tok"; store.vlcPassword = "pw-1"
            assertEquals(AutoSetupCheck.Outcome.OK_NOT_OPEN, AutoSetupCheck.run(store))
        } finally { helper.stop() }
    }

    @Test
    fun refusedOnceHealsViaOneAdoptThenOk() {
        val store = freshStore()
        val adoptCalls = AtomicInteger(0)
        val helper = helperServer(configured = true, pending = false, adoptPassword = "pw-2", adoptCalls = adoptCalls)
        val vlc = vlcServer("pw-2") // VLC only takes the PC's current password
        try {
            store.host = "127.0.0.1"; store.helperPort = helper.port; store.vlcPort = vlc.port
            store.helperToken = "tok"; store.vlcPassword = "stale" // the phone's copy is wrong
            assertEquals(AutoSetupCheck.Outcome.OK, AutoSetupCheck.run(store))
            assertEquals(1, adoptCalls.get()) // exactly one adoption fetch
            assertEquals("pw-2", store.vlcPassword) // adopted, never pushed
            assertNull(store.vlcAdoptRequestId) // the ack cleared the persisted request id
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun refusedWithSpentGrantIsRefused() {
        val store = freshStore()
        val helper = helperServer(configured = true, pending = false) // adopt -> 410
        val vlc = vlcServer("other") // every password the phone has is refused
        try {
            store.host = "127.0.0.1"; store.helperPort = helper.port; store.vlcPort = vlc.port
            store.helperToken = "tok"; store.vlcPassword = "stale"
            assertEquals(AutoSetupCheck.Outcome.REFUSED, AutoSetupCheck.run(store))
            assertEquals("stale", store.vlcPassword) // a refusal never overwrites the stored copy
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun nothingConfiguredIsRefusedNotGreen() {
        val store = freshStore()
        val vlcHits = AtomicInteger(0)
        val helper = helperServer(configured = false, pending = false)
        val vlc = vlcServer("pw-1", vlcHits)
        try {
            store.host = "127.0.0.1"; store.helperPort = helper.port; store.vlcPort = vlc.port
            store.helperToken = "tok"; store.vlcPassword = "pw-1"
            assertEquals(AutoSetupCheck.Outcome.REFUSED, AutoSetupCheck.run(store))
            assertEquals(0, vlcHits.get()) // no probe: the PC says nothing is set up
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun pcDownIsUnreachable() {
        val store = freshStore()
        val deadPort = ServerSocket(0).use { it.localPort }
        store.host = "127.0.0.1"; store.helperPort = deadPort; store.vlcPort = deadPort
        store.helperToken = "tok"; store.vlcPassword = "pw-1"
        assertEquals(AutoSetupCheck.Outcome.PC_UNREACHABLE, AutoSetupCheck.run(store))
    }

    @Test
    fun badTokenIsRefused() {
        val store = freshStore()
        val helper = FakeServer { _, _, _, _ -> 401 to """{"error":"bad token"}""" }.start()
        try {
            store.host = "127.0.0.1"; store.helperPort = helper.port; store.vlcPort = helper.port
            store.helperToken = "tok"; store.vlcPassword = "pw-1"
            assertEquals(AutoSetupCheck.Outcome.REFUSED, AutoSetupCheck.run(store))
        } finally { helper.stop() }
    }
}
