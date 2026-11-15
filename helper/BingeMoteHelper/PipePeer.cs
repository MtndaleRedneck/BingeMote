using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace BingeMoteHelper;

/// <summary>
/// rev12: peer identity for the input broker pipe. The pipe DACL alone cannot prove
/// who is on the other end (rev11 review blocker B1/B2), so both ends verify the
/// peer's PROCESS: the broker serves only clients whose image is this same installed
/// exe in this same session, and the helper sends ops only to a server that passes
/// the same check. A renamed copy, a script, or a process from another session all
/// fail. (Signer checks are impossible - the binary is unsigned.)
/// </summary>
internal static class PipePeer
{
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetNamedPipeClientProcessId(SafePipeHandle handle, out uint clientProcessId);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetNamedPipeServerProcessId(SafePipeHandle handle, out uint serverProcessId);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, uint processId);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool QueryFullProcessImageName(IntPtr hProcess, uint flags, StringBuilder exeName, ref uint size);
    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr h);

    private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private const int SystemProcessIdInformationClass = 88; // 0x58

    [DllImport("ntdll.dll")]
    private static extern int NtQuerySystemInformation(int systemInformationClass, ref SystemProcessIdInformation info, int systemInformationLength, out int returnLength);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint QueryDosDevice(string deviceName, StringBuilder targetPath, uint maxChars);

    [StructLayout(LayoutKind.Sequential)]
    private struct UnicodeString
    {
        public ushort Length;
        public ushort MaximumLength;
        public IntPtr Buffer;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SystemProcessIdInformation
    {
        public IntPtr ProcessId;
        public UnicodeString ImageName;
    }

    private static string ImagePath(uint pid)
    {
        var h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h == IntPtr.Zero)
        {
            // rev17: a non-elevated token cannot open a SYSTEM process even for limited
            // query - the process DACL grants Administrators, and a UAC-filtered token
            // carries Administrators deny-only, which reads as a deny (Ezzy's PC, Nov 15
            // 2026: open_process:win32=5 at every input op; the broker looked unavailable
            // and the mouse fell back to the local path that loses to elevated windows).
            // The peer check must work from ANY local token, so fall back to the
            // handle-free query Task Manager itself uses for SYSTEM image paths.
            var openError = Marshal.GetLastWin32Error();
            try { return ImagePathHandleFree(pid); }
            catch (Exception fallbackEx)
            {
                throw new InvalidOperationException("open_process:win32=" + openError + " (handle-free fallback: " + fallbackEx.Message + ")");
            }
        }
        try
        {
            var sb = new StringBuilder(1024);
            uint size = (uint)sb.Capacity;
            if (!QueryFullProcessImageName(h, 0, sb, ref size))
            {
                // The same privilege gap can bite here (handle opened, query denied).
                try { return ImagePathHandleFree(pid); }
                catch { throw new InvalidOperationException("image_query:win32=" + Marshal.GetLastWin32Error()); }
            }
            return sb.ToString();
        }
        finally { CloseHandle(h); }
    }

    /// <summary>rev17: image path of any local process WITHOUT opening it.
    /// NtQuerySystemInformation(SystemProcessIdInformation) needs no handle and no
    /// privilege - it is how Task Manager shows SYSTEM process paths to ordinary users -
    /// so the same-strength image+session peer check works from a non-elevated token.
    /// Returns the path in ordinary drive-letter form (the native query answers in
    /// \Device\... form; converted through the machine's drive mappings).</summary>
    private static string ImagePathHandleFree(uint pid)
    {
        const int chars = 1024;
        var buf = Marshal.AllocHGlobal(chars * 2);
        try
        {
            var info = new SystemProcessIdInformation
            {
                ProcessId = (IntPtr)pid,
                ImageName = new UnicodeString { Length = 0, MaximumLength = (ushort)(chars * 2), Buffer = buf }
            };
            var status = NtQuerySystemInformation(SystemProcessIdInformationClass, ref info, Marshal.SizeOf<SystemProcessIdInformation>(), out _);
            if (status != 0)
                throw new InvalidOperationException("image_query_nt:status=0x" + ((uint)status).ToString("x8"));
            if (info.ImageName.Length == 0)
                throw new InvalidOperationException("image_query_nt:empty");
            var nt = Marshal.PtrToStringUni(info.ImageName.Buffer, info.ImageName.Length / 2)
                     ?? throw new InvalidOperationException("image_query_nt:empty");
            return NtPathToDos(nt);
        }
        finally { Marshal.FreeHGlobal(buf); }
    }

    /// <summary>\Device\HarddiskVolumeN\... to C:\... via QueryDosDevice mappings.</summary>
    private static string NtPathToDos(string ntPath)
    {
        foreach (var drive in Environment.GetLogicalDrives())
        {
            var letter = drive.TrimEnd('\\');
            var sb = new StringBuilder(256);
            if (QueryDosDevice(letter, sb, (uint)sb.Capacity) == 0) continue;
            var target = sb.ToString();
            var nul = target.IndexOf('\0');
            if (nul >= 0) target = target[..nul];
            if (ntPath.StartsWith(target, StringComparison.OrdinalIgnoreCase))
                return letter + ntPath[target.Length..];
        }
        return ntPath; // unconvertible: leave native; the equality check fails loudly
    }

    private static bool PathsEqual(string a, string b)
    {
        try { return string.Equals(Path.GetFullPath(a), Path.GetFullPath(b), StringComparison.OrdinalIgnoreCase); }
        catch { return string.Equals(a, b, StringComparison.OrdinalIgnoreCase); }
    }

    /// <summary>Throws unless process pid runs this same installed exe in this same session.
    /// Returns a short attribution string for the log (never the peer's data).</summary>
    private static string VerifyOwnImage(uint pid)
    {
        using var proc = Process.GetProcessById((int)pid); // throws if the peer already exited
        var image = ImagePath(pid);
        var own = Environment.ProcessPath ?? throw new InvalidOperationException("own path unknown");
        if (!PathsEqual(image, own))
            throw new InvalidOperationException("peer image is not the installed helper");
        if (proc.SessionId != Process.GetCurrentProcess().SessionId)
            throw new InvalidOperationException("peer is in another session");
        return "pid=" + pid + " session=" + proc.SessionId;
    }

    /// <summary>Broker side: the connected client must be the installed helper itself.</summary>
    internal static string VerifyClient(NamedPipeServerStream pipe)
    {
        if (!GetNamedPipeClientProcessId(pipe.SafePipeHandle, out var pid))
            throw new InvalidOperationException("client_pid:win32=" + Marshal.GetLastWin32Error());
        return VerifyOwnImage(pid);
    }

    /// <summary>Helper side: the server behind the pipe must be our own broker (same
    /// installed exe, same session). The broker additionally refuses to serve unless it
    /// runs as SYSTEM, so a user-mode fake of the real exe kills itself.</summary>
    internal static string VerifyServer(NamedPipeClientStream pipe)
    {
        if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out var pid))
            throw new InvalidOperationException("server_pid:win32=" + Marshal.GetLastWin32Error());
        return VerifyOwnImage(pid);
    }

#if BROKER_TEST_HOOK
    /// <summary>CI negative control for the rev17 fix: what the PRE-rev17 handle-based
    /// server check saw from THIS token. Under a restricted (standard-like) token this
    /// must be ERROR_ACCESS_DENIED (5) against the SYSTEM broker, proving the CI token
    /// really matches Ezzy's condition, while the rev17 check still passes. Compiled
    /// into the e2e test build only.</summary>
    internal static (uint Pid, int Win32) TestHookLegacyProbe(NamedPipeClientStream pipe)
    {
        if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out var pid))
            return (0, Marshal.GetLastWin32Error());
        var h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        var err = h == IntPtr.Zero ? Marshal.GetLastWin32Error() : 0;
        if (h != IntPtr.Zero) CloseHandle(h);
        return (pid, err);
    }
#endif
}
