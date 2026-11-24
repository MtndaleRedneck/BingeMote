using BingeMoteHelper;
using System.Diagnostics;
using Xunit;

namespace BingeMoteHelper.Tests;

[CollectionDefinition("HTTP reservation", DisableParallelization = true)]
public class HttpReservationCollection { }

[Collection("HTTP reservation")]
public class LegacyUrlAclMigrationTests
{
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
