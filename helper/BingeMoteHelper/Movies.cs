// Movies: let the phone browse the configured movies folder and start a movie even when
// VLC is not running yet. Paths are jailed to the configured folder, and only common
// video files can be launched.
using System.Diagnostics;
using System.IO;
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
        if (root.Length == 0) return Error(400, "no movies folder", "Pick a movies folder first in the phone app's Settings.");
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

    /// <summary>List subfolders of any folder (drives when path is empty) so the phone can
    /// pick the movies folder with VLC closed. Folder names only, never files, read-only.</summary>
    public static string Browse(string path)
    {
        try
        {
            if (path.Length == 0)
            {
                var drives = new List<object>();
                foreach (var d in DriveInfo.GetDrives())
                    if (d.IsReady && (d.DriveType == DriveType.Fixed || d.DriveType == DriveType.Removable))
                        drives.Add(new { name = d.Name, dir = true });
                return JsonSerializer.Serialize(new { path = "", entries = drives });
            }
            // A bare drive letter ("C:") is drive-relative on Windows, not the root; mean the root.
            if (path.Length == 2 && char.IsLetter(path[0]) && path[1] == ':') path += "\\";
            // Local drives only (fixed or a removable USB stick), drive-rooted ("C:\..."):
            // no UNC shares (a token holder must not reach the network) and no relative paths.
            if (path.Length < 3 || !char.IsLetter(path[0]) || path[1] != ':' || (path[2] != '\\' && path[2] != '/'))
                return Error(404, "not found", "That folder does not exist on the PC.");
            var letter = char.ToUpperInvariant(path[0]);
            var localDrive = false;
            foreach (var d in DriveInfo.GetDrives())
                if ((d.DriveType == DriveType.Fixed || d.DriveType == DriveType.Removable)
                    && char.ToUpperInvariant(d.Name[0]) == letter) { localDrive = true; break; }
            if (!localDrive) return Error(404, "not found", "That folder does not exist on the PC.");
            var full = Path.GetFullPath(path);
            if (!Directory.Exists(full)) return Error(404, "not found", "That folder does not exist on the PC.");
            var entries = new List<object>();
            foreach (var d in Directory.GetDirectories(full).OrderBy(d => d, StringComparer.OrdinalIgnoreCase))
            {
                var name = Path.GetFileName(d);
                entries.Add(new { name = name.Length == 0 ? d : name, dir = true });
            }
            return JsonSerializer.Serialize(new { path = full, entries });
        }
        catch (UnauthorizedAccessException) { return Error(404, "not found", "Windows won't let BingeMote look in that folder."); }
        catch (Exception) { return Error(404, "not found", "That folder does not exist on the PC."); }
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
