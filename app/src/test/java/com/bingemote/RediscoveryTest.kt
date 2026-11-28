package com.bingemote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * DHCP rediscovery through the PRODUCTION seam (applySecureRediscovery -> HelperClient.probePaired
 * -> Store.updateSecureAddressAndGeneration -> SecurePairSave.updateAddress) over real TLS
 * handshakes with throwaway keytool identities, the same fixture style as SecureHttpTest.
 */
class RediscoveryTest {
    private class Identity(val ctx: SSLContext, val spki: ByteArray)
    private class Srv(val port: Int, val stop: () -> Unit)

    private val pc = "cd".repeat(16)      // 32 hex, the stored pcId
    private val tok = "ab".repeat(32)     // 64 hex, the helper-issued token shape
    private val otherTok = "ef".repeat(32)

    private fun identity(): Identity {
        val dir = java.nio.file.Files.createTempDirectory("redis").toFile()
        val ks = File(dir, "k.p12")
        val rc = ProcessBuilder("keytool", "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=pc", "-validity", "30", "-storetype", "PKCS12", "-keystore", ks.path,
            "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        check(rc == 0) { "keytool failed" }
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        return Identity(SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }, store.getCertificate("s").publicKey.encoded)
    }

    /** One accepted TLS connection: read the request, answer by path and (when set) token. */
    private fun serveOne(s: SSLSocket, requiredToken: String?) {
        try {
            s.soTimeout = 5000
            val r = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream()))
            val requestLine = r.readLine() ?: return
            var token: String? = null; var length = 0
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("X-BingeMote-Token:", ignoreCase = true)) token = line.substringAfter(':').trim()
                if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
            }
            if (length > 0) r.read(CharArray(length))
            val path = requestLine.split(" ").getOrElse(1) { "" }
            val code = if (requiredToken != null && token != requiredToken) 401 else if (path == "/ping") 200 else 404
            s.getOutputStream().apply { write("HTTP/1.1 $code X\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()); flush() }
        } finally { try { s.close() } catch (_: Exception) {} }
    }

    /** The fake helper: /ping 200 only with the required token (when set), else 401; every other
     *  path 404. Tolerates handshake-then-close probes (no request bytes ever arrive). */
    private fun server(id: Identity, requiredToken: String? = null): Srv {
        val ss = id.ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val t = Thread {
            try {
                while (true) {
                    val s = ss.accept() as SSLSocket
                    Thread { try { serveOne(s, requiredToken) } catch (e: Exception) { try { s.close() } catch (_: Exception) {} } }.start()
                }
            } catch (e: Exception) { }
        }.apply { isDaemon = true; start() }
        return Srv(ss.localPort) { try { ss.close() } catch (_: Exception) {} ; t.interrupt() }
    }

    /** Key flip: the FIRST connection presents idA, every later one idB - the address showed the
     *  stored key once and a different key before the token was ever sent. */
    private fun flipServer(a: Identity, b: Identity): Srv {
        val ss = ServerSocket(0)
        val n = java.util.concurrent.atomic.AtomicInteger(0)
        val t = Thread {
            try {
                while (true) {
                    val raw = ss.accept()
                    val id = if (n.getAndIncrement() == 0) a else b
                    Thread {
                        var s: SSLSocket? = null
                        try {
                            s = id.ctx.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
                            s.useClientMode = false
                            serveOne(s, null)
                        } catch (e: Exception) { try { s?.close() } catch (_: Exception) {} }
                    }.start()
                }
            } catch (e: Exception) { }
        }.apply { isDaemon = true; start() }
        return Srv(ss.localPort) { try { ss.close() } catch (_: Exception) {} ; t.interrupt() }
    }

    private fun pairedStore(id: Identity, port: Int): Pair<Store, String> {
        val s = Store(VlcTargetTestFakePrefs(), VlcTargetTestFakePrefs())
        val pinHex = PairedCredential.toHex(PinPolicy.hashSpki(id.spki))
        val r = SecurePairSave.save(s.securePairStore, "127.0.0.1", port, "PC", "pw", SecureHttp.BoundPin(pinHex, tok), pc)
        assertEquals(SecurePairSave.Outcome.SAVED, r)
        return s to s.pollSnapshot().encode!!
    }

    private fun candidate(port: Int) = HelperClient.DiscoveredHelper("127.0.0.1", port, "FakePC")

    @Test fun samePcAtNewAddressMovesOnlyHostPortAndReturnsTheCasGeneration() {
        KeyChangeState.resetForTests()
        val id = identity()
        val a = server(id, requiredToken = tok)
        val (store, _) = pairedStore(id, a.port)
        a.stop() // the old address dies; the SAME key answers at a new one
        val b = server(id, requiredToken = tok)
        try {
            val before = store.pairingGeneration
            val r = applySecureRediscovery(store, listOf(candidate(b.port)))
            assertNotNull(r)
            assertEquals(b.port, r!!.port)
            assertEquals(tok, r.token)
            assertTrue(r.generation > before)
            // The publish gate's comparison input holds: no repair landed, generation unchanged.
            assertEquals(r.generation, store.pairingGeneration)
            val after = store.pollSnapshot()
            assertEquals(b.port, after.helperPort)
            assertEquals(pc, after.pcId)
            assertEquals(tok, after.helperToken)
            assertTrue(HelperClient.ping("127.0.0.1", b.port, tok)) // the gate flows to the new address
        } finally { b.stop(); KeyChangeState.resetForTests() }
    }

    @Test fun wrongKeyTlsResponderIsRefusedAndNeverLatchesKeyChanged() {
        KeyChangeState.resetForTests()
        val id = identity()
        val a = server(id, requiredToken = tok)
        val (store, expected) = pairedStore(id, a.port)
        a.stop()
        val rogue = server(identity(), requiredToken = tok) // a different key, valid TLS
        try {
            assertNull(applySecureRediscovery(store, listOf(candidate(rogue.port))))
            assertEquals(false, KeyChangeState.isChanged(pc)) // an unverified address must not latch
            assertEquals(expected, store.pollSnapshot().encode) // record untouched
        } finally { rogue.stop(); KeyChangeState.resetForTests() }
    }

    @Test fun keyFlipBetweenProbeAndPingIsRefusedAndNeverLatches() {
        KeyChangeState.resetForTests()
        val id = identity()
        val a = server(id, requiredToken = tok)
        val (store, expected) = pairedStore(id, a.port)
        a.stop()
        val flip = flipServer(id, identity()) // handshake 1 matches the pin, handshake 2 does not
        try {
            val c = PairedCredential.decode(expected)!!
            assertEquals(false, HelperClient.probePaired(c, "127.0.0.1", flip.port))
            assertEquals(false, KeyChangeState.isChanged(pc))
            assertNull(applySecureRediscovery(store, listOf(candidate(flip.port))))
            assertEquals(expected, store.pollSnapshot().encode)
        } finally { flip.stop(); KeyChangeState.resetForTests() }
    }

    @Test fun rightKeyWrongTokenExpectationIsRefused() {
        KeyChangeState.resetForTests()
        val id = identity()
        val a = server(id, requiredToken = tok)
        val (store, expected) = pairedStore(id, a.port)
        a.stop()
        val b = server(id, requiredToken = otherTok) // same key, but the helper rejects our token
        try {
            assertNull(applySecureRediscovery(store, listOf(candidate(b.port))))
            assertEquals(expected, store.pollSnapshot().encode)
        } finally { b.stop(); KeyChangeState.resetForTests() }
    }

    @Test fun plaintextResponderIsRefused() {
        KeyChangeState.resetForTests()
        val id = identity()
        val a = server(id, requiredToken = tok)
        val (store, expected) = pairedStore(id, a.port)
        a.stop()
        val ss = ServerSocket(0)
        val t = Thread { try { while (true) ss.accept().close() } catch (e: Exception) { } }.apply { isDaemon = true; start() }
        try {
            assertNull(applySecureRediscovery(store, listOf(candidate(ss.localPort))))
            assertEquals(expected, store.pollSnapshot().encode)
        } finally { ss.close(); t.interrupt(); KeyChangeState.resetForTests() }
    }

    @Test fun saveBindsItsOwnGenerationAndStaleGenerationsAreRefused() {
        KeyChangeState.resetForTests()
        val id = identity()
        val s = Store(VlcTargetTestFakePrefs(), VlcTargetTestFakePrefs())
        val pinHex = PairedCredential.toHex(PinPolicy.hashSpki(id.spki))
        // A failed save binds no generation.
        val (badOutcome, badGen) = s.savePairingAndGeneration("127.0.0.1", 8492, "PC", "pw", SecureHttp.BoundPin(pinHex, "not-a-token"), pc)
        assertNotEquals(SecurePairSave.Outcome.SAVED, badOutcome)
        assertNull(badGen)
        // A successful save returns the generation of ITS OWN bump, current right after.
        val (outcome, gen) = s.savePairingAndGeneration("127.0.0.1", 8492, "PC", "pw", SecureHttp.BoundPin(pinHex, tok), pc)
        assertEquals(SecurePairSave.Outcome.SAVED, outcome)
        assertNotNull(gen)
        assertEquals(gen, s.pairingGeneration)
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        assertNotNull(s.runIfPairingCurrent(gen!!) { ran.set(true) })
        assertTrue(ran.get())
        // A repair bumps the generation: the first save's generation is refused from then on.
        val (outcome2, gen2) = s.savePairingAndGeneration("127.0.0.1", 9000, "PC2", "pw2", SecureHttp.BoundPin(pinHex, otherTok), "f0".repeat(16))
        assertEquals(SecurePairSave.Outcome.SAVED, outcome2)
        assertTrue(gen2!! > gen)
        ran.set(false)
        assertNull(s.runIfPairingCurrent(gen) { ran.set(true) })
        assertEquals(false, ran.get())
        KeyChangeState.resetForTests()
    }

    @Test fun publishGateIsAtomicAndDropsTheOldResultAfterRepairOrForget() {
        KeyChangeState.resetForTests()
        val id = identity()
        val a = server(id, requiredToken = tok)
        val (store, _) = pairedStore(id, a.port)
        a.stop()
        val b = server(id, requiredToken = tok)
        try {
            val r = applySecureRediscovery(store, listOf(candidate(b.port)))
            assertNotNull(r)
            // No mutation since the CAS: the gate runs the publish.
            val published = java.util.concurrent.atomic.AtomicBoolean(false)
            assertNotNull(store.runIfPairingCurrent(r!!.generation) { published.set(true) })
            assertTrue(published.get())
            // A repair lands: the SAME captured generation can never publish again - the
            // check and the publish are one locked section, so nothing can interleave.
            val pinHex = PairedCredential.toHex(PinPolicy.hashSpki(id.spki))
            assertEquals(SecurePairSave.Outcome.SAVED,
                SecurePairSave.save(store.securePairStore, "127.0.0.1", b.port, "PC", "pw2", SecureHttp.BoundPin(pinHex, otherTok), "f0".repeat(16)))
            published.set(false)
            assertNull(store.runIfPairingCurrent(r.generation) { published.set(true) })
            assertEquals(false, published.get())
            // A forget closes it the same way.
            val g2 = store.pairingGeneration
            assertTrue(SecurePairSave.forget(store.securePairStore))
            published.set(false)
            assertNull(store.runIfPairingCurrent(g2) { published.set(true) })
            assertEquals(false, published.get())
        } finally { b.stop(); KeyChangeState.resetForTests() }
    }

    @Test fun repairOrForgetBetweenProbeAndCasIsRefusedAndDropsTheStalePublish() {
        KeyChangeState.resetForTests()
        val id = identity()
        val a = server(id, requiredToken = tok)
        val (store, expected) = pairedStore(id, a.port)
        a.stop()
        val b = server(id, requiredToken = tok)
        try {
            val r = applySecureRediscovery(store, listOf(candidate(b.port)))
            assertNotNull(r)
            // A repair (re-pair) after the move bumps the generation: the captured one is stale,
            // so the publish gate drops the old address/token instead of lending it to the new pairing.
            val pinHex = PairedCredential.toHex(PinPolicy.hashSpki(id.spki))
            assertEquals(SecurePairSave.Outcome.SAVED,
                SecurePairSave.save(store.securePairStore, "127.0.0.1", b.port, "PC", "pw2", SecureHttp.BoundPin(pinHex, otherTok), "f0".repeat(16)))
            assertNotEquals(r!!.generation, store.pairingGeneration)
            // The stale pre-repair record can never be CAS-moved afterwards.
            assertNull(store.updateSecureAddressAndGeneration(expected, "127.0.0.1", b.port))
            // After a forget there is no record to move at all.
            assertTrue(SecurePairSave.forget(store.securePairStore))
            assertNull(store.updateSecureAddressAndGeneration(store.pollSnapshot().encode ?: "v3|x", "127.0.0.1", b.port))
        } finally { b.stop(); KeyChangeState.resetForTests() }
    }
}

/** Minimal JVM SharedPreferences over a map (same shape as VlcTargetTest's private fake,
 *  duplicated here because that one is class-private). */
private class VlcTargetTestFakePrefs : android.content.SharedPreferences {
    val m = HashMap<String, Any?>()
    override fun getString(key: String?, defValue: String?): String? = m[key] as? String ?: defValue
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
        private fun go() { for (r in rm) m.remove(r); for ((k, v) in w) m[k] = v }
    }
}
