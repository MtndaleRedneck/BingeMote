package com.bingemote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.cert.CertificateException

class PinPolicyTest {
    private fun h(b: Int) = ByteArray(32) { b.toByte() }

    @Test fun unknownPcIsRefusedWhenNotEnrolling() {
        assertEquals(PinResult.MISMATCH, PinPolicy().check("pc1", h(1), enroll = false))
    }

    @Test fun unknownPcIsRememberedOnlyDuringEnroll() {
        assertEquals(PinResult.FIRST_USE_REMEMBERED, PinPolicy().check("pc1", h(1), enroll = true))
    }

    @Test fun checkAloneNeverStoresAKey() {
        val p = PinPolicy()
        p.check("pc1", h(1), enroll = true)
        assertFalse(p.has("pc1"))
    }

    @Test fun committedKeyMatchesAndOtherKeyHardFailsEvenWhileEnrolling() {
        val p = PinPolicy()
        assertTrue(p.commit("pc1", h(1)))
        assertEquals(PinResult.MATCH, p.check("pc1", h(1), enroll = false))
        assertEquals(PinResult.MISMATCH, p.check("pc1", h(2), enroll = false))
        assertEquals(PinResult.MISMATCH, p.check("pc1", h(2), enroll = true))
    }

    @Test fun commitNeverOverwritesADifferentKey() {
        val p = PinPolicy()
        p.commit("pc1", h(1))
        assertFalse(p.commit("pc1", h(2)))
        assertEquals(PinResult.MATCH, p.check("pc1", h(1), enroll = false))
    }

    @Test fun commitRejectsWrongLength() {
        assertFalse(PinPolicy().commit("pc1", ByteArray(5)))
    }

    @Test fun forgetIsTheOnlyWayToReplaceAKey() {
        val p = PinPolicy()
        p.commit("pc1", h(1))
        assertTrue(p.forget("pc1"))
        assertEquals(PinResult.FIRST_USE_REMEMBERED, p.check("pc1", h(2), enroll = true))
    }

    @Test fun pinsAreKeyedPerPc() {
        val p = PinPolicy()
        p.commit("pc1", h(1))
        assertEquals(PinResult.MISMATCH, p.check("pc2", h(1), enroll = false))
    }

    @Test fun initialMapIsCopied() {
        val k = h(1)
        val p = PinPolicy(mapOf("pc1" to k))
        k[0] = 9
        assertEquals(PinResult.MATCH, p.check("pc1", h(1), enroll = false))
    }

    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded

    @Test fun trustManagerRecordsObservedHashAndCommitsTheSameKey() {
        val pub = key(); val p = PinPolicy()
        val tm = PinningTrustManager(p, "pc1", enroll = true)
        tm.checkSpki(pub)
        assertEquals(PinResult.FIRST_USE_REMEMBERED, tm.result)
        assertTrue(p.commit("pc1", tm.observedHash!!))
        PinningTrustManager(p, "pc1", enroll = false).checkSpki(pub)
    }

    @Test fun trustManagerRejectsAnotherKeyAndAnUnknownPcOutsideEnroll() {
        val p = PinPolicy(); p.commit("pc1", PinPolicy.hashSpki(key()))
        try { PinningTrustManager(p, "pc1", false).checkSpki(key()); fail() } catch (e: CertificateException) {}
        try { PinningTrustManager(PinPolicy(), "pc9", false).checkSpki(key()); fail() } catch (e: CertificateException) {}
    }

    @Test fun trustManagerRejectsEmptyChainAndClientCerts() {
        val tm = PinningTrustManager(PinPolicy(), "pc1", true)
        try { tm.checkServerTrusted(emptyArray(), "x"); fail() } catch (e: CertificateException) {}
        try { tm.checkClientTrusted(emptyArray(), "x"); fail() } catch (e: CertificateException) {}
        assertNull(tm.observedHash)
    }

    @Test fun trustManagerRefusesALaterDifferentKeyAfterTheFirst() {
        val tm = PinningTrustManager(PinPolicy(), "pc1", enroll = true)
        val a = key(); tm.checkSpki(a)
        tm.checkSpki(a)
        try { tm.checkSpki(key()); fail() } catch (e: CertificateException) {}
        assertTrue(tm.observedHash!!.contentEquals(PinPolicy.hashSpki(a)))
    }

    @Test fun persistFailureLeavesNoPinInMemory() {
        val p = PinPolicy()
        try { p.commitAfterPersist("pc1", h(1)) { throw IllegalStateException("disk") }; fail() } catch (e: IllegalStateException) {}
        assertFalse(p.has("pc1"))
    }

    @Test fun persistRunsBeforePublishAndConflictSkipsPersist() {
        val p = PinPolicy(); var seenBefore = true
        assertTrue(p.commitAfterPersist("pc1", h(1)) { seenBefore = p.has("pc1") })
        assertFalse(seenBefore); assertTrue(p.has("pc1"))
        var called = false
        assertFalse(p.commitAfterPersist("pc1", h(2)) { called = true })
        assertFalse(called)
    }

    @Test fun mismatchMarksSharedStateAndOnlyAFreshIdentityLeavesIt() {
        KeyChangeState.resetForTests()
        val policy = PinPolicy(mapOf("pcA" to ByteArray(32) { 1 }))
        val k1 = ByteArray(40) { 9 }
        try { PinningTrustManager(policy, "pcA", false).checkSpki(k1); fail("accepted a different key") } catch (e: PinMismatchException) { }
        assertTrue(KeyChangeState.isChanged("pcA"))
        // a brand-new trust manager object (a new websocket, a new request) sees the same state
        assertTrue(KeyChangeState.isChanged("pcA"))
        // another stored identity (an explicit re-pair creates one) is not affected
        assertFalse(KeyChangeState.isChanged("pcB"))
        assertFalse(KeyChangeState.isChanged(null))
        KeyChangeState.resetForTests()
    }
}
