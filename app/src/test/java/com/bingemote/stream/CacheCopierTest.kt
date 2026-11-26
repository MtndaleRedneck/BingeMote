package com.bingemote.stream

import java.io.File
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S9: the cache copy policy under concurrency, failure and shrinking space. */
class CacheCopierTest {

    private fun dir(): File {
        val d = File(System.getProperty("java.io.tmpdir"), "cachecopier-" + System.nanoTime())
        d.mkdirs(); return d
    }

    @Test fun partialCopyIsNeverReused() {
        val d = dir(); val tmp = File(d, "x.part"); val fin = File(d, "x")
        val broken = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100) 65 else throw java.io.IOException("cut off")
        }
        try { CacheCopier.copy({ broken }, tmp, fin, { 1L shl 40 }, 1L shl 33, 0) }
        catch (e: Exception) { /* expected */ }
        assertFalse("partial .part deleted", tmp.exists())
        assertFalse("partial never renamed", fin.exists())
    }

    @Test fun completeCopyRenames() {
        val d = dir(); val tmp = File(d, "x.part"); val fin = File(d, "x")
        CacheCopier.copy({ "hello".byteInputStream() }, tmp, fin, { 1L shl 40 }, 1L shl 33, 0)
        assertTrue(fin.exists()); assertFalse(tmp.exists())
        assertEquals("hello", fin.readText())
    }

    @Test fun freeSpaceIsEnforcedAsBytesGrow() {
        val d = dir(); val tmp = File(d, "x.part"); val fin = File(d, "x")
        var free = 10_000_000L
        // 9 MB of data; the floor is 5 MB and the "disk" drains as bytes are written,
        // so a copy this size can only finish by breaching the floor mid-copy.
        val data = ByteArray(9 * 1024 * 1024) { 7 }
        try {
            CacheCopier.copy({ data.inputStream() }, tmp, fin,
                { free - tmp.length().coerceAtLeast(0) }, 1L shl 33, 5_000_000)
            throw AssertionError("should have aborted")
        } catch (e: CacheCopier.NoSpace) { }
        assertFalse(tmp.exists()); assertFalse(fin.exists())
    }

    @Test fun concurrentCopiesSerializeAndReuseOneResult() {
        val d = dir()
        val lock = Object()
        val tmp = File(d, "x.part"); val fin = File(d, "x")
        val payloads = (1..8).map { ByteArray(64 * 1024) { it.toByte() } }
        val threads = (1..4).map {
            thread {
                synchronized(lock) {
                    if (!fin.exists())
                        CacheCopier.copy({ payloads[0].inputStream() }, tmp, fin, { 1L shl 40 }, 1L shl 33, 0)
                }
            }
        }
        threads.forEach { it.join() }
        assertTrue(fin.exists())
        assertFalse("no torn .part left behind", tmp.exists())
        assertEquals(64 * 1024, fin.length())
    }

    @Test fun declaredLengthMatch_commits() {
        val d = dir(); val tmp = File(d, "x.part"); val fin = File(d, "x")
        CacheCopier.copy({ "hello".byteInputStream() }, tmp, fin, { 1L shl 40 }, 1L shl 33, 0,
            expectedBytes = { 5 })
        assertTrue(fin.exists()); assertFalse(tmp.exists())
    }

    /** M1/Tab: a provider that ends its stream EARLY (or long) vs its declared
     *  length must never see its copy committed - validation runs before commit. */
    @Test fun declaredLengthMismatch_neverCommits() {
        val d = dir(); val tmp = File(d, "x.part"); val fin = File(d, "x")
        var commitCalled = false
        try {
            CacheCopier.copy({ "hello".byteInputStream() }, tmp, fin, { 1L shl 40 }, 1L shl 33, 0,
                expectedBytes = { 100 }, commit = { _, _ -> commitCalled = true; true })
            throw AssertionError("should have aborted")
        } catch (e: CacheCopier.SizeMismatch) {
            assertEquals(100L, e.expected); assertEquals(5L, e.actual)
        }
        assertFalse("commit never ran", commitCalled)
        assertFalse("partial .part deleted", tmp.exists())
        assertFalse("nothing committed", fin.exists())
    }

    /** R3/Tab: a provider declaring SIZE 0 (or negative) declares nothing -
     *  the copy completes on EOF exactly like an unknown length. Regression
     *  guard for the 502-vs-1.3 compatibility break. */
    @Test fun zeroOrNegativeDeclaredLength_treatedAsUnknown() {
        val d = dir(); val tmp = File(d, "x.part"); val fin = File(d, "x")
        CacheCopier.copy({ "hello".byteInputStream() }, tmp, fin, { 1L shl 40 }, 1L shl 33, 0,
            expectedBytes = { 0 })
        assertTrue("zero declaration: committed as unknown length", fin.exists())
        val d2 = dir(); val tmp2 = File(d2, "x.part"); val fin2 = File(d2, "x")
        CacheCopier.copy({ "hello".byteInputStream() }, tmp2, fin2, { 1L shl 40 }, 1L shl 33, 0,
            expectedBytes = { -5 })
        assertTrue("negative declaration: committed as unknown length", fin2.exists())
    }

    /** R3/Tab: a clean EOF BEYOND a stale positive declaration is accepted and
     *  reported (never a 502); a SHORT EOF still never commits. */
    @Test fun stalePositiveDeclaredLength_cleanEofBeyondCommitsAndReports() {
        val d = dir(); val tmp = File(d, "x.part"); val fin = File(d, "x")
        var reported: Pair<Long, Long>? = null
        CacheCopier.copy({ "hello world".byteInputStream() }, tmp, fin, { 1L shl 40 }, 1L shl 33, 0,
            expectedBytes = { 5 }, onStaleDeclared = { e, a -> reported = e to a })
        assertTrue("stale positive declaration: committed", fin.exists())
        assertEquals("hello world", fin.readText())
        assertEquals("stale declaration reported with both sizes", 5L to 11L, reported)
    }

    @Test fun unknownDeclaredLength_eofIsTheOnlySignal() {
        val d = dir(); val tmp = File(d, "x.part"); val fin = File(d, "x")
        CacheCopier.copy({ "hello".byteInputStream() }, tmp, fin, { 1L shl 40 }, 1L shl 33, 0,
            expectedBytes = { null })
        assertTrue(fin.exists())
    }
}
