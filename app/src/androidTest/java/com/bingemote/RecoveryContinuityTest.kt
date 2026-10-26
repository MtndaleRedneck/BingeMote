package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Same-signer instrumentation on exact signed APKs, no app debug hook or data reset. */
class RecoveryContinuityTest {
    @Test fun retainedSettingsAndSecretsDecrypt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val s = Store(ctx)
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "verify"
        if (phase == "seed") {
            s.pcName = "DESKTOP-TEST"; s.host = "192.0.2.27"; s.setupDone = true
            s.mouseSpeed = 1.4f; s.moviesPath = "C:\\Movies"
            s.skipMainSec = 10; s.skipSecondarySec = 60; s.haptics = false
            s.helperToken = "continuity-helper-fixture"
            s.vlcPassword = "continuity-vlc-fixture"
        }
        assertEquals("DESKTOP-TEST", s.pcName)
        assertEquals("192.0.2.27", s.host)
        assertTrue(s.setupDone)
        assertEquals(1.4f, s.mouseSpeed, 0.001f)
        assertEquals("C:\\Movies", s.moviesPath)
        assertEquals(10, s.skipMainSec); assertEquals(60, s.skipSecondarySec)
        assertFalse(s.haptics)
        assertEquals("continuity-helper-fixture", s.helperToken)
        assertEquals("continuity-vlc-fixture", s.vlcPassword)
        if (phase == "restored") {
            val prefs = ctx.getSharedPreferences("settings", 0)
            prefs.edit().remove("skipMainSec").remove("skipSecondarySec").commit()
            assertEquals(10, Store(ctx).skipMainSec); assertEquals(30, Store(ctx).skipSecondarySec)
            prefs.edit().putInt("skipMainSec",5).putInt("skipSecondarySec",180).commit()
            assertEquals(5, Store(ctx).skipMainSec); assertEquals(180, Store(ctx).skipSecondarySec)
            prefs.edit().putInt("skipMainSec",10).putInt("skipSecondarySec",60).commit()
        }
        println("RECOVERY_SETTINGS_AND_CREDENTIAL_DECRYPTION_PASS phase=" + phase)
    }
}
