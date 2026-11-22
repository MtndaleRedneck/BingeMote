using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class TrayBalloonRoutingTests
{
    // Regression for the 1.7.12 review finding: the tray balloon click handler used to
    // start the updater for EVERY balloon, including the input-failure warning. Routing
    // must follow the balloon that is actually showing.

    [Fact]
    public void UpdateBalloonClickStartsUpdate()
    {
        Assert.True(HelperWindow.TrayClickStartsUpdate(HelperWindow.TrayBalloon.UpdateAvailable));
    }

    [Fact]
    public void InputBlockedBalloonClickDoesNotStartUpdate()
    {
        Assert.False(HelperWindow.TrayClickStartsUpdate(HelperWindow.TrayBalloon.InputBlocked));
    }

    [Fact]
    public void NoBalloonClickDoesNotStartUpdate()
    {
        Assert.False(HelperWindow.TrayClickStartsUpdate(HelperWindow.TrayBalloon.None));
    }

    // Regression for ND-1: the throttle must not subtract the never-shown sentinel from
    // TickCount64 (that subtraction overflows and would suppress every balloon).

    [Fact]
    public void FirstBalloonFiresFromSentinel()
    {
        Assert.True(HelperWindow.BalloonDue(long.MinValue, 5_000));
    }

    [Fact]
    public void FirstBalloonFiresFromSentinelAtHugeUptime()
    {
        Assert.True(HelperWindow.BalloonDue(long.MinValue, long.MaxValue));
    }

    [Fact]
    public void SecondBalloonInsideWindowIsSuppressed()
    {
        Assert.False(HelperWindow.BalloonDue(10_000, 10_000 + 59_999));
    }

    [Fact]
    public void BalloonExactlyAtWindowEdgeIsSuppressed()
    {
        Assert.False(HelperWindow.BalloonDue(10_000, 10_000 + 60_000));
    }

    [Fact]
    public void BalloonAfterWindowExpiresFires()
    {
        Assert.True(HelperWindow.BalloonDue(10_000, 10_000 + 60_001));
    }

    [Fact]
    public void BalloonAfterWindowExpiresFiresAtHugeUptime()
    {
        Assert.True(HelperWindow.BalloonDue(long.MaxValue - 100_000, long.MaxValue - 10_000));
        Assert.False(HelperWindow.BalloonDue(long.MaxValue - 50_000, long.MaxValue - 10_000));
    }
}
