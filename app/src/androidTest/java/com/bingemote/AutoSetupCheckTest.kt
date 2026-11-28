package com.bingemote

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The step-3 automatic check driven for real: AutoSetupCheck runs the production
 * HelperClient / Vlc / VlcAuthRepair code over actual HTTP against fake PC and VLC
 * servers on localhost. No demo states, no assigned UI state - the same calls the
 * setup screen's LaunchedEffect makes. The helper side is REAL pinned HTTPS: the
 * fixture pairs through the production SecurePairSave path with a credential bound
 * to the fake server's key (a checked-in throwaway test identity, never a real
 * secret). No production bypass and no RequestGate weakening - the transport's
 * secure-only rule is exercised, not circumvented.
 */
class AutoSetupCheckTest {

    /** Minimal threaded HTTP fake: the handler maps (method, path, authorization, body) to (code, body). */
    private class FakeServer(
        private val handler: (String, String, String?, String) -> Pair<Int, String>,
        tls: SSLContext? = null,
        /** When set, a request without exactly this X-BingeMote-Token header gets 401 before the
         *  handler runs: the fake helper proves the token check, not just the TLS key. */
        private val requiredToken: String? = null,
    ) {
        private val socket: ServerSocket = tls?.serverSocketFactory?.createServerSocket(0) ?: ServerSocket(0)
        val port: Int = socket.localPort
        @Volatile private var stopped = false
        private val thread = Thread {
            while (!stopped) {
                val s = try { socket.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        s.soTimeout = 5000
                        val r = BufferedReader(InputStreamReader(s.getInputStream()))
                        val parts = (r.readLine() ?: "").split(" ")
                        var auth: String? = null
                        var token: String? = null
                        var length = 0
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", ignoreCase = true)) auth = line.substringAfter(':').trim()
                            if (line.startsWith("X-BingeMote-Token:", ignoreCase = true)) token = line.substringAfter(':').trim()
                            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val chars = CharArray(length)
                        if (length > 0) r.read(chars)
                        val (code, body) = if (requiredToken != null && token != requiredToken) 401 to "{\"error\":\"bad token\"}"
                        else handler(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, auth, String(chars))
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        s.getOutputStream().apply {
                            write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                        s.close()
                    } catch (e: Exception) {
                        try { s.close() } catch (_: Exception) {}
                    }
                }.start()
            }
        }

        fun start(): FakeServer { thread.start(); return this }
        fun stop() { stopped = true; try { socket.close() } catch (_: Exception) {} }
    }

    /** The fake helper's TLS identity: a checked-in throwaway test key pair (assets
     *  fakehelper-test.crt/.key), NOT a real credential. The secure transport pins this
     *  exact key, so instrumentation drives the real pinned-HTTPS path. PEM assets +
     *  pure JCA loading: no PKCS12/keystore-format risk on-device. */
    internal object HelperTls {
        private const val ALIAS_PASSWORD = "test-only"
        private fun loadCertAndKey(): Pair<X509Certificate, PrivateKey> {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val cert = assets.open("fakehelper-test.crt").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            } as X509Certificate
            val keyDer = assets.open("fakehelper-test.key").use { stream ->
                val pem = stream.readBytes().toString(Charsets.US_ASCII)
                android.util.Base64.decode(
                    pem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replace("\\s".toRegex(), ""),
                    android.util.Base64.DEFAULT,
                )
            }
            val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyDer))
            return cert to key
        }
        val context: SSLContext by lazy {
            val (cert, key) = loadCertAndKey()
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry("fakehelper", key, ALIAS_PASSWORD.toCharArray(), arrayOf(cert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(ks, ALIAS_PASSWORD.toCharArray()) }
            SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }
        /** The SPKI pin of the fake helper's key - the credential record binds to exactly this. */
        val pinHex: String by lazy { PairedCredential.toHex(PinPolicy.hashSpki(loadCertAndKey().first.publicKey.encoded)) }
    }

    /** 64 lowercase hex, the helper-issued token shape PairedCredential requires. */
    private val helperToken = "ab".repeat(32)

    /** Pair the test store with the fake helper through the REAL secure save path: a genuine
     *  PairedCredential record bound to the fake server's key pin. A fresh pcId per call keeps
     *  the process-wide key-change state out of the tests. Fails loudly (never silently
     *  unpaired) when the Keystore-backed store cannot persist on this device. */
    private fun pairWithHelper(store: Store, helperPort: Int, vlcPassword: String?): PairedCredential {
        val c = PairedCredential.of(PairedCredential.newPcId(), HelperTls.pinHex, "127.0.0.1", helperPort, helperToken)!!
        val outcome = SecurePairSave.save(
            store.securePairStore, c.host, c.port, "FakePC", vlcPassword,
            SecureHttp.BoundPin(HelperTls.pinHex, helperToken), c.pcId,
        )
        assertEquals("the fixture must pair through the real secure save path", SecurePairSave.Outcome.SAVED, outcome)
        return c
    }

    private fun freshStore(): Store {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().clear().commit()
        return Store(ctx)
    }

    /** VLC's status endpoint behind a fixed password; 401 for anything else. */
    private fun vlcServer(password: String, hits: AtomicInteger? = null): FakeServer =
        FakeServer({ _, path, auth, _ ->
            hits?.incrementAndGet()
            val expected = android.util.Base64.encodeToString(":$password".toByteArray(), android.util.Base64.NO_WRAP)
            if (path.startsWith("/requests/status.json") && auth == "Basic $expected") 200 to "{}"
            else 401 to "{}"
        }).start()

    private fun helperServer(configured: Boolean, pending: Boolean, adoptPassword: String? = null, adoptCalls: AtomicInteger? = null): FakeServer =
        FakeServer({ _, path, _, body ->
            when {
                path == "/vlc/setup-status" -> 200 to """{"configured":$configured,"pending":$pending}"""
                path == "/vlc/adopt" && adoptPassword != null && !body.contains("\"ack\":true") -> {
                    adoptCalls?.incrementAndGet()
                    200 to """{"password":"$adoptPassword"}"""
                }
                path == "/vlc/adopt" && adoptPassword != null -> 200 to """{"ok":true}"""
                path == "/vlc/adopt" -> 410 to """{"error":"adopt spent"}"""
                else -> 404 to "{}"
            }
        }, HelperTls.context).start()

    @Test
    fun configuredAndVlcAnswersIsOk() {
        val store = freshStore()
        val helper = helperServer(configured = true, pending = false)
        val vlc = vlcServer("pw-1")
        try {
            store.vlcPort = vlc.port; pairWithHelper(store, helper.port, "pw-1")
            assertEquals(AutoSetupCheck.Outcome.OK, AutoSetupCheck.run(store))
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun helper503IsSetupUnknownNeverDoneNeverRePair() {
        // E6: the helper's strict setup-status 503 means "could not verify right now" - a
        // distinct retry outcome, never a green Done and never a re-pair refusal.
        val store = freshStore()
        val helper = FakeServer({ _, path, _, _ ->
            if (path == "/vlc/setup-status") 503 to """{"ok":false}""" else 404 to "{}"
        }, HelperTls.context).start()
        val vlc = vlcServer("pw-1")
        try {
            store.vlcPort = vlc.port; pairWithHelper(store, helper.port, "pw-1")
            assertEquals(AutoSetupCheck.Outcome.SETUP_UNKNOWN, AutoSetupCheck.run(store))
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun stalePhonePasswordWithSpentGrantIsRefused() {
        // E7: the phone holds a superseded password, VLC has the current one, and the one-shot
        // adopt grant is already spent (410) - the heal cannot fix it: REFUSED, never a false
        // ready, and the re-pair guidance stands.
        val store = freshStore()
        val helper = helperServer(configured = true, pending = false, adoptPassword = null)
        val vlc = vlcServer("new-pw")
        try {
            store.vlcPort = vlc.port; pairWithHelper(store, helper.port, "old-pw")
            assertEquals(AutoSetupCheck.Outcome.REFUSED, AutoSetupCheck.run(store))
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun pendingAttestationIsDeferredWithoutPokingVlc() {
        val store = freshStore()
        val vlcHits = AtomicInteger(0)
        val helper = helperServer(configured = false, pending = true)
        val vlc = vlcServer("pw-1", vlcHits) // must never be contacted: old settings still live
        try {
            store.vlcPort = vlc.port; pairWithHelper(store, helper.port, "pw-1")
            assertEquals(AutoSetupCheck.Outcome.OK_DEFERRED, AutoSetupCheck.run(store))
            assertEquals(0, vlcHits.get())
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun configuredButVlcClosedIsOkNotOpen() {
        val store = freshStore()
        val helper = helperServer(configured = true, pending = false)
        val deadPort = ServerSocket(0).use { it.localPort } // closed the moment the socket frees
        try {
            store.vlcPort = deadPort; pairWithHelper(store, helper.port, "pw-1")
            assertEquals(AutoSetupCheck.Outcome.OK_NOT_OPEN, AutoSetupCheck.run(store))
        } finally { helper.stop() }
    }

    @Test
    fun refusedOnceHealsViaOneAdoptThenOk() {
        val store = freshStore()
        val adoptCalls = AtomicInteger(0)
        val helper = helperServer(configured = true, pending = false, adoptPassword = "pw-2", adoptCalls = adoptCalls)
        val vlc = vlcServer("pw-2") // VLC only takes the PC's current password
        try {
            store.vlcPort = vlc.port; pairWithHelper(store, helper.port, "stale") // the phone's copy is wrong
            assertEquals(AutoSetupCheck.Outcome.OK, AutoSetupCheck.run(store))
            assertEquals(1, adoptCalls.get()) // exactly one adoption fetch
            assertEquals("pw-2", store.vlcPassword) // adopted, never pushed
            assertNull(store.vlcAdoptRequestId) // the ack cleared the persisted request id
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun refusedWithSpentGrantIsRefused() {
        val store = freshStore()
        val helper = helperServer(configured = true, pending = false) // adopt -> 410
        val vlc = vlcServer("other") // every password the phone has is refused
        try {
            store.vlcPort = vlc.port; pairWithHelper(store, helper.port, "stale")
            assertEquals(AutoSetupCheck.Outcome.REFUSED, AutoSetupCheck.run(store))
            assertEquals("stale", store.vlcPassword) // a refusal never overwrites the stored copy
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun nothingConfiguredIsRefusedNotGreen() {
        val store = freshStore()
        val vlcHits = AtomicInteger(0)
        val helper = helperServer(configured = false, pending = false)
        val vlc = vlcServer("pw-1", vlcHits)
        try {
            store.vlcPort = vlc.port; pairWithHelper(store, helper.port, "pw-1")
            assertEquals(AutoSetupCheck.Outcome.REFUSED, AutoSetupCheck.run(store))
            assertEquals(0, vlcHits.get()) // no probe: the PC says nothing is set up
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun pcDownIsUnreachable() {
        val store = freshStore()
        val deadPort = ServerSocket(0).use { it.localPort }
        store.vlcPort = deadPort; pairWithHelper(store, deadPort, "pw-1")
        assertEquals(AutoSetupCheck.Outcome.PC_UNREACHABLE, AutoSetupCheck.run(store))
    }

    @Test
    fun noPasswordAtPairFinishHealsThroughAdopt() {
        // I2 pair-recovery: the pair reply carried NO password (the PC could not verify the
        // vlcrc at the commit boundary - the explicit-null reply) but the PC has one
        // configured. The check's VLC probe 401s on the blank stored password, the ONE
        // allowed adopt heal fetches the PC's copy, and the re-probe passes: OK - no re-pair,
        // no "update your PC".
        val store = freshStore()
        val helper = helperServer(configured = true, pending = false, adoptPassword = "pw-9")
        val vlc = vlcServer("pw-9")
        try {
            store.vlcPort = vlc.port; pairWithHelper(store, helper.port, null) // NO vlcPassword: the pair finished without one
            assertEquals(AutoSetupCheck.Outcome.OK, AutoSetupCheck.run(store))
            assertEquals("pw-9", store.vlcPassword)
        } finally { helper.stop(); vlc.stop() }
    }

    @Test
    fun missingSetupStatusRouteIsUnsupportedNotARetryLoop() {
        // An old PC helper has no /vlc/setup-status (404 -> MissingRoute): the honest outcome
        // is UNSUPPORTED (the update card), never a "couldn't reach the PC" retry loop.
        val store = freshStore()
        val helper = FakeServer({ _, _, _, _ -> 404 to "{}" }, HelperTls.context).start()
        try {
            store.vlcPort = helper.port; pairWithHelper(store, helper.port, "pw-1")
            assertEquals(AutoSetupCheck.Outcome.UNSUPPORTED, AutoSetupCheck.run(store))
        } finally { helper.stop() }
    }

    @Test
    fun badTokenIsRefused() {
        val store = freshStore()
        val helper = FakeServer({ _, _, _, _ -> 401 to """{"error":"bad token"}""" }, HelperTls.context).start()
        try {
            store.vlcPort = helper.port; pairWithHelper(store, helper.port, "pw-1")
            assertEquals(AutoSetupCheck.Outcome.REFUSED, AutoSetupCheck.run(store))
        } finally { helper.stop() }
    }

    @Test
    fun dhcpMoveProbesKeyAndTokenThenMovesStoredAddress() {
        // The PC's address changes (DHCP / new network): the old address is dead and the same
        // PC (same HelperTls key) answers at a new one. Goes through the PRODUCTION seam
        // (applySecureRediscovery): the probe must prove key + token, the CAS must move only
        // host/port, and the gate must then flow requests to the new address.
        val store = freshStore()
        val pings = { _: String, path: String, _: String?, _: String ->
            if (path == "/ping") 200 to "{}" else 404 to "{}"
        }
        val helperA = FakeServer(pings, HelperTls.context, requiredToken = helperToken).start()
        try {
            pairWithHelper(store, helperA.port, "pw-1")
            val before = store.pollSnapshot()
            assertEquals(helperA.port, before.helperPort)
            helperA.stop() // the old address dies
            // A plaintext listener at another address is not the PC: the seam must refuse it.
            FakeServer(pings, requiredToken = helperToken).start().use { plain ->
                assertEquals(null, applySecureRediscovery(store, listOf(DiscoveredHelper("127.0.0.1", plain.port, "rogue"))))
            }
            // The right key with the WRONG token expected by the server is not a proof either.
            FakeServer(pings, HelperTls.context, requiredToken = "ef".repeat(32)).start().use { wrongTok ->
                assertEquals(null, applySecureRediscovery(store, listOf(DiscoveredHelper("127.0.0.1", wrongTok.port, "rogue"))))
            }
            // The same PC at a new address: proof passes, the CAS moves the address, identity stays.
            val helperB = FakeServer(pings, HelperTls.context, requiredToken = helperToken).start()
            try {
                val r = applySecureRediscovery(store, listOf(DiscoveredHelper("127.0.0.1", helperB.port, "FakePC")))
                org.junit.Assert.assertNotNull(r)
                assertEquals(helperB.port, r!!.port)
                assertEquals(before.helperToken, r.token)
                val after = store.pollSnapshot()
                assertEquals(helperB.port, after.helperPort)
                assertEquals(before.pcId, after.pcId)
                assertEquals(before.helperToken, after.helperToken)
                assertTrue(HelperClient.ping("127.0.0.1", helperB.port, after.helperToken))
            } finally { helperB.stop() }
        } finally { runCatching { helperA.stop() } }
    }

    /** FakeServer has no Closeable; small scope helper so refusal-case servers always stop. */
    private fun <T : FakeServer, R> T.use(block: (T) -> R): R {
        try { return block(this) } finally { stop() }
    }
}
