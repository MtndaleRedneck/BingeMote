package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DisconnectedPlaybackTest {
    @Test fun appDisconnectedTapKeepsStateAndSendsNothing() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val stub = HapticTickTest.StatusStub(); stub.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            val remote = RemoteState(ctx, Store(ctx), scope)
            remote.debugSeedStatus()
            remote.debugForceStatus(unreachable = true, helperDown = true)
            val before = remote.status
            remote.togglePause()
            delay(400)
            assertEquals(before, remote.status)
            assertTrue(stub.commands.isEmpty())
        } finally { scope.cancel(); stub.stop() }
    }

    @Test fun appLostConnectionDoesNotFlipCachedPausedState() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val stub = HapticTickTest.StatusStub(); stub.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            HapticTickTest.seedPrefs(ctx, false, stub.port)
            val remote = RemoteState(ctx, Store(ctx), scope)
            remote.pollOnce()
            assertEquals("paused", remote.status?.state)
            stub.stop()
            remote.pollOnce()
            val before = remote.status
            remote.togglePause()
            delay(400)
            assertEquals(before, remote.status)
            assertTrue(stub.commands.isEmpty())
        } finally { scope.cancel(); stub.stop() }
    }
}
