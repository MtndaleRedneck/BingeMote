using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class AdoptGrantTests
{
    private static (Program.PairedPhone phone, List<bool> saves) FreshPhone()
    {
        return (new Program.PairedPhone { Token = "tok", Name = "Phone 1" }, new List<bool>());
    }

    /// <summary>What a helper restart sees: only the fields persisted on the phone record.</summary>
    private static Program.PairedPhone Rehydrate(Program.PairedPhone p) => new()
    {
        Token = p.Token, Name = p.Name, AdoptSpent = p.AdoptSpent,
        AdoptRequestId = p.AdoptRequestId, AdoptServedAtUtc = p.AdoptServedAtUtc,
    };

    [Fact]
    public void ServeHandsOutThePasswordOnce()
    {
        var (phone, saves) = FreshPhone();
        var r = AdoptGrant.Serve(phone, () => "pw-1", "req-1", DateTime.UtcNow, () => saves.Add(true));
        Assert.True(r.Served);
        Assert.False(r.Replayed);
        Assert.Equal("pw-1", r.Password);
        Assert.False(phone.AdoptSpent); // not spent until the ack or the window
    }

    [Fact]
    public void SameRequestIdReplaysWithinTheWindow()
    {
        var (phone, saves) = FreshPhone();
        var t0 = DateTime.UtcNow;
        var first = AdoptGrant.Serve(phone, () => "pw-1", "req-1", t0, () => saves.Add(true));
        var retry = AdoptGrant.Serve(phone, () => "pw-DIFFERENT", "req-1", t0.AddMinutes(2), () => saves.Add(true));
        Assert.True(retry.Served);
        Assert.True(retry.Replayed);
        // The replay re-reads the effective password: a PC-side change mid-window is honored,
        // and an unchanged one returns the same value, so a lost response never bricks the phone.
        Assert.Equal("pw-DIFFERENT", retry.Password);
        Assert.NotNull(first.Password);
        Assert.False(phone.AdoptSpent);
    }

    [Fact]
    public void DifferentRequestIdWithinTheWindowConflictsWithoutSpending()
    {
        var (phone, saves) = FreshPhone();
        var t0 = DateTime.UtcNow;
        AdoptGrant.Serve(phone, () => "pw-1", "req-1", t0, () => saves.Add(true));
        var r = AdoptGrant.Serve(phone, () => "pw-1", "req-2", t0.AddMinutes(1), () => saves.Add(true));
        Assert.True(r.Conflict);
        Assert.False(r.Served);
        Assert.False(phone.AdoptSpent);
        Assert.Empty(saves);
    }

    [Fact]
    public void AckSpendsTheGrantAndPersists()
    {
        var (phone, saves) = FreshPhone();
        AdoptGrant.Serve(phone, () => "pw-1", "req-1", DateTime.UtcNow, () => saves.Add(true));
        var ack = AdoptGrant.Ack(phone, "req-1", () => saves.Add(true));
        Assert.True(ack.Acked);
        Assert.True(phone.AdoptSpent);
        Assert.Single(saves);
        var again = AdoptGrant.Serve(phone, () => "pw-1", "req-9", DateTime.UtcNow, () => saves.Add(true));
        Assert.True(again.AlreadySpent);
        Assert.False(again.Served);
    }

    [Fact]
    public void AckIsIdempotent()
    {
        var (phone, saves) = FreshPhone();
        AdoptGrant.Serve(phone, () => "pw-1", "req-1", DateTime.UtcNow, () => saves.Add(true));
        AdoptGrant.Ack(phone, "req-1", () => saves.Add(true));
        var second = AdoptGrant.Ack(phone, "req-1", () => saves.Add(true));
        Assert.True(second.Acked); // a retried ack is a no-op, not an error
    }

    [Fact]
    public void WindowExpiringWithoutAckSpendsTheGrant()
    {
        var (phone, saves) = FreshPhone();
        var t0 = DateTime.UtcNow;
        AdoptGrant.Serve(phone, () => "pw-1", "req-1", t0, () => saves.Add(true));
        var late = AdoptGrant.Serve(phone, () => "pw-1", "req-1", t0.Add(AdoptGrant.RetryWindow).AddSeconds(1), () => saves.Add(true));
        Assert.True(late.AlreadySpent);
        Assert.True(phone.AdoptSpent);
        Assert.Single(saves); // the spend persisted
    }

    [Fact]
    public void SpentMarkerSurvivesARestart()
    {
        var (phone, saves) = FreshPhone();
        AdoptGrant.Serve(phone, () => "pw-1", "req-1", DateTime.UtcNow, () => saves.Add(true));
        AdoptGrant.Ack(phone, "req-1", () => saves.Add(true));
        var rehydrated = Rehydrate(phone); // the helper "restarts": only the record survives
        var r = AdoptGrant.Serve(rehydrated, () => "pw-1", "req-2", DateTime.UtcNow, () => saves.Add(true));
        Assert.True(r.AlreadySpent); // the persisted marker keeps the grant spent
        Assert.False(r.Served);
    }

    [Fact]
    public void ServedMarkerIsPersistedAtServeTime()
    {
        var (phone, saves) = FreshPhone();
        var t0 = DateTime.UtcNow;
        AdoptGrant.Serve(phone, () => "pw-1", "req-1", t0, () => saves.Add(true));
        Assert.Equal("req-1", phone.AdoptRequestId); // persisted marker, no value stored on the record
        Assert.Equal(t0, phone.AdoptServedAtUtc);
        Assert.Single(saves); // persisted BEFORE the response went out
    }

    [Fact]
    public void UnackedGrantReplaysAfterARestartNeverReArms()
    {
        var (phone, saves) = FreshPhone();
        var t0 = DateTime.UtcNow;
        AdoptGrant.Serve(phone, () => "pw-1", "req-1", t0, () => saves.Add(true));
        var rehydrated = Rehydrate(phone); // the helper "restarts": only the record survives
        var replay = AdoptGrant.Serve(rehydrated, () => "pw-1", "req-1", t0.AddMinutes(1), () => saves.Add(true));
        Assert.True(replay.Served);
        Assert.True(replay.Replayed); // same id in the window: replay, never a fresh grant
        var conflict = AdoptGrant.Serve(rehydrated, () => "pw-1", "req-2", t0.AddMinutes(1), () => saves.Add(true));
        Assert.True(conflict.Conflict);
        var late = AdoptGrant.Serve(rehydrated, () => "pw-1", "req-1", t0.Add(AdoptGrant.RetryWindow).AddSeconds(1), () => saves.Add(true));
        Assert.True(late.AlreadySpent); // past the window the persisted marker spends the grant
        Assert.True(rehydrated.AdoptSpent);
    }

    [Fact]
    public void AckWithoutAMarkerIsNothingToAck()
    {
        var (phone, saves) = FreshPhone();
        var r = AdoptGrant.Ack(phone, "req-never-served", () => saves.Add(true));
        Assert.False(r.Acked);
        Assert.False(phone.AdoptSpent);
        Assert.Empty(saves);
    }

    [Fact]
    public void ConcurrentServesAreAtomicPerToken()
    {
        var (phone, saves) = FreshPhone();
        var served = 0;
        var tasks = Enumerable.Range(0, 20).Select(i => Task.Run(() =>
        {
            var r = AdoptGrant.Serve(phone, () => "pw-1", "req-" + i, DateTime.UtcNow, () => { lock (saves) saves.Add(true); });
            if (r.Served && !r.Replayed) Interlocked.Increment(ref served);
        })).ToArray();
        Task.WaitAll(tasks);
        Assert.Equal(1, served); // exactly one fetch wins the check-and-set
    }

    [Fact]
    public void EmptyRequestIdIsRejected()
    {
        var (phone, saves) = FreshPhone();
        var r = AdoptGrant.Serve(phone, () => "pw-1", "", DateTime.UtcNow, () => saves.Add(true));
        Assert.False(r.Served);
        Assert.False(phone.AdoptSpent);
    }
}
