package com.bingemote

import java.util.concurrent.atomic.AtomicInteger

/**
 * Generation gate for the Movies list loaders. Every loader entry takes a generation with [bump];
 * after each await it asks [isCurrent] and drops its result when a newer load (or an early return
 * that also bumped) superseded it. This keeps a late result from an old origin/folder from writing
 * items, path, loading or error over the screen the user is on now. It only orders writes; it does not
 * cancel the work or the network call itself.
 */
class LoadGate {
    private val gen = AtomicInteger(0)

    /** Start a new load and return its generation; every earlier generation becomes stale. */
    fun bump(): Int = gen.incrementAndGet()

    /** The generation now in force, without superseding anything (captured before an await). */
    fun current(): Int = gen.get()

    fun isCurrent(g: Int): Boolean {
        val ok = gen.get() == g
        if (!ok && BuildConfig.DEBUG) staleDrops.incrementAndGet()
        return ok
    }

    /**
     * Debug-build test seam ONLY (a no-op in release: guarded by BuildConfig.DEBUG, nothing is recorded or kept).
     * Called by a loader at the point where its network call returned SUCCESSFULLY, instead of a bare [isCurrent]. Records
     * the loader kind, its generation, whether that generation was still current, and the first row name (max 40 chars)
     * so a test can see that a specific request's success was consumed and accepted or rejected. Returns [isCurrent].
     */
    fun successCurrent(kind: String, g: Int, rows: List<BrowseItem>): Boolean {
        val ok = isCurrent(g)
        if (BuildConfig.DEBUG) {
            val first = rows.firstOrNull()?.name?.take(40) ?: ""
            successes.add(Success(kind, g, ok, first))
        }
        return ok
    }

    data class Success(val kind: String, val gen: Int, val accepted: Boolean, val firstRow: String)

    companion object {
        /** Debug-build test seam ONLY: per-request success records written by [successCurrent]. */
        internal val successes = java.util.concurrent.CopyOnWriteArrayList<Success>()

        /** Debug-build test seam ONLY: how many times a loader asked and was told it was superseded (so a test can observe that a late result was consumed and dropped). */
        internal val staleDrops = AtomicInteger(0)
    }
}
