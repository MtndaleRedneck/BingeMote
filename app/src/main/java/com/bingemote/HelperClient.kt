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
    private fun post(host: String, port: Int, path: String, body: JSONObject, token: String?, timeoutMs: Int = 3000) {
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
                401, 403 -> throw BadToken("The PC rejected the pairing token.")
                404 -> throw Unreachable("The helper is too old for this app.")
                else -> throw Unreachable("The helper answered with error ${c.responseCode}.")
            }
        } catch (e: BadToken) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: Exception) { throw Unreachable(e.message ?: "Could not reach the helper.") }
        finally { c.disconnect() }
    }

    /** True when the helper answers and accepts the token. */
    fun ping(host: String, port: Int, token: String): Boolean = try {
        post(host, port, "/ping", JSONObject(), token); true
    } catch (e: Exception) { false }

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
                429 -> throw BadPairCode("Too many tries. Restart the helper on the PC to pair again.")
                else -> throw Unreachable("Pairing failed (error ${c.responseCode}).")
            }
        } catch (e: BadPairCode) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: Exception) { throw Unreachable(e.message ?: "Could not reach the helper.") }
        finally { c.disconnect() }
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
            val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
            val deadline = System.currentTimeMillis() + waitMs
            var sends = 0
            while (System.currentTimeMillis() < deadline) {
                if (sends < 3) { try { sock.send(packet) } catch (e: Exception) {}; sends++ }
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

    fun move(host: String, port: Int, token: String, dx: Float, dy: Float) =
        post(host, port, "/mouse/move", JSONObject().put("dx", dx.toDouble()).put("dy", dy.toDouble()), token, 1500)

    /** button: "left" or "right". down=true presses, down=false releases. */
    fun button(host: String, port: Int, token: String, button: String, down: Boolean) =
        post(host, port, "/mouse/button", JSONObject().put("button", button).put("down", down), token)

    fun scroll(host: String, port: Int, token: String, dy: Float) =
        post(host, port, "/mouse/scroll", JSONObject().put("dy", dy.toDouble()), token, 1500)

    /** Pinch zoom, sent to the PC as Ctrl + mouse wheel. delta > 0 zooms in. */
    fun zoom(host: String, port: Int, token: String, delta: Float) =
        post(host, port, "/mouse/zoom", JSONObject().put("delta", delta.toDouble()), token, 1500)

    /** Windows system volume, 0-100, and mute. Throws Unreachable when the helper does not answer. */
    fun volumeGet(host: String, port: Int, token: String): Pair<Int, Boolean> {
        val c = URL("http://$host:$port/volume/get").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2500; c.readTimeout = 2500
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-BingeMote-Token", token)
            c.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw Unreachable("volume get failed (${c.responseCode})")
            val text = c.inputStream.use { s: InputStream -> s.readBytes().toString(Charsets.UTF_8) }
            val o = JSONObject(text)
            return o.getInt("volume") to o.getBoolean("mute")
        } catch (e: Unreachable) { throw e }
        catch (e: Exception) { throw Unreachable(e.message ?: "Could not reach the helper.") }
        finally { c.disconnect() }
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
