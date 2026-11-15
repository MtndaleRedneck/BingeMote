package com.bingemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
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
        // The CI screenshot state seeds a demo session with no PC paired.
        val demo = BuildConfig.DEBUG && store.demoControls
        if (store.lockScreenControls && (store.isConfigured || demo)) context.startService(i)
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
/** 34:12 or 1:50:24. */
private fun fmtClockSec(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val ss = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

private const val NOTIF_ID = 101
private const val CHANNEL_ID = "playback"
private const val ACTION_MEDIA_REWIND = "com.bingemote.action.REWIND"
private const val ACTION_MEDIA_FAST_FORWARD = "com.bingemote.action.FAST_FORWARD"
private const val ACTION_MEDIA_PLAY_PAUSE = "com.bingemote.action.PLAY_PAUSE"

class MediaControlsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: MediaSession? = null
    private var callback: MediaSession.Callback? = null
    private var pollJob: Job? = null
    private var failures = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val store = Store(this)
        val s = MediaSession(this, "BingeMote")
        val cb = object : MediaSession.Callback() {
            override fun onPlay() { run { Vlc.togglePause(store.host, store.vlcPort, store.vlcPassword) } }
            override fun onPause() { run { Vlc.togglePause(store.host, store.vlcPort, store.vlcPassword) } }
            override fun onRewind() { jump(store, -store.skipSecondarySec) }
            override fun onFastForward() { jump(store, store.skipMainSec) }
            // The system media surface renders its two extra slots from these actions;
            // they fire his two chosen skip intervals (main forward, secondary back).
            override fun onSkipToPrevious() { jump(store, -store.skipSecondarySec) }
            override fun onSkipToNext() { jump(store, store.skipMainSec) }
            // No onSeekTo and no ACTION_SEEK_TO: the bar shows the place but can't be dragged.
        }
        callback = cb
        s.setCallback(cb)
        s.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        // The system media surface opens the app from this (docs: must launch an activity).
        s.setSessionActivity(
            android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )
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
        // CI screenshot state (debug builds only): seeded controls without a PC.
        when (intent?.action) {
            ACTION_MEDIA_REWIND -> { callback?.onRewind(); return START_STICKY }
            ACTION_MEDIA_FAST_FORWARD -> { callback?.onFastForward(); return START_STICKY }
            ACTION_MEDIA_PLAY_PAUSE -> {
                // Main thread: no network here; the poll keeps lastPlaying fresh.
                if (lastPlaying) callback?.onPause() else callback?.onPlay()
                return START_STICKY
            }
        }
        if (BuildConfig.DEBUG && Store(this).demoControls) { seedDemo(); return START_STICKY }
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
    @Volatile private var lastPlaying = false
    private fun refreshNow() { refreshRequested = true }

    private suspend fun pollOnce() {
        val store = Store(this)
        if (!store.lockScreenControls || !store.isConfigured) { stopSelf(); return }
        if (refreshRequested) { refreshRequested = false } /* poll right away anyway */
        val st = try {
            Vlc.status(store.host, store.vlcPort, store.vlcPassword)
        } catch (e: Vlc.BadAuth) {
            // The one automatic heal: adopt the PC's current password once; never push ours.
            if (VlcAuthRepair.tryHealOnce(store)) return // the next poll uses it
            failures++
            if (failures >= 5) session?.isActive = false
            return
        } catch (e: Exception) {
            failures++
            // VLC gone for good: drop the controls rather than pose over a dead player.
            if (failures >= 5) session?.isActive = false
            return
        }
        failures = 0
        val s = session ?: return
        val playing = st.playing
        lastPlaying = playing
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT,
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
        postMediaNotification(playing, st.timeSec, st.lengthSec, st.title)
    }

    // CI screenshot state (debug builds only): the seeded session as it looks mid-movie.
    private fun seedDemo() {
        val s = session ?: return
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT,
                )
                .setState(PlaybackState.STATE_PLAYING, (34 * 60 + 12) * 1000L, 1f)
                .setBufferedPosition((110 * 60 + 24) * 1000L)
                .build(),
        )
        s.setMetadata(
            android.media.MediaMetadata.Builder()
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, (110 * 60 + 24) * 1000L)
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "demo.mkv")
                .build(),
        )
        s.isActive = true
        postMediaNotification(true, (34 * 60 + 12).toLong(), (110 * 60 + 24).toLong(), "demo.mkv")
    }

    // The system media surface (quick settings / lock screen) is sourced from this
    // media-session notification: it renders as the media card, not a normal shade
    // row, and media-session notifications are exempt from the Android 13
    // notification permission, so it never prompts.
    private fun postMediaNotification(playing: Boolean, timeSec: Long, lengthSec: Long, title: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val s = session ?: return
        val openApp = android.app.PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        fun actionIntent(action: String) = android.app.PendingIntent.getService(
            this, action.hashCode(), Intent(this, MediaControlsService::class.java).setAction(action),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        fun icon(res: Int) = android.graphics.drawable.Icon.createWithResource(this, res)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title?.ifBlank { null } ?: "Playing")
            .setContentText("${fmtClockSec(timeSec)} of ${fmtClockSec(lengthSec)}")
            .setContentIntent(openApp)
            .setOngoing(playing)
            .addAction(Notification.Action.Builder(icon(android.R.drawable.ic_media_rew), "Back 30s", actionIntent(ACTION_MEDIA_REWIND)).build())
            .addAction(
                if (playing)
                    Notification.Action.Builder(icon(android.R.drawable.ic_media_pause), "Pause", actionIntent(ACTION_MEDIA_PLAY_PAUSE)).build()
                else
                    Notification.Action.Builder(icon(android.R.drawable.ic_media_play), "Play", actionIntent(ACTION_MEDIA_PLAY_PAUSE)).build(),
            )
            .addAction(Notification.Action.Builder(icon(android.R.drawable.ic_media_ff), "Forward 30s", actionIntent(ACTION_MEDIA_FAST_FORWARD)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        pollJob?.cancel()
        session?.let { it.isActive = false; it.release() }
        session = null
        super.onDestroy()
    }
}
