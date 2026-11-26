package com.bingemote.stream

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A refused foreground start (debug-build seam: StreamService.testForegroundRefusal) must (1) fail the bind fast with a
 * plain error, (2) build NO server, wake/Wi-Fi lock or watchdog for that instance (proved by the debug lifecycle events,
 * which the instance writes only when it reaches those steps), and (3) leave nothing behind for the NEXT, normal start in
 * the same process. The old instance's onDestroy ("destroyed" event) is awaited before the normal bind, so the second bind
 * cannot attach to the dying instance.
 * UNVERIFIED until this runs: whether the platform tolerates stopSelf() before startForeground() after
 * startForegroundService() without a did-not-start-in-time crash. A crash here is a real finding, not noise.
 * Honest limit: if the platform never delivers onStartCommand to the refused instance, "start-guard-refused" cannot
 * appear and the onStartCommand guard line is then not exercised; the test says so instead of asserting it.
 */
@RunWith(AndroidJUnit4::class)
class StreamServiceRefusalAndroidTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun reset() { StreamService.testForegroundRefusal = false }

    private fun awaitEvent(name: String, ms: Long = 10_000): Boolean {
        val until = android.os.SystemClock.elapsedRealtime() + ms
        while (!StreamService.testEvents.contains(name) && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
        return StreamService.testEvents.contains(name)
    }

    @Test fun refusedForegroundStart_buildsNothing_bindFailsFast_thenNormalStartWorks() {
        StreamService.testEvents.clear()
        StreamService.testForegroundRefusal = true
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            runBlocking { StreamCoordinator.bind(ctx) }
            fail("bind must fail when the foreground start is refused")
        } catch (e: IllegalStateException) {
            assertEquals("foreground start refused", e.message)
        }
        val took = android.os.SystemClock.elapsedRealtime() - t0
        assertTrue("fails fast (< 3 s), not after the 10 s server wait: $took ms", took < 3000)

        assertTrue("the refused instance recorded the refusal", awaitEvent("created-refused"))
        assertTrue("the refused instance is destroyed (onDestroy ran)", awaitEvent("destroyed"))
        val ev = StreamService.testEvents.toList()
        assertFalse("no server was built by the refused instance: $ev", ev.contains("server-built"))
        assertFalse("no wake/Wi-Fi lock was taken by the refused instance: $ev", ev.contains("locks-acquired"))
        assertFalse("no watchdog was started by the refused instance: $ev", ev.contains("watchdog-started"))
        if (ev.contains("start-command")) assertTrue("onStartCommand ran and took the refusal guard: $ev", ev.contains("start-guard-refused"))

        StreamService.testForegroundRefusal = false
        StreamService.testEvents.clear()
        val bound = runBlocking { StreamCoordinator.bind(ctx) }
        try {
            assertTrue("a normal start in the same process serves", bound.server.baseUrl.startsWith("http://"))
            assertTrue("the normal instance built its server", StreamService.testEvents.contains("server-built"))
        } finally {
            StreamCoordinator.release(ctx, bound)
            ctx.stopService(android.content.Intent(ctx, StreamService::class.java))
        }
    }
}
