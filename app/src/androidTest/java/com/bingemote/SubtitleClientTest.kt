package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class SubtitleClientTest {
    @get:Rule val failureDump = FailureDumpRule()

    @org.junit.Before fun isolateFromForegroundPolling() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
        ctx.stopService(android.content.Intent(ctx,MediaControlsService::class.java))
        Thread.sleep(300)
    }
    private suspend fun awaitCondition(timeoutMs:Long=20000, predicate:()->Boolean) {
        val end=System.currentTimeMillis()+timeoutMs
        while(System.currentTimeMillis()<end){if(predicate())return;delay(50)}
        assertTrue("condition not reached within $timeoutMs ms",predicate())
    }

    private class Stub : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        private val server = ServerSocket(0)
        val port = server.localPort
        @Volatile var oldPc = false
        @Volatile var errorCode = 0
        @Volatile var unknown = false
        @Volatile var failLoadOnly = false
        @Volatile var emptyMedia = false
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
                    val json = if (failLoadOnly && line.contains("/subtitles/load")) """{"unknown":true,"error":"uncertain"}""" else if (emptyMedia && line.contains("/subtitles/list")) """{"mediaId":"","files":[]}""" else if (errorCode > 0) """{"error":"fixture rejection","unknown":$unknown}""" else if (line.contains("/subtitles/list")) """{"mediaId":"opaque-movie","files":[{"id":"opaque-file","name":"Movie.en.srt","trackIds":[]}]}""" else if (line.contains("/subtitles/media")) """{"mediaId":"opaque-movie"}""" else """{"ok":true}"""
                    val code = if (failLoadOnly && line.contains("/subtitles/load")) "503 Fixture" else if (oldPc) "404 Not Found" else if (errorCode > 0) "$errorCode Fixture" else "200 OK"
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
    @Test fun oldPcFailureHidesFolderQuietlyAndKeepsExistingLoadedTracks() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Stub().use { stub ->
            stub.oldPc = true
            HapticTickTest.seedPrefs(ctx,false,stub.port)
            val store = Store(ctx);store.helperToken="fixture";store.helperPort=stub.port
            val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main)
            try {
                val remote = RemoteState(ctx,store,scope)
                withContext(Dispatchers.Main) { remote.debugSeedStatus();remote.refreshSubtitleFiles() }
                // The request must actually have happened before we assert the quiet outcome.
                repeat(100) { if (stub.requests.isNotEmpty()) return@repeat; delay(30) }
                assertTrue("subtitle list was never requested", stub.requests.isNotEmpty())
                awaitCondition(8000) { !remote.subtitleFilesLoading }
                // Owner's rule: an older PC app hides folder subtitles silently -
                // no error line, no Retry. This is the CONNECTED older-PC case: the
                // VLC link is fine, so its status and embedded tracks stay; only the
                // folder section hides. (Fully disconnected is a UI concern covered
                // by SubtitleSheetTest: the sheet then shows only Off, no stale tracks.)
                assertNull(remote.subtitleFileError)
                assertNull(remote.subtitleFolder)
                assertNotNull("loaded status must survive old PC",remote.status)
            } finally { scope.cancel() }
        }
    }
    @Test fun answeringPcFailureShowsListErrorAndClearsFolder() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Stub().use { stub ->
            stub.errorCode = 503
            HapticTickTest.seedPrefs(ctx,false,stub.port)
            val store = Store(ctx);store.helperToken="fixture";store.helperPort=stub.port
            val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main)
            try {
                val remote = RemoteState(ctx,store,scope)
                withContext(Dispatchers.Main) { remote.debugSeedStatus();remote.refreshSubtitleFiles() }
                repeat(100) { if (stub.requests.isNotEmpty()) return@repeat; delay(30) }
                assertTrue("subtitle list was never requested", stub.requests.isNotEmpty())
                awaitCondition(8000) { !remote.subtitleFilesLoading }
                // Owner-approved (2026-11-23): an ANSWERING helper whose subtitle
                // check fails keeps an honest message (the sheet renders it with
                // Retry); only gone/older/unpaired PCs stay quiet.
                assertEquals("Can't check subtitle files right now.", remote.subtitleFileError)
                assertNull(remote.subtitleFolder)
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
                awaitCondition(8000){remote.subtitleFolder!=null || remote.subtitleFileError!=null}
                assertEquals("listing error=${remote.subtitleFileError}; requests=${stub.requests}","opaque-movie",remote.subtitleFolder?.mediaId)
                assertEquals(1,stub.requests.size)
                assertFalse(remote.subtitleFilesLoading)
            } finally {scope.cancel()}
        }
    }

    @Test fun pendingSubtitleChoicesCoalesceToLatestOff() = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(ctx.filesDir,"subtitle-operations.json").delete()
        Stub().use { stub ->
            HapticTickTest.seedPrefs(ctx,false,stub.port)
            val store=Store(ctx);store.helperToken="fixture";store.helperPort=stub.port
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            try {
                val remote=RemoteState(ctx,store,scope)
                withContext(Dispatchers.Main){remote.refreshSubtitleFiles()}
                awaitCondition(){remote.subtitleFolder!=null}
                stub.delayMs=2800
                withContext(Dispatchers.Main){remote.pickSubtitle(1)}
                awaitCondition(){stub.requests.any{it.startsWith("POST /subtitles/select")}}
                withContext(Dispatchers.Main){remote.pickSubtitle(2);remote.pickSubtitle(3);remote.pickSubtitle(-1)}
                awaitCondition(){!remote.subtitleFilesLoading && remote.pickedSubtitle == -1}
                val selects=stub.requests.filter{it.startsWith("POST /subtitles/select")}
                assertEquals(2,selects.size)
                assertTrue(selects.last().contains("\"trackId\":-1"))
                assertEquals(-1,remote.pickedSubtitle)
                assertFalse(remote.subtitleFilesLoading)
            } finally {scope.cancel()}
        }
    }

    @Test fun typedSubtitleRejectionDistinguishesPostMutationUnknown() {
        Stub().use {stub->
            stub.errorCode=409
            try{HelperClient.loadSubtitle("127.0.0.1",stub.port,"fixture","movie","file");fail("rejection accepted")}
            catch (_:HelperClient.SubtitleRejected){}
            stub.unknown=true
            try{HelperClient.loadSubtitle("127.0.0.1",stub.port,"fixture","movie","file");fail("unknown accepted")}
            catch (_:HelperClient.Unreachable){}
        }
    }

    @Test fun supersededLoadUnknownSurvivesLaterOffAndEmptyList() = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(ctx.filesDir,"subtitle-operations.json").delete()
        Stub().use {stub->
            HapticTickTest.seedPrefs(ctx,false,stub.port)
            val store=Store(ctx);store.helperToken="fixture";store.helperPort=stub.port
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            try {
                val remote=RemoteState(ctx,store,scope)
                withContext(Dispatchers.Main){remote.refreshSubtitleFiles()};delay(300)
                stub.delayMs=700;stub.failLoadOnly=true
                withContext(Dispatchers.Main){remote.loadSubtitleFile(remote.subtitleFolder!!.files.single())}
                delay(200)
                withContext(Dispatchers.Main){remote.pickSubtitle(-1)}
                delay(3000)
                assertTrue(remote.subtitleOutcomeUnknown)
                assertNotNull(remote.subtitleFileError)
                assertNotEquals(-1,remote.pickedSubtitle)
                stub.delayMs=0;stub.emptyMedia=true
                withContext(Dispatchers.Main){remote.refreshSubtitleFiles()};delay(300)
                assertTrue("stopped/empty identity must not clear uncertainty",remote.subtitleOutcomeUnknown)
                stub.emptyMedia=false
                withContext(Dispatchers.Main){remote.refreshSubtitleFiles()};delay(300)
                assertTrue("same media resume must not clear uncertainty",remote.subtitleOutcomeUnknown)
                store.host="127.0.0.2" // same paired token, rediscovered address
                val recreated=withContext(Dispatchers.Main){RemoteState(ctx,store,scope)}
                delay(300)
                assertTrue("recreation must preserve uncertainty",recreated.subtitleOutcomeUnknown)
                assertNotNull(recreated.subtitleFileError)
            } finally {scope.cancel()}
        }
    }

    @Test fun preDispatchMarkerSurvivesRecreationDuringSlowMutation() = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(ctx.filesDir,"subtitle-operations.json").delete()
        Stub().use{stub->
            HapticTickTest.seedPrefs(ctx,false,stub.port)
            val store=Store(ctx);store.helperToken="lifecycle-fixture";store.helperPort=stub.port
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            try{
                val remote=RemoteState(ctx,store,scope)
                withContext(Dispatchers.Main){remote.refreshSubtitleFiles()};delay(300)
                stub.delayMs=2500
                withContext(Dispatchers.Main){remote.loadSubtitleFile(remote.subtitleFolder!!.files.single())}
                delay(300)
                val recreated=withContext(Dispatchers.Main){RemoteState(ctx,store,scope)}
                delay(300)
                assertTrue("in-flight marker must exist before response",recreated.subtitleOutcomeUnknown)
                store.helperToken="repaired-fixture"
                val repaired=withContext(Dispatchers.Main){RemoteState(ctx,store,scope)}
                delay(300)
                assertFalse("different pair token must not inherit warning",repaired.subtitleOutcomeUnknown)
                delay(3000)
                store.helperToken="lifecycle-fixture"
                withContext(Dispatchers.Main){recreated.refreshSubtitleFiles(quiet=true)}
                delay(300)
                assertFalse("completed request reconciles reconstructed state",recreated.subtitleOutcomeUnknown)
            }finally{scope.cancel()}
        }
    }

}
