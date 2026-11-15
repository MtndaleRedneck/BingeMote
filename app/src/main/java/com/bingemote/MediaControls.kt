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
private const val ACTION_MEDIA_VOL_DOWN = "com.bingemote.action.VOL_DOWN"
private const val ACTION_MEDIA_VOL_UP = "com.bingemote.action.VOL_UP"
private const val ACTION_DEMO_ROW = "com.bingemote.action.DEMO_ROW" // debug builds only
private const val NOTIF_ROW_ID = 102

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
            // State-driven, not a blind toggle: each surface asks for the state it
            // means (play or pause), and the command is idempotent, so a stale cached
            // state or a double tap can never flip playback the wrong way (Ezzy's
            // dead/inverting pause button was the old toggle guessing wrong).
            override fun onPlay() { run { Vlc.setPaused(store.host, store.vlcPort, store.vlcPassword, false) } }
            override fun onPause() { run { Vlc.setPaused(store.host, store.vlcPort, store.vlcPassword, true) } }
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

    /** One volume button step (5%). Same contract as the in-app volume row
     * (RemoteState.volumeStep): when the helper answers, drive WINDOWS volume (and
     * unmute only if it was muted); otherwise drive VLC volume with the shared mute
     * restore, clamped at 100% like the row (LOW1/LOW2 - the bar used to bypass both
     * and could push VLC to 200%). */
    private fun volumeBy(store: Store, delta: Int) {
        scope.launch {
            try {
                val helper = if (store.helperToken.isNotBlank())
                    try { HelperClient.volumeGet(store.host, store.helperPort, store.helperToken) } catch (_: Exception) { null }
                else null
                if (helper != null) {
                    val (win, winMuted) = helper
                    val next = (win + if (delta > 0) 5 else -5).coerceIn(0, 100)
                    HelperClient.volumeSet(store.host, store.helperPort, store.helperToken, next)
                    if (winMuted) HelperClient.volumeMute(store.host, store.helperPort, store.helperToken, false)
                } else if (store.appMuted) {
                    // Restore-then-step, same as the row: unmute to where we were, one step from there.
                    store.appMuted = false
                    val next = (store.preMuteRaw + delta).coerceIn(0, Vlc.VOLUME_100)
                    Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, next)
                } else {
                    val st = Vlc.status(store.host, store.vlcPort, store.vlcPassword)
                    Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, (st.volumeRaw + delta).coerceIn(0, Vlc.VOLUME_100))
                }
                refreshNow()
            } catch (_: Exception) {}
        }
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
            // The compact bar's skips both use his primary interval (Ezzy's design);
            // the system media card keeps its own secondary-back / main-forward pair.
            ACTION_MEDIA_REWIND -> { jump(Store(this), -Store(this).skipMainSec); return START_STICKY }
            ACTION_MEDIA_FAST_FORWARD -> { jump(Store(this), Store(this).skipMainSec); return START_STICKY }
            ACTION_MEDIA_PLAY_PAUSE -> {
                // Read the live state at tap time and send the opposite. Never trust
                // the cached lastPlaying: a stale cache is what made the old toggle
                // feel dead or inverted.
                scope.launch {
                    try {
                        val st = Vlc.status(Store(this@MediaControlsService).host, Store(this@MediaControlsService).vlcPort, Store(this@MediaControlsService).vlcPassword)
                        Vlc.setPaused(Store(this@MediaControlsService).host, Store(this@MediaControlsService).vlcPort, Store(this@MediaControlsService).vlcPassword, st.playing)
                        refreshNow()
                    } catch (_: Exception) {}
                }
                return START_STICKY
            }
            ACTION_MEDIA_VOL_DOWN -> { volumeBy(Store(this), -Vlc.VOLUME_STEP); return START_STICKY }
            ACTION_MEDIA_VOL_UP -> { volumeBy(Store(this), Vlc.VOLUME_STEP); return START_STICKY }
            ACTION_DEMO_ROW -> { if (BuildConfig.DEBUG) postDemoRow(); return START_STICKY }
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

    // Ezzy's compact bar replaces the old MediaStyle card: one row, five buttons
    // (rewind / vol down / play-pause / vol up / forward), his icon artwork, both
    // skips on his primary interval. The view is custom, but the style keeps the
    // media session attached, so the notification is still a media notification:
    // the system media surface keeps working and the Android 13 notification
    // permission exemption still applies (no prompt, same as before).
    private fun postMediaNotification(playing: Boolean, timeSec: Long, lengthSec: Long, title: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val s = session ?: return
        val store = Store(this)
        val openApp = android.app.PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        fun serviceIntent(action: String) = android.app.PendingIntent.getService(
            this, action.hashCode(), Intent(this, MediaControlsService::class.java).setAction(action),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val dm = resources.displayMetrics.density
        fun px(dp: Int) = (dp * dm + 0.5f).toInt()
        val skipSec = store.skipMainSec
        val views = android.widget.RemoteViews(packageName, R.layout.notification_controls)
        views.setImageViewBitmap(R.id.btn_rewind, NotificationArt.skip(px(34), forward = false, label = skipSec.toString()))
        views.setImageViewBitmap(R.id.btn_vol_down, NotificationArt.volumeDown(px(24)))
        views.setImageViewBitmap(R.id.btn_play_pause, if (playing) NotificationArt.pause(px(36)) else NotificationArt.play(px(36)))
        views.setImageViewBitmap(R.id.btn_vol_up, NotificationArt.volumeUp(px(24)))
        views.setImageViewBitmap(R.id.btn_forward, NotificationArt.skip(px(34), forward = true, label = skipSec.toString()))
        views.setOnClickPendingIntent(R.id.btn_rewind, serviceIntent(ACTION_MEDIA_REWIND))
        views.setOnClickPendingIntent(R.id.btn_vol_down, serviceIntent(ACTION_MEDIA_VOL_DOWN))
        views.setOnClickPendingIntent(R.id.btn_play_pause, serviceIntent(ACTION_MEDIA_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.btn_vol_up, serviceIntent(ACTION_MEDIA_VOL_UP))
        views.setOnClickPendingIntent(R.id.btn_forward, serviceIntent(ACTION_MEDIA_FAST_FORWARD))
        views.setContentDescription(R.id.btn_rewind, "Back $skipSec seconds")
        views.setContentDescription(R.id.btn_vol_down, "Volume down")
        views.setContentDescription(R.id.btn_play_pause, if (playing) "Pause" else "Play")
        views.setContentDescription(R.id.btn_vol_up, "Volume up")
        views.setContentDescription(R.id.btn_forward, "Forward $skipSec seconds")
        // The same five as notification actions too: the stock-Android media card and
        // the expanded decorated view render from the action list, not the custom view,
        // so a surface that ignores the custom layout still shows his controls.
        fun act(icon: android.graphics.Bitmap, label: String, intentAction: String) =
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithBitmap(icon), label, serviceIntent(intentAction)).build()
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title?.ifBlank { null } ?: "Playing")
            .setContentText("${fmtClockSec(timeSec)} of ${fmtClockSec(lengthSec)}")
            .setContentIntent(openApp)
            .setOngoing(playing)
            .addAction(act(NotificationArt.skip(px(24), forward = false, label = skipSec.toString()), "Back $skipSec seconds", ACTION_MEDIA_REWIND))
            .addAction(act(NotificationArt.volumeDown(px(24)), "Volume down", ACTION_MEDIA_VOL_DOWN))
            .addAction(act(if (playing) NotificationArt.pause(px(24)) else NotificationArt.play(px(24)), if (playing) "Pause" else "Play", ACTION_MEDIA_PLAY_PAUSE))
            .addAction(act(NotificationArt.volumeUp(px(24)), "Volume up", ACTION_MEDIA_VOL_UP))
            .addAction(act(NotificationArt.skip(px(24), forward = true, label = skipSec.toString()), "Forward $skipSec seconds", ACTION_MEDIA_FAST_FORWARD))
            .setCustomContentView(views)
            .setStyle(Notification.DecoratedMediaCustomViewStyle().setMediaSession(s.sessionToken))
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // CI experiment (debug builds only): the SAME compact bar posted as a plain
    // custom-view notification with NO media-session token, under a second id.
    // Instinct 2's question: does the stock shade render the custom row, and does it
    // stay enqueued without the POST_NOTIFICATIONS grant? Production keeps the token
    // (the exemption may need it); this only measures what stock Android does without it.
    private fun postDemoRow() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val store = Store(this)
        val skipSec = store.skipMainSec
        val dm = resources.displayMetrics.density
        fun px(dp: Int) = (dp * dm + 0.5f).toInt()
        fun intentFor(action: String) = android.app.PendingIntent.getService(
            this, action.hashCode(), Intent(this, MediaControlsService::class.java).setAction(action),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val views = android.widget.RemoteViews(packageName, R.layout.notification_controls)
        views.setImageViewBitmap(R.id.btn_rewind, NotificationArt.skip(px(34), forward = false, label = skipSec.toString()))
        views.setImageViewBitmap(R.id.btn_vol_down, NotificationArt.volumeDown(px(24)))
        views.setImageViewBitmap(R.id.btn_play_pause, NotificationArt.pause(px(36)))
        views.setImageViewBitmap(R.id.btn_vol_up, NotificationArt.volumeUp(px(24)))
        views.setImageViewBitmap(R.id.btn_forward, NotificationArt.skip(px(34), forward = true, label = skipSec.toString()))
        views.setOnClickPendingIntent(R.id.btn_rewind, intentFor(ACTION_MEDIA_REWIND))
        views.setOnClickPendingIntent(R.id.btn_vol_down, intentFor(ACTION_MEDIA_VOL_DOWN))
        views.setOnClickPendingIntent(R.id.btn_play_pause, intentFor(ACTION_MEDIA_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.btn_vol_up, intentFor(ACTION_MEDIA_VOL_UP))
        views.setOnClickPendingIntent(R.id.btn_forward, intentFor(ACTION_MEDIA_FAST_FORWARD))
        views.setContentDescription(R.id.btn_rewind, "Back $skipSec seconds")
        views.setContentDescription(R.id.btn_vol_down, "Volume down")
        views.setContentDescription(R.id.btn_play_pause, "Pause")
        views.setContentDescription(R.id.btn_vol_up, "Volume up")
        views.setContentDescription(R.id.btn_forward, "Forward $skipSec seconds")
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("demo.mkv")
            .setContentText("34:12 of 1:50:24")
            .setCustomContentView(views)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .build()
        nm.notify(NOTIF_ROW_ID, n)
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        pollJob?.cancel()
        session?.let { it.isActive = false; it.release() }
        session = null
        super.onDestroy()
    }
}
