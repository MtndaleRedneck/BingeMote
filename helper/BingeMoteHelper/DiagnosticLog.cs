using System.Diagnostics;
using System.Threading.Channels;

namespace BingeMoteHelper;

/// <summary>Separate rolling budgets: samples cannot consume fault/transition capacity.</summary>
internal sealed class DiagnosticLogBudget
{
    private readonly Queue<long> _samples = new(), _faults = new();
    internal bool TryTake(long nowMilliseconds, bool fault = false)
    {
        var times = fault ? _faults : _samples;
        while (times.Count > 0 && nowMilliseconds - times.Peek() >= 60_000) times.Dequeue();
        if (times.Count >= (fault ? 48 : 12)) return false;
        times.Enqueue(nowMilliseconds);
        return true;
    }
}

/// <summary>At most 12 sampled move lines plus 48 fault/transition lines in any rolling
/// minute. Separate bounded queues reserve fault capacity. Admission is reported to
/// callers. No disk write or queue wait on input threads. Queued lines can be lost at exit.</summary>
internal static class DiagnosticLog
{
    private static readonly object Gate = new();
    private static readonly DiagnosticLogBudget Budget = new();
    private static readonly Channel<string> Samples = MakeQueue(12), Faults = MakeQueue(48);
    private static long _sampleDropped, _faultDropped;
    private static Channel<string> MakeQueue(int capacity) => Channel.CreateBounded<string>(
        new BoundedChannelOptions(capacity) { SingleReader = true, FullMode = BoundedChannelFullMode.Wait });

    static DiagnosticLog()
    {
        _ = Task.Run(async () =>
        {
            while (true)
            {
                string? line;
                if (Faults.Reader.TryRead(out line) || Samples.Reader.TryRead(out line))
                {
                    try { Program.Log(line); } catch { /* keep the diagnostic worker alive */ }
                }
                else await Task.Delay(100);
            }
        });
    }

    internal static bool Write(string line) => Admit(line, true);
    internal static bool Sample(string line) => Admit(line, false);
    private static bool Admit(string line, bool fault)
    {
        try
        {
            lock (Gate)
            {
                var milliseconds = (long)(Stopwatch.GetTimestamp() * (1000.0 / Stopwatch.Frequency));
                ref long dropped = ref (fault ? ref _faultDropped : ref _sampleDropped);
                var queue = fault ? Faults : Samples;
                if (!Budget.TryTake(milliseconds, fault)) { dropped++; return false; }
                var message = line + (dropped > 0 ? $" diagnostic_lines_dropped={dropped}" : "");
                if (!queue.Writer.TryWrite(message)) { dropped++; return false; }
                dropped = 0;
                return true;
            }
        }
        catch { return false; }
    }
}
