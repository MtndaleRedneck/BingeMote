package com.bingemote.stream

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bridge between the approved Movies screen (Phone file-origin, per the v2
 * selector render) and the streaming stack. Flow when he taps a phone file with
 * the PC VLC:
 *
 *   1. start + bind StreamService (keeps process alive, holds the locks)
 *   2. register the movie Uri (+ sidecar subtitles) -> get the /s/<token>/<id> URL
 *   3. send the URL to VLC on the PC (in_play, subtitles as :input-slave)
 *
 * The pairing token never appears in the URL; the stream token dies with the
 * session. Sidecar discovery uses the folder-tree permission only.
 */
object StreamCoordinator {

    class Bound(val server: StreamServer, val connection: ServiceConnection)

    /** Which player the current phone stream was handed to, captured at play time.
     *  The shutdown watchdog polls THIS target - never the mutable UI source
     *  selection, so flipping sources mid-stream can't wrongly stop or strand it. */
    enum class ServedTarget { VLC }
    @Volatile var servedTarget: ServedTarget? = null
    val servedReceivers = java.util.concurrent.CopyOnWriteArraySet<com.bingemote.StreamTarget.Target>()

    fun receiverState(): StreamIdlePolicy.PlayerState {
        val targets = servedReceivers.toList()
        if (targets.isEmpty()) return StreamIdlePolicy.PlayerState.UNKNOWN
        val states = targets.map { runCatching { it.state() }.getOrDefault(StreamIdlePolicy.PlayerState.UNKNOWN) }
        return if (states.all { it == StreamIdlePolicy.PlayerState.STOPPED_OR_ENDED }) StreamIdlePolicy.PlayerState.STOPPED_OR_ENDED else StreamIdlePolicy.PlayerState.UNKNOWN
    }

    suspend fun bind(context: Context): Bound = suspendCancellableCoroutine { cont ->
        val app = context.applicationContext
        val conn = object : ServiceConnection {
            // Every failure path drops the connection, so a failed bind never keeps the service alive.
            fun failAndUnbind(e: Exception) {
                runCatching { app.unbindService(this) }
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val b = binder as? StreamService.Binder
                    ?: return failAndUnbind(IllegalStateException("bad binder"))
                // The bind can complete before onStartCommand has created the server (cold
                // start): wait briefly on the main thread, then give up with "server not ready"
                // (also what no Wi-Fi looks like, since the service stops itself then).
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val conn = this
                var tries = 0
                fun check() {
                    if (!cont.isActive) return
                    val server = b.server()
                    if (server != null) cont.resume(Bound(server, conn))
                    else if (b.refused()) failAndUnbind(IllegalStateException("foreground start refused")) // this instance is stopping: fail now, not after the 10 s wait
                    else if (++tries >= 100) failAndUnbind(IllegalStateException("server not ready"))
                    else handler.postDelayed({ check() }, 100)
                }
                check()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent(app, StreamService::class.java)
        app.startForegroundService(intent)
        if (!app.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            runCatching { app.unbindService(conn) }
            app.stopService(intent)
            cont.resumeWithException(IllegalStateException("stream service bind failed"))
        }
        cont.invokeOnCancellation { runCatching { app.unbindService(conn) } }
    }

    /** Registers the movie + sidecars and returns (movie URL, subtitle URLs). */
    fun handoff(bound: Bound, movie: Uri, name: String, subtitles: List<Uri>): Pair<String, List<String>> {
        val url = bound.server.addMovie(movie, name, subtitles)
        val id = url.substringAfterLast('/')
        return url to subtitles.indices.map { "${bound.server.baseUrl}/$id/sub/$it" }
    }

    fun release(context: Context, bound: Bound) {
        runCatching { context.applicationContext.unbindService(bound.connection) }
    }
}
