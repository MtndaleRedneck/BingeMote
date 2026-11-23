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
        Assert.False(string.IsNullOrEmpty(fixture), "Real VLC test requires an explicit video fixture");
        var movie = Path.Combine(root, "Movie", "movie.mp4"); Directory.CreateDirectory(Path.GetDirectoryName(movie)!); File.Copy(fixture, movie);
        File.WriteAllText(Path.Combine(Path.GetDirectoryName(movie)!, "manual.en.srt"), "1\r\n00:00:00,000 --> 00:01:00,000\r\nSubtitle fixture\r\n");
        var vlc = @"C:\Program Files\VideoLAN\VLC\vlc.exe";
        var probePath=@"C:\Program Files\VideoLAN\VLC\lua\http\requests\bm-subtitle-probe.json";
        File.WriteAllText(probePath,"<?vlc print('{\"selected\":' .. tostring(vlc.var.get(vlc.object.input(), \"spu-es\")) .. '}') ?>");
        var psi = new ProcessStartInfo(vlc) { UseShellExecute = false };
        foreach (var arg in new[] { "-I", "dummy", "--aout=dummy", "--vout=dummy", "--extraintf=http", "--http-host=127.0.0.1", "--http-port=8099", "--http-password=fixture", "--no-sub-autodetect-file", "--loop", movie }) psi.ArgumentList.Add(arg);
        using var process = Process.Start(psi)!;
        try {
            JsonDocument? listing = null;
            for (var i=0;i<40;i++) { listing?.Dispose(); listing = JsonDocument.Parse(await Subtitles.List(root,8099,"fixture")); if (listing.RootElement.GetProperty("files").GetArrayLength()>0) break; await Task.Delay(250); }
            using (listing) {
                var media = listing!.RootElement.GetProperty("mediaId").GetString()!;
                var id = listing.RootElement.GetProperty("files")[0].GetProperty("id").GetString()!;
                using var raw = new HttpClient();
                raw.DefaultRequestHeaders.Authorization = new("Basic",Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(":fixture")));
                await raw.GetStringAsync("http://127.0.0.1:8099/requests/status.json?command=subtitle_track&val=-1");
                using var vlcVersion=JsonDocument.Parse(await raw.GetStringAsync("http://127.0.0.1:8099/requests/status.json"));
                Console.WriteLine("REAL_VLC_VERSION="+vlcVersion.RootElement.GetProperty("version").GetString()+"; fresh-runner/no-vlcrc; explicitOff; no-sub-autodetect-file; dummy audio/video");
                var addPath=Path.Combine(Path.GetDirectoryName(movie)!,"manual.en.srt");
                await raw.GetStringAsync("http://127.0.0.1:8099/requests/status.json?command=addsubtitle&val="+Uri.EscapeDataString(addPath));
                await Task.Delay(800);
                using var addOnly=JsonDocument.Parse(await raw.GetStringAsync("http://127.0.0.1:8099/requests/bm-subtitle-probe.json"));
                Assert.Equal(-1,addOnly.RootElement.GetProperty("selected").GetInt32());
                Console.WriteLine("REAL_VLC_ADD_ONLY_PRESERVES_OFF_PASS");
                var loaded = await Subtitles.Load(root,8099,"fixture",media,id); Assert.True(loaded.status == 200, loaded.body);
                using var proof = JsonDocument.Parse(loaded.body); Assert.True(proof.RootElement.GetProperty("trackIds").GetArrayLength()>0);
                var repeat = await Subtitles.Load(root,8099,"fixture",media,id); Assert.Equal(200,repeat.status);
                using var p2 = JsonDocument.Parse(repeat.body); Assert.Equal(proof.RootElement.GetProperty("trackIds").ToString(),p2.RootElement.GetProperty("trackIds").ToString());
                var stale = await Subtitles.Load(root,8099,"fixture",new string('F',64),id); Assert.Equal(409,stale.status);

            }
        } finally { if (!process.HasExited) { process.Kill(true); process.WaitForExit(); } File.Delete(probePath); }
    }
    [Fact] public async Task BoundedRequestBodyRejectsOversizeAndMalformedStream() {
        using var oversized = new MemoryStream(new byte[65537]);
        await Assert.ThrowsAsync<InvalidDataException>(() => HttpServer.ReadBoundedBody(oversized));
        using var valid = new MemoryStream(System.Text.Encoding.UTF8.GetBytes("{\"trackId\":-1}"));
        Assert.Contains("trackId",await HttpServer.ReadBoundedBody(valid));
    }
    private sealed class SlowBody : MemoryStream {
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken token = default) {
            await Task.Delay(10000); return 0;
        }
    }
    [Fact] public async Task RequestBodyHasTotalDeadlineEvenIfStreamIgnoresCancellation() {
        using var slow = new SlowBody();
        var started=Stopwatch.StartNew();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(()=>HttpServer.ReadBoundedBody(slow));
        Assert.InRange(started.Elapsed.TotalSeconds,4,7);
    }
    [Fact] public async Task DelayedSelectThenOffUsesSameGateInOrder() {
        // A delayed fake VLC models a sidecar command continuing after the phone
        // has stopped waiting. Select must wait on the same server mutation gate.
        using var listener = new System.Net.HttpListener();
        var tcp = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback,0);
        tcp.Start(); var port=((System.Net.IPEndPoint)tcp.LocalEndpoint).Port; tcp.Stop();
        listener.Prefixes.Add($"http://127.0.0.1:{port}/");listener.Start();
        var arrived = new TaskCompletionSource();var finish = new TaskCompletionSource();
        var order = new List<string>();
        var server=Task.Run(async () => {
            for(var n=0;n<2;n++) {
                var ctx=await listener.GetContextAsync();var val=ctx.Request.QueryString["val"]!;
                if(n==0) { arrived.SetResult(); await finish.Task; }
                order.Add(val);
                var bytes=System.Text.Encoding.UTF8.GetBytes("{}");ctx.Response.ContentLength64=bytes.Length;
                await ctx.Response.OutputStream.WriteAsync(bytes);ctx.Response.Close();
            }
        });
        var first=Subtitles.Select(port,"fixture",3);
        await arrived.Task.WaitAsync(TimeSpan.FromSeconds(3));
        var off=Subtitles.Select(port,"fixture",-1);
        await Task.Delay(2300);Assert.False(off.IsCompleted);
        finish.SetResult();
        Assert.Equal(200,(await first).status);Assert.Equal(200,(await off).status);
        await server;Assert.Equal(new[]{"3","-1"},order);
    }

    [Fact] public async Task SubtitleRoutesRequireTokenAndCorrectMethod() {
        using var acl = Process.Start(new ProcessStartInfo("netsh", "http add urlacl url=http://+:8488/ sddl=D:(A;;GX;;;S-1-1-0)") { UseShellExecute=false, CreateNoWindow=true });
        acl!.WaitForExit();
        using var server = new HttpServer();server.Start();
        using var client = new HttpClient();
        foreach(var path in new[]{"list","media","load","select"}) {
            using var denied = await client.GetAsync("http://127.0.0.1:8488/subtitles/"+path);
            Assert.Equal(System.Net.HttpStatusCode.Unauthorized,denied.StatusCode);
        }
        const string token="unit-subtitle-token";
        lock(Program.PhonesLock) Program.Phones.Add(new Program.PairedPhone{Token=token,Name="test"});
        try {
            client.DefaultRequestHeaders.Add("X-BingeMote-Token",token);
            foreach(var path in new[]{"load","select"}) {
                using var wrong = await client.GetAsync("http://127.0.0.1:8488/subtitles/"+path);
                Assert.Equal(System.Net.HttpStatusCode.MethodNotAllowed,wrong.StatusCode);
            }
            using var listWrong=await client.PostAsync("http://127.0.0.1:8488/subtitles/list",new StringContent("{}"));
            Assert.Equal(System.Net.HttpStatusCode.MethodNotAllowed,listWrong.StatusCode);
        } finally {lock(Program.PhonesLock) Program.Phones.RemoveAll(p=>p.Token==token);}
    }

}
