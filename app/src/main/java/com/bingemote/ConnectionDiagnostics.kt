package com.bingemote

/** Only status codes and exception types, never credentials, headers or response bodies. */
object ConnectionDiagnostics {
    fun failureKind(e: Throwable): String = when (e) {
        is Vlc.BadAuth -> "HTTP 401: password refused"
        is Vlc.VlcError -> e.code?.let { "HTTP $it" } ?: "VLC response error"
        else -> (e.cause ?: e).javaClass.simpleName.ifBlank { "Network failure" }
    }
    fun helperFailure(e: Throwable): String = when (e) {
        is HelperClient.BadToken -> "HTTP ${e.code}: pairing token rejected"
        is HelperClient.Unreachable -> e.code?.let { "HTTP $it" } ?: failureKind(e)
        else -> failureKind(e)
    }
    fun title(unpaired: Boolean, refused: Boolean, lost: Boolean, vlcDown: Boolean, host: String): String = when {
        unpaired -> "PC rejected this phone's pairing"
        refused -> "VLC refused the password"
        lost -> "No connection at $host"
        vlcDown -> "VLC isn't answering"
        else -> ""
    }
}
