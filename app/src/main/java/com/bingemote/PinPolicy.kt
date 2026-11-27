package com.bingemote

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Result of checking the key a PC presented against the key remembered for that PC. */
enum class PinResult { FIRST_USE_REMEMBERED, MATCH, MISMATCH }

/**
 * Phone-side per-PC key memory (trust on first use), pure JVM. The key is the SHA-256 of the
 * server leaf's SubjectPublicKeyInfo. A PC is identified by the id stored locally at pairing,
 * never by an id supplied by discovery. An unknown PC is accepted only while [enroll] is true
 * (an explicit pairing in progress). A different key for a known PC is a hard failure and is
 * never replaced here; [forget] is the explicit re-pair step. Persistence is injected so the
 * caller can write the pin and the token in one atomic step.
 */
class PinPolicy(initial: Map<String, ByteArray> = emptyMap()) {
    private val pins = HashMap<String, ByteArray>().apply { initial.forEach { (k, v) -> put(k, v.clone()) } }

    @Synchronized fun check(pcId: String, presentedHash: ByteArray, enroll: Boolean): PinResult {
        val pin = pins[pcId] ?: return if (enroll) PinResult.FIRST_USE_REMEMBERED else PinResult.MISMATCH
        return if (MessageDigest.isEqual(pin, presentedHash)) PinResult.MATCH else PinResult.MISMATCH
    }

    /** Remember the key. Refuses a different key for a PC that already has one. */
    @Synchronized fun commit(pcId: String, hash: ByteArray): Boolean {
        if (hash.size != 32) return false
        val pin = pins[pcId]
        if (pin != null) return MessageDigest.isEqual(pin, hash)
        pins[pcId] = hash.clone()
        return true
    }

    /**
     * Persist first, then publish in memory. [persist] writes the pin and the token together
     * (the caller's atomic write). If it throws, nothing is remembered and the exception
     * propagates. Returns false, without calling [persist], when a DIFFERENT key is already
     * remembered for this PC or the hash is not 32 bytes. The same key still runs [persist].
     */
    @Synchronized fun commitAfterPersist(pcId: String, hash: ByteArray, persist: () -> Unit): Boolean {
        if (hash.size != 32) return false
        val pin = pins[pcId]
        if (pin != null && !MessageDigest.isEqual(pin, hash)) return false
        persist()                       // a same-key re-pair still saves its new token
        pins[pcId] = hash.clone()
        return true
    }

    @Synchronized fun has(pcId: String): Boolean = pins.containsKey(pcId)
    @Synchronized fun forget(pcId: String): Boolean = pins.remove(pcId) != null

    companion object {
        fun hashSpki(spkiDer: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(spkiDer)
    }
}

/**
 * Trust manager for one connection to one PC. It ignores certificate chains (the PC's key is
 * self-issued) and decides only by the pinned key. It records the hash it saw so a pairing can
 * commit that exact key together with the token from the SAME connection. Server checks fail on
 * a mismatch or on an unknown PC when [enroll] is false.
 */
/** The PC presented a key other than the remembered one. Typed so callers never match on message text. */
class PinMismatchException(message: String) : CertificateException(message)

class PinningTrustManager(
    private val policy: PinPolicy,
    private val pcId: String,
    private val enroll: Boolean,
    /** False only for a discovery probe: an unverified address showing another key must not latch the saved PC as changed. */
    private val markOnMismatch: Boolean = true,
) : X509TrustManager {
    private val lock = Any()
    private var first: ByteArray? = null
    private var res: PinResult? = null

    /** The accepted first key, or null. A rejected key is never stored here, so it can never be committed. */
    val observedHash: ByteArray? get() = synchronized(lock) { first?.clone() }
    /** The last decision, read under the same lock as the key. */
    val result: PinResult? get() = synchronized(lock) { res }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate presented")
        checkSpki(leaf.publicKey.encoded)
    }

    /** The decision itself, on the leaf's SubjectPublicKeyInfo bytes. The first accepted key is fixed for this object; a later different key is refused. */
    fun checkSpki(spkiDer: ByteArray) {
        val h = PinPolicy.hashSpki(spkiDer)
        synchronized(lock) {
            val f = first
            if (f != null && !MessageDigest.isEqual(f, h)) { res = PinResult.MISMATCH; if (markOnMismatch) KeyChangeState.mark(pcId); throw PinMismatchException("PC presented a different key than the first handshake") }
            val r = policy.check(pcId, h, enroll)
            res = r
            if (r == PinResult.MISMATCH) { if (markOnMismatch) KeyChangeState.mark(pcId); throw PinMismatchException("PC key does not match the remembered key") }
            if (f == null) first = h
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
