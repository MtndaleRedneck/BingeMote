// Remembers where each movie was left, so the phone can offer Resume / Start over the
// next time it is tapped. The helper polls VLC's own web interface every 5 s while VLC
// runs (it already carries the password), keeps the place per file on disk, and suppresses
// VLC's own "continue?" prompt for phone-launched plays.
using System.Diagnostics;
using System.Net;
using System.Text.Json;

namespace BingeMoteHelper;

internal static class ResumeTracker
{
    private sealed class Entry
    {
        public double Position { get; set; }
        public double Length { get; set; }
    }

    private static readonly object Gate = new();
    private static readonly Dictionary<string, Entry> ByFile = new(StringComparer.OrdinalIgnoreCase);
    private static string? _password;
    private static int _port = 8080;
    private static bool _pollerStarted;
    private static DateTime _lastSave = DateTime.MinValue;
    private static string? _currentFile;

    // Settled windows: under a minute watched is not worth asking about; the last couple
    // of minutes counts as finished.
    internal const long MinAskSec = 60;
    internal const long TailSec = 120;

    private static string StorePath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BingeMote", "resume-places.json");

    /// <summary>The saved place worth asking about, or null (barely started / nearly done / never seen).</summary>
    public static (long position, long length)? Ask(string file)
    {
        lock (Gate)
        {
            if (!ByFile.TryGetValue(file, out var e)) return null;
            if (e.Position < MinAskSec || e.Length - e.Position <= TailSec) return null;
            return ((long)e.Position, (long)e.Length);
        }
    }

    public static void Clear(string file)
    {
        lock (Gate) { ByFile.Remove(file); SaveLocked(); }
    }

    /// <summary>A phone-launched play hands the web-remote password over; remember it and
    /// make sure the poller is running.</summary>
    public static void NoteLaunch(int port, string password)
    {
        lock (Gate) { _port = port; if (password.Length > 0) _password = password; }
        EnsurePoller();
    }

    /// <summary>Called once at helper startup: load saved places; poll if the password is known.</summary>
    public static void Start()
    {
        try
        {
            var path = StorePath;
            if (File.Exists(path))
            {
                var map = JsonSerializer.Deserialize<Dictionary<string, Entry>>(File.ReadAllText(path));
                if (map != null) lock (Gate) { foreach (var kv in map) ByFile[kv.Key] = kv.Value; }
            }
        }
        catch { }
        var pw = VlcSetup.EffectivePassword();
        if (pw != null && pw.Length > 0)
        {
            lock (Gate) _password ??= pw;
            EnsurePoller();
        }
    }

    private static void EnsurePoller()
    {
        lock (Gate)
        {
            if (_pollerStarted) return;
            _pollerStarted = true;
        }
        Task.Run(async () =>
        {
            while (true)
            {
                await Task.Delay(5000);
                try { await PollOnce(); } catch { }
            }
        });
    }

    private static async Task PollOnce()
    {
        string? pw; int port;
        lock (Gate) { pw = _password; port = _port; }
        if (pw == null) return;
        if (Process.GetProcessesByName("vlc").Length == 0)
        {
            // VLC closed: forget the "current" file (its last place is already saved).
            lock (Gate) { _currentFile = null; SaveLocked(); }
            return;
        }
        var status = await Get(port, pw, "status.json");
        if (status == null) return;
        using var doc = JsonDocument.Parse(status);
        var root = doc.RootElement;
        if (!root.TryGetProperty("time", out var t) || !root.TryGetProperty("length", out var l)) return;
        var state = root.TryGetProperty("state", out var st) ? st.GetString() ?? "" : "";
        if (state != "playing" && state != "paused") return;
        var file = await CurrentFile(port, pw);
        if (file == null) return;
        lock (Gate)
        {
            _currentFile = file;
            ByFile[file] = new Entry { Position = t.GetDouble(), Length = l.GetDouble() };
            if (DateTime.UtcNow - _lastSave > TimeSpan.FromSeconds(30)) SaveLocked();
        }
    }

    /// <summary>The file VLC is playing, from the playlist's current item URI.</summary>
    private static async Task<string?> CurrentFile(int port, string pw)
    {
        var pl = await Get(port, pw, "playlist.json");
        if (pl == null) return null;
        try
        {
            using var doc = JsonDocument.Parse(pl);
            foreach (var node in doc.RootElement.GetProperty("children")[0].GetProperty("children").EnumerateArray())
            {
                if (node.TryGetProperty("current", out _)
                        && node.TryGetProperty("uri", out var uri))
                {
                    var u = new Uri(uri.GetString()!);
                    return u.IsFile ? u.LocalPath : null;
                }
            }
        }
        catch { }
        return null;
    }

    private static async Task<string?> Get(int port, string pw, string page)
    {
        try
        {
            var req = WebRequest.CreateHttp($"http://127.0.0.1:{port}/requests/{page}");
            req.Headers[HttpRequestHeader.Authorization] =
                "Basic " + Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(":" + pw));
            req.Timeout = 3000;
            using var resp = await req.GetResponseAsync();
            using var sr = new StreamReader(resp.GetResponseStream());
            return await sr.ReadToEndAsync();
        }
        catch { return null; }
    }

    private static void SaveLocked()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(StorePath)!);
            File.WriteAllText(StorePath, JsonSerializer.Serialize(ByFile));
            _lastSave = DateTime.UtcNow;
        }
        catch { }
    }
}
