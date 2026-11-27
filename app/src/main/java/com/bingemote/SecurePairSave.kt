package com.bingemote

/** Everything one secure pairing persists. All required, no defaults, so nothing can be silently dropped. */
internal interface SecurePairStore {
    var credential: String      // encoded PairedCredential (identity + pin + address + token), one value
    var intent: String          // write-ahead marker: non-empty means a save or reset started and did not finish
    var vlcPassword: String
    var pcName: String
    var legacyToken: String
    fun flush(): Boolean
    fun healthy(): Boolean
}

/**
 * Secure-only save and reset with a durable write-ahead marker. The marker is persisted (and flushed)
 * BEFORE anything else changes; if it cannot be persisted nothing is touched. It is cleared only after
 * the final flush succeeds. A crash or a failed cleanup therefore always leaves it set, and a set marker
 * resolves to Blocked on the next load (see [PairedCredential.Resolver]). The block does not depend on
 * any write made after a failure.
 */
internal object SecurePairSave {
    /** In-process latch: once a save or reset could not be proven complete, this store stays Blocked in this process
     *  no matter what the (possibly stale) cache reads, until a later save or reset fully succeeds. */
    private val latched: MutableSet<SecurePairStore> = java.util.Collections.newSetFromMap(java.util.Collections.synchronizedMap(java.util.WeakHashMap()))
    fun isLatched(s: SecurePairStore) = latched.contains(s)

    enum class Outcome {
        SAVED, FAILED_PRIOR_KEPT, FAILED_BLOCKED;
        /** Blocked is never an ordinary retry: the phone must be paired again from the PC's Pair screen. */
        val userMessage: String get() = when (this) {
            SAVED -> "Paired."
            FAILED_PRIOR_KEPT -> "Pairing did not save. Your earlier pairing is unchanged. Try Pair on the PC again."
            FAILED_BLOCKED -> "Pairing could not be saved safely and this phone is no longer paired. Open Pair on the PC, then pair this phone again. Retrying alone will not fix it."
        }
    }

    fun save(s: SecurePairStore, host: String, port: Int, pcName: String, vlcPassword: String?, proof: SecureHttp.BoundPin, pcId: String): Outcome {
        // The WHOLE transaction under pairingLock: a coherent reader (Store.vlcTarget) observes
        // pre- or post-save, never intent-set/credential-new with the old password.
        val r = synchronized(pairingLock) { try { saveInner(s, host, port, pcName, vlcPassword, proof, pcId) } finally { bumpPairingGenerationLocked() } }
        if (r == Outcome.FAILED_BLOCKED) latched.add(s) else if (r == Outcome.SAVED) latched.remove(s)
        return r
    }

    private fun saveInner(s: SecurePairStore, host: String, port: Int, pcName: String, vlcPassword: String?, proof: SecureHttp.BoundPin, pcId: String): Outcome {
        val record = PairedCredential.of(pcId, proof.pinHex, host, port, proof.token)?.encode() ?: return Outcome.FAILED_PRIOR_KEPT
        val priorCred: String; val priorVlc: String; val priorName: String
        try {
            if (!s.healthy()) return Outcome.FAILED_PRIOR_KEPT
            priorCred = s.credential; priorVlc = s.vlcPassword; priorName = s.pcName
            if (!s.healthy()) return Outcome.FAILED_PRIOR_KEPT
        } catch (e: Exception) { return Outcome.FAILED_PRIOR_KEPT }
        // 1. durable intent first; abort if it cannot be persisted
        val intentOk = runCatching { s.intent = "saving"; s.flush() }.getOrDefault(false)
        if (!intentOk) {
            // Nothing else was touched. If the marker did land it only blocks (safe); try to clear it.
            runCatching { s.intent = ""; s.flush() }
            return Outcome.FAILED_PRIOR_KEPT
        }
        // 2. mutate
        val mutated = runCatching {
            s.credential = record
            if (vlcPassword != null) s.vlcPassword = vlcPassword
            s.pcName = pcName
            // flush, then read back what is persisted: it must equal what was just written
            s.flush() && s.credential == record && s.pcName == pcName && (vlcPassword == null || s.vlcPassword == vlcPassword)
        }.getOrDefault(false)
        if (!mutated) {
            val restored = runCatching {
                s.credential = priorCred
                if (vlcPassword != null) s.vlcPassword = priorVlc
                s.pcName = priorName
                s.flush() && s.credential == priorCred && (vlcPassword == null || s.vlcPassword == priorVlc)
            }.getOrDefault(false)
            val cleared = restored && runCatching { s.intent = ""; s.flush() && s.intent.isEmpty() }.getOrDefault(false)
            return if (cleared) Outcome.FAILED_PRIOR_KEPT else Outcome.FAILED_BLOCKED   // marker still set on disk: blocked after restart
        }
        // 3. clear intent only after the final flush
        val done = runCatching { s.intent = ""; s.flush() && s.intent.isEmpty() }.getOrDefault(false)
        return if (done) Outcome.SAVED else Outcome.FAILED_BLOCKED
    }

    /**
     * Moves the stored PC to an address that already proved the stored key (see SecureHttp.probe). It changes only host and
     * port: identity, pin and token are copied from the record the probe used. [expectedRecord] is the exact snapshot probed;
     * if the stored record is no longer that (pair, forget or another change ran meanwhile) nothing is written. It never
     * clears a latched or key-changed state, and uses the same write-ahead marker and readback as save().
     */
    fun updateAddress(s: SecurePairStore, expectedRecord: String, host: String, port: Int): Boolean =
        synchronized(pairingLock) { try { updateAddressInner(s, expectedRecord, host, port) } finally { bumpPairingGenerationLocked() } }

    private fun updateAddressInner(s: SecurePairStore, expectedRecord: String, host: String, port: Int): Boolean {
        if (isLatched(s)) return false
        val old: PairedCredential; val oldRaw: String
        try {
            if (!s.healthy() || s.intent.isNotEmpty()) return false
            oldRaw = s.credential
            if (oldRaw != expectedRecord) return false
            old = PairedCredential.decode(oldRaw) ?: return false
        } catch (e: Exception) { return false }
        if (KeyChangeState.isChanged(old.pcId)) return false
        val newRec = PairedCredential.of(old.pcId, old.pinHex, host, port, old.token)?.encode() ?: return false
        if (newRec == oldRaw) return true
        if (!runCatching { s.intent = "saving"; s.flush() }.getOrDefault(false)) { runCatching { s.intent = ""; s.flush() }; return false }
        val wrote = runCatching { s.credential = newRec; s.flush() && s.credential == newRec }.getOrDefault(false)
        if (!wrote) {
            val restored = runCatching { s.credential = oldRaw; s.flush() && s.credential == oldRaw && run { s.intent = ""; s.flush() && s.intent.isEmpty() } }.getOrDefault(false)
            if (!restored) latched.add(s)
            return false
        }
        val done = runCatching { s.intent = ""; s.flush() && s.intent.isEmpty() }.getOrDefault(false)
        if (!done) { latched.add(s); return false }
        return true
    }

    /** Persisted reset: the only way out of a secure pairing. Same write-ahead rule. True only when fully reset. */
    fun forget(s: SecurePairStore): Boolean {
        val ok = synchronized(pairingLock) { try { forgetInner(s) } finally { bumpPairingGenerationLocked() } }
        if (!ok) latched.add(s) else latched.remove(s)
        return ok
    }

    private fun forgetInner(s: SecurePairStore): Boolean {
        if (!runCatching { s.intent = "forgetting"; s.flush() }.getOrDefault(false)) return false
        val ok = runCatching {
            s.credential = ""; s.vlcPassword = ""; s.legacyToken = ""
            s.flush() && s.credential.isEmpty()
        }.getOrDefault(false)
        if (!ok) return false
        return runCatching { s.intent = ""; s.flush() && s.intent.isEmpty() }.getOrDefault(false)
    }

    /** What a freshly loaded phone should do, from the persisted content. A broken or unreadable store is Blocked, never absent.
     *  Assembled under pairingLock: the credential/intent/legacy reads can never mix with a save,
     *  forget or address update in flight (the same coherence Store.vlcTarget gets for the VLC tuple). */
    fun load(s: SecurePairStore): PairedCredential.State = synchronized(pairingLock) {
        if (isLatched(s)) return@synchronized PairedCredential.State.Blocked
        val cred = runCatching { s.credential }.getOrNull(); val intent = runCatching { s.intent }.getOrNull(); val legacy = runCatching { s.legacyToken }.getOrNull()
        val broken = cred == null || intent == null || legacy == null || !runCatching { s.healthy() }.getOrDefault(false)
        return@synchronized PairedCredential.Resolver.resolve(cred ?: "", intent ?: "", legacy ?: "", broken)
    }
}
