using System.Net.Http;
using System.Text.Json;

namespace BingeMoteHelper;

/// <summary>
/// Automatic update check against the project's public GitHub releases. Runs on launch and
/// once a day; only talks to api.github.com over HTTPS; every failure is silent. It never
/// downloads or installs anything by itself: a newer version becomes a tray notification
/// and a menu item that opens the release page for him to choose.
/// </summary>
internal static class UpdateCheck
{
    private const string Api = "https://api.github.com/repos/MtndaleRedneck/BingeMote/releases/latest";
    public const string ReleasePage = "https://github.com/MtndaleRedneck/BingeMote/releases/latest";

    /// <summary>The published version if it is newer than [current] and ships a helper exe, else null. Never throws.</summary>
    public static (string Version, string Url)? LatestNewerThan(string current)
    {
        try
        {
            using var http = new HttpClient();
            http.DefaultRequestHeaders.UserAgent.ParseAdd("BingeMoteHelper");
            http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
            http.Timeout = TimeSpan.FromSeconds(8);
            var json = http.GetStringAsync(Api).GetAwaiter().GetResult();
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.TryGetProperty("draft", out var d) && d.GetBoolean()) return null;
            if (root.TryGetProperty("prerelease", out var p) && p.GetBoolean()) return null;
            var tag = (root.TryGetProperty("tag_name", out var t) ? t.GetString() : null) ?? "";
            var remote = Parse(tag);
            var local = Parse(current);
            if (remote == null || local == null) return null;
            for (var i = 0; i < Math.Max(remote.Length, local.Length); i++)
            {
                var a = i < remote.Length ? remote[i] : 0;
                var b = i < local.Length ? local[i] : 0;
                if (a != b) { if (a < b) return null; break; }
                if (i == Math.Max(remote.Length, local.Length) - 1) return null; // identical
            }
            // Only nag when the release actually carries a helper exe.
            if (root.TryGetProperty("assets", out var assets))
            {
                foreach (var a in assets.EnumerateArray())
                {
                    var name = a.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                    if (name.StartsWith("BingeMoteHelper") && name.EndsWith(".exe"))
                        return (tag.TrimStart('v', 'V'), ReleasePage);
                }
            }
            return null;
        }
        catch { return null; }
    }

    /// <summary>"v1.8.0" or "1.8.0.0" as numbers, or null when it is not a plain dotted version.</summary>
    private static int[]? Parse(string s)
    {
        var t = s.Trim().TrimStart('v', 'V');
        var parts = t.Split('.');
        if (parts.Length is < 1 or > 4) return null;
        var nums = new int[parts.Length];
        for (var i = 0; i < parts.Length; i++)
            if (!int.TryParse(parts[i], out nums[i]) || nums[i] is < 0 or > 9999) return null;
        return nums;
    }
}
