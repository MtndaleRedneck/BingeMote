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
                using var pipe = new NamedPipeClientStream(".", InputBroker.PipeName, PipeDirection.InOut, PipeOptions.None);
                pipe.Connect(200);
                using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
                using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
                pipe.WriteTimeout = 1000;
                pipe.ReadTimeout = 1000;
                writer.WriteLine(opLine);
                var reply = reader.ReadLine();
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
            catch
            {
                _retryAfter = DateTime.UtcNow.AddSeconds(30);
                if (!_everFellBack)
                {
                    _everFellBack = true;
                    DiagnosticLog.Write("input_routing=local (input broker unavailable, using local SendInput)");
                }
                return false;
            }
        }
    }
}
