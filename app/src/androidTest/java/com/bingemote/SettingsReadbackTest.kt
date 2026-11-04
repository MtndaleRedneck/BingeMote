package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Settings read-back of the 1.7.2 candidate on the emulator, against a CONTROLLED
 * loopback mock helper (not a real PC): opening Settings must make BOTH toggles
 * ("Keep PC awake while watching", "Show volume on PC screen") reflect the helper's
 * GET /config, and a helper whose config lacks a key must leave the phone-local value.
 */
class SettingsReadbackTest {

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

    @Test
    fun togglesReflectHelperConfigAndKeepLocalWhenKeysAbsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val ctx = instrumentation.targetContext

        val configBody = java.util.concurrent.atomic.AtomicReference("{\"moviesFolder\":\"\",\"keepAwake\":false,\"volumeOsd\":false}")
        val helper = MockHttp { req ->
            if (req.startsWith("GET /config")) configBody.get() else "{\"ok\":true}"
        }
        try {
            fun seed(keepAwake: Boolean, osd: Boolean) {
                val s = Store(ctx)
                s.host = "127.0.0.1"; s.helperPort = helper.port
                s.helperToken = "readback-token"; s.vlcPassword = "pw"
                s.pcName = "TESTPC"; s.setupDone = true
                s.keepAwake = keepAwake; s.showVolumeOnPc = osd
                // Flush the async apply() queue before the APP process (not this test
                // process) reads the same prefs; secrets need their own sync commit.
                assertTrue(ctx.getSharedPreferences("settings", 0)
                    .edit().putBoolean("seedCommitted", true).commit())
                val secrets = androidx.security.crypto.EncryptedSharedPreferences.create(
                    ctx, "secrets",
                    androidx.security.crypto.MasterKey.Builder(ctx)
                        .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
                assertTrue(secrets.edit().putString("helperToken", "readback-token")
                    .putString("vlcPassword", "pw").commit())
            }

            fun openSettings() {
                device.executeShellCommand("am force-stop com.bingemote")
                device.executeShellCommand("am start -n com.bingemote/.MainActivity --es screen settings")
                assertNotNull("settings screen did not open",
                    device.wait(Until.findObject(By.text("Keep PC awake while watching")), 20000))
            }

            fun toggleState(label: String): Boolean? {
                // The settings column scrolls; the volume toggle can start off-screen.
                repeat(6) {
                    val o = device.findObject(By.text(label))
                    if (o != null) return o.isChecked
                    device.executeShellCommand("input swipe 540 1500 540 700 300")
                    Thread.sleep(600)
                }
                return null
            }

            fun awaitToggle(label: String, want: Boolean, ms: Long) {
                val end = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < end) {
                    if (toggleState(label) == want) return
                    Thread.sleep(300)
                }
                assertEquals("toggle '$label'", want, toggleState(label))
            }

            // A) helper reports both OFF: phone-local ON must flip to the helper's state.
            seed(keepAwake = true, osd = true)
            openSettings()
            awaitToggle("Keep PC awake while watching", false, 15000)
            awaitToggle("Show volume on PC screen", false, 8000)

            // B) an older helper whose config lacks the keys: phone-local values stand.
            configBody.set("{\"moviesFolder\":\"\"}")
            seed(keepAwake = true, osd = true)
            openSettings()
            Thread.sleep(3000) // give any erroneous overwrite room to land
            awaitToggle("Keep PC awake while watching", true, 8000)
            awaitToggle("Show volume on PC screen", true, 8000)
        } finally {
            helper.close()
            device.executeShellCommand("am force-stop com.bingemote")
        }
    }
}
