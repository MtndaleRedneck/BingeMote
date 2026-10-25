package com.bingemote

import java.util.concurrent.atomic.AtomicLong

/**
 * Local-only diagnostic counters for testing the mouse transport: they live in
 * memory, are never uploaded, and nothing in the UI reads them.
 */
object Diag {
    /** Frames queued on the live WebSocket. */
    val wsSends = AtomicLong()
    /** Frames dropped because the WebSocket queue was full (never re-sent over HTTP). */
    val wsDrops = AtomicLong()
    /** Mouse/scroll/zoom/button calls that went over plain HTTP (socket not live). */
    val httpFallbacks = AtomicLong()
    /** Queued playback commands that expired before they could be sent. */
    val cmdsExpired = AtomicLong()
    /** Volume steps folded into a newer step. */
    val volumeCoalesced = AtomicLong()

    fun snapshot(): String =
        "ws=${wsSends.get()} wsDrops=${wsDrops.get()} httpFallback=${httpFallbacks.get()} expired=${cmdsExpired.get()} volCoalesced=${volumeCoalesced.get()}"
}
