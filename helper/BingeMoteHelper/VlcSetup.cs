// VLC's web remote, configured for him: the phone's Finish step hands the chosen password
// to the PC, and the PC writes extraintf=http + http-password into vlcrc, so a VLC opened
// by hand has the remote too. Written only while VLC is closed - a running VLC rewrites
// vlcrc on exit and would lose the change.
using System.Diagnostics;
using System.Text.Json;

namespace BingeMoteHelper;

internal static class VlcSetup
{
    public static string WriteVlcrc(string password)
    {
        if (password.Length == 0 || password.Length > 200)
            return JsonSerializer.Serialize(new { ok = false, reason = "bad password" });
        if (Process.GetProcessesByName("vlc").Length > 0)
        {
            Program.Log("vlcrc write skipped - VLC is running (it rewrites vlcrc on exit).");
            return JsonSerializer.Serialize(new { ok = false, reason = "vlc running" });
        }
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "vlc");
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, "vlcrc");
        var lines = File.Exists(path) ? File.ReadAllLines(path).ToList() : new List<string>();
        // Merge, don't overwrite: another interface may already sit in extraintf.
        Upsert(lines, "extraintf", MergeValue(FindValue(lines, "extraintf"), "http"));
        Upsert(lines, "http-password", password);
        File.WriteAllLines(path, lines);
        Program.Log("VLC web remote written into vlcrc from the phone's Finish step.");
        return JsonSerializer.Serialize(new { ok = true });
    }

    private static string? FindValue(List<string> lines, string key)
    {
        var prefix = key + "=";
        foreach (var line in lines)
        {
            var raw = line.TrimStart();
            if (raw.StartsWith('#')) continue; // only a live setting counts
            if (raw.StartsWith(prefix, StringComparison.Ordinal)) return raw[prefix.Length..].Trim();
        }
        return null;
    }

    private static string MergeValue(string? existing, string wanted)
    {
        if (string.IsNullOrEmpty(existing)) return wanted;
        var parts = existing.Split(',').Select(p => p.Trim()).ToList();
        if (parts.Contains(wanted)) return existing;
        parts.Add(wanted);
        return string.Join(",", parts);
    }

    /// <summary>Replace the (usually commented-out) vlcrc line for a key, or append it.</summary>
    private static void Upsert(List<string> lines, string key, string value)
    {
        var prefix = key + "=";
        for (var i = 0; i < lines.Count; i++)
        {
            var t = lines[i].TrimStart().TrimStart('#').TrimStart();
            if (t.StartsWith(prefix, StringComparison.Ordinal))
            {
                lines[i] = prefix + value;
                return;
            }
        }
        lines.Add(prefix + value);
    }
}
