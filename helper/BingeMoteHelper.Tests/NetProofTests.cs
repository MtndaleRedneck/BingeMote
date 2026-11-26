using System.Diagnostics;
using BingeMoteHelper;
using Xunit;
using Xunit.Abstractions;

namespace BingeMoteHelper.Tests;

/// <summary>
/// CI-only proof on a disposable GitHub Windows runner (never run elsewhere): does the PRODUCTION
/// NetworkCheck.MarkPrivate (the code behind the "This is my home Wi-Fi" button) actually change
/// a network's category? Skipped unless BM_NET_PROOF=1. The runner's adapter is Ethernet, not Wi-Fi,
/// and the Public starting state is set by this test's own setup, so this is not a real-Wi-Fi test.
/// </summary>
public class NetProofTests
{
    private readonly ITestOutputHelper _o;
    public NetProofTests(ITestOutputHelper o) { _o = o; }

    private static string Ps(string script)
    {
        var psi = new ProcessStartInfo("powershell", "-NoProfile -NonInteractive -Command \"" + script + "\"")
            { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true };
        using var p = Process.Start(psi)!;
        p.WaitForExit(30000);
        return (p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd()).Trim();
    }

    [Fact]
    public void MarkPrivateChangesRealCategoryOnDisposableRunner()
    {
        if (Environment.GetEnvironmentVariable("BM_NET_PROOF") != "1") return;
        var start = NetworkCheck.DefaultRouteProfile();
        Assert.NotNull(start);
        _o.WriteLine($"START production DefaultRouteProfile: name='{start!.Name}' category={start.Category} ifIndex={start.InterfaceIndex}");
        var original = start.Category;
        try
        {
            // Test setup (hook): put the runner's own adapter into Public so there is something to fix.
            _o.WriteLine("SETUP set Public: " + Ps("Set-NetConnectionProfile -InterfaceIndex " + start.InterfaceIndex + " -NetworkCategory Public"));
            var pub = NetworkCheck.DefaultRouteProfile();
            Assert.NotNull(pub);
            _o.WriteLine($"AFTER SETUP category={pub!.Category}");
            Assert.Equal("Public", pub.Category, ignoreCase: true);
            var ok = NetworkCheck.MarkPrivate(pub); // production code, runas verb as shipped
            var after = NetworkCheck.DefaultRouteProfile();
            _o.WriteLine($"MarkPrivate returned {ok}; category now {after?.Category}");
            Assert.True(ok);
            Assert.Equal("Private", after!.Category, ignoreCase: true);
        }
        finally
        {
            _o.WriteLine("RESTORE " + original + ": " + Ps("Set-NetConnectionProfile -InterfaceIndex " + start.InterfaceIndex + " -NetworkCategory " + original));
        }
    }
}
