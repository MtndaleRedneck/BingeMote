package com.bingemote

import com.bingemote.stream.StreamCoordinator
import com.bingemote.stream.StreamIdlePolicy

/** Where a phone stream is handed: VLC on the PC. */
object StreamTarget {
    data class Target(val host: String, val port: Int, val credential: String) {
        fun state(): StreamIdlePolicy.PlayerState =
            StreamIdlePolicy.mapState(Vlc.status(host, port, credential).state)
    }
    /** One coherent VLC target (host+port+password from a single locked read) or null when the
     *  store is broken or unpaired - the caller shows the same honest failure a dead VLC gives. */
    fun capture(s: Store): Target? = s.vlcTarget()?.let { Target(it.host, it.port, it.password) }
    fun play(target: Target, url: String, subs: List<String>) {
        // Capture ownership even if the final response is lost. UNKNOWN never authorizes stop.
        StreamCoordinator.servedReceivers.add(target)
        StreamCoordinator.servedTarget = StreamCoordinator.ServedTarget.VLC
        Vlc.playUri(target.host, target.port, target.credential, url, subs)
    }
}
