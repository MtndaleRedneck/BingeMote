namespace BingeMoteHelper;

/// <summary>
/// Local-only mouse transport diagnostics: how move frames arrive (live socket vs
/// plain HTTP) and how even the gaps between them are. Aggregated in memory and
/// written as ONE compact line a minute to the existing local helper.log - never
/// uploaded, never shown in the UI, no endpoint. Exists to tell, after the
/// TCP_NODELAY/pace changes, whether movement still arrives in bunches on a real
/// LAN. Flip Enabled to false to retire it without touching call sites.
/// </summary>
internal static class MoveDiag
{
    private const bool Enabled = true;

    private static readonly object Gate = new();
    private static long _ws, _http;
    private static DateTime _lastArrival;
    private static long _gapMinMs = long.MaxValue, _gapMaxMs, _gapSumMs, _gapCount;
    private static DateTime _windowStart = DateTime.UtcNow;
    private static bool _hadActivity;

    public static void RecordMove(bool viaLiveSocket)
    {
        if (!Enabled) return;
        lock (Gate)
        {
            var now = DateTime.UtcNow;
            if (viaLiveSocket) _ws++; else _http++;
            if (_lastArrival != default)
            {
                var gap = (long)(now - _lastArrival).TotalMilliseconds;
                if (gap < _gapMinMs) _gapMinMs = gap;
                if (gap > _gapMaxMs) _gapMaxMs = gap;
                _gapSumMs += gap; _gapCount++;
            }
            _lastArrival = now;
            _hadActivity = true;
            if ((now - _windowStart).TotalSeconds >= 60) Flush(now);
        }
    }

    /// <summary>Call when the live connection closes, so the tail window isn't lost.</summary>
    public static void FlushIfIdle()
    {
        if (!Enabled) return;
        lock (Gate) if (_hadActivity) Flush(DateTime.UtcNow);
    }

    private static void Flush(DateTime now)
    {
        var avg = _gapCount > 0 ? _gapSumMs / _gapCount : 0;
        Program.Log($"Mouse diag: {_ws + _http} moves (ws={_ws} http={_http}); " +
            (_gapCount > 0 ? $"arrival gaps min/avg/max {_gapMinMs}/{avg}/{_gapMaxMs} ms." : "single frame."));
        _ws = _http = 0; _gapMinMs = long.MaxValue; _gapMaxMs = 0; _gapSumMs = _gapCount = 0;
        _windowStart = now; _hadActivity = false;
    }
}
