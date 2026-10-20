package com.bingemote

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One subtitle track from VLC's stream list. [id] is the stream number VLC's subtitle_track command takes. */
data class SubTrack(val id: Int, val name: String, val selected: Boolean)

data class VlcStatus(
    /** playing, paused or stopped. */
    val state: String,
    val title: String,
    val timeSec: Long,
    val lengthSec: Long,
    /** VLC's raw volume, 256 = 100%. */
    val volumeRaw: Int,
    val fullscreen: Boolean,
    val subtitleTracks: List<SubTrack>,
) {
    val playing: Boolean get() = state == "playing"
    val volumePct: Int get() = Math.round(volumeRaw * 100f / 256f)
    val remainingSec: Long get() = (lengthSec - timeSec).coerceAtLeast(0)
}

data class BrowseItem(
    val name: String,
    val isDir: Boolean,
    val path: String,
    val uri: String,
    val sizeBytes: Long,
)

/**
 * Client for VLC's built-in HTTP interface. Plain HTTP on the home network, Basic auth
 * with an empty username and the interface password. Commands and fields follow VLC's own
 * requests README and lua source (3.0.x branch).
 */
object Vlc {
    /** VLC's volume scale: 256 = 100%. We cap the remote at 100%. */
    const val VOLUME_100 = 256
    /** One volume button step: 5% in VLC units. */
    const val VOLUME_STEP = 13

    class Unreachable(val msg: String) : Exception(msg)
    class BadAuth(val msg: String) : Exception(msg)

    private fun encode(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun get(url: String, password: String, timeoutMs: Int = 4000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            val creds = android.util.Base64.encodeToString(":$password".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            c.setRequestProperty("Authorization", "Basic $creds")
            when (c.responseCode) {
                200 -> return c.inputStream.use { it.readText(2 * 1024 * 1024) }
                401 -> throw BadAuth("VLC rejected the password.")
                else -> throw Unreachable("VLC answered with error ${c.responseCode}.")
            }
        } catch (e: BadAuth) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: Exception) { throw Unreachable(e.message ?: "Could not reach VLC.") }
        finally { c.disconnect() }
    }

    private fun InputStream.readText(max: Int): String {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n = read(buf); var t = 0
        while (n >= 0) { t += n; if (t > max) throw java.io.IOException("too large"); out.write(buf, 0, n); n = read(buf) }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun statusUrl(host: String, port: Int) = "http://$host:$port/requests/status.json"
    private fun browseUrl(host: String, port: Int) = "http://$host:$port/requests/browse.json"

    fun status(host: String, port: Int, password: String): VlcStatus =
        parseStatus(JSONObject(get(statusUrl(host, port), password)))

    /** Fire a playback command, e.g. command=pl_pause. */
    fun command(host: String, port: Int, password: String, command: String) {
        get("${statusUrl(host, port)}?$command", password)
    }

    fun togglePause(host: String, port: Int, pw: String) = command(host, port, pw, "command=pl_pause")
    fun toggleFullscreen(host: String, port: Int, pw: String) = command(host, port, pw, "command=fullscreen")

    /** Relative skip in seconds, negative to go back. */
    fun skip(host: String, port: Int, pw: String, seconds: Int) =
        command(host, port, pw, "command=seek&val=${encode(if (seconds >= 0) "+${seconds}s" else "${seconds}s")}")

    /** Absolute seek to [seconds] into the file. */
    fun seekTo(host: String, port: Int, pw: String, seconds: Long) =
        command(host, port, pw, "command=seek&val=${seconds.coerceAtLeast(0)}")

    /** Set VLC's volume in raw units (256 = 100%). */
    fun setVolume(host: String, port: Int, pw: String, raw: Int) =
        command(host, port, pw, "command=volume&val=${raw.coerceIn(0, VOLUME_100 * 2)}")

    fun setSubtitleTrack(host: String, port: Int, pw: String, trackId: Int) =
        command(host, port, pw, "command=subtitle_track&val=$trackId")

    /** Play a file on the PC by its VLC uri (from browse). */
    fun playUri(host: String, port: Int, pw: String, uri: String) =
        command(host, port, pw, "command=in_play&input=${encode(uri)}")

    /** List one folder on the PC. Throws Unreachable when VLC can't read it. */
    fun browse(host: String, port: Int, password: String, dir: String): List<BrowseItem> {
        val raw = get("$browseUrl(host, port)?dir=${encode(dir)}", password)
        val root = JSONObject(raw)
        val el = root.opt("element")
        val arr: JSONArray = when (el) {
            is JSONArray -> el
            is JSONObject -> el.optJSONArray("_array") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<BrowseItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                BrowseItem(
                    name = o.optString("name", ""),
                    isDir = o.optString("type", "") == "dir",
                    path = o.optString("path", ""),
                    uri = o.optString("uri", ""),
                    sizeBytes = o.optLong("size", 0),
                )
            )
        }
        return out
    }

    fun parseStatus(o: JSONObject): VlcStatus {
        var title = ""
        val tracks = ArrayList<SubTrack>()
        val info = o.optJSONObject("information")
        val cat = info?.optJSONObject("category")
        if (cat != null) {
            val meta = cat.optJSONObject("meta")
            if (meta != null) {
                title = meta.optString("title", "")
                if (title.isBlank()) title = meta.optString("filename", "")
                if (title.isBlank()) title = meta.optString("now_playing", "")
            }
            val keys = cat.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!k.startsWith("Stream")) continue
                val s = cat.optJSONObject(k) ?: continue
                if (!s.optString("Type", "").equals("Subtitle", ignoreCase = true)) continue
                val id = k.removePrefix("Stream").trim().toIntOrNull() ?: continue
                var name = s.optString("Language", "")
                val desc = s.optString("Description", "")
                if (name.isNotBlank() && desc.isNotBlank() && !desc.equals(name, true)) name = "$name ($desc)"
                if (name.isBlank()) name = desc.ifBlank { "Track ${tracks.size + 1}" }
                val sel = s.optString("selected", "")
                tracks.add(SubTrack(id, name, sel == "1" || sel.equals("true", true)))
            }
            tracks.sortBy { it.id }
        }
        return VlcStatus(
            state = o.optString("state", "stopped"),
            title = title,
            timeSec = o.optLong("time", 0),
            lengthSec = o.optLong("length", 0),
            volumeRaw = o.optInt("volume", 0),
            fullscreen = o.opt("fullscreen")?.toString()?.let { it == "1" || it == "true" } ?: false,
            subtitleTracks = tracks,
        )
    }
}

/** "1:14:02" or "14:02". */
fun formatTime(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Skip button labels: "5s" under a minute, "1m" at whole minutes. */
fun formatSkip(seconds: Int): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m"

val MAIN_SKIP_OPTIONS = listOf(5, 10, 15, 30, 60)
val SECONDARY_SKIP_OPTIONS = listOf(15, 30, 60, 120, 180, 300)

/** "1.6 GB" / "340 MB". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
    else -> "%.0f KB".format(bytes.toDouble() / 1024)
}

val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "wmv", "mov", "ts", "mpg", "mpeg", "webm", "flv", "vob")

fun isVideoFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
