package com.bingemote

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the actual RemoteViews PendingIntents, not manufactured action strings. */
class NotificationTapMatrixTest {
    @get:Rule val failureDump = FailureDumpRule()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val hits = AtomicInteger()
    private lateinit var original: (Context) -> Unit
    private val ids = listOf(R.id.btn_rewind, R.id.btn_vol_down, R.id.btn_play_pause, R.id.btn_vol_up, R.id.btn_forward)

    private fun stop() {
        val latch = CountDownLatch(1)
        MediaControlsService.serviceDestroyed = { latch.countDown() }
        ctx.stopService(Intent(ctx, MediaControlsService::class.java))
        if (MediaControlsService.testServiceAlive) assertTrue(latch.await(5, TimeUnit.SECONDS))
        MediaControlsService.serviceDestroyed = null
    }
    @Before fun before() {
        stop()
        device.executeShellCommand("pm grant com.bingemote android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("settings put system haptic_feedback_enabled 1")
        original = MediaControlsService.performTick
        MediaControlsService.performTick = { hits.incrementAndGet() }
        MediaControlsService.disablePeriodicPoll = true
    }
    @After fun after() {
        stop()
        MediaControlsService.performTick = original
        MediaControlsService.disablePeriodicPoll = false
        device.executeShellCommand("settings put system haptic_feedback_enabled 1")
    }
    private fun await(what: String, predicate: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        val end = t0 + 5000
        while (System.currentTimeMillis() < end) { if (predicate()) return; Thread.sleep(30) }
        // Pre-finally evidence at the failure point; the rule dump runs post-teardown.
        PreconditionDiag.capture("tapmatrix-await", what, t0,
            "alive=${MediaControlsService.testServiceAlive} observerSet=${MediaControlsService.requestObservedPoll != null}")
        fail("Timed out: $what")
    }
    private fun poll(tag: String = "", stub: HapticTickTest.StatusStub? = null) {
        val latch = CountDownLatch(1)
        val stubBefore = stub?.polls?.get()
        MediaControlsService.requestObservedPoll!! { latch.countDown() }
        val t0 = System.currentTimeMillis()
        val observed = latch.await(5, TimeUnit.SECONDS)
        if (!observed) PreconditionDiag.capture("tapmatrix-poll", "observed poll $tag", t0,
            "iteration=$tag alive=${MediaControlsService.testServiceAlive} observerSet=${MediaControlsService.requestObservedPoll != null} testPollSet=${MediaControlsService.requestTestPoll != null} stubPollsBefore=$stubBefore stubPollsAfter=${stub?.polls?.get()} (unchanged=pre-HTTP path; increased=completion path - a diagnosis lead, not exoneration)")
        assertTrue("observed poll", observed)
    }
    private fun currentRow(): View {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        await("active notification posted") { nm.activeNotifications.any { it.packageName == ctx.packageName } }
        val n = nm.activeNotifications.single { it.packageName == ctx.packageName }.notification
        lateinit var row: View
        inst.runOnMainSync { row = n.contentView.apply(ctx, FrameLayout(ctx)) }
        return row
    }
    private fun matrix(state: String, appOn: Boolean = true, systemOn: Boolean = true) {
        for (id in ids) {
            stop(); hits.set(0)
            val stub = HapticTickTest.StatusStub(); stub.start()
            try {
                HapticTickTest.seedPrefs(ctx, appOn, stub.port)
                device.executeShellCommand("settings put system haptic_feedback_enabled ${if (systemOn) 1 else 0}")
                ctx.startService(Intent(ctx, MediaControlsService::class.java))
                await("service ready $state/$id") { MediaControlsService.requestObservedPoll != null }
                if (state != "startup") poll("$state/$id", stub)
                if (state == "lost") { stub.failStatus = true; poll("$state/$id", stub) }
                val row = currentRow()
                val beforeCommands = stub.commands.size
                val posts = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
                MediaControlsService.postProbe = { posts.add(it) }
                inst.runOnMainSync { assertTrue(row.findViewById<View>(id).performClick()) }
                if (appOn && systemOn) await("one feedback for $state/$id") { hits.get() == 1 }
                Thread.sleep(500)
                assertEquals("feedback $state/$id", if (appOn && systemOn) 1 else 0, hits.get())
                if (id == R.id.btn_play_pause && state != "connected") {
                    assertEquals("disconnected Play sent a command", beforeCommands, stub.commands.size)
                    assertTrue("disconnected Play changed glyph", posts.isEmpty())
                }
                if (id == R.id.btn_play_pause && state == "connected") {
                    await("connected Play command $state/$id") { stub.commands.drop(beforeCommands).any { it.contains("pl_pause") } }
                    assertTrue("connected Play glyph feedback missing", posts.isNotEmpty())
                }
            } finally { MediaControlsService.postProbe = null; stop(); stub.stop() }
        }
    }
    @Test fun startupAllFiveAcknowledgeOnce() = matrix("startup")
    @Test fun connectedAllFiveAcknowledgeOnce() = matrix("connected")
    @Test fun lostConnectionAllFiveAcknowledgeOnce() = matrix("lost")
    @Test fun appOffSuppressesAllFive() = matrix("connected", appOn = false)
    @Test fun systemOffSuppressesAllFive() = matrix("connected", systemOn = false)
    @Test fun unknownAndStartNeverAcknowledge() {
        HapticTickTest.seedPrefs(ctx, true, HapticTickTest.deadPort())
        ctx.startService(Intent(ctx, MediaControlsService::class.java))
        await("service") { MediaControlsService.testServiceAlive }
        ctx.startService(Intent(ctx, MediaControlsService::class.java).setAction("unknown"))
        Thread.sleep(500)
        assertEquals(0, hits.get())
    }
}
