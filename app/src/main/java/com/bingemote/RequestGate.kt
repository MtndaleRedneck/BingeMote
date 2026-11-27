package com.bingemote

/**
 * The decision made before any helper request or websocket, as a pure function over ONE credential snapshot.
 * The supplier is read exactly once, so address, pin and token can never come from two different records.
 */
internal object RequestGate {
    sealed class Decision {
        /** Send using exactly this snapshot. */
        class Allow(val credential: PairedCredential) : Decision()
        /** No usable secure record, or the caller still names another PC. Nothing is sent. */
        object NotPaired : Decision()
        /** This PC presented a different key earlier in this process. Nothing is sent. */
        object KeyChanged : Decision()
    }

    /** [host]/[port]/[expectedToken] are what the caller still holds; null for the websocket,
     * which uses the record itself. A held value that disagrees with the record means the
     * caller's pairing is stale (a re-pair landed between the caller's read and this send):
     * nothing is sent. The token check runs BEFORE KeyChanged - a stale pairing is not a key change. */
    fun decide(source: () -> PairedCredential?, host: String?, port: Int?, expectedToken: String?): Decision {
        val c = source() ?: return Decision.NotPaired
        if (host != null && port != null && (host != c.host || port != c.port)) return Decision.NotPaired
        if (expectedToken != null && expectedToken != c.token) return Decision.NotPaired
        if (KeyChangeState.isChanged(c.pcId)) return Decision.KeyChanged
        return Decision.Allow(c)
    }

    /** Websocket reconnect: only while nothing stopped it and the saved PC has not changed its key. */
    fun mayReconnect(closedByUs: Boolean, paused: Boolean, shutdown: Boolean, source: () -> PairedCredential?): Boolean =
        !closedByUs && !paused && !shutdown && decide(source, null, null, null) is Decision.Allow
}
