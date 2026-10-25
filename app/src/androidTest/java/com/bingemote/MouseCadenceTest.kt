package com.bingemote

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Diagnosis harness for the jerky-mouse report - NOT a product gate. Injects a
 * synthetic drag and records the move frames the app actually emits to a fake
 * helper on localhost (a near-perfect link), so sender-loop cadence artifacts
 * show up without any LAN in the way.
 *
 * Honesty rules, learned the hard way:
 * - SystemClock.sleep() overshoots by ~17 ms on this emulator, so pass labels
 *   never claim a cadence. Every pass logs its MEASURED injected intervals AND
 *   the app-side delta arrivals (a DEBUG-only ring buffer in the gesture
 *   handler), and conclusions come only from measured cadences.
 * - Every pass must capture frames or the test FAILS - a missed pass is an
 *   invalid rig, never a silent conclusion.
 * - True sub-17 ms steady injection is beyond this rig; faster-than-60 Hz input
 *   is an on-device question, not answered here.
 *
 * Pass families: base-* and dwait-* now exercise the SAME shipped loop (the
 * delta-driven empty-window wait replaced the 20 ms nap in 1.11.0); the dwait
 * names are kept so runs compare across history. Expected post-fix: output
 * follows input ~1:1 (even gaps at the input cadence) at every cadence.
 *
 * Numbers go to logcat under BMMouse and are the deliverable. The fake helper
 * answers /ping and /volume/get (so the 1 Hz poll stays quiet), refuses /ws (so
 * frames take the per-batch HTTP path and each emission is a separately timed
 * request), and times every /mouse/move.
 */
class MouseCadenceTest {
    data class Frame(val atNanos: Long, val dx: Double, val dy: Double)

    private val frames = Collections.synchronizedList(mutableListOf<Frame>())
    @Volatile private var serverRunning = true

    @Test
    fun steadyDragEmissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val port = 18499

        // Pair the app against the fake helper before launch (same process/UID:
        // the encrypted store opens with the same master key).
        ctx.getSharedPreferences("settings", 0).edit()
            .putString("host", "127.0.0.1")
            .putInt("helperPort", port)
            .putInt("vlcPort", port)
            .putBoolean("setupDone", true)
            .apply()
        val secrets = EncryptedSharedPreferences.create(
            ctx, "secrets",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        secrets.edit().putString("vlcPassword", "rig").putString("helperToken", "rigtoken").apply()

        val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread { serveLoop(server) }

        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am start -n com.bingemote/.MainActivity --es screen mouse")
        assertNotNull(
            "mouse screen did not come up (Left click button missing)",
            device.wait(Until.findObject(By.text("Left click")), 10000)
        )
        Thread.sleep(1500)

        // Baseline passes (shipped loop). Requested cadences land ~17 ms slower
        // than asked - the measured histograms are the truth, not the labels.
        pass(instrumentation, "base-16fwd", startX = 240f, stepPx = 4f, intervalMs = 16L, moves = 180, deltaWait = false)
        pass(instrumentation, "base-16rev", startX = 900f, stepPx = -4f, intervalMs = 16L, moves = 150, deltaWait = false)
        pass(instrumentation, "base-8", startX = 240f, stepPx = 2f, intervalMs = 8L, moves = 360, deltaWait = false)
        pass(instrumentation, "base-33", startX = 240f, stepPx = 4f, intervalMs = 33L, moves = 90, deltaWait = false)
        // Fix-validation passes: same input against the delta-driven empty-window
        // wait (androidTest-only variant, dead in release).
        pass(instrumentation, "dwait-16fwd", startX = 240f, stepPx = 4f, intervalMs = 16L, moves = 180, deltaWait = true)
        pass(instrumentation, "dwait-33", startX = 240f, stepPx = 4f, intervalMs = 33L, moves = 90, deltaWait = true)
        MouseSendDebugHooks.deltaWaitEnabled = false

        serverRunning = false
        try { server.close() } catch (_: Exception) {}
        serverThread.join(2000)
        assertTrue("no move frames captured at the fake helper", frames.isEmpty())
    }

    private fun runDrag(
        instrumentation: android.app.Instrumentation,
        label: String, startX: Float, y: Float, stepPx: Float, intervalMs: Long, moves: Int
    ) {
        val downTime = SystemClock.uptimeMillis()
        var x = startX
        var lastInject = 0L
        val injected = mutableListOf<Long>()
        val down = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0)
        instrumentation.sendPointerSync(down)
        down.recycle()
        repeat(moves) {
            SystemClock.sleep(intervalMs)
            x += stepPx
            val now = SystemClock.uptimeMillis()
            if (lastInject != 0L) injected.add(now - lastInject)
            lastInject = now
            val mv = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, x, y, 0)
            instrumentation.sendPointerSync(mv)
            mv.recycle()
        }
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        instrumentation.sendPointerSync(up)
        up.recycle()
        // The injected cadence is measured, not assumed: sleep() overshoots.
        val hist = injected.groupingBy { it }.eachCount().toSortedMap()
        Log.i("BMMouse", "$label: injectedIntervals(ms->count)=$hist")
    }

    private fun pass(
        instrumentation: android.app.Instrumentation,
        label: String, startX: Float, stepPx: Float, intervalMs: Long, moves: Int
    ) {
        MouseSendDebugHooks.traceEnabled = true
        runDrag(instrumentation, label, startX = startX, y = 1000f, stepPx = stepPx, intervalMs = intervalMs, moves = moves)
        Thread.sleep(800)
        reportArrivals(label)
        report(label, snapshotAndReset())
    }

    private fun reportArrivals(label: String) {
        MouseSendDebugHooks.traceEnabled = false
        val arr = MouseSendDebugHooks.drainArrivals()
        val gaps = arr.toList().zipWithNext { a, b -> b - a }
        val hist = gaps.groupingBy { it }.eachCount().toSortedMap()
        Log.i("BMMouse", "$label: appArrivals=${arr.size} interArrival(ms->count)=$hist")
    }

    private fun snapshotAndReset(): List<Frame> {
        val out: List<Frame>
        synchronized(frames) { out = frames.toList(); frames.clear() }
        return out
    }

    private fun report(label: String, frames: List<Frame>) {
        assertTrue("$label: NO FRAMES CAPTURED - pass invalid, rig broken", frames.isNotEmpty())
        val gaps = frames.zipWithNext { a, b -> (b.atNanos - a.atNanos) / 1_000_000.0 }
        val steps = frames.map { kotlin.math.abs(it.dx) }
        val sortedSteps = steps.sorted()
        val medianStep = sortedSteps[sortedSteps.size / 2]
        val sortedGaps = gaps.sorted()
        val medianGap = sortedGaps[sortedGaps.size / 2]
        val big = steps.count { it >= medianStep * 1.8 }
        val gapsOver25 = gaps.count { it > 25 }
        val gapsOver30 = gaps.count { it > 30 }
        Log.i(
            "BMMouse",
            "$label: frames=${frames.size} medianStep=$medianStep medianGapMs=$medianGap " +
                "maxGapMs=${sortedGaps.lastOrNull()} bigBatches(>=1.8xMedian)=$big " +
                "gapsOver25ms=$gapsOver25 gapsOver30ms=$gapsOver30"
        )
        val hist = steps.groupingBy { kotlin.math.round(it * 2) / 2 }.eachCount().toSortedMap()
        Log.i("BMMouse", "$label: stepHistogram=$hist")
        val timeline = frames.zipWithNext { a, b ->
            "${((b.atNanos - a.atNanos) / 1_000_000)}ms/${kotlin.math.abs(b.dx)}"
        }.take(40).joinToString(" ")
        Log.i("BMMouse", "$label: firstFrames $timeline")
    }

    private fun serveLoop(server: ServerSocket) {
        while (serverRunning) {
            val sock = try { server.accept() } catch (e: Exception) { return }
            try { handle(sock) } catch (_: Exception) {} finally { try { sock.close() } catch (_: Exception) {} }
        }
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 3000
        val inp = sock.getInputStream()
        val reader = BufferedReader(InputStreamReader(inp))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "" }
        var contentLength = 0
        while (true) {
            val h = reader.readLine() ?: return
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val bodyChars = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(bodyChars, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        val body = String(bodyChars, 0, read)
        when {
            path == "/ping" -> respond(sock, 200, "{\"ok\":true}")
            path == "/volume/get" -> respond(sock, 200, "{\"volume\":50,\"mute\":false}")
            path == "/ws" -> respond(sock, 400, "{\"error\":\"websocket expected\"}")
            path.startsWith("/mouse/") -> {
                if (method == "POST" && path == "/mouse/move") {
                    try {
                        val o = JSONObject(body)
                        frames.add(Frame(System.nanoTime(), o.getDouble("dx"), o.getDouble("dy")))
                    } catch (_: Exception) {}
                }
                respond(sock, 200, "{\"ok\":true}")
            }
            else -> respond(sock, 404, "{}")
        }
    }

    private fun respond(sock: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
