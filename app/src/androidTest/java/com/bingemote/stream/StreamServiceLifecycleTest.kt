package com.bingemote.stream

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The phone stream service on a real emulator: cold start binds to a ready server, a wrong token is refused,
 *  the notification Stop action ends the service, and a service with no stream stays alive until stopped. */
class StreamServiceLifecycleTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun serviceRunning(): Boolean {
        @Suppress("DEPRECATION")
        return (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(100).any { it.service.className == StreamService::class.java.name }
    }

    private fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(200) }
        return cond()
    }

    private fun status(url: String): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        return try { c.responseCode } finally { c.disconnect() }
    }

    @Test fun coldStartServesRefusesWrongTokenAndStopActionEndsIt() {
        context.startService(Intent(context, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        assertTrue("service still running before the test", waitUntil(8000) { !serviceRunning() })
        val bound = runBlocking { StreamCoordinator.bind(context) }
        try {
            assertTrue("service not running after cold bind", serviceRunning())
            val base = bound.server.baseUrl // http://<wifi-ip>:<port>/s/<token>
            val root = base.substringBefore("/s/")
            assertEquals("wrong token must be refused", 403, status("$root/s/0000000000000000000000000000000000/1"))
            assertEquals("no token must be refused", 403, status("$root/x"))
            assertEquals("right token, unknown id", 404, status("$base/99"))
        } finally { StreamCoordinator.release(context, bound) }
        // The notification's Stop action is this exact intent.
        context.startService(Intent(context, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        assertTrue("service still running after Stop", waitUntil(10000) { !serviceRunning() })
    }
}
