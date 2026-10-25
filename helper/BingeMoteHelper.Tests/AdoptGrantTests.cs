using BingeMoteHelper;

namespace BingeMoteHelper.Tests;

public class AdoptGrantTests
{
    private static (Program.PairedPhone phone, List<bool> saves) FreshPhone()
    {
        AdoptGrant.ResetForTests();
        return (new Program.PairedPhone { Token = "tok", Name = "Phone 1" }, new List<bool>());
    }

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
        Assert.Equal(first.Password, retry.Password); // the lost response must not brick the phone
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
        AdoptGrant.ResetForTests(); // the helper "restarts": in-memory state is gone
        var r = AdoptGrant.Serve(phone, () => "pw-1", "req-2", DateTime.UtcNow, () => saves.Add(true));
        Assert.True(r.AlreadySpent); // the persisted marker keeps the grant spent
        Assert.False(r.Served);
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
