package com.bingemote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.cert.CertificateException

/** Secure-only pairing persistence. The "disk" is a map; a crash drops every write after the Nth, and a fresh load reads only that map. */
private val TOK = "a1".repeat(32)
private val TOK1 = "b2".repeat(32)
private val TOK2 = "c3".repeat(32)
class HelperPairSavePinTest {
    private val pin = "ab".repeat(32)
    private val pin2 = "cd".repeat(32)
    private val pc = "0123456789abcdef0123456789abcdef"
    private val pc2 = "f".repeat(32)

    private class Disk(var crashAfter: Int = Int.MAX_VALUE, var failWritesAfter: Int = Int.MAX_VALUE, var readFails: Boolean = false) {
        val m = hashMapOf("legacy" to "", "cred" to "", "intent" to "", "vlc" to "oldvlc", "name" to "old")
        var writes = 0
        fun put(k: String, v: String) { writes++; if (writes > failWritesAfter) throw IllegalStateException("write refused"); if (writes <= crashAfter) m[k] = v }
        fun get(k: String): String { if (readFails) throw SecureStoreUnavailable(); return m[k]!! }
        fun store() = object : SecurePairStore {
            override var credential: String get() = get("cred"); set(v) = put("cred", v)
            override var intent: String get() = get("intent"); set(v) = put("intent", v)
            override var vlcPassword: String get() = get("vlc"); set(v) = put("vlc", v)
            override var pcName: String get() = get("name"); set(v) = put("name", v)
            override var legacyToken: String get() = get("legacy"); set(v) = put("legacy", v)
            override fun flush() = true
            override fun healthy() = !readFails
        }
        fun freshLoad() = SecurePairSave.load(Disk(readFails = readFails).also { it.m.putAll(m) }.store())
    }

    private fun save(d: Disk, token: String = TOK, host: String = "192.168.1.5", pcId: String = pc, pinHex: String = pin) =
        SecurePairSave.save(d.store(), host, 8492, "PC", "vlcpw", SecureHttp.BoundPin(pinHex, token), pcId)

    private fun secure(s: PairedCredential.State) = (s as PairedCredential.State.Secure).credential

    private fun totalWrites(): Int { val d = Disk(); save(d); return d.writes }

    @Test fun cleanSaveLoadsSecureWithIdentityPinAddressAndTokenTogether() {
        val d = Disk(); assertEquals(SecurePairSave.Outcome.SAVED, save(d))
        val c = secure(d.freshLoad())
        assertEquals(listOf(pc, pin, "192.168.1.5", 8492, TOK), listOf(c.pcId, c.pinHex, c.host, c.port, c.token))
        assertEquals("", d.m["intent"])
        val pol = c.policy()
        assertEquals(PinResult.MATCH, pol.check(c.pcId, PairedCredential.pinBytes(c.pinHex), false))
        assertEquals(PinResult.MISMATCH, pol.check(pc2, PairedCredential.pinBytes(c.pinHex), false))
    }

    @Test fun crashAtEveryWriteFromUnpairedIsUnpairedBlockedOrWholeNeverPartial() {
        val total = totalWrites()
        for (n in 0..total) {
            val d = Disk(crashAfter = n); save(d)
            val s = d.freshLoad()
            when {
                n == 0 -> assertTrue("n=$n", s === PairedCredential.State.Unpaired)
                n < total -> assertTrue("n=$n", s === PairedCredential.State.Blocked)
                else -> assertEquals(TOK, secure(s).token)
            }
        }
    }

    @Test fun crashAtEveryWriteOverASecurePairingNeverExposesAMixedOrStaleSecureState() {
        val total = totalWrites()
        for (n in 0..total) {
            val d = Disk(); save(d, token = TOK1)
            d.writes = 0; d.crashAfter = n
            save(d, token = TOK2, host = "10.0.0.9", pcId = pc2, pinHex = pin2)
            val s = d.freshLoad()
            if (n == 0) assertEquals("n=$n", TOK1, secure(s).token)
            else if (n < total) assertTrue("n=$n", s === PairedCredential.State.Blocked)
            else { val c = secure(s); assertEquals(listOf(pc2, pin2, "10.0.0.9", TOK2), listOf(c.pcId, c.pinHex, c.host, c.token)) }
        }
    }

    @Test fun storageFailingOnEveryLaterWriteEndsInPriorKeptOrADurablyBlockedRestart() {
        val total = totalWrites()
        for (k in 0..total) {
            val d = Disk(); save(d, token = TOK1)
            d.writes = 0; d.failWritesAfter = k
            val r = save(d, token = TOK2, pcId = pc2, pinHex = pin2)
            val s = d.freshLoad()
            when (r) {
                SecurePairSave.Outcome.SAVED -> assertEquals("k=$k", TOK2, secure(s).token)
                SecurePairSave.Outcome.FAILED_PRIOR_KEPT -> assertEquals("k=$k", TOK1, secure(s).token)
                SecurePairSave.Outcome.FAILED_BLOCKED -> assertTrue("k=$k restart must be blocked", s === PairedCredential.State.Blocked)
            }
        }
    }

    @Test fun ifTheIntentCannotBePersistedNothingIsTouched() {
        val d = Disk(failWritesAfter = 0)
        assertEquals(SecurePairSave.Outcome.FAILED_PRIOR_KEPT, save(d))
        assertEquals("", d.m["cred"]); assertEquals("oldvlc", d.m["vlc"]); assertEquals("old", d.m["name"])
    }

    @Test fun anUnreadableStoreIsBlockedNotAbsentAndRefusesSaves() {
        val d = Disk(); save(d)
        d.readFails = true
        assertTrue(d.freshLoad() === PairedCredential.State.Blocked)
        val w = d.writes
        assertEquals(SecurePairSave.Outcome.FAILED_PRIOR_KEPT, save(d, token = "x"))
        assertEquals(w, d.writes)
    }

    @Test fun legacyOnlyIsNeverUsableAndNeverFallsBackFromACorruptSecureRecord() {
        val d = Disk(); d.m["legacy"] = "oldtoken"
        assertTrue(d.freshLoad() === PairedCredential.State.NeedsRepair)
        d.m["cred"] = "v3|garbage"
        assertTrue(d.freshLoad() === PairedCredential.State.Blocked)
        d.m["cred"] = PairedCredential.BLOCKED
        assertTrue(d.freshLoad() === PairedCredential.State.Blocked)
    }

    @Test fun badIdentityPinOrAddressIsRefusedBeforeAnyWrite() {
        for (a in listOf(Triple("short", pin, "1.2.3.4"), Triple(pc, "zz", "1.2.3.4"), Triple(pc, pin, "bad host|x"))) {
            val d = Disk()
            assertEquals(SecurePairSave.Outcome.FAILED_PRIOR_KEPT, save(d, pcId = a.first, pinHex = a.second, host = a.third))
            assertEquals(0, d.writes)
        }
    }

    @Test fun forgetIsPersistedAndAnInterruptedForgetIsBlockedNeverSecure() {
        val d = Disk(); d.m["legacy"] = "oldtoken"; save(d)
        assertTrue(SecurePairSave.forget(d.store()))
        assertTrue(d.freshLoad() === PairedCredential.State.Unpaired)
        assertEquals("", d.m["legacy"]); assertEquals("", d.m["vlc"])
        for (n in 1..4) {
            val e = Disk(); save(e); e.writes = 0; e.crashAfter = n
            SecurePairSave.forget(e.store())
            assertTrue("n=$n", e.freshLoad() === PairedCredential.State.Blocked || e.freshLoad() === PairedCredential.State.Unpaired)
            assertFalse("n=$n", e.freshLoad() is PairedCredential.State.Secure)
        }
    }

    @Test fun tokenMustBeExactlyTheHelperAlphabet() {
        for (bad in listOf("tok", TOK.uppercase(), TOK.dropLast(1), TOK + "a", TOK.replaceRange(0, 1, "g"), " $TOK"))
            assertNull(bad, PairedCredential.of(pc, pin, "10.0.0.5", 8492, bad))
        assertNotNull(PairedCredential.of(pc, pin, "10.0.0.5", 8492, TOK))
    }

    @Test fun readbackMismatchBlocksAndNeverClearsIntentAsSaved() {
        val d = Disk()
        val base = d.store()
        val lying = object : SecurePairStore by base {
            override var credential: String get() = if (base.intent == "saving" && base.credential.startsWith("v3")) "v3|other" else base.credential; set(v) { base.credential = v }
        }
        val r = SecurePairSave.save(lying, "192.168.1.5", 8492, "PC", "vlcpw", SecureHttp.BoundPin(pin, TOK), pc)
        assertNotEquals(SecurePairSave.Outcome.SAVED, r)
        if (r == SecurePairSave.Outcome.FAILED_BLOCKED) assertTrue(d.freshLoad() is PairedCredential.State.Blocked)
    }

    @Test fun failedIntentClearLatchesBlockedForTheSameProcess() {
        // Disk keeps the first N writes, the clear of the intent (last write) is refused: the cache may say clear, disk does not.
        val probe = totalWrites()
        val d = Disk(failWritesAfter = probe - 1)
        val store = d.store()
        val r = SecurePairSave.save(store, "192.168.1.5", 8492, "PC", "vlcpw", SecureHttp.BoundPin(pin, TOK), pc)
        assertEquals(SecurePairSave.Outcome.FAILED_BLOCKED, r)
        d.m["intent"] = ""   // a stale cache that now reads clear
        assertTrue(SecurePairSave.load(store) is PairedCredential.State.Blocked)
        d.failWritesAfter = Int.MAX_VALUE
        assertEquals(SecurePairSave.Outcome.SAVED, SecurePairSave.save(store, "192.168.1.5", 8492, "PC", "vlcpw", SecureHttp.BoundPin(pin, TOK), pc))
        assertTrue(SecurePairSave.load(store) is PairedCredential.State.Secure)
    }

    @Test fun addressUpdateChangesOnlyHostAndPortAndNeverIdentityPinOrToken() {
        KeyChangeState.resetForTests()
        val d = Disk(); save(d)
        val s = d.store(); val before = d.m["cred"]!!
        assertTrue(SecurePairSave.updateAddress(s, before, "10.9.8.7", 9000))
        val c = secure(SecurePairSave.load(s))
        assertEquals(listOf(pc, pin, "10.9.8.7", 9000, TOK), listOf(c.pcId, c.pinHex, c.host, c.port, c.token))
        assertEquals("", d.m["intent"])
    }

    @Test fun addressUpdateRefusesAStaleSnapshotAndAKeyChangedPc() {
        KeyChangeState.resetForTests()
        val d = Disk(); save(d)
        val s = d.store(); val probed = d.m["cred"]!!
        save(d, token = TOK2, pcId = pc2, pinHex = pin2)            // a re-pair ran after the probe
        assertEquals(false, SecurePairSave.updateAddress(s, probed, "10.9.8.7", 9000))
        assertEquals(pc2, secure(SecurePairSave.load(s)).pcId)
        KeyChangeState.mark(pc2)
        assertEquals(false, SecurePairSave.updateAddress(s, d.m["cred"]!!, "10.9.8.7", 9000))
        assertTrue(KeyChangeState.isChanged(pc2))               // update never clears it
        KeyChangeState.resetForTests()
    }

    @Test fun addressUpdateCrashAtEveryWriteLeavesOldNewOrBlockedNeverAMix() {
        KeyChangeState.resetForTests()
        val n = run { val d = Disk(); save(d); val w0 = d.writes; SecurePairSave.updateAddress(d.store(), d.m["cred"]!!, "10.9.8.7", 9000); d.writes - w0 }
        for (k in 0..n) {
            val d = Disk(); save(d); val old = d.m["cred"]!!
            d.writes = 0; d.crashAfter = k
            SecurePairSave.updateAddress(d.store(), old, "10.9.8.7", 9000)
            when (val st = d.freshLoad()) {
                is PairedCredential.State.Secure -> { val c = st.credential; assertEquals("k=$k", listOf(pc, pin, TOK), listOf(c.pcId, c.pinHex, c.token)); assertTrue("k=$k", c.host == "192.168.1.5" || c.host == "10.9.8.7") }
                is PairedCredential.State.Blocked -> {}
                else -> fail("k=$k unexpected state $st")
            }
        }
    }

    @Test fun blockedMessageDirectsRepairNotRetry() {
        val m = SecurePairSave.Outcome.FAILED_BLOCKED.userMessage
        assertTrue(m.contains("pair this phone again") && m.contains("Retrying alone will not fix it"))
        assertFalse(SecurePairSave.Outcome.FAILED_PRIOR_KEPT.userMessage.contains("no longer paired"))
    }

    @Test fun recordParsingIsStrict() {
        val ok = PairedCredential.of(pc, pin, "10.0.0.5", 8492, TOK)!!.encode()
        assertEquals(TOK, PairedCredential.decode(ok)!!.token)
        for (bad in listOf("", "v2|$pc|$pin|tok", "v3|$pc|$pin|10.0.0.5|99999|tok", "v3|$pc|$pin|10.0.0.5|8492|", "v3|$pc|$pin|10.0.0.5|8492|a|b", "v3|$pc|${pin.uppercase()}|10.0.0.5|8492|tok", "v3|${pc.take(31)}|$pin|10.0.0.5|8492|tok", "v3|$pc|$pin|a b|8492|tok", "blocked"))
            assertNull(bad, PairedCredential.decode(bad))
        assertEquals(32, PairedCredential.newPcId().length)
        assertEquals(pin, PairedCredential.toHex(PairedCredential.pinBytes(pin)))
    }

    @Test fun rejectedKeyIsNeverRecordedAsObserved() {
        val tm = PinningTrustManager(PinPolicy(mapOf("pc1" to ByteArray(32) { 1 })), "pc1", false)
        try { tm.checkSpki(byteArrayOf(9, 9, 9)); fail() } catch (e: CertificateException) {}
        assertNull(tm.observedHash); assertEquals(PinResult.MISMATCH, tm.result)
    }

    @Test fun ipv6HostsAreBracketed() {
        assertEquals("[fe80::1]", SecureHttp.urlHost("fe80::1"))
        assertEquals("[::1]", SecureHttp.urlHost("[::1]"))
        assertEquals("192.168.1.5", SecureHttp.urlHost("192.168.1.5"))
        try { SecureHttp.urlHost("fe80::1%wlan0"); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun sameKeyRePairStillRunsPersist() {
        val p = PinPolicy(); var persisted = 0
        assertTrue(p.commitAfterPersist("pc1", ByteArray(32) { 1 }) { persisted++ })
        assertTrue(p.commitAfterPersist("pc1", ByteArray(32) { 1 }) { persisted++ })
        assertEquals(2, persisted)
        assertTrue(!p.commitAfterPersist("pc1", ByteArray(32) { 2 }) { persisted++ }); assertEquals(2, persisted)
    }
}
