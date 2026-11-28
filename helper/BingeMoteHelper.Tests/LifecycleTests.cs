using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class LifecycleTests
{
    private const string Sid = "S-1-5-21-1111111111-2222222222-3333333333-1001";

    [Fact]
    public void SddlIsAdminFullAndTargetUserReadOnlyProtected() =>
        Assert.Equal("D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FR;;;" + Sid + ")", WatchdogSetup.BuildSddl(Sid));

    [Theory]
    [InlineData("S-1-5-21-1-2-3-1001", true)]
    [InlineData("S-1-12-1-1-2-3-4", true)]
    [InlineData("S-1-5-4", false)]          // INTERACTIVE group: never
    [InlineData("S-1-1-0", false)]          // Everyone
    [InlineData("S-1-5-32-545", false)]     // Users
    [InlineData("S-1-5-18", false)]
    [InlineData("S-1-5-21-1-2-3", false)]   // domain SID without RID, not a user
    [InlineData("", false)]
    [InlineData("x)(A;;FA;;;WD", false)]
    public void OnlyOrdinaryUserSidsAreAcceptedAsTarget(string sid, bool ok) => Assert.Equal(ok, WatchdogSetup.SidAcceptable(sid));

    [Fact]
    public void XmlRunsLimitedAsOneUserOnInteractiveToken()
    {
        var xml = WatchdogSetup.BuildXml(Sid, @"C:\Users\x & y\BingeMote.exe");
        Assert.Contains("<UserId>" + Sid + "</UserId>", xml);
        Assert.Contains("<LogonType>InteractiveToken</LogonType>", xml);
        Assert.Contains("<RunLevel>LeastPrivilege</RunLevel>", xml);
        Assert.Contains("x &amp; y", xml);
        Assert.DoesNotContain("S-1-5-4", xml);
        Assert.DoesNotContain("HighestAvailable", xml);
        Assert.Contains("--ensure-running", xml);
    }

    [Fact]
    public void TaskNameIsNeverTheLegacyFixedName() => Assert.NotEqual("BingeMote Watchdog", WatchdogSetup.TaskName);

    [Fact]
    public void SddlComparisonIgnoresAliasAndOrderButNotExtraRights()
    {
        var a = WatchdogSetup.BuildSddl(Sid);
        Assert.True(WatchdogSetup.SddlEquivalent(a, "D:P(A;;FR;;;" + Sid + ")(A;;FA;;;BA)(A;;FA;;;SY)"));
        Assert.False(WatchdogSetup.SddlEquivalent(a, "D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FA;;;" + Sid + ")"));
        Assert.False(WatchdogSetup.SddlEquivalent(a, "D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FR;;;" + Sid + ")(A;;FR;;;WD)"));
        Assert.False(WatchdogSetup.SddlEquivalent(a, "garbage"));
    }

    [Fact]
    public void JournalRejectsUnknownRuleNamesAndBadWatchdogSid()
    {
        var dir = Path.Combine(Path.GetTempPath(), "bm-lj-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var f = Path.Combine(dir, "lifecycle.json");
            var good = "{\"Key\":\"BingeMote-" + new string('a', 32) + "\",\"DisplayName\":\"" + FirewallSetup.ExpectedRules[0].Name + "\",\"Props\":\"True|Inbound\"}";
            File.WriteAllText(f, "{\"Version\":1,\"Rules\":[{\"Key\":\"BingeMote\",\"DisplayName\":\"BingeMote\",\"Props\":\"x\"}],\"UrlAcl\":false,\"Watchdog\":null}");
            Assert.Equal(MarkerState.Corrupt, LifecycleJournalStore.Read(f).state);   // a display name or legacy key is not a journal key
            File.WriteAllText(f, "{\"Version\":1,\"Rules\":[" + good + "],\"UrlAcl\":true,\"Watchdog\":null}");
            Assert.Equal(MarkerState.Present, LifecycleJournalStore.Read(f).state);
            File.WriteAllText(f, "{\"Version\":1,\"Rules\":[],\"UrlAcl\":false,\"Watchdog\":{\"Task\":\"BingeMote Watchdog\",\"Sid\":\"" + Sid + "\",\"Sddl\":\"x\",\"Exe\":\"x\"}}");
            Assert.Equal(MarkerState.Corrupt, LifecycleJournalStore.Read(f).state);   // legacy task name is never journal-eligible
            File.WriteAllText(f, "not json");
            Assert.Equal(MarkerState.Corrupt, LifecycleJournalStore.Read(f).state);
            Assert.Equal(MarkerState.Absent, LifecycleJournalStore.Read(Path.Combine(dir, "none.json")).state);
        }
        finally { Directory.Delete(dir, true); }
    }
}
