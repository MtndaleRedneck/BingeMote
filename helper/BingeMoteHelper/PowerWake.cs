using System.Runtime.InteropServices;

namespace BingeMoteHelper;

/// <summary>
/// Movie-session keep-awake: while a video is loaded in VLC (playing or paused) and
/// VLC's window is not minimized, hold the PC itself awake (never the display). A paused
/// movie no longer lets the laptop fall fully asleep and lock. The screen still turns
/// off on its own timer, the hold drops the moment the video stops, VLC closes or is
/// minimized, or the helper exits, and the owner's power settings are never touched.
/// Documented limits: lid close, the power button and Start > Sleep still sleep the
/// laptop, and the screensaver still runs.
/// </summary>
internal static class PowerWake
{
    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint esFlags);

    private const uint ES_CONTINUOUS = 0x80000000;
    private const uint ES_SYSTEM_REQUIRED = 0x00000001;

    private static readonly object Gate = new();
    private static bool _desired;
    private static bool _applied;
    private static SynchronizationContext? _ui;

    /// <summary>Test visibility into the session hold's desired state.</summary>
    internal static bool HoldingForVlc { get { lock (Gate) return _desired; } }

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
                if (!_noUiLogged) { _noUiLogged = true; Program.Log("Keep-awake hold disabled: no UI thread bound."); }
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
            Program.Log("Keep-awake hold " + (want ? "acquire" : "release") +
                " FAILED (SetThreadExecutionState returned 0); confirmed state stays " +
                (_applied ? "HELD" : "NOT HELD") + " and the next sync retries.");
            return;
        }
        lock (Gate) _applied = want;
    }
}
