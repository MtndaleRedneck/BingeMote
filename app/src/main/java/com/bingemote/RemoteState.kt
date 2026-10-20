package com.bingemote

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live connection to VLC: polls status about once a second while the app is open,
 * rediscovers the PC over UDP when the address goes stale, and turns two failed
 * polls in a row into the error card.
 */
class RemoteState(
    private val app: Context,
    val store: Store,
    private val scope: CoroutineScope,
) {
    var status by mutableStateOf<VlcStatus?>(null)
        private set
    var unreachable by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set

    /** Local mute: VLC has no mute command, so we send volume 0 and remember where we were. */
    var muted by mutableStateOf(false)
        private set
    private var preMuteRaw = 0

    /** Last subtitle track picked in the app, per media title. VLC's status doesn't report the current track on all versions. */
    var pickedSubtitle by mutableStateOf<Int?>(null)

    private var pollJob: Job? = null
    private var failures = 0
    private var rediscovering = false

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(1000)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun pollOnce() {
        if (!store.isConfigured) return
        try {
            val s = withContext(Dispatchers.IO) { Vlc.status(store.host, store.vlcPort, store.vlcPassword) }
            status = s
            failures = 0
            unreachable = false
            connecting = false
        } catch (e: Vlc.BadAuth) {
            failures = 2
            unreachable = true
        } catch (e: Exception) {
            failures++
            if (failures >= 2) {
                unreachable = true
                rediscover()
            }
        }
    }

    /** The laptop's IP changes with DHCP: ask the network for the helper and update the address. */
    private fun rediscover() {
        if (rediscovering) return
        rediscovering = true
        scope.launch(Dispatchers.IO) {
            try {
                val found = HelperClient.discover()
                val match = found.firstOrNull { it.name == store.pcName } ?: found.firstOrNull()
                if (match != null) {
                    store.host = match.host
                    store.helperPort = match.port
                    failures = 0
                }
            } finally {
                rediscovering = false
            }
        }
    }

    fun retry() {
        failures = 0
        connecting = true
        scope.launch { pollOnce() }
    }

    private fun fire(block: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try { block() } catch (e: Exception) { failures++; if (failures >= 2) unreachable = true }
        }
    }

    fun togglePause() = fire { Vlc.togglePause(store.host, store.vlcPort, store.vlcPassword) }
    fun toggleFullscreen() = fire { Vlc.toggleFullscreen(store.host, store.vlcPort, store.vlcPassword) }
    fun skip(seconds: Int) = fire { Vlc.skip(store.host, store.vlcPort, store.vlcPassword, seconds) }
    fun seekTo(seconds: Long) = fire { Vlc.seekTo(store.host, store.vlcPort, store.vlcPassword, seconds) }

    fun volumeStep(up: Boolean) {
        val s = status
        if (muted) {
            // Restore-then-step: unmute to where we were, then one step from there.
            val base = preMuteRaw
            muted = false
            val next = (base + if (up) Vlc.VOLUME_STEP else -Vlc.VOLUME_STEP).coerceIn(0, Vlc.VOLUME_100)
            fire { Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, next) }
        } else {
            val cur = s?.volumeRaw ?: return
            val next = (cur + if (up) Vlc.VOLUME_STEP else -Vlc.VOLUME_STEP).coerceIn(0, Vlc.VOLUME_100)
            fire { Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, next) }
        }
    }

    fun toggleMute() {
        if (muted) {
            muted = false
            val back = preMuteRaw.coerceIn(1, Vlc.VOLUME_100)
            fire { Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, back) }
        } else {
            preMuteRaw = (status?.volumeRaw ?: Vlc.VOLUME_100 / 2).coerceIn(1, Vlc.VOLUME_100 * 2)
            muted = true
            fire { Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, 0) }
        }
    }

    fun pickSubtitle(trackId: Int) {
        pickedSubtitle = trackId
        fire { Vlc.setSubtitleTrack(store.host, store.vlcPort, store.vlcPassword, trackId) }
    }
}
