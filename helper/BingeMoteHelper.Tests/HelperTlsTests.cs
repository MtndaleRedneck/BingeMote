using Xunit;
using BingeMoteHelper;

namespace BingeMoteHelper.Tests;

// PRIVATE SOURCE, NOT RUN. The planner, marker, journal and migration tests are pure and need no Windows. Anything that touches the
// certificate store, http.sys or elevation is listed at the bottom as an unrun requirement, not implemented here.
public class HelperTlsTests
{
    private const string Thumb = "AABBCCDDEEFF00112233445566778899AABBCCDD";
    private static readonly string Spki = new string('a', 64);
    private static readonly TlsIdentityMarker M = new(Thumb, Spki, TlsInspector.AppId.ToString(), 8488, "2026-01-01T00:00:00Z");

    private static TlsObservation Obs(TlsIdentityMarker? marker = null, CertState cert = CertState.Present, bool matches = true, bool orphan = false,
        BindingState v4 = BindingState.ExactExpected, BindingState v6 = BindingState.ExactExpected, bool conflict = false, bool setup = false, MarkerState ms = MarkerState.Present, bool due = false)
        => new(marker == null && ms == MarkerState.Present ? MarkerState.Absent : ms, marker, cert, matches, orphan, v4, v6, conflict, due, setup);

    [Fact] public void ExactIdentityAndBothBindingsIsReady()
        => Assert.Equal(TlsAction.Ready, TlsPlanner.Plan(Obs(M)).Action);

    [Fact] public void RuntimeNeverBindsOrRegeneratesEvenWhenSomethingIsMissing()
    {
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, v6: BindingState.Absent)).Action);
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, cert: CertState.Absent)).Action);
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(null, v4: BindingState.Absent, v6: BindingState.Absent)).Action);
    }

    [Fact] public void FirstExplicitSetupGeneratesAndBindsBothFamilies()
    {
        var p = TlsPlanner.Plan(Obs(null, cert: CertState.Absent, matches: false, v4: BindingState.Absent, v6: BindingState.Absent, setup: true));
        Assert.Equal(TlsAction.GenerateFresh, p.Action); Assert.True(p.BindV4 && p.BindV6);
    }

    [Fact] public void ExplicitSetupBindsOnlyTheMissingFamily()
    {
        var p = TlsPlanner.Plan(Obs(M, v6: BindingState.Absent, setup: true));
        Assert.Equal(TlsAction.BindMissing, p.Action); Assert.False(p.BindV4); Assert.True(p.BindV6);
    }

    [Fact] public void ExpectedCertAbsentIsRegeneratedOnlyByExplicitSetup()
    {
        Assert.Equal(TlsAction.GenerateFresh, TlsPlanner.Plan(Obs(M, cert: CertState.Absent, matches: false, setup: true)).Action);
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, cert: CertState.Absent, matches: false, setup: false)).Action);
    }

    [Theory]
    [InlineData(CertState.AccessError)]
    [InlineData(CertState.KeyMissing)]
    public void KeyAccessErrorsAndMissingKeysNeverRegenerate(CertState s)
        => Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, cert: s, setup: true)).Action);

    [Fact] public void ForeignBindingSniOrSpecificIpConflictAndUnknownStateAllFailClosedEvenInSetup()
    {
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, v4: BindingState.Foreign, setup: true)).Action);
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, v6: BindingState.Foreign, setup: true)).Action);
        Assert.Equal(TlsAction.Ready, TlsPlanner.Plan(Obs(M, conflict: true, setup: true)).Action);   // nothing to mutate: Ready with the unverified-coexistence note (mutation is the blocked case, see below)
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, v6: BindingState.Absent, conflict: true, setup: true)).Action);
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, v4: BindingState.Unknown, setup: true)).Action);
    }

    [Fact] public void OrphanCertOrBindingWithoutMarkerFailsClosedAndMismatchedThumbprintToo()
    {
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(null, orphan: true, v4: BindingState.Absent, v6: BindingState.Absent, setup: true)).Action);
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(null, v4: BindingState.Foreign, v6: BindingState.Absent, setup: true)).Action);
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, matches: false, setup: true)).Action);
    }

    private const string Sample = @"
SSL Certificate bindings:
-------------------------

    IP:port                      : 0.0.0.0:8488
    Certificate Hash             : aabbccddeeff00112233445566778899aabbccdd
    Application ID               : {bd54aef6-13d9-4c17-8e6f-e985b8026083}
    Certificate Store Name       : MY

    IP:port                      : [::]:8488
    Certificate Hash             : aabbccddeeff00112233445566778899aabbccdd
    Application ID               : {bd54aef6-13d9-4c17-8e6f-e985b8026083}
    Certificate Store Name       : MY

    IP:port                      : 192.168.1.5:8488
    Certificate Hash             : 0000000000000000000000000000000000000000
    Application ID               : {11111111-1111-1111-1111-111111111111}
    Certificate Store Name       : MY

    Hostname:port                : pc.example:8488
    Certificate Hash             : 0000000000000000000000000000000000000000
    Application ID               : {11111111-1111-1111-1111-111111111111}
    Certificate Store Name       : MY
";

    [Fact] public void CorruptMarkerFailsClosedAndNeverRegenerates()
        => Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(null, cert: CertState.Absent, v4: BindingState.Absent, v6: BindingState.Absent, setup: true, ms: MarkerState.Corrupt)).Action);

    [Fact] public void CorruptAndAbsentMarkerFilesAreDistinct()
    {
        var f = Path.GetTempFileName();
        try
        {
            File.WriteAllText(f, "{ not json"); Assert.Equal(MarkerState.Corrupt, TlsMarkerStore.Read(f).state);
            File.WriteAllText(f, "{\"Thumbprint\":\"zz\"}"); Assert.Equal(MarkerState.Corrupt, TlsMarkerStore.Read(f).state);
            File.Delete(f); Assert.Equal(MarkerState.Absent, TlsMarkerStore.Read(f).state);
        }
        finally { File.Delete(f); }
    }

    [Fact] public void JournalStatesAreDistinct()
    {
        var f = Path.GetTempFileName();
        try
        {
            File.WriteAllText(f, "garbage"); Assert.Equal(MarkerState.Corrupt, TlsJournalStore.Read(f).state);
            File.Delete(f); Assert.Equal(MarkerState.Absent, TlsJournalStore.Read(f).state);
        }
        finally { File.Delete(f); }
    }

    [Fact] public void UnverifiedCoexistenceNoteIsAlwaysCarried()
        => Assert.Contains("Not verified", TlsPlanner.Plan(Obs(M)).Notes);

    [Fact] public void RenewalWindowIsShorterThanValidity()
    {
        var now = DateTimeOffset.UtcNow;
        Assert.True(TlsLifetime.IsRenewalDue(now.AddDays(10), now));
        Assert.False(TlsLifetime.IsRenewalDue(now.AddDays(300), now));
        Assert.True(TlsLifetime.Validity < TimeSpan.FromDays(3650));
    }

    [Theory]
    [InlineData("Ours", "DeleteOldHttpReservation")]
    [InlineData("Legacy", "DeleteOldHttpReservation")]
    [InlineData("Foreign", "LeaveForeign")]
    [InlineData("None", "None")]
    public void OldHttpReservationIsOnlyPlannedForRemovalWhenOwned(string owner, string expected)
        => Assert.Equal(expected, UrlAclMigrationPlanner.PlanOld(Enum.Parse<FirewallSetup.AclOwnership>(owner)).ToString());

    [Fact] public void StaleOwnedBindingNeedsExplicitSetupAndBecomesAnUpdate()
    {
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, v4: BindingState.StaleOwned, v6: BindingState.StaleOwned)).Action);
        var p = TlsPlanner.Plan(Obs(M, v4: BindingState.StaleOwned, v6: BindingState.StaleOwned, setup: true));
        Assert.Equal(TlsAction.UpdateStale, p.Action);
    }

    [Fact] public void SniCcsOrSpecificIpOnPortBlocksMutationButNotAnExactReady()
    {
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, v6: BindingState.Absent, conflict: true, setup: true)).Action);
        var ready = TlsPlanner.Plan(Obs(M, conflict: true));
        Assert.Equal(TlsAction.Ready, ready.Action); Assert.Contains("specific-IP", ready.Notes); Assert.Contains("Not verified", ready.Notes);
    }

    [Fact] public void InteropLayoutPinsHoldOnThisProcessArchitecture()
    {
        Assert.True(HttpSysSsl.LayoutOk());
        if (IntPtr.Size == 8) { Assert.Equal(24, HttpSysSsl.SslQuerySize); Assert.Equal(152, HttpSysSsl.SniQuerySize); Assert.Equal(144, HttpSysSsl.CcsQuerySize); Assert.Equal(136, HttpSysSsl.SniSetParam); Assert.Equal(32, HttpSysSsl.ParamStore); }
        else { Assert.Equal(12, HttpSysSsl.SslQuerySize); Assert.Equal(144, HttpSysSsl.SniQuerySize); Assert.Equal(132, HttpSysSsl.SniSetParam); Assert.Equal(24, HttpSysSsl.ParamStore); }
        // Real-machine cross-check still required: write a record with netsh, read it back through HttpQueryServiceConfiguration, compare.
    }

    // Crash-boundary simulation of the pure recovery decision (every write boundary of Fresh, Bind and Renew).
    [Theory]
    [InlineData("Fresh", MarkerState.Absent, false, false, false, false, false, RecoveryDecision.RollBack)]      // crash before journal thumb, or before marker write
    [InlineData("Fresh", MarkerState.Present, true, true, true, false, true, RecoveryDecision.DeleteJournal)]    // marker written, journal not yet deleted: committed
    [InlineData("Fresh", MarkerState.Present, true, true, true, false, false, RecoveryDecision.FailClosed)]      // marker new but bindings no longer exact
    [InlineData("Fresh", MarkerState.Present, true, true, false, false, false, RecoveryDecision.FailClosed)]     // some other marker appeared
    [InlineData("Renew", MarkerState.Present, true, true, false, true, false, RecoveryDecision.RollBack)]        // prior marker, bindings partly moved: restore prior
    [InlineData("Renew", MarkerState.Present, true, true, true, false, true, RecoveryDecision.DeleteJournal)]    // committed
    [InlineData("Renew", MarkerState.Present, true, true, false, false, false, RecoveryDecision.FailClosed)]     // marker is neither prior nor new
    [InlineData("Bind", MarkerState.Present, true, true, true, false, true, RecoveryDecision.DeleteJournal)]     // completed
    [InlineData("Bind", MarkerState.Present, true, true, true, false, false, RecoveryDecision.RollBack)]         // partial: undo flagged additions only
    [InlineData("Bind", MarkerState.Corrupt, false, true, false, false, false, RecoveryDecision.FailClosed)]
    public void RecoveryDecidesFromActualStateAtEveryBoundary(string kind, MarkerState ms, bool present, bool thumbKnown, bool isNew, bool isOld, bool exact, RecoveryDecision expected)
        => Assert.Equal(expected, RecoveryPlanner.Decide(kind, ms, present, thumbKnown, isNew, isOld, exact));

    [Fact] public void RemovalDeletesOnlyExactOwnedBindingsAndAlwaysRetainsIdentityMaterial()
    {
        var p = RemovalPlanner.Decide(MarkerState.Present, BindingState.ExactExpected, BindingState.ExactExpected, CertState.Present, true, false, false);
        Assert.True(p.DeleteV4 && p.DeleteV6);         Assert.Contains("retained", p.Remains);
    }

    [Fact] public void RemovalLeavesForeignStaleOrUnknownBindingsAndNamesThem()
    {
        var f = RemovalPlanner.Decide(MarkerState.Present, BindingState.Foreign, BindingState.ExactExpected, CertState.Present, true, false, false);
        Assert.False(f.DeleteV4); Assert.True(f.DeleteV6); Assert.Contains("IPv4", f.Remains);
        var s = RemovalPlanner.Decide(MarkerState.Present, BindingState.Unknown, BindingState.StaleOwned, CertState.Present, true, false, false);
        Assert.False(s.DeleteV4 || s.DeleteV6);
    }

    [Fact] public void RegenerationWithPriorMarkerAndBothOldBindingsUpdatesInsteadOfAdding()
    {
        var p = TlsPlanner.Plan(Obs(M, cert: CertState.Absent, matches: false, setup: true));   // both bindings still ExactExpected for the prior thumbprint
        Assert.Equal(TlsAction.GenerateFresh, p.Action);
        Assert.True(p.UpdateV4 && p.UpdateV6); Assert.False(p.BindV4 || p.BindV6);
        var mixed = TlsPlanner.Plan(Obs(M, cert: CertState.Absent, matches: false, v6: BindingState.Absent, setup: true));
        Assert.True(mixed.UpdateV4 && !mixed.UpdateV6 && mixed.BindV6 && !mixed.BindV4);
    }

    [Theory]
    [InlineData("Fresh", MarkerState.Present, true, true, false, true, false, true, RecoveryDecision.RollBack)]    // prior marker still exactly the prior one
    [InlineData("Fresh", MarkerState.Present, true, true, false, false, false, true, RecoveryDecision.FailClosed)]   // a different marker appeared: never assumed ours
    [InlineData("Fresh", MarkerState.Absent, false, true, false, false, false, true, RecoveryDecision.FailClosed)]   // prior marker vanished
    public void RecoveryWithPriorIdentityNeverTrustsADifferentMarker(string kind, MarkerState ms, bool present, bool known, bool isNew, bool isOld, bool exact, bool hadPrior, RecoveryDecision expected)
        => Assert.Equal(expected, RecoveryPlanner.Decide(kind, ms, present, known, isNew, isOld, exact, hadPrior));

    [Fact] public void StaleBindingWhoseOldCertIsGoneFailsClosedWithAPreciseDiagnosisAndNoDeletion()
    {
        var gone = new HttpSysSsl.Result(HttpSysSsl.Kind.Found, "1111111111111111111111111111111111111111", M.AppId, "MY");
        Assert.Equal(BindingState.Foreign, TlsInspector.FromResult(gone, M));
        var d = TlsInspector.Diagnose("IPv4", gone, BindingState.Foreign, M);
        Assert.Contains("1111111111111111111111111111111111111111", d); Assert.Contains("left untouched", d);
        var plan = TlsPlanner.Plan(Obs(M, v4: BindingState.Foreign, setup: true) with { Diag = d });
        Assert.Equal(TlsAction.FailClosed, plan.Action); Assert.Contains("untouched", plan.Reason);
    }

    [Fact] public void Ipv6PolicyDefaultsToStrictAndOnlyRelaxesAnAbsentBindingNeverAForeignOne()
    {
        Assert.False(TlsPolicy.V6OptionalWhenStackAbsent);
        Assert.True(TlsPolicy.V6Required(false));   // strict default: required even with no stack
        Assert.Equal(TlsAction.FailClosed, TlsPlanner.Plan(Obs(M, v6: BindingState.Foreign) with { Ipv6Capable = false }).Action);
    }

    [Theory]
    [InlineData(MarkerState.Corrupt, false)]
    [InlineData(MarkerState.Absent, false)]
    public void RemovalDoesNothingWithoutAUsableMarker(MarkerState ms, bool journal)
    {
        var p = RemovalPlanner.Decide(ms, BindingState.ExactExpected, BindingState.ExactExpected, CertState.Present, true, false, journal);
        Assert.False(p.DeleteV4 || p.DeleteV6);
    }

    [Fact] public void RemovalDoesNothingWhileAJournalIsPending()
    {
        var p = RemovalPlanner.Decide(MarkerState.Present, BindingState.ExactExpected, BindingState.ExactExpected, CertState.Present, true, false, true);
        Assert.False(p.DeleteV4 || p.DeleteV6);
    }

    // UNRUN REQUIREMENTS (need a real elevated Windows machine, none available): real certificate import and key access under
    // MachineKeySet, http.sys handshakes on 0.0.0.0 and [::] with the phone's pin callback, foreign binding preservation,
    // rollback after a failed bind, declined UAC, same-key renewal (SPKI unchanged, dates reissued, phone accepts), the
    // unelevated runtime failing closed, existing-urlacl migration from the http:// reservation.
}
