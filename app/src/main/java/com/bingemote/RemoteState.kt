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

    /** VLC didn't answer but the helper did: the PC is fine, VLC just isn't open. */
    var vlcClosed by mutableStateOf(false)
    /** The helper just started VLC: hide the error card while it comes up. */
    var expectingVlc by mutableStateOf(false)
        private set

    /** The helper just started VLC: hide the error card for up to 20s while it comes up. */
    fun expectVlcStarting() {
        expectingVlc = true
        scope.launch {
            kotlinx.coroutines.delay(20_000)
            expectingVlc = false
        }
    }
    var connecting by mutableStateOf(false)
        private set

    /** Local mute: VLC has no mute command, so we send volume 0 and remember where we were. */
    var muted by mutableStateOf(false)
        private set

    /** Windows system volume 0-100 from the helper; null when the helper is unreachable, and VLC volume is used instead. */
    var winVolume by mutableStateOf<Int?>(null)
        private set

    /** True when the helper is paired but not answering, so the volume row fell back to VLC volume. */
    var helperDown by mutableStateOf(false)
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
            winVolume = if (store.helperToken.isNotBlank()) {
                try {
                    val (v, m) = withContext(Dispatchers.IO) { HelperClient.volumeGet(store.host, store.helperPort, store.helperToken) }
                    muted = m
                    helperDown = false
                    v
                } catch (e: Exception) { helperDown = true; null }
            } else null
            failures = 0
            unreachable = false
            vlcClosed = false
            connecting = false
            expectingVlc = false
        } catch (e: Vlc.BadAuth) {
            failures = 2
            unreachable = true
        } catch (e: Exception) {
            failures++
            if (failures >= 2) {
                unreachable = true
                // The PC answering its helper but not VLC means VLC simply isn't open.
                vlcClosed = store.helperToken.isNotBlank() &&
                    withContext(Dispatchers.IO) { HelperClient.ping(store.host, store.helperPort, store.helperToken) }
                if (store.autoReconnect) rediscover()
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
                val match = found.firstOrNull { it.name == store.pcName && HelperClient.ping(it.host, it.port, store.helperToken) }
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

    fun togglePause() {
        // Flip the icon at once; the next status poll (about a second out) confirms or corrects it.
        val s = status
        if (s != null) {
            status = when (s.state.lowercase()) {
                "playing" -> s.copy(state = "paused")
                "paused" -> s.copy(state = "playing")
                else -> s
            }
        }
        fire { Vlc.togglePause(store.host, store.vlcPort, store.vlcPassword) }
    }
    fun toggleFullscreen() = fire { Vlc.toggleFullscreen(store.host, store.vlcPort, store.vlcPassword) }
    fun skip(seconds: Int) = fire { Vlc.skip(store.host, store.vlcPort, store.vlcPassword, seconds) }
    fun seekTo(seconds: Long) = fire { Vlc.seekTo(store.host, store.vlcPort, store.vlcPassword, seconds) }

    fun volumeStep(up: Boolean) {
        val win = winVolume
        if (win != null) {
            // Windows system volume via the helper (recommended pick; Ezzy left it to us and did not object).
            val base = win
            if (muted) muted = false
            val next = (base + if (up) 5 else -5).coerceIn(0, 100)
            winVolume = next
            fire {
                HelperClient.volumeSet(store.host, store.helperPort, store.helperToken, next)
                HelperClient.volumeMute(store.host, store.helperPort, store.helperToken, false)
            }
            return
        }
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
        if (winVolume != null) {
            val next = !muted
            muted = next
            fire { HelperClient.volumeMute(store.host, store.helperPort, store.helperToken, next) }
            return
        }
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
