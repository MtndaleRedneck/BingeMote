package com.bingemote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Starts or stops the lock-screen controls service to match the setting. */
object MediaControls {
    fun sync(context: Context, store: Store) {
        val i = Intent(context, MediaControlsService::class.java)
        if (store.lockScreenControls && store.isConfigured) context.startService(i)
        else context.stopService(i)
    }
}

/**
 * Lock-screen media controls (off by default, Phone 8): while the setting is on and a
 * movie plays, the system media surface shows play/pause, his two skip intervals and the
 * position bar. A plain MediaSession - the system media controls are exempt from the
 * Android 13 notification permission, so no permission prompt and no notification of ours.
 * The service polls VLC itself (the app's own poll stops when the screen is off); every
 * two seconds on the home Wi-Fi, only while the setting is on.
 */
class MediaControlsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: MediaSession? = null
    private var pollJob: Job? = null
    private var failures = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val store = Store(this)
        val s = MediaSession(this, "BingeMote")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { run { Vlc.togglePause(store.host, store.vlcPort, store.vlcPassword) } }
            override fun onPause() { run { Vlc.togglePause(store.host, store.vlcPort, store.vlcPassword) } }
            override fun onRewind() { jump(store, -store.skipSecondarySec) }
            override fun onFastForward() { jump(store, store.skipMainSec) }
            // No onSeekTo and no ACTION_SEEK_TO: the bar shows the place but can't be dragged.
        })
        s.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session = s
    }

    private fun run(block: () -> Unit) {
        scope.launch { try { block(); refreshNow() } catch (_: Exception) {} }
    }

    private fun jump(store: Store, deltaSec: Int) {
        scope.launch {
            try {
                val st = Vlc.status(store.host, store.vlcPort, store.vlcPassword)
                Vlc.seekTo(store.host, store.vlcPort, store.vlcPassword,
                    (st.timeSec + deltaSec).coerceIn(0, st.lengthSec))
                refreshNow()
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollJob?.isActive != true) {
            pollJob = scope.launch {
                while (isActive) {
                    pollOnce()
                    delay(2000)
                }
            }
        }
        return START_STICKY
    }

    @Volatile private var refreshRequested = false
    private fun refreshNow() { refreshRequested = true }

    private suspend fun pollOnce() {
        val store = Store(this)
        if (!store.lockScreenControls || !store.isConfigured) { stopSelf(); return }
        if (refreshRequested) { refreshRequested = false } /* poll right away anyway */
        val st = try {
            Vlc.status(store.host, store.vlcPort, store.vlcPassword)
        } catch (e: Exception) {
            failures++
            // VLC gone for good: drop the controls rather than pose over a dead player.
            if (failures >= 5) session?.isActive = false
            return
        }
        failures = 0
        val s = session ?: return
        val playing = st.playing
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_FAST_FORWARD,
            )
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                st.timeSec * 1000L,
                if (playing) 1f else 0f,
            )
            .setBufferedPosition(st.lengthSec * 1000L)
            .build()
        s.setPlaybackState(state)
        val meta = android.media.MediaMetadata.Builder()
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, st.lengthSec * 1000L)
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, st.title)
            .build()
        s.setMetadata(meta)
        s.isActive = true
    }

    override fun onDestroy() {
        pollJob?.cancel()
        session?.let { it.isActive = false; it.release() }
        session = null
        super.onDestroy()
    }
}
