package com.bingemote

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide, credential-scoped "this PC presented a different key" state. Set by the trust manager itself the
 * moment a mismatch is seen, so no caller (HTTP, websocket, subtitles, swallowed-error paths) can hide or skip it.
 * Checked before every request. It is keyed by the local PC identity of the stored record: only an explicit re-pair
 * (a new record with a new local identity) leaves it behind. A successful poll or an ordinary retry never clears it.
 */
internal object KeyChangeState {
    private val changed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun mark(pcId: String) { changed.add(pcId) }
    fun isChanged(pcId: String?): Boolean = pcId != null && changed.contains(pcId)
    internal fun resetForTests() = changed.clear()
}
