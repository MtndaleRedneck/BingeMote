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

    fun requestOrOpenSettings(activity: android.app.Activity) {
        val prefs = activity.getSharedPreferences("notification_permission", Context.MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked",false)) {
            prefs.edit().putBoolean("asked",true).apply()
            requestPermission(activity)
        } else {
            activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,activity.packageName))
        }
    }

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
    @Volatile private var lastConnectedAt = 0L
    private fun playbackConnected(): Boolean = lastConnectedAt != 0L &&
        android.os.SystemClock.elapsedRealtime() - lastConnectedAt <= 5000
    private var failures = 0
    private val commandEpoch = java.util.concurrent.atomic.AtomicLong()
    private val pollMutex = Mutex()
    private var foregroundStarted = false
    private var lastRender: List<Any?>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A prior version's session is released when its process is replaced.
        // No new session is created here, so the row cannot supply a media token.
    }

    /** One volume button step (5%). Same contract as the in-app volume row
     * (RemoteState.volumeStep): when the helper answers, drive WINDOWS volume (and
     * unmute only if it was muted); otherwise drive VLC volume, capped at 100%. */
    @Volatile private var lastStatus: VlcStatus? = null
    private fun currentPlayback(store: Store): VlcStatus = lastStatus
        ?: Vlc.status(store.host, store.vlcPort, store.vlcPassword)

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
                } else {
                    val st = currentPlayback(store)
                    val next = (st.volumeRaw + delta).coerceIn(0, Vlc.VOLUME_100)
                    Vlc.setVolume(store.host, store.vlcPort, store.vlcPassword, next)
                    lastStatus = st.copy(volumeRaw = next)
                            }
                refreshNow()
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }

    private fun jump(store: Store, deltaSec: Int) {
        scope.launch {
            try {
                Vlc.skip(store.host, store.vlcPort, store.vlcPassword, deltaSec)
                refreshNow()
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MediaControls.allowed(this)) {
                stopSelf()
            return START_NOT_STICKY
        }
        // Enter foreground immediately, before the first network poll.
        if (!foregroundStarted) {
            postMediaNotification(lastPlaying, 0, 0, null)
        }
        if (!(BuildConfig.DEBUG && Store(this).demoControls)) {
        if (pollJob?.isActive != true) {
                pollJob = scope.launch {
                while (isActive) {
                    pollOnce()
                    delay(2000)
                }
            }
        }
        }
        // Disconnected Play stays tappable but is a no-op, including no tick.
        // Only a recent successful VLC poll permits optimistic state feedback.
        if (intent?.action == ACTION_MEDIA_PLAY_PAUSE && !playbackConnected()) return START_STICKY
        if (intent?.action in setOf(ACTION_MEDIA_REWIND, ACTION_MEDIA_FAST_FORWARD, ACTION_MEDIA_PLAY_PAUSE, ACTION_MEDIA_VOL_DOWN, ACTION_MEDIA_VOL_UP)) { commandEpoch.incrementAndGet(); tick() }
        when (intent?.action) {
            // Both skips use the primary interval, matching the in-app controls.
            ACTION_MEDIA_REWIND -> { jump(Store(this), -Store(this).skipMainSec); return START_STICKY }
            ACTION_MEDIA_FAST_FORWARD -> { jump(Store(this), Store(this).skipMainSec); return START_STICKY }
            ACTION_MEDIA_PLAY_PAUSE -> {
                // the glyph used to wait for VLC's answer (1-2 s on a
                // busy player). Flip on receipt for instant feedback. If the command
                // never lands, revert - but only when no newer bar action superseded
                // this press (a late failure must not stomp a later flip). The 2 s
                // poll reconciles any remaining divergence with the PC's real state.
                val prev = lastPlaying
                val epochAtPress = commandEpoch.get()
                lastPlaying = !prev
                postMediaNotification(lastPlaying, 0, 0, lastStatus?.title)
                scope.launch {
                    try {
                        val store = Store(this@MediaControlsService)
                        Vlc.togglePause(store.host, store.vlcPort, store.vlcPassword)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        lastConnectedAt = 0L
                        if (epochAtPress == commandEpoch.get()) {
                            lastPlaying = prev
                            postMediaNotification(prev, 0, 0, lastStatus?.title)
                        }
                    }
                    refreshNow()
                }
                return START_STICKY
            }
            ACTION_MEDIA_VOL_DOWN -> { volumeBy(Store(this), -Vlc.VOLUME_STEP); return START_STICKY }
            ACTION_MEDIA_VOL_UP -> { volumeBy(Store(this), Vlc.VOLUME_STEP); return START_STICKY }
        }
        if (BuildConfig.DEBUG && Store(this).demoControls) { seedDemo(); return START_STICKY }
        return START_STICKY
    }

    @Volatile private var lastPlaying = false
    private fun refreshNow() { scope.launch { pollOnce() } }

    /** short confirmation tick on each bar button press. Honors
     * the app's "Vibrate on press" setting AND the system haptic-feedback switch;
     * the notification channel itself never vibrates, and the status poll path
     * never calls this. */
    private fun tick() {
        try {
            if (!Store(this).haptics) return
            if (android.provider.Settings.System.getInt(contentResolver,
                    android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 1) return
            performTick(this)
        } catch (_: Exception) { }
    }

    companion object {
        /** Test seam: instrumented tests substitute a recorder; production vibrates. */
        internal var performTick: (Context) -> Unit = { ctx ->
            ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                ?.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
        }

        /** Test seam: instrumented tests watch every real bar post (true = play glyph). */
        internal var postProbe: ((Boolean) -> Unit)? = null
    }

    private suspend fun pollOnce() = pollMutex.withLock { pollBody() }
    private suspend fun pollBody() {
        // 1.12.4: guard the WHOLE body. On 1.12.3 only the VLC call was protected, so
        // one bad state push or notification post threw out of the poll coroutine and
        // killed the service silently - the bar simply vanished.
        try {
            val store = Store(this)
            if (!store.lockScreenControls || !store.isConfigured) {
                        stopSelf()
                return
            }
            val epoch = commandEpoch.get()
            val st = try {
                Vlc.status(store.host, store.vlcPort, store.vlcPassword)
            } catch (e: Vlc.BadAuth) {
                // The one automatic heal: adopt the PC's current password once; never push ours.
                if (VlcAuthRepair.tryHealOnce(store)) { return } // the next poll uses it
                lastConnectedAt = 0L
                failures++
                        if (failures >= 5) { stopSelf() }
                return
            } catch (e: Exception) { if (e is CancellationException) throw e;
                lastConnectedAt = 0L
                failures++
                        // VLC gone for good: drop the controls rather than pose over a dead player.
                if (failures >= 5) { stopSelf() }
                return
            }
            if (epoch != commandEpoch.get()) return
            failures = 0
            lastConnectedAt = android.os.SystemClock.elapsedRealtime()
            val playing = st.playing
            lastStatus = st
            lastPlaying = playing
            postMediaNotification(playing, st.timeSec, st.lengthSec, st.title)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
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
        // One shared content box per glyph (NotificationArt.forBar) keeps the edge
        // gaps between buttons perfectly even in the equal-weight slots.
        val big = px(48)
        val barPx = px(36)
        val icons = listOf(NotificationArt.forBar(NotificationArt.skip(big, false, skipSec.toString()), barPx), NotificationArt.forBar(NotificationArt.volumeDown(big), barPx),
            NotificationArt.forBar(if (playing) NotificationArt.pause(big) else NotificationArt.play(big), barPx), NotificationArt.forBar(NotificationArt.volumeUp(big), barPx),
            NotificationArt.forBar(NotificationArt.skip(big, true, skipSec.toString()), barPx))
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
            .setSmallIcon(R.drawable.ic_stat_bingemote)
            .setContentTitle(title?.ifBlank { null } ?: "Playing")
            .setContentText("Playback remote")
            .setContentIntent(openApp)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setCustomContentView(row)
            // no expanded form - the big view repeated the same row,
            // so the shade offered a pointless expand arrow. Collapsed-only: one fixed
            // row. (OEM shades may still decorate; validated on the CI profile.)
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
        postProbe?.invoke(playing)
        if (foregroundStarted) {
            nm.notify(NOTIF_ID, n)
        } else if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        foregroundStarted = true
        lastRender = key
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The started service keeps running when the app card is swiped away; the line
        // exists so a bar death can be told apart from a recents swipe in the log.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }
}
