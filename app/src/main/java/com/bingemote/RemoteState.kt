package com.bingemote

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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

    /** The address the live mouse connection uses. Store.host is plain preferences, so a
     *  rediscovery while the mouse screen is open never recomposes it - publish here instead. */
    var liveHost by mutableStateOf(store.host); private set
    var livePort by mutableStateOf(store.helperPort); private set
    var liveToken by mutableStateOf(store.helperToken); private set

    /** Pairing, re-pairing or rediscovery learned an address: open screens reconnect to it. */
    fun publishAddress(host: String, port: Int, token: String) {
        liveHost = host; livePort = port; liveToken = token
    }

    /** Screenshot-rig only: a fake playing video so seek-drag captures show real times. */
    fun debugSeedStatus() {
        if (BuildConfig.DEBUG) status = VlcStatus("playing", "Demo", 1500, 6624, 128, false, emptyList())
    }

    private var pollJob: Job? = null
    private var failures = 0
    private var helperFailures = 0
    private var rediscovering = false

    private companion object {
        /** Consecutive failed polls before the Disconnected card shows; one success clears it. */
        const val UNREACHABLE_AFTER = 2
    }

    /** One immediate retry on a network blip: a single dropped packet must not count as a failure.
     *  A connect timeout is not a blip - the PC is gone - so it is not retried (would double the wait). */
    private suspend fun <T> twiceIO(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (e: Vlc.BadAuth) { throw e }
        catch (e: Vlc.ConnectTimeout) { throw e }
        catch (e: HelperClient.ConnectTimeout) { throw e }
        catch (e: Exception) { block() }
    }

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
        // Status and Windows volume in parallel: a blocked helper used to stall the whole
        // poll (time and seek bar only updated every ~11s when VLC answered but the helper didn't).
        val vlcDeferred = scope.async { twiceIO { Vlc.status(store.host, store.vlcPort, store.vlcPassword) } }
        val volDeferred = if (store.helperToken.isNotBlank())
            scope.async { try { twiceIO { HelperClient.volumeGet(store.host, store.helperPort, store.helperToken) } } catch (e: Exception) { null } }
        else null
        try {
            val s = vlcDeferred.await()
            status = s
            if (volDeferred != null) {
                val vm = volDeferred.await()
                if (vm != null) {
                    helperFailures = 0
                    muted = vm.second
                    helperDown = false
                    // Trust the phone's own taps briefly: the poll doesn't overwrite a volume
                    // change that is still landing (that overwrite made the number jump back).
                    if (System.currentTimeMillis() - localVolumeAt > 1500) winVolume = vm.first
                } else {
                    // One blip keeps the last reading; two in a row means the helper is really gone.
                    helperFailures++
                    if (helperFailures >= 2) { helperDown = true; winVolume = null }
                }
            } else winVolume = null
            failures = 0
            unreachable = false
            vlcClosed = false
            connecting = false
            expectingVlc = false
        } catch (e: Vlc.BadAuth) {
            failures = UNREACHABLE_AFTER
            unreachable = true
        } catch (e: Exception) {
            failures++
            if (failures >= UNREACHABLE_AFTER) {
                unreachable = true
                // The PC answering its helper but not VLC means VLC simply isn't open.
                vlcClosed = store.helperToken.isNotBlank() &&
                    withContext(Dispatchers.IO) {
                        HelperClient.ping(store.host, store.helperPort, store.helperToken) ||
                            HelperClient.ping(store.host, store.helperPort, store.helperToken)
                    }
                if (store.autoReconnect) rediscover()
            }
        }
    }

    /** The laptop's IP changes with DHCP: ask the network for the helper and update the address. */
    fun rediscover() {
        if (rediscovering) return
        rediscovering = true
        scope.launch(Dispatchers.IO) {
            try {
                // The saved address usually still answers (VLC was the one down): one quick ping first.
                if (HelperClient.ping(store.host, store.helperPort, store.helperToken)) {
                    failures = 0
                    return@launch
                }
                val found = HelperClient.discover(3000)
                // The pairing token is the PC's identity; its name is only a label.
                val match = found.firstOrNull { HelperClient.ping(it.host, it.port, store.helperToken) }
                if (match != null) {
                    withContext(Dispatchers.Main) {
                        store.host = match.host
                        store.helperPort = match.port
                        publishAddress(match.host, match.port, store.helperToken)
                        failures = 0
                    }
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

    /** Set when the phone itself changes Windows volume; the poll trusts the phone for 1.5s. */
    private var localVolumeAt = 0L

    // One ordered queue for playback/volume commands: rapid volume taps and the phone's
    // volume keys land in tap order instead of racing each other in parallel coroutines.
    private val cmds = Channel<() -> Unit>(Channel.UNLIMITED)
    init {
        scope.launch(Dispatchers.IO) {
            for (c in cmds) try { c() } catch (_: Exception) { failures++; if (failures >= UNREACHABLE_AFTER) unreachable = true }
        }
    }

    private fun fire(block: () -> Unit) { cmds.trySend(block) }

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
    fun seekTo(seconds: Long) {
        if (BuildConfig.DEBUG) Log.i("BMSeek", "seekTo seconds=$seconds")
        fire { Vlc.seekTo(store.host, store.vlcPort, store.vlcPassword, seconds) }
    }

    fun volumeStep(up: Boolean) {
        val win = winVolume
        if (win != null) {
            // Windows system volume via the helper (recommended pick; Ezzy left it to us and did not object).
            val base = win
            val wasMuted = muted
            if (wasMuted) muted = false
            val next = (base + if (up) 5 else -5).coerceIn(0, 100)
            winVolume = next
            localVolumeAt = System.currentTimeMillis()
            fire {
                HelperClient.volumeSet(store.host, store.helperPort, store.helperToken, next)
                // Unmute only when it WAS muted - a plain volume step must not touch the mute switch.
                if (wasMuted) HelperClient.volumeMute(store.host, store.helperPort, store.helperToken, false)
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
            localVolumeAt = System.currentTimeMillis()
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
