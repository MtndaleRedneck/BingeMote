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
    // The pairing generation that PRODUCED the displayed status (its subtitle track
    // list): a displayed track choice belongs to the listing's pairing, not the tap's.
    // -1L = screenshot-rig demo seed sentinel; null = no provenance (fail closed on use).
    var statusGen by mutableStateOf<Long?>(null)
        private set
    var unreachable by mutableStateOf(false)
        private set

    /** Either service answering proves the PC is reachable, independently of VLC playback. */
    val pcReachable: Boolean get() = !unreachable || (store.helperToken.isNotBlank() && !helperDown)

    /** Status as the home screen presents it. The raw status is the last good poll and
     *  survives a dropped connection so a blip loses nothing; but once the PC is
     *  unreachable (or VLC itself is gone/refused) the seek bar, times and play glyph
     *  must read zero/play instead of that stale playback. The cached status itself stays
     *  for an instant recovery when the connection returns. */
    val shownStatus: VlcStatus?
        get() = if (unreachable || vlcClosed || vlcRefused) null else status

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
    /** The PC presented a different security key than the one remembered at pairing. Never cleared by a retry: only a fresh pairing replaces the record. */
    var keyChanged by mutableStateOf(false)
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
     *  rediscovery while the mouse screen is open never recomposes it - publish here instead.
     *  Starts BLANK (B2: no Main secure reads at construction); the init below publishes the
     *  stored address from one locked IO snapshot+generation capture. */
    var liveHost by mutableStateOf(""); private set
    var livePort by mutableStateOf(HelperClient.DEFAULT_PORT); private set
    var liveToken by mutableStateOf(""); private set

    /** Pairing, re-pairing or rediscovery learned an address: open screens reconnect to it. */
    fun publishAddress(host: String, port: Int, token: String) {
        liveHost = host; livePort = port; liveToken = token
    }

    /** Screenshot-rig only: a fake playing video so seek-drag captures show real times. */
    fun debugSeedStatus() {
        if (BuildConfig.DEBUG) { status = VlcStatus("playing", "Demo", 1500, 6624, 128, false, emptyList()); statusGen = -1L }
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
        // ONE locked pairing snapshot per cycle, captured on IO so Main never waits on
        // pairingLock. The storage-free mutation generation brackets the capture AND is
        // re-checked at EVERY async outcome's publish moment below: a re-pair, forget,
        // rediscovery or heal landing anywhere in the cycle drops that outcome - no
        // IO-check-then-unguarded-publish, and no stale outcome applied to a new pairing.
        val gen0 = store.pairingGeneration
        val snap = withContext(Dispatchers.IO) { store.pollSnapshot() }
        if (store.pairingGeneration != gen0) return
        // The helper-notice state belongs to the pairing it was learned from: a new
        // or cleared token makes it stale. Reset it here so a re-paired phone never
        // sees "pair again" (or a leftover helper warning) about its NEW pairing.
        // Runs before the isConfigured early-return so a cleared token also resets.
        if (noticeToken != snap.helperToken) {
            noticeToken = snap.helperToken
            unpaired = false
            helperNotice = false
            helperDown = false
            helperFailures = 0
            helperDownAt = 0L
        }
        // The same four conditions as Store.isConfigured, from the one snapshot; a poisoned
        // store is never configured (no false green from partially-"" reads).
        if (snap.broken || !(store.setupDone && snap.secure && snap.host.isNotBlank() && snap.vlcPassword.isNotBlank())) return
        // Status and Windows volume in parallel: a blocked helper used to stall the whole
        // poll (time and seek bar only updated every ~11s when VLC answered but the helper didn't).
        // While VLC is closed, re-check it every 4th cycle instead of every cycle (fewer
        // dead-end requests); the helper ping still runs every cycle either way.
        val skipVlc = vlcClosed && (closedPolls++ % CmdPolicy.CLOSED_POLL_EVERY != 0)
        // The cycle's VLC target comes from the SAME locked snapshot as the helper identity:
        // an old cycle never consumes a fresh new-pairing target mid-flight.
        val cycleTarget = if (!snap.broken && snap.host.isNotBlank()) VlcTarget(snap.host, snap.vlcPort, snap.vlcPassword, snap.secure) else null
        val vlcDeferred = if (skipVlc) null
            else scope.async { twiceIO { val vt = cycleTarget ?: throw Vlc.Unreachable("Could not reach VLC."); Vlc.status(vt.host, vt.port, vt.password) } }
        val volDeferred = if (snap.helperToken.isNotBlank())
            scope.async {
                try { twiceIO { HelperClient.volumeGet(snap.host, snap.helperPort, snap.helperToken) } }
                catch (e: HelperClient.BadToken) { throw e }
                catch (e: HelperClient.KeyChanged) { throw e }
                catch (e: Exception) { null }
            }
        else null
        // The helper's answer counts even when VLC's does not: the volume reading used to
        // be thrown away on the VLC-failure path, leaving the row stuck at its last value.
        if (volDeferred != null) {
            var volBadToken = false; var volKeyChanged = false
            val vm = try { volDeferred.await() } catch (e: HelperClient.BadToken) { volBadToken = true; null } catch (e: HelperClient.KeyChanged) { volKeyChanged = true; null }
            // Generation re-check at THIS outcome's publish moment: a re-pair/forget/rediscovery
            // completing while the volume call was in flight makes every result above stale.
            if (store.pairingGeneration != gen0) return
            keyChanged = KeyChangeState.isChanged(snap.pcId)   // from the cycle's own snapshot, never cleared by a good poll
            if (volBadToken) unpaired = true
            if (volKeyChanged) keyChanged = true
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
        // A cached VLC-closed state must not mask a subsequent loss of the PC.
        // Helper liveness is checked on every poll, including the skipped VLC cycles.
        if (helperDown && vlcClosed) vlcClosed = false
        if (skipVlc) return
        var vlcErr: Exception? = null
        val s = try { vlcDeferred!!.await() } catch (e: Exception) { vlcErr = e; null }
        // Generation re-check at THIS outcome's publish moment: a re-pair/forget/rediscovery
        // completing while the VLC status call was in flight makes the result stale - the old
        // cycle drops it (no heal, no rediscovery) instead of consuming a fresh target.
        if (store.pairingGeneration != gen0) return
        if (s != null) {
            status = s
            statusGen = gen0
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
                subtitleFolderGen = null
            }
            failures = 0
            unreachable = false
            vlcRefused = false
            vlcClosed = false
            closedPolls = 0
            connecting = false
            expectingVlc = false
        } else if (vlcErr is Vlc.BadAuth) {
            // The one automatic heal: adopt the PC's current password once. The phone never
            // pushes its stored copy at the PC - a refusal can be an intentional PC change.
            val healOutcome = withContext(Dispatchers.IO) {
                val wrotePw = VlcAuthRepair.tryHealOnce(store)
                // Atomically after the heal: is the store a state THIS CYCLE owns? The
                // ORIGINAL full record encode (pcId|pin|host|port|token) from this cycle's
                // snapshot, plus exactly the password this cycle owns - the captured original
                // when the heal wrote nothing, or THIS heal's adopted value when it wrote. A
                // same-encode newer-password save (before or after the heal's write) and an
                // original restored by another writer both fail: no outcome may be stamped
                // current for a store state this cycle never proved. The generation rides
                // along ONLY to guard the IO->Main hop (re-checked below).
                synchronized(pairingLock) {
                    val enc = (store.helperPairing() as? PairedCredential.State.Secure)?.credential?.encode()
                    val pw = store.helperVlcSnapshot()?.vlcPassword
                    Triple(wrotePw, VlcAuthRepair.postHealOwnershipOk(snap.encode, snap.vlcPassword, wrotePw, enc, pw), store.pairingGeneration)
                }
            }
            // Publish only when the locked section above proved a state this cycle OWNS AND
            // nothing newer landed on the way to Main.
            if (!healOutcome.second || store.pairingGeneration != healOutcome.third) return
            lastConnectedAt = 0L
            if (healOutcome.first != null) { vlcRefused = false; return } // healed: the next poll uses the fresh password
            vlcRefused = true
            failures = UNREACHABLE_AFTER
            unreachable = true
        } else {
            lastConnectedAt = 0L
            failures++
            if (failures >= UNREACHABLE_AFTER) {
                // The PC answering its helper but not VLC means VLC simply isn't open.
                // The ping is a nested IO await after this cycle's guards: re-check the
                // generation before ANY publish below, or vlcClosed/unreachable would
                // describe the old pairing.
                val helperUp = snap.helperToken.isNotBlank() &&
                    withContext(Dispatchers.IO) {
                        HelperClient.ping(snap.host, snap.helperPort, snap.helperToken) ||
                            HelperClient.ping(snap.host, snap.helperPort, snap.helperToken)
                    }
                if (store.pairingGeneration != gen0) return
                unreachable = true
                vlcClosed = helperUp
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
                // ONE capture for both pings (uniform with the command sequences); the address
                // write below stays gen-gated under pairingLock exactly as before.
                val seq = store.pairingSequence()
                if (seq != null && HelperClient.ping(seq.snapshot.host, seq.snapshot.helperPort, seq.snapshot.helperToken)) {
                    failures = 0
                    return@launch
                }
                val found = HelperClient.discover(3000)
                // A SECURE pairing moves only to an address that proves the stored key and
                // answers with the stored token (applySecureRediscovery); the record is the
                // identity, never the discovery data. The plain ping below runs only for
                // legacy records, which read as "pair again" anyway - their behavior is
                // unchanged.
                if (store.pollSnapshot().secure) {
                    val r = applySecureRediscovery(store, found)
                    if (r != null) {
                        withContext(Dispatchers.Main) {
                            // Check+publish are ONE atomic section under pairingLock (the CAS
                            // captured the generation under that same lock): a repair or forget
                            // can never slip between them and lend the old address and token to
                            // the new pairing.
                            if (store.runIfPairingCurrent(r.generation) { publishAddress(r.host, r.port, r.token) } != null) {
                                failures = 0
                            }
                        }
                    }
                } else {
                    // The pairing token is the legacy PC's identity; its name is only a label.
                    val match = found.firstOrNull { HelperClient.ping(it.host, it.port, seq?.snapshot?.helperToken ?: "") }
                    if (match != null) {
                        // Storage writes on IO (the locked setters do disk IO and wait on pairingLock);
                        // the token read rides the SAME locked section, so (writes, token, generation)
                        // are one atomic unit. Main receives only the in-memory state publish, gated by
                        // the storage-free generation captured with it: a newer mutation drops the publish.
                        val applied = withContext(Dispatchers.IO) {
                            synchronized(pairingLock) {
                                store.host = match.host
                                store.helperPort = match.port
                                store.pairingGeneration to store.helperToken
                            }
                        }
                        withContext(Dispatchers.Main) {
                            if (store.runIfPairingCurrent(applied.first) { publishAddress(match.host, match.port, applied.second) } != null) {
                                failures = 0
                            }
                        }
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
        // B2: the stored address publishes from ONE locked snapshot+generation capture; the
        // generation check and the Main publish are ONE atomic section under pairingLock, so a
        // re-pair during startup never shows the old pairing's address as current. On drift
        // the capture repeats on IO (bounded, 3 recaptures): a single mid-startup heal write
        // is absorbed. On persistent drift the address stays blank - LiveWs already fails
        // closed on blank, and the rediscovery-driven publish is the only later recovery
        // (named residual: a reachable-but-never-republished PC needs a process restart).
        scope.launch(Dispatchers.IO) {
            var capture = store.helperVlcSnapshotWithGeneration()
            var attempts = 0
            while (true) {
                val (snap, gen) = capture
                if (snap == null) return@launch
                val published = withContext(Dispatchers.Main) {
                    store.runIfPairingCurrent(gen) { publishAddress(snap.host, snap.helperPort, snap.helperToken) } != null
                }
                if (published || attempts >= 3) return@launch
                attempts++
                capture = store.helperVlcSnapshotWithGeneration()
            }
        }
    }
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
                try { c.block() }
                catch (e: HelperClient.KeyChanged) { keyChanged = true }
                catch (_: Exception) { failures++; if (failures >= UNREACHABLE_AFTER) unreachable = true }
            }
        }
    }

    private fun fire(kind: String, block: (PairingSequence) -> Unit) {
        // First press while the PC's screen is dark only wakes it - the tap itself does
        // nothing. VLC-direct commands are gated here; helper-routed
        // ones are also swallowed PC-side, so one tap never half-lands. When the screen
        // state can't be learned, the tap is wake-only too (fail-safe, settled with Tab).
        // ONE capture owns the whole command: the gate and the command target the SAME
        // pairing, and a re-pair between gate and send drops the command like an expired
        // tap instead of redirecting it onto the new pairing. A null sequence (poisoned
        // store, no host) maps to the same honest unreachable a dead VLC produces.
        val gated: () -> Unit = gated@ {
            val seq = store.pairingSequence() ?: throw Vlc.Unreachable("Could not reach VLC.")
            val s = seq.snapshot
            if (HelperClient.screenIsDark(s.host, s.helperPort, s.helperToken)) {
                HelperClient.wakeDisplay(s.host, s.helperPort, s.helperToken)
            } else {
                if (!seq.isCurrent()) { Diag.cmdsPairingDropped.incrementAndGet(); return@gated }
                block(seq)
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
        fire("playpause") { seq -> val s = seq.snapshot; Vlc.togglePause(s.host, s.vlcPort, s.vlcPassword) }
    }
    fun toggleFullscreen() = fire("fullscreen") { seq -> val s = seq.snapshot; Vlc.toggleFullscreen(s.host, s.vlcPort, s.vlcPassword) }
    fun skip(seconds: Int) = fire("skip") { seq -> val s = seq.snapshot; Vlc.skip(s.host, s.vlcPort, s.vlcPassword, seconds) }
    fun seekTo(seconds: Long) {
        if (BuildConfig.DEBUG) Log.i("BMSeek", "seekTo seconds=$seconds")
        fire("seek") { seq -> val s = seq.snapshot; Vlc.seekTo(s.host, s.vlcPort, s.vlcPassword, seconds) }
    }

    fun volumeStep(up: Boolean) {
        val win = winVolume
        if (win != null) {
            // Windows system volume via the helper (recommended pick; the default was chosen).
            val base = win
            val wasMuted = muted
            if (wasMuted) muted = false
            val next = (base + if (up) 5 else -5).coerceIn(0, 100)
            winVolume = next
            localVolumeAt = System.currentTimeMillis()
            fire("volume") { seq ->
                val s = seq.snapshot
                if (!seq.isCurrent()) { Diag.cmdsPairingDropped.incrementAndGet(); return@fire }
                HelperClient.volumeSet(s.host, s.helperPort, s.helperToken, next)
                // Unmute only when it WAS muted - a plain volume step must not touch the mute switch.
                if (wasMuted) {
                    if (!seq.isCurrent()) { Diag.cmdsPairingDropped.incrementAndGet(); return@fire }
                    HelperClient.volumeMute(s.host, s.helperPort, s.helperToken, false)
                }
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
            fire("volume") { seq -> val s = seq.snapshot; if (!seq.isCurrent()) { Diag.cmdsPairingDropped.incrementAndGet(); return@fire }; Vlc.setVolume(s.host, s.vlcPort, s.vlcPassword, next) }
        } else {
            val cur = s?.volumeRaw ?: return
            val next = (cur + if (up) Vlc.VOLUME_STEP else -Vlc.VOLUME_STEP).coerceIn(0, Vlc.VOLUME_100)
            // Echo the tap on the row right away - with the PC unreachable the poll can't
            // do it, and a tap that changes nothing feels dead. The next good poll overwrites.
            status = s.copy(volumeRaw = next)
            fire("volume") { seq -> val s = seq.snapshot; if (!seq.isCurrent()) { Diag.cmdsPairingDropped.incrementAndGet(); return@fire }; Vlc.setVolume(s.host, s.vlcPort, s.vlcPassword, next) }
        }
    }

    fun toggleMute() {
        if (winVolume != null) {
            val next = !muted
            muted = next
            localVolumeAt = System.currentTimeMillis()
            fire("volume") { seq -> val s = seq.snapshot; if (!seq.isCurrent()) { Diag.cmdsPairingDropped.incrementAndGet(); return@fire }; HelperClient.volumeMute(s.host, s.helperPort, s.helperToken, next) }
            return
        }
        if (muted) {
            muted = false
            val back = preMuteRaw.coerceIn(1, Vlc.VOLUME_100)
            fire("volume") { seq -> val s = seq.snapshot; if (!seq.isCurrent()) { Diag.cmdsPairingDropped.incrementAndGet(); return@fire }; Vlc.setVolume(s.host, s.vlcPort, s.vlcPassword, back) }
        } else {
            preMuteRaw = (status?.volumeRaw ?: Vlc.VOLUME_100 / 2).coerceIn(1, Vlc.VOLUME_100 * 2)
            muted = true
            fire("volume") { seq -> val s = seq.snapshot; if (!seq.isCurrent()) { Diag.cmdsPairingDropped.incrementAndGet(); return@fire }; Vlc.setVolume(s.host, s.vlcPort, s.vlcPassword, 0) }
        }
    }

    var subtitleFolder by mutableStateOf<HelperClient.SubtitleFolder?>(null); private set
    // The pairing generation that PRODUCED the displayed folder listing (same origin rule
    // as statusGen; both mirror the MoviesScreen listingGen binding).
    var subtitleFolderGen by mutableStateOf<Long?>(null); private set
    private var subtitleListLoading by mutableStateOf(false)
    private var subtitleMutationLoading by mutableStateOf(false)
    val subtitleFilesLoading get() = subtitleListLoading || subtitleMutationLoading
    private var subtitleListError by mutableStateOf<String?>(null)
    private var subtitleMutationError by mutableStateOf<String?>(null)
    val subtitleFileError get() = subtitleMutationError ?: subtitleListError
    private var confirmedSubtitleMedia: String? = null
    private val subtitleJournal = SubtitleJournal(app)
    private val subtitleWarningKey: String get() = subtitleWarningKeyFor(store.helperToken)
    private fun subtitleWarningKeyFor(token: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(("subtitle-v1:$token").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    var subtitleOutcomeUnknown by mutableStateOf(false); private set
    private var uncertainFolderMedia: String? = null
    private var uncertainSubtitleMedia: Set<String> = emptySet()
    private var loadedWarningKey: String? = null
    private var warningReadInFlight = false
    private var warningReadAgain = false
    @Volatile private var warningGeneration = 0
    private suspend fun readSubtitleWarnings(key:String,current:String?): Pair<List<SubtitleJournal.Entry>,Boolean> = withContext(Dispatchers.IO){
        try{
            subtitleJournal.entries(key) to subtitleJournal.isDegraded(key)
        }catch(_:Exception){emptyList<SubtitleJournal.Entry>() to true}
    }
    private fun applySubtitleWarnings(key:String,current:String?,result:Pair<List<SubtitleJournal.Entry>,Boolean>): Boolean {
        if(key!=subtitleWarningKey || current!=confirmedSubtitleMedia)return false
        val records=result.first
        val relevant=records.filter{current.isNullOrEmpty() || it.media.isEmpty() || it.media==current || it.phase=="pending"}
        val wasUnknown=subtitleOutcomeUnknown
        subtitleOutcomeUnknown=result.second || relevant.isNotEmpty()
        uncertainSubtitleMedia=relevant.map{it.media}.toSet()
        uncertainFolderMedia=relevant.firstOrNull{it.media==current}?.media ?: relevant.firstOrNull()?.media
        if(result.second)subtitleMutationError="Subtitle safety history couldn't be read. New file loads are paused because their previous outcome cannot be established. Existing controls remain best-effort."
        else if(subtitleOutcomeUnknown)subtitleMutationError="Couldn't confirm the previous subtitle change. Existing controls are still available."
        else if(wasUnknown)subtitleMutationError=null
        return true
    }
    private fun reconcileSubtitleWarnings() {
        val key=subtitleWarningKey
        if(loadedWarningKey!=key){
            loadedWarningKey=key;confirmedSubtitleMedia=null;pickedSubtitle=null;pickedSubtitleFile=null
            subtitleFolder=null;subtitleRequest++;subtitleListRequest++;warningGeneration++
            subtitleListLoading=false;subtitleMutationLoading=false;subtitleMutationError=null
            subtitleOutcomeUnknown=false;uncertainFolderMedia=null;uncertainSubtitleMedia=emptySet()
        }
        if(warningReadInFlight){warningReadAgain=true;return}
        warningReadInFlight=true
        scope.launch {
            try {
                do {
                    warningReadAgain=false
                    val readKey=subtitleWarningKey;val current=confirmedSubtitleMedia
                    val generation=warningGeneration;val mutation=subtitleRequest
                    val result=readSubtitleWarnings(readKey,current)
                    if(generation==warningGeneration && mutation==subtitleRequest)applySubtitleWarnings(readKey,current,result)
                    else warningReadAgain=true
                } while(warningReadAgain)
            } finally { warningReadInFlight=false }
        }
    }
    private fun beginSubtitleMutation(media: String?, key: String, folderLoad:Boolean=false): String = try { subtitleJournal.begin(key,media,folderLoad) }
        catch (_: Exception) { throw HelperClient.SubtitleRejected("Couldn't save subtitle safety state. Try again.") }
    private fun finishSubtitleMutation(id: String?, unknown: Boolean) {
        if (id != null) { subtitleJournal.finish(id,unknown);warningGeneration++ }
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
        statusGen = -1L
        subtitleFolder = HelperClient.SubtitleFolder("demo", listOf(
            HelperClient.SubtitleFile("demo-en", "Movie.en.srt"),
            HelperClient.SubtitleFile("demo-es", "Movie.es.srt")), false)
        subtitleFolderGen = -1L
        pickedSubtitleFile = "demo-en"
    }
    /** Screenshot-rig only: seeds the leftover list message a prior unavailable/
     *  truncated result would have set, so tests can prove a later failed check
     *  clears it. Debug builds only. */
    internal fun debugSeedSubtitleListError() {
        if (!BuildConfig.DEBUG) return
        subtitleListError = "Can't check subtitle files right now."
    }
    fun refreshSubtitleFiles(quiet: Boolean = false) {
        reconcileSubtitleWarnings()
        if (BuildConfig.DEBUG && subtitleFolder?.mediaId == "demo") return
        if (subtitleListInFlight) return
        subtitleListInFlight = true
        val request = ++subtitleListRequest
        val mutationAtStart = subtitleRequest
        if (!quiet) { subtitleListLoading = true; subtitleListError = null }
        val job = scope.launch {
            // Read-only refresh: ONE coherent snapshot+generation captured at EXECUTION time
            // on IO (no tap binding). The publishes below require THIS capture's generation,
            // so a re-pair mid-fetch never publishes the old PC's folder as current.
            // Cleanup is now guaranteed two ways: the OUTER finally wraps the capture too
            // (a capture failure used to skip the inner finally and wedge the in-flight
            // flag), and invokeOnCompletion covers a launch cancelled before its body
            // ever ran (no body, no finally). Both cleanups are idempotent.
            try {
            val (snap, capGen) = withContext(Dispatchers.IO) { store.helperVlcSnapshotWithGeneration() }
            try {
                val folder = withContext(Dispatchers.IO) { HelperClient.subtitleFiles(snap?.host ?: "", snap?.helperPort ?: 8488, snap?.helperToken ?: "") }
                if (request == subtitleListRequest && mutationAtStart == subtitleRequest && store.pairingGeneration == capGen) {
                    if (!folder.unavailable && folder.mediaId.isNotEmpty() && confirmedSubtitleMedia != folder.mediaId) { pickedSubtitleFile = null; pickedSubtitle = null }
                    if (!folder.unavailable && folder.mediaId.isNotEmpty()) {
                        confirmedSubtitleMedia = folder.mediaId
                        reconcileSubtitleWarnings()
                    }
                    subtitleFolder = folder
                    subtitleFolderGen = capGen
                    subtitleListError = null
                    if (folder.unavailable) subtitleListError = "Can't check subtitle files right now."
                    else if (folder.truncated) subtitleListError = "This folder is large. Only some subtitle files are shown."
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (request == subtitleListRequest && mutationAtStart == subtitleRequest && store.pairingGeneration == capGen) {
                    // No connection to the PC (or an older PC app without folder
                    // subtitles): folder entries simply stay hidden, exactly like
                    // before the feature existed. No error line, no Retry. A leftover
                    // message from an earlier unavailable/truncated result is stale
                    // content and must go too, or it would render an error/Retry the
                    // current failure did not produce.
                    subtitleFolder = null
                    subtitleFolderGen = null
                    // Quiet only when the PC is gone (raw socket failure), too old
                    // (MissingRoute), or unpaired (BadToken - the main screen owns
                    // that notice). An ANSWERING helper whose check failed keeps an
                    // honest message + Retry, same wording as its unavailable answer.
                    subtitleListError = when (e) {
                        is HelperClient.MissingRoute, is HelperClient.BadToken, is java.io.IOException -> null
                        else -> "Can't check subtitle files right now."
                    }
                }
            } finally { subtitleListInFlight = false; if (request == subtitleListRequest) subtitleListLoading = false }
            } finally { subtitleListInFlight = false; if (request == subtitleListRequest) subtitleListLoading = false }
        }
        job.invokeOnCompletion { subtitleListInFlight = false; if (request == subtitleListRequest) subtitleListLoading = false }
    }
    fun loadSubtitleFile(file: HelperClient.SubtitleFile) {
        reconcileSubtitleWarnings()
        val folder = subtitleFolder ?: return
        // The file names an entry in the DISPLAYED listing, so the binding generation is
        // the listing's ORIGIN, never a fresh tap capture: an off-Main writer landing
        // between the check and a fresh capture would stamp this old-listing item with
        // the NEW pairing's generation (the MoviesScreen listingGen rule). Null origin =
        // no PC provenance: fail closed. The -1L demo sentinel and a live origin pass.
        val originGen = subtitleFolderGen
        if (originGen != -1L && (originGen == null || originGen != store.pairingGeneration)) {
            subtitleMutationError = "This subtitle list is out of date. Tap Retry to refresh it."
            return
        }
        if (folder.mediaId in uncertainSubtitleMedia || "" in uncertainSubtitleMedia) {
            subtitleMutationError = "Couldn't confirm the file load. Existing subtitle controls are still available."
            return
        }
        val request = ++subtitleRequest
        // Every send and publication below binds to the listing's ORIGIN generation
        // (checked above): the pairing the displayed file came from. The demo sentinel
        // falls back to a live capture (storage-free volatile read, no Main lock wait).
        val tapGen = if (originGen == -1L) store.pairingGeneration else originGen
        subtitleMutationError = null
        subtitleMutationLoading = true
        // Same ordered command lane as Off/loaded-track selection. A later Off cannot
        // overtake a slow add and then be undone when that add eventually lands.
        cmds.trySend(Cmd("sub", System.currentTimeMillis(), request) {
            var journalId: String? = null
            try {
                // ONE coherent snapshot+generation captured HERE on IO: a re-pair between
                // the tap and this capture drops the command instead of sending across.
                val (snap, snapGen) = store.helperVlcSnapshotWithGeneration()
                if (snap == null || snapGen != tapGen) throw HelperClient.SubtitleRejected("pairing changed")
                val host = snap.host; val port = snap.helperPort; val token = snap.helperToken
                val warningKey = subtitleWarningKeyFor(token)
                journalId = beginSubtitleMutation(folder.mediaId,warningKey,true)
                val loaded = HelperClient.loadSubtitle(host, port, token, folder.mediaId, file.id)
                finishSubtitleMutation(journalId,false)
                val refreshed = if (loaded) try { HelperClient.subtitleFiles(host, port, token) } catch (_: Exception) { null } else null
                scope.launch {
                    if (request == subtitleRequest && store.pairingGeneration == tapGen) {
                        val current=confirmedSubtitleMedia
                        val result=readSubtitleWarnings(warningKey,current)
                        if(request!=subtitleRequest || store.pairingGeneration != tapGen)return@launch
                        if(!applySubtitleWarnings(warningKey,current,result))return@launch
                        if (loaded) {
                            pickedSubtitle = null
                            pickedSubtitleFile = if (refreshed?.mediaId == folder.mediaId) file.id else null
                            subtitleFolder = refreshed
                            subtitleFolderGen = if (refreshed == null) null else tapGen
                            if (refreshed == null || refreshed.unavailable) subtitleMutationError = "Subtitle loaded. Couldn't refresh the list. Tap Retry."
                        } else subtitleMutationError = "Screen woken. Tap the subtitle again to load it."
                        subtitleMutationLoading = false
                    }
                }
            } catch (_: HelperClient.SubtitleRejected) {
                finishSubtitleMutation(journalId,false)
                scope.launch { if (store.pairingGeneration == tapGen && request == subtitleRequest) {
                    subtitleMutationError = "That subtitle selection changed. Refresh the list and try again."
                    subtitleMutationLoading = false
                } }
            } catch (_: Exception) {
                finishSubtitleMutation(journalId,true)
                scope.launch {
                    // A newer intent can hide an old success, but cannot erase an
                    // uncertain mutation that may already have reached VLC.
                    if (store.pairingGeneration == tapGen && (confirmedSubtitleMedia == null || confirmedSubtitleMedia == folder.mediaId)) {
                        subtitleOutcomeUnknown = true
                        uncertainFolderMedia = folder.mediaId
                        uncertainSubtitleMedia = uncertainSubtitleMedia + folder.mediaId
                        subtitleMutationError = "Couldn't confirm the file load. Existing subtitle controls are still available."
                    }
                    if (store.pairingGeneration == tapGen && request == subtitleRequest) subtitleMutationLoading = false
                }
            }
        })
    }

    fun pickSubtitle(trackId: Int) {
        reconcileSubtitleWarnings()
        subtitleRequest++
        subtitleMutationLoading = false
        val request = subtitleRequest
        // A TRACK choice names a row of the DISPLAYED status track list, so its binding
        // generation is the status's ORIGIN (same rule as the folder listing above).
        // Off (-1) is not listing-derived and keeps a live capture. Null origin = no
        // provenance: fail closed. The -1L demo sentinel and a live origin pass.
        val originGen = if (trackId == -1) -1L else statusGen
        if (originGen != -1L && (originGen == null || originGen != store.pairingGeneration)) {
            // The sheet's Retry refreshes the FOLDER list, not the track status (tracks
            // come from the poll) - so no Retry promise here, just an honest retry-later.
            subtitleMutationError = "This subtitle list is out of date. Try again in a moment."
            return
        }
        val tapGen = if (originGen == -1L) store.pairingGeneration else originGen
        val mediaId = confirmedSubtitleMedia
        if (!subtitleOutcomeUnknown) subtitleMutationError = null
        subtitleMutationLoading = true
        cmds.trySend(Cmd("sub", System.currentTimeMillis(), request) {
            var journalId: String? = null
            var dispatched = false
            var dispatchedMedia: String? = null
            try {
                // ONE coherent snapshot+generation captured HERE on IO: a re-pair between
                // the tap and this capture drops the command instead of sending across.
                val (snap, snapGen) = store.helperVlcSnapshotWithGeneration()
                if (snap == null || snapGen != tapGen) throw HelperClient.SubtitleRejected("pairing changed")
                val host = snap.host; val port = snap.helperPort; val token = snap.helperToken
                val warningKey = subtitleWarningKeyFor(token)
                val currentMedia = try { HelperClient.subtitleMedia(host,port,token) } catch (_: HelperClient.MissingRoute) { null }
                if (trackId != -1 && mediaId != null && currentMedia != null && mediaId != currentMedia) throw HelperClient.SubtitleRejected("movie changed")
                dispatchedMedia = currentMedia ?: confirmedSubtitleMedia
                try { journalId = beginSubtitleMutation(dispatchedMedia,warningKey) }
                catch (_: HelperClient.SubtitleRejected) {
                    // Existing track controls remain usable when history storage is
                    // unavailable, but cannot claim a confirmed durable outcome.
                    subtitleJournal.markDegraded(warningKey)
                    scope.launch { if(store.pairingGeneration == tapGen){
                        subtitleOutcomeUnknown=true
                        subtitleMutationError="Subtitle history couldn't be saved. Existing controls are best-effort; the selected state is unconfirmed."
                    } }
                }
                dispatched = true
                if (currentMedia == null || !HelperClient.selectSubtitle(host, port, token, trackId, currentMedia)) {
                    if (HelperClient.screenIsDark(host, port, token)) {
                        HelperClient.wakeDisplay(host, port, token)
                        throw HelperClient.SubtitleRejected("wake only")
                    }
                    // The VLC-direct fallback target comes from the SAME guarded capture
                    // above, never a fresh store read after the guard: a re-pair landing
                    // between the two would retarget this pairing's track choice at the NEW
                    // PC's VLC. snap is already null-checked, which covers vlcTarget()'s
                    // unreachable cases (blank host / poisoned store) one for one.
                    Vlc.setSubtitleTrack(snap.host, snap.vlcPort, snap.vlcPassword, trackId)
                }
                finishSubtitleMutation(journalId,false)
                scope.launch {
                    if (store.pairingGeneration == tapGen && request == subtitleRequest) {
                        val current=confirmedSubtitleMedia
                        val result=readSubtitleWarnings(warningKey,current)
                        if(request!=subtitleRequest || store.pairingGeneration != tapGen)return@launch
                        if(!applySubtitleWarnings(warningKey,current,result))return@launch
                        pickedSubtitleFile=null;if(!subtitleOutcomeUnknown)pickedSubtitle=trackId
                        subtitleMutationLoading=false
                    }
                }
            } catch (_: HelperClient.SubtitleRejected) {
                finishSubtitleMutation(journalId,false)
                scope.launch { if (store.pairingGeneration == tapGen && request == subtitleRequest) {
                    subtitleMutationError = "The movie or subtitle selection changed. Try again."
                    subtitleMutationLoading = false
                } }
            } catch (_: Exception) {
                if (dispatched) finishSubtitleMutation(journalId,true)
                scope.launch {
                    if (store.pairingGeneration == tapGen && dispatched && (mediaId == null || confirmedSubtitleMedia == null || confirmedSubtitleMedia == mediaId)) {
                        subtitleOutcomeUnknown = true
                        uncertainFolderMedia = dispatchedMedia
                        uncertainSubtitleMedia = uncertainSubtitleMedia + dispatchedMedia.orEmpty()
                        subtitleMutationError = "Couldn't confirm the subtitle change. You can try the existing controls again."
                    }
                    if (store.pairingGeneration == tapGen && !dispatched && request == subtitleRequest) subtitleMutationError = "Can't reach the PC to change subtitles. Try again."
                    if (store.pairingGeneration == tapGen && request == subtitleRequest) subtitleMutationLoading = false
                }
            }
        })
    }
}


/** The verified result of a secure rediscovery: the endpoint that proved the stored identity
 *  plus the post-CAS pairing generation for the publish gate. */
internal class RediscoveredEndpoint(val host: String, val port: Int, val token: String, val generation: Long)

/** One seam for the whole secure-rediscovery decision, used by RemoteState.rediscover and by
 *  tests: probe each discovered candidate against the STORED identity (pin + token; discovery
 *  data only ever picks candidates) and CAS the first proof into the record. Returns the
 *  verified endpoint with the generation captured under the CAS lock, or null when nothing
 *  moved (no proof, record changed underneath, store latched). Never publishes itself. */
internal fun applySecureRediscovery(store: Store, candidates: List<HelperClient.DiscoveredHelper>): RediscoveredEndpoint? {
    val snap = store.pollSnapshot()
    val expected = if (snap.secure) snap.encode else null
    val cred = expected?.let { PairedCredential.decode(it) } ?: return null
    val match = candidates.firstOrNull { HelperClient.probePaired(cred, it.host, it.port) } ?: return null
    val gen = store.updateSecureAddressAndGeneration(expected, match.host, match.port) ?: return null
    return RediscoveredEndpoint(match.host, match.port, cred.token, gen)
}
