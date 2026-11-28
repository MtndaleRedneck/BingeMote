using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

/// <summary>Pure planner coverage for the HTTPS listen-permission lifecycle: setup
/// (HttpsReservationPlanner) and uninstall cleanup (HttpsReservationCleanupPlanner).
/// These exercise only the decision tables - no machine state is touched.</summary>
public class HttpsReservationPlannerTests
{
    [Fact]
    public void Setup_AddsOnlyWhenAbsent_KeepsWhenExactlyOurs()
    {
        Assert.Equal(HttpsReservationPlanner.Action.Add, HttpsReservationPlanner.Plan(FirewallSetup.AclOwnership.None));
        Assert.Equal(HttpsReservationPlanner.Action.Keep, HttpsReservationPlanner.Plan(FirewallSetup.AclOwnership.Ours));
    }

    [Fact]
    public void Setup_NeverDeletesWhatIsNotProvablyOurs()
    {
        // Regression: the HTTPS grant has always been interactive-users only - no BingeMote
        // version has ever written an Everyone (WD) HTTPS reservation - so unlike the old-http
        // migration, Legacy is NOT a permitted delete class for HTTPS. A Legacy or otherwise
        // foreign reservation is left untouched and setup refuses loudly.
        Assert.Equal(HttpsReservationPlanner.Action.Refuse, HttpsReservationPlanner.Plan(FirewallSetup.AclOwnership.Legacy));
        Assert.Equal(HttpsReservationPlanner.Action.Refuse, HttpsReservationPlanner.Plan(FirewallSetup.AclOwnership.Foreign));
    }

    [Fact]
    public void Cleanup_DeletesOnlyOwnedReservationWithMarkerAndNoJournal()
    {
        Assert.Equal(HttpsReservationCleanupPlanner.Action.Delete,
            HttpsReservationCleanupPlanner.Plan(true, MarkerState.Present, FirewallSetup.AclOwnership.Ours, MarkerState.Absent));
    }

    [Fact]
    public void Cleanup_JournalHoldsTheReservationLikeTheBindings()
    {
        // A pending or corrupt interrupted-setup journal must hold the reservation the same way
        // RemovalPlanner holds the bindings, so crash recovery still sees the whole state.
        Assert.Equal(HttpsReservationCleanupPlanner.Action.Keep,
            HttpsReservationCleanupPlanner.Plan(true, MarkerState.Present, FirewallSetup.AclOwnership.Ours, MarkerState.Present));
        Assert.Equal(HttpsReservationCleanupPlanner.Action.Keep,
            HttpsReservationCleanupPlanner.Plan(true, MarkerState.Present, FirewallSetup.AclOwnership.Ours, MarkerState.Corrupt));
    }

    [Fact]
    public void Cleanup_CompositionTable_DeletesExactlyOneCell()
    {
        // Full cross-product: Delete iff found AND marker Present AND owner Ours AND journal Absent.
        foreach (var found in new[] { false, true })
        foreach (var marker in Enum.GetValues<MarkerState>())
        foreach (var owner in Enum.GetValues<FirewallSetup.AclOwnership>())
        foreach (var journal in Enum.GetValues<MarkerState>())
        {
            var expected = found && marker == MarkerState.Present && owner == FirewallSetup.AclOwnership.Ours && journal == MarkerState.Absent
                ? HttpsReservationCleanupPlanner.Action.Delete
                : HttpsReservationCleanupPlanner.Action.Keep;
            Assert.Equal(expected, HttpsReservationCleanupPlanner.Plan(found, marker, owner, journal));
        }
    }
}
