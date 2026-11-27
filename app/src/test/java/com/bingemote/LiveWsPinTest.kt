package com.bingemote

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live websocket over real TLS: pinned key accepted, a different key is a hard stop with no retries, unpaired does nothing. */
class LiveWsPinTest {
    private fun waitFor(ms: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(25) }
        return cond()
    }

    private val accept = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {}
    }

    @Test fun pinnedKeyConnectsOverWssWithTheStoredToken() {
        val fx = TlsFixture(); val restore = fx.install()
        fx.server.enqueue(MockResponse().withWebSocketUpgrade(accept))
        val ws = LiveWs("127.0.0.1", fx.port, fx.token)
        try {
            ws.connect()
            val opened = waitFor { ws.open }
            assertTrue("socket never opened; requests=${fx.server.requestCount} keyChanged=${ws.keyChanged}", opened)
            val req = fx.server.takeRequest()
            assertEquals("/ws", req.path)
            assertEquals(fx.token, req.getHeader("X-BingeMote-Token"))
            assertFalse(ws.keyChanged)
        } finally { ws.close(); restore(); fx.close() }
    }

    @Test fun aDifferentKeyIsAHardStopAndNothingIsSent() {
        val real = TlsFixture(); val other = TlsFixture()
        // Saved credential pins "other"'s key but points at "real"'s port: the server presents a different key.
        val cred = PairedCredential.of(other.pcId, PairedCredential.toHex(PinPolicy.hashSpki(other.spki)), "127.0.0.1", real.port, real.token)!!
        val old = HelperClient.pairingSource
        HelperClient.pairingSource = { cred }
        real.server.enqueue(MockResponse().withWebSocketUpgrade(accept))
        val ws = LiveWs("127.0.0.1", real.port, real.token)
        try {
            ws.connect()
            assertTrue("key change not detected", waitFor { ws.keyChanged })
            assertFalse(ws.open)
            Thread.sleep(1500)   // longer than the first backoff step: no reconnect may follow
            assertEquals(0, real.server.requestCount)   // the handshake failed before any HTTP request, token never sent
        } finally { ws.close(); HelperClient.pairingSource = old; real.close(); other.close() }
    }

    @Test fun unpairedDoesNothing() {
        val fx = TlsFixture()
        val old = HelperClient.pairingSource
        HelperClient.pairingSource = { null }
        val ws = LiveWs("127.0.0.1", fx.port, fx.token)
        try {
            ws.connect()
            Thread.sleep(500)
            assertFalse(ws.open); assertEquals(0, fx.server.requestCount)
        } finally { ws.close(); HelperClient.pairingSource = old; fx.close() }
    }
}
