package com.bingemote

/** What a 401 from VLC means and the ONE automatic thing the phone may do about it. */
object VlcAuthRepair {
    @Volatile private var healing = false

    /**
     * The only automatic heal for a refused password: the one-time adoption fetch from the
     * PC. The phone NEVER pushes its stored password at the PC on a 401 - a refusal can be
     * an intentional change made on the PC, and pushing would silently revert it. Returns
     * true when a fresh password was adopted and stored; false when the grant is spent, the
     * helper is too old, or the PC cannot be reached - the caller then surfaces the refusal.
     */
    @Synchronized
    fun tryHealOnce(store: Store): Boolean {
        if (healing) return false
        if (store.host.isBlank() || store.helperToken.isBlank()) return false
        healing = true
        try {
            // Persist the request id before sending: a crash or a lost response replays the
            // same id (the helper re-serves it) instead of burning the one-shot grant.
            val reqId = store.vlcAdoptRequestId
                ?: java.util.UUID.randomUUID().toString().also { store.vlcAdoptRequestId = it }
            val pw = HelperClient.adoptVlcPassword(store.host, store.helperPort, store.helperToken, reqId)
                ?: return false
            store.vlcPassword = pw
            if (HelperClient.ackVlcAdoption(store.host, store.helperPort, store.helperToken, reqId)) {
                store.vlcAdoptRequestId = null
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            healing = false
        }
    }
}
