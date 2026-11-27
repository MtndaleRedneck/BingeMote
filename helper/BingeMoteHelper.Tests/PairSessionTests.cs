using System;
using Xunit;

namespace BingeMoteHelper.Tests;

[Collection("ProgramState")]
public class PairSessionTests : IDisposable
{
    public PairSessionTests() { Reset(); }
    public void Dispose() { Reset(); }
    private static void Reset() { lock (HttpServer.PairLock) { Program.PairCode = null; Program.PendingPhoneName = null; Program.PairFailures = 0; } }

    [Fact]
    public void SessionStaysOpenUnderTenMinutes_AndClosesAtTenMinutes()
    {
        var t0 = new DateTime(2026, 11, 26, 20, 0, 0, DateTimeKind.Utc);
        lock (HttpServer.PairLock) { Program.PairCode = "4321"; Program.PairCodeIssuedUtc = t0; Program.PendingPhoneName = "P"; }
        Assert.False(HttpServer.ExpirePairCodeIfStale(t0.AddMinutes(9).AddSeconds(59)));
        Assert.Equal("4321", Program.PairCode);
        Assert.True(HttpServer.ExpirePairCodeIfStale(t0.AddMinutes(10)));
        Assert.Null(Program.PairCode);
        Assert.Null(Program.PendingPhoneName);
        Assert.False(HttpServer.ExpirePairCodeIfStale(t0.AddMinutes(11)));   // nothing left to close
    }

    [Fact]
    public void NoCodeMeansNothingToClose()
    {
        Assert.False(HttpServer.ExpirePairCodeIfStale(DateTime.UtcNow));
    }
}
