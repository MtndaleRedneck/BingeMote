package com.bingemote

/** What a 401 from VLC means and the ONE automatic thing the phone may do about it. */
object VlcAuthRepair {
    @Volatile private var healing = false

    /** The identity a heal is bound to: the FULL secure record (null for a legacy pairing)
     *  plus the endpoint/token it was captured with and the pending adopt request id - all
     *  from ONE locked section. Every send below uses THIS capture's token, and every local
     *  write re-verifies under pairingLock that the store is still this exact identity: a
     *  re-pair or forget mid-heal makes the heal skip its writes, so the old pairing's
     *  adopted password and request-id effects never land on a new pairing's slot. */
    private class HealIdentity(
        val record: String?, val host: String, val port: Int, val token: String, val secure: Boolean, val reqId: String?,
        val vlcPassword: String,
    )

    /** One locked capture: the pairing load, the endpoint/token fallbacks and the pending
     *  request id in the same section (helperPairing/helperVlcSnapshot re-enter pairingLock
     *  on this thread). Null when there is no usable helper identity (blank host or token,
     *  or a broken store) - the same refusal the old blank checks gave. */
    private fun captureHealIdentity(store: Store): HealIdentity? = synchronized(pairingLock) {
        val rec = (store.helperPairing() as? PairedCredential.State.Secure)?.credential?.encode()
        val snap = store.helperVlcSnapshot()
        if (snap == null || snap.helperToken.isBlank()) null
        else HealIdentity(rec, snap.host, snap.helperPort, snap.helperToken, snap.secure, store.vlcAdoptRequestId, snap.vlcPassword)
    }

    /** True only while the CURRENT record, endpoint/token AND stored password still equal
     *  [expectedPassword] and the capture. The caller holds pairingLock (re-entrant here), so
     *  the whole compare is one section. The password IS bound: a save with an IDENTICAL
     *  encoded credential but a NEWER password (a retried pairing proof) must not be
     *  overwritten by this heal's stale adopted value. [expectedPassword] is the captured
     *  original BEFORE this heal's write, and the heal's own adopted value AFTER it - the
     *  only two states a current heal may ever see. */
    private fun isCurrentLocked(store: Store, id: HealIdentity, expectedPassword: String): Boolean {
        val rec = (store.helperPairing() as? PairedCredential.State.Secure)?.credential?.encode()
        if (rec != id.record) return false
        val snap = store.helperVlcSnapshot() ?: return false
        return snap.host == id.host && snap.helperPort == id.port && snap.helperToken == id.token && snap.secure == id.secure &&
            snap.vlcPassword == expectedPassword
    }

    /**
     * The only automatic heal for a refused password: the one-time adoption fetch from the
     * PC. The phone NEVER pushes its stored password at the PC on a 401 - a refusal can be
     * an intentional change made on the PC, and pushing would silently revert it. Returns
     * the password THIS heal adopted and stored - the caller's own-write evidence - or null
     * when this heal WROTE NOTHING: the grant is spent, the helper is too old, the PC cannot
     * be reached, or the stored identity moved mid-heal. A caller publishing an outcome must
     * bind that outcome to a password state this cycle actually owns - see
     * [postHealOwnershipOk]; a bare boolean cannot distinguish "refused, wrote nothing" from
     * "wrote", and only the written value is a state this cycle owns after a write.
     *
     * The adopt/ack function params are a TEST SEAM ONLY (same pattern as Store's primary
     * constructor): production callers pass nothing and get HelperClient.
     */
    @Synchronized
    fun tryHealOnce(
        store: Store,
        adopt: (String, Int, String, String) -> String? = HelperClient::adoptVlcPassword,
        ack: (String, Int, String, String) -> Boolean = HelperClient::ackVlcAdoption,
    ): String? {
        if (healing) return null
        val id = captureHealIdentity(store) ?: return null
        healing = true
        try {
            // Persist the request id before sending: a crash or a lost response replays the
            // same id (the helper re-serves it) instead of burning the one-shot grant. The
            // mint lands ONLY while the store is still the captured pairing - a re-pair or
            // forget mid-heal must not mint an id into the new pairing's slot.
            val reqId = id.reqId ?: java.util.UUID.randomUUID().toString().also {
                val minted = synchronized(pairingLock) {
                    if (!isCurrentLocked(store, id, id.vlcPassword)) false else { store.vlcAdoptRequestId = it; true }
                }
                if (!minted) return null
            }
            val pw = adopt(id.host, id.port, id.token, reqId) ?: return null
            // The password write lands ONLY while the stored record is still the exact one
            // that fetched it: a replacement/forget during the network call skips the
            // write - the old PC's password never lands on a new pairing.
            val wrote = synchronized(pairingLock) {
                if (!isCurrentLocked(store, id, id.vlcPassword)) false else { store.vlcPassword = pw; true }
            }
            if (!wrote) return null
            // The write HAS landed: from here the truthful own-write evidence is pw, whatever
            // ACK/clear do. Returning null here would reclassify a real write as "wrote
            // nothing" and let a restored-original state pass the post-heal ownership check.
            // An ACK failure or throw leaves the request-id slot for replay - the existing
            // ACK/retry policy, unchanged.
            try {
                if (ack(id.host, id.port, id.token, reqId)) {
                    // Same compare for the clear, expecting THIS heal's own written password
                    // (the one accepted post-write state): a stale heal never erases a new
                    // pairing's slot.
                    synchronized(pairingLock) {
                        if (isCurrentLocked(store, id, pw)) store.vlcAdoptRequestId = null
                    }
                }
            } catch (e: Exception) {
                // ACK/clear threw after the write: slot preserved for replay; evidence stays pw.
            }
            return pw
        } catch (e: Exception) {
            return null
        } finally {
            healing = false
        }
    }

    /** Whether the store state observed AFTER a heal attempt is one this cycle may publish an
     *  outcome for. Requires the cycle's ORIGINAL full record encode AND exactly the password
     *  state this cycle owns: the captured original when the heal wrote nothing
     *  ([healWrote] == null), or THIS heal's own adopted value when it wrote. Everything else
     *  is another writer's state: a same-encode save with a newer password, and also the
     *  ORIGINAL value seen again after our own write (only another writer's restore can put
     *  it back). A null original encode (legacy pairing) or a null current read fails
     *  closed - the post-heal branch has never published for those. */
    internal fun postHealOwnershipOk(
        originalEncode: String?, originalPassword: String, healWrote: String?,
        currentEncode: String?, currentPassword: String?,
    ): Boolean =
        originalEncode != null && currentEncode == originalEncode &&
            if (healWrote != null) currentPassword == healWrote else currentPassword == originalPassword
}
