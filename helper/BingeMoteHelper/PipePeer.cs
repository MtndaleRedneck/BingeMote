using System.Diagnostics;
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

    private static string ImagePath(uint pid)
    {
        var h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h == IntPtr.Zero) throw new InvalidOperationException("open_process:win32=" + Marshal.GetLastWin32Error());
        try
        {
            var sb = new StringBuilder(1024);
            uint size = (uint)sb.Capacity;
            if (!QueryFullProcessImageName(h, 0, sb, ref size))
                throw new InvalidOperationException("image_query:win32=" + Marshal.GetLastWin32Error());
            return sb.ToString();
        }
        finally { CloseHandle(h); }
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
}
