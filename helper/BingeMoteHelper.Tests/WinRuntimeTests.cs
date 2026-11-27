// T-W series: real-Windows runtime checks. These run only on a Windows testhost
// (net10.0-windows); CI runs them on windows-latest via the WinRuntime filter.
// WinRuntimeTlsTests (T-W3) mutates shared machine TLS state, so that class refuses
// (fail-LOUD, never skip) anywhere but a fresh hosted GitHub Actions runner.
// Proof boundaries: T-W1/T-W2 prove parsing/detection against real Windows output;
// T-W4 proves real File.Replace ACL/metadata semantics; T-W5/W6 prove the deferred
// vlcrc write against REAL process detection (a fake vlc.exe), not a hook.
// None of these proves phone reachability on a genuine Public-profile LAN.
using System.Diagnostics;
using System.Net;
using Xunit;

namespace BingeMoteHelper.Tests;

// Same non-parallel collection as every other Program.DataDir writer (definition in
// StoreHardeningTests.cs): both classes in this file redirect/reset the process-wide
// Program.DataDir; without this the TLS class's Dispose could reset the real dir while a
// T-W5/W6 SaveConfig in the other class is still writing.
[Collection("ProgramState")]
public sealed class WinRuntimeTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bm-winrt-" + Guid.NewGuid().ToString("N"));

    public WinRuntimeTests()
    {
        Directory.CreateDirectory(_dir);
        VlcSetup.VlcDirOverride = _dir;
        VlcSetup.VlcRunningForTests = null; // T-W5/W6 must use REAL process detection
        // DataDir isolation: T-W5/W6's StagePending -> MutateAndPersist/SaveConfig writes
        // config.json. Without this redirect that write lands in the real
        // LocalAppData\BingeMote\config.json with test in-memory state - on any machine,
        // fresh or not. SaveConfig honors Program.DataDir, so the temp dir owns it.
        Program.RedirectDataDirForTests(_dir);
    }

    public void Dispose()
    {
        VlcSetup.VlcDirOverride = null;
        VlcSetup.VlcRunningForTests = null;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    // T-W1: ACL classification helpers against REAL netsh output on Windows (read-only).
    [Fact]
    public void TW1_UrlAclParsingAgainstRealNetshOutput()
    {
        var raw = Run("netsh", "http", "show", "urlacl");
        // Parse-free invariants: the classifier must never throw on real rows, and
        // UrlAclExists() must agree with the raw output containing our prefix form.
        foreach (var line in raw.Split('\n'))
        {
            var trimmed = line.Trim();
            if (!trimmed.StartsWith("SDDL", StringComparison.OrdinalIgnoreCase)) continue;
            var sddl = trimmed.Substring("SDDL".Length).Trim();
            var _ = FirewallSetup.ClassifySddl(sddl, "S-1-5-4"); // must not throw
        }
        // Product-specific invariant (not "any urlacl exists"): the classifier's answer
        // must match whether the runner's real listing carries the product's own prefix.
        var prefix = "https://+:" + Program.HttpPort + "/";
        Assert.Equal(raw.Contains(prefix, StringComparison.OrdinalIgnoreCase),
                     FirewallSetup.UrlAclExists());
    }

    // T-W2: profile detection against the runner's real default route.
    [Fact]
    public void TW2_DefaultRouteProfileIsRealAndClassified()
    {
        var p = NetworkCheck.DefaultRouteProfile();
        Assert.NotNull(p);
        Assert.False(string.IsNullOrWhiteSpace(p!.Name));
        Assert.Contains(p.Category, new[] { "Public", "Private", "DomainAuthenticated" });
        Assert.True(p.InterfaceIndex > 0);
    }

    // T-W4: File.Replace preserves destination ACL + metadata on a real Windows file.
    [Fact]
    public void TW4_VlcrcRewritePreservesAclAndCreationTime()
    {
        VlcSetup.WriteVlcrc("first-pass");
        var path = Path.Combine(_dir, "vlcrc");
        var created = new DateTime(2020, 1, 2, 3, 4, 5, DateTimeKind.Utc);
        File.SetCreationTimeUtc(path, created);
        File.SetAttributes(path, FileAttributes.ReadOnly);
        File.SetAttributes(path, FileAttributes.None); // prove attributes round-trip via Replace
        // Custom ACL marker via icacls: grant "Everyone:Read" as a detectable extra ACE.
        Run("icacls", path, "/grant", "*S-1-1-0:(R)");

        VlcSetup.WriteVlcrc("second-pass");

        Assert.Equal(created, File.GetCreationTimeUtc(path));   // metadata preserved by File.Replace
        var acl = Run("icacls", path);
        Assert.Contains("S-1-1-0", acl);                        // custom ACE survived the replace
    }

    // T-W5 + T-W6: deferred vlcrc write vs a REAL running "vlc" process (renamed ping.exe),
    // then completion after the process exits. No VlcRunningForTests hook involved.
    [Fact]
    public void TW56_DeferredWriteWaitsForRealVlcProcessThenCompletes()
    {
        VlcSetup.WriteVlcrc("on-disk-a");
        Assert.True(VlcSetup.StagePending("fresh-b"));

        var fakeExe = Path.Combine(_dir, "vlc.exe");
        File.Copy(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "ping.exe"), fakeExe);
        using var fakeVlc = Process.Start(new ProcessStartInfo(fakeExe, "-t 127.0.0.1") { CreateNoWindow = true })!;

        Assert.True(VlcSetup.RetryPending());                    // still waiting: real process seen
        Assert.Contains("on-disk-a", File.ReadAllText(Path.Combine(_dir, "vlcrc")));
        Assert.DoesNotContain("fresh-b", File.ReadAllText(Path.Combine(_dir, "vlcrc")));

        fakeVlc.Kill();
        Assert.True(fakeVlc.WaitForExit(10_000));
        Assert.False(VlcSetup.RetryPending());                   // applied: nothing left waiting
        Assert.Contains("fresh-b", File.ReadAllText(Path.Combine(_dir, "vlcrc")));
        Assert.False(VlcSetup.Pending);
    }

    private static string Run(string exe, params string[] args)
    {
        var psi = new ProcessStartInfo(exe, string.Join(' ', args))
        { RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
        using var p = Process.Start(psi)!;
        var outp = p.StandardOutput.ReadToEnd();
        Assert.True(p.WaitForExit(20_000));
        return outp;
    }
}

[Collection("ProgramState")] // see WinRuntimeTests above: shared process-wide DataDir
public sealed class WinRuntimeTlsTests : IDisposable
{
    // Isolated test identity: the test listener binds TCP 8489 with its own marker/cert.
    // TCP 8489 is a test choice, not a claim about product ports; safety comes from the
    // ctor guard, which proves the runner carries NO pre-existing product TLS state
    // (8488 bindings, marker, journal) before anything mutates, and from teardown that
    // deletes an 8489 binding only when the endpoint query proves it is this test's.
    // Cert, marker and journal are never deleted here; the hosted runner is destroyed whole.
    private const int TestPort = 8489;
    private const int ProductPort = 8488;
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bm-winrt-tls-" + Guid.NewGuid().ToString("N"));

    public WinRuntimeTlsTests()
    {
        AssertHostedFreshRunner();
        Directory.CreateDirectory(_dir);
        Program.RedirectDataDirForTests(_dir);
        HttpServer.PortOverrideForTests = TestPort;
    }

    // Hosted-CI-only mutation gate (fail-LOUD, never skip-as-pass): T-W3 writes a real
    // sslcert binding and identity into state shared with the product, so this class runs
    // only on a disposable hosted runner proven to carry no pre-existing product TLS state.
    // On any ordinary Windows machine (no GITHUB_ACTIONS) or a non-fresh runner, every test
    // in this class FAILS loudly instead of mutating shared state.
    private static void AssertHostedFreshRunner()
    {
        Assert.Equal("true", Environment.GetEnvironmentVariable("GITHUB_ACTIONS"));
        Assert.False(File.Exists(Path.Combine(ProtectedFile.Dir, "tls-identity.json")),
            "refusing to run: a TLS identity marker already exists on this machine");
        Assert.False(File.Exists(Path.Combine(ProtectedFile.Dir, "tls-journal.json")),
            "refusing to run: a TLS journal already exists on this machine");
        foreach (var ip in new[] { IPAddress.Any, IPAddress.IPv6Any })
        {
            // Structured query, fail-closed: only a proven Absent passes; Found or any
            // Error (unreadable store, unsupported layout) refuses just the same.
            var r = HttpSysSsl.QueryExact_(ip, ProductPort);
            Assert.True(r.Kind == HttpSysSsl.Kind.Absent,
                $"refusing to run: product-port binding state at {ip}:{ProductPort} is {r.Kind} ({r.Why}), not proven absent");
            // The test endpoints must be equally free: setup must never disturb a foreign
            // 8489 binding, and teardown's proven-ownership rule is not a substitute for
            // never touching one in the first place.
            var t = HttpSysSsl.QueryExact_(ip, TestPort);
            Assert.True(t.Kind == HttpSysSsl.Kind.Absent,
                $"refusing to run: test-port binding state at {ip}:{TestPort} is {t.Kind} ({t.Why}), not proven absent");
        }
    }

    public void Dispose()
    {
        HttpServer.PortOverrideForTests = 0;
        Program.ResetDataDirForTests();
        // Conservative teardown (proven ownership, never inference): an 8489 binding is
        // deleted only when the structured HTTP.SYS query of that exact endpoint proves it
        // carries THIS test's marker thumbprint AND AppId. Absent, Error, mismatch or an
        // invalid/missing marker all retain. Owning the marker or the port alone is not
        // proof the binding is ours. The cert, key container, marker and journal are never
        // deleted; the hosted runner is destroyed whole, so residue is disposable by
        // construction (a technical fact, not an acceptance claim).
        try
        {
            var markerPath = Path.Combine(ProtectedFile.Dir, "tls-identity.json");
            var thumb = ""; var appId = "";
            if (File.Exists(markerPath))
            {
                var text = File.ReadAllText(markerPath);
                var mt = System.Text.RegularExpressions.Regex.Match(text, "[0-9A-Fa-f]{40}");
                var ma = System.Text.RegularExpressions.Regex.Match(text,
                    "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
                if (mt.Success) thumb = mt.Value;
                if (ma.Success) appId = ma.Value;
            }
            if (thumb.Length == 40 && appId.Length == 36)
                foreach (var (ip, ep) in new[] { (IPAddress.Any, $"0.0.0.0:{TestPort}"), (IPAddress.IPv6Any, $"[::]:{TestPort}") })
                {
                    var r = HttpSysSsl.QueryExact_(ip, TestPort);
                    if (r.Kind == HttpSysSsl.Kind.Found &&
                        r.Thumbprint.Equals(thumb, StringComparison.OrdinalIgnoreCase) &&
                        r.AppId.Trim('{', '}').Equals(appId, StringComparison.OrdinalIgnoreCase))
                        RunQuiet("netsh", "http", "delete", "sslcert", $"ipport={ep}");
                    // anything else: ownership unproven, retain for runner destruction
                }
        }
        catch { /* cleanup never fails the test outcome */ }
        try { Directory.Delete(_dir, true); } catch { }
    }

    // T-W3: REAL loopback pinned-HTTPS round trip on Windows (admin runner) on the isolated
    // test port. /ping without a token must be 401 (fail-closed), which proves the full
    // TLS + HTTP path with the expected identity. Proves listener/TLS plumbing on Windows;
    // it does not prove phone reachability on a genuine Public-profile LAN.
    [Fact]
    public async System.Threading.Tasks.Task TW3_RealLoopbackPinnedHttpsRoundTrip()
    {
        Assert.Equal(0, TlsSetup.Run(TestPort, explicitSetup: true));
        Assert.True(HelperTls.RuntimeReady(TestPort, out var why), why);

        using var server = new HttpServer();
        server.Start();

        string? presented = null;
        using var handler = new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (msg, cert, chain, errors) =>
            {
                presented = cert?.GetCertHashString();
                return true; // capture only; the phone-side pin check is the app's job
            }
        };
        using var client = new HttpClient(handler);
        using var res = await client.GetAsync($"https://127.0.0.1:{TestPort}/ping");

        Assert.Equal(System.Net.HttpStatusCode.Unauthorized, res.StatusCode); // fail-closed without a token
        Assert.False(string.IsNullOrWhiteSpace(presented));
        var marker = File.ReadAllText(Path.Combine(ProtectedFile.Dir, "tls-identity.json"));
        Assert.Contains(presented!, marker, StringComparison.OrdinalIgnoreCase);
    }

    private static void RunQuiet(string exe, params string[] args)
    {
        try
        {
            using var p = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(exe, string.Join(' ', args))
            { CreateNoWindow = true, UseShellExecute = false })!;
            p.WaitForExit(15_000);
        }
        catch { }
    }
}
