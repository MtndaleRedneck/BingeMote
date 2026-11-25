package com.bingemote

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Support for the three navigation tests (MoviesNavTest, FeedbackFreshEntryTest,
 * SettingsScrollNavTest) that run against the minified signed release APK.
 * The app dex and the test dex share one classloader and the app's R8 pass strips Kotlin
 * stdlib classes it does not use (run 45711044738: kotlin.jdk7.AutoCloseableKt and
 * kotlin.concurrent.ThreadsKt NoClassDefFoundError). This file therefore uses only java.*
 * APIs, anonymous classes, loops and plain member calls: no kotlin.concurrent.thread, no
 * .use, no listOf/map/joinToString, no kotlin.text String extensions, no lambdas.
 */
/**
 * legacyStatus=false: Movies browse stub (status "stopped" body plus browse.json), as the old private Vlc.
 * legacyStatus=true: exact answers of the old HapticTickTest.StatusStub for the Feedback and Settings tests:
 * status {time 30, length 3600, volume 128, state "paused", filename demo.mkv}, a command request
 * (command=) answered with the status body (pl_pause toggles paused/playing), /volume/get and /ping bodies.
 */
class NavStub(private val legacyStatus: Boolean) : AutoCloseable {
    @Volatile private var state = "paused"
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    fun start() {
        val acceptor = object : Thread() {
            override fun run() {
                while (running.get()) {
                    try {
                        val s = server.accept()
                        val worker = object : Thread() {
                            override fun run() { serve(s) }
                        }
                        worker.start()
                    } catch (e: Exception) { }
                }
            }
        }
        acceptor.start()
    }

    private fun serve(s: Socket) {
        try {
            val r = BufferedReader(InputStreamReader(s.getInputStream()))
            val line: String = r.readLine() ?: ""
            while (true) {
                val h: String = r.readLine() ?: break
                if (h.length == 0) break
            }
            val body: String = if (legacyStatus) legacyBody(line) else
                if (has(line, "browse.json")) dirJson(dirParam(line)) else STATUS_JSON
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val o = s.getOutputStream()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " +
                bytes.size.toString() + "\r\nConnection: close\r\n\r\n"
            o.write(head.toByteArray(StandardCharsets.UTF_8))
            o.write(bytes)
            o.flush()
            s.close()
        } catch (e: Exception) { }
    }

    private fun legacyBody(line: String): String {
        if (has(line, "command=")) {
            if (has(line, "pl_pause")) state = if (state == "playing") "paused" else "playing"
            return legacyStatusJson(state)
        }
        if (has(line, "/volume/get")) return "{\"volume\":50,\"mute\":false}"
        if (has(line, "/ping")) return "{}"
        return legacyStatusJson(state)
    }

    private fun legacyStatusJson(snap: String): String =
        "{\"time\":30,\"length\":3600,\"volume\":128,\"state\":\"" + snap +
            "\",\"information\":{\"category\":{\"meta\":{\"filename\":\"demo.mkv\"}}}}"

    override fun close() {
        running.set(false)
        try { server.close() } catch (e: Exception) { }
    }

    companion object {
        const val ROOT = "C:\\Movies"
        private const val STATUS_JSON =
            "{\"time\":0,\"length\":0,\"volume\":256,\"state\":\"stopped\",\"information\":{\"category\":{\"meta\":{}}}}"

        fun has(s: String, needle: String): Boolean = indexOfStr(s, needle, 0) >= 0

        fun indexOfStr(s: String, needle: String, from: Int): Int {
            var i = from
            while (i + needle.length <= s.length) {
                var j = 0
                while (j < needle.length && s[i + j] == needle[j]) j++
                if (j == needle.length) return i
                i++
            }
            return -1
        }

        /** The value after "dir=" up to the next space or '&', URL-decoded; ROOT when absent. */
        fun dirParam(line: String): String {
            val at = indexOfStr(line, "dir=", 0)
            if (at < 0) return ROOT
            var end = at + 4
            while (end < line.length && line[end] != ' ' && line[end] != '&') end++
            return URLDecoder.decode(line.substring(at + 4, end), "UTF-8")
        }

        private fun jsonPath(path: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < path.length) {
                val c = path[i]
                if (c == '\\') sb.append("\\\\") else sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun entry(name: String, type: String, path: String): String =
            "{\"type\":\"" + type + "\",\"name\":\"" + name + "\",\"path\":\"" + jsonPath(path) +
                "\",\"uri\":\"file:///x\",\"size\":0}"

        fun dirJson(dir: String): String {
            var d = dir
            while (d.length > 0 && d[d.length - 1] == '\\') d = d.substring(0, d.length - 1)
            val items = ArrayList<String>()
            if (d == ROOT) {
                var i = 1
                while (i <= 30) {
                    val n = if (i < 10) "0" + i.toString() else i.toString()
                    items.add(entry("Folder " + n, "dir", ROOT + "\\Folder " + n))
                    i++
                }
            } else {
                items.add(entry("..", "dir", ROOT))
                items.add(entry("inner movie.mp4", "file", dir + "\\inner movie.mp4"))
            }
            val sb = StringBuilder("{\"element\":[")
            var k = 0
            while (k < items.size) {
                if (k > 0) sb.append(',')
                sb.append(items[k])
                k++
            }
            sb.append("]}")
            return sb.toString()
        }

        /** Same prefs and Store values as HapticTickTest.seedPrefs(ctx, false, port). */
        fun seed(ctx: Context, port: Int) {
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("setupDone", true)
                .putString("host", "127.0.0.1")
                .putInt("vlcPort", port)
                .putBoolean("lockScreenControls", true)
                .putBoolean("demoControls", false)
                .putBoolean("haptics", false)
                .putInt("skipMainSec", 5)
                .apply()
            Store(ctx).vlcPassword = "x"
            Store(ctx).helperToken = ""
        }
    }
}
