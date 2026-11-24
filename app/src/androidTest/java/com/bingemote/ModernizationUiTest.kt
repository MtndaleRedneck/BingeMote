package com.bingemote

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ModernizationUiTest {
    @get:Rule val failureDump = FailureDumpRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    @Test fun pcReachabilityDoesNotDependOnVlcBeingOpen() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val remote = RemoteState(ctx, Store(ctx), scope)
            for (vlcDown in listOf(false, true)) for (helperDown in listOf(false, true)) {
                remote.debugForceStatus(unreachable = vlcDown, helperDown = helperDown)
                assertEquals("VLC down=$vlcDown helper down=$helperDown", !(vlcDown && helperDown), remote.pcReachable)
            }
        } finally { scope.cancel() }
    }

    @Test fun helperLossClearsCachedVlcClosedDuringSkippedVlcPoll() = runBlocking {
        device.pressHome()
        ctx.stopService(Intent(ctx, MediaControlsService::class.java))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val port = HapticTickTest.deadPort()
            HapticTickTest.seedPrefs(ctx, false, port)
            val store = Store(ctx)
            store.helperToken = "modernization-fixture"
            store.helperPort = port
            val remote = RemoteState(ctx, store, scope)
            remote.pollOnce()
            remote.vlcClosed = true
            remote.pollOnce()
            assertTrue(remote.helperDown)
            assertFalse("stale VLC-closed state must not mask full PC loss", remote.vlcClosed)
        } finally { scope.cancel() }
    }

    private fun launch(screen: String, status: String) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screen", screen).putExtra("statuscase", status))
    }

    @Test fun moviesDisconnectedKeepsHeaderShowsBannerWithoutRetry() {
        Store(ctx).moviesPath = ""
        launch("movies", "cur-g")
        assertTrue(device.wait(Until.hasObject(By.text("Movies")), 30000))
        assertTrue(device.wait(Until.hasObject(By.text("Trying to reconnect")), 10000))
        assertTrue(device.hasObject(By.text("Both devices need to be on the same network")))
        assertFalse(device.hasObject(By.text("Try again")))
        assertFalse(device.hasObject(By.text("No videos in this folder.")))
        assertTrue(device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-modern-movies-offline.png")))
    }

    @Test fun settingsReentersAtTopAfterScrolling() {
        launch("main", "cur-ok")
        device.wait(Until.findObject(By.desc("Settings")), 30000).click()
        assertTrue(device.wait(Until.hasObject(By.text("Your PC")), 10000))
        repeat(3) { device.swipe(device.displayWidth/2, device.displayHeight*4/5, device.displayWidth/2, device.displayHeight/4, 25) }
        assertFalse("test must really scroll away from the top", device.hasObject(By.text("Your PC")))
        device.pressBack()
        device.wait(Until.findObject(By.desc("Settings")), 10000).click()
        assertTrue("Settings must reopen at the top", device.wait(Until.hasObject(By.text("Your PC")), 10000))
        assertTrue(device.takeScreenshot(File(ctx.getExternalFilesDir(null), "bm-modern-settings-reentry.png")))
    }
}
