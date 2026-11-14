using System.IO.Pipes;
using System.Text;

namespace BingeMoteHelper;

/// <summary>
/// rev11: helper-side half of the SYSTEM input broker (see InputBroker). Each input op
/// tries the pipe first; on any failure the caller falls back to the original local
/// SendInput/SetCursorPos path, so old installs without the broker behave exactly as before.
///
/// rev12 hardening (from the rev11 review):
/// - One PERSISTENT verified connection instead of a connect per op under the global lock:
///   no per-frame connect cost, and the server verifies the client once per connection.
/// - The client verifies the SERVER before sending anything (PipePeer: the pipe's server
///   process must be this same installed exe in this session; the broker in turn refuses
///   to run unless it is SYSTEM), so a fake endpoint squatted on the pipe name gets no ops.
/// - A broker that has never answered is retried at most every 30s so input never
///   stutters; a connection that was working and dropped is retried quickly (1s), and a
///   failed op is retried once on a fresh connection before falling back locally.
/// </summary>
internal static class InputBrokerClient
{
    private static readonly object Gate = new();
    private static DateTime _retryAfter = DateTime.MinValue;
    private static bool _everUsed, _everFellBack, _hadConnection;

    private static NamedPipeClientStream? _pipe;
    private static StreamReader? _reader;
    private static StreamWriter? _writer;

    /// <summary>True when the broker accepted and played the op; false = caller must play it locally.</summary>
    internal static bool TryRun(string opLine)
    {
        if (!InputSender.BrokerRoutingEnabled) return false;
        lock (Gate)
        {
            if (DateTime.UtcNow < _retryAfter) return false;
            try
            {
                return TryRunLocked(opLine);
            }
            catch (Exception ex)
            {
                // A once-good connection that broke gets a quick retry; a broker that never
                // answered backs off for 30s so per-frame ops never pay a connect each.
                _retryAfter = DateTime.UtcNow.AddSeconds(_hadConnection ? 1 : 30);
                Cleanup();
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

    private static bool TryRunLocked(string opLine)
    {
        EnsureConnected();
        try
        {
            RoundTrip(opLine);
        }
        catch
        {
            // The server may have idle-dropped or restarted this connection: retry the op
            // exactly once on a fresh connection before giving up to the local path.
            Cleanup();
            EnsureConnected();
            RoundTrip(opLine);
        }
        if (!_everUsed)
        {
            _everUsed = true;
            DiagnosticLog.Write("input_routing=broker (SYSTEM input broker active)");
        }
        return true;
    }

    private static void RoundTrip(string opLine)
    {
        // .NET 8's PipeStream.ReadTimeout/WriteTimeout throw InvalidOperationException
        // ("Timeouts are not supported on this stream") on this stream in both sync and
        // async modes (CI run 43532556389), so bound the round-trip with WaitAsync instead;
        // on timeout the abandoned overlapped op is cancelled when the pipe is disposed.
        _writer!.WriteLineAsync(opLine).WaitAsync(TimeSpan.FromSeconds(1)).GetAwaiter().GetResult();
        var reply = _reader!.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(2)).GetAwaiter().GetResult();
        if (reply != "ok") throw new InvalidOperationException(reply ?? "no reply");
    }

    private static void EnsureConnected()
    {
        if (_pipe is { IsConnected: true }) return;
        Cleanup();
        var pipe = new NamedPipeClientStream(".", InputBroker.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous); // async handle: real overlapped IO, cancelled on dispose
        pipe.Connect(1000);
        PipePeer.VerifyServer(pipe); // never send input to an endpoint that is not our own broker
        _pipe = pipe;
        _writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
        _reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
        _hadConnection = true;
    }

    private static void Cleanup()
    {
        try { _reader?.Dispose(); } catch { }
        try { _writer?.Dispose(); } catch { }
        try { _pipe?.Dispose(); } catch { }
        _reader = null; _writer = null; _pipe = null;
    }
}
