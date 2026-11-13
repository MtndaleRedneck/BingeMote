using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

// UNTESTED (never run).
[Collection("PowerWakeStatic")]
public class SendInputTrackerTests
{
    [Fact]
    public void ShortCountIsFailureEvenWithErrorZero()
    {
        SendInputTracker.ResetForTest(); var log = new List<string>();
        Assert.True(SendInputTracker.Record(1, 0, 0, line => { log.Add(line); return true; }));
        Assert.Single(log); Assert.Contains("win32_error=0", log[0]); Assert.Contains("rejected", log[0]);
    }

    [Fact]
    public void FullCountIsNotFailureAndStaysQuiet()
    {
        SendInputTracker.ResetForTest(); var log = new List<string>();
        Assert.False(SendInputTracker.Record(2, 2, 0, line => { log.Add(line); return true; }));
        Assert.Empty(log);
    }

    [Fact]
    public void RecoveredOnlyAfterRealSuccess_NotWhenErrorNumberUnchanged()
    {
        SendInputTracker.ResetForTest(); var log = new List<string>();
        SendInputTracker.Record(1, 0, 5, line => { log.Add(line); return true; });   // fails, error 5
        SendInputTracker.Record(1, 0, 5, line => { log.Add(line); return true; });   // same error, still failing: no "recovered"
        Assert.DoesNotContain(log, l => l.Contains("recovered"));
        SendInputTracker.Record(1, 1, 5, line => { log.Add(line); return true; });   // success even though error number is stale
        Assert.Contains(log, l => l.Contains("recovered"));
    }

    [Fact]
    public void ContinuingFailuresAreRateLimitedButSummarized()
    {
        SendInputTracker.ResetForTest(); var log = new List<string>();
        var t0 = new DateTime(2026, 11, 12, 20, 0, 0, DateTimeKind.Utc);
        SendInputTracker.Now = () => t0;
        SendInputTracker.Record(1, 0, 0, line => { log.Add(line); return true; });
        for (var i = 1; i <= 5; i++) { var n = t0.AddSeconds(i); SendInputTracker.Now = () => n; SendInputTracker.Record(1, 0, 0, line => { log.Add(line); return true; }); }
        Assert.Single(log);
        SendInputTracker.Now = () => t0.AddSeconds(11);
        SendInputTracker.Record(1, 0, 0, line => { log.Add(line); return true; });
        Assert.Equal(2, log.Count); Assert.Contains("failures_since_last_log=6", log[1]);
    }
    [Fact]
    public void RejectedFailureAndRecoveryRemainPendingUntilAdmitted()
    {
        SendInputTracker.ResetForTest(); var log = new List<string>();
        Assert.True(SendInputTracker.Record(1, 0, 0, _ => false));
        Assert.False(SendInputTracker.Record(1, 1, 0, _ => false));
        SendInputTracker.Record(1, 1, 0, line => { log.Add(line); return true; });
        Assert.Equal(2, log.Count);
        Assert.Contains("rejected", log[0]); Assert.Contains("failures_since_last_log=1", log[0]);
        Assert.Contains("recovered", log[1]);
    }
    [Fact]
    public void RejectedContinuingFailureSummaryRetainsItsCount()
    {
        SendInputTracker.ResetForTest(); var log = new List<string>();
        for(int i=0;i<20;i++) SendInputTracker.Record(1,0,0,_=>false);
        SendInputTracker.Record(1,0,0,line=> { log.Add(line); return true; });
        Assert.Single(log); Assert.Contains("failures_since_last_log=21",log[0]);
    }
}
