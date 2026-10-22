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
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var ws: WebSocket? = null
    private var attempts = 0
    @Volatile var open = false
        private set
    @Volatile private var closedByUs = false

    @Synchronized
    fun connect() {
        if (ws != null || closedByUs || scheduler.isShutdown) return
        val req = Request.Builder()
            .url("ws://$host:$port/ws")
            .header("X-BingeMote-Token", token)
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                attempts = 0
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
        open = false
        synchronized(this) { if (ws === webSocket) ws = null }
        if (closedByUs || scheduler.isShutdown) return
        val delay = (500L shl attempts.coerceAtMost(4)).coerceAtMost(5000L) // 0.5, 1, 2, 4, 5... s
        attempts++
        try { scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
    }

    /** True when the frame went out on the live connection; false means the caller uses HTTP. */
    fun send(json: JSONObject): Boolean {
        val w = ws
        if (!open || w == null) return false
        return try { w.send(json.toString()) } catch (e: Exception) { false }
    }

    @Synchronized
    fun close() {
        closedByUs = true
        open = false
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        scheduler.shutdownNow()
    }
}
