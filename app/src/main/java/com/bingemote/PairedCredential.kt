package com.bingemote

/**
 * The trusted credential of a secure (pinned) PC pairing, as ONE stored value: the phone's local id for
 * the PC, the PC's key hash, the PC's address and the token. Identity, key, address and token can never
 * be half written or come from different pairings. Anything that does not parse exactly is unusable.
 */
class PairedCredential private constructor(val pcId: String, val pinHex: String, val host: String, val port: Int, val token: String) {
    fun encode(): String = "v3|$pcId|$pinHex|$host|$port|$token"

    /** The pin policy for this one stored PC, keyed by its stored id (never an id from discovery). */
    fun policy(): PinPolicy = PinPolicy(mapOf(pcId to pinBytes(pinHex)))

    companion object {
        /** Written alongside a failed save that could not be undone: never parses. */
        const val BLOCKED = "blocked"
        /** The helper issues tokens as 64 lowercase hex (windows Program.NewToken). Anything else is rejected, never trimmed. */
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val HEX32 = Regex("[0-9a-f]{32}")
        private val HOST = Regex("[0-9A-Za-z.:\\-]{1,64}")

        fun of(pcId: String, pinHex: String, host: String, port: Int, token: String): PairedCredential? =
            if (HEX32.matches(pcId) && HEX64.matches(pinHex) && HOST.matches(host) && port in 1..65535 &&
                HEX64.matches(token)) PairedCredential(pcId, pinHex, host, port, token) else null

        fun decode(raw: String): PairedCredential? {
            val p = raw.split('|')
            return if (p.size == 6 && p[0] == "v3") p[4].toIntOrNull()?.let { of(p[1], p[2], p[3], it, p[5]) } else null
        }

        fun newPcId(rng: java.security.SecureRandom = java.security.SecureRandom()): String = toHex(ByteArray(16).also { rng.nextBytes(it) })
        fun pinBytes(pinHex: String): ByteArray = ByteArray(32) { pinHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun toHex(hash: ByteArray): String = hash.joinToString("") { "%02x".format(it) }
    }

    /** What the phone may do with what is stored. Only [Secure] can connect. */
    sealed class State {
        object Unpaired : State()
        class Secure(val credential: PairedCredential) : State()
        /** Only an old plain-HTTP token exists: unusable, re-pair required. */
        object NeedsRepair : State()
        /** A save/reset was interrupted, the record does not parse, or the store could not be read: unusable, re-pair required. Never reads as absent. */
        object Blocked : State()
    }

    object Resolver {
        fun resolve(credentialRaw: String, intentRaw: String, legacyToken: String, storeBroken: Boolean): State = when {
            storeBroken -> State.Blocked
            intentRaw.isNotEmpty() -> State.Blocked
            credentialRaw.isNotEmpty() -> decode(credentialRaw)?.let { State.Secure(it) } ?: State.Blocked
            legacyToken.isNotEmpty() -> State.NeedsRepair
            else -> State.Unpaired
        }
    }
}
