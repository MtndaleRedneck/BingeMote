using System.Drawing;
using Xunit;

namespace BingeMoteHelper.Tests;

public class VolumeOsdTests
{
    [Theory]
    [InlineData(1920, 1080)]
    [InlineData(3840, 2160)]
    [InlineData(1280, 720)]
    [InlineData(2560, 1440)]
    [InlineData(1166, 985)]
    public void PillUsesFrameFractionsWithoutDpiDoubleScaling(int width, int height)
    {
        var frame = new Rectangle(0, 0, width, height);
        var panel = VolumeOsd.PanelBounds(frame);
        Assert.InRange(Math.Abs(panel.Width - width * 0.167), 0, 0.5);
        Assert.InRange(Math.Abs(panel.Height - height * 0.0375), 0, 0.5);
        Assert.InRange(Math.Abs((frame.Bottom - panel.Bottom) - height * 0.059), 0, 0.5);
        Assert.InRange(Math.Abs((panel.Left + panel.Right) / 2.0 - width / 2.0), 0, 0.5);
        Assert.True(frame.Contains(panel));
    }

    [Fact]
    public void FrameOriginIsKeptForOutputPosition()
    {
        var origin = VolumeOsd.PanelBounds(new Rectangle(0, 0, 1920, 1080));
        var offset = VolumeOsd.PanelBounds(new Rectangle(-1920, 100, 1920, 1080));
        Assert.Equal(new Rectangle(origin.X - 1920, origin.Y + 100, origin.Width, origin.Height), offset);
    }

    [Theory]
    [InlineData(0, 1080)]
    [InlineData(1920, 0)]
    [InlineData(-1, 1080)]
    public void InvalidFramesRefused(int width, int height)
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => VolumeOsd.PanelBounds(new Rectangle(0, 0, width, height)));
    }
}
