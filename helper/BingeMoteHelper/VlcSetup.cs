// VLC's web remote, configured for him: the phone's Finish step hands the chosen password
// to the PC, and the PC writes extraintf=http + http-password into vlcrc, so a VLC opened
// by hand has the remote too. Written only while VLC is closed - a running VLC rewrites
// vlcrc on exit and would lose the change.
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;

namespace BingeMoteHelper;

internal static class VlcSetup
{
    public static string WriteVlcrc(string password)
    {
        if (password.Length == 0 || password.Length > 200 || password.Contains('\r') || password.Contains('\n'))
            return JsonSerializer.Serialize(new { ok = false, reason = "bad password" });
        if (Process.GetProcessesByName("vlc").Length > 0)
        {
            lock (Gate) _pendingPassword = password;
            Program.SaveConfig(); // the deferred write must survive a helper restart
            Program.Log("vlcrc write skipped - VLC is running (it rewrites vlcrc on exit). Will retry when VLC closes.");
            return JsonSerializer.Serialize(new { ok = false, reason = "vlc running" });
        }
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "vlc");
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, "vlcrc");
        var lines = File.Exists(path) ? File.ReadAllLines(path).ToList() : new List<string>();
        // Merge, don't overwrite: another interface may already sit in extraintf.
        Upsert(lines, "extraintf", MergeValue(FindValue(lines, "extraintf"), "http"));
        Upsert(lines, "http-password", password);
        // Listen on every address (not just localhost) so a hand-opened VLC takes the phone
        // too; the password and the Private-only firewall rule guard it.
        Upsert(lines, "http-host", "0.0.0.0");
        File.WriteAllLines(path, lines);
        lock (Gate) _pendingPassword = null;
        Program.SaveConfig(); // clear any persisted deferred write
        Program.Log("VLC web remote written into vlcrc.");
        return JsonSerializer.Serialize(new { ok = true });
    }

    private static readonly object Gate = new();
    private static string? _pendingPassword;

    /// <summary>VLC was open, so the write was skipped; retry when VLC closes.</summary>
    public static bool Pending => _pendingPassword != null;

    /// <summary>The deferred value itself (for config.json; vlcrc is plain text anyway).
    /// Null when nothing waits.</summary>
    public static string? PendingValue { get { lock (Gate) return _pendingPassword; } }

    /// <summary>The PC's attestation for the phone's setup check: vlcrc right now carries
    /// everything phone control needs - the web interface enabled (extraintf=http), a bind
    /// the phone can reach (http-host set and not loopback-only), and a live password - and
    /// no write is still waiting. A running VLC that has not reloaded yet reports Pending
    /// instead. Password equality alone is never enough.</summary>
    public static bool Configured
    {
        get
        {
            if (Pending) return false;
            if (string.IsNullOrEmpty(ReadVlcrcValue("http-password"))) return false;
            var extraintf = ReadVlcrcValue("extraintf") ?? "";
            var parts = extraintf.Split(new[] { ',', ':', ' ' }, StringSplitOptions.RemoveEmptyEntries).Select(x => x.Trim()).ToList();
            if (!parts.Contains("http") && !parts.Contains("luahttp")) return false; // interface off
            var host = ReadVlcrcValue("http-host");
            if (string.IsNullOrEmpty(host)) return false; // VLC's default binds localhost only
            if (host is "127.0.0.1" or "localhost" or "::1") return false; // the phone could never reach it
            return true;
        }
    }

    /// <summary>Re-arm a deferred write after a helper restart: VLC still running keeps it
    /// waiting for the retry timer; VLC already closed completes it immediately.</summary>
    public static void RestorePending(string password)
    {
        if (Process.GetProcessesByName("vlc").Length > 0)
        {
            lock (Gate) _pendingPassword = password;
            Program.SaveConfig();
            Program.Log("Restored the vlcrc write deferred before the restart - it completes when VLC closes.");
            return;
        }
        Program.Log("VLC closed while BingeMote was off - completing the deferred vlcrc write now.");
        WriteVlcrc(password);
    }

    /// <summary>Called on a timer: if an earlier write lost to a running VLC and VLC has
    /// since closed, write now. True while a write is still waiting.</summary>
    public static bool RetryPending()
    {
        string? pw;
        lock (Gate) pw = _pendingPassword;
        if (pw == null) return false;
        if (Process.GetProcessesByName("vlc").Length > 0) return true;
        Program.Log("VLC closed - writing the skipped vlcrc web-remote setup now.");
        using var doc = JsonDocument.Parse(WriteVlcrc(pw));
        return doc.RootElement.GetProperty("ok").GetBoolean() == false;
    }

    /// <summary>The password the phone must use right now: a deferred write wins over
    /// vlcrc (it becomes vlcrc the moment VLC closes), and vlcrc is re-read every call so
    /// a hand edit on the PC is picked up. null when nothing is configured yet.</summary>
    public static string? EffectivePassword()
    {
        lock (Gate) { if (_pendingPassword != null) return _pendingPassword; }
        return ReadVlcrcValue("http-password");
    }

    /// <summary>The automatic-first setup: the PC picks the password. Reuses the one
    /// already configured (including a write still waiting for VLC to close); generates a
    /// fresh one only when nothing exists. The value is never logged.</summary>
    public static string ReadOrCreatePassword()
    {
        var existing = EffectivePassword();
        if (!string.IsNullOrEmpty(existing))
        {
            // A vlcrc can carry a password with the web interface itself off: the idempotent
            // write also ensures extraintf=http and http-host, not just the password.
            WriteVlcrc(existing);
            return existing;
        }
        var pw = Convert.ToHexString(RandomNumberGenerator.GetBytes(12)).ToLowerInvariant();
        WriteVlcrc(pw); // defers to the pending write when VLC is running; EffectivePassword still serves it
        Program.Log("VLC web-remote password generated for the phone (value never logged).");
        return pw;
    }

    /// <summary>The live (uncommented) value of a vlcrc key, or null. Used for the
    /// web-remote password the resume poller needs.</summary>
    internal static string? ReadVlcrcValue(string key)
    {
        try
        {
            var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "vlc", "vlcrc");
            if (!File.Exists(path)) return null;
            return FindValue(File.ReadAllLines(path).ToList(), key);
        }
        catch { return null; }
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
        // VLC's own preferences writer separates with ':' ("rc:http") and its loader also
        // takes space and ','; "luahttp" is its old name for the same interface.
        var parts = existing.Split(new[] { ',', ':', ' ' }, StringSplitOptions.RemoveEmptyEntries)
                            .Select(p => p.Trim()).ToList();
        if (parts.Contains(wanted) || (wanted == "http" && parts.Contains("luahttp"))) return existing;
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
