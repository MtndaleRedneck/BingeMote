package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The first-press wake rule of the 1.7.2 candidate, exercised against CONTROLLED
 * loopback mocks (a mock helper and a mock VLC, both inside this test - not real
 * machines). What this proves: the app's own gating behavior per screen state.
 * What it cannot prove: a real PC's display, a real VLC, real Windows power.
 *
 *  - screen dark: a playback tap is wake-only (POST /wake-display, ZERO VLC commands)
 *  - screen on: the same tap reaches VLC
 *  - helper unreachable: the tap goes NOWHERE (strict no-send on unknown state)
 */
class WakeGateTest {

    /** One-port loopback HTTP mock: records the request line, answers from the lambda. */
    private class MockHttp(private val handler: (String) -> String) {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        @Volatile private var open = true
        init {
            thread {
                while (open) {
                    val s = try { socket.accept() } catch (_: Exception) { break }
                    thread {
                        try {
                            s.soTimeout = 5000
                            val r = s.getInputStream().bufferedReader()
                            val requestLine = r.readLine() ?: return@thread
                            var len = 0
                            while (true) {
                                val line = r.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", true)) len = line.substringAfter(':').trim().toInt()
                            }
                            var read = 0
                            while (read < len) { if (r.read() < 0) break; read++ }
                            val body = handler(requestLine).toByteArray(Charsets.UTF_8)
                            val out = s.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body); out.flush(); s.close()
                        } catch (_: Exception) { try { s.close() } catch (_: Exception) {} }
                    }
                }
            }
        }
        fun close() { open = false; try { socket.close() } catch (_: Exception) {} }
    }

    private fun await(what: String, ms: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        assertTrue("timed out waiting for: $what", cond())
    }

    @Test
    fun darkScreenFirstTapIsWakeOnlySecondTapExecutes() {
        val wakeCalls = AtomicInteger(0)
        val screenStateCalls = AtomicInteger(0)
        val dark = java.util.concurrent.atomic.AtomicBoolean(true)
        val helper = MockHttp { req ->
            when {
                req.startsWith("GET /screen-state") -> { screenStateCalls.incrementAndGet(); "{\"dark\":${dark.get()}}" }
                req.startsWith("POST /wake-display") -> { wakeCalls.incrementAndGet(); "{\"ok\":true}" }
                else -> "{\"ok\":true}"
            }
        }
        val vlcCommands = AtomicInteger(0)
        val vlc = MockHttp { req ->
            if (req.contains("/requests/status.json") && req.contains("command=")) vlcCommands.incrementAndGet()
            "{}"
        }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(ctx)
        store.host = "127.0.0.1"; store.helperPort = helper.port; store.vlcPort = vlc.port
        store.helperToken = "gate-token"; store.vlcPassword = "pw"; store.setupDone = true
        val scope = CoroutineScope(SupervisorJob())
        try {
            val remote = RemoteState(ctx, store, scope)
            remote.togglePause()
            await("wake-only first tap", 8000) { wakeCalls.get() == 1 }
            assertEquals("dark screen: the tap's VLC command must NOT be sent", 0, vlcCommands.get())
            assertTrue("the gate must consult /screen-state", screenStateCalls.get() >= 1)
            dark.set(false)
            remote.togglePause()
            await("second tap reaches VLC", 8000) { vlcCommands.get() == 1 }
            assertEquals("screen on: no extra wake call", 1, wakeCalls.get())
        } finally {
            scope.cancel(); helper.close(); vlc.close()
        }
    }

    @Test
    fun unreachableHelperSendsNothingAnywhere() {
        // A closed loopback port stands in for a fully quit helper. Strict rule: the
        // tap becomes wake-only and the wake goes nowhere - nothing reaches VLC.
        val dead = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); val deadPort = dead.localPort; dead.close()
        val vlcCommands = AtomicInteger(0)
        val vlcRequests = AtomicInteger(0)
        val vlc = MockHttp { req ->
            vlcRequests.incrementAndGet()
            if (req.contains("command=")) vlcCommands.incrementAndGet()
            "{}"
        }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(ctx)
        store.host = "127.0.0.1"; store.helperPort = deadPort; store.vlcPort = vlc.port
        store.helperToken = "gate-token"; store.vlcPassword = "pw"; store.setupDone = true
        val scope = CoroutineScope(SupervisorJob())
        try {
            val remote = RemoteState(ctx, store, scope)
            remote.togglePause()
            remote.skip(30)
            // Connection-refused is fast on loopback; 3s covers both gate timeouts.
            Thread.sleep(3000)
            assertEquals("helper unreachable: no VLC playback command may be sent", 0, vlcCommands.get())
            assertEquals("helper unreachable: no VLC request at all may be sent", 0, vlcRequests.get())
        } finally {
            scope.cancel(); vlc.close()
        }
    }
}
