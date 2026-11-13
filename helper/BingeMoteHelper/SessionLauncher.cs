using System.Diagnostics;
using System.Runtime.InteropServices;

namespace BingeMoteHelper;

/// <summary>
/// rev11: a Task Scheduler task running as SYSTEM lands in session 0, where injected
/// input can never reach the user's desktop (session 0 isolation). This relaunches
/// the current exe into the ACTIVE CONSOLE session with a SYSTEM token - the
/// psexec -s -i pattern: duplicate our own SYSTEM token, retarget it at the console
/// session, CreateProcessAsUser onto winsta0\default there. Returns true when a
/// relaunch happened (the caller should exit; the session-1 copy does the work).
/// </summary>
internal static class SessionLauncher
{
    [DllImport("kernel32.dll")] private static extern uint WTSGetActiveConsoleSessionId();
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool OpenProcessToken(IntPtr h, uint access, out IntPtr token);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool DuplicateTokenEx(IntPtr existing, uint access, IntPtr attrs, int impersonation, int tokenType, out IntPtr newToken);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool SetTokenInformation(IntPtr token, int infoClass, ref uint info, uint infoLen);
    [DllImport("userenv.dll", SetLastError = true, CharSet = CharSet.Unicode)] private static extern bool CreateEnvironmentBlock(out IntPtr env, IntPtr token, bool inherit);
    [DllImport("userenv.dll")] private static extern bool DestroyEnvironmentBlock(IntPtr env);
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)] private static extern bool CreateProcessAsUserW(IntPtr token, string? app, string cmd, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr env, string? dir, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr h);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct STARTUPINFO
    {
        public int cb; public string? reserved; public string? desktop; public string? title;
        public int x, y, xSize, ySize, xCount, yCount, fill, flags;
        public short show, reserved2; public IntPtr reserved3, stdIn, stdOut, stdErr;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION { public IntPtr process, thread; public int pid, tid; }

    internal static bool RelaunchIntoActiveConsoleSession(string argLine, string logTag)
    {
        var current = (uint)Process.GetCurrentProcess().SessionId;
        var target = WTSGetActiveConsoleSessionId();
        if (target == 0xFFFFFFFF || target == current) return false;

        IntPtr own = IntPtr.Zero, dup = IntPtr.Zero, env = IntPtr.Zero;
        try
        {
            if (!OpenProcessToken(Process.GetCurrentProcess().Handle, 0x02000000 /* MAXIMUM_ALLOWED */, out own))
                return Fail(where: "OpenProcessToken", logTag);
            if (!DuplicateTokenEx(own, 0x02000000, IntPtr.Zero, 2 /* SecurityImpersonation */, 1 /* TokenPrimary */, out dup))
                return Fail("DuplicateTokenEx", logTag);
            var session = target;
            if (!SetTokenInformation(dup, 12 /* TokenSessionId */, ref session, 4))
                return Fail("SetTokenInformation", logTag);
            if (!CreateEnvironmentBlock(out env, dup, false)) env = IntPtr.Zero; // fall back to inheriting ours
            var si = new STARTUPINFO { cb = Marshal.SizeOf<STARTUPINFO>(), desktop = "winsta0\\default" };
            var cmd = "\"" + Environment.ProcessPath + "\" " + argLine;
            if (!CreateProcessAsUserW(dup, null, cmd, IntPtr.Zero, IntPtr.Zero, false,
                    0x00000400 /* CREATE_NO_WINDOW */ | 0x04000000 /* CREATE_UNICODE_ENVIRONMENT */, env, null, ref si, out var pi))
                return Fail("CreateProcessAsUser", logTag);
            CloseHandle(pi.process);
            CloseHandle(pi.thread);
            DiagnosticLog.Write($"{logTag}=relaunched session={current} console={target} pid={pi.pid}");
            return true;
        }
        finally
        {
            if (env != IntPtr.Zero) DestroyEnvironmentBlock(env);
            if (dup != IntPtr.Zero) CloseHandle(dup);
            if (own != IntPtr.Zero) CloseHandle(own);
        }
    }

    private static bool Fail(string where, string logTag)
    {
        DiagnosticLog.Write($"{logTag}=session_relaunch_failed {where} win32={Marshal.GetLastWin32Error()}");
        return false;
    }
}
