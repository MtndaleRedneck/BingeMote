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
}
