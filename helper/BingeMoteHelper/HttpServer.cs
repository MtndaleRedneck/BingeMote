using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Net;
using System.Text;
using System.Text.Json;

namespace BingeMoteHelper;

/// <summary>
/// LAN HTTP API for the paired phone. Every endpoint except /pair needs the
/// pairing token in X-BingeMote-Token, and only private (home network) addresses are served.
/// </summary>
internal sealed class HttpServer : IDisposable
{
    private readonly HttpListener _listener = new();
    private Thread? _thread;
    private volatile bool _running;

    public void Start()
    {
        _listener.Prefixes.Add($"http://+:{Program.HttpPort}/");
        _listener.Start();
        _running = true;
        _thread = new Thread(Loop) { IsBackground = true };
        _thread.Start();
    }

    private void Loop()
    {
        while (_running)
        {
            HttpListenerContext ctx;
            try { ctx = _listener.GetContext(); }
            catch { break; }
            Task.Run(async () => await Handle(ctx));
        }
    }

    private async Task Handle(HttpListenerContext ctx)
    {
        try
        {
            var req = ctx.Request;
            var res = ctx.Response;

            if (!IsPrivateAddress(req.RemoteEndPoint.Address))
            {
                Reply(res, 403, "{\"error\":\"not a private address\"}");
                return;
            }

            var path = req.Url?.AbsolutePath?.TrimEnd('/') ?? "";
            switch (path)
            {
                case "/ping":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    Reply(res, 200, "{\"ok\":true}");
                    return;

                case "/pair":
                    HandlePair(req, res);
                    return;

                // The live mouse connection (item 5): one always-open WebSocket for
                // move/scroll/zoom/button frames, fire-and-forget. Same port, same token
                // check; an old phone simply never opens it and stays on HTTP.
                case "/ws":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    if (!req.IsWebSocketRequest) { Reply(res, 400, "{\"error\":\"websocket expected\"}"); return; }
                    await HandleWs(ctx);
                    return;

                case "/config":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    HandleConfig(req, res);
                    return;

                // The phone's Finish step hands over the VLC web-remote password (T36):
                // write it into vlcrc so a hand-opened VLC has the remote too.
                case "/vlc/setup":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    {
                        var pw = JsonDocument.Parse(ReadBody(req)).RootElement.GetProperty("password").GetString() ?? "";
                        Reply(res, 200, VlcSetup.WriteVlcrc(pw));
                    }
                    return;

                case "/movies/list":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    {
                        var rel = req.QueryString["path"] ?? "";
                        var answer = Movies.List(Program.MoviesFolder ?? "", rel);
                        Reply(res, answer.Contains("\"not found\"") || answer.Contains("\"no movies folder\"") ? 404 : 200, answer);
                    }
                    return;

                case "/browse":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    {
                        var answer = Movies.Browse(req.QueryString["path"] ?? "");
                        Reply(res, answer.Contains("\"not found\"") ? 404 : 200, answer);
                    }
                    return;

                case "/movies/play":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    {
                        using var doc = JsonDocument.Parse(ReadBody(req));
                        var rr = doc.RootElement;
                        var answer = Movies.Play(
                            Program.MoviesFolder ?? "",
                            rr.GetProperty("path").GetString() ?? "",
                            rr.TryGetProperty("httpPort", out var hp) ? hp.GetInt32() : 8080,
                            rr.TryGetProperty("httpPassword", out var pw) ? pw.GetString() ?? "" : "",
                            rr.TryGetProperty("fullscreen", out var fs) && fs.GetBoolean(),
                            req.LocalEndPoint.Address);
                        Reply(res, answer.Contains("\"error\"") ? 400 : 200, answer);
                    }
                    return;

                default:
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    RouteCommand(path, ReadBody(req), res);
                    return;
            }
        }
        catch
        {
            try { Reply(ctx.Response, 500, "{\"error\":\"internal\"}"); } catch { }
        }
    }

    private static bool Authorized(HttpListenerRequest req)
    {
        var token = req.Headers["X-BingeMote-Token"];
        if (token == null) return false;
        lock (Program.PhonesLock) { return Program.Phones.Any(p => ConstantTimeEq(token, p.Token)); }
    }

    private static readonly object PairLock = new();
    private void HandlePair(HttpListenerRequest req, HttpListenerResponse res)
    {
        lock (PairLock) { HandlePairLocked(req, res); }
    }
    private void HandlePairLocked(HttpListenerRequest req, HttpListenerResponse res)
    {
        if (Program.PairFailures >= Program.MaxPairFailures)
        {
            Program.Log("Pairing locked - too many wrong codes.");
            Reply(res, 429, "{\"error\":\"locked\",\"detail\":\"Too many wrong codes. Restart BingeMote on the PC to try again.\"}");
            return;
        }
        // No code on screen: guesses here must not count toward the lock, or anyone on the
        // Wi-Fi could lock pairing while he is not even pairing.
        if (Program.PairCode == null)
        {
            Reply(res, 403, "{\"error\":\"bad code\"}");
            return;
        }
        var body = ReadBody(req);
        var code = JsonDocument.Parse(body).RootElement.GetProperty("code").GetString() ?? "";
        var valid = Program.PairCode != null
                    && DateTime.UtcNow <= Program.PairCodeExpires
                    && ConstantTimeEq(code, Program.PairCode);
        if (!valid)
        {
            Program.PairFailures++;
            Program.Log("Wrong pairing code entered (" + Program.PairFailures + " of " + Program.MaxPairFailures + ").");
            Reply(res, 403, "{\"error\":\"bad code\"}");
            return;
        }
        if (Program.PhonesCount >= Program.MaxPhones)
        {
            Program.Log("Pairing refused - already " + Program.MaxPhones + " phones paired.");
            Reply(res, 409, "{\"error\":\"too many phones\",\"detail\":\"This PC already has " + Program.MaxPhones + " phones paired. Remove one in the BingeMote window on the PC to make room.\"}");
            return;
        }
        var token = Program.NewToken();
        // The name typed in the window when Pair was tapped; a code from elsewhere pairs as "Phone N".
        var phoneName = Program.PendingPhoneName;
        Program.PendingPhoneName = null;
        lock (Program.PhonesLock)
        {
            if (string.IsNullOrWhiteSpace(phoneName)) phoneName = "Phone " + (Program.Phones.Count + 1);
            Program.Phones.Add(new Program.PairedPhone { Token = token, Name = phoneName.Trim() });
        }
        Program.PairCode = null;
        Program.PairFailures = 0;
        Program.SaveConfig();
        Program.Log("Phone paired as \"" + phoneName + "\" (" + Program.PhonesCount + " total).");
        Reply(res, 200, JsonSerializer.Serialize(new { token = token, name = Program.MachineLabel() }));
        Program.PhonesChanged?.Invoke();
    }

    /// <summary>GET returns the shared settings; POST writes them. Only the movies folder so far.</summary>
    private static void HandleConfig(HttpListenerRequest req, HttpListenerResponse res)
    {
        if (req.HttpMethod == "GET")
        {
            Reply(res, 200, JsonSerializer.Serialize(new { moviesFolder = Program.MoviesFolder ?? "" }));
            return;
        }
        var folder = JsonDocument.Parse(ReadBody(req)).RootElement.GetProperty("moviesFolder").GetString() ?? "";
        Program.MoviesFolder = folder.Trim();
        Program.SaveConfig();
        Program.Log("Movies folder set from the phone: " + (Program.MoviesFolder.Length == 0 ? "(cleared)" : Program.MoviesFolder));
        Reply(res, 200, "{\"ok\":true}");
    }

    private static void RouteCommand(string path, string body, HttpListenerResponse res)
    {
        using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body);
        var r = doc.RootElement;
        switch (path)
        {
            case "/mouse/move":
                InputSender.Move((float)r.GetProperty("dx").GetDouble(), (float)r.GetProperty("dy").GetDouble());
                break;
            case "/mouse/button":
                InputSender.Button(
                    r.GetProperty("button").GetString() ?? "left",
                    r.GetProperty("down").GetBoolean());
                break;
            case "/mouse/scroll":
                InputSender.Scroll((float)r.GetProperty("dy").GetDouble());
                break;
            case "/mouse/zoom":
                InputSender.Zoom((float)r.GetProperty("delta").GetDouble());
                break;
            case "/volume/get":
            {
                try
                {
                    var (volume, mute) = VolumeControl.Get();
                    Reply(res, 200, JsonSerializer.Serialize(new { volume, mute }));
                }
                catch (VolumeControl.NoAudioDeviceException ex) { NoAudio(res, ex); }
                return;
            }
            case "/volume/set":
                try { VolumeControl.Set(r.GetProperty("volume").GetInt32()); }
                catch (VolumeControl.NoAudioDeviceException ex) { NoAudio(res, ex); return; }
                break;
            case "/volume/mute":
                try { VolumeControl.SetMute(r.GetProperty("mute").GetBoolean()); }
                catch (VolumeControl.NoAudioDeviceException ex) { NoAudio(res, ex); return; }
                break;
            case "/key":
                InputSender.NamedKey(r.GetProperty("key").GetString() ?? "");
                break;
            case "/type":
                InputSender.TypeText(r.GetProperty("text").GetString() ?? "");
                break;
            default:
                Reply(res, 404, "{\"error\":\"unknown\"}");
                return;
        }
        Reply(res, 200, "{\"ok\":true}");
    }

    /// <summary>Serve one live mouse connection until it drops. Each text frame is a small
    /// JSON command applied as it arrives (frames are ordered, so a click sent after moves
    /// lands after them). No replies: fire-and-forget.</summary>
    private static async Task HandleWs(HttpListenerContext ctx)
    {
        WebSocket? ws = null;
        // This connection's pressed mouse buttons: whatever it still holds when it ends
        // gets released, so a phone that dies mid-drag can't leave the PC's mouse down.
        var pressed = new HashSet<string>();
        // Any frame counts as life (the phone sends a heartbeat every 2 s while open).
        // ~8 s of silence means the phone is gone without a close frame: let go.
        var lastFrameAt = DateTime.UtcNow;
        var lastSeq = -1L;
        using var watchdog = new System.Threading.Timer(_ =>
        {
            if (DateTime.UtcNow - lastFrameAt > TimeSpan.FromSeconds(8))
            {
                Program.Log("Live mouse connection silent for 8s - releasing buttons and closing.");
                try { ws?.Abort(); } catch { }
            }
        }, null, 4000, 4000);
        var token = ctx.Request.Headers["X-BingeMote-Token"] ?? "";
        try
        {
            var wsCtx = await ctx.AcceptWebSocketAsync(null);
            ws = wsCtx.WebSocket;
            Program.Log("Live mouse connection opened.");
            var buf = new byte[4096];
            var text = new StringBuilder();
            while (ws.State == WebSocketState.Open)
            {
                var r = await ws.ReceiveAsync(new ArraySegment<byte>(buf), CancellationToken.None);
                if (r.MessageType == WebSocketMessageType.Close)
                {
                    try { await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "", CancellationToken.None); } catch { }
                    break;
                }
                if (r.MessageType != WebSocketMessageType.Text) continue;
                text.Append(Encoding.UTF8.GetString(buf, 0, r.Count));
                if (!r.EndOfMessage) continue;
                lastFrameAt = DateTime.UtcNow;
                // Removing a phone must stop its input right away, not when it happens to
                // disconnect: the token check only ran at the handshake.
                if (!StillPaired(token))
                {
                    Program.Log("Live mouse connection closed - the phone was removed.");
                    break;
                }
                DispatchWs(text.ToString(), pressed, ref lastSeq);
                text.Clear();
            }
        }
        catch { /* a dropped connection is normal - Wi-Fi blips, the phone sleeps */ }
        finally
        {
            foreach (var b in pressed)
            {
                try { InputSender.Button(b, false); } catch { }
                Program.Log("Released a held " + b + " mouse button from the dropped live connection.");
            }
            try { ws?.Dispose(); } catch { }
            Program.Log("Live mouse connection closed.");
        }
    }

    private static bool StillPaired(string token)
    {
        lock (Program.PhonesLock) { return Program.Phones.Any(p => ConstantTimeEq(token, p.Token)); }
    }

    /// <summary>Apply one frame from the live connection. Anything malformed costs nothing.
    /// Frame numbers are counted: a gap means a frame went missing between phone and PC.</summary>
    private static void DispatchWs(string text, HashSet<string> pressed, ref long lastSeq)
    {
        try
        {
            using var doc = JsonDocument.Parse(text);
            var r = doc.RootElement;
            var cmd = r.GetProperty("cmd").GetString();
            if (cmd == "heartbeat") return; // liveness only, applied above
            if (r.TryGetProperty("seq", out var sq))
            {
                var n = sq.GetInt64();
                if (lastSeq >= 0 && n != lastSeq + 1)
                    Program.Log($"Live connection frame gap: expected {lastSeq + 1}, got {n} ({n - lastSeq - 1} lost).");
                lastSeq = n;
            }
            switch (cmd)
            {
                case "move": InputSender.Move((float)r.GetProperty("dx").GetDouble(), (float)r.GetProperty("dy").GetDouble()); break;
                case "button":
                    var b = r.GetProperty("button").GetString() ?? "left";
                    var down = r.GetProperty("down").GetBoolean();
                    if (down) pressed.Add(b); else pressed.Remove(b);
                    InputSender.Button(b, down);
                    break;
                case "scroll": InputSender.Scroll((float)r.GetProperty("dy").GetDouble()); break;
                case "zoom": InputSender.Zoom((float)r.GetProperty("delta").GetDouble()); break;
            }
        }
        catch { }
    }

    /// <summary>503 with a reason the phone can show, instead of a bare "internal" error.</summary>
    private static void NoAudio(HttpListenerResponse res, VolumeControl.NoAudioDeviceException ex)
    {
        Reply(res, 503, JsonSerializer.Serialize(new { error = "no audio device", detail = ex.Message }));
    }

    private static string ReadBody(HttpListenerRequest req)
    {
        if (req.ContentLength64 > 64 * 1024) throw new InvalidDataException("Request too large");
        using var reader = new StreamReader(req.InputStream, Encoding.UTF8);
        var s = reader.ReadToEnd();
        if (s.Length > 64 * 1024) throw new InvalidDataException("Request too large");
        return s;
    }

    private static void Reply(HttpListenerResponse res, int code, string json)
    {
        var bytes = Encoding.UTF8.GetBytes(json);
        res.StatusCode = code;
        res.ContentType = "application/json";
        res.ContentLength64 = bytes.Length;
        res.OutputStream.Write(bytes, 0, bytes.Length);
        res.OutputStream.Close();
    }

    /// <summary>Loopback or RFC1918 / link-local: a home network, not the internet.</summary>
    internal static bool IsPrivateAddress(IPAddress ip)
    {
        if (IPAddress.IsLoopback(ip)) return true;
        var b = ip.GetAddressBytes();
        if (b.Length != 4) return false;
        return b[0] == 10
               || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
               || (b[0] == 192 && b[1] == 168)
               || (b[0] == 169 && b[1] == 254);
    }

    private static bool ConstantTimeEq(string a, string b)
    {
        var ab = Encoding.UTF8.GetBytes(a);
        var bb = Encoding.UTF8.GetBytes(b);
        return CryptographicOperations.FixedTimeEquals(
            ab.Length == bb.Length ? ab : new byte[bb.Length], bb) && a.Length == b.Length;
    }

    public void Dispose()
    {
        _running = false;
        try { _listener.Stop(); } catch { }
    }
}

/// <summary>Answers UDP discovery broadcasts so the phone can find this PC by itself.</summary>
internal sealed class DiscoveryResponder : IDisposable
{
    private UdpClient? _udp;
    private Thread? _thread;
    private volatile bool _running;

    public void Start()
    {
        _udp = new UdpClient(Program.DiscoveryPort) { EnableBroadcast = true };
        _running = true;
        _thread = new Thread(Loop) { IsBackground = true };
        _thread.Start();
    }

    private void Loop()
    {
        var any = new IPEndPoint(IPAddress.Any, 0);
        while (_running)
        {
            try
            {
                var data = _udp!.Receive(ref any);
                if (!HttpServer.IsPrivateAddress(any.Address)) continue;
                if (Encoding.UTF8.GetString(data).Trim() != Program.DiscoverMagic) continue;
                var payload = $"{Program.ReplyMagic}|{Program.MachineLabel()}|{Program.HttpPort}";
                var reply = Encoding.UTF8.GetBytes(payload);
                _udp.Send(reply, reply.Length, any);
            }
            catch { if (!_running) break; }
        }
    }

    public void Dispose()
    {
        _running = false;
        try { _udp?.Close(); } catch { }
    }
}
