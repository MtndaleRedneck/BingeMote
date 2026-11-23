package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class SubtitleClientTest {
    private class Stub : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        private val server = ServerSocket(0)
        val port = server.localPort
        @Volatile var oldPc = false
        @Volatile var responseBytes = 0
        @Volatile var delayMs = 0L
        @Volatile var running = true
        init { thread(isDaemon = true) {
            while (running) try {
                server.accept().use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: ""
                    val headers = mutableListOf<String>()
                    while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break; headers.add(h) }
                    val len = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val body = CharArray(len); var n = 0
                    while (n < len) { val k = reader.read(body, n, len-n); if (k < 0) break; n += k }
                    requests.add(line + "\n" + headers.joinToString("\n") + "\n" + String(body))
                    val json = if (line.contains("/subtitles/list")) """{"mediaId":"opaque-movie","files":[{"id":"opaque-file","name":"Movie.en.srt","trackIds":[]}]}""" else """{"ok":true}"""
                    val code = if (oldPc) "404 Not Found" else "200 OK"
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val bytes = if (responseBytes > 0) ByteArray(responseBytes) { 32 } else json.toByteArray()
                    sock.getOutputStream().write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()+bytes)
                }
            } catch (_: Exception) { }
        } }
        override fun close() { running = false; server.close() }
    }
    @Test fun listIsReadOnlyAndLoadSendsOpaqueIdsWithPairingToken() {
        Stub().use { stub ->
            val listing = HelperClient.subtitleFiles("127.0.0.1", stub.port, "fixture-token")
            assertEquals("Movie.en.srt", listing.files.single().name)
            assertEquals(1, stub.requests.size)
            assertTrue(stub.requests.single().startsWith("GET /subtitles/list"))
            assertTrue(stub.requests.single().contains("X-BingeMote-Token: fixture-token", true))
            assertTrue(HelperClient.loadSubtitle("127.0.0.1",stub.port,"fixture-token",listing.mediaId,listing.files.single().id))
            val request = stub.requests.last()
            assertTrue(request.startsWith("POST /subtitles/load"))
            assertTrue(request.contains("opaque-movie")); assertTrue(request.contains("opaque-file"))
            assertFalse(request.contains("Movie.en.srt"))
        }
    }
    @Test fun oldPcFailureKeepsExistingLoadedTracks() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Stub().use { stub ->
            stub.oldPc = true
            HapticTickTest.seedPrefs(ctx,false,stub.port)
            val store = Store(ctx);store.helperToken="fixture";store.helperPort=stub.port
            val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main)
            try {
                val remote = RemoteState(ctx,store,scope)
                withContext(Dispatchers.Main) { remote.debugSeedStatus();remote.refreshSubtitleFiles() }
                repeat(100) { if (remote.subtitleFileError != null) return@repeat; delay(30) }
                assertNotNull(remote.subtitleFileError)
                assertNull(remote.subtitleFolder)
                assertNotNull("loaded status must survive old PC",remote.status)
            } finally { scope.cancel() }
        }
    }
    @Test fun selectFallsBackOnlyForExplicit404() {
        Stub().use { stub ->
            stub.oldPc = true
            assertFalse(HelperClient.selectSubtitle("127.0.0.1",stub.port,"fixture",-1))
            stub.oldPc = false
            assertTrue(HelperClient.selectSubtitle("127.0.0.1",stub.port,"fixture",-1))
        }
    }
    @Test fun oversizedSubtitleResponseIsRejected() {
        Stub().use { stub ->
            stub.responseBytes = 524289
            try { HelperClient.subtitleFiles("127.0.0.1",stub.port,"fixture"); fail("oversize accepted") }
            catch (_: HelperClient.Unreachable) { }
        }
    }

    @Test fun delayedSelectAndErrorNeverMasqueradeAs404Fallback() {
        Stub().use { stub ->
            stub.delayMs = 2300
            val start=System.nanoTime()
            assertTrue(HelperClient.selectSubtitle("127.0.0.1",stub.port,"fixture",-1))
            assertTrue((System.nanoTime()-start)/1_000_000 >= 2000)
        }
        ServerSocket(0).use { absent ->
            val port=absent.localPort;absent.close()
            try { HelperClient.selectSubtitle("127.0.0.1",port,"fixture",-1);fail("unreachable accepted") }
            catch (_: Exception) { }
        }
    }

    @Test fun quietRefreshCoalescesSlowListingInsteadOfStarvingIt() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Stub().use { stub ->
            stub.delayMs=3000
            HapticTickTest.seedPrefs(ctx,false,stub.port)
            val store=Store(ctx);store.helperToken="fixture";store.helperPort=stub.port
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            try {
                val remote=RemoteState(ctx,store,scope)
                withContext(Dispatchers.Main){remote.refreshSubtitleFiles()}
                delay(2000)
                withContext(Dispatchers.Main){remote.refreshSubtitleFiles(quiet=true)}
                delay(1400)
                assertEquals("opaque-movie",remote.subtitleFolder?.mediaId)
                assertEquals(1,stub.requests.size)
                assertFalse(remote.subtitleFilesLoading)
            } finally {scope.cancel()}
        }
    }

    @Test fun pendingSubtitleChoicesCoalesceToLatestOff() = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        Stub().use { stub ->
            HapticTickTest.seedPrefs(ctx,false,stub.port)
            val store=Store(ctx);store.helperToken="fixture";store.helperPort=stub.port
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            try {
                val remote=RemoteState(ctx,store,scope)
                withContext(Dispatchers.Main){remote.refreshSubtitleFiles()}
                delay(300)
                stub.delayMs=2800
                withContext(Dispatchers.Main){remote.pickSubtitle(1)}
                delay(300)
                withContext(Dispatchers.Main){remote.pickSubtitle(2);remote.pickSubtitle(3);remote.pickSubtitle(-1)}
                delay(6500)
                val selects=stub.requests.filter{it.startsWith("POST /subtitles/select")}
                assertEquals(2,selects.size)
                assertTrue(selects.last().contains("\"trackId\":-1"))
                assertEquals(-1,remote.pickedSubtitle)
                assertFalse(remote.subtitleFilesLoading)
            } finally {scope.cancel()}
        }
    }

}
