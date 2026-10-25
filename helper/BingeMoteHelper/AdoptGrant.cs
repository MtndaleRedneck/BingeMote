namespace BingeMoteHelper;

/// <summary>
/// One-shot VLC-password adoption per paired phone (the automatic-first setup): the paired
/// phone may fetch the PC's VLC password exactly once, by request ID. The served marker
/// (request ID + served-at, never the value) is persisted on the phone record at serve
/// time, so a helper restart can never re-arm a grant: the same ID inside the retry window
/// replays (re-reading the effective password, so a PC-side change mid-window is honored),
/// a different ID inside the window conflicts, and a marker past its window spends the
/// grant on the next call. Only the consumed-ack spends it early. The spent marker also
/// persists, so a restart never re-arms anything. The password value is never logged.
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

    private static readonly object Gate = new();

    /// <summary>Serve the one-shot fetch, or replay an in-flight one for the same request ID.</summary>
    public static Result Serve(Program.PairedPhone phone, Func<string> password, string requestId, DateTime nowUtc, Action persist)
    {
        if (string.IsNullOrWhiteSpace(requestId) || requestId.Length > 100) return new Result();
        lock (Gate)
        {
            if (phone.AdoptSpent) return new Result { AlreadySpent = true };
            if (phone.AdoptRequestId != null && phone.AdoptServedAtUtc.HasValue)
            {
                var withinWindow = nowUtc - phone.AdoptServedAtUtc.Value <= RetryWindow;
                if (withinWindow && phone.AdoptRequestId == requestId)
                    return new Result { Served = true, Replayed = true, Password = password() };
                if (withinWindow)
                    return new Result { Conflict = true }; // another fetch attempt must wait for the window
                // Window expired without an ack: the grant is spent.
                phone.AdoptSpent = true;
                phone.AdoptRequestId = null;
                phone.AdoptServedAtUtc = null;
                persist();
                return new Result { AlreadySpent = true };
            }
            var pw = password();
            // Persist the marker BEFORE serving: a restart now replays/spends from the
            // record instead of silently re-arming a fresh grant.
            phone.AdoptRequestId = requestId;
            phone.AdoptServedAtUtc = nowUtc;
            persist();
            return new Result { Served = true, Password = pw };
        }
    }

    /// <summary>The phone confirms it stored the password: the grant is spent for good.</summary>
    public static Result Ack(Program.PairedPhone phone, string requestId, Action persist)
    {
        lock (Gate)
        {
            if (phone.AdoptSpent) return new Result { Acked = true, AlreadySpent = true }; // idempotent ack
            if (phone.AdoptRequestId == null || phone.AdoptRequestId != requestId)
                return new Result(); // nothing to ack
            phone.AdoptSpent = true;
            phone.AdoptRequestId = null;
            phone.AdoptServedAtUtc = null;
            persist();
            return new Result { Acked = true };
        }
    }
}
