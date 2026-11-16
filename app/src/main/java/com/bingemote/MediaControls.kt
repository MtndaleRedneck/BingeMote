package com.bingemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Starts or stops the lock-screen controls service to match the setting. */
object MediaControls {
    fun allowed(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun requestPermission(activity: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !allowed(activity)) {
            androidx.core.app.ActivityCompat.requestPermissions(activity,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 126)
        }
    }

    fun sync(context: Context, store: Store) {
        val i = Intent(context, MediaControlsService::class.java)
        // The CI screenshot state seeds demo controls with no PC paired.
        val demo = BuildConfig.DEBUG && store.demoControls
        if (store.lockScreenControls && (store.isConfigured || demo) && allowed(context)) {
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        } else context.stopService(i)
    }
}

/** Five-button remote notification. This is NOT a media session: Android owns the
 * notification header, but our RemoteViews owns the controls. Requires ordinary
 * notification permission on Android 13+. Samsung rendering remains unproven. */
private const val NOTIF_ID = 101
private const val CHANNEL_ID = "remote_controls_v1"
private const val ACTION_MEDIA_REWIND = "com.bingemote.action.REWIND"
private const val ACTION_MEDIA_FAST_FORWARD = "com.bingemote.action.FAST_FORWARD"
private const val ACTION_MEDIA_PLAY_PAUSE = "com.bingemote.action.PLAY_PAUSE"
private const val ACTION_MEDIA_VOL_DOWN = "com.bingemote.action.VOL_DOWN"
private const val ACTION_MEDIA_VOL_UP = "com.bingemote.action.VOL_UP"

class MediaControlsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var failures = 0
    private val pollMutex = Mutex()
    private var foregroundStarted = false
    private var lastRender: List<Any?>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BMLog.init(this)
        BMLog.i("service create")
        scope.launch {
            PlaybackUpdates.latest.collect { snapshot ->
                if (snapshot != null && snapshot.endpoint == "${Store(this@MediaControlsService).host}:${Store(this@MediaControlsService).vlcPort}") {
                    lastPlaying = snapshot.status.playing
                    postMediaNotification(snapshot.status.playing, snapshot.status.timeSec, snapshot.status.lengthSec, snapshot.status.title)
                }
            }
        }
        // A prior version's session is released when its process is replaced.
        // No new session is created here, so the row cannot supply a media token.
    }

    /** One volume button step (5%). Same contract as the in-app volume row
     * (RemoteState.volumeStep): when the helper answers, drive WINDOWS volume (and
     * unmute only if it was muted); otherwise drive VLC volume with the shared mute
     * restore, clamped at 100% like the row (LOW1/LOW2 - the bar used to bypass both
     * and could push VLC to 200%). */
    private fun currentPlayback(store: Store): VlcStatus = PlaybackUpdates.latest.value
        ?.takeIf { it.endpoint == "${store.host}:${store.vlcPort}" && android.os.SystemClock.elapsedRealtimeNanos() - it.requestedAt < 5_000_000_000L }
        ?.status ?: Vlc.status(store.host, store.vlcPort, store.vlcPassword)

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
                    val st = currentPlayback(store)
                    Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, (st.volumeRaw + delta).coerceIn(0, Vlc.VOLUME_100))
                    BMLog.i("volume via vlc ${st.volumeRaw} -> ${(st.volumeRaw + delta).coerceIn(0, Vlc.VOLUME_100)}")
                }
                refreshNow()
            } catch (e: Exception) { if (e is CancellationException) throw e; BMLog.w("volume FAILED: $e") }
        }
    }

    private fun jump(store: Store, deltaSec: Int) {
        scope.launch {
            try {
                val st = currentPlayback(store)
                Vlc.seekTo(store.host, store.vlcPort, store.vlcPassword,
                    (st.timeSec + deltaSec).coerceIn(0, st.lengthSec))
                PlaybackUpdates.publish(android.os.SystemClock.elapsedRealtimeNanos(), st.copy(timeSec = (st.timeSec + deltaSec).coerceIn(0, st.lengthSec)), "${store.host}:${store.vlcPort}")
                BMLog.i("seek ${st.timeSec} -> ${(st.timeSec + deltaSec).coerceIn(0, st.lengthSec)}")
                refreshNow()
            } catch (e: Exception) { if (e is CancellationException) throw e; BMLog.w("seek FAILED: $e") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MediaControls.allowed(this)) {
            BMLog.w("controls unavailable: notification permission denied")
            stopSelf()
            return START_NOT_STICKY
        }
        // Enter foreground immediately, before the first network poll.
        if (!foregroundStarted) {
            val recent = PlaybackUpdates.latest.value?.takeIf { android.os.SystemClock.elapsedRealtimeNanos() - it.requestedAt < 10_000_000_000L }
            lastPlaying = recent?.status?.playing ?: false
            postMediaNotification(lastPlaying, 0, 0, recent?.status?.title)
        }
        if (!(BuildConfig.DEBUG && Store(this).demoControls)) {
        if (pollJob?.isActive != true) {
            BMLog.i("poll loop start")
            pollJob = scope.launch {
                while (isActive) {
                    pollOnce()
                    delay(2000)
                }
            }
        }
        }
        when (intent?.action) {
            // Both skips use the primary interval, matching the in-app controls.
            ACTION_MEDIA_REWIND -> { BMLog.i("tap rewind"); jump(Store(this), -Store(this).skipMainSec); return START_STICKY }
            ACTION_MEDIA_FAST_FORWARD -> { BMLog.i("tap forward"); jump(Store(this), Store(this).skipMainSec); return START_STICKY }
            ACTION_MEDIA_PLAY_PAUSE -> {
                // Read the live state at tap time and send the opposite. Never trust
                // the cached lastPlaying: a stale cache is what made the old toggle
                // feel dead or inverted.
                BMLog.i("tap play/pause")
                scope.launch {
                    try {
                        val st = currentPlayback(Store(this@MediaControlsService))
                        Vlc.setPaused(Store(this@MediaControlsService).host, Store(this@MediaControlsService).vlcPort, Store(this@MediaControlsService).vlcPassword, st.playing)
                        PlaybackUpdates.publish(android.os.SystemClock.elapsedRealtimeNanos(), st.copy(state = if(st.playing) "paused" else "playing"), "${Store(this@MediaControlsService).host}:${Store(this@MediaControlsService).vlcPort}")
                        BMLog.i("play/pause sent (was playing=${st.playing})")
                        refreshNow()
                    } catch (e: Exception) { if (e is CancellationException) throw e; BMLog.w("play/pause FAILED: $e") }
                }
                return START_STICKY
            }
            ACTION_MEDIA_VOL_DOWN -> { BMLog.i("tap volume down"); volumeBy(Store(this), -Vlc.VOLUME_STEP); return START_STICKY }
            ACTION_MEDIA_VOL_UP -> { BMLog.i("tap volume up"); volumeBy(Store(this), Vlc.VOLUME_STEP); return START_STICKY }
        }
        if (BuildConfig.DEBUG && Store(this).demoControls) { seedDemo(); return START_STICKY }
        return START_STICKY
    }

    @Volatile private var refreshRequested = false
    @Volatile private var lastPlaying = false
    private fun refreshNow() { refreshRequested = true; scope.launch { pollOnce() } }

    private suspend fun pollOnce() = pollMutex.withLock { pollBody() }
    private suspend fun pollBody() {
        // 1.12.4: guard the WHOLE body. On 1.12.3 only the VLC call was protected, so
        // one bad state push or notification post threw out of the poll coroutine and
        // killed the service silently - the bar simply vanished (Ezzy's recording).
        try {
            val store = Store(this)
            if (!store.lockScreenControls || !store.isConfigured) {
                BMLog.w("stopSelf: setting off or PC not configured (controls=${store.lockScreenControls}, configured=${store.isConfigured})")
                stopSelf()
                return
            }
            if (refreshRequested) { refreshRequested = false } /* poll right away anyway */
            val requestedAt = android.os.SystemClock.elapsedRealtimeNanos()
            val st = try {
                Vlc.status(store.host, store.vlcPort, store.vlcPassword)
            } catch (e: Vlc.BadAuth) {
                // The one automatic heal: adopt the PC's current password once; never push ours.
                if (VlcAuthRepair.tryHealOnce(store)) { BMLog.w("poll auth FAILED - adopted the PC's current password"); return } // the next poll uses it
                failures++
                BMLog.w("poll auth FAILED ($failures)")
                if (failures >= 5) { BMLog.w("controls stopped after $failures poll failures"); stopSelf() }
                return
            } catch (e: Exception) { if (e is CancellationException) throw e;
                failures++
                BMLog.w("poll FAILED ($failures): $e")
                // VLC gone for good: drop the controls rather than pose over a dead player.
                if (failures >= 5) { BMLog.w("controls stopped after $failures poll failures"); stopSelf() }
                return
            }
            failures = 0
            val playing = st.playing
            if (playing != lastPlaying) BMLog.i("poll: ${if (playing) "playing" else "paused"} ${st.timeSec}s/${st.lengthSec}s")
            PlaybackUpdates.publish(requestedAt, st, "${store.host}:${store.vlcPort}")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            BMLog.w("poll crashed, service stays up: $t")
        }
    }

    // Debug-only visual seed; real button behavior is exercised against a stub PC.
    private fun seedDemo() {
        postMediaNotification(true, 2052, 6624, "demo.mkv")
    }

    @Synchronized private fun postMediaNotification(playing: Boolean, timeSec: Long, lengthSec: Long, title: String?) {
        val config = resources.configuration
        val key = listOf(playing, title, Store(this).skipMainSec, config.uiMode, resources.displayMetrics.densityDpi)
        if (foregroundStarted && key == lastRender) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
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
        val row = android.widget.RemoteViews(packageName, R.layout.notification_controls)
        val labels = listOf("Back $skipSec seconds", "Volume down", if (playing) "Pause" else "Play", "Volume up", "Forward $skipSec seconds")
        val ids = listOf(R.id.btn_rewind, R.id.btn_vol_down, R.id.btn_play_pause, R.id.btn_vol_up, R.id.btn_forward)
        val actions = listOf(ACTION_MEDIA_REWIND, ACTION_MEDIA_VOL_DOWN, ACTION_MEDIA_PLAY_PAUSE, ACTION_MEDIA_VOL_UP, ACTION_MEDIA_FAST_FORWARD)
        val icons = listOf(NotificationArt.skip(px(34), false, skipSec.toString()), NotificationArt.volumeDown(px(24)),
            if (playing) NotificationArt.pause(px(36)) else NotificationArt.play(px(36)), NotificationArt.volumeUp(px(24)),
            NotificationArt.skip(px(34), true, skipSec.toString()))
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val tint = if (dark) android.graphics.Color.WHITE else android.graphics.Color.rgb(38, 38, 45)
        ids.indices.forEach { i ->
            val bitmap = icons[i].copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            android.graphics.Canvas(bitmap).drawBitmap(icons[i], 0f, 0f, paint)
            row.setImageViewBitmap(ids[i], bitmap)
            row.setContentDescription(ids[i], labels[i])
            row.setOnClickPendingIntent(ids[i], serviceIntent(actions[i]))
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title?.ifBlank { null } ?: "Playing")
            .setContentText("Playback remote")
            .setContentIntent(openApp)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setCustomContentView(row)
            .setCustomBigContentView(row)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    // Android 16's promoted-ongoing ("Live Update") surface has an
                    // explicit opt-out; compileSdk predates the constant, so reflect.
                    try {
                        Notification.Builder::class.java
                            .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType ?: Boolean::class.java)
                            .invoke(this, false)
                    } catch (_: Exception) { }
                }
            }
            .build()
        if (foregroundStarted) {
            nm.notify(NOTIF_ID, n)
        } else if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        foregroundStarted = true
        lastRender = key
        BMLog.i("notification render playing=$playing")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The started service keeps running when the app card is swiped away; the line
        // exists so a bar death can be told apart from a recents swipe in the log.
        BMLog.i("app swiped from recents (service keeps running)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        BMLog.w("service destroy (bar torn down)")
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }
}
