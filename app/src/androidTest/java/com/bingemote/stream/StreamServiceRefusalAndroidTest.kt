package com.bingemote.stream

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A refused foreground start (debug-build seam: StreamService.testForegroundRefusal) must fail the bind fast with a
 * plain error, build no server for that instance, and leave nothing behind for the NEXT, normal start in the same process
 * (the refusal flag lives on the service instance, there is no static state to reset).
 * UNVERIFIED by design until this runs: whether the platform tolerates stopSelf() before startForeground() after
 * startForegroundService() without a did-not-start-in-time crash. A crash here is a real finding, not noise.
 */
@RunWith(AndroidJUnit4::class)
class StreamServiceRefusalAndroidTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun reset() { StreamService.testForegroundRefusal = false }

    @Test fun refusedForegroundStart_bindFailsFast_thenNormalStartWorks() {
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

        // The refused instance stops itself (stopSelf). Wait until it is really gone before the normal start, so the
        // second bind cannot attach to the dying instance (no product change: this only orders the test).
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val until = android.os.SystemClock.elapsedRealtime() + 10_000
        @Suppress("DEPRECATION")
        while (am.getRunningServices(50).any { it.service.className == StreamService::class.java.name } && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        @Suppress("DEPRECATION")
        assertTrue("the refused service instance is gone", am.getRunningServices(50).none { it.service.className == StreamService::class.java.name })

        StreamService.testForegroundRefusal = false
        val bound = runBlocking { StreamCoordinator.bind(ctx) }
        try {
            assertTrue("a normal start in the same process serves", bound.server.baseUrl.startsWith("http://"))
        } finally {
            StreamCoordinator.release(ctx, bound)
            ctx.stopService(android.content.Intent(ctx, StreamService::class.java))
        }
    }
}
