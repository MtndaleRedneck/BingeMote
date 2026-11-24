using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class UrlAclTests
{
    [Theory]
    [InlineData("D:(A;;GX;;;S-1-5-4)", true)]
    [InlineData("D:(A;;GX;;;IU)", true)]
    [InlineData("D:(A;;GX;;;WD)", false)]
    [InlineData("D:(A;;GX;;;S-1-1-0)", false)]
    [InlineData("D:(A;;GX;;;IU)(A;;GX;;;WD)", false)]
    [InlineData("D:(A;;GA;;;IU)", false)]
    [InlineData("D:(D;;GX;;;IU)", false)]
    [InlineData("D:", false)]
    [InlineData("invalid", false)]
    public void OnlyInteractiveExecuteReservationIsAccepted(string sddl, bool expected)
    {
        foreach (var ending in new[] { "\r\n", "\n", "" })
            Assert.Equal(expected, FirewallSetup.HasInteractiveOnlyGrant("    SDDL: " + sddl + ending));
    }

    [Fact]
    public void MissingPermissionFailsClosed() => Assert.False(FirewallSetup.HasInteractiveOnlyGrant("URL reservation not found"));

    [Fact]
    public void LocalizedLabelDoesNotChangeSddlSemantics() => Assert.True(
        FirewallSetup.HasInteractiveOnlyGrant("    Descriptor de seguridad: D:(A;;GX;;;S-1-5-4)\r\n"));
}
