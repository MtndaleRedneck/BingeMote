using System.Diagnostics;
using System.Net.Http;
using System.Security.Cryptography;

namespace BingeMoteHelper;

/// <summary>
/// Downloads the installer for an available update, checks it against the release's
/// SHA-256, then hands it to Windows. Only GitHub hosts are followed. Nothing installs
/// on its own: the installer still shows and Windows asks for permission once.
/// </summary>
internal static class SelfUpdate
{
    private static readonly HttpClient Http = new();

    /// <summary>Fetch the installer to the temp folder and verify its SHA-256 when the
    /// release published one. Returns the local path; throws on any failure. A download that
    /// stalls for 60 seconds is canceled so the button never sits on "Downloading..." forever.
    /// Slow connections may take as long as they need: only silence counts.</summary>
    public static async Task<string> Download(string version, string exeUrl, string? sha256, IProgress<int>? progress = null)
    {
        using var stall = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        var current = exeUrl;
        for (var hops = 0; ; hops++)
        {
            var host = new Uri(current).Host;
            if (!host.Equals("github.com", StringComparison.OrdinalIgnoreCase) &&
                !host.EndsWith(".githubusercontent.com", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("The download address was not trusted: " + host);
            using var req = new HttpRequestMessage(HttpMethod.Get, current);
            req.Headers.UserAgent.ParseAdd("BingeMotePC");
            using var resp = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, stall.Token);
            var code = (int)resp.StatusCode;
            if (code is >= 300 and < 400)
            {
                var loc = resp.Headers.Location ?? throw new InvalidOperationException("The download could not start.");
                current = new Uri(new Uri(current), loc).ToString();
                if (hops >= 5) throw new InvalidOperationException("The download redirected too many times.");
                continue;
            }
            resp.EnsureSuccessStatusCode();
            var dest = Path.Combine(Path.GetTempPath(), "BingeMote-PC-" + version + ".exe");
            var total = resp.Content.Headers.ContentLength ?? -1;
            await using (var fs = File.Create(dest))
            {
                await using var src = await resp.Content.ReadAsStreamAsync(stall.Token);
                var buf = new byte[64 * 1024];
                long got = 0;
                int n;
                while ((n = await src.ReadAsync(buf, stall.Token)) > 0)
                {
                    await fs.WriteAsync(buf.AsMemory(0, n), stall.Token);
                    got += n;
                    // Every arrived chunk proves the download is alive: the 60 seconds only
                    // ever count silence.
                    stall.CancelAfter(TimeSpan.FromSeconds(60));
                    if (total > 0) progress?.Report((int)(got * 100 / total));
                }
            }
            if (sha256 != null)
            {
                using var sha = SHA256.Create();
                await using var rs = File.OpenRead(dest);
                var hex = Convert.ToHexString(await sha.ComputeHashAsync(rs)).ToLowerInvariant();
                if (!hex.Equals(sha256, StringComparison.OrdinalIgnoreCase))
                {
                    try { File.Delete(dest); } catch { }
                    throw new InvalidOperationException("The download did not match the release's SHA-256.");
                }
            }
            return dest;
        }
    }

    /// <summary>Open the downloaded installer (it asks Windows for permission once) and
    /// quit so it can replace the running exe.</summary>
    public static void LaunchAndQuit(string path)
    {
        Program.Log("Starting the downloaded installer silently, quitting for the update.");
        // Tell the watchdog an install is about to run so its 5-minute check cannot start a
        // copy mid-install. Fresh for 3 minutes; any normal start deletes it.
        try { File.WriteAllText(Program.UpdatingMarkerPath, DateTime.UtcNow.ToString("O")); } catch { }
        // One Windows permission prompt, a small progress bar, done: no wizard pages,
        // and it keeps the choices from the first install. The installer relaunches the app.
        Process.Start(new ProcessStartInfo(path)
        {
            UseShellExecute = true,
            Arguments = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS",
        });
        System.Windows.Forms.Application.Exit();
    }
}
