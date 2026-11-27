package com.bingemote

import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Opens HTTPS connections to a PC whose identity is its pinned key, not a certificate name.
 * The host is an IP address on the local network, so name checks are skipped and the pin is
 * the only identity decision. Callers pass the locally stored PC id, never one taken from
 * discovery. Pairing passes enroll = true and reads [Link.trust] afterwards to commit the key
 * seen on that same connection together with the token.
 */
object SecureHttp {
    /** Proof that a key was presented on one validated response. Only [Link.proof] makes one (tests excepted, same module). */
    class BoundPin internal constructor(val pinHex: String, val token: String)

    /** One response body bound to the key of the connection it arrived on. A token can only be taken out of it by [pin]. */
    class BoundBody internal constructor(private val text: String, private val pinHex: String, val status: Int) {
        /** The body, for refusal detail only; a token can only leave through [pin]. */
        val body: String get() = text
        fun pin(extractToken: (String) -> String): BoundPin = BoundPin(pinHex, extractToken(text))
    }

    class Link(val connection: HttpsURLConnection, val trust: PinningTrustManager) {
        @Volatile private var responseKeyHash: ByteArray? = null

        /**
         * Gets the response and, before anything can disconnect, reads the key the server presented on THIS
         * connection (its own serverCertificates). Always use this instead of connection.responseCode.
         */
        fun responseCode(): Int {
            val rc = connection.responseCode
            responseKeyHash = try { connection.serverCertificates.firstOrNull()?.publicKey?.encoded?.let { PinPolicy.hashSpki(it) } } catch (e: Exception) { null }
            return rc
        }

        /** True only when the key captured in [responseCode] equals the key the trust manager accepted. */
        /**
         * Reads this connection's response body itself (capped at 64 KB) after [responseCode], and ties it to the key this
         * connection presented. There is no way to hand in text from elsewhere. Null when no key was bound or the read fails.
         */
        fun readBound(): BoundBody? {
            if (!boundToObservedKey()) return null
            val hex = trust.observedHash?.let { PairedCredential.toHex(it) } ?: return null
            val text = try {
                (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)?.use { s ->
                    val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(4096); var total = 0
                    while (true) { val n = s.read(chunk); if (n < 0) break; total += n; if (total > 65536) return null; buf.write(chunk, 0, n) }
                    buf.toString("UTF-8")
                } ?: ""
            } catch (e: Exception) { return null }
            return BoundBody(text, hex, connection.responseCode)
        }

        fun boundToObservedKey(): Boolean {
            val seen = responseKeyHash; val obs = trust.observedHash
            return seen != null && obs != null && MessageDigest.isEqual(seen, obs)
        }
    }

    /** IPv6 literals need brackets in a URL; a zone id is not supported and is rejected. */
    fun urlHost(host: String): String = when {
        host.startsWith("[") -> host
        ':' in host -> { require('%' !in host) { "zoned IPv6 address not supported" }; "[$host]" }
        else -> host
    }

    private val acceptAnyName = HostnameVerifier { _, _ -> true }

    fun open(policy: PinPolicy, pcId: String, host: String, port: Int, path: String, enroll: Boolean = false,
        sharedTrust: PinningTrustManager? = null,
        connectTimeoutMs: Int = 4000, readTimeoutMs: Int = 5000): Link {
        val tm = sharedTrust ?: PinningTrustManager(policy, pcId, enroll)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), null)
        val c = URL("https://${urlHost(host)}:$port$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = ctx.socketFactory
        c.hostnameVerifier = acceptAnyName   // this instance only, never the global default
        c.connectTimeout = connectTimeoutMs; c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = false    // a redirect must never carry a secret to another origin
        return Link(c, tm)
    }

    enum class ProbeResult { MATCH, MISMATCH, UNREACHABLE }

    /**
     * Handshake only: connects, completes the TLS handshake against the stored key and closes. No application bytes
     * are ever written, so no token or request can reach an address that has not proven the stored key. A mismatch here
     * never marks the saved PC as key-changed (the address was never verified as that PC).
     */
    fun probe(policy: PinPolicy, pcId: String, host: String, port: Int, timeoutMs: Int = 3000): ProbeResult {
        val tm = PinningTrustManager(policy, pcId, false, markOnMismatch = false)
        var s: javax.net.ssl.SSLSocket? = null
        return try {
            val ctx = SSLContext.getInstance("TLS"); ctx.init(null, arrayOf<TrustManager>(tm), null)
            s = ctx.socketFactory.createSocket() as javax.net.ssl.SSLSocket
            s.soTimeout = timeoutMs
            s.connect(java.net.InetSocketAddress(host.removePrefix("[").removeSuffix("]"), port), timeoutMs)
            s.startHandshake()
            if (tm.result == PinResult.MATCH) ProbeResult.MATCH else ProbeResult.UNREACHABLE
        } catch (e: javax.net.ssl.SSLException) {
            var x: Throwable? = e; var n = 0; var mm = false
            while (x != null && n++ < 8) { if (x is PinMismatchException) mm = true; x = x.cause }
            if (mm) ProbeResult.MISMATCH else ProbeResult.UNREACHABLE
        } catch (e: Exception) { ProbeResult.UNREACHABLE }
        finally { try { s?.close() } catch (e: Exception) { } }
    }
}
