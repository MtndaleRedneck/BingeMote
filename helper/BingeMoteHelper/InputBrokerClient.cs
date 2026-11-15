using System.IO.Pipes;
using System.Text;

namespace BingeMoteHelper;

/// <summary>
/// rev11: helper-side half of the SYSTEM input broker (see InputBroker). Each input op
/// tries the pipe first; when the broker is UNAVAILABLE the caller falls back to the
/// original local SendInput/SetCursorPos path, so old installs without the broker behave
/// exactly as before.
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
///
/// rev16 (stress-run findings F1/F2/F18):
/// - Tri-state result. An explicit broker answer is authoritative: a REJECTED op is
///   never replayed on the local path. rev15 replayed rejected ops locally, so one
///   Infinity move the privileged validator threw out still poisoned the local
///   fractional-motion state for every later move.
/// - Every op carries a "#<session>.<id>" frame and the lost-reply retry resends the
///   SAME framed line; the broker dedups (session, id) it has already accepted. rev15
///   resent the bare op, so a dropped "ok" reply double-applied the move.
/// - A consecutive-failure circuit breaker: after 3 transport failures in a row the
///   broker is backed off for 30s, so a wedged broker costs one bounded stall instead
///   of a multi-second hang on every op.
/// </summary>
internal static class InputBrokerClient
{
    /// <summary>Played = the broker accepted and played the op. Rejected = the broker
    /// REFUSED it: the answer is authoritative, never replay it locally. Unavailable =
    /// no broker answer at all: the caller may play it locally.</summary>
    internal enum BrokerResult { Played, Rejected, Unavailable }

    /// <summary>The broker answered "err ...": the op was refused, not lost.</summary>
    private sealed class BrokerRejectedException : Exception
    {
        internal BrokerRejectedException(string reply) : base(reply) { }
    }

    private static readonly object Gate = new();
    private static DateTime _retryAfter = DateTime.MinValue;
    private static bool _everUsed, _everFellBack, _hadConnection;
    private static int _consecFailures;

    // rev16 F2: per-process op identity for the broker's resend dedup. The session id is
    // random per helper process; the op id increments per NEW op, so a retry resends the
    // exact same framed line and the broker recognizes it.
    private static readonly string Session = Guid.NewGuid().ToString("N")[..8];
    private static long _nextId;

    private static NamedPipeClientStream? _pipe;
    private static StreamReader? _reader;
    private static StreamWriter? _writer;

    internal static BrokerResult TryRun(string opLine)
    {
        if (!InputSender.BrokerRoutingEnabled) return BrokerResult.Unavailable;
        lock (Gate)
        {
            if (DateTime.UtcNow < _retryAfter) return BrokerResult.Unavailable;
            var framed = "#" + Session + "." + System.Threading.Interlocked.Increment(ref _nextId).ToString("x8") + " " + opLine;
            try
            {
                TryRunLocked(framed);
                _consecFailures = 0;
                return BrokerResult.Played;
            }
            catch (BrokerRejectedException ex)
            {
                // The broker is alive and REFUSED the op. Replaying it locally can apply
                // an op the privileged validator threw out (rev15: a rejected Infinity
                // move poisoned the local move state forever after). Drop it instead.
                _consecFailures = 0;
                DiagnosticLog.Write($"input_broker REJECTED op, not replayed locally ({ex.Message})");
                return BrokerResult.Rejected;
            }
            catch (Exception ex)
            {
                _consecFailures++;
                // A once-good connection that broke gets a quick retry; a broker that
                // never answered backs off for 30s; 3+ consecutive failures trip the
                // breaker for 30s so a wedged broker is one bounded stall per 30s,
                // not a per-op hang.
                var backoffSeconds = _consecFailures >= 3 ? 30 : (_hadConnection ? 1 : 30);
                _retryAfter = DateTime.UtcNow.AddSeconds(backoffSeconds);
                Cleanup();
                if (!_everFellBack)
                {
                    _everFellBack = true;
                    DiagnosticLog.Write("input_routing=local (input broker unavailable, using local SendInput)");
                }
                // Always recorded (fault budget): the first failure needs its reason.
                DiagnosticLog.Write($"input_broker_client error {ex.GetType().Name}: {ex.Message}");
                return BrokerResult.Unavailable;
            }
        }
    }

    private static void TryRunLocked(string framedLine)
    {
        EnsureConnected();
        try
        {
            RoundTrip(framedLine);
        }
        catch (BrokerRejectedException)
        {
            throw; // an explicit refusal is never retried and never replayed locally
        }
        catch
        {
            // The server may have idle-dropped or restarted this connection: retry the op
            // exactly once on a fresh connection before giving up to the local path. The
            // SAME framed line goes out again; the broker dedups it when the first try
            // landed and only its reply was lost.
            Cleanup();
            EnsureConnected();
            RoundTrip(framedLine);
        }
        if (!_everUsed)
        {
            _everUsed = true;
            DiagnosticLog.Write("input_routing=broker (SYSTEM input broker active)");
        }
    }

    private static void RoundTrip(string framedLine)
    {
        // .NET 8's PipeStream.ReadTimeout/WriteTimeout throw InvalidOperationException
        // ("Timeouts are not supported on this stream") on this stream in both sync and
        // async modes (CI run 43532556389), so bound the round-trip with WaitAsync instead;
        // on timeout the abandoned overlapped op is cancelled when the pipe is disposed.
        _writer!.WriteLineAsync(framedLine).WaitAsync(TimeSpan.FromSeconds(1)).GetAwaiter().GetResult();
        // 5s ack window (review P2): a local broker answers in milliseconds or it is
        // wedged. A shorter window turns a slow-but-LIVE broker into a double apply -
        // the broker played the op, its late "ok" went unread, and the local fallback
        // played it again (rev15: HELLO typed 3x, dx100 moved 300). The window absorbs
        // a delayed ack so it is consumed; a truly wedged broker still fails here, and
        // the circuit breaker bounds what repeated wedges cost.
        var reply = _reader!.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
        if (reply == "ok") return;
        if (reply != null && reply.StartsWith("err", StringComparison.Ordinal))
            throw new BrokerRejectedException(reply);
        throw new InvalidOperationException(reply ?? "no reply");
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
