// Movies: let the phone browse the configured movies folder and start a movie even when
// VLC is not running yet. Paths are jailed to the configured folder, and only common
// video files can be launched.
using System.Diagnostics;
using System.Net;
using System.Text.Json;

namespace BingeMoteHelper;

internal static class Movies
{
    private static readonly HashSet<string> VideoExt = new(StringComparer.OrdinalIgnoreCase)
        { ".mkv", ".mp4", ".m4v", ".avi", ".mov", ".wmv", ".ts", ".mpg", ".mpeg", ".webm", ".flv", ".m2ts" };

    /// <summary>Resolve a folder-relative path, refusing anything outside the movies folder.</summary>
    private static string? Jail(string root, string rel)
    {
        if (root.Length == 0) return null;
        try
        {
            var fullRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            var full = Path.GetFullPath(Path.Combine(fullRoot, rel.Replace('/', Path.DirectorySeparatorChar)));
            if (!full.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase)) return null;
            return full;
        }
        catch { return null; }
    }

    /// <summary>List one level of the movies folder (folders first, then video files).</summary>
    public static string List(string root, string rel)
    {
        if (root.Length == 0) return Error(400, "no movies folder", "Pick a movies folder first (in this window or in the app's Settings).");
        var dir = Jail(root, rel);
        if (dir == null || !Directory.Exists(dir)) return Error(404, "not found", "That folder does not exist on the PC.");
        var entries = new List<object>();
        foreach (var d in Directory.GetDirectories(dir).OrderBy(d => d, StringComparer.OrdinalIgnoreCase))
            entries.Add(new { name = Path.GetFileName(d), dir = true });
        foreach (var f in Directory.GetFiles(dir).OrderBy(f => f, StringComparer.OrdinalIgnoreCase))
            if (VideoExt.Contains(Path.GetExtension(f)))
                entries.Add(new { name = Path.GetFileName(f), dir = false });
        var fullRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var atRoot = string.Equals(Path.GetFullPath(dir).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar,
            fullRoot, StringComparison.OrdinalIgnoreCase);
        return JsonSerializer.Serialize(new { path = rel, atRoot, entries });
    }

    /// <summary>Start VLC with the chosen file and the web interface the app expects.
    /// The web interface binds to the address the phone just used, never to every network.</summary>
    public static string Play(string root, string rel, int httpPort, string httpPassword, bool fullscreen, IPAddress bindTo)
    {
        var file = Jail(root, rel);
        if (file == null || !File.Exists(file)) return Error(404, "not found", "That file does not exist on the PC.");
        if (!VideoExt.Contains(Path.GetExtension(file))) return Error(400, "not a video", "That file is not a video.");
        var vlc = FindVlc();
        if (vlc == null) return Error(404, "vlc not found", "VLC does not seem to be installed on this PC (looked in the usual places).");
        // ArgumentList, never string concatenation: a quote in the VLC password must not
        // become extra VLC options.
        var psi = new ProcessStartInfo { FileName = vlc, UseShellExecute = false };
        psi.ArgumentList.Add(file);
        psi.ArgumentList.Add("--extraintf=http");
        psi.ArgumentList.Add("--http-host=" + BindAddress(bindTo));
        psi.ArgumentList.Add("--http-port=" + httpPort);
        psi.ArgumentList.Add("--http-password=" + httpPassword);
        if (fullscreen) psi.ArgumentList.Add("--fullscreen");
        try
        {
            Process.Start(psi);
        }
        catch (Exception ex)
        {
            return Error(500, "launch failed", ex.Message);
        }
        Program.Log("Started VLC with: " + file);
        return JsonSerializer.Serialize(new { ok = true });
    }

    /// <summary>The address VLC's web interface listens on: the one the phone reached us on.</summary>
    private static string BindAddress(IPAddress addr)
    {
        if (addr.IsIPv4MappedToIPv6) return addr.MapToIPv4().ToString();
        if (addr.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6 && !IPAddress.IsLoopback(addr))
        {
            // VLC's --http-host wants brackets for IPv6; rather than risk a malformed bind, listen on all.
            Program.Log("Phone reached us over IPv6 - VLC's web interface will listen on all addresses.");
            return "0.0.0.0";
        }
        return addr.ToString();
    }

    /// <summary>Where is vlc.exe: Windows App Paths first, then the two usual install folders.</summary>
    private static string? FindVlc()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(
                @"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\vlc.exe");
            var fromReg = key?.GetValue(null) as string;
            if (fromReg != null && File.Exists(fromReg)) return fromReg;
        }
        catch { }
        foreach (var p in new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "VideoLAN", "VLC", "vlc.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "VideoLAN", "VLC", "vlc.exe"),
        })
            if (File.Exists(p)) return p;
        return null;
    }

    private static string Error(int code, string error, string detail) =>
        JsonSerializer.Serialize(new { code, error, detail });
}
