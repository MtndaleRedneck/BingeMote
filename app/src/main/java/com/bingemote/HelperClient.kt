package com.bingemote

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** A helper found on the network by UDP broadcast. */
data class DiscoveredHelper(val host: String, val port: Int, val name: String)

/**
 * Client for the BingeMote Windows helper: mouse, scroll, zoom and keyboard over LAN HTTP.
 * Every request carries the pairing token. Pairing trades a one-time 6-digit code
 * (shown on the PC) for the token. HTTP is not encrypted. Use only on a trusted private network;
 * a device on that network could observe the token and input.
 */
object HelperClient {
    const val DEFAULT_PORT = 8488
    const val DISCOVERY_PORT = 8489
    private const val DISCOVER_MAGIC = "BINGEMOTE_DISCOVER_V1"
    private const val REPLY_MAGIC = "BINGEMOTE_HERE_V1"

    class Unreachable(val msg: String) : Exception(msg)
    class BadToken(val msg: String) : Exception(msg)
    class BadPairCode(val msg: String) : Exception(msg)

    @Synchronized
    private fun post(host: String, port: Int, path: String, body: JSONObject, token: String?, timeoutMs: Int = 5000) {
        val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (token != null) c.setRequestProperty("X-BingeMote-Token", token)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            c.outputStream.use { os: OutputStream -> os.write(bytes) }
            when (c.responseCode) {
                in 200..299 -> return
                else -> throw failure(c)
            }
        } catch (e: BadToken) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: Exception) { throw Unreachable(e.message ?: "Could not reach BingeMote PC on the computer.") }
        finally { releaseQuietly(c) }
    }

    /** True when the helper answers and accepts the token. */
    fun ping(host: String, port: Int, token: String): Boolean = try {
        post(host, port, "/ping", JSONObject(), token); true
    } catch (e: Exception) { false }

    @Synchronized
    private fun get(host: String, port: Int, path: String, token: String, timeoutMs: Int = 5000): JSONObject {
        val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("X-BingeMote-Token", token)
            when (c.responseCode) {
                in 200..299 -> return JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                else -> throw failure(c)
            }
        } catch (e: BadToken) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: Exception) { throw Unreachable(e.message ?: "Could not reach BingeMote PC on the computer.") }
        finally { releaseQuietly(c) }
    }

    /**
     * Turn an error response into the right exception. The helper answers its known
     * routes with a JSON body carrying a readable "detail" - show THAT, never a guess.
     * Only a bare 404 (no helper JSON) means the route is missing: the PC is too old.
     */
    private fun failure(c: HttpURLConnection): Exception {
        val code = c.responseCode
        if (code == 401 || code == 403) return BadToken("The PC rejected the pairing token.")
        val detail = try {
            c.errorStream?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)).optString("detail") }
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
        if (detail != null) return Unreachable(detail)
        if (code == 404) return Unreachable("BingeMote PC is too old for this app.")
        return Unreachable("BingeMote PC answered with error $code.")
    }

    /** Let OkHttp pool the socket (keep-alive); disconnect() forced a fresh TCP handshake on every
     * mouse batch, which is what made the pointer lurch. Consuming the response lets the pool reuse it. */
    private fun releaseQuietly(c: HttpURLConnection) {
        try { c.inputStream.use { it.readBytes() } } catch (_: Exception) {
            try { c.errorStream?.use { it.readBytes() } } catch (_: Exception) {}
        }
    }

    /** Subfolders anywhere on the PC (drives when path is empty), so the folder picker
     * works with VLC closed. Folder names only - the PC never sends files. */
    fun browse(host: String, port: Int, token: String, path: String): List<String> {
        val enc = java.net.URLEncoder.encode(path, "UTF-8")
        val json = get(host, port, "/browse?path=$enc", token)
        val arr = json.getJSONArray("entries")
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).getString("name"))
        return out
    }

    /** The movies folder as set on the PC; null when the helper can't be asked (too old or down). */
    fun getMoviesFolder(host: String, port: Int, token: String): String? = try {
        get(host, port, "/config", token).optString("moviesFolder", "")
    } catch (e: Exception) { null }

    /** One level of the PC's movies folder, listed by the helper (works with VLC closed). */
    fun listMovies(host: String, port: Int, token: String, relPath: String): List<Pair<String, Boolean>> {
        val enc = java.net.URLEncoder.encode(relPath, "UTF-8")
        val json = get(host, port, "/movies/list?path=$enc", token)
        val out = mutableListOf<Pair<String, Boolean>>()
        val arr = json.getJSONArray("entries")
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            out.add(e.getString("name") to e.getBoolean("dir"))
        }
        return out
    }

    /** Ask the helper to start VLC with this file (folder-relative path). detail = error text when it fails. */
    fun playMovie(host: String, port: Int, token: String, relPath: String, httpPort: Int, httpPassword: String, fullscreen: Boolean): String? = try {
        post(host, port, "/movies/play", JSONObject()
            .put("path", relPath).put("httpPort", httpPort).put("httpPassword", httpPassword).put("fullscreen", fullscreen), token)
        null
    } catch (e: Exception) { e.message ?: "The PC could not start the movie." }

    /** Save the movies folder on the PC (the helper owns it; both sides can set it). */
    fun setMoviesFolder(host: String, port: Int, token: String, folder: String) =
        post(host, port, "/config", JSONObject().put("moviesFolder", folder), token)

    /**
     * Trade a one-time 6-digit code (shown on the PC) for the long-lived pairing token.
     * Returns the token and the PC's name.
     */
    fun pair(host: String, port: Int, code: String): Pair<String, String> {
        val c = URL("http://$host:$port/pair").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000; c.readTimeout = 4000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("code", code.trim()).toString().toByteArray(Charsets.UTF_8)) }
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.use { s: InputStream ->
                s.readBytes().toString(Charsets.UTF_8)
            } ?: ""
            when (c.responseCode) {
                200 -> {
                    val o = JSONObject(text)
                    return o.getString("token") to o.optString("name", "PC")
                }
                401, 403 -> throw BadPairCode("Wrong code. Check the code on the PC and try again.")
                429 -> throw BadPairCode("Too many tries. Restart BingeMote PC on the computer to pair again.")
                else -> throw Unreachable("Pairing failed (error ${c.responseCode}).")
            }
        } catch (e: BadPairCode) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: Exception) { throw Unreachable(e.message ?: "Could not reach BingeMote PC on the computer.") }
        finally { releaseQuietly(c) }
    }

    /** Find helpers on the network. Waits up to [waitMs] for replies. */
    fun discover(waitMs: Int = 1500): List<DiscoveredHelper> {
        val found = LinkedHashMap<String, DiscoveredHelper>()
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = 250
            val payload = DISCOVER_MAGIC.toByteArray(Charsets.UTF_8)
            val targets = broadcastTargets()
            val deadline = System.currentTimeMillis() + waitMs
            var sends = 0
            while (System.currentTimeMillis() < deadline) {
                // Six rounds to every broadcast address: routers drop lone broadcasts in both directions.
                if (sends < 6) {
                    for (t in targets) {
                        try { sock.send(DatagramPacket(payload, payload.size, t, DISCOVERY_PORT)) } catch (e: Exception) {}
                    }
                    sends++
                }
                try {
                    val buf = ByteArray(1024)
                    val reply = DatagramPacket(buf, buf.size)
                    sock.receive(reply)
                    val text = String(reply.data, 0, reply.length, Charsets.UTF_8)
                    val parts = text.split("|")
                    if (parts.size >= 3 && parts[0] == REPLY_MAGIC) {
                        val port = parts[2].toIntOrNull() ?: DEFAULT_PORT
                        val host = reply.address.hostAddress ?: continue
                        found[host] = DiscoveredHelper(host, port, parts[1])
                    }
                } catch (e: java.net.SocketTimeoutException) { /* keep waiting */ }
            }
        } catch (e: Exception) { /* no network, no discovery */ }
        finally { sock?.close() }
        return found.values.toList()
    }

    /** 255.255.255.255 plus each interface's own subnet broadcast - some routers only pass one of them. */
    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) {}
        try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces() ?: return out.toList()
            for (ni in ifs) {
                try { if (!ni.isUp || ni.isLoopback) continue } catch (e: Exception) { continue }
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        } catch (e: Exception) {}
        return out.toList()
    }

    fun move(host: String, port: Int, token: String, dx: Float, dy: Float) =
        post(host, port, "/mouse/move", JSONObject().put("dx", dx.toDouble()).put("dy", dy.toDouble()), token, 2500)

    /** button: "left" or "right". down=true presses, down=false releases. */
    fun button(host: String, port: Int, token: String, button: String, down: Boolean) =
        post(host, port, "/mouse/button", JSONObject().put("button", button).put("down", down), token)

    fun scroll(host: String, port: Int, token: String, dy: Float) =
        post(host, port, "/mouse/scroll", JSONObject().put("dy", dy.toDouble()), token, 2500)

    /** Pinch zoom, sent to the PC as Ctrl + mouse wheel. delta > 0 zooms in. */
    fun zoom(host: String, port: Int, token: String, delta: Float) =
        post(host, port, "/mouse/zoom", JSONObject().put("delta", delta.toDouble()), token, 2500)

    /** Windows system volume, 0-100, and mute. Throws Unreachable when the helper does not answer. */
    fun volumeGet(host: String, port: Int, token: String): Pair<Int, Boolean> {
        val c = URL("http://$host:$port/volume/get").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 5000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-BingeMote-Token", token)
            c.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw Unreachable("volume get failed (${c.responseCode})")
            val text = c.inputStream.use { s: InputStream -> s.readBytes().toString(Charsets.UTF_8) }
            val o = JSONObject(text)
            return o.getInt("volume") to o.getBoolean("mute")
        } catch (e: Unreachable) { throw e }
        catch (e: Exception) { throw Unreachable(e.message ?: "Could not reach BingeMote PC on the computer.") }
        finally { releaseQuietly(c) }
    }

    fun volumeSet(host: String, port: Int, token: String, percent: Int) =
        post(host, port, "/volume/set", JSONObject().put("volume", percent), token)

    fun volumeMute(host: String, port: Int, token: String, mute: Boolean) =
        post(host, port, "/volume/mute", JSONObject().put("mute", mute), token)

    /** A named key: "enter", "backspace", "escape", "space", "tab", "up", "down", "left", "right". */
    fun key(host: String, port: Int, token: String, key: String) =
        post(host, port, "/key", JSONObject().put("key", key), token)

    /** Type literal text. */
    fun typeText(host: String, port: Int, token: String, text: String) =
        post(host, port, "/type", JSONObject().put("text", text), token)
}
