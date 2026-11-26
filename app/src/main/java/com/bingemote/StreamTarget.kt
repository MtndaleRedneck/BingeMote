package com.bingemote

import com.bingemote.stream.StreamCoordinator
import com.bingemote.stream.StreamIdlePolicy

/** Where a phone stream is handed: VLC on the PC. */
object StreamTarget {
    data class Target(val host: String, val port: Int, val credential: String) {
        fun state(): StreamIdlePolicy.PlayerState =
            StreamIdlePolicy.mapState(Vlc.status(host, port, credential).state)
    }
    fun capture(s: Store): Target = Target(s.host, s.vlcPort, s.vlcPassword)
    fun play(target: Target, url: String, subs: List<String>) {
        // Capture ownership even if the final response is lost. UNKNOWN never authorizes stop.
        StreamCoordinator.servedReceivers.add(target)
        StreamCoordinator.servedTarget = StreamCoordinator.ServedTarget.VLC
        Vlc.playUri(target.host, target.port, target.credential, url, subs)
    }
}
