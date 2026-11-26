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

    companion object {
        /** Debug-build test seam ONLY: how many times a loader asked and was told it was superseded (so a test can observe that a late result was consumed and dropped). */
        internal val staleDrops = AtomicInteger(0)
    }
}
