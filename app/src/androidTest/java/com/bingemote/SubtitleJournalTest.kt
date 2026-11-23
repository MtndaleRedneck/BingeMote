package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SubtitleJournalTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun reset(){File(ctx.filesDir,"subtitle-operations.json").delete();File(ctx.filesDir,"subtitle-operations.json.bak").delete()}
    @Test fun olderUnknownIsNotOverwrittenByLaterSuccessfulOperation(){
        reset();val journal=SubtitleJournal(ctx)
        val a=journal.begin("pair","movieA");assertTrue(journal.finish(a,true))
        val b=journal.begin("pair","movieB");assertTrue(journal.finish(b,false))
        val restored=SubtitleJournal(ctx).entries("pair")
        assertEquals(1,restored.size);assertEquals("movieA",restored.single().media)
        assertTrue(journal.clearOtherMedia("pair","movieB"));assertTrue(journal.entries("pair").isEmpty())
    }
    @Test fun liveCompletionReconcilesReconstructedPending(){
        reset();val first=SubtitleJournal(ctx);val id=first.begin("pair","movie")
        val recreated=SubtitleJournal(ctx);assertEquals("pending",recreated.entries("pair").single().phase)
        assertTrue(first.finish(id,false));assertTrue(recreated.entries("pair").isEmpty())
    }
    @Test fun failedStorageDoesNotCreateDispatchPermit(){
        reset();val target=File(ctx.filesDir,"subtitle-operations.json");target.mkdir()
        try { try{SubtitleJournal(ctx).begin("pair","movie");fail("storage failure accepted")}catch(_:Exception){} }
        finally{target.deleteRecursively()}
    }
}
