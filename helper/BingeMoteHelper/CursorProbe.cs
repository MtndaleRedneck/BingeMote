using System.Diagnostics;
using System.Runtime.InteropServices;

namespace BingeMoteHelper;

/// <summary>
/// UNTESTED. Local-only pointer diagnostics for the "mouse does nothing" report.
/// Reads whether the pointer moved, whether Windows is showing it, whether a mouse is
/// attached, and whether the foreground window is elevated. Booleans and counts only:
/// no window titles, no process names, nothing uploaded.
/// </summary>
internal static class CursorProbe
{
    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int x, y; }
    [StructLayout(LayoutKind.Sequential)] private struct CURSORINFO { public int cbSize, flags; public IntPtr hCursor; public POINT pt; }
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool GetCursorInfo(ref CURSORINFO ci);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int idx);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr h);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool OpenProcessToken(IntPtr proc, uint access, out IntPtr tok);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool GetTokenInformation(IntPtr tok, int cls, out int info, int len, out int ret);

    private const int CURSOR_SHOWING = 1, CURSOR_SUPPRESSED = 2, SM_MOUSEPRESENT = 19;

    /// <summary>Null when the position could not be read (never a fake coordinate).</summary>
    public static (int x, int y)? Pos() => GetCursorPos(out var p) ? (p.x, p.y) : null;

    /// <summary>moved, not_moved, or unknown (either read failed).</summary>
    public static string Moved((int x, int y)? before, (int x, int y)? after) =>
        before is null || after is null ? "unknown" : (before.Value != after.Value ? "moved" : "not_moved");

    /// <summary>Cursor API result: "showing", "hidden", "suppressed", or "api_failed:win32=N".</summary>
    public static string Visibility()
    {
        var ci = new CURSORINFO { cbSize = Marshal.SizeOf<CURSORINFO>() };
        if (!GetCursorInfo(ref ci)) return "api_failed:win32=" + Marshal.GetLastWin32Error();
        if ((ci.flags & CURSOR_SUPPRESSED) != 0) return "suppressed";
        return (ci.flags & CURSOR_SHOWING) != 0 ? "showing" : "hidden";
    }

    public static bool MousePresent() => GetSystemMetrics(SM_MOUSEPRESENT) != 0;

    /// <summary>Elevation of the foreground window's process, reported on its own:
    /// "elevated", "not_elevated", or "unreadable:<step>:win32=N" (never guessed either way).</summary>
    public static string ForegroundElevation()
    {
        IntPtr proc = IntPtr.Zero, tok = IntPtr.Zero;
        try
        {
            var fg = GetForegroundWindow();
            if (fg == IntPtr.Zero) return "unreadable:no_foreground_window";
            GetWindowThreadProcessId(fg, out var pid);
            if (pid == 0) return "unreadable:no_pid";
            proc = OpenProcess(0x1000 /*QUERY_LIMITED_INFORMATION*/, false, pid);
            if (proc == IntPtr.Zero) return "unreadable:open_process:win32=" + Marshal.GetLastWin32Error();
            if (!OpenProcessToken(proc, 0x0008 /*TOKEN_QUERY*/, out tok)) return "unreadable:open_token:win32=" + Marshal.GetLastWin32Error();
            if (!GetTokenInformation(tok, 20 /*TokenElevation*/, out var el, 4, out _)) return "unreadable:token_info:win32=" + Marshal.GetLastWin32Error();
            return el != 0 ? "elevated" : "not_elevated";
        }
        catch (Exception e) { return "unreadable:exception:" + e.GetType().Name; }
        finally { if (tok != IntPtr.Zero) CloseHandle(tok); if (proc != IntPtr.Zero) CloseHandle(proc); }
    }
}
