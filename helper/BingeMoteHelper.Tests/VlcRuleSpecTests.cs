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

public class VlcRulePureBranchTests
{
    private const string Vlc = @"C:\Program Files\VideoLAN\VLC\vlc.exe";
    private static string Key(char c) => "BingeMote-" + new string(c, 32);

    [Fact]
    public void NewRuleScriptCarriesProgramPortProtocolAndEscapesQuotes()
    {
        var pub = FirewallSetup.BuildNewRuleScript(Key('a'), FirewallSetup.VlcSpecs(Vlc, 9090).Single(s => s.Profile == "Public"));
        Assert.Contains("-Protocol TCP", pub);
        Assert.Contains("-LocalPort 9090", pub);
        Assert.Contains("-Program '" + Vlc + "'", pub);
        Assert.Contains("-Profile Public", pub);
        Assert.Contains("-RemoteAddress LocalSubnet4", pub);
        var priv = FirewallSetup.BuildNewRuleScript(Key('b'), FirewallSetup.VlcSpecs(Vlc, 9090).Single(s => s.Profile == "Private"));
        Assert.DoesNotContain("-Protocol", priv);
        Assert.DoesNotContain("-LocalPort", priv);
        Assert.DoesNotContain("-RemoteAddress", priv);
        var quoted = FirewallSetup.BuildNewRuleScript(Key('c'), new FirewallSetup.RuleSpec("n", "Private", "Any", 0, "Any", @"C:\O'Neil\vlc.exe"));
        Assert.Contains("-Program 'C:\\O''Neil\\vlc.exe'", quoted);
        // the four helper rules still build exactly as before: protocol, port, no program
        var helper = FirewallSetup.BuildNewRuleScript(Key('d'), FirewallSetup.ExpectedRules[0]);
        Assert.DoesNotContain("-Program", helper);
        Assert.Contains("-Protocol TCP -LocalPort 8490", helper);
    }

    [Fact]
    public void SetRuleScriptNeverDeletesAndOnlyChangesProgramAndPort()
    {
        var s = FirewallSetup.BuildSetRuleScript(Key('a'), FirewallSetup.VlcSpecs(Vlc, 9090)[1]);
        Assert.StartsWith("$ErrorActionPreference='Stop'; Set-NetFirewallRule -Name '" + Key('a') + "'", s);
        Assert.Contains("-LocalPort 9090", s);
        Assert.DoesNotContain("Remove-", s);
        Assert.DoesNotContain("-NewDisplayName", s);
        Assert.DoesNotContain("-LocalPort", FirewallSetup.BuildSetRuleScript(Key('a'), FirewallSetup.VlcSpecs(Vlc, 9090)[0]));
    }

    [Fact]
    public void DifferentAdminAttemptsOnlyThePrivateRule()
    {
        var same = FirewallSetup.VlcSpecsForUser(Vlc, 8080, true);
        var other = FirewallSetup.VlcSpecsForUser(Vlc, 8080, false);
        Assert.Equal(2, same.Length);
        Assert.Single(other);
        Assert.Equal("Private", other[0].Profile);
    }

    private static FirewallSetup.KeyRead One(string display, string props) => new(FirewallSetup.Found.One, display, props);

    [Fact]
    public void OwnedUpdateDecisionIsStrict()
    {
        var e = new RuleEntry(Key('a'), Program.RuleVlcPublic, "True|Inbound|Allow");
        var list = new List<RuleEntry> { e };
        Assert.Equal(FirewallSetup.OwnedUpdate.Update, FirewallSetup.DecideOwnedUpdate(list, Program.RuleVlcPublic, One(Program.RuleVlcPublic, "True|Inbound|Allow")));
        Assert.Equal(FirewallSetup.OwnedUpdate.Edited, FirewallSetup.DecideOwnedUpdate(list, Program.RuleVlcPublic, One(Program.RuleVlcPublic, "True|Inbound|Allow|x")));
        Assert.Equal(FirewallSetup.OwnedUpdate.Edited, FirewallSetup.DecideOwnedUpdate(list, Program.RuleVlcPublic, One("other", "True|Inbound|Allow")));
        Assert.Equal(FirewallSetup.OwnedUpdate.Edited, FirewallSetup.DecideOwnedUpdate(list, Program.RuleVlcPublic, new FirewallSetup.KeyRead(FirewallSetup.Found.Absent, "", "")));
        Assert.Equal(FirewallSetup.OwnedUpdate.Edited, FirewallSetup.DecideOwnedUpdate(list, Program.RuleVlcPublic, new FirewallSetup.KeyRead(FirewallSetup.Found.Dup, "", "")));
        Assert.Equal(FirewallSetup.OwnedUpdate.Edited, FirewallSetup.DecideOwnedUpdate(list, Program.RuleVlcPublic, null));
        Assert.Equal(FirewallSetup.OwnedUpdate.NotOwned, FirewallSetup.DecideOwnedUpdate(new List<RuleEntry>(), Program.RuleVlcPublic, One(Program.RuleVlcPublic, "True|Inbound|Allow")));
        Assert.Equal(FirewallSetup.OwnedUpdate.NotOwned, FirewallSetup.DecideOwnedUpdate(new List<RuleEntry> { e, new RuleEntry(Key('b'), Program.RuleVlcPublic, "x") }, Program.RuleVlcPublic, One(Program.RuleVlcPublic, "True|Inbound|Allow")));
        Assert.Equal(FirewallSetup.OwnedUpdate.NotOwned, FirewallSetup.DecideOwnedUpdate(list, "BingeMote VLC", One("BingeMote VLC", "True|Inbound|Allow")));   // the legacy name is never owned
    }

    [Fact]
    public void UnverifiedUpdateMovesTheEntryToUncertainAndSurvivesTheJournalRoundTrip()
    {
        var keep = new RuleEntry(Key('b'), Program.RuleVlcPrivate, "p");
        var upd = new RuleEntry(Key('a'), Program.RuleVlcPublic, "q");
        var j = new LifecycleJournal(1, new List<RuleEntry> { keep, upd }, true, null, new List<UncertainEntry>(), null);
        var after = FirewallSetup.MoveToUncertain(j, Key('a'), "in-place update exited 1, read-back Absent");
        Assert.Equal(new[] { Key('b') }, after.Rules.Select(x => x.Key).ToArray());
        Assert.Single(after.Uncertain);
        Assert.Equal(Key('a'), after.Uncertain[0].Key);
        Assert.Equal(Program.RuleVlcPublic, after.Uncertain[0].DisplayName);
        Assert.True(after.UrlAcl);                       // unrelated journal parts are preserved
        // the old witness is gone: the same entry is no longer owned for a later update
        Assert.Equal(FirewallSetup.OwnedUpdate.NotOwned, FirewallSetup.DecideOwnedUpdate(after.Rules, Program.RuleVlcPublic, One(Program.RuleVlcPublic, "q")));
        // and the file form is still a valid journal
        var dir = Path.Combine(Path.GetTempPath(), "bm-mu-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var f = Path.Combine(dir, "lifecycle.json");
            File.WriteAllText(f, System.Text.Json.JsonSerializer.Serialize(after));
            Assert.Equal(MarkerState.Present, LifecycleJournalStore.Read(f).state);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void IncompleteNoticeIsShownOnceAndAbsentMeansNothingToShow()
    {
        var dir = Path.Combine(Path.GetTempPath(), "bm-nt-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var marker = Path.Combine(dir, "firewall.ok");
            var notice = Path.Combine(dir, "notice.txt");
            Assert.False(FirewallSetup.TakeVlcIncompleteNotice(marker, notice, () => true));   // absent: nothing to show
            File.WriteAllText(notice, "2026-01-01T00:00:00Z\n");
            Assert.False(FirewallSetup.TakeVlcIncompleteNotice(marker, notice, () => false));  // untrusted directory: ignored, not marked seen
            Assert.True(FirewallSetup.TakeVlcIncompleteNotice(marker, notice, () => true));
            Assert.False(FirewallSetup.TakeVlcIncompleteNotice(marker, notice, () => true));   // once per notice
            Assert.True(File.Exists(notice));                                                  // never deleted by the reader
            File.WriteAllText(notice, "2026-02-02T00:00:00Z\n");                              // a new incomplete run is a new notice
            Assert.True(FirewallSetup.TakeVlcIncompleteNotice(marker, notice, () => true));
        }
        finally { Directory.Delete(dir, true); }
    }
}
