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

    private fun seed(ctx: android.content.Context, port: Int, keepAwake: Boolean, osd: Boolean) {
        val s = Store(ctx)
        s.host = "127.0.0.1"; s.helperPort = port
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

    private fun openSettings(device: UiDevice) {
        // The workflow force-stops the app BEFORE each read-back run: the cold start
        // re-reads the seeded prefs from disk and re-fires the settings fetch. Never
        // kill the process from inside a test - the instrumentation runs IN the app's
        // process, so a force-stop is a runner suicide (v2's "Process crashed").
        device.executeShellCommand("am start -n com.bingemote/.MainActivity --es screen settings")
        assertNotNull("settings screen did not open",
            device.wait(Until.findObject(By.text("Keep PC awake while watching")), 20000))
    }

    private fun toggleRow(device: UiDevice, label: String): androidx.test.uiautomator.UiObject2 {
        // The label is a plain Text and is NEVER checkable; the ROW carries the Switch
        // semantics (Modifier.toggleable, Role.Switch - v4 read the label itself, which
        // made leg A's false-expectation vacuous and leg B fail honestly). Association
        // is structural: the row is the PARENT of the exact label node. If the row is
        // missing or not checkable the layout changed - REFUSE loudly, never default.
        repeat(6) {
            val o = device.findObject(By.text(label))
            if (o != null) {
                val row = o.parent
                if (row != null && row.isCheckable) return row
                throw AssertionError("toggle row for '$label' missing or not checkable")
            }
            device.executeShellCommand("input swipe 540 1500 540 700 300")
            Thread.sleep(600)
        }
        throw AssertionError("toggle '$label' not found")
    }

    private fun toggleState(device: UiDevice, label: String): Boolean = toggleRow(device, label).isChecked

    private fun awaitToggle(device: UiDevice, label: String, want: Boolean, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (toggleState(device, label) == want) return
            Thread.sleep(300)
        }
        assertEquals("toggle '$label'", want, toggleState(device, label))
    }

    private fun readBack(mockConfig: String, wantKeepAwake: Boolean, wantOsd: Boolean, settleMs: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val ctx = instrumentation.targetContext
        val helper = MockHttp { req ->
            if (req.startsWith("GET /config")) mockConfig else "{\"ok\":true}"
        }
        try {
            seed(ctx, helper.port, keepAwake = true, osd = true)
            // Non-vacuous baseline: the same-process prefs singleton the app reads must
            // hold the seeded ON values, or a later OFF/ON assertion proves nothing.
            assertTrue("seeded keepAwake baseline not observable", Store(ctx).keepAwake)
            assertTrue("seeded volumeOsd baseline not observable", Store(ctx).showVolumeOnPc)
            openSettings(device)
            Thread.sleep(settleMs)
            awaitToggle(device, "Keep PC awake while watching", wantKeepAwake, 15000)
            awaitToggle(device, "Show volume on PC screen", wantOsd, 8000)
        } finally {
            helper.close()
        }
    }

    /** Helper reports both OFF: the phone-local ON must flip to the helper's state. */
    @Test
    fun helperStateWinsOverPhoneLocal() {
        readBack("{\"moviesFolder\":\"\",\"keepAwake\":false,\"volumeOsd\":false}",
            wantKeepAwake = false, wantOsd = false, settleMs = 0)
    }

    /** An older helper whose config lacks the keys: the phone-local values stand. */
    @Test
    fun absentKeysKeepPhoneLocal() {
        readBack("{\"moviesFolder\":\"\"}",
            wantKeepAwake = true, wantOsd = true, settleMs = 3000)
    }

}
