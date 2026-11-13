using System.Diagnostics;
using System.Threading.Channels;

namespace BingeMoteHelper;

/// <summary>Shared rolling-minute budget, including diagnostic state transitions.</summary>
internal sealed class DiagnosticLogBudget
{
    private readonly Queue<long> _times = new();
    internal bool TryTake(long nowMilliseconds)
    {
        while (_times.Count > 0 && nowMilliseconds - _times.Peek() >= 60_000) _times.Dequeue();
        if (_times.Count >= 60) return false;
        _times.Enqueue(nowMilliseconds);
        return true;
    }
}

/// <summary>Bounded, best-effort local diagnostics. No disk writes on input threads.
/// At most 60 lines in any rolling minute, including all state changes. Excess lines
/// are dropped; this trades exhaustive logging for keeping input responsive.</summary>
internal static class DiagnosticLog
{
    private static readonly object Gate = new();
    private static readonly DiagnosticLogBudget Budget = new();
    private static readonly Channel<string> Pending = Channel.CreateBounded<string>(
        new BoundedChannelOptions(64) { SingleReader = true, FullMode = BoundedChannelFullMode.Wait });

    static DiagnosticLog()
    {
        _ = Task.Run(async () =>
        {
            await foreach (var line in Pending.Reader.ReadAllAsync())
            {
                Program.Log(line);
            }
        });
    }

    internal static void Write(string line)
    {
        try
        {
          lock (Gate)
          {
            var milliseconds = (long)(Stopwatch.GetTimestamp() * (1000.0 / Stopwatch.Frequency));
            if (!Budget.TryTake(milliseconds)) return;
            Pending.Writer.TryWrite(line); // never wait for the worker or disk
          }
        }
        catch { /* diagnostics must never escape into the input path */ }
    }
}
