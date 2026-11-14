using System.IO.Pipes;
using System.Text;

namespace BingeMoteHelper;

/// <summary>
/// rev11: helper-side half of the SYSTEM input broker (see InputBroker). Each input op
/// tries the pipe first; on any failure the caller falls back to the original local
/// SendInput/SetCursorPos path, so old installs without the broker behave exactly as before.
/// A broker that just failed is retried at most every 30s so input never stutters.
/// </summary>
internal static class InputBrokerClient
{
    private static readonly object Gate = new();
    private static DateTime _retryAfter = DateTime.MinValue;
    private static bool _everUsed, _everFellBack;

    /// <summary>True when the broker accepted and played the op; false = caller must play it locally.</summary>
    internal static bool TryRun(string opLine)
    {
        if (!InputSender.BrokerRoutingEnabled) return false;
        lock (Gate)
        {
            if (DateTime.UtcNow < _retryAfter) return false;
            try
            {
                using var pipe = new NamedPipeClientStream(".", InputBroker.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous); // async handle: real overlapped IO, cancelled on dispose
                pipe.Connect(1000);
                using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
                using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
                // .NET 8's PipeStream.ReadTimeout/WriteTimeout throw InvalidOperationException
                // ("Timeouts are not supported on this stream") on this stream in both sync and
                // async modes (CI run 43532556389), so bound the round-trip with WaitAsync instead;
                // on timeout the abandoned overlapped op is cancelled when the pipe is disposed.
                writer.WriteLineAsync(opLine).WaitAsync(TimeSpan.FromSeconds(1)).GetAwaiter().GetResult();
                var reply = reader.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(2)).GetAwaiter().GetResult();
                if (reply == "ok")
                {
                    if (!_everUsed)
                    {
                        _everUsed = true;
                        DiagnosticLog.Write("input_routing=broker (SYSTEM input broker active)");
                    }
                    return true;
                }
                throw new InvalidOperationException(reply ?? "no reply");
            }
            catch (Exception ex)
            {
                _retryAfter = DateTime.UtcNow.AddSeconds(30);
                if (!_everFellBack)
                {
                    _everFellBack = true;
                    DiagnosticLog.Write("input_routing=local (input broker unavailable, using local SendInput)");
                }
                // Always recorded (fault budget): the first failure needs its reason.
                DiagnosticLog.Write($"input_broker_client error {ex.GetType().Name}: {ex.Message}");
                return false;
            }
        }
    }
}
