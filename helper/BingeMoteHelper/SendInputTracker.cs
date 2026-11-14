namespace BingeMoteHelper;

/// <summary>Return count determines failure, even with Win32 error zero. Pending
/// failures and recovery are retained until the bounded logger admits their summary.</summary>
internal static class SendInputTracker
{
    private static readonly object Gate = new();
    private static bool _failing, _failureReported, _recoveryPending;
    private static long _failuresSinceLog;
    private static uint _pendingSent, _pendingRequested;
    private static int _pendingError;
    private static DateTime _lastLog = DateTime.MinValue;
    internal static Func<DateTime> Now = () => DateTime.UtcNow;

    internal static void ResetForTest()
    {
        lock (Gate)
        {
            _failing = _failureReported = _recoveryPending = false;
            _failuresSinceLog = 0; _lastLog = DateTime.MinValue; Now = () => DateTime.UtcNow;
        }
    }

    internal static bool Record(uint requested, uint sent, int lastError, Func<string, bool> log)
    {
        var failed = sent < requested;
        lock (Gate)
        {
            if (failed)
            {
                _failuresSinceLog++;
                _pendingSent = sent; _pendingRequested = requested; _pendingError = lastError;
                _failing = true;
                // A newer failure cancels a not-yet-admitted recovery: logging "recovered"
                // after this failure would put the events out of order. That older
                // incident's recovery is subsumed by this failure's summary.
                _recoveryPending = false;
            }
            else if (_failing)
            {
                _recoveryPending = true;
                _failing = false;
            }
            var now = Now();
            // No count/timestamp is cleared unless the corresponding line was admitted.
            if (_failuresSinceLog > 0 && (!_failureReported || _recoveryPending || now - _lastLog >= TimeSpan.FromSeconds(10)))
            {
                if (!log($"SendInput rejected input: sent={_pendingSent} of requested={_pendingRequested}, win32_error={_pendingError}{(_pendingError == 0 ? " (zero does not identify the cause; UIPI blocking is one possibility)" : "")}, failures_since_last_log={_failuresSinceLog}.")) return failed;
                _lastLog = now; _failuresSinceLog = 0; _failureReported = true;
            }
            if (_recoveryPending)
            {
                if (!log("SendInput recovered after a full successful send (pending recovery summary).")) return failed;
                _recoveryPending = false; _failureReported = false;
            }
        }
        return failed;
    }
}
