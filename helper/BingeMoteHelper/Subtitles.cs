using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BingeMoteHelper;

/// <summary>Only sidecar SRTs next to VLC's current local movie under the configured root.
/// Paths are derived on the PC, never accepted from the phone. Reparse points are refused.</summary>
internal static class Subtitles
{
    internal sealed record Candidate(string Id, string Name, int[] TrackIds);
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, int[]> Loaded = new();
    internal sealed record Snapshot(string MediaId, string MediaPath, Candidate[] Files, bool Truncated);
    private static readonly SemaphoreSlim LoadGate = new(1, 1);
    private static readonly AsyncLocal<CancellationToken> OperationToken = new();
    private static long latestSelection;
    private static string Hash(string s) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s)));

    internal static string? ConfinedFile(string root, string file)
    {
        try {
            if (string.IsNullOrWhiteSpace(root) || !Path.IsPathFullyQualified(root) || !Path.IsPathFullyQualified(file)) return null;
            if (root.StartsWith(@"\\") || file.StartsWith(@"\\")) return null;
            var r = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar);
            var f = Path.GetFullPath(file);
            if (!f.StartsWith(r + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) return null;
            // Reject links on the entire chain, including the configured root's ancestors.
            for (var at = new DirectoryInfo(Path.GetDirectoryName(f)!); at != null; at = at.Parent)
                if ((at.Attributes & FileAttributes.ReparsePoint) != 0) return null;
            if (!File.Exists(f) || (File.GetAttributes(f) & FileAttributes.ReparsePoint) != 0) return null;
            return f;
        } catch { return null; }
    }

    internal static Snapshot? SnapshotFor(string root, string file, string playbackId)
    {
        var f = ConfinedFile(root, file);
        if (!new[] { ".mkv", ".mp4", ".m4v", ".avi", ".mov", ".wmv", ".ts", ".mpg", ".mpeg", ".webm", ".flv", ".m2ts" }.Contains(Path.GetExtension(file).ToLowerInvariant())) return null;
        if (f == null) return null;
        var mediaId = Hash(f.ToUpperInvariant() + "|" + playbackId);
        var entries = new List<Candidate>();
        // One directory only. Bound entries before serializing to the phone.
        var scanned = 0; var truncated = false;
        foreach (var entry in Directory.EnumerateFiles(Path.GetDirectoryName(f)!)) {
            if (++scanned > 4096) { truncated = true; break; }
            if (!Path.GetExtension(entry).Equals(".srt", StringComparison.OrdinalIgnoreCase)) continue;
            var safe = ConfinedFile(root, entry);
            if (safe == null) continue;
            var name = Path.GetFileName(safe);
            if (name.Length > 255) continue;
            var candidateId = Hash(mediaId + "|" + name.ToUpperInvariant());
            entries.Add(new Candidate(candidateId, name, Loaded.TryGetValue(candidateId, out var ids) ? ids.Take(32).ToArray() : Array.Empty<int>()));
            if (entries.Count >= 200) { truncated = true; break; }
        }
        return new Snapshot(mediaId, f, entries.OrderBy(e => e.Name, StringComparer.OrdinalIgnoreCase).ToArray(), truncated);
    }

    private static int[] TrackIds(JsonDocument status) {
        try {
            return status.RootElement.GetProperty("information").GetProperty("category").EnumerateObject()
                .Where(p => p.Name.StartsWith("Stream ") && p.Value.TryGetProperty("Type", out var type) && string.Equals(type.GetString(), "Subtitle", StringComparison.OrdinalIgnoreCase))
                .Select(p => int.TryParse(p.Name[7..], out var id) ? id : -1).Where(id => id >= 0).ToArray();
        } catch { return Array.Empty<int>(); }
    }
    private static async Task<JsonDocument> Get(int port, string password, string page) {
        using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(4) };
        client.DefaultRequestHeaders.Authorization = new("Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(":" + password)));
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(OperationToken.Value);
        timeout.CancelAfter(TimeSpan.FromSeconds(4));
        using var response = await client.GetAsync($"http://127.0.0.1:{port}/requests/{page}", HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync();
        using var buffer = new MemoryStream();
        var chunk = new byte[8192];
        while (true) {
            var count = await stream.ReadAsync(chunk, timeout.Token);
            if (count == 0) break;
            if (buffer.Length + count > 2 * 1024 * 1024) throw new IOException("response too large");
            buffer.Write(chunk, 0, count);
        }
        return JsonDocument.Parse(buffer.ToArray());
    }
    private static IEnumerable<JsonElement> Nodes(JsonElement e) {
        yield return e;
        if (e.TryGetProperty("children", out var children) && children.ValueKind == JsonValueKind.Array)
            foreach (var child in children.EnumerateArray()) foreach (var node in Nodes(child)) yield return node;
    }
    private static async Task<Snapshot?> Current(string root, int port, string password, bool enumerate = true) {
        using var status = await Get(port, password, "status.json");
        if (!status.RootElement.TryGetProperty("state", out var state) || state.GetString() is not ("playing" or "paused")) return null;
        using var playlist = await Get(port, password, "playlist.json");
        var current = Nodes(playlist.RootElement).Where(n => n.TryGetProperty("current", out _) && n.TryGetProperty("uri", out _)).ToArray();
        if (current.Length != 1) return null;
        var item = current[0];
        if (!Uri.TryCreate(item.GetProperty("uri").GetString(), UriKind.Absolute, out var uri)) return null;
        var id = item.TryGetProperty("id", out var val) ? val.ToString() : "";
        // Playlist IDs can restart from the same value after VLC restarts. Include
        // its process lifetime so cached track IDs never bind to a later instance.
        var processes = System.Diagnostics.Process.GetProcessesByName("vlc");
        try {
            if (enumerate && processes.Length != 1) return null;
            id += "|port:" + port + "|" + string.Join("|",processes.Select(p => {
                try { return p.Id + ":" + p.StartTime.ToUniversalTime().Ticks; }
                catch { return p.Id + ":unknown"; }
            }).OrderBy(v=>v));
        } finally { foreach (var process in processes) process.Dispose(); }
        if (!enumerate || !uri.IsFile || uri.IsUnc)
            return new Snapshot(Hash((uri.IsFile && !uri.IsUnc ? uri.LocalPath.ToUpperInvariant() : uri.AbsoluteUri) + "|" + id), "", Array.Empty<Candidate>(), false);
        return SnapshotFor(root, uri.LocalPath, id) ?? new Snapshot(Hash(uri.LocalPath.ToUpperInvariant() + "|" + id), uri.LocalPath, Array.Empty<Candidate>(), false);
    }
    internal static async Task<string> Media(int port, string password) {
        var current = await Current("", port, password, false);
        return JsonSerializer.Serialize(new { mediaId = current?.MediaId ?? "" });
    }
    internal static async Task<string> List(string root, int port, string password) {
        try {
            var snapshot = await Current(root, port, password);
            return JsonSerializer.Serialize(new { supported = true, mediaId = snapshot?.MediaId ?? "", files = snapshot?.Files ?? Array.Empty<Candidate>(), truncated = snapshot?.Truncated ?? false }, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
        } catch { return "{\"supported\":true,\"mediaId\":\"\",\"files\":[],\"unavailable\":true}"; }
    }
    internal static async Task<(int status, string body)> Load(string root, int port, string password, string mediaId, string candidateId) {
        if (mediaId.Length != 64 || candidateId.Length != 64) return (400, "{\"error\":\"invalid selection\"}");
        var selection = Interlocked.Increment(ref latestSelection);
        if (!await LoadGate.WaitAsync(TimeSpan.FromSeconds(30))) return (429, "{\"error\":\"subtitle busy\"}");
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        OperationToken.Value = deadline.Token;
        var mutationSent = false;
        try {
            if (selection != Volatile.Read(ref latestSelection)) return (409, "{\"error\":\"selection changed\"}");
            var snapshot = await Current(root, port, password);
            if (snapshot == null || snapshot.MediaId != mediaId) return (409, "{\"error\":\"movie changed\"}");
            var candidate = snapshot.Files.SingleOrDefault(c => c.Id == candidateId);
            if (candidate == null) return (409, "{\"error\":\"subtitle changed\"}");
            var file = ConfinedFile(root, Path.Combine(Path.GetDirectoryName(snapshot.MediaPath)!, candidate.Name));
            if (file == null) return (409, "{\"error\":\"subtitle unavailable\"}");
            // Re-check the actual VLC item immediately before sending the add command.
            var latest = await Current(root, port, password);
            if (latest?.MediaId != mediaId) return (409, "{\"error\":\"movie changed\"}");
            using var currentStatus = await Get(port, password, "status.json");
            var currentIds = TrackIds(currentStatus);
            if (selection != Volatile.Read(ref latestSelection)) return (409, "{\"error\":\"selection changed\"}");
            if (candidate.TrackIds.Length > 0 && candidate.TrackIds.All(currentIds.Contains)) {
                mutationSent = true;
                using var selected = await Get(port, password, "status.json?command=subtitle_track&val=" + candidate.TrackIds[0]);
                return (200, JsonSerializer.Serialize(new { ok = true, trackIds = candidate.TrackIds }));
            }
            var beforeIds = currentIds;
            if (selection != Volatile.Read(ref latestSelection)) return (409, "{\"error\":\"selection changed\"}");
            mutationSent = true;
            using var response = await Get(port, password, "status.json?command=addsubtitle&val=" + Uri.EscapeDataString(file));
            var added = TrackIds(response).Except(beforeIds).ToArray();
            for (var attempt = 0; added.Length == 0 && attempt < 10; attempt++) {
                await Task.Delay(100, deadline.Token);
                using var after = await Get(port, password, "status.json");
                added = TrackIds(after).Except(beforeIds).ToArray();
            }
            if (added.Length == 0) return (503, "{\"error\":\"subtitle not confirmed\",\"unknown\":true}");
            if (Loaded.Count > 1000) Loaded.Clear();
            if (selection != Volatile.Read(ref latestSelection)) return (409, "{\"error\":\"selection changed\",\"unknown\":true}");
            using var selectedTrack = await Get(port, password, "status.json?command=subtitle_track&val=" + added[0]);
            Loaded[candidate.Id] = added;
            return (200, JsonSerializer.Serialize(new { ok = true, trackIds = added }));
        } catch { return (503, JsonSerializer.Serialize(new { error = "subtitle unavailable", unknown = mutationSent })); }
        finally { OperationToken.Value = default; LoadGate.Release(); }
    }
    // All subtitle mutations, including Off, share the same gate. The caller's HTTP
    // timeout cannot let a direct-VLC Off overtake a still-running sidecar operation.
    internal static async Task<(int status, string body)> Select(int port, string password, int trackId, string? root = null, string? mediaId = null) {
        if (trackId < -1) return (400, "{\"error\":\"invalid track\"}");
        var selection = Interlocked.Increment(ref latestSelection);
        if (!await LoadGate.WaitAsync(TimeSpan.FromSeconds(30))) return (429, "{\"error\":\"subtitle busy\"}");
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        OperationToken.Value = deadline.Token;
        var mutationSent = false;
        try {
            if (selection != Volatile.Read(ref latestSelection)) return (409, "{\"error\":\"selection changed\"}");
            if (root != null) {
                var current = await Current(root, port, password, false);
                if (current == null || current.MediaId != mediaId) return (409, "{\"error\":\"movie changed\"}");
            }
            mutationSent = true;
            using var response = await Get(port, password, "status.json?command=subtitle_track&val=" + trackId);
            return (200, "{\"ok\":true}");
        } catch { return (503, JsonSerializer.Serialize(new { error = "subtitle unavailable", unknown = mutationSent })); }
        finally { OperationToken.Value = default; LoadGate.Release(); }
    }

}
