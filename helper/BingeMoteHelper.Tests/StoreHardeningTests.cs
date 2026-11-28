using System.Text.Json;
using Xunit;
using BingeMoteHelper;

namespace BingeMoteHelper.Tests;

[CollectionDefinition("ProgramState", DisableParallelization = true)]
public class ProgramStateCollection { }

/// <summary>S1 parse matrix (reviewer source finding, ported from the base StoreParseTests
/// minus DPAPI - plaintext port: unreadable = missing/null token string): one record without a
/// readable token fails the WHOLE store closed in the current phones[] format AND the legacy
/// tokens[] format; the 1.0/1.1 single-token null is tolerated exactly as the base. Skipping a
/// bad record in memory would silently drop that phone's stored token on the next save - the
/// data loss the atomic store exists to prevent.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
public class StoreParseTests
{
    private static JsonElement Root(string json) => JsonDocument.Parse(json).RootElement;

    [Fact]
    public void AllValidRecordsParse()
    {
        var ok = Program.TryParsePhones(Root("{\"phones\":[{\"token\":\"tok-valid-1\",\"name\":\"Sofa phone\"}]}"),
            out var phones, out var unreadable);
        Assert.True(ok);
        Assert.Null(unreadable);
        Assert.Single(phones);
        Assert.Equal("tok-valid-1", phones[0].Token);
        Assert.Equal("Sofa phone", phones[0].Name);
    }

    [Fact]
    public void MixedValidPlusBadRecordFailsTheWholeStore()
    {
        var ok = Program.TryParsePhones(Root("{\"phones\":[{\"token\":\"tok-good\",\"name\":\"Good phone\"},{\"name\":\"Torn phone\"}]}"),
            out var phones, out var unreadable);
        Assert.False(ok);                  // fail closed: the store is broken, not partially loaded
        Assert.Equal("Torn phone", unreadable);
        Assert.Single(phones);             // the good record was read - but the caller refuses to use it
        Assert.Equal("Good phone", phones[0].Name);
    }

    [Fact]
    public void NullTokenInLegacyTokensArrayFailsClosed()
    {
        var ok = Program.TryParsePhones(Root("{\"tokens\":[\"tok-a\",null]}"), out _, out _);
        Assert.False(ok);
    }

    [Fact]
    public void LegacySingleTokenNullIsTolerated()
    {
        var ok = Program.TryParsePhones(Root("{\"token\":null}"), out var phones, out _);
        Assert.True(ok);
        Assert.Empty(phones);
    }

    [Fact]
    public void LegacyPlainTokenStillParses()
    {
        var ok = Program.TryParsePhones(Root("{\"token\":\"plain-legacy-token\"}"), out var phones, out _);
        Assert.True(ok);
        Assert.Single(phones);
        Assert.Equal("plain-legacy-token", phones[0].Token);
    }
}

/// <summary>S8: config POST change+persist is one transaction. A refused save leaves memory,
/// disk and the reply in agreement (all three settings roll back, failure is reported), and a
/// retry after the cause is fixed persists cleanly. The failure is injected by putting a
/// directory where the config file belongs.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class ConfigSaveRollbackTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-rb-" + Guid.NewGuid().ToString("N"));

    public ConfigSaveRollbackTests()
    {
        Directory.CreateDirectory(_dir);
        Program.RedirectDataDirForTests(_dir);
        Program.StoreBroken = false;
        lock (Program.PhonesLock) { Program.Phones.Clear(); }
        Program.MoviesFolder = @"C:\Movies"; Program.KeepAwakeEnabled = true; Program.VolumeOsdEnabled = true;
    }

    public void Dispose()
    {
        Program.StoreBroken = false;
        lock (Program.PhonesLock) { Program.Phones.Clear(); }
        Program.MoviesFolder = null; Program.KeepAwakeEnabled = true; Program.VolumeOsdEnabled = true;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private void BreakSaves() { if (File.Exists(Program.ConfigPath)) File.Delete(Program.ConfigPath); Directory.CreateDirectory(Program.ConfigPath); }
    private void FixSaves() { if (Directory.Exists(Program.ConfigPath)) Directory.Delete(Program.ConfigPath, true); }
    private static JsonElement Disk() => JsonDocument.Parse(File.ReadAllText(Program.ConfigPath)).RootElement.Clone();

    [Fact]
    public void ConfigChangePersistsKeepAwakeAndVolumeOsdAcrossRestart()
    {
        Assert.True(HttpServer.ApplyConfigChange(@"D:\Films", false, false));
        var d = Disk();
        Assert.Equal(@"D:\Films", d.GetProperty("moviesFolder").GetString());
        Assert.False(d.GetProperty("keepAwake").GetBoolean());
        Assert.False(d.GetProperty("volumeOsd").GetBoolean());
    }

    [Fact]
    public void FailedConfigSaveRestoresAllThreeSettingsAndReportsFailure()
    {
        BreakSaves();
        Assert.False(HttpServer.ApplyConfigChange(@"D:\Films", false, false));
        Assert.Equal(@"C:\Movies", Program.MoviesFolder);
        Assert.True(Program.KeepAwakeEnabled);
        Assert.True(Program.VolumeOsdEnabled);
        FixSaves();
        Assert.True(HttpServer.ApplyConfigChange(@"E:\Later", true, true)); // retry after the cause is fixed persists cleanly
        Assert.Equal(@"E:\Later", Disk().GetProperty("moviesFolder").GetString());
    }

    [Fact]
    public void AReaderUnderTheSaveLockNeverSeesAChangeThatIsAboutToBeRolledBack()
    {
        // Saves keep failing. Each failed change is applied and undone inside the outer save
        // lock, so a reader that takes the same lock must only ever see the prior folder.
        BreakSaves();
        var stop = false;
        var failer = new Thread(() => { while (!Volatile.Read(ref stop)) HttpServer.ApplyConfigChange(@"X:\Never", null, null); });
        failer.Start();
        var seen = new List<string?>();
        for (var i = 0; i < 2000; i++) lock (Program.ConfigSaveLock) { seen.Add(Program.MoviesFolder); }
        Volatile.Write(ref stop, true); failer.Join();
        Assert.DoesNotContain(@"X:\Never", seen);
        Assert.Equal(@"C:\Movies", Program.MoviesFolder);
    }
}

/// <summary>S2/S11: the save transaction itself (adapted from the base SaveTransactionTests to
/// the port's MutateAndPersist seam - pair/adopt route wiring is held for the S7/S10 contract,
/// so these exercise the transaction primitive the routes use). Concurrent transactions must
/// serialize through the OUTER save lock (no lost phone, no torn file); a failed save rolls the
/// in-memory change back so a retry persists cleanly; a broken store refuses every save and
/// preserves the file byte-for-byte.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class SaveTransactionTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-save-" + Guid.NewGuid().ToString("N"));

    public SaveTransactionTests()
    {
        Directory.CreateDirectory(_dir);
        Program.RedirectDataDirForTests(_dir);
        Program.StoreBroken = false;
        lock (Program.PhonesLock) { Program.Phones.Clear(); }
    }

    public void Dispose()
    {
        Program.StoreBroken = false;
        lock (Program.PhonesLock) { Program.Phones.Clear(); }
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private bool AddPhoneAndPersist(string token, string name)
    {
        Program.PairedPhone? added = null;
        return Program.MutateAndPersist(
            () => { lock (Program.PhonesLock) { added = new Program.PairedPhone { Token = token, Name = name }; Program.Phones.Add(added); } },
            () => { lock (Program.PhonesLock) { if (added != null) Program.Phones.Remove(added); } },
            "test phone add");
    }

    private static string[] TokensOnDisk()
    {
        var json = File.ReadAllText(Program.ConfigPath);
        return JsonDocument.Parse(json).RootElement.GetProperty("phones")
            .EnumerateArray()
            .Select(p => p.GetProperty("token").GetString()!)
            .OrderBy(t => t, StringComparer.Ordinal)
            .ToArray();
    }

    [Fact]
    public void ConcurrentTransactionsLoseNoPhoneAndNeverTearTheFile()
    {
        const int perThread = 25;
        var errors = new List<Exception>();
        void Worker(string tag)
        {
            try
            {
                for (var i = 0; i < perThread; i++)
                    Assert.True(AddPhoneAndPersist("tok-" + tag + "-" + i, tag + " phone " + i));
            }
            catch (Exception ex) { lock (errors) { errors.Add(ex); } }
        }
        var a = new Thread(() => Worker("a"));
        var b = new Thread(() => Worker("b"));
        a.Start(); b.Start(); a.Join(); b.Join();

        Assert.Empty(errors);
        var expected = Enumerable.Range(0, perThread).Select(i => "tok-a-" + i)
            .Concat(Enumerable.Range(0, perThread).Select(i => "tok-b-" + i))
            .OrderBy(t => t, StringComparer.Ordinal).ToArray();
        // Every transaction persisted: the file holds exactly the union of both threads.
        Assert.Equal(expected, TokensOnDisk());
        // Memory and disk agree (no save finished out of order with a stale snapshot).
        Assert.Equal(expected, Program.PhonesSnapshot().Select(p => p.Token).OrderBy(t => t, StringComparer.Ordinal).ToArray());
    }

    [Fact]
    public void FailedSaveRollsBackInMemoryAndRetryPersists()
    {
        Assert.True(AddPhoneAndPersist("tok-first", "first phone"));
        new FileInfo(Program.ConfigPath).IsReadOnly = true;
        try
        {
            Assert.False(AddPhoneAndPersist("tok-second", "second phone"));
            // Refused write leaves no phantom: the phone is gone from memory...
            Assert.DoesNotContain(Program.PhonesSnapshot(), p => p.Token == "tok-second");
            // ...and the file still holds exactly the first phone.
            Assert.Equal(new[] { "tok-first" }, TokensOnDisk());
        }
        finally
        {
            new FileInfo(Program.ConfigPath).IsReadOnly = false;
        }
        Assert.True(AddPhoneAndPersist("tok-second", "second phone"));
        Assert.Equal(new[] { "tok-first", "tok-second" }, TokensOnDisk());
    }

    [Fact]
    public void BrokenStoreRefusesSaveAndPreservesTheFile()
    {
        Assert.True(AddPhoneAndPersist("tok-kept", "kept phone"));
        var before = File.ReadAllBytes(Program.ConfigPath);
        Program.StoreBroken = true;
        Assert.Throws<InvalidOperationException>(() => Program.SaveConfig());
        // The preserved file is byte-identical: a torn store is never overwritten.
        Assert.Equal(before, File.ReadAllBytes(Program.ConfigPath));
    }
}

/// <summary>W1/W2 (reviewer line fixes on the held S10 helpers): the strict vlcrc read returns
/// null only when the file is really not there, never for an unreadable one; PlanPassword
/// caches ONE candidate so two callers never plan different passwords from the same empty read;
/// an existing live password is returned as-is.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class VlcrcStrictReadTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-vlcrc-" + Guid.NewGuid().ToString("N"));
    private string VlcDirPath => Path.Combine(_dir, "vlc");

    public VlcrcStrictReadTests()
    {
        Directory.CreateDirectory(VlcDirPath);
        VlcSetup.VlcDirOverride = VlcDirPath;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
    }

    public void Dispose()
    {
        VlcSetup.VlcDirOverride = null;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    [Fact]
    public void MissingVlcrcPlansOneCachedCandidateAcrossCalls()
    {
        var first = VlcSetup.PlanPassword();
        Assert.False(string.IsNullOrEmpty(first.Value));
        Assert.True(first.IsFreshCandidate); // branch 6: the pair save must stage it (F12)
        Assert.Equal(first.Value, VlcSetup.PlanPassword().Value); // same empty read, same candidate (W2)
    }

    [Fact]
    public void ExistingVlcrcPasswordIsReturnedUnchanged()
    {
        File.WriteAllText(Path.Combine(VlcDirPath, "vlcrc"), "http-password=live-value\n");
        var plan = VlcSetup.PlanPassword();
        Assert.Equal("live-value", plan.Value);
        Assert.False(plan.IsFreshCandidate); // serve-current plans stage nothing (F12)
    }

    [Fact]
    public void UnreadableVlcrcThrowsNeverReadsAsEmpty()
    {
        // A directory where the file belongs: the read fails with UnauthorizedAccess, which must
        // surface as VlcrcUnreadableException - never as "nothing configured" (W1).
        Directory.CreateDirectory(Path.Combine(VlcDirPath, "vlcrc"));
        Assert.Throws<VlcSetup.VlcrcUnreadableException>(() => VlcSetup.PlanPassword());
    }
}

/// <summary>E4: AdoptGrant marker rollback (base parity restored). A failed persist on the
/// serve marker, the ack spend, or the window-expiry spend restores ALL THREE marker fields
/// and reports Failed (route: 503) - memory never runs ahead of disk, so a same-id retry does
/// not replay from memory and a restart never re-arms the grant.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
public class AdoptGrantRollbackTests
{
    private static readonly Action FailingPersist = () => throw new IOException("disk full");

    [Fact]
    public void FailedServePersistRestoresMarkersAndReportsFailed()
    {
        var phone = new Program.PairedPhone { Token = "t", Name = "P" };
        var r = AdoptGrant.Serve(phone, () => "pw", "req-1", DateTime.UtcNow, FailingPersist);
        Assert.True(r.Failed);
        Assert.False(r.Served);
        Assert.False(phone.AdoptSpent);
        Assert.Null(phone.AdoptRequestId);
        Assert.Null(phone.AdoptServedAtUtc);
    }

    [Fact]
    public void FailedAckPersistRestoresMarkersAndReportsFailed()
    {
        var phone = new Program.PairedPhone { Token = "t", Name = "P", AdoptRequestId = "req-1", AdoptServedAtUtc = DateTime.UtcNow };
        var r = AdoptGrant.Ack(phone, "req-1", FailingPersist);
        Assert.True(r.Failed);
        Assert.False(r.Acked);
        Assert.False(phone.AdoptSpent);
        Assert.Equal("req-1", phone.AdoptRequestId);
        Assert.NotNull(phone.AdoptServedAtUtc);
    }

    [Fact]
    public void FailedWindowExpirySpendRestoresMarkersAndReportsFailed()
    {
        var served = DateTime.UtcNow - TimeSpan.FromMinutes(10); // past the retry window
        var phone = new Program.PairedPhone { Token = "t", Name = "P", AdoptRequestId = "req-1", AdoptServedAtUtc = served };
        var r = AdoptGrant.Serve(phone, () => "pw", "req-2", DateTime.UtcNow, FailingPersist);
        Assert.True(r.Failed);
        Assert.False(r.AlreadySpent);
        Assert.False(phone.AdoptSpent);
        Assert.Equal("req-1", phone.AdoptRequestId);
        Assert.Equal(served, phone.AdoptServedAtUtc);
    }
}

/// <summary>E1/E2: the one authoritative password decision - exact branch table, the pending
/// clear as ONE save unit, and the superseded-never-served rule. Branch 2: stale pending
/// cleared, current served, never rewritten. Branch 3: matching pending cleared WITHOUT
/// rewrite. Branch 4: current served. Branch 5: deferred pending served. Branch 6: nothing (or
/// only a superseded record) - null, and a failed clear marks the record superseded so it is
/// never served or applied afterward.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class PasswordDecisionTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-decide-" + Guid.NewGuid().ToString("N"));
    private string VlcDirPath => Path.Combine(_dir, "vlc");
    private string VlcrcPath => Path.Combine(VlcDirPath, "vlcrc");

    public PasswordDecisionTests()
    {
        Directory.CreateDirectory(VlcDirPath);
        Program.RedirectDataDirForTests(Path.Combine(_dir, "cfg"));
        Directory.CreateDirectory(Path.Combine(_dir, "cfg"));
        Program.StoreBroken = false;
        VlcSetup.VlcDirOverride = VlcDirPath;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
    }

    public void Dispose()
    {
        VlcSetup.VlcDirOverride = null;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        Program.StoreBroken = false;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private void WriteVlcrc(params string[] lines) => File.WriteAllLines(VlcrcPath, lines);

    [Fact]
    public void StalePendingIsClearedAndCurrentServedWithoutRewrite()
    {
        // R2: the current CHANGED EXTERNALLY after the pending was staged (the pending stood
        // over pre-pw; current-pw landed since - VLC's exit rewrite or a hand edit). Staging a
        // value over the current that is still on disk is a DEFERRAL and stands owed (I2Wip10,
        // TW56); staleness is exactly the case where the current is no longer the staged-over one.
        WriteVlcrc("extraintf=http", "http-password=pre-pw", "http-host=0.0.0.0");
        Assert.True(VlcSetup.StagePending("old-pw"));
        WriteVlcrc("extraintf=http", "http-password=current-pw", "http-host=0.0.0.0");
        var bytes = File.ReadAllBytes(VlcrcPath);
        var d = VlcSetup.ResolvePasswordDecision();
        Assert.Equal("current-pw", d.Serve);
        Assert.Equal("old-pw", d.StalePending);
        Assert.Equal("current-pw", VlcSetup.EffectivePassword()); // serves current, clears stale
        Assert.False(VlcSetup.Pending);
        Assert.Equal(bytes, File.ReadAllBytes(VlcrcPath)); // never rewritten
    }

    [Fact]
    public void MatchingPendingIsClearedWithoutRewrite()
    {
        WriteVlcrc("extraintf=http", "http-password=same-pw", "http-host=0.0.0.0");
        var bytes = File.ReadAllBytes(VlcrcPath);
        Assert.True(VlcSetup.StagePending("same-pw"));
        Assert.Equal("same-pw", VlcSetup.EffectivePassword());
        Assert.False(VlcSetup.Pending);
        Assert.Equal(bytes, File.ReadAllBytes(VlcrcPath)); // cleared WITHOUT a rewrite
    }

    [Fact]
    public void DeferredPendingIsServedWhenNothingOnDisk()
    {
        Assert.True(VlcSetup.StagePending("deferred-pw"));
        var d = VlcSetup.ResolvePasswordDecision();
        Assert.True(d.DeferredOwed);
        Assert.Equal("deferred-pw", d.Serve);
    }

    [Fact]
    public void SupersededPendingIsNeverServedOrApplied()
    {
        Assert.True(VlcSetup.StagePending("stale-candidate"));
        // Defense-in-depth: NO production path produces a superseded record today (the old
        // failed-clear poison marker was retired with the I2 faithful whole-state rollback);
        // the hook installs the state directly so the guard's contract stays tested - this
        // is a future-defense state, not the failed-clear contract.
        VlcSetup.SupersedePendingForTests();
        var d = VlcSetup.ResolvePasswordDecision();
        Assert.Null(d.Serve);
        Assert.True(d.SupersededPending);
        // The timer never applies a superseded record: it clears and reports done.
        Assert.False(VlcSetup.RetryPending());
        Assert.False(File.Exists(VlcrcPath)); // nothing was ever written
    }
}

/// <summary>E5: full-file vlcrc atomic replace with the content-identity guard. A vlcrc that
/// changes between the merge read and the replace aborts (never overwritten); a failed
/// post-replace verify keeps the value staged as pending; the no-op path clears the pending
/// record without touching the file.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class VlcrcAtomicWriteTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-vlcwrite-" + Guid.NewGuid().ToString("N"));
    private string VlcDirPath => Path.Combine(_dir, "vlc");
    private string VlcrcPath => Path.Combine(VlcDirPath, "vlcrc");

    public VlcrcAtomicWriteTests()
    {
        Directory.CreateDirectory(VlcDirPath);
        Program.RedirectDataDirForTests(Path.Combine(_dir, "cfg"));
        Directory.CreateDirectory(Path.Combine(_dir, "cfg"));
        Program.StoreBroken = false;
        VlcSetup.VlcDirOverride = VlcDirPath;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
    }

    public void Dispose()
    {
        VlcSetup.VlcDirOverride = null;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        VlcSetup.BeforeReplaceGuardForTests = null;
        VlcSetup.AfterReplaceForTests = null;
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        Program.StoreBroken = false;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private static bool OkOf(string json) => JsonDocument.Parse(json).RootElement.GetProperty("ok").GetBoolean();
    private static string? ReasonOf(string json) => JsonDocument.Parse(json).RootElement.TryGetProperty("reason", out var r) ? r.GetString() : null;

    [Fact]
    public void ChangedContentAbortsTheReplaceAndServesTheDiscoveredState()
    {
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=A", "http-host=0.0.0.0" });
        VlcSetup.BeforeReplaceGuardForTests = () =>
            File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=OTHER", "http-host=0.0.0.0" });
        try
        {
            var json = VlcSetup.WriteVlcrc("B");
            // I2-1: NOT a success for the requested value - the discovered current is DIFFERENT;
            // the caller re-decides and serves the authoritative discovered value.
            Assert.False(OkOf(json));
            Assert.Equal("changed", ReasonOf(json));
            Assert.Equal("OTHER", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password=")).Substring("http-password=".Length));
            Assert.False(VlcSetup.Pending); // the discovered current serves; nothing staged
        }
        finally { VlcSetup.BeforeReplaceGuardForTests = null; }
    }

    [Fact]
    public void FailedPostReplaceVerifyKeepsTheValueStaged()
    {
        VlcSetup.AfterReplaceForTests = () =>
            File.WriteAllLines(VlcrcPath, new[] { "# junk written by someone else" });
        try
        {
            var json = VlcSetup.WriteVlcrc("planned-pw");
            Assert.False(OkOf(json));
            Assert.Equal("verify failed", ReasonOf(json));
            Assert.True(VlcSetup.Pending); // staged, never reported ready
        }
        finally { VlcSetup.AfterReplaceForTests = null; }
    }

    [Fact]
    public void NoOpPathClearsPendingWithoutTouchingTheFile()
    {
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=pw", "http-host=0.0.0.0" });
        var bytes = File.ReadAllBytes(VlcrcPath);
        Assert.True(VlcSetup.StagePending("pw"));
        Assert.True(OkOf(VlcSetup.WriteVlcrc("pw")));
        Assert.False(VlcSetup.Pending);
        Assert.Equal(bytes, File.ReadAllBytes(VlcrcPath));
    }
}

/// <summary>F10: ClearPending mismatch semantics. A mismatched clear is a no-op success that
/// persists nothing, and even when the race lands inside the mutate (another thread staged a
/// DIFFERENT pending between the pre-check and the under-lock re-verify), a failed save's
/// rollback must leave that untouched pending intact - durable AND mirror.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class ClearPendingGuardTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-clrguard-" + Guid.NewGuid().ToString("N"));
    private string VlcDirPath => Path.Combine(_dir, "vlc");

    public ClearPendingGuardTests()
    {
        Directory.CreateDirectory(VlcDirPath);
        Program.RedirectDataDirForTests(Path.Combine(_dir, "cfg"));
        Directory.CreateDirectory(Path.Combine(_dir, "cfg"));
        Program.StoreBroken = false;
        VlcSetup.VlcDirOverride = VlcDirPath;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
    }

    public void Dispose()
    {
        VlcSetup.ClearPendingPreMutateForTests = null;
        VlcSetup.VlcDirOverride = null;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        Program.StoreBroken = false;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    [Fact]
    public void MismatchedClearIsANoOpSuccessAndPersistsNothing()
    {
        Assert.True(VlcSetup.StagePending("real-pending"));
        // Saves now fail: if the mismatched clear attempted a persist it would report failure.
        if (File.Exists(Program.ConfigPath)) File.Delete(Program.ConfigPath);
        Directory.CreateDirectory(Program.ConfigPath);
        try { Assert.True(VlcSetup.ClearPending("not-the-pending")); } // no-op success, save never attempted
        finally { Directory.Delete(Program.ConfigPath, true); }
        Assert.True(VlcSetup.Pending);
        Assert.Equal("real-pending", Program.PendingVlcDurable);
        Assert.Equal("real-pending", VlcSetup.EffectivePassword()); // still served (branch 5)
    }

    [Fact]
    public void FailedSaveAfterARacedStageLeavesTheOtherPendingIntact()
    {
        Assert.True(VlcSetup.StagePending("expected-pw"));
        if (File.Exists(Program.ConfigPath)) File.Delete(Program.ConfigPath);
        Directory.CreateDirectory(Program.ConfigPath); // saves now fail
        // The race: another thread stages a DIFFERENT pending between the pre-check and the
        // under-lock re-verify (hook runs inside the mutate, already under ConfigSaveLock).
        VlcSetup.ClearPendingPreMutateForTests = () => VlcSetup.StagePendingWithinSaveLock("raced-pw");
        try { Assert.False(VlcSetup.ClearPending("expected-pw")); } // the save fails
        finally { VlcSetup.ClearPendingPreMutateForTests = null; Directory.Delete(Program.ConfigPath, true); }
        // The raced pending survived: the failed save's rollback did NOT wipe it (F10).
        Assert.True(VlcSetup.Pending);
        Assert.Equal("raced-pw", Program.PendingVlcDurable);
    }
}

/// <summary>F11: concurrent vlcrc writers never share a temp file (each call owns a unique
/// temp and only ever deletes its own). I2 companion: internal writers now SERIALIZE under
/// ConfigSaveLock, so the old cross-thread replace/first-create races are structurally closed -
/// each write commits legitimately at its turn (last writer wins, no obligation involved). The
/// remaining first-create collision is EXTERNAL only (VLC itself / another process) and is
/// simulated same-thread: it maps to the discovered-state serve, never a bare failure.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class VlcrcConcurrentWriteTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-vlcconc-" + Guid.NewGuid().ToString("N"));
    private string VlcDirPath => Path.Combine(_dir, "vlc");
    private string VlcrcPath => Path.Combine(VlcDirPath, "vlcrc");

    public VlcrcConcurrentWriteTests()
    {
        Directory.CreateDirectory(VlcDirPath);
        Program.RedirectDataDirForTests(Path.Combine(_dir, "cfg"));
        Directory.CreateDirectory(Path.Combine(_dir, "cfg"));
        Program.StoreBroken = false;
        VlcSetup.VlcDirOverride = VlcDirPath;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
    }

    public void Dispose()
    {
        VlcSetup.BeforeReplaceGuardForTests = null;
        VlcSetup.BeforeNewFileMoveForTests = null;
        VlcSetup.VlcDirOverride = null;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        Program.StoreBroken = false;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private static bool OkOf(string json) => JsonDocument.Parse(json).RootElement.GetProperty("ok").GetBoolean();
    private static string? ReasonOf(string json) => JsonDocument.Parse(json).RootElement.TryGetProperty("reason", out var r) ? r.GetString() : null;
    private string CurrentPassword() => File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..];

    [Fact]
    public void ConcurrentWritersSerializeAndBothCompleteTruthfully()
    {
        // I2 companion: internal writers serialize under ConfigSaveLock - both writes commit
        // legitimately at their turn, each ok:true is truthful at its commit (last writer wins;
        // no owed obligation is involved, so no busy-refusal applies).
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=base", "http-host=0.0.0.0" });
        string? jA = null, jB = null; Exception? eA = null, eB = null;
        var tA = new Thread(() => { try { jA = VlcSetup.WriteVlcrc("pw-A"); } catch (Exception ex) { eA = ex; } });
        var tB = new Thread(() => { try { jB = VlcSetup.WriteVlcrc("pw-B"); } catch (Exception ex) { eB = ex; } });
        tA.Start(); tB.Start();
        Assert.True(tA.Join(TimeSpan.FromSeconds(60)));
        Assert.True(tB.Join(TimeSpan.FromSeconds(60)));
        Assert.Null(eA);
        Assert.Null(eB);
        Assert.True(OkOf(jA!));
        Assert.True(OkOf(jB!));
        Assert.Contains(CurrentPassword(), new[] { "pw-A", "pw-B" });
        Assert.Empty(Directory.GetFiles(VlcDirPath, "vlcrc.tmp*")); // no shared/orphaned temp
    }

    [Fact]
    public void ExternalFirstCreateCollisionServesTheDiscoveredValue()
    {
        // The remaining first-create race is EXTERNAL only (VLC itself or another process):
        // simulated same-thread - the hook creates vlcrc between the guard read and the move.
        // The write must re-decide and serve the discovered value - never an overwrite, never
        // a bare failure.
        VlcSetup.BeforeNewFileMoveForTests = () =>
            File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        string json;
        try { json = VlcSetup.WriteVlcrc("pw-mine"); }
        finally { VlcSetup.BeforeNewFileMoveForTests = null; }
        Assert.False(OkOf(json));
        Assert.Equal("changed", ReasonOf(json));
        Assert.Equal("X", CurrentPassword()); // the discovered value stands, nothing overwritten
        Assert.False(VlcSetup.Pending); // the discovered current serves; nothing staged over it
        Assert.Empty(Directory.GetFiles(VlcDirPath, "vlcrc.tmp*"));
    }
}

/// <summary>F12: the pair save transaction is atomic across phone + pending, and readiness is
/// truthful. (a) a failed save at the stage rolls back phone AND pending (route: code stays
/// unspent, 503); (b) a fresh candidate lands in the SAME save as the phone (crash-safe);
/// (c) when the apply's emergency stage cannot persist, the value comes back UNVERIFIED (the
/// reply's vlcPending = !verifiedInEffect || Pending reads true - never a false ready);
/// (d) a serve-current plan stages nothing.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class PairTransactionTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-pairtxn-" + Guid.NewGuid().ToString("N"));
    private string VlcDirPath => Path.Combine(_dir, "vlc");
    private string VlcrcPath => Path.Combine(VlcDirPath, "vlcrc");

    public PairTransactionTests()
    {
        Directory.CreateDirectory(VlcDirPath);
        Program.RedirectDataDirForTests(Path.Combine(_dir, "cfg"));
        Directory.CreateDirectory(Path.Combine(_dir, "cfg"));
        Program.StoreBroken = false;
        VlcSetup.VlcDirOverride = VlcDirPath;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        lock (Program.PhonesLock) Program.Phones.Clear();
    }

    public void Dispose()
    {
        VlcSetup.VlcDirOverride = null;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        VlcSetup.VlcRunningForTests = null;
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        lock (Program.PhonesLock) Program.Phones.Clear();
        Program.StoreBroken = false;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private void BreakSaves() { if (File.Exists(Program.ConfigPath)) File.Delete(Program.ConfigPath); Directory.CreateDirectory(Program.ConfigPath); }
    private void FixSaves() { if (Directory.Exists(Program.ConfigPath)) Directory.Delete(Program.ConfigPath, true); }

    [Fact]
    public void FailedSaveAtStageRollsBackPhoneAndPending()
    {
        string? name = "Pixel";
        BreakSaves();
        try { Assert.False(HttpServer.TrySavePair("tok-a", ref name, "fresh-pw", out _)); }
        finally { FixSaves(); }
        lock (Program.PhonesLock) Assert.Empty(Program.Phones);
        Assert.Null(Program.PendingVlcDurable);
        Assert.False(VlcSetup.Pending);
    }

    [Fact]
    public void FreshCandidateLandsInTheSameSaveAsThePhone()
    {
        string? name = "Pixel";
        Assert.True(HttpServer.TrySavePair("tok-b", ref name, "fresh-pw", out var noInstead));
        Assert.Null(noInstead);
        lock (Program.PhonesLock) Assert.Contains(Program.Phones, p => p.Token == "tok-b");
        Assert.Equal("fresh-pw", Program.PendingVlcDurable);
        Assert.True(VlcSetup.Pending);
        var disk = JsonDocument.Parse(File.ReadAllText(Program.ConfigPath)).RootElement;
        Assert.Equal("fresh-pw", disk.GetProperty("pendingVlcPassword").GetString());
        Assert.Contains(disk.GetProperty("phones").EnumerateArray(), p => p.GetProperty("token").GetString() == "tok-b");
    }

    [Fact]
    public void ApplyWithFailedEmergencyStageReportsUnverified()
    {
        // vlcrc unreadable (a directory where the file belongs) + saves fail: the emergency
        // stage cannot persist. The served value must come back UNVERIFIED - the pair reply's
        // vlcPending = !verifiedInEffect || Pending then reads true even though Pending is
        // false (F12/D2: never a false ready).
        Directory.CreateDirectory(VlcrcPath);
        BreakSaves();
        (string value, bool verifiedInEffect) applied = (null!, true);
        try { applied = VlcSetup.ApplyPlannedPassword("planned-pw"); }
        finally { FixSaves(); }
        Assert.Equal("planned-pw", applied.value);
        Assert.False(applied.verifiedInEffect);
        Assert.False(VlcSetup.Pending); // nothing durable - and still never "ready"
    }

    [Fact]
    public void StagedFreshCandidateAppliesImmediatelyWhenVlcClosed()
    {
        // R3: the pair save staged the fresh candidate; VLC is closed -> the apply writes NOW
        // through the E5-guarded path, clears the pending as one E2 unit, and reports verified.
        Assert.True(VlcSetup.StagePending("fresh-pw"));
        var applied = VlcSetup.ApplyPlannedPassword("fresh-pw");
        Assert.Equal("fresh-pw", applied.value);
        Assert.True(applied.verifiedInEffect);
        Assert.False(VlcSetup.Pending);
        Assert.Equal("fresh-pw", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
    }

    [Fact]
    public void StagedFreshCandidateDefersWhenVlcRunning()
    {
        // R3: VLC running -> the same staged plan defers truthfully (verified false, pending
        // kept); the retry timer completes it when VLC closes.
        Assert.True(VlcSetup.StagePending("fresh-pw"));
        VlcSetup.VlcRunningForTests = () => true;
        try
        {
            var applied = VlcSetup.ApplyPlannedPassword("fresh-pw");
            Assert.Equal("fresh-pw", applied.value);
            Assert.False(applied.verifiedInEffect);
        }
        finally { VlcSetup.VlcRunningForTests = null; }
        Assert.True(VlcSetup.Pending);
        Assert.False(File.Exists(VlcrcPath));
    }

    [Fact]
    public void DifferentOwedPendingIsNeverOverwrittenByThePlan()
    {
        // R3 boundary: a DIFFERENT owed value is served as deferred and the plan writes nothing.
        Assert.True(VlcSetup.StagePending("other-pw"));
        var applied = VlcSetup.ApplyPlannedPassword("planned-pw");
        Assert.Equal("other-pw", applied.value);
        Assert.False(applied.verifiedInEffect);
        Assert.False(File.Exists(VlcrcPath));
        Assert.Equal("other-pw", Program.PendingVlcDurable);
    }

    [Fact]
    public void ServeCurrentPlanStagesNothing()
    {
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=live-pw", "http-host=0.0.0.0" });
        var plan = VlcSetup.PlanPassword();
        Assert.Equal("live-pw", plan.Value);
        Assert.False(plan.IsFreshCandidate);
        string? name = "Pixel";
        Assert.True(HttpServer.TrySavePair("tok-d", ref name, plan.IsFreshCandidate ? plan.Value : null, out _));
        Assert.Null(Program.PendingVlcDurable);
        Assert.False(VlcSetup.Pending);
        var applied = VlcSetup.ApplyPlannedPassword(plan.Value);
        Assert.Equal("live-pw", applied.value);
        Assert.True(applied.verifiedInEffect); // the strict read just observed it
    }
}

/// <summary>I2 WIP10 blockers: (1) a changed file under the write is never a success for the
/// requested value - apply/adopt serve the AUTHORITATIVE discovered value; (2) an expected
/// IO/access failure after the durable phone save still yields a truthful answer (staged
/// pending, verified false - never a bare failure that strands the phone); (3) a pending
/// staged between plan and the pair save's lock is owed and durable - the pair never
/// overwrites it and serves that value instead.
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class I2Wip10BlockerTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-i2b-" + Guid.NewGuid().ToString("N"));
    private string VlcDirPath => Path.Combine(_dir, "vlc");
    private string VlcrcPath => Path.Combine(VlcDirPath, "vlcrc");

    public I2Wip10BlockerTests()
    {
        Directory.CreateDirectory(VlcDirPath);
        Program.RedirectDataDirForTests(Path.Combine(_dir, "cfg"));
        Directory.CreateDirectory(Path.Combine(_dir, "cfg"));
        Program.StoreBroken = false;
        VlcSetup.VlcDirOverride = VlcDirPath;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        lock (Program.PhonesLock) Program.Phones.Clear();
    }

    public void Dispose()
    {
        VlcSetup.BeforeReplaceGuardForTests = null;
        VlcSetup.BeforeNewFileMoveForTests = null;
        VlcSetup.BeforePlanningReadForTests = null;
        VlcSetup.VlcRunningForTests = null;
        VlcSetup.VlcDirOverride = null;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        try { new DirectoryInfo(VlcDirPath).Attributes = FileAttributes.Normal; } catch { }
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        lock (Program.PhonesLock) Program.Phones.Clear();
        Program.StoreBroken = false;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    [Fact]
    public void ApplyOverAChangedFileServesTheDiscoveredValueNotThePlan()
    {
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=A", "http-host=0.0.0.0" });
        VlcSetup.BeforeReplaceGuardForTests = () =>
            File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=OTHER", "http-host=0.0.0.0" });
        (string value, bool verifiedInEffect) applied;
        try { applied = VlcSetup.ApplyPlannedPassword("B"); }
        finally { VlcSetup.BeforeReplaceGuardForTests = null; }
        Assert.Equal("OTHER", applied.value); // the authoritative discovered value, never the plan
        Assert.True(applied.verifiedInEffect); // the strict re-read just observed it
        Assert.Equal("OTHER", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending);
    }

    [Fact]
    public void ReadOrCreateServesTheDiscoveredValueAfterAConcurrentChange()
    {
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=orig", "http-host=0.0.0.0" });
        VlcSetup.BeforeReplaceGuardForTests = () =>
            File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=OTHER", "http-host=0.0.0.0" });
        string served;
        try { served = VlcSetup.ReadOrCreatePassword(); }
        finally { VlcSetup.BeforeReplaceGuardForTests = null; }
        Assert.Equal("OTHER", served); // the one-shot adopt fetch is never spent on the wrong value
    }

    [Fact]
    public void VlcrcBlockedByDirectoryAfterDurablePairStagesAndReportsUnverified()
    {
        // A directory squats on the vlcrc path: a REAL access failure on Windows (a ReadOnly
        // DIRECTORY attribute is an Explorer hint, never enforced for file IO - run 2 proved it
        // fails nothing). Whether the failure surfaces at the decision read or at the write
        // depends on the OS exception mapping; both routes converge on the same contract and
        // this test asserts the contract, not the branch: the apply stages the pending and
        // reports unverified - the pair reply still carries the token with vlcPending=true,
        // never a stranded 500. The staged baseline stays the pair save's (proven absent).
        string? name = "Pixel";
        Assert.True(HttpServer.TrySavePair("tok-io", ref name, "fresh-pw", out _));
        Directory.CreateDirectory(VlcrcPath);
        (string value, bool verifiedInEffect) applied = (null!, true);
        try { applied = VlcSetup.ApplyPlannedPassword("fresh-pw"); }
        finally { try { Directory.Delete(VlcrcPath); } catch { } }
        Assert.Equal("fresh-pw", applied.value);
        Assert.False(applied.verifiedInEffect);
        Assert.True(VlcSetup.Pending);
        Assert.Equal("fresh-pw", Program.PendingVlcDurable);
        Assert.Null(Program.PendingVlcStagedOverDurable);
        Assert.False(Program.PendingVlcStagedOverUncertainDurable);
        lock (Program.PhonesLock) Assert.Contains(Program.Phones, p => p.Token == "tok-io"); // phone stayed durable
    }

    [Fact]
    public void ReadOrCreateFreshPathServesDiscoveredAfterCompetingCreate()
    {
        // T-F13b: branch 6 (nothing configured); a competing creator lands X while our fresh
        // write is mid-flight: the served value is X, never the generated candidate.
        var fired = 0;
        VlcSetup.BeforeNewFileMoveForTests = () =>
        {
            if (Interlocked.Exchange(ref fired, 1) == 0)
                File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        };
        string served;
        try { served = VlcSetup.ReadOrCreatePassword(); }
        finally { VlcSetup.BeforeNewFileMoveForTests = null; }
        Assert.Equal("X", served);
        Assert.Equal("X", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending); // nothing staged over the discovered current (F13)
    }

    [Fact]
    public void InjectedIoFailureAfterDurablePairStagesAndReportsUnverified()
    {
        // T-F14: raw IOException injected mid-write (hook throws) AFTER the phone is durable:
        // the apply stages and reports unverified - the reply still carries token+pending.
        string? name = "Pixel";
        Assert.True(HttpServer.TrySavePair("tok-io2", ref name, "fresh-pw", out _));
        VlcSetup.BeforeReplaceGuardForTests = () => throw new IOException("injected replace failure");
        (string value, bool verifiedInEffect) applied = (null!, true);
        try { applied = VlcSetup.ApplyPlannedPassword("fresh-pw"); }
        finally { VlcSetup.BeforeReplaceGuardForTests = null; }
        Assert.Equal("fresh-pw", applied.value);
        Assert.False(applied.verifiedInEffect);
        Assert.True(VlcSetup.Pending);
        Assert.Equal("fresh-pw", Program.PendingVlcDurable);
        lock (Program.PhonesLock) Assert.Contains(Program.Phones, p => p.Token == "tok-io2");
    }

    [Fact]
    public void InterveningOwedPendingKeepsTheServeAndDropsTheCandidate()
    {
        // T-F15: setup stages owed S with VLC running; a pair with fresh candidate C preserves
        // S, drops C, and the apply serves (S, unverified) - C never lands anywhere.
        Assert.True(VlcSetup.StagePending("S-owed"));
        VlcSetup.VlcRunningForTests = () => true;
        try
        {
            string? name = "Pixel";
            Assert.True(HttpServer.TrySavePair("tok-f15", ref name, "C-candidate", out var instead));
            Assert.Equal("S-owed", instead);
            Assert.Equal("S-owed", Program.PendingVlcDurable);
            var applied = VlcSetup.ApplyPlannedPassword(instead ?? "C-candidate");
            Assert.Equal("S-owed", applied.value);
            Assert.False(applied.verifiedInEffect); // VLC running: deferred, truthful
            // The dropped candidate never reappears: a later plan decides branch 5 and serves S.
            var plan = VlcSetup.PlanPassword();
            Assert.Equal("S-owed", plan.Value);
            Assert.False(plan.IsFreshCandidate);
        }
        finally { VlcSetup.VlcRunningForTests = null; }
        Assert.False(File.Exists(VlcrcPath));
        Assert.Equal("S-owed", Program.PendingVlcDurable);
    }

    [Fact]
    public void InterveningOwedPendingSurvivesThePairSaveAndIsServed()
    {
        // A /vlc/setup deferral staged AFTER the plan but BEFORE the pair save's lock: owed and
        // durable - the pair must not overwrite it; it serves that value instead.
        Assert.True(VlcSetup.StagePending("owed-pw"));
        string? name = "Pixel";
        Assert.True(HttpServer.TrySavePair("tok-race", ref name, "candidate-pw", out var instead));
        Assert.Equal("owed-pw", instead);
        Assert.Equal("owed-pw", Program.PendingVlcDurable);
        var applied = VlcSetup.ApplyPlannedPassword(instead ?? "candidate-pw");
        Assert.Equal("owed-pw", applied.value);
        Assert.True(applied.verifiedInEffect); // VLC closed: the owed write completed immediately (R3)
        Assert.False(VlcSetup.Pending);
        Assert.Equal("owed-pw", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
    }

    [Fact]
    public void DeferredOwedOverAnUnchangedCurrentAppliesWhenVlcCloses()
    {
        // I2Wip10 marker lifecycle: Y staged over X; X is unchanged at decision time, so the
        // obligation stands (never stale-cleared) and the retry applies it once VLC closes.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("Y"));
        Assert.Equal("X", Program.PendingVlcStagedOverDurable);
        Assert.False(Program.PendingVlcStagedOverUncertainDurable);
        VlcSetup.VlcRunningForTests = () => true;
        try { Assert.True(VlcSetup.RetryPending()); } // still owed while VLC runs
        finally { VlcSetup.VlcRunningForTests = null; }
        Assert.False(VlcSetup.RetryPending()); // VLC closed: applied
        Assert.Equal("Y", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending);
        Assert.Null(Program.PendingVlcDurable);
        Assert.Null(Program.PendingVlcStagedOverDurable);
    }

    [Fact]
    public void ExternalChangeAfterThePlanningReadIsStaleNeverOwed()
    {
        // I2 D2: Z lands AFTER Y was staged over X - Y must never gain Z as a consent baseline.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("Y"));
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        var d = VlcSetup.ResolvePasswordDecision();
        Assert.Equal("Z", d.Serve);
        Assert.Equal("Y", d.StalePending);
        Assert.False(d.DeferredOwed);
        Assert.False(VlcSetup.RetryPending()); // clears Y unapplied, never writes over Z
        Assert.Equal("Z", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending);
    }

    [Fact]
    public void ConvergeOnTheSameOwedValueKeepsTheOriginalBaseline()
    {
        // I2 D2: re-staging the SAME owed value after an external change must not re-baseline.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("Y")); // staged over X
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("Y")); // converge: baseline stays X
        Assert.Equal("X", Program.PendingVlcStagedOverDurable);
        var d = VlcSetup.ResolvePasswordDecision();
        Assert.Equal("Y", d.StalePending); // Z != X: stale, never owed over Z
    }

    [Fact]
    public void UncertainBaselineNeverStaleClearsAndNeverApplies()
    {
        // I2 D2 fail-closed: the baseline read FAILS at stage time (vlcrc held with no sharing:
        // a sharing violation, deterministic - no OS exception-type guessing). Later an
        // arbitrary current appears: the pending STANDS - never cleared (could strand the phone
        // holding it), never applied over that current - and the status reports the obligation.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=locked", "http-host=0.0.0.0" });
        using (new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.StagePending("Y"));
        Assert.True(Program.PendingVlcStagedOverUncertainDurable);
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        var d = VlcSetup.ResolvePasswordDecision();
        Assert.Equal("Z", d.Serve); // the truth in effect is served
        Assert.Null(d.StalePending); // ...but the pending is NOT cleared
        Assert.False(d.DeferredOwed);
        Assert.True(d.PendingUnresolved);
        Assert.True(VlcSetup.RetryPending()); // keeps waiting; no write over Z
        Assert.Equal("Z", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.True(VlcSetup.Pending);
        Assert.Equal((false, true), VlcSetup.StatusSnapshot()); // the unresolved obligation is reported
    }

    [Fact]
    public void FailedSaveStagesNothingAndLeavesNoMarker()
    {
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        if (File.Exists(Program.ConfigPath)) File.Delete(Program.ConfigPath);
        Directory.CreateDirectory(Program.ConfigPath); // saves now fail
        string? stage = null;
        try { stage = VlcSetup.StagePendingGuarded("Y", "X", true); }
        finally { Directory.Delete(Program.ConfigPath, true); }
        Assert.Equal("failed", stage);
        Assert.False(VlcSetup.Pending);
        Assert.Null(Program.PendingVlcDurable);
        Assert.Null(Program.PendingVlcStagedOverDurable);
    }

    [Fact]
    public void FailedClearRollbackRestoresTheMarkerWithTheValue()
    {
        // The marker moves in the SAME E2 unit: a failed clear restores the value AND its
        // baseline together - never a value with another baseline's provenance.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("W")); // W staged over X
        if (File.Exists(Program.ConfigPath)) File.Delete(Program.ConfigPath);
        Directory.CreateDirectory(Program.ConfigPath); // saves now fail
        try { Assert.False(VlcSetup.ClearPending("W")); } // rollback: value + marker restored WHOLE (I2 faithful)
        finally { Directory.Delete(Program.ConfigPath, true); }
        Assert.Equal("W", Program.PendingVlcDurable);
        Assert.Equal("X", Program.PendingVlcStagedOverDurable);
        Assert.False(Program.PendingVlcStagedOverUncertainDurable);
        var d = VlcSetup.ResolvePasswordDecision();
        // The restored record is the SAME live obligation as before the failed clear - never
        // a manufactured poison state: it re-decides on its PROVEN baseline X (current ==
        // baseline, so it is owed, not stale, not superseded): the certain-baseline deferral
        // branch serves the OWED pending W with DeferredOwed (the apply completes over X when
        // VLC closes; the in-effect split is EffectivePassword's job, not this decision's
        // serve). It can only ever apply over X - a different current stale-clears it
        // (OwedPendingProtection coverage), a verified-in-effect W would matching-clear it.
        Assert.Null(d.StalePending);
        Assert.False(d.SupersededPending);
        Assert.True(d.DeferredOwed);
        Assert.Equal("W", d.Serve);
    }

    [Fact]
    public void ReplaceFailureKeepsThePlanningReadBaseline()
    {
        // I2 D2 write path: owed == planned with VLC closed applies NOW (R3); the replace fails
        // (vlcrc held read-share: the planning read succeeds, the replace cannot land). The
        // emergency re-stage converges on the same value and KEEPS the planning-read baseline -
        // never a re-captured later state.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=old", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("new-pw")); // staged over "old"
        using var held = new FileStream(VlcrcPath, FileMode.Open, FileAccess.Read, FileShare.Read);
        var applied = VlcSetup.ApplyPlannedPassword("new-pw");
        Assert.Equal("new-pw", applied.value);
        Assert.False(applied.verifiedInEffect);
        Assert.True(VlcSetup.Pending);
        Assert.Equal("new-pw", Program.PendingVlcDurable);
        Assert.Equal("old", Program.PendingVlcStagedOverDurable);
        Assert.False(Program.PendingVlcStagedOverUncertainDurable);
    }

    [Fact]
    public void RestartRehydratesTheMarkerAndCompletesTheOwedApply()
    {
        // Persistence/rehydration: the durable config carries value + baseline; a restart
        // re-derives the owed deferral and completes it when VLC is already closed.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("Y")); // over X, saved durably
        VlcSetup.ResetPendingForTests(); // simulate the mirror lost to a restart (the config file stands)
        Program.LoadConfig();
        Assert.Equal("Y", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending);
        Assert.Null(Program.PendingVlcDurable);
        Assert.Null(Program.PendingVlcStagedOverDurable);
    }

    [Fact]
    public void PreMarkerConfigMigratesAsUncertainAndFailsClosed()
    {
        // Migration: a config written BEFORE the marker existed carries no baseline keys. The
        // rehydrated pending is fail-closed uncertain - never the old silent stale-clear, never
        // applied over an arbitrary current. It stands until a later state resolves it.
        File.WriteAllText(Program.ConfigPath, "{\"phones\":[],\"pendingVlcPassword\":\"Y\"}");
        VlcSetup.VlcRunningForTests = () => true; // rehydrate re-arms, never applies during load
        try { Program.LoadConfig(); }
        finally { VlcSetup.VlcRunningForTests = null; }
        Assert.True(Program.PendingVlcStagedOverUncertainDurable);
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        var d = VlcSetup.ResolvePasswordDecision();
        Assert.Null(d.StalePending);
        Assert.True(d.PendingUnresolved);
        Assert.True(VlcSetup.RetryPending()); // stands; no write over Z
        Assert.Equal("Z", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.Equal((false, true), VlcSetup.StatusSnapshot());
    }

    private static bool OkOf(string json) => JsonDocument.Parse(json).RootElement.GetProperty("ok").GetBoolean();
    private static string? ReasonOf(string json) => JsonDocument.Parse(json).RootElement.TryGetProperty("reason", out var r) ? r.GetString() : null;

    [Fact]
    public void UnreachableVlcrcIsNeverPlannedAsCertainAbsence()
    {
        // I2 D4 production write path: a directory squats on the vlcrc path - File.Exists
        // reports false for it, but the planning read must NOT launder that into a
        // certain-absent staged-over baseline (a later decision would stale-clear the owed
        // write over the file that was really there). The strict planning read refuses the
        // whole write instead: no stage, no marker, a truthful failure the route maps to 503.
        Directory.CreateDirectory(VlcrcPath);
        try { Assert.Throws<VlcSetup.VlcrcUnreadableException>(() => VlcSetup.WriteVlcrc("Z")); }
        finally { try { Directory.Delete(VlcrcPath); } catch { } }
        Assert.False(VlcSetup.Pending);
        Assert.Null(Program.PendingVlcDurable);
        Assert.Null(Program.PendingVlcStagedOverDurable);
        Assert.False(Program.PendingVlcStagedOverUncertainDurable);
    }

    [Fact]
    public void ProvenStalePlanFailsExplicitlyOnReadErrorAndIsNeverRestaged()
    {
        // I2 D5+N2: C was staged over X; external Z replaced X (C is proven stale). The apply
        // clears the stale record, then the verified serve's strict read fails. The emergency
        // path must neither re-stage C (its only baseline would be Z, the value that replaced
        // it) NOR reply with C (a credential neither on disk nor owed - the phone would hold a
        // pending=true reply against a PC with no pending): an explicit failure, which the
        // pair route maps to a retryable 503.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("C"));
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        FileStream? held = null;
        VlcSetup.BeforeReplaceGuardForTests = () =>
            held = new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None); // the verified serve's strict read fails
        try { Assert.Throws<VlcSetup.VlcrcUnreadableException>(() => VlcSetup.ApplyPlannedPassword("C")); }
        finally { VlcSetup.BeforeReplaceGuardForTests = null; held?.Dispose(); }
        Assert.False(VlcSetup.Pending); // never re-staged over the discovered current
        Assert.Null(Program.PendingVlcDurable);
        Assert.Null(Program.PendingVlcStagedOverDurable);
        var d = VlcSetup.ResolvePasswordDecision(); // readable again: Z serves, nothing is owed
        Assert.Equal("Z", d.Serve);
        Assert.False(d.DeferredOwed);
    }

    [Fact]
    public void ProvenStalePlanServesTheVerifiedDiscoveredCurrentWhenReadable()
    {
        // I2 N2, transient failure: the verified serve's read fails once; the emergency
        // re-decide succeeds. The reply carries the VERIFIED discovered current - never the
        // stale plan - and nothing is staged.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("C"));
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        VlcSetup.BeforeReplaceGuardForTests = () => throw new IOException("simulated transient failure at the verified serve");
        (string value, bool verifiedInEffect) applied;
        try { applied = VlcSetup.ApplyPlannedPassword("C"); }
        finally { VlcSetup.BeforeReplaceGuardForTests = null; }
        Assert.Equal("Z", applied.value); // the discovered truth, never the stale plan
        Assert.True(applied.verifiedInEffect); // the strict re-decide just observed it
        Assert.False(VlcSetup.Pending);
        Assert.Null(Program.PendingVlcDurable);
    }

    [Fact]
    public void AutonomousRetryNeverWritesOverAChangeThatLandedAfterTheDecision()
    {
        // I2 baseline window: Y is owed over X; external Z lands BETWEEN the retry's decision
        // and the write's planning read. The autonomous apply must not write Y over Z - the
        // carried decision baseline mismatches the planning read, so it re-decides: Y is stale
        // over Z and cleared unapplied; Z stands.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("Y"));
        VlcSetup.BeforePlanningReadForTests = () =>
            File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        try { VlcSetup.RetryPending(); }
        finally { VlcSetup.BeforePlanningReadForTests = null; }
        Assert.Equal("Z", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending); // Y stale-cleared by the re-decide - never written over Z
        Assert.Null(Program.PendingVlcDurable);
    }

    [Fact]
    public void ExplicitFreshWriteStagesOverItsOwnProvenPlanningRead()
    {
        // Contrast (I2): an EXPLICIT fresh write carries no prior decision - its own strict
        // planning read is the consent baseline, so the current it proves is staged-over as
        // the proven state (never certain-absent, never a stale recapture).
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        VlcSetup.VlcRunningForTests = () => true;
        string json;
        try { json = VlcSetup.WriteVlcrc("Q"); }
        finally { VlcSetup.VlcRunningForTests = null; }
        Assert.Equal("vlc running", ReasonOf(json));
        Assert.Equal("Q", Program.PendingVlcDurable);
        Assert.Equal("Z", Program.PendingVlcStagedOverDurable);
        Assert.False(Program.PendingVlcStagedOverUncertainDurable);
    }

    [Fact]
    public void ResolutionPersistFailureRestoresTheUnresolvedRecordWhole()
    {
        // I2 N1 transaction failure: the atomic replace rolls back on a failed save - the OLD
        // unresolved record comes back whole (value + baseline + uncertain flag); the new
        // value is nowhere. Old and new obligations are never both absent.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        using (new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.StagePending("Y")); // uncertain baseline
        if (File.Exists(Program.ConfigPath)) File.Delete(Program.ConfigPath);
        Directory.CreateDirectory(Program.ConfigPath); // saves now fail
        string json;
        VlcSetup.VlcRunningForTests = () => true;
        try { json = VlcSetup.WriteVlcrc("Z"); }
        finally { VlcSetup.VlcRunningForTests = null; Directory.Delete(Program.ConfigPath, true); }
        Assert.False(OkOf(json));
        Assert.Equal("stage-failed", ReasonOf(json)); // truthful: nothing durable changed
        Assert.True(VlcSetup.Pending);
        Assert.Equal("Y", Program.PendingVlcDurable); // the old record restored whole
        Assert.Null(Program.PendingVlcStagedOverDurable);
        Assert.True(Program.PendingVlcStagedOverUncertainDurable);
    }

    [Fact]
    public void VerifiedServeNeverStagesAProvenStalePlanWhenTheDiscoveredCurrentDisappears()
    {
        // I2 VerifiedServe stale gate: C was staged over X; external Z replaced X (C proven
        // stale and cleared); then Z DISAPPEARS before the verified serve's re-decide.
        // Nothing serves - but the proven-stale C is still never re-staged: explicit failure
        // (the route maps it to a retryable 503), never a nowhere credential.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("C"));
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        VlcSetup.BeforeReplaceGuardForTests = () => File.Delete(VlcrcPath); // the discovered current disappears
        try { Assert.Throws<VlcSetup.VlcrcUnreadableException>(() => VlcSetup.ApplyPlannedPassword("C")); }
        finally { VlcSetup.BeforeReplaceGuardForTests = null; }
        Assert.False(VlcSetup.Pending); // the proven-stale plan was never re-staged
        Assert.Null(Program.PendingVlcDurable);
    }

    [Fact]
    public void FailedClearLeavesTheOldRecordUnresolvedAndNeverAutonomouslyOverwriting()
    {
        // I2 resolution-clear failure boundary (commit-first ordering): the explicit
        // replacement Q writes and VERIFIES; clearing the replaced record Y fails to persist.
        // The old record stands - UNRESOLVED (uncertain baseline, different from the new
        // current): autonomous retries never write it over Q; the route's ok stays truthful
        // (Q IS in effect); the next explicit request resolves the record.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        using (new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.StagePending("Y")); // uncertain baseline, saves still working
        if (File.Exists(Program.ConfigPath)) File.Delete(Program.ConfigPath);
        Directory.CreateDirectory(Program.ConfigPath); // config saves now fail
        string json;
        try { json = VlcSetup.WriteVlcrc("Q"); }
        finally { Directory.Delete(Program.ConfigPath, true); }
        Assert.True(OkOf(json)); // truthful: Q is verified in effect
        Assert.Equal("Q", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.Equal("Y", Program.PendingVlcDurable); // the failed clear rolled back: Y stands whole
        Assert.True(Program.PendingVlcStagedOverUncertainDurable);
        Assert.True(VlcSetup.Pending); // surfaced as an unresolved pending, not dropped
        Assert.True(VlcSetup.RetryPending()); // unresolved: still waiting - NEVER writes Y over Q
        Assert.Equal("Q", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.True(VlcSetup.Pending);
        var resolved = VlcSetup.WriteVlcrc("Q"); // the next explicit request resolves it
        Assert.True(OkOf(resolved));
        Assert.False(VlcSetup.Pending);
        Assert.Null(Program.PendingVlcDurable);
    }

    [Fact]
    public void PairReplyDeliversTheTokenAndTheOwedPasswordWhenTheApplyCannotVerify()
    {
        // I2 route recovery (production pair path): the phone is durable and the code spent;
        // the apply's write then fails. The apply CONVERGES internally on the durably owed
        // value (the compare-guarded emergency stage finds the same live record), so the reply
        // carries the token (the phone never loses its pairing), the DURABLY OWED value, and
        // vlcPending - and the owed value completes on the next retry pass.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("C")); // C durably owed over X
        VlcSetup.BeforePlanningReadForTests = () => throw new IOException("simulated planning-read failure after the pair commit");
        string json;
        try { json = HttpServer.FinishPairReply("tok-route", "C"); }
        finally { VlcSetup.BeforePlanningReadForTests = null; }
        using (var doc = JsonDocument.Parse(json))
        {
            Assert.Equal("tok-route", doc.RootElement.GetProperty("token").GetString()); // the token ALWAYS reaches the phone
            Assert.Equal("C", doc.RootElement.GetProperty("vlcPassword").GetString()); // durably owed - servable (internal convergence)
            Assert.True(doc.RootElement.GetProperty("vlcPending").GetBoolean());
        }
        Assert.True(VlcSetup.Pending); // the obligation stands
        Assert.Equal("X", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]); // nothing written
        Assert.False(VlcSetup.RetryPending()); // recovery: the owed write completes on the next pass
        Assert.Equal("C", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending);
    }

    [Fact]
    public void PairReplyServesTheVerifiedCurrentAndNeverTheUncertainRecordsValue()
    {
        // I2 in-effect separation at the pair route (production path): with an UNRESOLVED
        // record (uncertain baseline) standing over a different current, the apply needs no
        // write - the VERIFIED in-effect current X rides the reply while the unresolved
        // record's value NEVER does. vlcPending stays true (S10 formula: a live obligation
        // stands) so the phone confirms through its status check; the record stands for
        // explicit resolution (I2 N1), never dropped, never applied over the unproven current.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        using (new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.StagePending("UNC-owed-value")); // uncertain baseline
        Assert.True(VlcSetup.ResolvePasswordDecision().PendingUnresolved);
        var json = HttpServer.FinishPairReply("tok-unc", "UNC-owed-value");
        using (var doc = JsonDocument.Parse(json))
        {
            Assert.Equal("tok-unc", doc.RootElement.GetProperty("token").GetString());
            Assert.Equal("X", doc.RootElement.GetProperty("vlcPassword").GetString()); // the VERIFIED in-effect truth
            Assert.True(doc.RootElement.GetProperty("vlcPending").GetBoolean()); // S10: a live obligation still stands
        }
        Assert.DoesNotContain("UNC-owed-value", json); // the unresolved record's value never rides
        Assert.True(VlcSetup.Pending); // the obligation stands unresolved
        Assert.Equal("X", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]); // untouched
        Assert.True(OkOf(VlcSetup.WriteVlcrc("X"))); // the next explicit request resolves it (N1)
        Assert.False(VlcSetup.Pending);
    }

    [Fact]
    public void PairReplyNeverServesAPhantomCredentialWhenThePlanIsProvenStale()
    {
        // I2 route recovery at the N2 boundary (production pair path): C was staged over X;
        // external Z replaced X; Z then DISAPPEARS mid-apply. The apply fails explicitly; the
        // reply still carries the token but NO password (nothing verified, nothing owed) -
        // vlcPending true sends the phone through its status/adopt checks instead of storing
        // a nowhere credential.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("C"));
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        VlcSetup.BeforeReplaceGuardForTests = () => File.Delete(VlcrcPath);
        string json;
        try { json = HttpServer.FinishPairReply("tok-route2", "C"); }
        finally { VlcSetup.BeforeReplaceGuardForTests = null; }
        using (var doc = JsonDocument.Parse(json))
        {
            Assert.Equal("tok-route2", doc.RootElement.GetProperty("token").GetString());
            Assert.Equal(JsonValueKind.Null, doc.RootElement.GetProperty("vlcPassword").ValueKind); // never a phantom
            Assert.True(doc.RootElement.GetProperty("vlcPending").GetBoolean());
        }
        Assert.False(VlcSetup.Pending); // the stale plan was cleared, never re-staged
        Assert.Null(Program.PendingVlcDurable);
    }

    [Fact]
    public void ExplicitNoOpClearsAnUnresolvableRecordOnlyAfterVerifying()
    {
        // I2 N1 no-op path: the requested value is ALREADY in effect; the unresolvable record
        // (uncertain baseline, different from the verified current) is cleared after the
        // strict verification, and the request reports truthful success.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        using (new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.StagePending("Y")); // uncertain baseline
        Assert.True(VlcSetup.ResolvePasswordDecision().PendingUnresolved);
        var json = VlcSetup.WriteVlcrc("X"); // explicit request for the value already in effect
        Assert.True(OkOf(json));
        Assert.False(VlcSetup.Pending);
        Assert.Null(Program.PendingVlcDurable);
    }

    [Fact]
    public void HostRepairNeverResolvesAnUnresolvableRecordIncidentally()
    {
        // I2 N1 caller intent: ReadOrCreate's idempotent host repair is NOT an explicit phone
        // request - it must never clear the unresolved record. It serves the in-effect current.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        using (new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.StagePending("Y")); // uncertain baseline
        var served = VlcSetup.ReadOrCreatePassword();
        Assert.Equal("X", served); // the value actually in effect
        Assert.True(VlcSetup.Pending); // the unresolved record STANDS - no incidental resolution
        Assert.Equal("Y", Program.PendingVlcDurable);
        Assert.True(Program.PendingVlcStagedOverUncertainDurable);
    }

    [Fact]
    public void ExplicitSameValueRequestRebaselinesAnUncertainRecordOnItsProvenRead()
    {
        // I2 same-value explicit consent: the phone explicitly re-requests the OWED value Y
        // while its baseline is uncertain and the proven current is X. The defer stage
        // re-baselines on this request's proven planning read (not the unprovable converge)
        // - and the owed apply can then complete when VLC closes.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        using (new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.StagePending("Y")); // uncertain baseline
        VlcSetup.VlcRunningForTests = () => true;
        string json;
        try { json = VlcSetup.WriteVlcrc("Y"); }
        finally { VlcSetup.VlcRunningForTests = null; }
        Assert.Equal("vlc running", ReasonOf(json));
        Assert.Equal("Y", Program.PendingVlcDurable);
        Assert.Equal("X", Program.PendingVlcStagedOverDurable); // re-baselined on the proven read
        Assert.False(Program.PendingVlcStagedOverUncertainDurable);
        Assert.False(VlcSetup.RetryPending()); // VLC closed: the owed apply lands over the proven baseline
        Assert.Equal("Y", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending);
    }

    [Fact]
    public void UnresolvablePendingYieldsToAnExplicitRequestAndDefersWithProvenBaseline()
    {
        // I2 bounded resolution, deferral path (VLC running): Y's baseline is uncertain and
        // the strict planning read proves a DIFFERENT current X - Y could never resolve and
        // would busy every later request forever. The explicit request clears the unresolved
        // record and defers with the planning read's PROVEN baseline - and because that
        // baseline is proven, the owed apply completes when VLC closes.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        using (new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.StagePending("Y")); // uncertain baseline (the stage-time read fails)
        Assert.True(Program.PendingVlcStagedOverUncertainDurable);
        VlcSetup.VlcRunningForTests = () => true;
        string json;
        try { json = VlcSetup.WriteVlcrc("Z"); }
        finally { VlcSetup.VlcRunningForTests = null; }
        Assert.False(OkOf(json));
        Assert.Equal("vlc running", ReasonOf(json)); // deferred - NOT pending-busy
        Assert.True(VlcSetup.Pending);
        Assert.Equal("Z", Program.PendingVlcDurable);
        Assert.Equal("X", Program.PendingVlcStagedOverDurable); // proven by this request's strict planning read
        Assert.False(Program.PendingVlcStagedOverUncertainDurable);
        Assert.False(VlcSetup.RetryPending()); // VLC closed: the owed apply lands over the proven baseline
        Assert.Equal("Z", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending);
    }

    [Fact]
    public void DifferentCurrentWithPersistentReadFailuresStandsUntilAnExplicitRequestResolves()
    {
        // I2 bounded resolution, immediate path (VLC closed): while the vlcrc stays unreadable
        // the uncertain pending neither applies nor clears; once reads recover with a different
        // current it stands unresolved; the explicit new request resolves it and writes.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        using (new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.StagePending("Y")); // uncertain baseline
        using (var held = new FileStream(VlcrcPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            Assert.True(VlcSetup.RetryPending()); // persistent read failure: stands - never applied, never cleared
        Assert.True(VlcSetup.ResolvePasswordDecision().PendingUnresolved);
        var json = VlcSetup.WriteVlcrc("Z"); // VLC closed: the explicit request resolves and writes now
        Assert.True(OkOf(json));
        Assert.Equal("Z", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending);
        Assert.Null(Program.PendingVlcDurable);
    }

    [Fact]
    public void TornStoreMissingBaselineKeyMigratesUncertain()
    {
        // Torn/partial store: the uncertain flag says false but the baseline KEY is absent -
        // a well-formed atomic save always carries both. Fail closed: the pending rehydrates
        // uncertain, never a certain-absent baseline parsed out of a malformed record.
        File.WriteAllText(Program.ConfigPath, "{\"phones\":[],\"pendingVlcPassword\":\"Y\",\"pendingVlcStagedOverUncertain\":false}");
        VlcSetup.VlcRunningForTests = () => true; // rehydrate re-arms, never applies during load
        try { Program.LoadConfig(); }
        finally { VlcSetup.VlcRunningForTests = null; }
        Assert.True(Program.PendingVlcStagedOverUncertainDurable);
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Z", "http-host=0.0.0.0" });
        var d = VlcSetup.ResolvePasswordDecision();
        Assert.Null(d.StalePending);
        Assert.True(d.PendingUnresolved);
        Assert.True(VlcSetup.RetryPending()); // stands; no write over Z
        Assert.Equal((false, true), VlcSetup.StatusSnapshot());
    }

    [Fact]
    public void EffectivePasswordServesTheInEffectCurrentDuringADeferral()
    {
        // I2 in-effect separation: Y is owed over the unchanged current X. While the deferral
        // stands, VLC still accepts X - control serving Y would 401 until the apply. The owed
        // Y stays attested as pending; after the apply, Y is the value in effect.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("Y"));
        Assert.Equal("X", VlcSetup.EffectivePassword()); // what VLC accepts right now
        Assert.Equal((false, true), VlcSetup.StatusSnapshot()); // Y still owed and attested
        Assert.False(VlcSetup.RetryPending()); // VLC closed: the apply lands
        Assert.Equal("Y", VlcSetup.EffectivePassword()); // now in effect
    }
}

/// <summary>I2 same-obligation companion: a DIFFERENT live owed pending is an existing
/// obligation held by phone(s) - a setup write (VLC running or closed) must never replace it
/// or make it disappear; the request gets a retryable busy with state unchanged. The same
/// value (or none) converges; a no-op/successful write clears only its OWN satisfied
/// obligation; genuine independently-current supersession stays separate (covered by the
/// existing branch-2 stale-clear and superseded-never-served tests). F14b: an adopt serve is
/// only ever verified-current or durably staged - a nowhere value throws (route maps to 503,
/// one-shot grant unspent).
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class OwedPendingProtectionTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-owed-" + Guid.NewGuid().ToString("N"));
    private string VlcDirPath => Path.Combine(_dir, "vlc");
    private string VlcrcPath => Path.Combine(VlcDirPath, "vlcrc");

    public OwedPendingProtectionTests()
    {
        Directory.CreateDirectory(VlcDirPath);
        Program.RedirectDataDirForTests(Path.Combine(_dir, "cfg"));
        Directory.CreateDirectory(Path.Combine(_dir, "cfg"));
        Program.StoreBroken = false;
        VlcSetup.VlcDirOverride = VlcDirPath;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        VlcSetup.VlcRunningForTests = null;
        ClearAnyMirrorPending(); // static mirror survives a prior class; start clean
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
    }

    public void Dispose()
    {
        VlcSetup.VlcDirOverride = null;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        VlcSetup.VlcRunningForTests = null;
        ClearAnyMirrorPending();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        Program.StoreBroken = false;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private static void ClearAnyMirrorPending()
    {
        var pv = VlcSetup.PendingValueWithinSaveLock;
        if (pv != null) VlcSetup.ClearPending(pv);
    }

    private static bool OkOf(string json) => JsonDocument.Parse(json).RootElement.GetProperty("ok").GetBoolean();
    private static string? ReasonOf(string json) => JsonDocument.Parse(json).RootElement.TryGetProperty("reason", out var r) ? r.GetString() : null;

    [Fact]
    public void DifferentOwedPendingBlocksSetupWhileVlcRuns()
    {
        Assert.True(VlcSetup.StagePending("P-owed"));
        VlcSetup.VlcRunningForTests = () => true;
        var json = VlcSetup.WriteVlcrc("Q-new");
        Assert.False(OkOf(json));
        Assert.Equal("pending-busy", ReasonOf(json)); // retryable busy, state unchanged
        Assert.True(VlcSetup.Pending);
        Assert.Equal("P-owed", Program.PendingVlcDurable); // the obligation stands
        Assert.False(File.Exists(VlcrcPath)); // Q never staged, never written
    }

    [Fact]
    public void DifferentOwedPendingBlocksSetupWhileVlcClosed()
    {
        Assert.True(VlcSetup.StagePending("P-owed"));
        VlcSetup.VlcRunningForTests = () => false;
        var json = VlcSetup.WriteVlcrc("Q-new");
        Assert.False(OkOf(json));
        Assert.Equal("pending-busy", ReasonOf(json));
        Assert.True(VlcSetup.Pending);
        Assert.Equal("P-owed", Program.PendingVlcDurable);
        Assert.False(File.Exists(VlcrcPath)); // I2: Q is never written, running or closed
    }

    [Fact]
    public void SameOwedValueConvergesAndClearsWhenVlcClosed()
    {
        Assert.True(VlcSetup.StagePending("P-owed"));
        VlcSetup.VlcRunningForTests = () => false;
        var json = VlcSetup.WriteVlcrc("P-owed");
        Assert.True(OkOf(json));
        Assert.Equal("P-owed", File.ReadAllLines(VlcrcPath).First(l => l.StartsWith("http-password="))["http-password=".Length..]);
        Assert.False(VlcSetup.Pending); // the write cleared only its own satisfied obligation
        Assert.Null(Program.PendingVlcDurable);
    }

    [Fact]
    public void NoOpWriteNeverClearsAnUnrelatedOwedPending()
    {
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=Q", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("P-owed"));
        var json = VlcSetup.WriteVlcrc("Q"); // Q is already in effect: a truthful no-op
        Assert.True(OkOf(json));
        Assert.True(VlcSetup.Pending); // the unrelated obligation was NOT cleared by Q's no-op
        Assert.Equal("P-owed", Program.PendingVlcDurable);
    }

    [Fact]
    public void AdoptServeThrowsWhenTheValueIsNeitherVerifiedNorDurablyStaged()
    {
        // F14b / I2 adopt contract: VLC running defers, but the stage's persist fails - the
        // requested value is nowhere (not on disk, not durable). ReadOrCreate must throw; the
        // adopt route maps IOException to a retryable 503 and leaves the grant unspent
        // (wrap verified at HttpServer.cs:205-213), never spending it on a nowhere-password.
        VlcSetup.VlcRunningForTests = () => true;
        if (File.Exists(Program.ConfigPath)) File.Delete(Program.ConfigPath);
        Directory.CreateDirectory(Program.ConfigPath); // saves now fail
        try { Assert.Throws<IOException>(() => VlcSetup.ReadOrCreatePassword()); }
        finally { Directory.Delete(Program.ConfigPath, true); }
        Assert.False(VlcSetup.Pending); // the failed stage rolled back: nothing false-owed
        Assert.Null(Program.PendingVlcDurable);
    }

    [Fact]
    public void AdoptServeAcceptsADurablyStagedDeferredValue()
    {
        VlcSetup.VlcRunningForTests = () => true;
        var served = VlcSetup.ReadOrCreatePassword();
        Assert.True(VlcSetup.Pending);
        Assert.Equal(served, Program.PendingVlcDurable); // durably staged: a legal adopt serve
        Assert.False(File.Exists(VlcrcPath));
    }
}

/// <summary>DELTA-H (+I2 R2 correction): the setup-status attestation is ONE coherent read
/// (ConfigSaveLock -> Gate; a write/pending pair is never observed half-committed; ONE strict
/// file read feeds every component); pending means a LIVE obligation (non-superseded and NO
/// current password on disk - the only state E1 would still apply); a different current makes
/// the pending E1-stale (cleared unapplied), so that state is configured, never pending;
/// configured is the full strict checks and never true while a live obligation waits; an
/// unreadable vlcrc throws (route: 503, never an empty/false attestation).
/// RUNTIME LABEL: compile-verified on the Linux evidence route; behavior not run there
/// (Windows-TFM test host). Runs on the Windows verification gate.</summary>
[Collection("ProgramState")]
public class StatusSnapshotTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-status-" + Guid.NewGuid().ToString("N"));
    private string VlcDirPath => Path.Combine(_dir, "vlc");
    private string VlcrcPath => Path.Combine(VlcDirPath, "vlcrc");

    public StatusSnapshotTests()
    {
        Directory.CreateDirectory(VlcDirPath);
        Program.RedirectDataDirForTests(Path.Combine(_dir, "cfg"));
        Directory.CreateDirectory(Path.Combine(_dir, "cfg"));
        Program.StoreBroken = false;
        VlcSetup.VlcDirOverride = VlcDirPath;
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        ClearAnyMirrorPending(); // static mirror survives a prior class; start clean
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
    }

    public void Dispose()
    {
        VlcSetup.VlcDirOverride = null;
        VlcSetup.VlcRunningForTests = null; // static: never leak the "VLC closed" hook into another test class
        VlcSetup.ForgetCandidateForTests();
        VlcSetup.ResetPendingForTests();
        ClearAnyMirrorPending();
        lock (Program.ConfigSaveLock) Program.PendingVlcDurable = null;
        Program.StoreBroken = false;
        Program.ResetDataDirForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private static void ClearAnyMirrorPending()
    {
        var pv = VlcSetup.PendingValueWithinSaveLock;
        if (pv != null) VlcSetup.ClearPending(pv);
    }

    [Fact]
    public void LiveObligationReportsPendingAndNeverConfigured()
    {
        Assert.True(VlcSetup.StagePending("P-owed")); // e.g. staged while VLC was running
        var (configured, pending) = VlcSetup.StatusSnapshot();
        Assert.True(pending);
        Assert.False(configured); // invariant: configured is never true while a live obligation waits
    }

    [Fact]
    public void AppliedEqualPendingAfterRestartReportsNotPendingAndConfigured()
    {
        // Branch 3 clears an applied-equal record only when a decision runs; a status read is
        // READ-ONLY and must still not report it as a waiting write.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=P", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("P"));
        var (configured, pending) = VlcSetup.StatusSnapshot();
        Assert.False(pending);
        Assert.True(configured);
        Assert.True(VlcSetup.Pending); // untouched: no mutation during a status read
    }

    [Fact]
    public void PoisonedSupersededPendingReportsNotPending()
    {
        Assert.True(VlcSetup.StagePending("P-owed"));
        // Same defense-in-depth note as SupersededPendingIsNeverServedOrApplied: no production
        // producer exists today; the hook installs the superseded state directly so the
        // status contract stays tested (pending means a LIVE obligation - DELTA-H).
        VlcSetup.SupersedePendingForTests();
        var (configured, pending) = VlcSetup.StatusSnapshot();
        Assert.False(pending); // a superseded record is not a live obligation
        Assert.False(configured);
    }

    [Fact]
    public void UnreadableVlcrcThrowsInsteadOfAFalseAttestation()
    {
        Directory.CreateDirectory(VlcrcPath); // vlcrc exists but cannot be read as a file
        Assert.Throws<VlcSetup.VlcrcUnreadableException>(() => VlcSetup.StatusSnapshot());
    }

    [Fact]
    public void RetryPendingApplyDuringStatusReadsYieldsOnlyCoherentTuples()
    {
        // While RetryPending ACTUALLY applies an owed write, every StatusSnapshot a reader
        // observes is the coherent BEFORE (false,true) or AFTER (true,false) payload - never
        // (false,false) or (true,true), because the clear is inside the write's critical
        // section. I2: full before/after payload alternatives, not a tautology.
        Assert.True(VlcSetup.StagePending("P-owed"));
        VlcSetup.VlcRunningForTests = () => false;
        var done = new ManualResetEventSlim(false);
        var violations = 0; var reads = 0;
        var reader = new Thread(() =>
        {
            while (!done.IsSet)
            {
                var (c, p) = VlcSetup.StatusSnapshot();
                if (c == p) Interlocked.Increment(ref violations); // (T,T) and (F,F) are both incoherent here
                Interlocked.Increment(ref reads);
            }
        });
        reader.Start();
        Assert.False(VlcSetup.RetryPending()); // applies P and clears the obligation in one critical section
        Thread.Sleep(50);
        done.Set();
        Assert.True(reader.Join(TimeSpan.FromSeconds(30)));
        Assert.True(reads > 0);
        Assert.Equal(0, violations);
        Assert.Equal((true, false), VlcSetup.StatusSnapshot());
    }

    [Fact]
    public void OwedDeferredPendingOverCurrentReportsPendingNotConfigured()
    {
        // I2 D1: vlcrc carries X and a DIFFERENT pending Y is staged over it - a deferral: Y
        // stands OWED (not stale; staleness begins only when the current is no longer the
        // staged-over one). A decision would still apply Y when VLC closes, so the status is a
        // live obligation: pending, NOT configured - configured is never true while a live
        // obligation waits, even though phones holding X can control VLC right now. The read
        // mutates nothing: Y's record still awaits its decision.
        File.WriteAllLines(VlcrcPath, new[] { "extraintf=http", "http-password=X", "http-host=0.0.0.0" });
        Assert.True(VlcSetup.StagePending("Y"));
        Assert.Equal((false, true), VlcSetup.StatusSnapshot());
        Assert.True(VlcSetup.Pending); // untouched by the read
    }
}
