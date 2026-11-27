package com.bingemote

import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** A MockWebServer served over TLS with a throwaway self-signed key, plus the pinned credential that trusts exactly that key. */
class TlsFixture : AutoCloseable {
    val server = MockWebServer()
    val spki: ByteArray
    val token = "ab".repeat(32)
    val pcId = PairedCredential.newPcId()   // distinct per fixture: key-change state is process-wide, keyed by pcId

    init {
        val dir = java.nio.file.Files.createTempDirectory("tlsfx").toFile()
        val ks = File(dir, "k.p12")
        val rc = ProcessBuilder("keytool", "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=pc", "-validity", "30", "-storetype", "PKCS12", "-keystore", ks.path,
            "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start()
            .also { it.inputStream.readBytes() }.waitFor()
        check(rc == 0) { "keytool failed" }
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        spki = store.getCertificate("s").publicKey.encoded
        server.useHttps(ctx.socketFactory, false)
        server.start()
    }

    val port: Int get() = server.port

    fun credential(): PairedCredential =
        PairedCredential.of(pcId, PairedCredential.toHex(PinPolicy.hashSpki(spki)), "127.0.0.1", port, token)!!

    /** Route HelperClient through this server's pinned credential for the duration of a test. */
    fun install(): () -> Unit {
        val old = HelperClient.pairingSource
        val c = credential()
        HelperClient.pairingSource = { c }
        return { HelperClient.pairingSource = old }
    }

    override fun close() = server.shutdown()
}
