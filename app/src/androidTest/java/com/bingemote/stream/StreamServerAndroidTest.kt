package com.bingemote.stream

import android.net.Uri
import androidx.test.runner.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Loopback integration test: real sockets, real Range handling, real token
 *  checks, against a synthetic media file. SAF/provider paths use a file:// URI
 *  (contentResolver-backed, same descriptor route as SAF). */
@RunWith(AndroidJUnit4::class)
class StreamServerAndroidTest {

    private lateinit var server: StreamServer
    private lateinit var file: File
    private lateinit var movieUrl: String
    private val bytes = ByteArray(1000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        StreamServer.providerBudget.run { drainPermits(); release(8) }
        android.util.Log.i("StreamAudit", "APP_PID=${android.os.Process.myPid()}")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "synthetic-movie.bin").apply { writeBytes(bytes) }
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0)
        server.start()
        movieUrl = server.addMovie(Uri.fromFile(file), "synthetic-movie.bin")
    }

    @After
    fun tearDown() {
        server.stop()
        file.delete()
        // Make abandoned CloseGuard-owned objects visible in the captured app log.
        System.gc(); System.runFinalization(); Thread.sleep(100)
    }

    private fun get(url: String, range: String? = null): Triple<Int, Map<String, List<String>>, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        return Triple(code, c.headerFields, body)
    }

    /** Actual pipe/copy/stop lifecycle. Deliberately not a SAF permission/grant test. */
    @Test fun stalledPipeStopReturnsAndWorkersDrain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
        val opened = java.util.concurrent.atomic.AtomicInteger()
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
            android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]); opened.incrementAndGet() }[0]
        })
        server.start()
        val url = server.addMovie(Uri.parse("content://synthetic-pipe/stall"), "stall.mkv")
        val client = kotlin.concurrent.thread { runCatching { get(url) } }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (opened.get() < 2 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            assertTrue("copy pipe was opened", opened.get() >= 2)
            val before = android.os.SystemClock.elapsedRealtime(); server.stop()
            assertTrue("stop must return promptly", android.os.SystemClock.elapsedRealtime() - before < 2000)
            client.join(5000); assertTrue("stalled client ended", !client.isAlive)
            val until = android.os.SystemClock.elapsedRealtime()+5000
            while (server.openStreams.get()>0 && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(10)
            assertEquals(0, server.openStreams.get())
            // Keep producer ends open until after drain assertions: no false release from test teardown.
            // M1: openStreams reaches zero at the request's LOGICAL release; the
            // abandoned copy worker deletes its temp when it physically ends (or
            // the drain sweep does). Wait for that physical cleanup, don't race it.
            fun leftoverPart() = context.cacheDir.listFiles().orEmpty().any { it.name.endsWith(".part") && it.name.startsWith("stream-") }
            val gone = android.os.SystemClock.elapsedRealtime() + 10_000
            while (leftoverPart() && android.os.SystemClock.elapsedRealtime() < gone) Thread.sleep(50)
            assertTrue("abandoned copy's temp is removed once the worker physically ends", !leftoverPart())
        } finally {
            server.stop(); writers.forEach { runCatching { it.close() } }; client.join(5000)
        }
    }
    @Test fun stalledWriterIsClosedByDeadline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val large = File(context.cacheDir, "stalled-writer.bin")
        java.io.RandomAccessFile(large,"rw").use { it.setLength(128L*1024*1024) }
        val url = URL(server.addMovie(Uri.fromFile(large), "large"))
        val sock = java.net.Socket(); sock.receiveBufferSize=1024; sock.connect(java.net.InetSocketAddress(url.host,url.port))
        sock.getOutputStream().write("GET ${url.path} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        try {
            Thread.sleep(200)
            val until=android.os.SystemClock.elapsedRealtime()+65000
            while(server.openStreams.get()>0 && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(100)
            assertEquals("stalled writer released",0,server.openStreams.get())
        } finally {sock.close();large.delete()}
    }
    @Test fun restartPurgesStaleCacheAndKeepsOtherCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stale = File(context.cacheDir, "stream-old-dead.part").apply { writeText("old") }
        val unrelated = File(context.cacheDir, "unrelated.txt").apply { writeText("keep") }
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0)
        server.start()
        assertTrue(!stale.exists()); assertTrue(unrelated.exists()); unrelated.delete()
    }
    @Test fun rangeErrorsAndUnsupportedRanges_realSockets() {
        for (range in listOf("bytes=1000-", "bytes=900-800")) {
            val (code, headers, body) = get(movieUrl, range)
            assertEquals(416, code); assertEquals(0, body.size)
            assertTrue(headers.entries.any { it.key.equals("Content-Range", true) && it.value.contains("bytes */1000") })
        }
        for (range in listOf("items=0-9", "bytes=-9", "bytes=0-9,20-30", "bytes=garbage")) {
            val (code, _, body) = get(movieUrl, range)
            assertEquals(200, code); assertArrayEquals(bytes, body)
        }
    }

    @Test
    fun abandoned100LargeSeeks_recoverAndStillLoadSubtitle() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val large=File(context.cacheDir,"abandoned-seeks.bin")
        java.io.RandomAccessFile(large,"rw").use { it.setLength(16L*1024*1024) }
        val sub=File(context.cacheDir,"abandoned-seeks.srt").apply { writeText("1\n00:00:00,000 --> 00:00:03,000\nHello\n") }
        try {
            val url=server.addMovie(Uri.fromFile(large),"large",listOf(Uri.fromFile(sub)))
            repeat(100) { i ->
                val c=URL(url).openConnection() as HttpURLConnection;c.setRequestProperty("Range","bytes=${i*1000}-");c.connectTimeout=3000;c.readTimeout=3000
                try { assertEquals(206,c.responseCode);c.inputStream.use { assertTrue(it.read(ByteArray(1024))>0) } } finally { c.disconnect() }
            }
            assertArrayEquals(sub.readBytes(),get("$url/sub/0").third)
            val until=android.os.SystemClock.elapsedRealtime()+5000
            while(server.openStreams.get()>0 && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(10)
            assertEquals(0,server.openStreams.get())
        } finally {large.delete();sub.delete()}
    }

    @Test
    fun rapid100SeeksAndSubtitle_realSocketsPreserveBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sub = File(context.cacheDir, "rapid-seek.srt").apply { writeText("1\n00:00:00,000 --> 00:00:03,000\nHello\n") }
        try {
            val url = server.addMovie(Uri.fromFile(file), "movie", listOf(Uri.fromFile(sub)))
            // Warm the HTTP implementation before measuring actual process FD counts.
            get(url, "bytes=0-16")
            fun settledFdCount(): Int {
                val until = android.os.SystemClock.elapsedRealtime() + 5000
                while (server.openStreams.get() > 0 && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(10)
                assertEquals("all request workers drained", 0, server.openStreams.get())
                System.gc(); System.runFinalization(); Thread.sleep(100)
                return java.io.File("/proc/self/fd").list()?.size ?: error("FD count unavailable")
            }
            val beforeFds = settledFdCount()
            repeat(200) { i ->
                val start = i * 7 % 900
                val (code, _, body) = get(url, "bytes=$start-${start+16}")
                assertEquals(206, code); assertArrayEquals(bytes.copyOfRange(start,start+17),body)
                if (i % 10 == 0) assertArrayEquals(sub.readBytes(),get("$url/sub/0").third)
            }
            val afterFds = settledFdCount()
            android.util.Log.i("StreamAudit", "FD_COUNT before=$beforeFds after=$afterFds allowance=10")
            assertTrue("per-request FDs retained: before=$beforeFds after=$afterFds", afterFds <= beforeFds + 10)
        } finally { sub.delete() }
    }

    @Test
    fun oversizedHeader_refusedWithoutServing() {
        val u = URL(movieUrl)
        java.net.Socket(u.host, u.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("GET ${u.path} HTTP/1.1\r\nX-Large: " + "x".repeat(9000) + "\r\n\r\n").toByteArray())
            assertEquals(-1, socket.getInputStream().read())
        }
        org.junit.Assert.assertFalse(server.servedAny)
    }

    @Test
    fun connectionCap_doesNotCreateUnboundedWorkers() {
        val u = URL(movieUrl)
        val sockets = (1..12).map { java.net.Socket(u.host, u.port) }
        try {
            Thread.sleep(250)
            assertTrue(server.openStreams.get() <= 4)
            org.junit.Assert.assertFalse(server.servedAny)
        } finally { sockets.forEach { it.close() } }
    }

    @Test
    fun fullGet_200_allBytes() {
        val (code, headers, body) = get(movieUrl)
        assertEquals(200, code)
        assertArrayEquals(bytes, body)
        assertEquals("1000", headers["Content-Length"]?.firstOrNull())
    }

    @Test
    fun rangeGet_206_correctSpanAndHeader() {
        val (code, headers, body) = get(movieUrl, "bytes=10-19")
        assertEquals(206, code)
        assertEquals(10, body.size)
        assertArrayEquals(bytes.copyOfRange(10, 20), body)
        assertEquals("bytes 10-19/1000", headers["Content-Range"]?.firstOrNull())
    }

    @Test
    fun openEndedRange_clampsToEnd() {
        val (code, headers, body) = get(movieUrl, "bytes=990-")
        assertEquals(206, code)
        assertEquals(10, body.size)
        assertEquals("bytes 990-999/1000", headers["Content-Range"]?.firstOrNull())
    }

    @Test
    fun wrongToken_403() {
        val bad = movieUrl.replace(Regex("/s/[^/]+/"), "/s/000000000000000000000000000000000000/")
        val (code, _, _) = get(bad)
        assertEquals(403, code)
    }

    @Test
    fun unknownMovie_404() {
        val (code, _, _) = get("$movieUrl".replaceAfterLast('/', "99"))
        assertEquals(404, code)
    }

    @Test
    fun urlShape_carriesSessionTokenNotPairingToken() {
        assertTrue(movieUrl.matches(Regex("http://127\\.0\\.0\\.1:\\d+/s/[0-9a-f]{36}/1")))
    }

    /** M1: a provider that never returns must not pin the REQUEST. The deadline
     *  abandons it without the provider guard: socket closed, open-stream count
     *  released (the count the idle policy and therefore the service locks follow),
     *  while the writer end stays open (the provider never came back). */
    @Test fun stalledProviderDeadline_abandonsRequestBeforeProviderReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
            android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]) }[0]
        })
        server.providerDeadlineMs = 1500
        server.start()
        val url = server.addMovie(Uri.parse("content://synthetic-pipe/stall"), "stall.mkv")
        val sock = java.net.Socket()
        try {
            val u = URL(url)
            sock.connect(java.net.InetSocketAddress(u.host, u.port))
            sock.soTimeout = 30_000
            sock.getOutputStream().write("GET ${u.path} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val until = android.os.SystemClock.elapsedRealtime() + 15_000
            while (server.openStreams.get() > 0 && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            assertEquals("request released without the provider returning", 0, server.openStreams.get())
            assertEquals("server closed the client socket", -1, sock.getInputStream().read())
            // The pipe provider's own close completes between poll cycles, so the
            // budget permit comes back (a truly parked SAF/FUSE provider would
            // keep it held - that path is declared, not emulatable here).
            val settle = android.os.SystemClock.elapsedRealtime() + 10_000
            while (StreamServer.providerBudget.availablePermits() < 8 && android.os.SystemClock.elapsedRealtime() < settle) Thread.sleep(50)
            assertEquals("closed provider returns its budget permit", 8, StreamServer.providerBudget.availablePermits())
        } finally {
            server.stop(); writers.forEach { runCatching { it.close() } }; runCatching { sock.close() }
        }
    }

    /** M1: bytes that arrive AFTER the abandonment must never be written to the
     *  (closed) client socket, and the abandoned copy must never commit into the
     *  cache (no rename, temp deleted). */
    @Test fun lateProviderReturn_noWriteNoCommit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
            android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]) }[0]
        })
        server.providerDeadlineMs = 1500
        server.start()
        val url = server.addMovie(Uri.parse("content://synthetic-pipe/late"), "late.mkv")
        val received = java.util.concurrent.atomic.AtomicInteger()
        val client = kotlin.concurrent.thread {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.readTimeout = 30_000
                val stream = runCatching { c.inputStream }.getOrNull() ?: return@runCatching
                stream.use { val b = ByteArray(4096); while (it.read(b) >= 0) received.incrementAndGet() }
            }
        }
        try {
            val until = android.os.SystemClock.elapsedRealtime() + 15_000
            while (server.openStreams.get() > 0 && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            assertEquals("request abandoned", 0, server.openStreams.get())
            client.join(15_000); assertTrue("client connection ended", !client.isAlive)
            // Late provider return: data shows up only now.
            writers.forEach { w -> runCatching { android.os.ParcelFileDescriptor.AutoCloseOutputStream(w).use { it.write(ByteArray(8192)); it.flush() } } }
            Thread.sleep(1500)
            assertEquals("no bytes written after abandonment", 0, received.get())
            assertTrue("no cache commit from the abandoned copy",
                context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("stream-") })
            val settle = android.os.SystemClock.elapsedRealtime() + 10_000
            while (StreamServer.providerBudget.availablePermits() < 8 && android.os.SystemClock.elapsedRealtime() < settle) Thread.sleep(50)
            assertEquals("late provider closed by the worker", 8, StreamServer.providerBudget.availablePermits())
        } finally {
            server.stop(); writers.forEach { runCatching { it.close() } }; client.join(5000)
        }
    }

    /** M1: stop() racing the provider deadline still releases exactly once -
     *  the count lands on zero and never goes negative. */
    @Test fun stopAndDeadline_onceOnlyRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
            android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]) }[0]
        })
        server.providerDeadlineMs = 1500
        server.start()
        val url = server.addMovie(Uri.parse("content://synthetic-pipe/race"), "race.mkv")
        val client = kotlin.concurrent.thread { runCatching { get(url) } }
        try {
            Thread.sleep(1200) // right around the deadline window
            val before = android.os.SystemClock.elapsedRealtime(); server.stop()
            assertTrue("stop must return promptly", android.os.SystemClock.elapsedRealtime() - before < 2000)
            client.join(5000); assertTrue("client ended", !client.isAlive)
            assertEquals(0, server.openStreams.get())
            Thread.sleep(1000)
            assertEquals("no double release", 0, server.openStreams.get())
        } finally {
            server.stop(); writers.forEach { runCatching { it.close() } }; client.join(5000)
        }
    }

    /** M1/Tab: the process-wide provider budget rejects new streams immediately
     *  when exhausted - no queue, no unbounded parked workers. (Exhaustion is
     *  injected by holding all permits; the end-to-end hold/release wiring is
     *  proven by the deadline tests.) */
    @Test fun providerBudgetExhausted_rejectsWith503() {
        assertTrue(StreamServer.providerBudget.tryAcquire(8))
        try {
            assertEquals("further stream clearly refused", 503, get(movieUrl).first)
        } finally { StreamServer.providerBudget.release(8) }
    }

    /** M1/Tab: the budget gate is process-wide and shared across clients: every
     *  concurrent new stream is refused while exhausted. (One loopback peer
     *  stands in for multiple clients; the per-peer accept cap is 4, so three
     *  concurrent requests stay under it and all reach the gate.) */
    @Test fun providerBudgetExhausted_concurrentStreamsAllRefused503() {
        assertTrue(StreamServer.providerBudget.tryAcquire(8))
        try {
            val codes = java.util.concurrent.ConcurrentLinkedQueue<Int>()
            val clients = (1..3).map { kotlin.concurrent.thread { runCatching { codes.add(get(movieUrl).first) } } }
            clients.forEach { it.join(10_000) }
            assertEquals(3, codes.size)
            assertTrue("every concurrent stream refused", codes.all { it == 503 })
        } finally { StreamServer.providerBudget.release(8) }
    }

    /** M1/Tab: the budget survives a service restart - a fresh session must not
     *  pretend old parked providers are gone. */
    @Test fun providerBudgetSurvivesRestart_newSessionRefusesUntilPermitsReturn() {
        assertTrue(StreamServer.providerBudget.tryAcquire(8))
        server.stop()
        server = StreamServer(InstrumentationRegistry.getInstrumentation().targetContext, InetAddress.getByName("127.0.0.1"), 0)
        server.start()
        try {
            val url = server.addMovie(Uri.fromFile(file), "fresh")
            assertEquals("parked providers from the old session still count", 503, get(url).first)
            StreamServer.providerBudget.release(8) // parked providers finally closed
            assertEquals(200, get(url).first)
        } finally {
            StreamServer.providerBudget.run { drainPermits(); release(8) }
            server.stop()
        }
    }

    /** M1/Tab: a provider permit is held for the provider's whole lifetime -
     *  from before the open through the reads until the close completes - and
     *  never freed by request abandonment alone. Pipe fakes prove the
     *  ownership/wiring, not a physically blocked close (declared). */
    @Test fun providerPermit_heldThroughReadUntilCloseReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
            android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]) }[0]
        })
        server.providerDeadlineMs = 60_000 // no abandonment in this test
        server.start()
        val url = server.addMovie(Uri.parse("content://synthetic-pipe/permit"), "permit.mkv")
        val client = kotlin.concurrent.thread { runCatching { get(url) } }
        try {
            val until = android.os.SystemClock.elapsedRealtime() + 10_000
            while (writers.size < 2 && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(10)
            assertTrue("copy provider open", writers.size >= 2)
            assertEquals("one permit held while the copy provider is open", 7, StreamServer.providerBudget.availablePermits())
            server.stop()
            client.join(5000)
            val settle = android.os.SystemClock.elapsedRealtime() + 10_000
            while (StreamServer.providerBudget.availablePermits() < 8 && android.os.SystemClock.elapsedRealtime() < settle) Thread.sleep(50)
            assertEquals("permit returned after the close completed", 8, StreamServer.providerBudget.availablePermits())
        } finally {
            server.stop(); writers.forEach { runCatching { it.close() } }; client.join(5000)
        }
    }

    /** M1/Tab: commit vs abandonment - complete entry or none, never partial,
     *  never a leftover temp. Rounds vary the deadline around the copy; the
     *  atomic ownership itself is unit-tested in RequestStateTest. */
    @Test fun abandonVsCacheCommit_completeEntryOrNone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
        repeat(6) { iter ->
            val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
            server.stop()
            server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
                android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]) }[0]
            }, providerSize = { _, _ -> payload.size.toLong() })
            server.providerDeadlineMs = 50 + iter * 60L // fire around the copy window
            server.start()
            val url = server.addMovie(Uri.parse("content://synthetic-pipe/commit$iter"), "commit$iter.mkv")
            val client = kotlin.concurrent.thread { runCatching { get(url) } }
            try {
                val opened = android.os.SystemClock.elapsedRealtime() + 10_000
                while (writers.size < 2 && android.os.SystemClock.elapsedRealtime() < opened) Thread.sleep(10)
                assertTrue("copy pipe opened", writers.size >= 2)
                kotlin.concurrent.thread {
                    runCatching {
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(writers[1]).use { out ->
                            var off = 0
                            while (off < payload.size) {
                                val n = minOf(4096, payload.size - off)
                                out.write(payload, off, n); out.flush(); off += n
                                Thread.sleep(5) // pace ~160ms total so copies straddle the 50-350ms deadlines
                            }
                        }
                    }
                }
                val until = android.os.SystemClock.elapsedRealtime() + 15_000
                while (server.openStreams.get() > 0 && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(25)
                client.join(10_000)
                val left = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("stream-") }
                assertTrue("no leftover temp", left.none { it.name.endsWith(".part") })
                left.forEach {
                    assertEquals("commit is the COMPLETE payload or nothing", payload.size.toLong(), it.length())
                    assertArrayEquals(payload, it.readBytes())
                }
            } finally {
                server.stop(); writers.forEach { runCatching { it.close() } }; client.join(5000)
            }
        }
    }

    /** M1/Tab: a provider that ends its stream EARLY vs its declared length -
     *  the truncated copy must never be committed (validation before the
     *  commit gate), never left as a temp, and the answer is a clear error. */
    @Test fun shortProviderCopy_neverCommits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
            android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]) }[0]
        }, providerSize = { _, _ -> 8192L })
        server.start()
        val url = server.addMovie(Uri.parse("content://synthetic-pipe/short"), "short.mkv")
        val code = java.util.concurrent.atomic.AtomicInteger(-1)
        val client = kotlin.concurrent.thread { runCatching { code.set(get(url).first) } }
        try {
            val opened = android.os.SystemClock.elapsedRealtime() + 10_000
            while (writers.size < 2 && android.os.SystemClock.elapsedRealtime() < opened) Thread.sleep(10)
            assertTrue("copy pipe opened", writers.size >= 2)
            // Half the declared length, then EOF.
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(writers[1]).use { it.write(ByteArray(4096)); it.flush() }
            client.join(10_000)
            assertFalse("client ended", client.isAlive)
            assertEquals("truncated copy is a clear provider error", 502, code.get())
            val left = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("stream-") }
            assertTrue("no committed entry from a short copy", left.none { !it.name.endsWith(".part") })
            assertTrue("no leftover temp", left.none { it.name.endsWith(".part") })
            val settle = android.os.SystemClock.elapsedRealtime() + 10_000
            while (StreamServer.providerBudget.availablePermits() < 8 && android.os.SystemClock.elapsedRealtime() < settle) Thread.sleep(50)
            assertEquals("provider permit returned", 8, StreamServer.providerBudget.availablePermits())
        } finally {
            server.stop(); writers.forEach { runCatching { it.close() } }; client.join(5000)
        }
    }

    /** R3/Tab: a non-seekable provider that declares SIZE 0 for streamed content
     *  (common for streamed/generated media) must play as UNKNOWN length: EOF
     *  completes the copy and the entry commits. On bd80f63 this ended in 502 -
     *  a regression vs 1.3, which had no declared-length validation. */
    @Test fun zeroDeclaredSize_copyCompletesAsUnknownLength() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = ByteArray(64 * 1024) { (it % 239).toByte() }
        val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
            android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]) }[0]
        }, providerSize = { _, _ -> 0L })
        server.start()
        val url = server.addMovie(Uri.parse("content://synthetic-pipe/zerolen"), "zerolen.mkv")
        var code = -1; var body = ByteArray(0)
        val client = kotlin.concurrent.thread { runCatching { get(url).also { code = it.first; body = it.third } } }
        try {
            val opened = android.os.SystemClock.elapsedRealtime() + 10_000
            while (writers.size < 2 && android.os.SystemClock.elapsedRealtime() < opened) Thread.sleep(10)
            assertTrue("copy pipe opened", writers.size >= 2)
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(writers[1]).use { it.write(payload); it.flush() }
            client.join(10_000)
            assertFalse("client ended", client.isAlive)
            assertEquals("zero declared size plays as unknown length", 200, code)
            assertTrue("full payload served", payload.contentEquals(body))
            val left = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("stream-") }
            assertTrue("committed entry exists", left.any { !it.name.endsWith(".part") })
            assertTrue("no leftover temp", left.none { it.name.endsWith(".part") })
            val settle = android.os.SystemClock.elapsedRealtime() + 10_000
            while (StreamServer.providerBudget.availablePermits() < 8 && android.os.SystemClock.elapsedRealtime() < settle) Thread.sleep(50)
            assertEquals("provider permit returned", 8, StreamServer.providerBudget.availablePermits())
        } finally {
            server.stop(); writers.forEach { runCatching { it.close() } }; client.join(5000)
        }
    }

    /** M1/Tab: the stop drain is bounded even with a provider that never
     *  returns - the drain thread itself never runs a close, so it must reach
     *  its end (cache sweep + token release) within the drain budget instead
     *  of parking one drain thread per stopped session. Pipe fakes cannot
     *  emulate a physically blocked close (declared); this pins the threading
     *  structure: stop returns promptly AND the drain terminates. */
    @Test fun stopDrain_boundedWithNeverReturningProvider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
            android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]) }[0]
        })
        server.providerDeadlineMs = 60_000 // no abandonment: the provider is "never" heard from
        server.drainBudgetMs = 2_000
        server.start()
        val url = server.addMovie(Uri.parse("content://synthetic-pipe/drain"), "drain.mkv")
        val client = kotlin.concurrent.thread { runCatching { get(url) } }
        try {
            val opened = android.os.SystemClock.elapsedRealtime() + 10_000
            while (writers.size < 2 && android.os.SystemClock.elapsedRealtime() < opened) Thread.sleep(10)
            assertTrue("copy pipe opened", writers.size >= 2)
            val t0 = android.os.SystemClock.elapsedRealtime()
            server.stop()
            assertTrue("stop returns promptly", android.os.SystemClock.elapsedRealtime() - t0 < 2000)
            assertTrue("drain thread terminates instead of parking",
                server.drainFinished.await(10, java.util.concurrent.TimeUnit.SECONDS))
            client.join(5000)
        } finally {
            writers.forEach { runCatching { it.close() } }; client.join(5000)
        }
    }

    /** M1 lifecycle across restart: after a stop with a stalled provider, a NEW
     *  server session serves normally. Honest limit: a pipe provider cannot stay
     *  truly parked (its close succeeds between poll cycles), so "old workers
     *  still blocked" across the restart is declared, not emulated - what this
     *  proves is that teardown plus a fresh session leave a working server. */
    @Test fun stopRestartAfterStall_newSessionServes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val writers = java.util.concurrent.CopyOnWriteArrayList<android.os.ParcelFileDescriptor>()
        server.stop()
        server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0, openDescriptor = { _, _ ->
            android.os.ParcelFileDescriptor.createPipe().also { writers.add(it[1]) }[0]
        })
        server.providerDeadlineMs = 1000
        server.start()
        val stallUrl = server.addMovie(Uri.parse("content://synthetic-pipe/restart"), "restart.mkv")
        val stalled = kotlin.concurrent.thread { runCatching { get(stallUrl) } }
        try {
            val until = android.os.SystemClock.elapsedRealtime() + 15_000
            while (server.openStreams.get() > 0 && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            assertEquals(0, server.openStreams.get())
            server.stop()
            stalled.join(5000)
            // Fresh session on the same port family: normal serving works.
            server = StreamServer(context, InetAddress.getByName("127.0.0.1"), 0)
            server.start()
            val fresh = server.addMovie(Uri.fromFile(file), "fresh")
            val (code, _, body) = get(fresh)
            assertEquals(200, code)
            assertArrayEquals(bytes, body)
        } finally {
            server.stop(); writers.forEach { runCatching { it.close() } }; stalled.join(5000)
        }
    }
}
