package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SubtitleJournalTest {
    @get:Rule val failureDump = FailureDumpRule()

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
    @Test fun backupOnlyFileRecoversAndInvalidDataDoesNotResetSilently(){
        reset();val journal=SubtitleJournal(ctx);val id=journal.begin("pair","movie")
        val base=File(ctx.filesDir,"subtitle-operations.json")
        assertTrue(base.renameTo(File(base.path+".bak")))
        assertEquals(1,SubtitleJournal(ctx).entries("pair").size)
        journal.finish(id,false)
        base.writeText("{truncated")
        try{SubtitleJournal(ctx).begin("pair","movie");fail("corrupt state accepted")}catch(_:Exception){}
        reset()
    }
    @Test fun repeatedUnknownForSameMediaIsCompactedWithoutHidingIt(){
        reset();val journal=SubtitleJournal(ctx)
        repeat(150){val id=journal.begin("pair","movie");assertTrue(journal.finish(id,true))}
        assertEquals(1,journal.entries("pair").size)
    }

    @Test fun orphanPendingBecomesUnknownAndDifferentMediaCanRecover(){
        reset()
        File(ctx.filesDir,"subtitle-operations.json").writeText("""{"entries":[{"id":"dead-process-op","pair":"pair","media":"movieA","phase":"pending"}]}""")
        val journal=SubtitleJournal(ctx)
        assertEquals("unknown",journal.entries("pair").single().phase)
        assertTrue(journal.clearOtherMedia("pair","movieB"))
        assertTrue(journal.entries("pair").isEmpty())
    }
    @Test fun capacityFailurePreservesAllUncertaintyAndDoesNotGrantBegin(){
        reset();val entries=org.json.JSONArray()
        repeat(1024){entries.put(org.json.JSONObject().put("id","old-$it").put("pair","pair").put("media","movie$it").put("phase","unknown"))}
        File(ctx.filesDir,"subtitle-operations.json").writeText(org.json.JSONObject().put("entries",entries).toString())
        val journal=SubtitleJournal(ctx)
        assertEquals(1,journal.entries("pair").size)
        val existing=journal.begin("pair","newMovie");assertTrue(journal.finish(existing,false))
        assertEquals("",journal.entries("pair").single().media)
        assertTrue(journal.clearOtherMedia("pair","currentMovie"))
        assertEquals("coarse uncertainty must survive media refresh",1,journal.entries("pair").size)
        try{journal.begin("pair","currentMovie",true);fail("coarse uncertainty allowed folder add")}catch(_:Exception){}
    }

    @Test fun degradedHistoryBlocksFolderTransactionButNotExistingControlSlot(){
        reset();val journal=SubtitleJournal(ctx)
        journal.markDegraded("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        try{journal.begin("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","movie",true);fail("degraded folder add accepted")}catch(_:Exception){}
        val id=journal.begin("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","movie",false)
        assertTrue(journal.finish(id,false))
        assertTrue(SubtitleJournal(ctx).isDegraded("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }

}
