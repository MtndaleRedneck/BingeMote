package com.bingemote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Store.vlcTarget(): the direct-VLC tuple (host+port+password) is ONE locked read - never a
 *  mid-re-pair mix - with the legacy fallback and blank password preserved, and a poisoned
 *  secret store yielding an honest null instead of a partial tuple. */
class VlcTargetTest {
    private val pin = "ab".repeat(32)
    private val tok = "a1".repeat(32)
    private val pc = "0".repeat(32)

    /** Minimal JVM SharedPreferences over a map, with test hooks on reads. */
    private open class FakePrefs : android.content.SharedPreferences {
        val m = HashMap<String, Any?>()
        val writeCount = java.util.concurrent.atomic.AtomicInteger(0)
        open fun onGetString(key: String?): String? = m[key] as? String
        override fun getString(key: String?, defValue: String?): String? = onGetString(key) ?: defValue
        override fun getInt(key: String?, defValue: Int): Int = m[key] as? Int ?: defValue
        override fun getAll(): MutableMap<String, *> = HashMap(m)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = m[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = m.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Ed()
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        inner class Ed : android.content.SharedPreferences.Editor {
            private val w = HashMap<String, Any?>(); private val rm = HashSet<String>()
            override fun putString(key: String?, value: String?) = apply { key?.let { w[it] = value; rm.remove(it) } }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { }
            override fun putInt(key: String?, value: Int) = apply { key?.let { w[it] = value } }
            override fun putLong(key: String?, value: Long) = apply { }
            override fun putFloat(key: String?, value: Float) = apply { }
            override fun putBoolean(key: String?, value: Boolean) = apply { key?.let { w[it] = value } }
            override fun remove(key: String?) = apply { key?.let { rm.add(it); w.remove(it) } }
            override fun clear() = apply { }
            override fun commit(): Boolean { go(); return true }
            override fun apply() { go() }
            private fun go() { writeCount.addAndGet(rm.size + w.size); for (r in rm) m.remove(r); for ((k, v) in w) m[k] = v }
        }
    }

    private fun pairedStore(host: String, vlcPw: String): Store {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        val out = SecurePairSave.save(s.securePairStore, host, 8492, "PC", vlcPw, SecureHttp.BoundPin(pin, tok), pc)
        assertEquals(SecurePairSave.Outcome.SAVED, out)
        return s
    }

    /** T1: a secret read that fails mid-snapshot poisons the box; vlcTarget() returns null and
     *  NEVER a host-with-blank-password partial tuple. */
    @Test fun poisonMidReadYieldsNullNeverAPartialTuple() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "10.0.0.5", 8492, "PC", "secretpw", SecureHttp.BoundPin(pin, tok), pc))
        val failing = object : FakePrefs() {
            override fun onGetString(key: String?): String? {
                if (key == "vlcPassword") throw IllegalStateException("disk died")
                return super.onGetString(key)
            }
        }
        failing.m.putAll(secrets.m)
        val poisoned = Store(prefs, failing)
        assertNull(poisoned.vlcTarget())
        assertTrue(poisoned.secureStoreBroken)
    }

    /** T2: a re-pair landing while a reader is parked mid-snapshot must block until the read
     *  finishes: the reader gets the WHOLE old tuple, the next read the WHOLE new one.
     *  Deterministic coordination, no timing assumptions: the reader parks INSIDE the locked
     *  section (latch); the writer signals it has reached the save call and is then required to
     *  reach Thread.State.BLOCKED (it can only be waiting on pairingLock - its first action in
     *  save is the lock) while the instrumented store proves ZERO writes landed. */
    @Test fun rePairDuringAReadNeverProducesAMixedTuple() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "old-host", 8492, "PC", "old-vlc-pw", SecureHttp.BoundPin(pin, tok), pc))

        val parked = CountDownLatch(1); val release = CountDownLatch(1)
        val gate = object : FakePrefs() {
            val firstVlcRead = AtomicBoolean(true)
            override fun onGetString(key: String?): String? {
                if (key == "vlcPassword" && firstVlcRead.compareAndSet(true, false)) {
                    parked.countDown()
                    assertTrue("test setup: release must arrive", release.await(30, TimeUnit.SECONDS))
                }
                return super.onGetString(key)
            }
        }
        gate.m.putAll(secrets.m)
        val gated = Store(prefs, gate)

        val readResult = AtomicReference<VlcTarget?>()
        val reader = Thread { readResult.set(gated.vlcTarget()) }
        reader.start()
        assertTrue("reader should park inside the snapshot", parked.await(30, TimeUnit.SECONDS))

        val writerAtGate = CountDownLatch(1)
        val writerDone = AtomicBoolean(false)
        val proof = SecureHttp.BoundPin(pin, tok)
        val writer = Thread {
            writerAtGate.countDown()                     // the very next action is the save call
            SecurePairSave.save(gated.securePairStore, "new-host", 8492, "PC", "new-vlc-pw", proof, pc)
            writerDone.set(true)
        }
        writer.start()
        assertTrue("writer should reach the save call", writerAtGate.await(30, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals("writer must be BLOCKED on pairingLock while the reader is parked", Thread.State.BLOCKED, writer.state)
        assertEquals("no write may land while the reader is parked", 0, gate.writeCount.get())
        assertFalse(writerDone.get())

        release.countDown()
        reader.join(30_000); writer.join(30_000)
        assertTrue("writer must complete once the read finishes", writerDone.get())
        val old = readResult.get()
        assertEquals(VlcTarget("old-host", 8080, "old-vlc-pw", true), old)   // wholly OLD
        assertEquals(VlcTarget("new-host", 8080, "new-vlc-pw", true), gated.vlcTarget()) // wholly NEW
    }

    /** T3: no secure record -> the legacy plain-prefs fallback, with a BLANK password kept as a
     *  valid member (legacy manual-setup VLC), and a non-blank one passed through. */
    @Test fun legacyFallbackAndBlankPasswordArePreserved() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        prefs.m["host"] = "192.168.1.9"; prefs.m["vlcPort"] = 8081
        val s = Store(prefs, secrets)
        assertEquals(VlcTarget("192.168.1.9", 8081, "", false), s.vlcTarget())  // blank password valid
        secrets.m["vlcPassword"] = "legacypw"
        assertEquals(VlcTarget("192.168.1.9", 8081, "legacypw", false), s.vlcTarget())
        // No host anywhere -> honestly unavailable.
        val empty = Store(FakePrefs(), FakePrefs())
        assertNull(empty.vlcTarget())
    }

    /** T4: the mutation generation advances on EVERY writer - a SecurePairSave save, an address
     *  update, every locked legacy setter, a forget - and NEVER on reads. */
    @Test fun generationAdvancesOnWritersNeverOnReads() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        var g = s.pairingGeneration
        s.vlcTarget(); s.helperVlcSnapshot(); s.pollSnapshot(); s.helperToken; s.host; s.isConfigured
        assertEquals("reads never advance the generation", g, s.pairingGeneration)

        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "10.0.0.5", 8492, "PC", "pw", SecureHttp.BoundPin(pin, tok), pc))
        assertTrue(s.pairingGeneration > g); g = s.pairingGeneration

        val rec = s.securePairStore.credential
        assertTrue(SecurePairSave.updateAddress(s.securePairStore, rec, "10.0.0.6", 8492))
        assertTrue(s.pairingGeneration > g); g = s.pairingGeneration

        s.host = "10.0.0.7";   assertTrue(s.pairingGeneration > g); g = s.pairingGeneration
        s.helperPort = 8489;   assertTrue(s.pairingGeneration > g); g = s.pairingGeneration
        s.vlcPort = 8081;      assertTrue(s.pairingGeneration > g); g = s.pairingGeneration
        s.helperToken = "x";   assertTrue(s.pairingGeneration > g); g = s.pairingGeneration
        s.vlcPassword = "y";   assertTrue(s.pairingGeneration > g); g = s.pairingGeneration
        s.pcName = "Z";        assertTrue(s.pairingGeneration > g); g = s.pairingGeneration

        assertTrue(SecurePairSave.forget(s.securePairStore))
        assertTrue(s.pairingGeneration > g)
    }

    /** T5 (deterministic, no sleeps): the stream-guard semantics. A generation captured at tap
     *  time is STILL VALID while a writer is merely waiting for the lock (no spurious drop), and
     *  is invalidated once that writer's locked section completes (drop, never retarget). The
     *  reader parks inside its locked snapshot; the writer must reach Thread.State.BLOCKED (its
     *  first action in save is the pairingLock monitor) with zero writes landed; after release
     *  the save completes and the generation has advanced. */
    @Test fun tapGenerationDropsOnlyOnACompletedMutation() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "old-host", 8492, "PC", "old-pw", SecureHttp.BoundPin(pin, tok), pc))

        val parked = CountDownLatch(1); val release = CountDownLatch(1)
        val gate = object : FakePrefs() {
            val firstVlcRead = AtomicBoolean(true)
            override fun onGetString(key: String?): String? {
                if (key == "vlcPassword" && firstVlcRead.compareAndSet(true, false)) {
                    parked.countDown()
                    assertTrue("test setup: release must arrive", release.await(30, TimeUnit.SECONDS))
                }
                return super.onGetString(key)
            }
        }
        gate.m.putAll(secrets.m)
        val gated = Store(prefs, gate)

        val reader = Thread { gated.vlcTarget() }
        reader.start()
        assertTrue("reader should park inside the snapshot", parked.await(30, TimeUnit.SECONDS))

        val tapGen = gated.pairingGeneration   // the tap: storage-free, taken while a reader holds the lock

        val writerAtGate = CountDownLatch(1); val writerDone = AtomicBoolean(false)
        val writer = Thread {
            writerAtGate.countDown()           // the very next action is the save call
            SecurePairSave.save(gated.securePairStore, "new-host", 8492, "PC", "new-pw", SecureHttp.BoundPin(pin, tok), pc)
            writerDone.set(true)
        }
        writer.start()
        assertTrue("writer should reach the save call", writerAtGate.await(30, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals("writer must be BLOCKED on pairingLock", Thread.State.BLOCKED, writer.state)
        assertEquals("no write may have landed while the writer is parked", 0, gate.writeCount.get())

        // A merely-WAITING writer has not mutated anything: the tap's generation is still valid.
        assertEquals("no spurious drop while the writer only waits", tapGen, gated.pairingGeneration)

        release.countDown()
        reader.join(30_000); writer.join(30_000)
        assertTrue("writer must complete once the read finishes", writerDone.get())
        // The completed mutation advanced the generation: a capture holding tapGen is dropped.
        assertTrue("a completed mutation invalidates the tap's generation", gated.pairingGeneration != tapGen)
    }

    /** T6: the poll guard shape - a deferred re-pair landing between a cycle's snapshot capture
     *  and an async outcome's publish moment invalidates the cycle (every outcome drops), and
     *  the NEW pairing is whole and coherent for the next cycle. */
    @Test fun deferredRePairBeforeAwaitDropsOldCycleOutcome() {
        val s = pairedStore("old-host", "old-pw")
        val gen0 = s.pairingGeneration
        val snap = s.pollSnapshot()
        assertEquals("old-host", snap.host)
        assertFalse(snap.broken)
        // The cycle's async work is in flight when the re-pair completes.
        val pin2 = "cd".repeat(32); val tok2 = "b2".repeat(32); val pc2 = "1".repeat(32)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "new-host", 8492, "PC2", "new-pw", SecureHttp.BoundPin(pin2, tok2), pc2))
        // The publish-moment guard observes the drift: every outcome of the old cycle drops.
        assertTrue(s.pairingGeneration != gen0)
        // The dropped cycle touched nothing: the NEW pairing is intact.
        assertEquals(HelperVlcSnapshot("new-host", 8492, tok2, 8080, "new-pw", true), s.helperVlcSnapshot())
        val next = s.pollSnapshot()
        assertEquals("new-host", next.host)
        assertEquals(tok2, next.helperToken)
        assertEquals(pc2, next.pcId)
        assertTrue(next.secure)
        assertFalse(next.broken)
    }

    /** T7: pollSnapshot carries the VLC port and marks a poisoned store honestly (broken=true,
     *  never a false-green snapshot), matching its siblings' post-read health checks. */
    @Test fun pollSnapshotCarriesVlcPortAndMarksPoison() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "10.0.0.5", 8492, "PC", "pw", SecureHttp.BoundPin(pin, tok), pc))
        assertEquals(PollSnapshot("10.0.0.5", 8492, tok, 8080, "pw", pc, true, false,
            PairedCredential.of(pc, pin, "10.0.0.5", 8492, tok)?.encode()), s.pollSnapshot())

        val failing = object : FakePrefs() {
            override fun onGetString(key: String?): String? {
                if (key == "vlcPassword") throw IllegalStateException("disk died")
                return super.onGetString(key)
            }
        }
        failing.m.putAll(secrets.m)
        val poisoned = Store(prefs, failing)
        assertTrue(poisoned.pollSnapshot().broken)
    }

    /** T8 (deterministic, no sleeps): helperVlcSnapshot - the primitive the subtitle origins and
     *  the helper listings now share - is wholly-old or wholly-new when a re-pair lands mid-
     *  capture, never a mixed triple. Same latch/BLOCKED/write-count coordination as T2: the
     *  reader parks INSIDE the locked section (on the first legacy-token read inside the nested
     *  load), the writer must reach Thread.State.BLOCKED with zero writes landed, then completes
     *  after release. */
    @Test fun helperVlcSnapshotIsNeverAMixedTriple() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "old-host", 8492, "PC", "old-pw", SecureHttp.BoundPin(pin, tok), pc))

        val parked = CountDownLatch(1); val release = CountDownLatch(1)
        val gate = object : FakePrefs() {
            val firstTokenRead = AtomicBoolean(true)
            override fun onGetString(key: String?): String? {
                if (key == "helperToken" && firstTokenRead.compareAndSet(true, false)) {
                    parked.countDown()
                    assertTrue("test setup: release must arrive", release.await(30, TimeUnit.SECONDS))
                }
                return super.onGetString(key)
            }
        }
        gate.m.putAll(secrets.m)
        val gated = Store(prefs, gate)

        val readResult = AtomicReference<HelperVlcSnapshot?>()
        val reader = Thread { readResult.set(gated.helperVlcSnapshot()) }
        reader.start()
        assertTrue("reader should park inside the snapshot", parked.await(30, TimeUnit.SECONDS))

        val tok2 = "b2".repeat(32); val pc2 = "1".repeat(32)
        val writerAtGate = CountDownLatch(1); val writerDone = AtomicBoolean(false)
        val writer = Thread {
            writerAtGate.countDown()
            SecurePairSave.save(gated.securePairStore, "new-host", 8493, "PC2", "new-pw", SecureHttp.BoundPin(pin, tok2), pc2)
            writerDone.set(true)
        }
        writer.start()
        assertTrue("writer should reach the save call", writerAtGate.await(30, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals("writer must be BLOCKED on pairingLock", Thread.State.BLOCKED, writer.state)
        assertEquals("no write may land while the reader is parked", 0, gate.writeCount.get())

        release.countDown()
        reader.join(30_000); writer.join(30_000)
        assertTrue("writer must complete once the read finishes", writerDone.get())
        assertEquals(HelperVlcSnapshot("old-host", 8492, tok, 8080, "old-pw", true), readResult.get())   // wholly OLD
        assertEquals(HelperVlcSnapshot("new-host", 8493, tok2, 8080, "new-pw", true), gated.helperVlcSnapshot()) // wholly NEW
    }

    /** T9 (deterministic, no sleeps): a re-pair landing while the adoption fetch is in flight -
     *  the stale heal SKIPS the password write (the old PC's password never lands on the new
     *  pairing), never reaches ack, reports failure, and does NOT clear the request-id slot. */
    @Test fun healSkipsWriteAndClearWhenRePairLandsDuringFetch() {
        val s = pairedStore("old-host", "old-pw")
        val fetched = CountDownLatch(1); val releaseFetch = CountDownLatch(1)
        val seenReqId = AtomicReference<String?>(); val ackCalled = AtomicBoolean(false)
        val adopt: (String, Int, String, String) -> String? = { _, _, _, r ->
            seenReqId.set(r)
            fetched.countDown()
            assertTrue("test setup: release must arrive", releaseFetch.await(30, TimeUnit.SECONDS))
            "adopted-old-pc-pw"
        }
        val ack: (String, Int, String, String) -> Boolean = { _, _, _, _ -> ackCalled.set(true); true }
        val healResult = AtomicReference<String?>()
        val healer = Thread { healResult.set(VlcAuthRepair.tryHealOnce(s, adopt, ack)) }
        healer.start()
        assertTrue("heal should be inside the fetch", fetched.await(30, TimeUnit.SECONDS))
        val tok2 = "b2".repeat(32); val pc2 = "1".repeat(32)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "new-host", 8492, "PC2", "new-pw", SecureHttp.BoundPin(pin, tok2), pc2))
        releaseFetch.countDown()
        healer.join(30_000)
        assertNull("the stale heal must report it wrote nothing", healResult.get())
        assertFalse("the stale heal never acks", ackCalled.get())
        // The old PC's adopted password never landed on the NEW pairing.
        assertEquals(HelperVlcSnapshot("new-host", 8492, tok2, 8080, "new-pw", true), s.helperVlcSnapshot())
        // The request-id slot is preserved (phone-chosen id; the helper binds it on first serve).
        assertEquals(seenReqId.get(), s.vlcAdoptRequestId)
    }

    /** T10 (deterministic, no sleeps): a re-pair landing between the (legitimate, while-current)
     *  password write and the ack - the write stood, but the stale heal's CLEAR is skipped: the
     *  request-id slot survives for the pairing it belongs to. */
    @Test fun healClearSkippedWhenRePairLandsBetweenWriteAndAck() {
        val s = pairedStore("old-host", "old-pw")
        val acking = CountDownLatch(1); val releaseAck = CountDownLatch(1)
        val seenReqId = AtomicReference<String?>()
        val adopt: (String, Int, String, String) -> String? = { _, _, _, r -> seenReqId.set(r); "adopted-pw" }
        val ack: (String, Int, String, String) -> Boolean = { _, _, _, _ ->
            acking.countDown()
            assertTrue("test setup: release must arrive", releaseAck.await(30, TimeUnit.SECONDS))
            true
        }
        val healResult = AtomicReference<String?>()
        val healer = Thread { healResult.set(VlcAuthRepair.tryHealOnce(s, adopt, ack)) }
        healer.start()
        assertTrue("heal should be inside the ack", acking.await(30, TimeUnit.SECONDS))
        val tok2 = "b2".repeat(32); val pc2 = "1".repeat(32)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "new-host", 8492, "PC2", "new-pw", SecureHttp.BoundPin(pin, tok2), pc2))
        releaseAck.countDown()
        healer.join(30_000)
        assertEquals("the heal surfaces the password it wrote", "adopted-pw", healResult.get())
        // The re-pair's own password stands; the stale clear did not erase the slot.
        assertEquals(HelperVlcSnapshot("new-host", 8492, tok2, 8080, "new-pw", true), s.helperVlcSnapshot())
        assertEquals(seenReqId.get(), s.vlcAdoptRequestId)
    }

    /** T11: the intact-pairing heal still adopts, writes and clears; and a LEGACY pairing (no
     *  secure record) heals through the same fallback identity as before. */
    @Test fun healAdoptsWritesAndClearsOnAnIntactPairing() {
        val s = pairedStore("old-host", "old-pw")
        val adopt: (String, Int, String, String) -> String? = { _, _, _, _ -> "fresh-pw" }
        val ack: (String, Int, String, String) -> Boolean = { _, _, _, _ -> true }
        assertEquals("fresh-pw", VlcAuthRepair.tryHealOnce(s, adopt, ack))
        assertEquals("fresh-pw", s.vlcPassword)
        assertNull(s.vlcAdoptRequestId)

        val prefs = FakePrefs(); val secrets = FakePrefs()
        prefs.m["host"] = "192.168.1.9"; secrets.m["helperToken"] = "legacytok"
        val legacy = Store(prefs, secrets)
        val seen = AtomicReference<String?>()
        val adoptL: (String, Int, String, String) -> String? = { h, _, t, _ -> seen.set("$h|$t"); "fresh-pw" }
        assertEquals("fresh-pw", VlcAuthRepair.tryHealOnce(legacy, adoptL, ack))
        assertEquals("192.168.1.9|legacytok", seen.get())
        assertEquals("fresh-pw", legacy.vlcPassword)
        assertNull(legacy.vlcAdoptRequestId)
    }

    /** T15 (deterministic, no sleeps; the I2 same-record case): a save landing mid-adopt with
     *  an IDENTICAL encoded credential (same pcId/pin/host/port/token) but a NEWER VLC
     *  password. The heal's compare binds the captured password, so the stale adopted value
     *  never overwrites the newer one; the heal reports failure, never acks, and the
     *  request-id slot is preserved. */
    @Test fun healSkipsWriteWhenIdenticalRecordSavedWithNewerPasswordMidAdopt() {
        val s = pairedStore("old-host", "old-pw")
        val fetched = CountDownLatch(1); val releaseFetch = CountDownLatch(1)
        val seenReqId = AtomicReference<String?>(); val ackCalled = AtomicBoolean(false)
        val adopt: (String, Int, String, String) -> String? = { _, _, _, r ->
            seenReqId.set(r)
            fetched.countDown()
            assertTrue("test setup: release must arrive", releaseFetch.await(30, TimeUnit.SECONDS))
            "stale-adopted-pw"
        }
        val ack: (String, Int, String, String) -> Boolean = { _, _, _, _ -> ackCalled.set(true); true }
        val healResult = AtomicReference<String?>()
        val healer = Thread { healResult.set(VlcAuthRepair.tryHealOnce(s, adopt, ack)) }
        healer.start()
        assertTrue("heal should be inside the fetch", fetched.await(30, TimeUnit.SECONDS))
        // IDENTICAL credential (same pin/token/pcId/host/port via the real writer), NEWER password.
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "old-host", 8492, "PC", "newer-pw", SecureHttp.BoundPin(pin, tok), pc))
        releaseFetch.countDown()
        healer.join(30_000)
        assertNull("the stale heal must report it wrote nothing", healResult.get())
        assertFalse("the stale heal never acks", ackCalled.get())
        // The newer password stands; the heal's stale adopted value never landed.
        assertEquals("newer-pw", s.vlcPassword)
        assertEquals(seenReqId.get(), s.vlcAdoptRequestId)
    }

    /** T16: the post-heal ownership decision accepts exactly the states this cycle can own -
     *  the captured original when the heal wrote nothing, or THIS heal's own adopted value
     *  when it wrote. Same-encode newer-password saves (mid-adopt or post-write) and a
     *  restored original are another writer's state: no outcome may publish for them. */
    @Test fun postHealOwnershipBindsOnlyOwnedPasswordStates() {
        val enc = PairedCredential.of(pc, pin, "10.0.0.5", 8492, tok)!!.encode()
        // Heal wrote nothing: only the captured ORIGINAL password proves ownership.
        assertTrue(VlcAuthRepair.postHealOwnershipOk(enc, "orig", null, enc, "orig"))
        // Same-encode save with a NEWER password mid-adopt (heal refused, wrote nothing): not owned.
        assertFalse(VlcAuthRepair.postHealOwnershipOk(enc, "orig", null, enc, "newer-pw"))
        // Heal wrote: its OWN adopted value is the one owned state.
        assertTrue(VlcAuthRepair.postHealOwnershipOk(enc, "orig", "adopted", enc, "adopted"))
        // The ORIGINAL value again after our write means another writer restored it: not owned.
        assertFalse(VlcAuthRepair.postHealOwnershipOk(enc, "orig", "adopted", enc, "orig"))
        // Same-record replacement with a different password after our write, before ack: not owned.
        assertFalse(VlcAuthRepair.postHealOwnershipOk(enc, "orig", "adopted", enc, "third-pw"))
        // Identity moved: a different or absent record never publishes.
        val enc2 = PairedCredential.of(pc, pin, "10.0.0.6", 8492, tok)!!.encode()
        assertFalse(VlcAuthRepair.postHealOwnershipOk(enc, "orig", "adopted", enc2, "adopted"))
        assertFalse(VlcAuthRepair.postHealOwnershipOk(enc, "orig", null, null, "orig"))
        // Legacy cycles (no original encode) never publish, same as before this binding.
        assertFalse(VlcAuthRepair.postHealOwnershipOk(null, "orig", null, null, "orig"))
        // A poisoned read (null current password) fails closed.
        assertFalse(VlcAuthRepair.postHealOwnershipOk(enc, "orig", null, enc, null))
    }

    /** T17 (deterministic): an ACK THROW after the heal's write landed must still surface the
     *  truthful own-write evidence (the adopted password), never null - null would reclassify
     *  the write as "wrote nothing" and let a restored-original state pass the ownership
     *  predicate. The request-id slot stays for replay (existing ACK/retry policy). */
    @Test fun ackThrowAfterWriteStillReturnsTruthfulWriteEvidence() {
        val s = pairedStore("old-host", "old-pw")
        val adopt: (String, Int, String, String) -> String? = { _, _, _, _ -> "adopted-pw" }
        val ack: (String, Int, String, String) -> Boolean = { _, _, _, _ -> throw RuntimeException("ack lost") }
        val evidence = VlcAuthRepair.tryHealOnce(s, adopt, ack)
        assertEquals("the landed write must surface as evidence", "adopted-pw", evidence)
        assertEquals("adopted-pw", s.vlcPassword)
        assertNotNull("slot preserved for replay after a lost ack", s.vlcAdoptRequestId)
        // A writer restoring the ORIGINAL password after this write must NOT pass ownership:
        val enc = (s.helperPairing() as PairedCredential.State.Secure).credential.encode()
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "old-host", 8492, "PC", "old-pw", SecureHttp.BoundPin(pin, tok), pc))
        val cur = s.helperVlcSnapshot()!!.vlcPassword
        assertEquals("old-pw", cur) // the restore really landed
        assertFalse(VlcAuthRepair.postHealOwnershipOk(enc, "old-pw", evidence, enc, cur))
    }

    /** T12: the conditional moviesPath write lands only while the pairing generation still
     *  matches the fetch's capture - a re-pair in between skips the old PC's value whole. */
    @Test fun conditionalMoviesPathWriteIsSkippedAfterARePair() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "old-host", 8492, "PC", "pw", SecureHttp.BoundPin(pin, tok), pc))
        val fetchGen = s.pairingGeneration
        assertTrue(s.setMoviesPathIfCurrent("C:\\Movies", fetchGen))
        assertEquals("C:\\Movies", s.moviesPath)
        // A re-pair lands between the fetch and the write: the old PC's value is skipped whole.
        val tok2 = "b2".repeat(32); val pc2 = "1".repeat(32)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "new-host", 8492, "PC2", "new-pw", SecureHttp.BoundPin(pin, tok2), pc2))
        assertFalse(s.setMoviesPathIfCurrent("D:\\OldPc", fetchGen))
        assertEquals("C:\\Movies", s.moviesPath)
        // The current generation still writes.
        assertTrue(s.setMoviesPathIfCurrent("E:\\NewPc", s.pairingGeneration))
        assertEquals("E:\\NewPc", s.moviesPath)
    }

    /** T13: the snapshot-with-generation capture carries the generation OF THE SNAPSHOT's
     *  record from one locked section, so a re-pair during the folder fetch makes the
     *  conditional write skip - the regression where the generation was read AFTER the
     *  fetch and matched the new pairing again. */
    @Test fun snapshotWithGenerationSkipsAFolderFetchedAcrossARePair() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "old-host", 8492, "PC", "pw", SecureHttp.BoundPin(pin, tok), pc))
        // Pre-fetch capture, as the Compose effects now do: snapshot + generation together.
        val (snap, gen) = s.helperVlcSnapshotWithGeneration()
        assertEquals("old-host", snap?.host)
        assertEquals(s.pairingGeneration, gen)
        // The fetch runs (not modeled), then a re-pair lands before the conditional write.
        val tok2 = "b2".repeat(32); val pc2 = "1".repeat(32)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "new-host", 8492, "PC2", "new-pw", SecureHttp.BoundPin(pin, tok2), pc2))
        assertTrue(s.pairingGeneration != gen)
        // The old PC's folder value keyed on the pre-fetch capture is skipped whole...
        assertFalse(s.setMoviesPathIfCurrent("D:\\OldPc", gen))
        // ...and a fresh capture names the new record with its new generation.
        val (snap2, gen2) = s.helperVlcSnapshotWithGeneration()
        assertEquals("new-host", snap2?.host)
        assertEquals(s.pairingGeneration, gen2)
        assertTrue(s.setMoviesPathIfCurrent("E:\\NewPc", gen2))
        assertEquals("E:\\NewPc", s.moviesPath)
    }

    /** T14: one command sequence owns the pairing it captured - snapshot and generation from
     *  one locked section, every send uses the snapshot's fields, and a re-pair mid-sequence
     *  drops the rest (isCurrent false) instead of redirecting onto the new pairing. */
    @Test fun commandSequenceOwnsItsCapturedPairing() {
        val prefs = FakePrefs(); val secrets = FakePrefs()
        val s = Store(prefs, secrets)
        // Unpaired: no sequence - the same honest-unavailable as a blank-host snapshot.
        assertNull(s.pairingSequence())
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "old-host", 8492, "PC", "pw", SecureHttp.BoundPin(pin, tok), pc))
        val seq = s.pairingSequence()!!
        // One coherent target: helper endpoint AND VLC credentials of the SAME record.
        assertEquals("old-host", seq.snapshot.host)
        assertEquals(8492, seq.snapshot.helperPort)
        assertEquals(tok, seq.snapshot.helperToken)
        assertEquals("pw", seq.snapshot.vlcPassword)
        assertTrue(seq.isCurrent())
        // A re-pair mid-sequence: the captured target still names the old record, and the
        // sequence reports itself dropped rather than following the new pairing.
        val tok2 = "b2".repeat(32); val pc2 = "1".repeat(32)
        assertEquals(SecurePairSave.Outcome.SAVED,
            SecurePairSave.save(s.securePairStore, "new-host", 8492, "PC2", "new-pw", SecureHttp.BoundPin(pin, tok2), pc2))
        assertFalse(seq.isCurrent())
        assertEquals("old-host", seq.snapshot.host)
        // A fresh sequence owns the new pairing.
        val seq2 = s.pairingSequence()!!
        assertTrue(seq2.isCurrent())
        assertEquals("new-host", seq2.snapshot.host)
    }

    /** T18 (deterministic, no sleeps): pairingStatusWithGeneration - the setup flow's ONE
     *  locked (token?, password?, generation) capture - never mixes one pairing's token
     *  with another's password. The reader parks INSIDE the locked section (gated on the
     *  vlcPassword read); a real save then BLOCKS on pairingLock, so the capture returns
     *  wholly OLD with the OLD generation. */
    @Test fun pairingStatusCaptureNeverMixesRecords() {
        val prefs = FakePrefs()
        val reading = CountDownLatch(1); val releaseRead = CountDownLatch(1)
        val gated = object : FakePrefs() {
            override fun onGetString(key: String?): String? {
                if (key == "vlcPassword" && reading.count > 0) {
                    reading.countDown()
                    assertTrue("test setup: release must arrive", releaseRead.await(30, TimeUnit.SECONDS))
                }
                return super.onGetString(key)
            }
        }
        gated.m["helperToken"] = "tok"   // OLD pairing: token present, password BLANK
        val s = Store(prefs, gated)
        val result = AtomicReference<Triple<Boolean, Boolean, Long>?>()
        val reader = Thread { result.set(s.pairingStatusWithGeneration()) }
        reader.start()
        assertTrue("reader should park mid-capture", reading.await(30, TimeUnit.SECONDS))
        val genBefore = s.pairingGeneration
        val saveOutcome = AtomicReference<SecurePairSave.Outcome?>()
        val writer = Thread { saveOutcome.set(SecurePairSave.save(s.securePairStore, "new-host", 8492, "PC", "new-pw", SecureHttp.BoundPin(pin, tok), pc)) }
        writer.start()
        val deadline = System.nanoTime() + 30_000_000_000L
        while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) { }
        assertEquals("the writer must BLOCK on the capture's lock", Thread.State.BLOCKED, writer.state)
        releaseRead.countDown()
        reader.join(30_000); writer.join(30_000)
        // Wholly OLD: old token present, old (blank) password, old generation - never a mix.
        assertEquals(Triple(true, false, genBefore), result.get())
        assertEquals(SecurePairSave.Outcome.SAVED, saveOutcome.get())
        // After the writer lands, a fresh capture is wholly NEW with a NEWER generation.
        val after = s.pairingStatusWithGeneration()
        assertEquals(Triple(true, true, after.third), after)
        assertTrue("the save bumps the generation", after.third > genBefore)
    }
}
