using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class PowerWakeTests
{
    [Fact]
    public void SessionHoldFollowsVlc()
    {
        PowerWake.SyncToVlc(true);
        Assert.True(PowerWake.HoldingForVlc);
        PowerWake.SyncToVlc(true); // steady state, no flip
        Assert.True(PowerWake.HoldingForVlc);
        PowerWake.SyncToVlc(false);
        Assert.False(PowerWake.HoldingForVlc);
    }

    [Fact]
    public void ReleaseDropsTheHold()
    {
        PowerWake.SyncToVlc(true);
        PowerWake.Release();
        Assert.False(PowerWake.HoldingForVlc);
    }

    [Fact]
    public void DisplayWakeIsOneShotAndKeepsNoHold()
    {
        PowerWake.Release();
        PowerWake.WakeDisplay();
        Assert.False(PowerWake.HoldingForVlc);
    }
}
