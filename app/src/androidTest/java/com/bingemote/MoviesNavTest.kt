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
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Navigation rule: opening Movies fresh starts at the top; going into a folder and
 * coming back (Back) lands on the folder row you left. Deterministic local VLC stub: status.json plus
 * browse.json for C:\Movies (30 folders) and one subfolder. Fail-closed bounds checks.
 */
class MoviesNavTest {
    @get:Rule val failureDump = FailureDumpRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val root = "C:\\Movies"

    private class Vlc : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        private fun dirJson(dir: String): String {
            fun e(name: String, type: String, path: String) = """{"type":"$type","name":"$name","path":"${path.replace("\\", "\\\\")}","uri":"file:///x","size":0}"""
            val items = if (dir.trimEnd('\\') == "C:\\Movies") (1..30).map { String.format("Folder %02d", it) }.map { e(it, "dir", "C:\\Movies\\$it") }
            else listOf(e("..", "dir", "C:\\Movies"), e("inner movie.mp4", "file", "$dir\\inner movie.mp4"))
            return """{"element":[${items.joinToString(",")}]}"""
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
                                val dir = URLDecoder.decode(line.substringAfter("dir=", "C:\\Movies").substringBefore(' ').substringBefore('&'), "UTF-8")
                                dirJson(dir)
                            } else """{"time":0,"length":0,"volume":256,"state":"stopped","information":{"category":{"meta":{}}}}"""
                            val o = s.getOutputStream()
                            o.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                            o.flush(); s.close()
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
        override fun close() { running.set(false); server.close() }
    }

    private fun onScreen(text: String): Boolean {
        val o = device.findObject(By.text(text)) ?: return false
        val b = o.visibleBounds
        return b.top >= 0 && b.bottom <= device.displayHeight && b.height() > 0
    }
    private fun shot(name: String) = device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-$name.png"))

    @Test fun moviesFreshEntryTopAndFolderBackKeepsPlace() {
        Vlc().use { stub ->
            stub.start()
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            Store(ctx).moviesPath = root
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val btn = device.wait(Until.findObject(By.desc("Movies")), 20000)
            assertNotNull("Home Movies button", btn)
            btn.click()
            assertTrue("list loaded", device.wait(Until.hasObject(By.text("Folder 01")), 15000))
            device.waitForIdle()
            assertTrue("first entry at top", onScreen("Folder 01"))
            shot("mv-first-top")

            // Scroll down to a deep folder row.
            var n = 0
            while (!onScreen("Folder 25") && n++ < 25) device.swipe(540, 1700, 540, 700, 12)
            assertTrue("deep folder reachable", onScreen("Folder 25"))
            assertFalse("scrolled off the top", onScreen("Folder 01"))
            shot("mv-scrolled")

            // Into the folder, then Back: same place.
            device.findObject(By.text("Folder 25")).click()
            assertTrue("inside folder", device.wait(Until.hasObject(By.text("inner movie")), 10000))
            device.pressBack()
            assertTrue("back in the parent list", device.wait(Until.hasObject(By.text("Folder 25")), 10000))
            device.waitForIdle()
            assertTrue("Back keeps place", onScreen("Folder 25"))
            assertFalse("did not jump to top", onScreen("Folder 01"))
            shot("mv-folder-back-kept")

            // Leave Movies to Home and open it again: top.
            device.pressBack()
            val again = device.wait(Until.findObject(By.desc("Movies")), 10000)
            assertNotNull("Home again", again)
            again.click()
            assertTrue(device.wait(Until.hasObject(By.text("Folder 01")), 15000))
            device.waitForIdle()
            assertTrue("fresh entry at top", onScreen("Folder 01"))
            assertFalse("not left deep", onScreen("Folder 25"))
            shot("mv-reentry-top")
        }
    }
}
