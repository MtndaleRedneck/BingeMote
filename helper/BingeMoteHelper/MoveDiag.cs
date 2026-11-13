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
    // DEBUG-only per the agreed scope: the RELEASE helper carries no active timing
    // logging. Flip to true only in a diagnostic build; the compiler removes the
    // call-site bodies when false.
    private const bool Enabled = true; // UNTESTED: on for the mouse-does-nothing investigation; local log only

    private static readonly object Gate = new();
    private static long _ws, _http;
    private static DateTime _lastArrival;
    private static long _gapMinMs = long.MaxValue, _gapMaxMs, _gapSumMs, _gapCount;
    private static DateTime _windowStart = DateTime.UtcNow;
    private static bool _hadActivity;
    private static long _swallowed;
    private static (int x, int y)? _posAtWindowStart = CursorProbe.Pos();

    /// <summary>A move frame arrived but the wake filter ate it.</summary>
    public static void RecordSwallowed() { if (!Enabled) return; lock (Gate) { _swallowed++; _hadActivity = true; } }

    private static long _inputCount;
    private static DateTime _sampleAt = DateTime.MinValue;
    private static readonly object LogGate = new();
    private static bool _showTriedThisEpisode;
    [System.Runtime.InteropServices.DllImport("user32.dll")] private static extern int ShowCursor(bool show);

    // The cursor/elevation probes are kernel round-trips; their values only change when the
    // foreground window or cursor state changes (seconds, not frames), so refresh them at most
    // every 250ms instead of on every move frame in the stream. (rev6)
    private static DateTime _probeAt = DateTime.MinValue;
    private static string _visCache = "unknown", _elevCache = "unknown";
    private static bool _presentCache;

    /// <summary>Local pointer probes. All output uses the shared bounded asynchronous
    /// diagnostic writer: state changes cannot bypass its 60-lines/minute limit.</summary>
    public static void AfterInput((int x, int y)? before, bool swallowed, string via)
    {
        try { AfterInputCore(before, swallowed, via); }
        catch { /* optional diagnostics must not affect input */ }
    }

    private static void AfterInputCore((int x, int y)? before, bool swallowed, string via)
    {
        if (!Enabled) return;
        var n = System.Threading.Interlocked.Increment(ref _inputCount);
        var now = DateTime.UtcNow;
        lock (LogGate)
        {
            if (now - _sampleAt < TimeSpan.FromSeconds(5)) return;
            _sampleAt = now;
        }
        var after = CursorProbe.Pos(); // cheap, and the actual motion detector: read every frame
        var moved = CursorProbe.Moved(before, after);
        var extra = "";
        string vis; bool present; string elev;
        lock (LogGate)
        {
            if (now - _probeAt > TimeSpan.FromMilliseconds(250))
            {
                _probeAt = now;
                _visCache = CursorProbe.Visibility();
                _presentCache = CursorProbe.MousePresent();
                _elevCache = CursorProbe.ForegroundElevation();
            }
            vis = _visCache; present = _presentCache; elev = _elevCache;
            // CANDIDATE (not proven): one real ShowCursor(true) per hidden episode. It is
            // per-thread, so it may change nothing; the log says whether visibility changed.
            if (vis != "hidden") _showTriedThisEpisode = false;
            else if (!swallowed && !_showTriedThisEpisode)
            {
                _showTriedThisEpisode = true;
                var counter = ShowCursor(true);
                var vis2 = CursorProbe.Visibility();
                extra = $" showcursor_candidate: counter={counter} visibility_before={vis} visibility_after={vis2} changed_anything={vis2 != vis}";
            }

        }
        var line = $"Mouse input #{n} via={via} swallowed={swallowed} cursor_moved={moved} cursor_api={vis} mouse_present={present} foreground_elevation={elev} display={PowerWake.Monitor}{extra}";
        if (elev == "elevated") line += " NOTE: a higher-privilege app is in front; Windows may ignore input from BingeMote. BingeMote does not elevate itself.";
        DiagnosticLog.Sample(line);
    }

    public static void RecordMove(bool viaLiveSocket)
    {
        try { RecordMoveCore(viaLiveSocket); } catch { }
    }

    private static void RecordMoveCore(bool viaLiveSocket)
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
        try { lock (Gate) if (_hadActivity) Flush(DateTime.UtcNow); } catch { }
    }

    private static void Flush(DateTime now)
    {
        var avg = _gapCount > 0 ? _gapSumMs / _gapCount : 0;
        var pos = CursorProbe.Pos();
        var moved = CursorProbe.Moved(_posAtWindowStart, pos); _posAtWindowStart = pos;
        DiagnosticLog.Sample($"Mouse diag: ws_received={_ws} http_received={_http} swallowed_by_wake_filter={_swallowed} cursor_moved={moved} cursor_api={CursorProbe.Visibility()} mouse_present={CursorProbe.MousePresent()} foreground_elevation={CursorProbe.ForegroundElevation()} display={PowerWake.Monitor}; " +
            (_gapCount > 0 ? $"arrival gaps min/avg/max {_gapMinMs}/{avg}/{_gapMaxMs} ms." : "single frame."));
        _ws = _http = _swallowed = 0; _gapMinMs = long.MaxValue; _gapMaxMs = 0; _gapSumMs = _gapCount = 0;
        _windowStart = now; _hadActivity = false;
    }
}
