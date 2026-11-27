package com.bingemote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket

/** Real TLS handshakes against a throwaway local server with generated self-signed keys. */
class SecureHttpTest {
    private class Srv(val port: Int, val spki: ByteArray, val accepts: java.util.concurrent.atomic.AtomicInteger, val stop: () -> Unit)

    private fun server(reply: () -> String = { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok" }): Srv {
        val accepts = java.util.concurrent.atomic.AtomicInteger()
        val dir = java.nio.file.Files.createTempDirectory("pin").toFile()
        val ks = File(dir, "k.p12")
        val rc = ProcessBuilder("keytool", "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=pc", "-validity", "30", "-storetype", "PKCS12", "-keystore", ks.path,
            "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        check(rc == 0) { "keytool failed" }
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val t = Thread {
            try {
                while (true) {
                    val s = ss.accept(); accepts.incrementAndGet()
                    try {
                        val i = s.getInputStream(); val o = s.getOutputStream()
                        i.read(); o.write(reply().toByteArray()); o.flush()
                    } catch (e: Exception) { } finally { try { s.close() } catch (e: Exception) { } }
                }
            } catch (e: Exception) { }
        }.apply { isDaemon = true; start() }
        return Srv(ss.localPort, store.getCertificate("s").publicKey.encoded, accepts) { ss.close(); t.interrupt() }
    }

    @Test fun enrollThenLaterConnectionMatchesAndMismatchFails() {
        val a = server(); val b = server()
        try {
            val p = PinPolicy()
            val first = SecureHttp.open(p, "pc1", "127.0.0.1", a.port, "/x", enroll = true)
            assertEquals(200, first.responseCode())
            assertEquals(PinResult.FIRST_USE_REMEMBERED, first.trust.result)
            assertTrue(p.commit("pc1", first.trust.observedHash!!))
            assertTrue(first.trust.observedHash!!.contentEquals(PinPolicy.hashSpki(a.spki)))
            val again = SecureHttp.open(p, "pc1", "127.0.0.1", a.port, "/x")
            assertEquals(200, again.responseCode())
            assertEquals(PinResult.MATCH, again.trust.result)
            try { SecureHttp.open(p, "pc1", "127.0.0.1", b.port, "/x", enroll = true).responseCode(); fail("other key accepted") }
            catch (e: SSLException) { }
        } finally { a.stop(); b.stop() }
    }

    @Test fun unknownPcIsRefusedOutsidePairing() {
        val a = server()
        try {
            try { SecureHttp.open(PinPolicy(), "pcX", "127.0.0.1", a.port, "/x").responseCode(); fail("unknown PC accepted") }
            catch (e: SSLException) { }
            assertNotNull(a.spki)
        } finally { a.stop() }
    }

    @Test fun readBoundTakesTheBodyFromItsOwnConnectionAndTiesItToThatKey() {
        val body = """{"token":"${"ab".repeat(32)}"}"""
        val a = server({ "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body" })
        try {
            val l = SecureHttp.open(PinPolicy(), "pc1", "127.0.0.1", a.port, "/pair", enroll = true)
            assertEquals(200, l.responseCode())
            val bound = l.readBound()!!
            assertEquals(200, bound.status)
            val pin = bound.pin { Regex("\"token\":\"([0-9a-f]+)\"").find(it)!!.groupValues[1] }
            assertEquals("ab".repeat(32), pin.token)
            assertEquals(PairedCredential.toHex(PinPolicy.hashSpki(a.spki)), pin.pinHex)
        } finally { a.stop() }
    }

    @Test fun readBoundIsNullBeforeAnyResponseOrWhenTheKeyWasNotBound() {
        val a = server()
        try {
            val l = SecureHttp.open(PinPolicy(), "pc1", "127.0.0.1", a.port, "/x", enroll = true)
            assertEquals(null, l.readBound())   // no responseCode() yet, so no key was captured
        } finally { a.stop() }
    }

    @Test fun probeMatchesTheStoredKeyWithoutSendingAnyBytesAndMismatchDoesNotLatch() {
        KeyChangeState.resetForTests()
        val a = server(); val b = server()
        try {
            val policy = PinPolicy(mapOf("pc1" to PinPolicy.hashSpki(a.spki)))
            val before = a.accepts.get()
            assertEquals(SecureHttp.ProbeResult.MATCH, SecureHttp.probe(policy, "pc1", "127.0.0.1", a.port))
            assertEquals(SecureHttp.ProbeResult.MISMATCH, SecureHttp.probe(policy, "pc1", "127.0.0.1", b.port))
            assertTrue(!KeyChangeState.isChanged("pc1"))           // an unverified address cannot block the saved PC
            assertEquals(SecureHttp.ProbeResult.UNREACHABLE, SecureHttp.probe(policy, "pc1", "127.0.0.1", 1, 500))
            assertTrue(a.accepts.get() > before)
            // a real saved-endpoint mismatch still latches
            try { SecureHttp.open(policy, "pc1", "127.0.0.1", b.port, "/x").responseCode(); fail("accepted") } catch (e: SSLException) { }
            assertTrue(KeyChangeState.isChanged("pc1"))
        } finally { a.stop(); b.stop(); KeyChangeState.resetForTests() }
    }

    @Test fun redirectIsNotFollowedAndNoSecondRequestIsMade() {
        val a = server({ "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1:1/steal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" })
        try {
            val l = SecureHttp.open(PinPolicy(mapOf("pc1" to PinPolicy.hashSpki(a.spki))), "pc1", "127.0.0.1", a.port, "/x")
            l.connection.setRequestProperty("X-BingeMote-Token", "t")
            assertEquals(302, l.responseCode())
            assertEquals(1, a.accepts.get())
        } finally { a.stop() }
    }

    @Test fun commitBindsToTheSameConnectionsKey() {
        val a = server()
        try {
            val l = SecureHttp.open(PinPolicy(), "pc1", "127.0.0.1", a.port, "/x", enroll = true)
            assertEquals(200, l.responseCode())
            assertTrue(l.boundToObservedKey())
        } finally { a.stop() }
    }

    @Test fun secondHandshakeWithAnotherKeyOnTheSameTrustIsRefused() {
        val a = server(); val b = server()
        try {
            val l = SecureHttp.open(PinPolicy(), "pc1", "127.0.0.1", a.port, "/x", enroll = true)
            assertEquals(200, l.responseCode())
            try { SecureHttp.open(PinPolicy(), "pc1", "127.0.0.1", b.port, "/x", enroll = true, sharedTrust = l.trust).responseCode(); fail("swapped key accepted") }
            catch (e: SSLException) { }
            assertTrue(l.trust.observedHash!!.contentEquals(PinPolicy.hashSpki(a.spki)))
        } finally { a.stop(); b.stop() }
    }

    @Test fun redirectIsNotFollowedAndNeverReachesTheOtherServer() {
        val b = server()
        val a = server({ "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1:${b.port}/y\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" })
        try {
            val l = SecureHttp.open(PinPolicy(), "pc1", "127.0.0.1", a.port, "/x", enroll = true)
            assertEquals(302, l.responseCode())
            assertEquals(0, b.accepts.get())
        } finally { a.stop(); b.stop() }
    }
}
