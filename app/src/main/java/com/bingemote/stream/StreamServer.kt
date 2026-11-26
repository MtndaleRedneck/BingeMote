package com.bingemote.stream

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Serves the original movie file (no re-encode) to the PC player over plain HTTP.
 *
 * URL shape: http://<wifi-ip>:<port>/s/<sessionToken>/<id>[/sub/<n>]
 *  - Session token is random per serving session and dies with it; the pairing
 *    token is never used here (libVLC can't set custom headers, so the token
 *    rides in the path).
 *  - Range requests get proper 206 + Content-Range so libVLC can seek.
 *  - SAF content URIs are served directly via openFileDescriptor; if a provider
 *    hands back a non-seekable (pipe) descriptor we fall back to copying the file
 *    into the cache once and serving that.
 *  - Binds to the Wi-Fi interface only.
 *
 * Prototype status: platform-verification list still open (SAF seekability across
 * providers, service type, Wi-Fi lock behavior). Nothing here is final.
 */
class StreamServer(private val context: Context, private val wifiAddr: InetAddress, private val port: Int = 0,
    private val openDescriptor: (Uri, android.os.CancellationSignal) -> ParcelFileDescriptor? = { uri, signal -> context.contentResolver.openFileDescriptor(uri, "r", signal) },
    private val providerSize: (Uri, android.os.CancellationSignal) -> Long? = { uri, signal ->
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null, signal)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
    },
) {

    data class Entry(val uri: Uri, val displayName: String, val subtitles: List<Uri>)

    private val random = SecureRandom()
    private val token: String = ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>() // written on the IO thread, read on the pool threads
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val permits = java.util.concurrent.Semaphore(24)
    /** Connections closed without a response by admission (diagnostics: tests print these on a failed read). */
    val rejectedPeerCap = java.util.concurrent.atomic.AtomicInteger(0)
    val rejectedNoPermit = java.util.concurrent.atomic.AtomicInteger(0)
    private val mediaPermits = java.util.concurrent.Semaphore(8)
    private val perPeer = java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>()
    private val cancellations = java.util.concurrent.ConcurrentHashMap.newKeySet<android.os.CancellationSignal>()
    private val providers = java.util.concurrent.ConcurrentHashMap.newKeySet<Guarded<*>>()
    private val clientDeadlines = java.util.concurrent.ConcurrentHashMap<Socket, Long>()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val requests = java.util.concurrent.ConcurrentHashMap.newKeySet<RequestState>()
    /** Test seam: provider inactivity deadline in ms (production default 60 s). */
    @Volatile internal var providerDeadlineMs: Long = 60_000
    /** Test seam: stop-drain wait budget (production default DRAIN_WAIT_BUDGET_MS). */
    @Volatile internal var drainBudgetMs: Long = DRAIN_WAIT_BUDGET_MS
    /** Test-observable: the drain thread reached its end (once per stop()). */
    internal val drainFinished = java.util.concurrent.CountDownLatch(1)
    @Volatile private var running = false

    /** Serializes close() against every raw-descriptor use: while a worker holds
     *  the guard for one poll/read cycle, close() waits, so OUR close cannot slip
     *  between the worker obtaining the descriptor number and that cycle's use
     *  (descriptor reuse from our own close cannot feed bytes from the wrong file).
     *  Bounds: a ready poll does NOT bound the following Os.read - on a seekable
     *  SAF/FUSE provider the read itself can still block, and while it does, the
     *  close waits on the guard. The REQUEST no longer waits with it (M1: the
     *  deadline abandons the request without the guard); only the parked provider
     *  thread/FD remains until the read returns, holding its process-wide
     *  provider-budget permit (which bounds such parked providers). */
    private class Guarded<T : java.io.Closeable>(val inner: T) {
        /** FAIR lock, not a `synchronized` monitor: the copy/serve loops re-enter
         *  use() every poll cycle (~500 ms), and a monitor lets the just-releasing
         *  worker barge ahead of a parked close() indefinitely (observed in CI:
         *  a deadline close starved >10 s behind an idle pipe poll, so the
         *  provider's budget permit never returned). Fairness hands the gate to a
         *  waiting close at the next cycle boundary, so "close between poll
         *  cycles" actually holds. A close still waits for a parked READ inside
         *  use() (declared residual). */
        private val gate = java.util.concurrent.locks.ReentrantLock(true)
        @Volatile var closed = false; private set
        val closeQueued = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var deadlineAt: Long = Long.MAX_VALUE
        /** The request currently serving from this provider (deadline abandonment). */
        @Volatile var request: RequestState? = null
        private var onClosed: (() -> Unit)? = null
        fun close() {
            var cb: (() -> Unit)? = null
            gate.lock()
            try {
                if (!closed) { closed = true; runCatching { inner.close() } }
                cb = onClosed; onClosed = null
            } finally { gate.unlock() }
            cb?.invoke()
        }
        /** Runs [cb] exactly once: immediately if already closed, otherwise when a
         *  future close() completes - possibly only after a parked provider read
         *  returns. Used to hand abandoned-worker capacity back. */
        fun onClosedOnce(cb: () -> Unit) {
            val runNow: Boolean
            gate.lock()
            try { runNow = if (closed) true else { onClosed = cb; false } } finally { gate.unlock() }
            if (runNow) cb()
        }
        fun <R> use(block: (T) -> R): R {
            gate.lock()
            try {
                if (closed) throw java.io.InterruptedIOException("provider closed")
                return block(inner)
            } finally { gate.unlock() }
        }
    }

    /** Thrown when the process-wide provider budget is exhausted mid-serve
     *  (the copy path's second open): mapped to a clear 503, never queued. */
    private class ProviderBudgetExhausted : Exception()

    /** M1: per-request lifecycle arbitration. Exactly one of normal end (finish),
     *  provider-deadline abandonment (abandon), or stop wins the once-only release
     *  of the request's socket / open-stream count / deadline bookkeeping - and that release
     *  never touches a provider guard, so a provider parked in an uninterruptible
     *  SAF/FUSE read cannot keep the request's socket, the open-stream count, or
     *  (through the idle policy reaching zero active) the service's wake/Wi-Fi
     *  locks. The parked provider thread/FD stays until the provider returns
     *  (declared residual), holding a process-wide provider-budget permit. */
    internal class RequestState(val client: Socket, val peer: String, private val release: (RequestState) -> Unit) {
        /** Two INDEPENDENT arbitration points (M1/Tab), deliberately not one state
         *  machine: cache-commit ownership and request cleanup must not wait on
         *  each other. */
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        /** 0 OPEN, 1 COMMIT_WON, 2 CLOSED. */
        private val commitGate = java.util.concurrent.atomic.AtomicInteger(0)
        val isActive: Boolean get() = !released.get()
        /** Exactly-once logical release (socket/count/locks). Never waits: if a
         *  commit won the gate first, its rename finishes independently while the
         *  deadline/stop still releases immediately. */
        fun abandon(): Boolean = if (released.compareAndSet(false, true)) {
            commitGate.compareAndSet(0, 2)
            release(this); true
        } else false
        fun finish(): Boolean = if (released.compareAndSet(false, true)) {
            commitGate.compareAndSet(0, 2)
            release(this); true
        } else false
        /** Atomic commit-vs-abandon ownership: the rename runs only if this CAS
         *  wins while the request is neither abandoned nor finished. Returns null
         *  when refused - the caller deletes the temp file and treats the copy as
         *  uncommitted. A won commit finishes its (bounded) rename even if
         *  abandonment lands meanwhile. */
        fun <R> withCommit(block: () -> R): R? =
            if (commitGate.compareAndSet(0, 1)) block() else null
    }

    val baseUrl: String get() = "http://${wifiAddr.hostAddress}:${socket?.localPort ?: port}/s/$token"

    private val nextId = java.util.concurrent.atomic.AtomicInteger(0)

    fun addMovie(uri: Uri, name: String, subtitles: List<Uri> = emptyList()): String {
        val id = nextId.incrementAndGet().toString()
        entries[id] = Entry(uri, name, subtitles)
        return "$baseUrl/$id"
    }

    fun start() {
        activeTokens.add(token)
        cleanCache(context)
        val srv = ServerSocket(port, 8, wifiAddr)
        socket = srv
        running = true
        watchdog.scheduleWithFixedDelay({
            val now = android.os.SystemClock.elapsedRealtime()
            // Client sockets close inline: Socket.close never blocks.
            clientDeadlines.forEach { (client, at) -> if (now >= at && clientDeadlines.remove(client, at)) runCatching { client.close() } }
            // Provider closes run on the pool: a SAF/FUSE close can block, and one
            // blocked close must never stall the watchdog and every other expiry.
            providers.forEach { res ->
                if (now >= res.deadlineAt) {
                    // M1: abandon the REQUEST first, without the provider guard.
                    // Even when the worker is parked in an uninterruptible read
                    // (so the close below waits), its socket/count/locks release
                    // now, the idle policy can reach zero active, and the service's
                    // wake/Wi-Fi locks release. The provider's budget permit
                    // returns only when its close actually completes.
                    res.request?.let { req -> req.abandon() }
                    queueClose(res)
                }
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
        pool.execute {
            while (running) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                val peer = client.inetAddress.hostAddress.orEmpty()
                var count = 0
                perPeer.compute(peer) { _, current -> (current ?: java.util.concurrent.atomic.AtomicInteger(0)).also { count = it.incrementAndGet() } }
                if (count > 4) { rejectedPeerCap.incrementAndGet(); releasePeer(peer); runCatching { client.close() }; continue }
                if (!permits.tryAcquire()) { rejectedNoPermit.incrementAndGet(); releasePeer(peer); runCatching { client.close() }; continue }
                // A stalled client must not hold a worker forever (teardown C12).
                runCatching { client.soTimeout = 60_000 }
                clients.add(client)
                openStreams.incrementAndGet()
                val req = RequestState(client, peer, ::releaseLogical)
                requests.add(req)
                try {
                    pool.execute {
                        // Logical release is once-only (a deadline/stop abandonment
                        // wins over finish); the resource release below is also
                        // exactly once - here, at the worker's real exit.
                        try { runCatching { handle(client, req) } }
                        finally { req.finish(); releaseResources(req); requests.remove(req) }
                    }
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    req.finish(); releaseResources(req); requests.remove(req)
                }
            }
        }
    }

    private fun releasePeer(peer: String) {
        perPeer.computeIfPresent(peer) { _, count -> if (count.decrementAndGet() == 0) null else count }
    }

    /** The once-only LOGICAL request release (M1): client socket, open-stream
     *  count (the count the idle policy - and so the service locks - follows),
     *  deadline bookkeeping. Runs on whichever side wins the arbitration - worker
     *  end, provider deadline, or stop - and never waits on a provider guard.
     *  Deliberately does NOT release the admission permit/peer slot: those stay
     *  held until the worker's real read AND provider close return. */
    private fun releaseLogical(req: RequestState) {
        clients.remove(req.client)
        clientDeadlines.remove(req.client)
        openStreams.decrementAndGet()
        runCatching { req.client.close() }
    }

    /** Resource release: admission permit + peer slot. Exactly once per request,
     *  at the worker's real exit (its read AND its provider close have returned) -
     *  never at abandonment, so a parked provider keeps holding what it pins and
     *  admission cannot over-subscribe real resources. */
    private fun releaseResources(req: RequestState) {
        releasePeer(req.peer)
        permits.release()
    }

    /** Queue a provider's close on the worker pool, once per provider. The close
     *  itself NEVER runs on the calling thread (watchdog, stop caller, or drain):
     *  a SAF/FUSE close can block, and a blocked close must not park a timer, a
     *  shutdown path, or the drain. If the pool is already shut down (stop
     *  teardown race), just unregister - stop()'s own close pass and the
     *  worker's exit close are the owners then; a provider whose read never
     *  returns stays parked, budget-bounded (declared residual). */
    private fun queueClose(res: Guarded<*>) {
        if (!res.closeQueued.compareAndSet(false, true)) return
        try { pool.execute { providers.remove(res); res.close() } }
        catch (e: java.util.concurrent.RejectedExecutionException) { providers.remove(res) }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        cancellations.forEach { it.cancel() }
        // Cheap, unblocking closes first: cutting client sockets frees worker
        // writes, and cancel signals free pending provider opens.
        clients.forEach { runCatching { it.close() } }
        // M1: stop arbitrates with deadline/end for every live request (once-only),
        // so a parked provider worker cannot keep counts held through teardown.
        requests.forEach { it.abandon() }
        watchdog.shutdownNow()
        // Hand every provider close to the pool BEFORE shutting it down (queued
        // tasks still run under an orderly shutdown; shutdownNow would discard
        // them and cannot interrupt a parked SAF/FUSE read anyway).
        providers.forEach { queueClose(it) }
        pool.shutdown()
        kotlin.concurrent.thread(name = "stream-drain", isDaemon = true) {
            // The drain thread only WAITS - the deadline is computed FIRST and no
            // close ever runs on this thread - so a provider that never returns
            // cannot park one drain thread per stopped session for process
            // lifetime (M1). A parked close occupies a pool thread instead,
            // bounded process-wide by the provider budget (a parked provider
            // holds its permit). After the budget, remaining parked providers
            // are closed by their own workers when (if) the read returns; the
            // cache is reclaimed below and by the next start()'s cleanCache.
            val drainDeadline = android.os.SystemClock.elapsedRealtime() + drainBudgetMs
            while (!pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                if (android.os.SystemClock.elapsedRealtime() >= drainDeadline) break
            }
            // Deletion cannot race a rename: every request was abandoned above,
            // so no NEW commit can win the gate; a commit that won before
            // abandonment finishes its rename inside CacheCopier.copy before
            // the worker proceeds (never across the copy boundary), and the
            // wait above gives such workers until the budget to finish.
            // A process killed before this point leaves files behind; cleanCache()
            // on the next start() reclaims them.
            context.cacheDir.listFiles()?.filter { it.name.startsWith("stream-$token-") }?.forEach { it.delete() }
            activeTokens.remove(token)
            drainFinished.countDown()
        }
    }

    /** Open client connections right now (teardown C1: an open connection means the
     *  movie is alive - a long pause keeps the socket and must never cut the stream). */
    /** Incremented on the accept thread, decremented on pool threads: an
     *  AtomicInteger, because @Volatile does not make ++/-- atomic and a drifted
     *  count would stall (or rush) the idle watchdog (round-3 A4). */
    val openStreams = java.util.concurrent.atomic.AtomicInteger(0)
    /** True once the first request was served: the shutdown watchdog arms only after this. */
    @Volatile var servedAny = false

    /** S9: one in-flight cache copy per URI. */
    private val copyLocks = java.util.concurrent.ConcurrentHashMap<String, Object>()

    companion object {
        private val activeTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        /** M1/Tab: PROCESS-WIDE concurrent-provider budget, shared across
         *  sessions: a parked provider thread/FD survives its own session (it
         *  stays until the provider returns), so the accounting must too - a
         *  fresh session must not pretend old parked providers are gone. One
         *  permit per open provider (probe, copy, or serve), acquired with a
         *  nonwaiting tryAcquire BEFORE the open and returned only when that
         *  provider's close actually completes - after its reads end. Exhaustion
         *  rejects new streams immediately (503), so parked providers cannot
         *  accumulate without bound process-wide. Media admission
         *  (mediaPermits = 8) is capped by this budget. Internal for tests. */
        internal val providerBudget = java.util.concurrent.Semaphore(8)
        /** Cap for one non-seekable file copied into cache (teardown C12). */
        const val MAX_CACHE_COPY_BYTES = 8L * 1024 * 1024 * 1024
        /** M1: how long the stop() drain waits for provider workers before leaving
         *  the rest to worker-side closes + next-start cleanCache. Bounds the
         *  drain-thread lifetime across repeated stop/restart cycles. */
        internal const val DRAIN_WAIT_BUDGET_MS = 300_000L
        /** S9: refuse a cache copy the phone cannot hold (floor for system + VLC). */
        const val MIN_FREE_BYTES = 512L * 1024 * 1024
        /** The request-line gate: anything that is not "GET <path> ..." is refused
         *  before any serving happens - garbage must never flip servedAny. */
        internal fun validRequestLine(line: String): Boolean {
            val parts = line.split(" ")
            return parts.size >= 2 && parts[0] == "GET"
        }

        /** The cache slot for one streamed URI (temp suffix while a copy is in flight). */
        private fun cacheFile(context: android.content.Context, uri: Uri, suffix: String): java.io.File {
            val hex = java.security.MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return java.io.File(context.cacheDir, "stream-$hex$suffix")
        }

        /** Drop every cached stream copy (called when the service stops). */
        fun cleanCache(context: android.content.Context) {
            context.cacheDir.listFiles()?.filter { file -> file.name.startsWith("stream-") && activeTokens.none { file.name.startsWith("stream-$it-") } }?.forEach { it.delete() }
        }
    }

    private fun handle(client: Socket, req: RequestState) {
        val input = client.getInputStream().bufferedReader()
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        fun line(): String? {
            val b = StringBuilder()
            while (b.length < 8192) {
                val left = deadline - android.os.SystemClock.elapsedRealtime()
                if (left <= 0) throw java.net.SocketTimeoutException()
                client.soTimeout = minOf(left, 10_000L).toInt()
                val c = input.read(); if (c < 0) return null
                if (c == 10) return b.toString().trimEnd('\r')
                b.append(c.toChar())
            }
            throw java.io.IOException("header line too long")
        }
        val requestLine = line() ?: return
        if (!validRequestLine(requestLine)) return
        val parts = requestLine.split(" ")
        var range: LongRange? = null
        var headerBytes = requestLine.length
        var headerCount = 0
        while (true) {
            val line = line() ?: return
            headerBytes += line.length
            if (++headerCount > 64 || headerBytes > 32768) return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                range = HttpRange.parse(line.substringAfter(':').trim())
            }
        }
        val path = parts[1].removePrefix("/s/$token/")
        if (path == parts[1]) return reply(client, 403, "bad token".toByteArray(), "text/plain")
        val segs = path.split('/')
        val entry = entries[segs.getOrNull(0)] ?: return reply(client, 404, ByteArray(0), "text/plain")
        val uri: Uri = when {
            segs.size == 1 -> entry.uri
            segs.size == 3 && segs[1] == "sub" -> entry.subtitles.getOrNull(segs[2].toIntOrNull() ?: -1)
                ?: return reply(client, 404, ByteArray(0), "text/plain")
            else -> return reply(client, 404, ByteArray(0), "text/plain")
        }
        if (!mediaPermits.tryAcquire()) return reply(client, 503, ByteArray(0), "text/plain")
        try { client.soTimeout = 60_000; serve(client, uri, range, req) }
        finally { mediaPermits.release() }
    }

    /** Serve the document, honoring Range. Falls back to a cached copy when the
     *  provider's descriptor is not seekable. */
    private fun serve(client: Socket, uri: Uri, range: LongRange?, req: RequestState) {
        val signal = android.os.CancellationSignal()
        cancellations.add(signal)
        if (!running) { cancellations.remove(signal); return }
        val openDeadline = try { watchdog.schedule({ signal.cancel() }, 60, java.util.concurrent.TimeUnit.SECONDS) }
            catch (e: java.util.concurrent.RejectedExecutionException) { cancellations.remove(signal); return }
        // Process-wide provider budget: nonwaiting admission BEFORE the provider
        // open; the permit returns only when this provider's close completes -
        // after its reads end (own-worker close on the normal path). Never at
        // request abandonment: a parked provider keeps holding what it pins.
        if (!providerBudget.tryAcquire()) return reply(client, 503, ByteArray(0), "text/plain")
        val pfd: ParcelFileDescriptor = try { openDescriptor(uri, signal) }
            catch (e: Throwable) { providerBudget.release(); throw e }
            finally { openDeadline.cancel(false); cancellations.remove(signal) }
            ?: run { providerBudget.release(); return reply(client, 404, ByteArray(0), "text/plain") }
        if (!running) { pfd.close(); providerBudget.release(); return }
        val guarded = Guarded(pfd)
        guarded.onClosedOnce { providerBudget.release() }
        providers.add(guarded)
        if (!running) { providers.remove(guarded); guarded.close(); return }
        guarded.deadlineAt = android.os.SystemClock.elapsedRealtime() + providerDeadlineMs
        guarded.request = req
        try {
        run {
            val size = guarded.use { it.statSize }
            if (size < 0) {
                // This descriptor is only the seekability probe: close it BEFORE the
                // copy and the cached serve. Holding a pipe provider open while nobody
                // reads it can stall the provider's writer. The copy owns its own descriptor.
                providers.remove(guarded); guarded.close()
                // Non-seekable provider: copy once into cache, then serve the copy.
                // Teardown C12: the copy lands under a temp name and is renamed only when
                // COMPLETE, so a cut-off copy is never reused; oversized copies are capped.
                // N-4: SHA-256 of the URI - a 32-bit hashCode collision between two
                // different files must never share one cache slot.
                val cached = java.io.File(context.cacheDir, "stream-$token-" + cacheFile(context, uri, "").name)
                if (!cached.exists()) {
                    // S9: one copy per URI at a time - two first requests for the same
                    // non-seekable file must not write the same .part together. The
                    // second request waits, then serves the finished copy.
                    val lock = copyLocks.getOrPut(uri.toString()) { Object() }
                    synchronized(lock) {
                        if (!cached.exists()) {
                            val tmp = java.io.File(context.cacheDir, "stream-$token-" + cacheFile(context, uri, ".part").name)
                            var expectedBytes: Long? = null
                            try {
                                CacheCopier.copy(
                                    open = {
                                        val copySignal = android.os.CancellationSignal(); cancellations.add(copySignal)
                                        val copyOpenDeadline = try { watchdog.schedule({ copySignal.cancel() }, 60, java.util.concurrent.TimeUnit.SECONDS) }
                                            catch (e: java.util.concurrent.RejectedExecutionException) { cancellations.remove(copySignal); throw java.io.InterruptedIOException() }
                                        if (!providerBudget.tryAcquire()) throw ProviderBudgetExhausted()
                                        val copyFd = try {
                                            val fd = openDescriptor(uri, copySignal)
                                            // Declared length for complete-or-nothing validation, under the same
                                            // cancellation umbrella as the open. A provider that cannot answer
                                            // yields null = unknown (EOF is then the only completeness signal,
                                            // declared); a canceled query never fails the stream.
                                            if (fd != null) expectedBytes = providerSize(uri, copySignal)
                                            fd
                                        }
                                            catch (e: Throwable) { providerBudget.release(); throw e }
                                            finally { copyOpenDeadline.cancel(false); cancellations.remove(copySignal) }
                                            ?: run { providerBudget.release(); throw java.io.IOException("no provider") }
                                        if (!running) { copyFd.close(); providerBudget.release(); throw java.io.InterruptedIOException() }
                                        val input = ParcelFileDescriptor.AutoCloseInputStream(copyFd)
                                        val ginput = Guarded<java.io.InputStream>(input)
                                        ginput.onClosedOnce { providerBudget.release() }
                                        providers.add(ginput)
                                        if (!running) { providers.remove(ginput); ginput.close(); throw java.io.InterruptedIOException() }
                                        ginput.deadlineAt = android.os.SystemClock.elapsedRealtime() + providerDeadlineMs
                                        ginput.request = req
                                        object : java.io.FilterInputStream(input) {
                                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                                // Poll a non-seekable pipe instead of entering a blind read.
                                                // Each poll/read holds the guard for one cycle, so a deadline close
                                                // can only interpose between cycles; it cannot recycle the fd while
                                                // a cycle uses it. The ready poll bounds the WAIT for readability,
                                                // not the read that follows it (see Guarded).
                                                while (running) {
                                                    val ready = ginput.use {
                                                        val poll = android.system.StructPollfd().apply { this.fd = copyFd.fileDescriptor; events = android.system.OsConstants.POLLIN.toShort() }
                                                        try { android.system.Os.poll(arrayOf(poll), 500) > 0 }
                                                        catch (e: android.system.ErrnoException) { if (e.errno != android.system.OsConstants.EINTR) throw e; false }
                                                    }
                                                    if (ready) return ginput.use { raw ->
                                                        val n = raw.read(b, off, len)
                                                        ginput.deadlineAt = android.os.SystemClock.elapsedRealtime() + providerDeadlineMs
                                                        n
                                                    }
                                                }
                                                throw java.io.InterruptedIOException()
                                            }
                                            override fun close() { providers.remove(ginput); ginput.close() }
                                        }
                                    },
                                    tmp = tmp, final = cached,
                                    freeSpace = { context.cacheDir.usableSpace },
                                    maxBytes = MAX_CACHE_COPY_BYTES, minFree = MIN_FREE_BYTES, cancelled = { !running || !req.isActive },
                                    // Fully-read AND validated before the commit CAS (M1/Tab).
                                    expectedBytes = { expectedBytes },
                                    // R3/Tab: clean EOF beyond a stale positive declaration is accepted;
                                    // log a FIXED message - no URI, name or byte counts (a size can
                                    // fingerprint content).
                                    onStaleDeclared = { _, _ -> android.util.Log.w("StreamServer", "provider streamed past its declared length; accepted clean EOF") },
                                    // Atomic commit-vs-abandon ownership (M1/Tab):
                                    // the rename runs only while still ACTIVE.
                                    commit = { t, f -> req.withCommit { t.renameTo(f) } ?: false },
                                )
                            } catch (e: ProviderBudgetExhausted) {
                                return reply(client, 503, ByteArray(0), "text/plain")
                            } catch (e: CacheCopier.SizeMismatch) {
                                return reply(client, 502, ByteArray(0), "text/plain")
                            } catch (e: CacheCopier.NoSpace) {
                                return reply(client, 507, ByteArray(0), "text/plain")
                            } catch (e: CacheCopier.TooLarge) {
                                return reply(client, 413, ByteArray(0), "text/plain")
                            } catch (e: Exception) {
                                return reply(client, 500, ByteArray(0), "text/plain")
                            }
                        }
                    }
                }
                // S-2: "served" means a valid /s/<token>/<id> request actually starting
            // to send bytes - not any stray request line. Arming on junk (bad token,
            // 404 path, port scan) would strand the service on the served rule forever.
            servedAny = true
            return serveFile(client, cached, cached.length(), range, req)
            }
            servedAny = true
            serveChannel(client, guarded, size, range, guessMime(uri), req)
        }
        } finally { providers.remove(guarded); guarded.close() }
    }

    private fun serveChannel(client: Socket, guarded: Guarded<ParcelFileDescriptor>, size: Long, range: LongRange?, mime: String, req: RequestState) {
        val (code, start, end) = resolveRange(size, range)
        val out = client.getOutputStream()
        headers(client, out, code, end - start + 1, mime, size, start, end)
        if (code == 416) { out.flush(); return }
        // The PFD remains the sole descriptor owner, including stop/watchdog close.
        // Avoid a FileInputStream wrapper that would need a second ownership policy.
        guarded.use { pfd ->
            while (true) {
                try { android.system.Os.lseek(pfd.fileDescriptor, start, android.system.OsConstants.SEEK_SET); break }
                catch (e: android.system.ErrnoException) { if (e.errno != android.system.OsConstants.EINTR) throw e }
            }
        }
        val buf = ByteArray(64 * 1024)
        var left = end - start + 1
        while (left > 0) {
            // Poll, then read: the poll bounds the WAIT for readability on a stuck
            // provider; it does not bound the Os.read that follows - a seekable
            // SAF/FUSE read can still block after a ready poll, and a deadline/stop
            // close then waits on the guard for that read. The request is already
            // abandoned and released by then (M1): only this parked thread/FD waits.
            // Our close cannot recycle the fd while a cycle holds the guard.
            val n = run {
                while (running) {
                    val ready = guarded.use { pfd ->
                        val poll = android.system.StructPollfd().apply { this.fd = pfd.fileDescriptor; events = android.system.OsConstants.POLLIN.toShort() }
                        try { android.system.Os.poll(arrayOf(poll), 500) > 0 }
                        catch (e: android.system.ErrnoException) { if (e.errno != android.system.OsConstants.EINTR) throw e; false }
                    }
                    if (ready) return@run guarded.use { pfd ->
                        var read: Int
                        while (true) {
                            try { read = android.system.Os.read(pfd.fileDescriptor, buf, 0, minOf(buf.size.toLong(), left).toInt()); break }
                            catch (e: android.system.ErrnoException) {
                                if (e.errno != android.system.OsConstants.EINTR) throw EOFException().apply { initCause(e) }
                            }
                        }
                        read
                    }
                }
                throw java.io.InterruptedIOException()
            }
            if (n <= 0) throw EOFException()
            // Abandoned while the provider was parked: never write to the
            // (closed) socket afterwards.
            if (!req.isActive) throw java.io.InterruptedIOException("request abandoned")
            guarded.deadlineAt = android.os.SystemClock.elapsedRealtime() + providerDeadlineMs
            clientDeadlines[client] = android.os.SystemClock.elapsedRealtime() + 60_000
            out.write(buf, 0, n)
            clientDeadlines.remove(client)
            left -= n
        }
        out.flush()
    }

    private fun serveFile(client: Socket, f: java.io.File, size: Long, range: LongRange?, req: RequestState) {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val (code, start, end) = resolveRange(size, range)
            val out = client.getOutputStream()
            headers(client, out, code, end - start + 1, "video/*", size, start, end)
            if (code == 416) { out.flush(); return }
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = end - start + 1
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                if (!req.isActive) throw java.io.InterruptedIOException("request abandoned")
                clientDeadlines[client] = android.os.SystemClock.elapsedRealtime() + 60_000
                out.write(buf, 0, n)
                clientDeadlines.remove(client)
                left -= n
            }
            out.flush()
        }
    }

    internal fun resolveRange(size: Long, range: LongRange?): Triple<Int, Long, Long> {
        return HttpRange.resolve(size, range)
    }

    private fun headers(client: Socket, out: java.io.OutputStream, code: Int, len: Long, mime: String, total: Long, start: Long, end: Long) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(if (code == 206) " Partial Content" else if (code == 200) " OK" else if (code == 416) " Range Not Satisfiable" else " Error").append("\r\n")
        sb.append("Content-Length: ").append(len).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (code == 416) sb.append("Content-Range: bytes */").append(total).append("\r\n")
        if (code == 206) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        clientDeadlines[client] = android.os.SystemClock.elapsedRealtime() + 60_000
        try { out.write(sb.toString().toByteArray(Charsets.US_ASCII)) } finally { clientDeadlines.remove(client) }
    }

    private fun reply(client: Socket, code: Int, body: ByteArray, mime: String) {
        val out = client.getOutputStream()
        headers(client, out, code, body.size.toLong(), mime, body.size.toLong(), 0, body.size - 1L)
        out.write(body); out.flush()
    }

    private fun guessMime(uri: Uri): String =
        (context.contentResolver.getType(uri) ?: "video/*").let { m -> if (Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+*-]+").matches(m)) m else "video/*" } // no CR/LF can reach the header
}
