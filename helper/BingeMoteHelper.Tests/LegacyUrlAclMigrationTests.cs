using BingeMoteHelper;
using System.Diagnostics;
using Xunit;
using Xunit.Abstractions;

namespace BingeMoteHelper.Tests;

[CollectionDefinition("HTTP reservation", DisableParallelization = true)]
public class HttpReservationCollection { }

[Collection("HTTP reservation")]
public class LegacyUrlAclMigrationTests
{
    private readonly ITestOutputHelper _output;
    public LegacyUrlAclMigrationTests(ITestOutputHelper output) => _output = output;

    private static int Netsh(string args)
    {
        using var p = Process.Start(new ProcessStartInfo("netsh", args)
        { UseShellExecute = false, CreateNoWindow = true })!;
        p.WaitForExit();
        return p.ExitCode;
    }

    [Fact]
    public void LegacyEveryoneGrantIsRejectedAndElevatedSetupReplacesIt()
    {
        // This test deletes and recreates the machine's real http://+:8488/ URL
        // reservation and runs the elevated setup. Only run where that is safe:
        // CI opts in via BINGEMOTE_URLACL_E2E=1 on the disposable runner. On a
        // developer machine it skips so a local test run cannot alter the dev's
        // own reservation.
        // Markers let CI prove from the trx whether the E2E ran or skipped - a
        // green row alone cannot distinguish the two.
        if (Environment.GetEnvironmentVariable("BINGEMOTE_URLACL_E2E") != "1")
        {
            _output.WriteLine("URLACL_E2E:SKIPPED opt-in not set");
            return;
        }
        _output.WriteLine("URLACL_E2E:EXECUTED");
        Netsh("http delete urlacl url=http://+:8488/");
        Assert.Equal(0, Netsh("http add urlacl url=http://+:8488/ sddl=D:(A;;GX;;;S-1-1-0)"));
        Assert.False(FirewallSetup.UrlAclExists());
        var root = Path.Combine(Path.GetTempPath(), "bm-acl-" + Guid.NewGuid().ToString("N"));
        var marker = Path.Combine(root, "firewall.done");
        try
        {
            Assert.Equal(0, FirewallSetup.RunElevated(marker));
            Assert.True(File.Exists(marker));
            Assert.True(FirewallSetup.UrlAclExists());
            using var server = new HttpServer();
            server.Start();
        }
        finally
        {
            Netsh("http delete urlacl url=http://+:8488/");
            try { Directory.Delete(root, true); } catch { }
        }
    }
}
