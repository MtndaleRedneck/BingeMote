using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

// UNTESTED (never run): written without a .NET toolchain.
[Collection("PowerWakeStatic")]
public class PowerWakeUnknownStateTests
{
    private static DateTime t0 = new(2026, 11, 12, 12, 0, 0, DateTimeKind.Utc);
    private static void At(double sec) => PowerWake.Now = () => t0.AddSeconds(sec);

    [Fact]
    public void UnknownDisplay_MovesPassImmediately()
    {
        PowerWake.ResetForTest(); At(0);
        Assert.False(PowerWake.ShouldSwallowContinuous());
        At(1); Assert.False(PowerWake.ShouldSwallowContinuous());
    }

    [Fact]
    public void KnownOff_MovesSwallowedAtMostThreeSecondsThenPass()
    {
        PowerWake.ResetForTest(); PowerWake.NoteMonitorState(0);
        At(0); Assert.True(PowerWake.ShouldSwallowContinuous());
        for (var ms = 100; ms < 3000; ms += 100) { PowerWake.Now = () => t0.AddMilliseconds(ms); Assert.True(PowerWake.ShouldSwallowContinuous()); }
        At(3.1); Assert.False(PowerWake.ShouldSwallowContinuous());
        At(3.2); Assert.False(PowerWake.ShouldSwallowContinuous()); // capped gesture keeps passing
    }

    [Fact]
    public void KnownOff_NewGestureAfterQuietIsSwallowedAgain()
    {
        PowerWake.ResetForTest(); PowerWake.NoteMonitorState(0);
        At(0); Assert.True(PowerWake.ShouldSwallowContinuous());
        At(5); Assert.True(PowerWake.ShouldSwallowContinuous());
    }

    [Fact]
    public void KnownOn_NothingSwallowed()
    {
        PowerWake.ResetForTest(); PowerWake.NoteMonitorState(1); At(0);
        Assert.False(PowerWake.ShouldSwallowContinuous());
        Assert.False(PowerWake.ShouldSwallowDiscrete());
    }

    [Fact]
    public void UnknownDisplay_FirstClickWakesSecondClickPasses()
    {
        PowerWake.ResetForTest(); At(0);
        Assert.True(PowerWake.ShouldSwallowDiscrete());
        At(1); Assert.False(PowerWake.ShouldSwallowDiscrete());
        At(2); Assert.False(PowerWake.ShouldSwallowDiscrete());
    }

    [Fact]
    public void KnownOff_FirstClickWakesSecondPassesAndLaterDarkSwallowsAgain()
    {
        PowerWake.ResetForTest(); PowerWake.NoteMonitorState(0);
        At(0); Assert.True(PowerWake.ShouldSwallowDiscrete());
        At(2); Assert.False(PowerWake.ShouldSwallowDiscrete());
        At(11); Assert.True(PowerWake.ShouldSwallowDiscrete());
    }
}
