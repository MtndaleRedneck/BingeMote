using BingeMoteHelper;
using System.Diagnostics;
using System.Text.Json;
using Xunit;

namespace BingeMoteHelper.Tests;

public class SubtitleTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "bm-subs-" + Guid.NewGuid().ToString("N"));
    public SubtitleTests() => Directory.CreateDirectory(root);
    public void Dispose() { try { Directory.Delete(root, true); } catch { } }
    private string FileAt(string relative) { var path = Path.Combine(root, relative); Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.WriteAllText(path, "fixture"); return path; }
    [Fact] public void DirectParentOnlyAndOpaqueIds() {
        var movie = FileAt(@"Movie\movie.mp4"); FileAt(@"Movie\English.srt"); FileAt(@"Movie\Spanish.SRT");
        FileAt(@"Movie\Child\Other.srt"); FileAt(@"Other\Other.srt"); FileAt(@"Movie\text.txt");
        var s = Subtitles.SnapshotFor(root, movie, "1")!;
        Assert.Equal(new[] { "English.srt", "Spanish.SRT" }, s.Files.Select(f => f.Name));
        Assert.All(s.Files, f => Assert.Equal(64, f.Id.Length));
        Assert.NotEqual(s.MediaId, Subtitles.SnapshotFor(root, movie, "2")!.MediaId);
    }
    [Fact] public void OutsideAndPrefixSiblingAreRefused() {
        var outside = root + "-other"; Directory.CreateDirectory(outside);
        try { var file = Path.Combine(outside, "movie.mp4"); File.WriteAllText(file, "x"); Assert.Null(Subtitles.SnapshotFor(root, file, "1")); }
        finally { Directory.Delete(outside, true); }
        Assert.Null(Subtitles.ConfinedFile(root, @"\\server\share\movie.mp4"));
        Assert.Null(Subtitles.ConfinedFile(root, "relative.mp4"));
    }
    [Fact] public void DirectoryJunctionEscapeIsRefused() {
        var outside = root + "-outside"; Directory.CreateDirectory(outside); var link = Path.Combine(root, "Linked");
        try {
            File.WriteAllText(Path.Combine(outside, "movie.mp4"), "x");
            var p = Process.Start(new ProcessStartInfo("cmd.exe", $"/c mklink /J \"{link}\" \"{outside}\"") { CreateNoWindow = true, UseShellExecute = false })!;
            p.WaitForExit(); Assert.Equal(0, p.ExitCode);
            Assert.Null(Subtitles.SnapshotFor(root, Path.Combine(link, "movie.mp4"), "1"));
        } finally { if (Directory.Exists(link)) Directory.Delete(link); Directory.Delete(outside, true); }
    }
    [Fact] public async Task InvalidAndStaleIdsNeverLoad() {
        var result = await Subtitles.Load(root, 1, "x", "bad", "bad"); Assert.Equal(400, result.status);
        var unavailable = await Subtitles.Load(root, 1, "x", new string('A',64), new string('B',64)); Assert.Equal(503, unavailable.status);
    }
    [Fact] public async Task RealVlcAddsSidecarWithoutRestartAndReusesLoadedTrack() {
        var fixture = Environment.GetEnvironmentVariable("BINGEMOTE_SUBTITLE_VIDEO");
        if (string.IsNullOrEmpty(fixture)) return; // CI sets this explicitly in the real-VLC job.
        var movie = Path.Combine(root, "Movie", "movie.mp4"); Directory.CreateDirectory(Path.GetDirectoryName(movie)!); File.Copy(fixture, movie);
        File.WriteAllText(Path.Combine(Path.GetDirectoryName(movie)!, "manual.en.srt"), "1\r\n00:00:00,000 --> 00:01:00,000\r\nSubtitle fixture\r\n");
        var vlc = @"C:\Program Files\VideoLAN\VLC\vlc.exe";
        var psi = new ProcessStartInfo(vlc) { UseShellExecute = false };
        foreach (var arg in new[] { "-I", "dummy", "--aout=dummy", "--vout=dummy", "--extraintf=http", "--http-host=127.0.0.1", "--http-port=8099", "--http-password=fixture", "--no-sub-autodetect-file", "--loop", movie }) psi.ArgumentList.Add(arg);
        using var process = Process.Start(psi)!;
        try {
            JsonDocument? listing = null;
            for (var i=0;i<40;i++) { listing?.Dispose(); listing = JsonDocument.Parse(await Subtitles.List(root,8099,"fixture")); if (listing.RootElement.GetProperty("files").GetArrayLength()>0) break; await Task.Delay(250); }
            using (listing) {
                var media = listing!.RootElement.GetProperty("mediaId").GetString()!;
                var id = listing.RootElement.GetProperty("files")[0].GetProperty("id").GetString()!;
                var loaded = await Subtitles.Load(root,8099,"fixture",media,id); Assert.True(loaded.status == 200, loaded.body);
                using var proof = JsonDocument.Parse(loaded.body); Assert.True(proof.RootElement.GetProperty("trackIds").GetArrayLength()>0);
                var repeat = await Subtitles.Load(root,8099,"fixture",media,id); Assert.Equal(200,repeat.status);
                using var p2 = JsonDocument.Parse(repeat.body); Assert.Equal(proof.RootElement.GetProperty("trackIds").ToString(),p2.RootElement.GetProperty("trackIds").ToString());
                var stale = await Subtitles.Load(root,8099,"fixture",new string('F',64),id); Assert.Equal(409,stale.status);
            }
        } finally { if (!process.HasExited) { process.Kill(true); process.WaitForExit(); } }
    }
}
