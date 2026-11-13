namespace BingeMoteHelper;

/// <summary>
/// Decides whether a SendInput call failed. Failure is sent &lt; requested,
/// whatever GetLastError says: Windows can reject injected input (UIPI, an elevated
/// foreground app) and leave the error at 0. Logs the failure with the error value even
/// if zero, then "recovered" only after a later call sends everything.
/// </summary>
internal static class SendInputTracker
{
    private static readonly object Gate = new();
    private static bool _failing;
    private static long _failuresSinceLog;
    private static DateTime _lastLog = DateTime.MinValue;
    internal static Func<DateTime> Now = () => DateTime.UtcNow;

    internal static void ResetForTest() { lock (Gate) { _failing = false; _failuresSinceLog = 0; _lastLog = DateTime.MinValue; Now = () => DateTime.UtcNow; } }

    /// <summary>Returns true when the call failed (sent &lt; requested).</summary>
    internal static bool Record(uint requested, uint sent, int lastError, Action<string> log)
    {
        var failed = sent < requested;
        lock (Gate)
        {
            if (failed)
            {
                _failuresSinceLog++;
                var now = Now();
                // First failure of a run logs at once; a continuing run logs at most every 10 s.
                if (!_failing || now - _lastLog >= TimeSpan.FromSeconds(10))
                {
                    log($"SendInput rejected input: sent={sent} of requested={requested}, win32_error={lastError}{(lastError == 0 ? " (zero does not identify the cause; UIPI blocking is one possibility)" : "")}, failures_since_last_log={_failuresSinceLog}.");
                    _lastLog = now; _failuresSinceLog = 0;
                }
                _failing = true;
            }
            else if (_failing)
            {
                log($"SendInput recovered: sent={sent} of requested={requested}.");
                _failing = false; _failuresSinceLog = 0;
            }
        }
        return failed;
    }
}
