using System.Net;
using System.Text;
using System.Text.Json;

namespace BingeMoteHelper;

/// <summary>
/// LAN HTTP API for the paired phone. Every endpoint except /ping and /pair needs the
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
            Task.Run(() => Handle(ctx));
        }
    }

    private void Handle(HttpListenerContext ctx)
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
        return Program.Token != null && token == Program.Token;
    }

    private void HandlePair(HttpListenerRequest req, HttpListenerResponse res)
    {
        if (Program.PairFailures >= Program.MaxPairFailures)
        {
            Reply(res, 429, "{\"error\":\"locked\",\"detail\":\"Too many wrong codes. Restart the helper on the PC to try again.\"}");
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
            Reply(res, 403, "{\"error\":\"bad code\"}");
            return;
        }
        Program.Token = Program.NewToken();
        Program.PairCode = null;
        Program.PairFailures = 0;
        Program.SaveConfig();
        Reply(res, 200, JsonSerializer.Serialize(new { token = Program.Token, name = Program.MachineLabel() }));
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

    private static string ReadBody(HttpListenerRequest req)
    {
        using var reader = new StreamReader(req.InputStream, Encoding.UTF8);
        var body = reader.ReadToEnd();
        return body.Length > 64 * 1024 ? body[..(64 * 1024)] : body;
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
