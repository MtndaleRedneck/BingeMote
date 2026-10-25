package com.bingemote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The live mouse connection: one always-open WebSocket to the helper on the same port,
 * fire-and-forget frames for move/scroll/zoom/button. When it is not open the callers
 * fall back to plain HTTP requests, so an old PC, a refused handshake or a Wi-Fi blip
 * costs nothing. Reconnects by itself with a short backoff (0.5, 1, 2 s, capped at 5 s);
 * moves made while it is down go out through the HTTP fallback, never saved up.
 */
class LiveWs(private val host: String, private val port: Int, private val token: String) {
    // Server pings keep a half-dead Wi-Fi socket from sitting open undetected.
    // 3 s: a half-dead socket (PC slept, Wi-Fi dropped without a FIN) is noticed within a
    // few seconds, not 30 - until then send() would report success into the void.
    private val client = OkHttpClient.Builder().pingInterval(3, TimeUnit.SECONDS).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var ws: WebSocket? = null
    private var attempts = 0
    @Volatile var open = false
        private set
    @Volatile private var closedByUs = false
    @Volatile private var paused = false
    private var pendingReconnect: java.util.concurrent.ScheduledFuture<*>? = null
    private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null
    // Per-connection frame numbers: the helper logs a gap when one goes missing.
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    private fun startHeartbeat() {
        stopHeartbeat()
        // The helper releases held mouse buttons and closes the socket after ~8 s of
        // silence, so a quietly-dead app can't leave the PC's mouse pressed.
        heartbeat = try {
            scheduler.scheduleWithFixedDelay({
                try { ws?.send("{\"cmd\":\"heartbeat\"}") } catch (_: Exception) {}
            }, 2, 2, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
    }

    private fun stopHeartbeat() {
        try { heartbeat?.cancel(false) } catch (_: Exception) {}
        heartbeat = null
    }

    @Synchronized
    fun connect() {
        if (ws != null || closedByUs || paused || scheduler.isShutdown) return
        if (host.isBlank() || port <= 0) return // not paired yet; HTTP callers no-op the same way
        val req = try {
            Request.Builder()
                .url("ws://$host:$port/ws")
                .header("X-BingeMote-Token", token)
                .build()
        } catch (e: Exception) { return } // a malformed saved host must never crash the screen
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // A late onOpen from a replaced socket must not mark the new one open
                // (drop() already checks this; onOpen did not).
                val ours = synchronized(this) { ws === webSocket }
                if (!ours) {
                    try { webSocket.close(1000, null) } catch (_: Exception) {}
                    return
                }
                open = true
                attempts = 0
                startHeartbeat()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                drop(webSocket)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                drop(webSocket)
            }
        })
    }

    private fun drop(webSocket: WebSocket) {
        // A late callback from an old socket must not mark a newer healthy one closed.
        val ours = synchronized(this) { if (ws === webSocket) { ws = null; true } else false }
        if (ours) { open = false; stopHeartbeat() }
        if (closedByUs || paused || scheduler.isShutdown) return
        val delay = (500L shl attempts.coerceAtMost(4)).coerceAtMost(5000L) // 0.5, 1, 2, 4, 5... s
        attempts++
        try { synchronized(this) { pendingReconnect = scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS) } } catch (_: Exception) {}
    }

    /** App went to the background: close the socket and stop the reconnect clock. A dead
     *  Wi-Fi must not keep the radio (and the battery) busy while he is not looking at it. */
    @Synchronized
    fun pause() {
        paused = true
        stopHeartbeat()
        try { pendingReconnect?.cancel(false) } catch (_: Exception) {}
        pendingReconnect = null
        open = false
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
    }

    /** Back in the foreground: reconnect right away, backoff reset. */
    @Synchronized
    fun resume() {
        if (!paused || closedByUs || scheduler.isShutdown) return
        paused = false
        attempts = 0
        connect()
    }

    /** True when the frame was queued on the live connection. False when the socket is
     * not open (caller may use HTTP) or the queue is full (caller drops; mixing channels
     * mid-stream reordered frames, which is the jerky-mouse suspect). */
    fun send(json: JSONObject): Boolean {
        val w = ws
        if (!open || w == null) return false
        if (w.queueSize() > 2048) return false
        json.put("seq", seq.incrementAndGet())
        return try { w.send(json.toString()) } catch (e: Exception) { false }
    }

    @Synchronized
    fun close() {
        closedByUs = true
        open = false
        stopHeartbeat()
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        scheduler.shutdownNow()
    }
}
