namespace BingeMoteHelper;

/// <summary>
/// One-shot VLC-password adoption per paired phone (the automatic-first setup): the paired
/// phone may fetch the PC's VLC password exactly once, by request ID. The same ID is served
/// again within the retry window, so a lost response never bricks the phone; only the
/// consumed-ack or the window expiring spends the grant. Check-and-set is atomic per token.
/// The spent marker persists on the phone record (config.json), so a restart never re-arms
/// anything. The password value is never logged.
/// </summary>
internal static class AdoptGrant
{
    public static readonly TimeSpan RetryWindow = TimeSpan.FromMinutes(5);

    internal sealed class Result
    {
        public bool Served;        // 200 with the password
        public bool Replayed;      // same request ID served again inside the window
        public bool Conflict;      // a different request ID while one is still in flight
        public bool AlreadySpent;  // 410
        public bool Acked;         // consumed-ack accepted
        public string? Password;   // set when Served/Replayed
    }

    private sealed class InFlight
    {
        public string RequestId = "";
        public string Password = "";
        public DateTime ServedAtUtc;
    }

    private static readonly object Gate = new();
    private static readonly Dictionary<string, InFlight> _inFlight = new(); // keyed by phone token

    /// <summary>Serve the one-shot fetch, or replay an in-flight one for the same request ID.</summary>
    public static Result Serve(Program.PairedPhone phone, Func<string> password, string requestId, DateTime nowUtc, Action persist)
    {
        if (string.IsNullOrWhiteSpace(requestId) || requestId.Length > 100) return new Result();
        lock (Gate)
        {
            if (phone.AdoptSpent) return new Result { AlreadySpent = true };
            if (_inFlight.TryGetValue(phone.Token, out var f))
            {
                var withinWindow = nowUtc - f.ServedAtUtc <= RetryWindow;
                if (withinWindow && f.RequestId == requestId)
                    return new Result { Served = true, Replayed = true, Password = f.Password };
                if (withinWindow)
                    return new Result { Conflict = true }; // another fetch attempt must wait for the window
                // Window expired without an ack: the grant is spent.
                phone.AdoptSpent = true;
                persist();
                _inFlight.Remove(phone.Token);
                return new Result { AlreadySpent = true };
            }
            var pw = password();
            _inFlight[phone.Token] = new InFlight { RequestId = requestId, Password = pw, ServedAtUtc = nowUtc };
            return new Result { Served = true, Password = pw };
        }
    }

    /// <summary>The phone confirms it stored the password: the grant is spent for good.</summary>
    public static Result Ack(Program.PairedPhone phone, string requestId, Action persist)
    {
        lock (Gate)
        {
            if (phone.AdoptSpent) return new Result { Acked = true, AlreadySpent = true }; // idempotent ack
            if (!_inFlight.TryGetValue(phone.Token, out var f) || f.RequestId != requestId)
                return new Result(); // nothing to ack
            phone.AdoptSpent = true;
            persist();
            _inFlight.Remove(phone.Token);
            return new Result { Acked = true };
        }
    }

    /// <summary>Unit tests only: drops the in-memory in-flight state (a "restart").</summary>
    internal static void ResetForTests()
    {
        lock (Gate) _inFlight.Clear();
    }
}
