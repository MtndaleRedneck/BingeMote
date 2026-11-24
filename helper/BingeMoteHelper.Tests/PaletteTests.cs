using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class PaletteTests
{
    [Fact]
    public void DarkPaletteMatchesAndroidThemeTokens()
    {
        Assert.Equal(unchecked((int)0xFFF97C02), HelperWindow.Orange.ToArgb());
        Assert.Equal(unchecked((int)0xFFFB9E2E), HelperWindow.OrangeLight.ToArgb());
        Assert.Equal(unchecked((int)0xFF1B1B1E), HelperWindow.DarkBg.ToArgb());
        Assert.Equal(unchecked((int)0xFF26262D), HelperWindow.DarkCard.ToArgb());
        Assert.Equal(unchecked((int)0xFF2E2E36), HelperWindow.DarkCard2.ToArgb());
        Assert.Equal(unchecked((int)0xFFF5F5F7), HelperWindow.DarkText.ToArgb());
        Assert.Equal(unchecked((int)0xFF9696A0), HelperWindow.DarkMuted.ToArgb());
        Assert.Equal(unchecked((int)0xFF232328), HelperWindow.HeaderBackground.ToArgb());
        Assert.Equal(unchecked((int)0xFF4A4A52), HelperWindow.TrackGray.ToArgb());
    }
}
