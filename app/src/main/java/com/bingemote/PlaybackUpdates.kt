package com.bingemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One ordered live playback snapshot for the app and notification; older requests cannot win. */
object PlaybackUpdates {
    data class Snapshot(val requestedAt: Long, val status: VlcStatus, val endpoint: String)
    private val state = MutableStateFlow<Snapshot?>(null)
    val latest = state.asStateFlow()
    @Synchronized fun publish(requestedAt: Long, status: VlcStatus, endpoint: String = "") {
        if (requestedAt >= (state.value?.requestedAt ?: Long.MIN_VALUE)) state.value = Snapshot(requestedAt,status,endpoint)
    }
}
