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
 * Client for the BingeMote Windows helper: mouse, scroll, zoom and keyboard over LAN HTTPS.
 * Every request goes only to the stored PC address, over TLS pinned to the key proven at pairing,
 * and carries the pairing token from the same stored record. Pairing trades a one-time code
 * (shown on the PC) for the token and the PC's key. An old plain-HTTP pairing is unusable:
 * the phone pairs again from the start.
 */
object HelperClient {
    const val DEFAULT_PORT = 8488
    const val DISCOVERY_PORT = 8489
    private const val DISCOVER_MAGIC = "BINGEMOTE_DISCOVER_V1"
    private const val REPLY_MAGIC = "BINGEMOTE_HERE_V1"

    open class Unreachable(val msg: String) : Exception(msg)
    class SubtitleRejected(val detail: String) : Exception(detail)
    class MissingRoute : Unreachable("BingeMote PC is too old for this app.")
    /** Shown to the user. The raw socket text ("failed to connect to /192.168.1.5 (port 8488) after 2500ms") used to leak into Movies. */
    private const val NO_PC = "Can't reach BingeMote on the PC. Check that the PC is on and BingeMote is open."
    class BadToken(val msg: String) : Exception(msg)
    class BadPairCode(val msg: String) : Exception(msg)
    /** The PC answered but could not verify VLC settings right now (store busy/broken): retry, never re-pair. */
    class PcSetupUnknown(msg: String) : Exception(msg)
    /** The PC could not be connected to at all (off, sleeping, new address). */
    class ConnectTimeout(val msg: String) : Exception(msg)


    /** The PC changed its key since pairing. Never retried, never downgraded: the phone must be paired again from the PC. */
    class KeyChanged : Unreachable("This PC's security key changed, so the phone refused to connect. If you did not reinstall or reset BingeMote on the PC, do not continue. To pair again, open Pair on the PC and pair this phone from the start.")
    /** No usable secure pairing (unpaired, old plain pairing, interrupted save, unreadable store). Nothing is sent. */
    class NotPairedSecurely : Unreachable("This phone needs to be paired again. Open Pair on the PC and pair this phone.")

    /** Supplies the loaded secure record (the app sets it from Store.helperPairing()). Null source means nothing can be sent. */
    @Volatile internal var pairingSource: () -> PairedCredential? = { null }

    private fun credential(): PairedCredential = pairingSource() ?: throw NotPairedSecurely()

    /** Only https to the stored PC address, pinned to the stored key. The caller's held address and
     * token are enforced against the record (Zephyr R12): a re-pair between the caller's read and this
     * send makes them stale, and a stale pairing sends nothing. */
    private fun secureOpen(host: String, port: Int, path: String, expectedToken: String, connectMs: Int = 4000, readMs: Int = 5000): HttpURLConnection {
        val c = when (val d = RequestGate.decide(pairingSource, host, port, expectedToken)) {
            is RequestGate.Decision.Allow -> d.credential
            RequestGate.Decision.NotPaired -> throw NotPairedSecurely()
            RequestGate.Decision.KeyChanged -> throw KeyChanged()   // after a key change nothing more is sent until re-pair
        }
        val conn = SecureHttp.open(c.policy(), c.pcId, c.host, c.port, path, false, null, connectMs, readMs).connection
        // Same snapshot `c` supplies address, pin and token: a re-pair between two reads can never mix them.
        conn.setRequestProperty("X-BingeMote-Token", c.token)
        return conn
    }

    internal fun isKeyMismatch(e: Throwable?): Boolean {
        var x = e; var n = 0
        while (x != null && n++ < 8) { if (x is PinMismatchException) return true; x = x.cause }
        return false
    }
    private fun mapFailure(e: Exception): Exception = if (e is KeyChanged) e else if (isKeyMismatch(e)) KeyChanged() else Unreachable(NO_PC)

    /** The mouse screen's live connection: while it is open, move/scroll/zoom/button go
     * out as fire-and-forget frames on it and the HTTP request below never happens. */
    @Volatile var live: LiveWs? = null

    /** One shared worker for fire-and-forget calls (button release on background/leave) -
     * replaces ad-hoc `Thread {}` spawns that piled up one executor per gesture. */
    val io: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "bingemote-io").apply { isDaemon = true }
    }

    private fun post(host: String, port: Int, path: String, body: JSONObject, token: String, timeoutMs: Int = 5000) {
        val c = secureOpen(host, port, path, token, timeoutMs, timeoutMs)
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            c.outputStream.use { os: OutputStream -> os.write(bytes) }
            when (c.responseCode) {
                in 200..299 -> return
                else -> throw failure(c)
            }
        } catch (e: BadToken) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: ConnectTimeout) { throw e }
        catch (e: java.net.SocketTimeoutException) { throw ConnectTimeout(NO_PC) }
        catch (e: java.net.ConnectException) { throw ConnectTimeout(NO_PC) }
        catch (e: java.net.UnknownHostException) { throw ConnectTimeout(NO_PC) }
        catch (e: Exception) { throw mapFailure(e) }
        finally { releaseQuietly(c) }
    }

    /** True when the helper answers and accepts the token. */
    fun ping(host: String, port: Int, token: String): Boolean = try {
        // 1.5 s: a liveness probe must answer fast or be counted dead.
        post(host, port, "/ping", JSONObject(), token, 1500); true
    } catch (e: Exception) { false }

    /**
     * DHCP rediscovery probe for a SECURE pairing: the candidate address must first prove the
     * stored key with a handshake-only probe (no application bytes ever cross an unproven
     * address, and a mismatch there never marks the PC key-changed), then answer /ping on a
     * pinned connection with the stored token. RequestGate is deliberately not consulted: its
     * same-address check exists to catch stale callers on ordinary requests, and here the
     * address move is the whole point. Identity comes only from the stored record, never from
     * discovery data.
     */
    fun probePaired(c: PairedCredential, host: String, port: Int): Boolean {
        if (KeyChangeState.isChanged(c.pcId)) return false
        if (SecureHttp.probe(c.policy(), c.pcId, host, port, 1500) != SecureHttp.ProbeResult.MATCH) return false
        // The second connection must not latch the saved pcId either: the address is still
        // unverified, so a key flip between probe and ping means this was never the saved PC.
        // (PinningTrustManager's markOnMismatch defaults to true - right for verified
        // endpoints, wrong here.)
        val tm = PinningTrustManager(c.policy(), c.pcId, false, markOnMismatch = false)
        val link = try { SecureHttp.open(c.policy(), c.pcId, host, port, "/ping", false, tm, 1500, 1500) }
            catch (e: Exception) { return false }
        val conn = link.connection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-BingeMote-Token", c.token)
            conn.outputStream.use { os: OutputStream -> os.write("{}".toByteArray(Charsets.UTF_8)) }
            link.responseCode() in 200..299
        } catch (e: Exception) { false }
        finally { releaseQuietly(conn) }
    }

    private fun get(host: String, port: Int, path: String, token: String, timeoutMs: Int = 5000): JSONObject {
        val c = secureOpen(host, port, path, token, timeoutMs, timeoutMs)
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            when (c.responseCode) {
                in 200..299 -> return JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                // The setup check's 503 means "the PC could not verify VLC settings" - distinct
                // from a network failure, never a green Done, never an unpair.
                503 -> if (path == "/vlc/setup-status") throw PcSetupUnknown("The PC could not verify VLC settings.") else throw failure(c)
                else -> throw failure(c)
            }
        } catch (e: BadToken) { throw e }
        catch (e: PcSetupUnknown) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: ConnectTimeout) { throw e }
        catch (e: java.net.SocketTimeoutException) { throw ConnectTimeout(NO_PC) }
        catch (e: java.net.ConnectException) { throw ConnectTimeout(NO_PC) }
        catch (e: java.net.UnknownHostException) { throw ConnectTimeout(NO_PC) }
        catch (e: Exception) { throw mapFailure(e) }
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
        if (code == 404) return MissingRoute()
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

    /** Subtitle-only bounded transport. Cleanup closes rather than draining an untrusted body. */
    private fun subtitleRequest(host: String, port: Int, token: String, path: String, body: JSONObject? = null, timeoutMs: Int = 6000): JSONObject {
        val c = secureOpen(host, port, path, token, minOf(timeoutMs, 5000), timeoutMs)
        val deadline = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val stopper = deadline.schedule({ c.disconnect() }, timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            c.connectTimeout = minOf(timeoutMs, 5000); c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type","application/json")
                val bytes = body.toString().toByteArray(Charsets.UTF_8); c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            if (code == 404) throw MissingRoute()
            if (code == 401 || code == 403) throw BadToken("The PC rejected the pairing token.")
            if (c.contentLengthLong > 524288) throw Unreachable("Subtitle response too large.")
            val out = java.io.ByteArrayOutputStream()
            (if (code in 200..299) c.inputStream else c.errorStream).use { input ->
                requireNotNull(input)
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    if (out.size() + n > 524288) throw Unreachable("Subtitle response too large.")
                    out.write(buf,0,n)
                }
            }
            val result = JSONObject(out.toString("UTF-8"))
            if (code !in 200..299) {
                if (!result.optBoolean("unknown", true)) throw SubtitleRejected(result.optString("error","Selection unavailable"))
                throw Unreachable("Couldn't confirm the subtitle change.")
            }
            return result
        } finally { stopper.cancel(false); c.disconnect(); deadline.shutdownNow() }
    }

    data class SubtitleFile(val id: String, val name: String, val trackIds: List<Int> = emptyList())
    data class SubtitleFolder(val mediaId: String, val files: List<SubtitleFile>, val unavailable: Boolean, val truncated: Boolean = false)
    fun subtitleFiles(host: String, port: Int, token: String): SubtitleFolder {
        val json = subtitleRequest(host, port, token, "/subtitles/list")
        val arr = json.getJSONArray("files")
        require(arr.length() <= 200) { "Too many subtitle files" }
        return SubtitleFolder(json.optString("mediaId"), (0 until arr.length()).map {
            val item = arr.getJSONObject(it)
            require(item.getString("name").length <= 255 && item.getString("id").length <= 64)
            val ids = item.optJSONArray("trackIds")
            require((ids?.length() ?: 0) <= 200)
            SubtitleFile(item.getString("id"), item.getString("name"), if (ids == null) emptyList() else (0 until ids.length()).map { ids.getInt(it) })
        }, json.optBoolean("unavailable"), json.optBoolean("truncated"))
    }
    fun loadSubtitle(host: String, port: Int, token: String, mediaId: String, id: String): Boolean {
        val json = subtitleRequest(host,port,token,"/subtitles/load",JSONObject().put("mediaId",mediaId).put("id",id),55000)
        return json.optBoolean("ok") && !json.optBoolean("woke")
    }

    fun subtitleMedia(host: String, port: Int, token: String): String =
        subtitleRequest(host,port,token,"/subtitles/media").getString("mediaId")

    /** False only for an explicit missing route. Never bypass an in-flight helper
     * operation with a direct VLC command after a timeout or an auth failure. */
    fun selectSubtitle(host: String, port: Int, token: String, trackId: Int, mediaId: String = ""): Boolean = try {
        val json = subtitleRequest(host,port,token,"/subtitles/select",JSONObject().put("trackId",trackId).put("mediaId",mediaId),50000)
        if (json.optBoolean("woke")) throw Unreachable("Screen woken. Tap the subtitle again.")
        if (!json.optBoolean("ok")) throw Unreachable("Couldn't change subtitles.")
        true
    } catch (_: MissingRoute) { false }

    /** The movies folder as set on the PC; null when the helper can't be asked (too old or down). */
    fun getMoviesFolder(host: String, port: Int, token: String): String? = try {
        get(host, port, "/config", token).optString("moviesFolder", "")
    } catch (e: Exception) { null }

    /** Strict variant: throws on read failure. Blank means the PC provably has no
     *  folder configured - unlike a null from [getMoviesFolder], which also covers
     *  "could not read", and must never be treated as permission to overwrite. */
    fun getMoviesFolderStrict(host: String, port: Int, token: String): String =
        get(host, port, "/config", token).optString("moviesFolder", "")

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

    /** Ask the helper to start VLC with this file (folder-relative path).
     * [resume] null = ask if a saved place exists; true = resume; false = start over.
     * Returns the raw response JSON; the caller checks "ask" (saved place found),
     * "ok" (playing), or "detail" (error text). */
    fun playMovie(host: String, port: Int, token: String, relPath: String, httpPort: Int, httpPassword: String, fullscreen: Boolean, resume: Boolean? = null): JSONObject {
        val body = JSONObject()
            .put("path", relPath).put("httpPort", httpPort).put("httpPassword", httpPassword).put("fullscreen", fullscreen)
        if (resume != null) body.put("resume", resume)
        return JSONObject(postForBody(host, port, "/movies/play", body, token))
    }

    /** post() that returns the response body (/movies/play answers carry the resume question). */
    private fun postForBody(host: String, port: Int, path: String, body: JSONObject, token: String, timeoutMs: Int = 5000): String {
        val c = secureOpen(host, port, path, token, timeoutMs, timeoutMs)
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { os: OutputStream -> os.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.use { s: InputStream -> s.readBytes().toString(Charsets.UTF_8) } ?: ""
            when (c.responseCode) {
                in 200..299 -> return text
                else -> throw failure(c)
            }
        } catch (e: BadToken) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: ConnectTimeout) { throw e }
        catch (e: java.net.SocketTimeoutException) { throw ConnectTimeout(NO_PC) }
        catch (e: java.net.ConnectException) { throw ConnectTimeout(NO_PC) }
        catch (e: java.net.UnknownHostException) { throw ConnectTimeout(NO_PC) }
        catch (e: Exception) { throw mapFailure(e) }
        finally { releaseQuietly(c) }
    }

    /** Hand the VLC web-remote password to the PC so it can write vlcrc (needs VLC closed).
     * Returns the skip reason ("vlc running") when the PC deferred it; null when written.
     * The PC retries the write by itself when VLC closes. */
    fun setupVlc(host: String, port: Int, token: String, password: String): String? {
        val resp = JSONObject(postForBody(host, port, "/vlc/setup", JSONObject().put("password", password), token))
        return if (resp.optBoolean("ok")) null else resp.optString("reason").ifBlank { null }
    }

    /** Keep-awake preference for the PC. Best-effort: helpers that don't know the key
     * leave the movies folder untouched (a missing moviesFolder fails before anything is
     * written) and ignore it. */
    fun setKeepAwake(host: String, port: Int, token: String, keepAwake: Boolean) =
        post(host, port, "/config", JSONObject().put("keepAwake", keepAwake), token)

    /** Read the shared settings back. Helpers without volumeOsd leave the key absent. */
    fun getConfig(host: String, port: Int, token: String): JSONObject =
        get(host, port, "/config", token)

    /** Toggle the PC volume overlay. Helpers without it ignore the unknown config key. */
    fun setVolumeOsd(host: String, port: Int, token: String, on: Boolean) =
        post(host, port, "/config", JSONObject().put("volumeOsd", on), token)

    /** Save the movies folder on the PC (the helper owns it; both sides can set it). */
    fun setMoviesFolder(host: String, port: Int, token: String, folder: String) =
        post(host, port, "/config", JSONObject().put("moviesFolder", folder), token)

    /**
     * Trade a one-time code (shown on the PC) for the long-lived pairing token.
     * Returns the token and the PC's name.
     */
    /** What a successful pairing returns. vlcPassword/vlcPending exist on helper 1.7+:
     *  the PC generates the VLC password and hands it over with the pair. An old helper
     *  leaves them null/false and the phone falls back to the manual password card. */
    data class PairResult(val token: String, val name: String, val vlcPassword: String?, val vlcPending: Boolean)

    internal fun parsePairJson(text: String): PairResult {
        val o = JSONObject(text)
        val pw = o.optString("vlcPassword", "").ifBlank { null }
        return PairResult(o.getString("token"), o.optString("name", "PC"), pw, o.optBoolean("vlcPending", false))
    }

    fun pair(host: String, port: Int, code: String): PairResult = throw NotPairedSecurely()

    /** Secure first pairing over HTTPS: the key seen on THIS response is the one proven by [proof]. pcId is the local identity the key is remembered under. */
    class SecurePairResult(val result: PairResult, val proof: SecureHttp.BoundPin, val pcId: String)

    fun pairSecure(host: String, port: Int, code: String, pcId: String = PairedCredential.newPcId()): SecurePairResult {
        val link = SecureHttp.open(PinPolicy(emptyMap()), pcId, host, port, "/pair", true)
        val c = link.connection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("code", code.trim()).toString().toByteArray(Charsets.UTF_8)) }
            val rc = link.responseCode()
            val bound = link.readBound() ?: throw Unreachable("The PC could not be verified. Pair again.")
            val text = bound.body
            if (rc == 200) {
                // The token is taken from the body this link read and bound to its key; nothing else can supply it.
                var parsed: PairResult? = null
                val proof = bound.pin { body -> parsePairJson(body).also { parsed = it }.token }
                return SecurePairResult(parsed!!, proof, pcId)
            }
            val detail = try { JSONObject(text).optString("detail") } catch (e: Exception) { "" }
            val fallback = when (rc) {
                401, 403 -> "Wrong code. Check the code on the PC and try again."
                410 -> "That code expired - press Pair again in the BingeMote window on the PC."
                429 -> "Too many tries. Press Pair again in the BingeMote window on the PC."
                409 -> "That PC already has too many phones paired. Remove one in the BingeMote window first."
                else -> "Pairing failed (error $rc)."
            }
            throw BadPairCode(if (detail.isNotBlank()) detail else fallback)
        } catch (e: BadPairCode) { throw e }
        catch (e: Unreachable) { throw e }
        catch (e: Exception) { throw mapFailure(e) }
        finally { releaseQuietly(c) }
    }

    /** The PC's attestation for the automatic setup check: is the phone's VLC password
     * the live one in vlcrc (configured), or is a write waiting for VLC to close (pending)?
     * A green Done is grounded in this, never in a network error alone. */
    data class VlcSetupStatus(val configured: Boolean, val pending: Boolean)

    fun vlcSetupStatus(host: String, port: Int, token: String): VlcSetupStatus =
        get(host, port, "/vlc/setup-status", token).let {
            VlcSetupStatus(it.optBoolean("configured"), it.optBoolean("pending"))
        }

    /** The one-shot adoption fetch (automatic-first setup): the PC's VLC password,
     *  served exactly once per paired phone. null = the grant is spent or the helper is
     *  too old to know the endpoint. Throws Unreachable when the PC cannot be reached. */
    fun adoptVlcPassword(host: String, port: Int, token: String, requestId: String): String? {
        val o = postJson(host, port, "/vlc/adopt", token, JSONObject().put("requestId", requestId)) ?: return null
        return o.optString("password", "").ifBlank { null }
    }

    /** Confirm the password is stored: spends the one-shot grant for good. */
    fun ackVlcAdoption(host: String, port: Int, token: String, requestId: String): Boolean =
        postJson(host, port, "/vlc/adopt", token, JSONObject().put("requestId", requestId).put("ack", true)) != null

    /** POST a JSON body with the pairing token; null on any refusal (401/404/409/410) or error. */
    private fun postJson(host: String, port: Int, path: String, token: String, body: JSONObject): JSONObject? {
        val c = try { secureOpen(host, port, path, token) } catch (e: Exception) { return null }
        try {
            c.connectTimeout = 4000; c.readTimeout = 4000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) return null
            val text = c.inputStream.use { s: InputStream -> s.readBytes().toString(Charsets.UTF_8) }
            return JSONObject(text)
        } catch (e: Exception) { if (e is KeyChanged || isKeyMismatch(e)) throw KeyChanged(); return null }
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

    /** One ordered path: the live socket when it is open, plain HTTP when it is not -
     * never a per-frame mix of the two (mixed frames are ordered only per channel, and
     * interleaving them can reorder moves against clicks). A full socket queue drops the
     * frame instead of reordering it; the next move delta supersedes it. */
    private fun frame(host: String, port: Int, token: String, json: JSONObject, path: String, timeoutMs: Int = 5000) {
        val l = live
        if (l != null && l.open) {
            if (l.send(json)) Diag.wsSends.incrementAndGet() else Diag.wsDrops.incrementAndGet()
            return
        }
        Diag.httpFallbacks.incrementAndGet()
        post(host, port, path, json, token, timeoutMs)
    }

    fun move(host: String, port: Int, token: String, dx: Float, dy: Float) =
        frame(host, port, token, JSONObject().put("cmd", "move").put("dx", dx.toDouble()).put("dy", dy.toDouble()), "/mouse/move", 2500)

    /** button: "left" or "right". down=true presses, down=false releases. Frames are
     * ordered, so a click sent after moves on the live connection lands after them. */
    fun button(host: String, port: Int, token: String, button: String, down: Boolean) =
        frame(host, port, token, JSONObject().put("cmd", "button").put("button", button).put("down", down), "/mouse/button")

    fun scroll(host: String, port: Int, token: String, dy: Float) =
        frame(host, port, token, JSONObject().put("cmd", "scroll").put("dy", dy.toDouble()), "/mouse/scroll", 2500)

    /** Pinch zoom, sent to the PC as Ctrl + mouse wheel. delta > 0 zooms in. */
    fun zoom(host: String, port: Int, token: String, delta: Float) =
        frame(host, port, token, JSONObject().put("cmd", "zoom").put("delta", delta.toDouble()), "/mouse/zoom", 2500)

    /** Windows system volume, 0-100, and mute. Throws Unreachable when the helper does not answer. */
    fun volumeGet(host: String, port: Int, token: String): Pair<Int, Boolean> {
        val c = secureOpen(host, port, "/volume/get", token, 1500, 5000)
        try {
            c.connectTimeout = 1500; c.readTimeout = 5000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            // Connect explicitly so a dead PC (connect timeout) is told apart from an error answer.
            try { c.connect() } catch (e: java.net.SocketTimeoutException) { throw ConnectTimeout(NO_PC) }
            c.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            if (c.responseCode == 401) throw BadToken("phone no longer paired")
            if (c.responseCode !in 200..299) throw Unreachable("volume get failed (${c.responseCode})")
            val text = c.inputStream.use { s: InputStream -> s.readBytes().toString(Charsets.UTF_8) }
            val o = JSONObject(text)
            return o.getInt("volume") to o.getBoolean("mute")
        } catch (e: Unreachable) { throw e }
        catch (e: ConnectTimeout) { throw e }
        catch (e: BadToken) { throw e }
        catch (e: Exception) { throw mapFailure(e) }
        finally { releaseQuietly(c) }
    }

    fun volumeSet(host: String, port: Int, token: String, percent: Int) =
        post(host, port, "/volume/set", JSONObject().put("volume", percent), token)

    fun volumeMute(host: String, port: Int, token: String, mute: Boolean) =
        post(host, port, "/volume/mute", JSONObject().put("mute", mute), token)

    /** True when the PC's screen is dark - or when we can't find out, which includes an
     * unreachable helper AND an unpaired phone (fail-safe: the tap becomes wake-only
     * rather than firing blind; without a pairing there is no way to know the screen,
     * so the strict rule is never to send the original action on an unknown state). */
    fun screenIsDark(host: String, port: Int, token: String): Boolean {
        if (token.isBlank()) return true
        return try {
            get(host, port, "/screen-state", token, 1500).optBoolean("dark", true)
        } catch (e: Exception) { true }
    }

    /** Ask the PC to bring its screen back (its idle timer shut it off). Fire-and-forget;
     * failure means nothing - the laptop may simply be off. */
    fun wakeDisplay(host: String, port: Int, token: String) {
        if (token.isBlank()) return
        try { post(host, port, "/wake-display", JSONObject(), token, 1500) } catch (_: Exception) {}
    }

    /** A named key: "enter", "backspace", "escape", "space", "tab", "up", "down", "left", "right". */
    fun key(host: String, port: Int, token: String, key: String) =
        post(host, port, "/key", JSONObject().put("key", key), token)

    /** Type literal text. */
    fun typeText(host: String, port: Int, token: String, text: String) =
        post(host, port, "/type", JSONObject().put("text", text), token)
}
