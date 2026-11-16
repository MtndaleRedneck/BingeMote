package com.bingemote

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The lock-screen controls log, on the phone only: every line the media-controls
 * service writes to logcat (tag BMBar) also lands in a small ring file, so a broken
 * bar can be diagnosed from Settings without adb (1.12.4: Ezzy's One UI card looked
 * wrong, its taps did nothing, and the card vanished mid-recording - all invisible
 * without the lines). Nothing is uploaded; a line leaves the phone only when he
 * shares the log himself from the Settings row.
 */
object BMLog {
    private const val FILE_NAME = "bmbar.log"
    private const val MAX_BYTES = 256 * 1024

    @Volatile private var logFile: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Once per process is enough (MainActivity and the service both call it). */
    fun init(context: Context) {
        logFile = File(context.applicationContext.filesDir, FILE_NAME)
    }

    fun i(msg: String) {
        Log.i("BMBar", msg)
        write("I", msg)
    }

    fun w(msg: String) {
        Log.w("BMBar", msg)
        write("W", msg)
    }

    @Synchronized
    private fun write(level: String, msg: String) {
        val f = logFile ?: return
        try {
            if (f.length() > MAX_BYTES) {
                // Keep the newer half - the same ring-by-halving the PC's helper.log uses.
                f.writeText(f.readText().let { it.substring(it.length / 2) })
            }
            f.appendText(stamp.format(Date()) + " " + level + " " + msg + "\n")
        } catch (_: Exception) { /* logging must never break the bar */ }
    }

    fun read(context: Context): String =
        try {
            File(context.filesDir, FILE_NAME).readText().ifBlank { "(log is empty)" }
        } catch (_: Exception) {
            "(no log yet - use the lock-screen controls once, then look again)"
        }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) { }
    }
}
