using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class VlcRuleSpecTests
{
    private const string Vlc = @"C:\Program Files\VideoLAN\VLC\vlc.exe";

    [Fact]
    public void VlcRulesHaveNewDistinctNamesNotTheLegacyName()
    {
        var specs = FirewallSetup.VlcSpecs(Vlc, 8080);
        Assert.Equal(2, specs.Length);
        Assert.All(specs, s => Assert.NotEqual("BingeMote VLC", s.Name));
        Assert.Equal(specs.Length, specs.Select(s => s.Name).Distinct().Count());
        Assert.All(specs, s => Assert.Contains(s.Name, FirewallSetup.JournalableNames));
        Assert.DoesNotContain("BingeMote VLC", FirewallSetup.JournalableNames);
        Assert.DoesNotContain(specs.Select(s => s.Name), n => FirewallSetup.ExpectedRules.Any(r => r.Name == n));
    }

    [Fact]
    public void PrivateVlcRuleKeepsTheOldNarrownessProgramOnly()
    {
        var r = FirewallSetup.VlcSpecs(Vlc, 8080).Single(s => s.Profile == "Private");
        Assert.Equal(Vlc, r.Program);
        Assert.Equal("Any", r.Protocol);
        Assert.Equal(0, r.Port);
        Assert.Equal("Any", r.Remote);
    }

    [Theory]
    [InlineData(8080)]
    [InlineData(9090)]
    public void PublicVlcRuleIsProgramTcpHttpPortLocalSubnetOnly(int port)
    {
        var r = FirewallSetup.VlcSpecs(Vlc, port).Single(s => s.Profile == "Public");
        Assert.Equal(Vlc, r.Program);
        Assert.Equal("TCP", r.Protocol);
        Assert.Equal(port, r.Port);
        Assert.Equal("LocalSubnet4", r.Remote);
    }

    [Fact]
    public void JournalAcceptsTheVlcNamesAndStillRejectsTheLegacyName()
    {
        var dir = Path.Combine(Path.GetTempPath(), "bm-vlc-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var f = Path.Combine(dir, "lifecycle.json");
            string Entry(string name) => "{\"Key\":\"BingeMote-" + new string('c', 32) + "\",\"DisplayName\":\"" + name + "\",\"Props\":\"True|Inbound\"}";
            File.WriteAllText(f, "{\"Version\":1,\"Rules\":[" + Entry(Program.RuleVlcPublic) + "],\"UrlAcl\":false,\"Watchdog\":null,\"Uncertain\":[]}");
            Assert.Equal(MarkerState.Present, LifecycleJournalStore.Read(f).state);
            File.WriteAllText(f, "{\"Version\":1,\"Rules\":[" + Entry("BingeMote VLC") + "],\"UrlAcl\":false,\"Watchdog\":null,\"Uncertain\":[]}");
            Assert.Equal(MarkerState.Corrupt, LifecycleJournalStore.Read(f).state);
        }
        finally { Directory.Delete(dir, true); }
    }
}
