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
    private static bool _desired;
    private static bool _applied;
    private static volatile MonitorState _monitor = MonitorState.Unknown;
    private static SynchronizationContext? _ui;

    /// <summary>Test visibility into the session hold's desired state.</summary>
    internal static bool HoldingForVlc { get { lock (Gate) return _desired; } }

    /// <summary>Latest display state from the power notification.</summary>
    internal static MonitorState Monitor => _monitor;

    /// <summary>Dark, or not yet known (fail-safe): the first tap wakes only.</summary>
    internal static bool DisplayDarkOrUnknown
    {
        get
        {
            if (_monitor != MonitorState.On) return true;
            lock (Gate)
            {
                // WakeDisplay reported success optimistically. If no power notification
                // confirmed the screen is really on within 2.5s, fall back to dark - the
                // next tap then wakes again, which is the safe direction.
                if (_wakePending && DateTime.UtcNow - _wakeUtc > TimeSpan.FromMilliseconds(2500)) return true;
            }
            return false;
        }
    }

    private static bool _wakePending;
    private static DateTime _wakeUtc;

    /// <summary>The window's power-notification callback feeds this.</summary>
    internal static void NoteMonitorState(uint raw)
    {
        _monitor = raw switch
        {
            0 => MonitorState.Off,
            1 => MonitorState.On,
            2 => MonitorState.Dimmed,
            _ => MonitorState.Unknown,
        };
        lock (Gate) _wakePending = false; // a real state change confirms the display
    }

    /// <summary>Bring the screen back if its idle timer shut it off. One-shot only:
    /// without ES_CONTINUOUS the call resets the idle timers and leaves the session
    /// hold below untouched, so the screen can still turn off again normally.</summary>
    public static void WakeDisplay()
    {
        try { SetThreadExecutionState(ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED); } catch { }
        // The reset turns the display on; the next power notification corrects us if not.
        _monitor = MonitorState.On;
        lock (Gate) { _wakePending = true; _wakeUtc = DateTime.UtcNow; }
    }

    // A tap or gesture that lands while the screen is dark is consumed whole - the first
    // touch only wakes. Continuous input (touchpad / scroll) has no explicit gesture end
    // in the protocol, so the swallow extends with each swallowed frame: a drag is eaten
    // to its end, and only 400ms of quiet hands control back. Discrete presses are gated
    // on the dark check alone, so the next press after the wake works normally.
    private static DateTime _swallowUntil = DateTime.MinValue;

    /// <summary>Touchpad moves, scrolls and zooms. True means the frame was consumed
    /// (screen woken if it was dark) and must not be injected.</summary>
    public static bool ShouldSwallowContinuous()
    {
        lock (Gate)
        {
            if (DisplayDarkOrUnknown)
            {
                WakeDisplay();
                _swallowUntil = DateTime.UtcNow.AddMilliseconds(400);
                return true;
            }
            if (DateTime.UtcNow < _swallowUntil)
            {
                _swallowUntil = DateTime.UtcNow.AddMilliseconds(400);
                return true;
            }
            return false;
        }
    }

    /// <summary>Clicks, keys and volume. True means the press was consumed (screen woken)
    /// and must not be injected.</summary>
    public static bool ShouldSwallowDiscrete()
    {
        lock (Gate)
        {
            if (!DisplayDarkOrUnknown) return false;
            WakeDisplay();
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

    private static void ApplyHold()
    {
        SynchronizationContext? ui;
        lock (Gate) ui = _ui;
        if (ui != null && SynchronizationContext.Current != ui)
        {
            ui.Post(_ => ApplyHoldCore(), null);
            return;
        }
        ApplyHoldCore();
    }

    private static void ApplyHoldCore()
    {
        lock (Gate)
        {
            if (_desired == _applied) return;
            // SYSTEM only, never DISPLAY: the screen may turn off, the PC may not sleep.
            try { SetThreadExecutionState(_desired ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED : ES_CONTINUOUS); } catch { }
            _applied = _desired;
        }
    }
}
