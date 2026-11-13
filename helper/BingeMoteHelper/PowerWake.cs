using System.Runtime.InteropServices;

namespace BingeMoteHelper;

/// <summary>
/// Windows power state around movie sessions. Two pieces:
/// - Display tracking + WakeDisplay: while the PC's screen is dark, the FIRST tap of any
///   button only brings the screen back - the tap's own action is swallowed (his rule,
///   Nov 3). Display on/off arrives as GUID_CONSOLE_DISPLAY_STATE power notifications on
///   the hidden window; unknown (helper just started) counts as dark, the fail-safe
///   direction, so an uncertain tap can never fire blind.
/// - A session hold: while a video is loaded in VLC (playing or paused) and VLC's window
///   is not minimized, keep the PC itself awake (never the display). A paused movie no
///   longer lets the laptop fall fully asleep and lock. The screen still turns off on its
///   own timer, the hold drops the moment the video stops, VLC closes or is minimized, and
///   the owner's power settings are never touched. Documented limits: lid close, the power
///   button and Start > Sleep still sleep the laptop, and the screensaver still runs.
/// </summary>
internal static class PowerWake
{
    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint esFlags);

    private const uint ES_CONTINUOUS = 0x80000000;
    private const uint ES_SYSTEM_REQUIRED = 0x00000001;
    private const uint ES_DISPLAY_REQUIRED = 0x00000002;

    internal enum MonitorState { Unknown, Off, On, Dimmed }

    private static readonly object Gate = new();
    /// <summary>Clock, replaceable in tests.</summary>
    internal static Func<DateTime> Now = () => DateTime.UtcNow;
    private static DateTime _lastDiscreteSwallow = DateTime.MinValue;

    /// <summary>Tests only: back to the just-started state.</summary>
    internal static void ResetForTest()
    {
        lock (Gate) { _monitor = MonitorState.Unknown; _swallowUntil = DateTime.MinValue; _swallowStart = DateTime.MinValue; _lastDiscreteSwallow = DateTime.MinValue; Now = () => DateTime.UtcNow; }
    }
    private static bool _desired;
    private static bool _applied;
    private static volatile MonitorState _monitor = MonitorState.Unknown;
    private static SynchronizationContext? _ui;

    /// <summary>Test visibility into the session hold's desired state.</summary>
    internal static bool HoldingForVlc { get { lock (Gate) return _desired; } }

    /// <summary>Latest display state from the power notification.</summary>
    internal static MonitorState Monitor => _monitor;

    /// <summary>Dark, or not yet known (fail-safe): the first tap wakes only. Only a real
    /// GUID_CONSOLE_DISPLAY_STATE notification ever marks the display On - WakeDisplay never
    /// does. A wake that failed to turn the screen on therefore stays dark, and the next tap
    /// simply wakes again; no tap's action can slip through on an assumed On.</summary>
    internal static bool DisplayDarkOrUnknown => _monitor != MonitorState.On;

    /// <summary>The window's power-notification callback feeds this - the only source
    /// that may mark the display On.</summary>
    internal static void NoteMonitorState(uint raw)
    {
        var prev = _monitor;
        _monitor = raw switch
        {
            0 => MonitorState.Off,
            1 => MonitorState.On,
            2 => MonitorState.Dimmed,
            _ => MonitorState.Unknown,
        };
        if (prev != _monitor) { try { DiagnosticLog.Write($"Display state notification: {prev} -> {_monitor} (raw={raw})."); } catch { } }
    }

    /// <summary>Bring the screen back if its idle timer shut it off. One-shot only:
    /// without ES_CONTINUOUS the call resets the idle timers and leaves the session
    /// hold below untouched, so the screen can still turn off again normally.</summary>
    public static void WakeDisplay()
    {
        // One-shot timer reset only. The display state itself stays whatever the power
        // notifications last reported - if the screen does not actually come back, this
        // changes nothing and the next tap tries again.
        try { SetThreadExecutionState(ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED); } catch { }
    }

    // A tap or gesture that lands while the screen is dark is consumed whole - the first
    // touch only wakes. Continuous input (touchpad / scroll) has no explicit gesture end
    // in the protocol, so the swallow extends with each swallowed frame: a drag is eaten
    // to its end, and only 400ms of quiet hands control back. Discrete presses are gated
    // on the dark check alone, so the next press after the wake works normally.
    private static DateTime _swallowUntil = DateTime.MinValue;
    private static DateTime _swallowStart = DateTime.MinValue;

    /// <summary>Touchpad moves, scrolls and zooms. True means the frame was consumed
    /// (screen woken if it was dark) and must not be injected.</summary>
    public static bool ShouldSwallowContinuous()
    {
        lock (Gate)
        {
            // UNTESTED fix (H4): only a KNOWN dark screen swallows pointer motion. An
            // "unknown" display state (no notification received yet) used to swallow every
            // move forever, with the swallow window renewing itself on each frame. Moving
            // the pointer is harmless, so unknown no longer blocks it; clicks and keys
            // still treat unknown as dark.
            var now = Now();
            // A gesture is swallowed only while the screen is KNOWN off, or while finishing
            // a swallowed gesture, and in every case for at most 3 s from its first frame.
            // After the cap, moves pass even if the display still reads Off: moving the
            // pointer is harmless, and an indefinite swallow is the failure being chased.
            if (_swallowStart == DateTime.MinValue || now > _swallowUntil) _swallowStart = DateTime.MinValue; // gesture over
            var inGesture = _swallowStart != DateTime.MinValue;
            if (_monitor == MonitorState.Off || inGesture)
            {
                if (_swallowStart == DateTime.MinValue) _swallowStart = now;
                if (now - _swallowStart < TimeSpan.FromSeconds(3))
                {
                    if (_monitor == MonitorState.Off) WakeDisplay();
                    _swallowUntil = now.AddMilliseconds(400);
                    return true;
                }
                _swallowUntil = now.AddMilliseconds(400); // keep the cap window until input goes quiet
                return false;
            }
            return false;
        }
    }

    /// <summary>Clicks, keys and volume. True means the press was consumed (screen woken)
    /// and must not be injected. UNTESTED change: while the display is not known to be On
    /// (off, dimmed or unknown) a press is swallowed at most once per 10 s. The next press
    /// passes, so a missing display notification can no longer block clicks, keys and
    /// volume indefinitely. A known-On screen never swallows.</summary>
    public static bool ShouldSwallowDiscrete()
    {
        lock (Gate)
        {
            if (!DisplayDarkOrUnknown) return false;
            var now = Now();
            if (now - _lastDiscreteSwallow < TimeSpan.FromSeconds(10)) return false;
            _lastDiscreteSwallow = now;
            WakeDisplay();
            // UNTESTED diagnostic (rev6): moves already log their swallows; clicks, keys and
            // volume did not, so a swallowed press left no trace. Bounded by the 10s cap above.
            try { DiagnosticLog.Write($"Wake filter swallowed a discrete press (display={_monitor}): the press only wakes, the next press within 10s acts."); } catch { }
            return true;
        }
    }

    /// <summary>The hidden window's thread owns every ES_CONTINUOUS transition. Windows
    /// execution state is per-thread, so a hold set on one thread cannot be released from
    /// another - calling this from the listener's pool threads marshals to the UI thread.
    /// Called once from the window's constructor.</summary>
    public static void BindUi(SynchronizationContext ui) { lock (Gate) _ui = ui; }

    /// <summary>Sync the session hold to the keep-awake condition.</summary>
    public static void SyncToVlc(bool vlcPlayingPausedVisible)
    {
        lock (Gate) _desired = vlcPlayingPausedVisible;
        ApplyHold();
    }

    /// <summary>Drop the hold outright (real exit), so the normal sleep timer applies.</summary>
    public static void Release()
    {
        lock (Gate) _desired = false;
        ApplyHold();
    }

    private static bool _noUiLogged;

    private static void ApplyHold()
    {
        SynchronizationContext? ui;
        lock (Gate) ui = _ui;
        if (ui == null)
        {
            // No owning thread: the hold never engages (fail-safe). Applying here would
            // set per-thread state on a pool thread that can never release it reliably.
            lock (Gate)
            {
                if (!_noUiLogged) { _noUiLogged = true; DiagnosticLog.Write("Keep-awake hold disabled: no UI thread bound."); }
            }
            return;
        }
        if (SynchronizationContext.Current != ui)
        {
            ui.Post(_ => ApplyHoldCore(), null);
            return;
        }
        ApplyHoldCore();
    }

    private static void ApplyHoldCore()
    {
        bool want;
        lock (Gate) want = _desired;
        // _applied is the last state the API CONFIRMED, never an assumption: on a failed
        // call it stays put, so the next 10s sync tick retries (acquire AND release) -
        // a failed release must not pretend the hold is gone while Windows still holds it.
        if (want == _applied) return;
        uint prev;
        // SYSTEM only, never DISPLAY: the screen may turn off, the PC may not sleep.
        try { prev = SetThreadExecutionState(want ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED : ES_CONTINUOUS); }
        catch { prev = 0; }
        if (prev == 0)
        {
            DiagnosticLog.Write("Keep-awake hold " + (want ? "acquire" : "release") +
                " FAILED (SetThreadExecutionState returned 0); confirmed state stays " +
                (_applied ? "HELD" : "NOT HELD") + " and the next sync retries.");
            return;
        }
        lock (Gate) _applied = want;
    }
}
