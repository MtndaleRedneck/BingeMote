package com.bingemote

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private fun awaitServiceDestroyed(ctx: Context) {
    val done = java.util.concurrent.CountDownLatch(1)
    MediaControlsService.serviceDestroyed = { done.countDown() }
    ctx.stopService(Intent(ctx, MediaControlsService::class.java))
    if (MediaControlsService.testServiceAlive)
        assertTrue("prior service destruction not acknowledged", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
    MediaControlsService.serviceDestroyed = null
}

/**
 * Ezzy 2026-11-22 "Add haptic": a bar button press must tick, and nothing else may.
 * The real Vibrator is replaced by a recorder through the MediaControlsService.performTick
 * seam - what the phone physically feels (VibratorManager + EFFECT_CLICK) cannot be proven
 * on an emulator. What IS proven here: (1) an action intent reaches tick, (2) the app's
 * own "Vibrate on press" setting suppresses it, (3) the system haptic switch suppresses
 * it, (4) the 2 s status poll never ticks.
 */
class HapticTickTest {
    private val hits = AtomicInteger(0)
    private lateinit var device: UiDevice
    private lateinit var originalTick: (Context) -> Unit
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        awaitServiceDestroyed(ctx)
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("pm grant com.bingemote android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("settings put system haptic_feedback_enabled 1")
        originalTick = MediaControlsService.performTick
        MediaControlsService.performTick = { hits.incrementAndGet() }
        seedPrefs(ctx, haptics = true, port = deadPort())
    }

    @After fun tearDown() {
        device.executeShellCommand("settings put system haptic_feedback_enabled 1")
        ctx.stopService(Intent(ctx, MediaControlsService::class.java))
        MediaControlsService.performTick = originalTick
    }

    @Test fun actionFiresTick() {
        ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_VOL_UP))
        waitFor("tick on action", 5000) { hits.get() >= 1 }
        Thread.sleep(500)
        assertEquals("exactly one tick per press", 1, hits.get())
    }

    @Test fun appSettingOffSuppressesTick() {
        seedPrefs(ctx, haptics = false, port = deadPort())
        ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_VOL_UP))
        Thread.sleep(3000)
        assertEquals(0, hits.get())
    }

    @Test fun systemSwitchOffSuppressesTick() {
        device.executeShellCommand("settings put system haptic_feedback_enabled 0")
        try {
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_VOL_UP))
            Thread.sleep(3000)
            assertEquals(0, hits.get())
        } finally {
            device.executeShellCommand("settings put system haptic_feedback_enabled 1")
        }
    }

    @Test fun pollNeverTicks() {
        val stub = StatusStub(); stub.start()
        try {
            seedPrefs(ctx, haptics = true, port = stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java)) // no action: pure polling
            Thread.sleep(5500) // first poll immediately, then every 2 s
            assertTrue("expected the poll to have run, saw ${stub.polls.get()} polls", stub.polls.get() >= 2)
            assertEquals("poll path must never tick", 0, hits.get())
        } finally { stub.stop() }
    }

    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        org.junit.Assert.fail("timeout waiting for: $what")
    }

    companion object {
        private const val ACTION_VOL_UP = "com.bingemote.action.VOL_UP"
        internal fun deadPort(): Int = ServerSocket(0).use { it.localPort }

        internal fun seedPrefs(ctx: Context, haptics: Boolean, port: Int) {
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("setupDone", true)
                .putString("host", "127.0.0.1")
                .putInt("vlcPort", port)
                .putBoolean("lockScreenControls", true)
                .putBoolean("demoControls", false)
                .putBoolean("haptics", haptics)
                .putInt("skipMainSec", 5)
                .apply()
            Store(ctx).vlcPassword = "x"
            Store(ctx).helperToken = ""
        }
    }

    /**
     * Minimal VLC HTTP stub, faithful to the real player: a command CHANGES STATE ON
     * RECEIPT (Ezzy: "it pauses instantly") but its HTTP RESPONSE can be slow
     * (commandDelayMs); a status request snapshots state at ARRIVAL and can answer
     * late (statusDelayMs), which is how a stale in-flight poll is reproduced.
     */
    class StatusStub {
        val polls = AtomicInteger(0)
        val commands = CopyOnWriteArrayList<String>()
        @Volatile var state = "paused"
        @Volatile var commandDelayMs = 0L
        @Volatile var statusDelayMs = 0L
        /** Next N commands are NOT applied and answer 500 after their delay. */
        @Volatile var failCommands = 0
        @Volatile var failStatus = false
        @Volatile var beforeStatusResponse: (() -> Unit)? = null
        @Volatile var beforeCommandResponse: (() -> Unit)? = null
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort

        fun start() {
            thread {
                while (running.get()) {
                    try {
                        val sock = server.accept()
                        thread {
                            try {
                                val r = BufferedReader(InputStreamReader(sock.getInputStream()))
                                val line = r.readLine() ?: ""
                                while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
                                val snap: String
                                if (line.contains("command=")) {
                                    val fail = failCommands > 0
                                    if (fail) failCommands--
                                    if (!fail && line.contains("pl_pause")) state = if (state == "playing") "paused" else "playing"
                                    commands += line.substringAfter('?', "")
                                    if (commandDelayMs > 0) Thread.sleep(commandDelayMs)
                                    beforeCommandResponse?.invoke()
                                    if (fail) {
                                        val out = sock.getOutputStream()
                                        out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                        out.flush(); sock.close(); return@thread
                                    }
                                    snap = state
                                } else {
                                    if (failStatus) {
                                        val out = sock.getOutputStream()
                                        out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                        out.flush(); sock.close(); return@thread
                                    }
                                    snap = state // snapshot at arrival: the response may be stale by send time
                                    polls.incrementAndGet()
                                    if (statusDelayMs > 0) Thread.sleep(statusDelayMs)
                                }
                                if (!line.contains("command=")) beforeStatusResponse?.invoke()
                                val body = """{"time":30,"length":3600,"volume":128,"state":"$snap","information":{"category":{"meta":{"filename":"demo.mkv"}}}}"""
                                val out = sock.getOutputStream()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                                out.flush(); sock.close()
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        fun stop() { running.set(false); try { server.close() } catch (_: Exception) {} }
    }
}

/**
 * Ezzy 2026-11-22: "it pauses instantly, but takes a second or two to change symbols".
 * The MediaControlsService.postProbe seam records every real bar post (state + time).
 * These tests pin the play/pause glyph contract against a stub PC whose command answer
 * is provably slow (2.5 s):
 *  1. the glyph must flip well before VLC's answer (target state shown on receipt);
 *  2. a successful slow command must never revert the glyph;
 *  3. known disconnected/dead PCs must never flip the glyph;
 *  4. rapid double taps must end consistent with the PC's real state;
 *  5. a status poll in flight across the command (stale response) must not undo the flip.
 * On the pre-fix code, tests 1 and 3 fail by construction (the flip waited for the
 * answer; a dead PC gave no feedback at all) - that red run is the delayed-response
 * repro of Ezzy's report.
 */
class ImmediateFlipTest {
    // Same failure-evidence rule as the sheet tests: this class failed in run
    // 45310990361 with zero captures; never leave a failure undocumented.
    @get:Rule val failureDump = FailureDumpRule()

    private data class Post(val at: Long, val playing: Boolean)

    private val posts = CopyOnWriteArrayList<Post>()
    private lateinit var device: UiDevice
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        awaitServiceDestroyed(ctx)
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("pm grant com.bingemote android.permission.POST_NOTIFICATIONS")
        posts.clear()
        MediaControlsService.postProbe = { playing -> posts += Post(System.currentTimeMillis(), playing) }
    }

    @After fun tearDown() {
        awaitServiceDestroyed(ctx)
        MediaControlsService.beforePollCommit = null
        MediaControlsService.beforePlaybackDelivery = null
        MediaControlsService.afterPollAttempt = null
        MediaControlsService.beforePollFailure = null
        MediaControlsService.pendingPollSkipped = null
        MediaControlsService.playbackPostFailure = null
        MediaControlsService.throwPlaybackPost = false
        MediaControlsService.cancelBeforePlaybackStart = false
        MediaControlsService.playbackCompleted = null
        MediaControlsService.pollCommitted = null
        MediaControlsService.disablePeriodicPoll = false
        MediaControlsService.postProbe = null
    }

    @Test fun glyphFlipsBeforeVlcAnswersAndNeverRevertsOnSuccess() {
        val stub = HapticTickTest.StatusStub(); stub.state = "paused"; stub.commandDelayMs = 2500; stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, haptics = false, port = stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            Thread.sleep(3000) // initial post + first title poll settle; steady-state polls dedup
            posts.clear()
            val t0 = System.currentTimeMillis()
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            val end = t0 + 4000
            var flip: Post? = null
            while (System.currentTimeMillis() < end) {
                flip = posts.firstOrNull { it.playing }
                if (flip != null) break
                Thread.sleep(50)
            }
            assertTrue("no flip post within 4 s", flip != null)
            assertTrue("glyph flip took ${flip!!.at - t0} ms (must not wait for the 2.5 s VLC answer)",
                flip.at - t0 < 1000)
            Thread.sleep(3000) // the slow command lands at ~2.5 s; success must NOT revert
            assertTrue("successful command must not revert the glyph", posts.none { !it.playing })
        } finally { stub.stop() }
    }

    @Test fun disconnectedPlayIsTappableNoOp() {
        HapticTickTest.seedPrefs(ctx, haptics = false, port = HapticTickTest.deadPort())
        ctx.startService(Intent(ctx, MediaControlsService::class.java))
        Thread.sleep(3000)
        posts.clear()
        ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
        Thread.sleep(2500)
        assertTrue("disconnected Play must never post Pause: $posts", posts.none { it.playing })
    }

    @Test fun knownLostConnectionDoesNotFlip() {
        val stub = HapticTickTest.StatusStub(); stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, haptics = false, port = stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            Thread.sleep(3000)
            stub.stop()
            Thread.sleep(3000) // a failed poll invalidates the last good connection
            posts.clear()
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            Thread.sleep(1500)
            assertTrue("lost connection must not show playback: $posts", posts.none { it.playing })
        } finally { stub.stop() }
    }

    @Test fun rapidDoubleTapEndsConsistentWithPc() {
        val stub = HapticTickTest.StatusStub(); stub.state = "paused"; stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, haptics = false, port = stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            Thread.sleep(3000)
            posts.clear()
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            Thread.sleep(300) // rapid second tap; the first fast command has landed by now
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            Thread.sleep(4000) // commands + a reconciliation poll
            assertEquals("PC must get both toggles", 2, stub.commands.count { it.contains("pl_pause") })
            assertEquals("two toggles return the PC to paused", "paused", stub.state)
            assertTrue("final glyph must match the PC", posts.isNotEmpty() && !posts.last().playing)
        } finally { stub.stop() }
    }

    @Test fun staleInflightPollDoesNotUndoFlip() {
        val stub = HapticTickTest.StatusStub(); stub.state = "paused"; stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, haptics = false, port = stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            val connectedBy = System.currentTimeMillis() + 8000
            while (posts.count { !it.playing } < 2 && System.currentTimeMillis() < connectedBy) Thread.sleep(50)
            assertTrue("initial successful status not posted", posts.count { !it.playing } >= 2)
            stub.statusDelayMs = 2000
            Thread.sleep(1500) // next poll is in flight with snapshot paused
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            Thread.sleep(3500) // stale poll answers + a fresh poll reconciles
            val flips = posts.dropWhile { !it.playing }
            assertTrue("no flip observed", flips.isNotEmpty())
            assertTrue("stale in-flight poll undid the flip: $posts", flips.none { !it.playing })
        } finally { stub.stop() }
    }

    @Test fun pollPausedAfterEpochCheckCannotOverwriteNewerPlay() {
        val stub = HapticTickTest.StatusStub(); stub.state = "paused"; stub.start()
        val reached = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        try {
            HapticTickTest.seedPrefs(ctx, haptics = false, port = stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            val readyBy = System.currentTimeMillis() + 8000
            while (posts.count { !it.playing } < 2 && System.currentTimeMillis() < readyBy) Thread.sleep(30)
            assertTrue("initial connection missing", posts.count { !it.playing } >= 2)
            MediaControlsService.beforePollCommit = {
                MediaControlsService.observeHeldPoll?.invoke { completed.countDown() }
                reached.countDown()
                assertTrue("barrier release timeout", release.await(8, java.util.concurrent.TimeUnit.SECONDS))
            }
            assertTrue("poll never reached pre-commit barrier", reached.await(6, java.util.concurrent.TimeUnit.SECONDS))
            posts.clear()
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            val flippedBy = System.currentTimeMillis() + 2000
            while (posts.none { it.playing } && System.currentTimeMillis() < flippedBy) Thread.sleep(20)
            assertTrue("connected Play did not respond while old poll paused", posts.any { it.playing })
            MediaControlsService.beforePollCommit = null
            release.countDown()
            assertTrue("old poll never completed", completed.await(4, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("old poll overwrote newer Play: $posts", posts.none { !it.playing })
        } finally {
            MediaControlsService.beforePollCommit = null
            MediaControlsService.afterPollAttempt = null
            release.countDown()
            stub.stop()
        }
    }

    @Test fun pollCannotRevertPlayBeforeCommandDelivery() {
        val stub = HapticTickTest.StatusStub(); stub.state = "paused"; stub.start()
        val reached = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            HapticTickTest.seedPrefs(ctx, haptics = false, port = stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            val readyBy = System.currentTimeMillis() + 8000
            while (posts.count { !it.playing } < 2 && System.currentTimeMillis() < readyBy) Thread.sleep(30)
            assertTrue("initial connection missing", posts.count { !it.playing } >= 2)
            MediaControlsService.beforePlaybackDelivery = {
                reached.countDown()
                assertTrue("delivery barrier timed out", release.await(8, java.util.concurrent.TimeUnit.SECONDS))
            }
            posts.clear()
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            assertTrue(reached.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val skipped = java.util.concurrent.CountDownLatch(1)
            MediaControlsService.pendingPollSkipped = { skipped.countDown() }
            assertTrue("no poll attempted while delivery pending", skipped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            MediaControlsService.pendingPollSkipped = null
            assertTrue("same-epoch poll reverted optimistic Play: $posts", posts.isNotEmpty() && posts.all { it.playing })
            assertEquals("paused", stub.state)
            MediaControlsService.beforePlaybackDelivery = null
            release.countDown()
            Thread.sleep(1200)
            assertEquals("playing", stub.state)
            assertTrue(posts.all { it.playing })
        } finally {
            MediaControlsService.beforePlaybackDelivery = null
            release.countDown()
            stub.stop()
        }
    }

    @Test fun staleFailedPollCannotInvalidateNewerAction() {
        val stub = HapticTickTest.StatusStub(); stub.start()
        val reached = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            val readyBy = System.currentTimeMillis() + 8000
            while (posts.count { !it.playing } < 2 && System.currentTimeMillis() < readyBy) Thread.sleep(30)
            assertTrue(posts.count { !it.playing } >= 2)
            MediaControlsService.beforePollFailure = {
                MediaControlsService.observeHeldPoll?.invoke { done.countDown() }
                reached.countDown()
                assertTrue(release.await(8, java.util.concurrent.TimeUnit.SECONDS))
            }
            stub.failStatus = true
            MediaControlsService.requestTestPoll?.invoke()
            assertTrue(reached.await(2, java.util.concurrent.TimeUnit.SECONDS))
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            val commandBy = System.currentTimeMillis() + 1500
            while (stub.commands.isEmpty() && System.currentTimeMillis() < commandBy) Thread.sleep(20)
            assertEquals(1, stub.commands.size)
            MediaControlsService.beforePollFailure = null
            stub.failStatus = false
            release.countDown()
            assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS))
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            val bothBy = System.currentTimeMillis() + 1500
            while (stub.commands.size < 2 && System.currentTimeMillis() < bothBy) Thread.sleep(20)
            assertEquals("stale failure disabled newer connected tap", 2, stub.commands.size)
        } finally {
            MediaControlsService.beforePollFailure = null
            MediaControlsService.afterPollAttempt = null
            release.countDown(); stub.stop()
        }
    }

    @Test fun reversedDeliveryDoubleTapPreservesParity() {
        val stub = HapticTickTest.StatusStub(); stub.state = "paused"; stub.start()
        val firstHeld = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val deliveries = java.util.concurrent.atomic.AtomicInteger()
        try {
            HapticTickTest.seedPrefs(ctx, haptics = false, port = stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            val readyBy = System.currentTimeMillis() + 8000
            while (posts.count { !it.playing } < 2 && System.currentTimeMillis() < readyBy) Thread.sleep(30)
            assertTrue(posts.count { !it.playing } >= 2)
            MediaControlsService.beforePlaybackDelivery = {
                if (deliveries.incrementAndGet() == 1) {
                    firstHeld.countDown()
                    assertTrue(releaseFirst.await(8, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            posts.clear()
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            assertTrue(firstHeld.await(2, java.util.concurrent.TimeUnit.SECONDS))
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            val secondBy = System.currentTimeMillis() + 3000
            while (stub.commands.isEmpty() && System.currentTimeMillis() < secondBy) Thread.sleep(20)
            assertEquals("second command must pass held first", 1, stub.commands.size)
            assertEquals("playing", stub.state)
            val skipped = java.util.concurrent.CountDownLatch(1)
            MediaControlsService.pendingPollSkipped = { skipped.countDown() }
            assertTrue("no poll attempted while first delivery held", skipped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            MediaControlsService.pendingPollSkipped = null
            assertTrue("pending first delivery lost double-tap target", posts.isNotEmpty() && !posts.last().playing)
            MediaControlsService.beforePlaybackDelivery = null
            releaseFirst.countDown()
            val bothBy = System.currentTimeMillis() + 3000
            while (stub.commands.size < 2 && System.currentTimeMillis() < bothBy) Thread.sleep(20)
            assertEquals(2, stub.commands.size)
            assertEquals("paused", stub.state)
            Thread.sleep(1000)
            assertTrue(!posts.last().playing)
        } finally {
            MediaControlsService.beforePlaybackDelivery = null
            releaseFirst.countDown()
            stub.stop()
        }
    }

    @Test fun throwingOptimisticPostReleasesPendingAndPollingResumes() {
        checkPreStartCleanup(throwPost = true)
    }

    @Test fun cancelledBeforeCoroutineEntryReleasesPendingAndPollingResumes() {
        checkPreStartCleanup(throwPost = false)
    }

    private fun checkPreStartCleanup(throwPost: Boolean) {
        val stub = HapticTickTest.StatusStub(); stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            val readyBy = System.currentTimeMillis() + 8000
            while (posts.count { !it.playing } < 2 && System.currentTimeMillis() < readyBy) Thread.sleep(30)
            assertTrue(posts.count { !it.playing } >= 2)
            MediaControlsService.throwPlaybackPost = throwPost
            MediaControlsService.cancelBeforePlaybackStart = !throwPost
            val postedFailure = java.util.concurrent.CountDownLatch(1)
            MediaControlsService.playbackPostFailure = { postedFailure.countDown() }
            val targetDone = java.util.concurrent.CountDownLatch(1)
            val counts = java.util.concurrent.CopyOnWriteArrayList<Int>()
            MediaControlsService.playbackCompleted = { counts += it; targetDone.countDown() }
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            if (throwPost) assertTrue(postedFailure.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("target action completion missing", targetDone.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("pending accounting not zero/nonnegative: $counts", counts.isNotEmpty() && counts.all { it == 0 })
            val committed = java.util.concurrent.CountDownLatch(1)
            MediaControlsService.pollCommitted = { committed.countDown() }
            MediaControlsService.requestTestPoll?.invoke()
            assertTrue("fresh post-completion poll did not commit", committed.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("cancelled/failed prelaunch sent command", stub.commands.isEmpty())
            MediaControlsService.throwPlaybackPost = false
            MediaControlsService.cancelBeforePlaybackStart = false
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            val commandBy = System.currentTimeMillis() + 2000
            while (stub.commands.isEmpty() && System.currentTimeMillis() < commandBy) Thread.sleep(30)
            assertEquals("subsequent connected Play must still work", 1, stub.commands.size)
        } finally {
            MediaControlsService.throwPlaybackPost = false
            MediaControlsService.cancelBeforePlaybackStart = false
            MediaControlsService.playbackPostFailure = null
            MediaControlsService.playbackCompleted = null
            MediaControlsService.pollCommitted = null
            stub.stop()
        }
    }

    @Test fun heldStatusReturnAfterDestructionCannotPost() {
        val stub = HapticTickTest.StatusStub(); stub.start()
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        try {
            MediaControlsService.disablePeriodicPoll = true
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            val startedBy = System.currentTimeMillis() + 3000
            while (!MediaControlsService.testServiceAlive && System.currentTimeMillis() < startedBy) Thread.sleep(20)
            assertTrue(MediaControlsService.testServiceAlive)
            val connected = java.util.concurrent.CountDownLatch(1)
            MediaControlsService.requestObservedPoll?.invoke { connected.countDown() }
            assertTrue(connected.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(posts.count { !it.playing } >= 2)
            stub.beforeStatusResponse = {
                held.countDown()
                assertTrue(release.await(8, java.util.concurrent.TimeUnit.SECONDS))
            }
            MediaControlsService.requestObservedPoll?.invoke { returned.countDown() }
            assertTrue(held.await(2, java.util.concurrent.TimeUnit.SECONDS))
            awaitServiceDestroyed(ctx)
            posts.clear()
            assertEquals("target returned before held response release", 1L, returned.count)
            release.countDown()
            assertTrue("held status never returned", returned.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("destroyed service posted status", posts.isEmpty())
        } finally {
            MediaControlsService.beforePollCommit = null
            MediaControlsService.afterPollAttempt = null
            MediaControlsService.disablePeriodicPoll = false
            release.countDown(); stub.stop()
        }
    }

    @Test fun heldPlaybackFailureAfterDestructionCannotRevert() {
        val stub = HapticTickTest.StatusStub(); stub.failCommands = 1; stub.start()
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            val readyBy = System.currentTimeMillis() + 8000
            while (posts.count { !it.playing } < 2 && System.currentTimeMillis() < readyBy) Thread.sleep(30)
            assertTrue(posts.count { !it.playing } >= 2)
            stub.beforeCommandResponse = {
                held.countDown()
                assertTrue(release.await(8, java.util.concurrent.TimeUnit.SECONDS))
            }
            MediaControlsService.playbackCompleted = { assertEquals("held target pending count", 0, it); completed.countDown() }
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            assertTrue(held.await(2, java.util.concurrent.TimeUnit.SECONDS))
            awaitServiceDestroyed(ctx)
            posts.clear()
            assertEquals("playback completed before held response release", 1L, completed.count)
            release.countDown()
            assertTrue(completed.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("destroyed service reverted glyph", posts.isEmpty())
        } finally {
            MediaControlsService.beforePlaybackDelivery = null
            MediaControlsService.playbackCompleted = null
            release.countDown(); stub.stop()
        }
    }

    @Test fun lateFailureDoesNotStompNewerPress() {
        val stub = HapticTickTest.StatusStub(); stub.state = "paused"
        stub.commandDelayMs = 1500; stub.failCommands = 1 // press 1's command fails LATE
        stub.start()
        try {
            HapticTickTest.seedPrefs(ctx, haptics = false, port = stub.port)
            ctx.startService(Intent(ctx, MediaControlsService::class.java))
            Thread.sleep(3000)
            posts.clear()
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            Thread.sleep(400) // press 2 supersedes while press 1's command is still in flight
            ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction(ACTION_PLAY_PAUSE))
            Thread.sleep(5000) // press 1 fails at ~1.5 s; press 2 succeeds ~1.9 s; polls reconcile
            assertEquals("PC must see both commands", 2, stub.commands.count { it.contains("pl_pause") })
            assertTrue("flip posts for both presses", posts.count { it.playing } >= 1)
            // The late failure must not post a stale revert: exactly ONE pause-state
            // post (press 2's flip). An unguarded revert would add a second one.
            assertEquals("late failure stomped the newer press: $posts", 1, posts.count { !it.playing })
            // press 1 was never applied, press 2 toggled: PC ends PLAYING, and the
            // reconciliation poll must bring the glyph to that confirmed state.
            assertEquals("playing", stub.state)
            assertTrue("poll must reconcile glyph to the PC's real state: $posts", posts.last().playing)
        } finally { stub.stop() }
    }

    companion object {
        private const val ACTION_PLAY_PAUSE = "com.bingemote.action.PLAY_PAUSE"
    }
}
