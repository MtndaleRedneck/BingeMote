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
    /** VLC refused the password and the one automatic heal (the adoption fetch) is spent:
     *  the card says so and points at re-pair or the PC-side setting. */
    var vlcRefused by mutableStateOf(false)
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
    // The PC answered 401: this phone's pairing was removed. Definitive, not a blip.
    var unpaired by mutableStateOf(false)
        private set
    // The helper-down NOTICE waits ~5s of continuous failure; the VLC volume
    // fallback (2 strikes) still kicks in immediately - a brief blip falls back
    // quietly without nagging.
    var helperNotice by mutableStateOf(false)
        private set
    private var helperDownAt = 0L
    // The pairing token the notice state above belongs to (see pollOnce).
    private var noticeToken: String? = null
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

    /** Screenshot-rig only: mock hero note line for the status-notice proposal
     * renders (the proposed E/unpaired subtitle). Never set outside the rig. */
    var mockHeroNote by mutableStateOf<String?>(null)

    /** Screenshot-rig only: force the connection surfaces so captures show the real
     * status UI in each review scenario. */
    fun debugForceStatus(unreachable: Boolean, helperDown: Boolean, helperNotice: Boolean = false, unpaired: Boolean = false) {
        if (BuildConfig.DEBUG) {
            this.unreachable = unreachable; this.helperDown = helperDown
            this.helperNotice = helperNotice; this.unpaired = unpaired
        }
    }

    private var pollJob: Job? = null
    private var lastConnectedAt = 0L
    private var failures = 0
    private var helperFailures = 0
    private var rediscovering = false
    private var closedPolls = 0
    private var lastTitle: String? = null
    private var lastLengthSec: Long = -1

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
        // The helper-notice state belongs to the pairing it was learned from: a new
        // or cleared token makes it stale. Reset it here so a re-paired phone never
        // sees "pair again" (or a leftover helper warning) about its NEW pairing.
        // Runs before the isConfigured early-return so a cleared token also resets.
        if (noticeToken != store.helperToken) {
            noticeToken = store.helperToken
            unpaired = false
            helperNotice = false
            helperDown = false
            helperFailures = 0
            helperDownAt = 0L
        }
        if (!store.isConfigured) return
        // Status and Windows volume in parallel: a blocked helper used to stall the whole
        // poll (time and seek bar only updated every ~11s when VLC answered but the helper didn't).
        // While VLC is closed, re-check it every 4th cycle instead of every cycle (fewer
        // dead-end requests); the helper ping still runs every cycle either way.
        val skipVlc = vlcClosed && (closedPolls++ % CmdPolicy.CLOSED_POLL_EVERY != 0)
        val vlcDeferred = if (skipVlc) null
            else scope.async { twiceIO { Vlc.status(store.host, store.vlcPort, store.vlcPassword) } }
        val volDeferred = if (store.helperToken.isNotBlank())
            scope.async {
                try { twiceIO { HelperClient.volumeGet(store.host, store.helperPort, store.helperToken) } }
                catch (e: HelperClient.BadToken) { throw e }
                catch (e: Exception) { null }
            }
        else null
        // The helper's answer counts even when VLC's does not: the volume reading used to
        // be thrown away on the VLC-failure path, leaving the row stuck at its last value.
        if (volDeferred != null) {
            val vm = try { volDeferred.await() } catch (e: HelperClient.BadToken) { unpaired = true; null }
            if (vm != null) {
                helperFailures = 0
                muted = vm.second
                helperDown = false
                helperDownAt = 0L
                helperNotice = false
                unpaired = false
                // Trust the phone's own taps briefly: the poll doesn't overwrite a volume
                // change that is still landing (that overwrite made the number jump back).
                if (System.currentTimeMillis() - localVolumeAt > 1500) winVolume = vm.first
            } else {
                // One blip keeps the last reading; two in a row means the helper is really gone.
                helperFailures++
                if (helperFailures >= 2) {
                    if (!helperDown) helperDownAt = System.currentTimeMillis()
                    helperDown = true; winVolume = null
                }
                helperNotice = helperDown && !unpaired &&
                    System.currentTimeMillis() - helperDownAt >= 5000
            }
        } else winVolume = null
        if (skipVlc) return
        try {
            val s = vlcDeferred!!.await()
            status = s
            lastConnectedAt = android.os.SystemClock.elapsedRealtime()
            // A new video clears the remembered subtitle pick; the sheet then shows the
            // file's own selected track instead of a checkmark from the previous video.
            if (s.title != lastTitle || s.lengthSec != lastLengthSec) {
                lastTitle = s.title; lastLengthSec = s.lengthSec
                subtitleRequest++
                subtitleListRequest++
                subtitleListLoading = false
                subtitleMutationLoading = false
                pickedSubtitle = null
                pickedSubtitleFile = null
                subtitleFolder = null
            }
            failures = 0
            unreachable = false
            vlcRefused = false
            vlcClosed = false
            closedPolls = 0
            connecting = false
            expectingVlc = false
        } catch (e: Vlc.BadAuth) {
            lastConnectedAt = 0L
            // The one automatic heal: adopt the PC's current password once. The phone never
            // pushes its stored copy at the PC - a refusal can be an intentional PC change.
            val healed = withContext(Dispatchers.IO) { VlcAuthRepair.tryHealOnce(store) }
            if (healed) { vlcRefused = false; return } // the next poll uses the fresh password
            vlcRefused = true
            failures = UNREACHABLE_AFTER
            unreachable = true
        } catch (e: Exception) {
            lastConnectedAt = 0L
            failures++
            if (failures >= UNREACHABLE_AFTER) {
                unreachable = true
                // The PC answering its helper but not VLC means VLC simply isn't open.
                vlcClosed = store.helperToken.isNotBlank() &&
                    withContext(Dispatchers.IO) {
                        HelperClient.ping(store.host, store.helperPort, store.helperToken) ||
                            HelperClient.ping(store.host, store.helperPort, store.helperToken)
                    }
                rediscover() // always on since 1.9 (the toggle is gone: nobody had a reason to shut it off)
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
    // Taps carry their time: a play/pause/skip/seek queued more than about 2 s (a dead
    // PC, a stalled request) is a tap he no longer expects, so it is dropped - and a
    // burst of volume steps folds into the newest one.
    private class Cmd(val kind: String, val ts: Long, val subtitleGeneration: Int = -1, val block: () -> Unit)
    private val cmds = Channel<Cmd>(Channel.UNLIMITED)
    @Volatile private var pushback: Cmd? = null // one non-volume command held while volume folds
    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                var c = pushback?.also { pushback = null } ?: cmds.receive()
                if (c.kind == "sub" && c.subtitleGeneration != subtitleRequest) continue
                if (c.kind == "volume") {
                    while (true) {
                        val nxt = cmds.tryReceive().getOrNull() ?: break
                        if (nxt.kind == "volume") { Diag.volumeCoalesced.incrementAndGet(); c = nxt }
                        else { pushback = nxt; break }
                    }
                } else if (c.kind != "sub" && System.currentTimeMillis() - c.ts > CmdPolicy.EXPIRY_MS) {
                    Diag.cmdsExpired.incrementAndGet()
                    if (c.kind == "sub") scope.launch { subtitleMutationLoading = false }
                    continue
                }
                try { c.block() } catch (_: Exception) { failures++; if (failures >= UNREACHABLE_AFTER) unreachable = true }
            }
        }
    }

    private fun fire(kind: String, block: () -> Unit) {
        // First press while the PC's screen is dark only wakes it - the tap itself does
        // nothing (his rule, Nov 3). VLC-direct commands are gated here; helper-routed
        // ones are also swallowed PC-side, so one tap never half-lands. When the screen
        // state can't be learned, the tap is wake-only too (fail-safe, settled with Tab).
        val gated: () -> Unit = {
            if (HelperClient.screenIsDark(store.host, store.helperPort, store.helperToken)) {
                HelperClient.wakeDisplay(store.host, store.helperPort, store.helperToken)
            } else {
                block()
            }
        }
        cmds.trySend(Cmd(kind, System.currentTimeMillis(), block = gated))
    }

    fun togglePause() {
        // A disconnected tap is intentionally inert, without disabled styling.
        if (unreachable || vlcClosed || vlcRefused || lastConnectedAt == 0L ||
            android.os.SystemClock.elapsedRealtime() - lastConnectedAt > 5000) return
        if (BuildConfig.DEBUG) Log.i("BMSeek", "togglePause")
        // Flip the icon at once; the next status poll (about a second out) confirms or corrects it.
        val s = status
        if (s != null) {
            status = when (s.state.lowercase()) {
                "playing" -> s.copy(state = "paused")
                "paused" -> s.copy(state = "playing")
                else -> s
            }
        }
        fire("playpause") { Vlc.togglePause(store.host, store.vlcPort, store.vlcPassword) }
    }
    fun toggleFullscreen() = fire("fullscreen") { Vlc.toggleFullscreen(store.host, store.vlcPort, store.vlcPassword) }
    fun skip(seconds: Int) = fire("skip") { Vlc.skip(store.host, store.vlcPort, store.vlcPassword, seconds) }
    fun seekTo(seconds: Long) {
        if (BuildConfig.DEBUG) Log.i("BMSeek", "seekTo seconds=$seconds")
        fire("seek") { Vlc.seekTo(store.host, store.vlcPort, store.vlcPassword, seconds) }
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
            fire("volume") {
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
            status?.let { status = it.copy(volumeRaw = next) }
            fire("volume") { Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, next) }
        } else {
            val cur = s?.volumeRaw ?: return
            val next = (cur + if (up) Vlc.VOLUME_STEP else -Vlc.VOLUME_STEP).coerceIn(0, Vlc.VOLUME_100)
            // Echo the tap on the row right away - with the PC unreachable the poll can't
            // do it, and a tap that changes nothing feels dead. The next good poll overwrites.
            status = s.copy(volumeRaw = next)
            fire("volume") { Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, next) }
        }
    }

    fun toggleMute() {
        if (winVolume != null) {
            val next = !muted
            muted = next
            localVolumeAt = System.currentTimeMillis()
            fire("volume") { HelperClient.volumeMute(store.host, store.helperPort, store.helperToken, next) }
            return
        }
        if (muted) {
            muted = false
            val back = preMuteRaw.coerceIn(1, Vlc.VOLUME_100)
            fire("volume") { Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, back) }
        } else {
            preMuteRaw = (status?.volumeRaw ?: Vlc.VOLUME_100 / 2).coerceIn(1, Vlc.VOLUME_100 * 2)
            muted = true
            fire("volume") { Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, 0) }
        }
    }

    var subtitleFolder by mutableStateOf<HelperClient.SubtitleFolder?>(null); private set
    private var subtitleListLoading by mutableStateOf(false)
    private var subtitleMutationLoading by mutableStateOf(false)
    val subtitleFilesLoading get() = subtitleListLoading || subtitleMutationLoading
    private var subtitleListError by mutableStateOf<String?>(null)
    private var subtitleMutationError by mutableStateOf<String?>(null)
    val subtitleFileError get() = subtitleMutationError ?: subtitleListError
    private var confirmedSubtitleMedia: String? = null
    private val subtitleJournal = SubtitleJournal(app)
    private val subtitleWarningKey: String get() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(("subtitle-v1:" + store.helperToken).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    var subtitleOutcomeUnknown by mutableStateOf(false); private set
    private var uncertainFolderMedia: String? = null
    private var loadedWarningKey: String? = null
    private fun reconcileSubtitleWarnings() {
        val key = subtitleWarningKey
        val records = try { subtitleJournal.entries(key) } catch (_: Exception) { listOf(SubtitleJournal.Entry("unreadable",key,"","unknown")) }
        val media = records.firstOrNull()?.media
        val wasUnknown = subtitleOutcomeUnknown
        val bindingChanged = loadedWarningKey != key
        loadedWarningKey = key
        subtitleOutcomeUnknown = media != null
        uncertainFolderMedia = media
        if (bindingChanged) {
            confirmedSubtitleMedia = null; pickedSubtitle = null; pickedSubtitleFile = null
            subtitleFolder = null; subtitleRequest++
        }
        if (media != null) subtitleMutationError = "Couldn't confirm the previous subtitle change. Existing controls are still available."
        else if (wasUnknown || bindingChanged) subtitleMutationError = null
    }
    private fun beginSubtitleMutation(media: String?, key: String): String = try { subtitleJournal.begin(key,media) }
        catch (_: Exception) { throw HelperClient.SubtitleRejected("Couldn't save subtitle safety state. Try again.") }
    private fun finishSubtitleMutation(id: String?, unknown: Boolean) {
        if (id != null) subtitleJournal.finish(id,unknown)
    }
    var pickedSubtitleFile by mutableStateOf<String?>(null); private set
    @Volatile private var subtitleRequest = 0
    private var subtitleListRequest = 0
    private var subtitleListInFlight = false
    init { reconcileSubtitleWarnings() }

    internal fun debugSubtitlePreview() {
        if (!BuildConfig.DEBUG) return
        status = VlcStatus("playing", "Demo", 30, 3600, 128, false,
            listOf(SubTrack(1, "English", false), SubTrack(2, "Spanish", false)))
        subtitleFolder = HelperClient.SubtitleFolder("demo", listOf(
            HelperClient.SubtitleFile("demo-en", "Movie.en.srt"),
            HelperClient.SubtitleFile("demo-es", "Movie.es.srt")), false)
        pickedSubtitleFile = "demo-en"
    }
    fun refreshSubtitleFiles(quiet: Boolean = false) {
        reconcileSubtitleWarnings()
        if (BuildConfig.DEBUG && subtitleFolder?.mediaId == "demo") return
        if (subtitleListInFlight) return
        subtitleListInFlight = true
        val request = ++subtitleListRequest
        val mutationAtStart = subtitleRequest
        if (!quiet) { subtitleListLoading = true; subtitleListError = null }
        val host = store.host; val port = store.helperPort; val token = store.helperToken
        scope.launch {
            try {
                val folder = withContext(Dispatchers.IO) { HelperClient.subtitleFiles(host, port, token) }
                if (request == subtitleListRequest && mutationAtStart == subtitleRequest) {
                    if (!folder.unavailable && folder.mediaId.isNotEmpty() && confirmedSubtitleMedia != folder.mediaId) { pickedSubtitleFile = null; pickedSubtitle = null }
                    if (!folder.unavailable && folder.mediaId.isNotEmpty()) {
                        if (confirmedSubtitleMedia != folder.mediaId && uncertainFolderMedia != folder.mediaId && !uncertainFolderMedia.isNullOrEmpty()) {
                            subtitleJournal.clearOtherMedia(subtitleWarningKey,folder.mediaId)
                            reconcileSubtitleWarnings()
                        }
                        confirmedSubtitleMedia = folder.mediaId
                    }
                    subtitleFolder = folder
                    subtitleListError = null
                    if (folder.unavailable) subtitleListError = "Can't check subtitle files right now."
                    else if (folder.truncated) subtitleListError = "This folder is large. Only some subtitle files are shown."
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                if (request == subtitleListRequest && mutationAtStart == subtitleRequest) {
                    subtitleFolder = null
                    subtitleListError = "Folder subtitles need the updated PC app and a connection to it."
                }
            } finally { subtitleListInFlight = false; if (request == subtitleListRequest) subtitleListLoading = false }
        }
    }
    fun loadSubtitleFile(file: HelperClient.SubtitleFile) {
        reconcileSubtitleWarnings()
        val folder = subtitleFolder ?: return
        if (uncertainFolderMedia == folder.mediaId) {
            subtitleMutationError = "Couldn't confirm the file load. Existing subtitle controls are still available."
            return
        }
        val request = ++subtitleRequest
        val host = store.host; val port = store.helperPort; val token = store.helperToken
        val warningKey = subtitleWarningKey
        subtitleMutationError = null
        subtitleMutationLoading = true
        // Same ordered command lane as Off/loaded-track selection. A later Off cannot
        // overtake a slow add and then be undone when that add eventually lands.
        cmds.trySend(Cmd("sub", System.currentTimeMillis(), request) {
            var journalId: String? = null
            try {
                journalId = beginSubtitleMutation(folder.mediaId,warningKey)
                val loaded = HelperClient.loadSubtitle(host, port, token, folder.mediaId, file.id)
                finishSubtitleMutation(journalId,false)
                val refreshed = if (loaded) try { HelperClient.subtitleFiles(host, port, token) } catch (_: Exception) { null } else null
                scope.launch {
                    if (request == subtitleRequest && host == store.host && token == store.helperToken) {
                        reconcileSubtitleWarnings()
                        if (loaded) {
                            pickedSubtitle = null
                            pickedSubtitleFile = if (refreshed?.mediaId == folder.mediaId) file.id else null
                            subtitleFolder = refreshed
                            if (refreshed == null || refreshed.unavailable) subtitleMutationError = "Subtitle loaded. Couldn't refresh the list. Tap Retry."
                        } else subtitleMutationError = "Screen woken. Tap the subtitle again to load it."
                        subtitleMutationLoading = false
                    }
                }
            } catch (_: HelperClient.SubtitleRejected) {
                finishSubtitleMutation(journalId,false)
                scope.launch { if (request == subtitleRequest) {
                    subtitleMutationError = "That subtitle selection changed. Refresh the list and try again."
                    subtitleMutationLoading = false
                } }
            } catch (_: Exception) {
                finishSubtitleMutation(journalId,true)
                scope.launch {
                    // A newer intent can hide an old success, but cannot erase an
                    // uncertain mutation that may already have reached VLC.
                    if (confirmedSubtitleMedia == null || confirmedSubtitleMedia == folder.mediaId) {
                        subtitleOutcomeUnknown = true
                        uncertainFolderMedia = folder.mediaId
                        subtitleMutationError = "Couldn't confirm the file load. Existing subtitle controls are still available."
                    }
                    if (request == subtitleRequest) subtitleMutationLoading = false
                }
            }
        })
    }

    fun pickSubtitle(trackId: Int) {
        reconcileSubtitleWarnings()
        subtitleRequest++
        subtitleMutationLoading = false
        val request = subtitleRequest
        val host = store.host; val port = store.helperPort; val token = store.helperToken
        val warningKey = subtitleWarningKey
        val vlcPort = store.vlcPort; val password = store.vlcPassword
        val mediaId = confirmedSubtitleMedia
        if (!subtitleOutcomeUnknown) subtitleMutationError = null
        subtitleMutationLoading = true
        cmds.trySend(Cmd("sub", System.currentTimeMillis(), request) {
            var journalId: String? = null
            var dispatched = false
            var dispatchedMedia: String? = null
            try {
                val currentMedia = try { HelperClient.subtitleMedia(host,port,token) } catch (_: HelperClient.MissingRoute) { null }
                if (trackId != -1 && mediaId != null && currentMedia != null && mediaId != currentMedia) throw HelperClient.SubtitleRejected("movie changed")
                dispatchedMedia = currentMedia ?: confirmedSubtitleMedia
                journalId = beginSubtitleMutation(dispatchedMedia,warningKey)
                dispatched = true
                if (currentMedia == null || !HelperClient.selectSubtitle(host, port, token, trackId, currentMedia)) {
                    if (HelperClient.screenIsDark(host, port, token)) {
                        HelperClient.wakeDisplay(host, port, token)
                        throw HelperClient.SubtitleRejected("wake only")
                    }
                    Vlc.setSubtitleTrack(host, vlcPort, password, trackId)
                }
                finishSubtitleMutation(journalId,false)
                scope.launch {
                    if (request == subtitleRequest) { reconcileSubtitleWarnings(); pickedSubtitleFile = null; if (!subtitleOutcomeUnknown) pickedSubtitle = trackId; subtitleMutationLoading = false }
                }
            } catch (_: HelperClient.SubtitleRejected) {
                finishSubtitleMutation(journalId,false)
                scope.launch { if (request == subtitleRequest) {
                    subtitleMutationError = "The movie or subtitle selection changed. Try again."
                    subtitleMutationLoading = false
                } }
            } catch (_: Exception) {
                if (dispatched) finishSubtitleMutation(journalId,true)
                scope.launch {
                    if (dispatched && (mediaId == null || confirmedSubtitleMedia == null || confirmedSubtitleMedia == mediaId)) {
                        subtitleOutcomeUnknown = true
                        uncertainFolderMedia = dispatchedMedia
                        subtitleMutationError = "Couldn't confirm the subtitle change. You can try the existing controls again."
                    }
                    if (!dispatched && request == subtitleRequest) subtitleMutationError = "Can't reach the PC to change subtitles. Try again."
                    if (request == subtitleRequest) subtitleMutationLoading = false
                }
            }
        })
    }
}
