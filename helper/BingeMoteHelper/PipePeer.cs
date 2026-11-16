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

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetLongPathName(string lpszShortPath, StringBuilder lpszLongPath, uint cchBuffer);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetFileInformationByHandle(Microsoft.Win32.SafeHandles.SafeFileHandle h, out ByHandleFileInformation info);

    [StructLayout(LayoutKind.Sequential)]
    private struct ByHandleFileInformation
    {
        public uint FileAttributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME CreationTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastAccessTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWriteTime;
        public uint VolumeSerialNumber;
        public uint FileSizeHigh;
        public uint FileSizeLow;
        public uint NumberOfLinks;
        public uint FileIndexHigh;
        public uint FileIndexLow;
    }

    private const int ERROR_ACCESS_DENIED = 5;
    private const int ERROR_INSUFFICIENT_BUFFER = 122;

#if BROKER_TEST_HOOK
    /// <summary>CI-only: how the last peer verification identified the peer's image
    /// (openprocess vs handlefree:win32=N) and how it compared (file-identity vs
    /// path-compare). Endpoint-bound proof for the e2e: the filtered-token op must
    /// show the fallback branch on the SAME connection TryRun used. Compiled out of
    /// the shipping exe.</summary>
    internal static volatile string? LastVerifyTrace;
#endif

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

    /// <summary>The peer's image path, and how it was read: viaHandleFree is true when
    /// the handle-free query answered because OpenProcess was denied. openWin32 is the
    /// original OpenProcess error (0 when OpenProcess succeeded) - preserved so a
    /// rejection never loses the reason.</summary>
    private static string ImagePath(uint pid, out bool viaHandleFree, out int openWin32)
    {
        viaHandleFree = false;
        openWin32 = 0;
        var h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h == IntPtr.Zero)
        {
            // rev17: a non-elevated token cannot open a SYSTEM process even for limited
            // query - the process DACL grants Administrators, and a UAC-filtered token
            // carries Administrators deny-only, which reads as a deny (Ezzy's PC, Nov 15
            // 2026: open_process:win32=5 at every input op; the broker looked unavailable
            // and the mouse fell back to the local path that loses to elevated windows).
            // The peer check must work from ANY local token, so fall back to the
            // handle-free image-path query (NtQuerySystemInformation), which needs
            // no process handle and no extra privilege.
            // rev18 (Instinct 2 review): fall back ONLY on access-denied (5) - other
            // errors (no such process, invalid handle) must not take the weaker-read
            // path, and the original error is never dropped.
            openWin32 = Marshal.GetLastWin32Error();
            if (openWin32 != ERROR_ACCESS_DENIED)
                throw new InvalidOperationException("open_process:win32=" + openWin32);
            viaHandleFree = true;
            return ImagePathHandleFree(pid);
        }
        try
        {
            // rev18: grow the buffer instead of failing on a long path.
            var capacity = 1024;
            for (var attempt = 0; attempt < 3; attempt++)
            {
                var sb = new StringBuilder(capacity);
                uint size = (uint)sb.Capacity;
                if (QueryFullProcessImageName(h, 0, sb, ref size)) return sb.ToString();
                var err = Marshal.GetLastWin32Error();
                if (err == ERROR_INSUFFICIENT_BUFFER) { capacity *= 8; continue; }
                if (err == ERROR_ACCESS_DENIED)
                {
                    // The same privilege gap can bite here (handle opened, query denied).
                    openWin32 = err;
                    viaHandleFree = true;
                    return ImagePathHandleFree(pid);
                }
                throw new InvalidOperationException("image_query:win32=" + err);
            }
            throw new InvalidOperationException("image_query:win32=" + ERROR_INSUFFICIENT_BUFFER);
        }
        finally { CloseHandle(h); }
    }

    /// <summary>rev17: image path of any local process WITHOUT opening it.
    /// NtQuerySystemInformation(SystemProcessIdInformation) needs no handle and no
    /// privilege, so the same-strength image+session peer check works from a
    /// non-elevated token.
    /// Returns the path in ordinary drive-letter form (the native query answers in
    /// \Device\... form; converted through the machine's drive mappings).</summary>
    private static string ImagePathHandleFree(uint pid)
    {
        // Instinct 2 (rev19 review): grow the buffer instead of silently truncating a
        // long image path, and validate the ANSWER, not just the status: the returned
        // buffer pointer must be the allocation we handed in, the length must be
        // even/in-bounds/non-empty, and the content must not embed a NUL (a real path
        // never contains one - an embedded NUL means the answer is not a plain path).
        for (var chars = 1024; ; chars *= 4)
        {
            var buf = Marshal.AllocHGlobal(chars * 2);
            try
            {
                var info = new SystemProcessIdInformation
                {
                    ProcessId = (IntPtr)pid,
                    ImageName = new UnicodeString { Length = 0, MaximumLength = (ushort)Math.Min(chars * 2, ushort.MaxValue - 1), Buffer = buf }
                };
                var status = NtQuerySystemInformation(SystemProcessIdInformationClass, ref info, Marshal.SizeOf<SystemProcessIdInformation>(), out _);
                if ((status == unchecked((int)0x80000005) /* STATUS_BUFFER_OVERFLOW */
                        || status == unchecked((int)0xC0000004) /* STATUS_INFO_LENGTH_MISMATCH */
                        || status == unchecked((int)0xC0000023) /* STATUS_BUFFER_TOO_SMALL */)
                    && chars < 65536)
                    continue; // grow and retry
                if (status != 0)
                    throw new InvalidOperationException("image_query_nt:status=0x" + ((uint)status).ToString("x8"));
                if (info.ImageName.Buffer != buf)
                    throw new InvalidOperationException("image_query_nt:buffer_moved");
                if (info.ImageName.Length == 0 || (info.ImageName.Length & 1) != 0
                    || info.ImageName.Length > info.ImageName.MaximumLength
                    || info.ImageName.MaximumLength > chars * 2)
                    throw new InvalidOperationException("image_query_nt:bad_length");
                var nt = Marshal.PtrToStringUni(info.ImageName.Buffer, info.ImageName.Length / 2)
                         ?? throw new InvalidOperationException("image_query_nt:empty");
                if (nt.IndexOf('\0') >= 0)
                    throw new InvalidOperationException("image_query_nt:embedded_nul");
                if (!nt.StartsWith(@"\Device\", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("image_query_nt:unexpected_root");
                return NtPathToDos(nt);
            }
            finally { Marshal.FreeHGlobal(buf); }
        }
    }

    /// <summary>rev18 (Instinct 2 HIGH): strict \Device\... to DOS mapping. The old
    /// prefix match had no component boundary (\Device\HarddiskVolume1 matched a peer
    /// at \Device\HarddiskVolume10\BingeMote.exe, producing the invented drive-relative
    /// path "C:0\BingeMote.exe") and took the first drive letter of several. The strict
    /// core is pure and unit-tested: longest device first, the character after the
    /// device must be a backslash with at least one path component behind it (no bare
    /// volume roots, no drive-relative output), no match means unmapped - never a
    /// guessed path.</summary>
    internal static bool TryMapNtPath(string ntPath, IReadOnlyList<(string Letter, string Device)> map, out string dosPath)
    {
        dosPath = "";
        foreach (var (letter, device) in map.OrderByDescending(m => m.Device.Length))
        {
            if (device.Length == 0) continue;
            if (ntPath.Length > device.Length + 1
                && ntPath.StartsWith(device, StringComparison.OrdinalIgnoreCase)
                && ntPath[device.Length] == '\\')
            {
                dosPath = letter + ntPath[device.Length..]; // letter + "\rest" = "C:\rest", always rooted
                return true;
            }
        }
        return false;
    }

    /// <summary>The machine's live drive map, longest device target first. QueryDosDevice
    /// grows its buffer on demand instead of truncating at a fixed size.</summary>
    private static IReadOnlyList<(string Letter, string Device)> DriveMap()
    {
        var map = new List<(string, string)>();
        foreach (var drive in Environment.GetLogicalDrives())
        {
            var letter = drive.TrimEnd('\\');
            var capacity = 256;
            for (var attempt = 0; attempt < 3; attempt++)
            {
                var sb = new StringBuilder(capacity);
                var got = QueryDosDevice(letter, sb, (uint)sb.Capacity);
                if (got == 0)
                {
                    if (Marshal.GetLastWin32Error() == ERROR_INSUFFICIENT_BUFFER) { capacity *= 4; continue; }
                    break;
                }
                var target = sb.ToString();
                var nul = target.IndexOf('\0');
                if (nul >= 0) target = target[..nul];
                if (target.Length > 0) map.Add((letter, target));
                break;
            }
        }
        return map;
    }

    /// <summary>Strict live mapping; throws when the device has no drive letter (a peer
    /// image on an unmapped volume is unverifiable, not guessed).</summary>
    private static string NtPathToDos(string ntPath)
    {
        if (!TryMapNtPath(ntPath, DriveMap(), out var dosPath))
            throw new InvalidOperationException("image_query_nt:unmapped_device");
        return dosPath;
    }

    /// <summary>rev18: peer identity by FILE IDENTITY, not by path spelling. Volume
    /// serial + file index survive the alias forms this attestation covers: SUBST
    /// drive letters, reparse points, hardlinks, and 8.3 names. NOT covered:
    /// caller-local DefineDosDevice mappings, native (\\?\) volume spellings, a
    /// filtered caller device namespace, or mutation after the check - callers fail
    /// closed on those. A same-named copy of the exe from another folder is a
    /// different file index and does not match. Returns null when either file cannot
    /// be opened (caller decides the fallback).</summary>
    internal static bool? SameFile(string pathA, string pathB)
    {
        try
        {
            using var a = new FileStream(pathA, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var b = new FileStream(pathB, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            if (!GetFileInformationByHandle(a.SafeFileHandle, out var ia)) return null;
            if (!GetFileInformationByHandle(b.SafeFileHandle, out var ib)) return null;
            return ia.VolumeSerialNumber == ib.VolumeSerialNumber
                && ia.FileIndexHigh == ib.FileIndexHigh
                && ia.FileIndexLow == ib.FileIndexLow;
        }
        catch { return null; }
    }
    /// <summary>rev19 (Instinct 2): canonical path form for the namespace binding -
    /// full path, 8.3 aliases expanded via GetLongPathName, trailing separators
    /// trimmed. GetLongPathName failing (file gone between query and open) returns
    /// the full-path form; the identity open after it fails closed on a vanished file.</summary>
    internal static string NormalizePath(string path)
    {
        var full = Path.GetFullPath(path).TrimEnd('\\');
        var sb = new StringBuilder(1024);
        var n = GetLongPathName(full, sb, (uint)sb.Capacity);
        if (n > 0 && n < sb.Capacity) return sb.ToString().TrimEnd('\\');
        return full;
    }

    /// <summary>rev19 (Instinct 2): the peer's image path must resolve to THIS installed
    /// file's canonical path - the protected admin-controlled install namespace - never
    /// to a caller-local alias (SUBST, 8.3, reparse remap). Pure string binding after
    /// canonicalization; the same-file open underneath it stays defense in depth.</summary>
    internal static bool IsExpectedImagePath(string peerImagePath, string expectedPath)
    {
        return string.Equals(NormalizePath(peerImagePath), NormalizePath(expectedPath),
            StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Throws unless process pid runs this same installed exe in this same session.
    /// rev18 (Instinct 2 HIGH): the comparison is by FILE IDENTITY (volume serial + file
    /// index) whenever both image files can be opened. Aliases the path check cannot
    /// see are still rejected, but by the namespace binding above - identity alone
    /// compares files, it does not authenticate which image the peer process LOADED.
    /// No path-string compare survives anywhere: BOTH read paths require file
    /// identity, because a guessed path is exactly what the handle-free token cannot
    /// afford - and the openprocess route gets no weaker check either.
    /// Returns a short attribution string for the log (never the peer's data).</summary>
    private static string VerifyOwnImage(uint pid)
    {
        using var proc = Process.GetProcessById((int)pid); // throws if the peer already exited
        var image = ImagePath(pid, out var viaHandleFree, out var openWin32);
        var own = Environment.ProcessPath ?? throw new InvalidOperationException("own path unknown");
        var read = viaHandleFree ? "handlefree:win32=" + openWin32 : "openprocess";
        // rev18 (Instinct 2): file identity is REQUIRED, never a path-spelling compare.
        // Fail closed: an image file that cannot be opened (deleted post-launch by an
        // update, locked) rejects the peer; the broker watchdog restores service. The
        // rename/replace TOCTOU behind this is an ADMIN operation on the protected
        // install dir (rev14 ACL proof), outside the same-user non-elevated model.
        // rev19 (Instinct 2): NAMESPACE BINDING FIRST - the peer's image path must
        // resolve to this installed file's canonical path. A caller-local alias
        // (SUBST letter, 8.3 spelling, reparse remap) rejects here even when it
        // points at the same bytes: the namespace is the trust boundary, and the
        // mapped path must live inside it, not merely reach it.
        if (!IsExpectedImagePath(image, own))
        {
#if BROKER_TEST_HOOK
            LastVerifyTrace = read + " namespace:mismatch";
#endif
            throw new InvalidOperationException("peer image path is outside the install namespace (read " + read + ")");
        }
        // Then compare the ACTUAL PEER FILE against the expected file by identity
        // (volume serial + file index) - never expected-against-expected. A denied
        // or failed open fails closed. NOTE (Instinct 2 rev19 review): this does NOT
        // prove the install file was never replaced - identity is a point-in-time
        // comparison of the peer's current image file against ours, not a binding to
        // the bytes the peer process loaded at launch.
        var identity = SameFile(image, own);
        if (identity != true)
        {
#if BROKER_TEST_HOOK
            LastVerifyTrace = read + (identity == false ? " file-identity:mismatch" : " file-identity:undeterminable");
#endif
            throw new InvalidOperationException(identity == false
                ? "peer image is not the installed helper (file identity differs; read " + read + ")"
                : "peer image file identity undeterminable; failing closed (read " + read + ")");
        }
        if (proc.SessionId != Process.GetCurrentProcess().SessionId)
        {
#if BROKER_TEST_HOOK
            LastVerifyTrace = read + " file-identity session:mismatch";
#endif
            throw new InvalidOperationException("peer is in another session");
        }
        string started;
        try { started = proc.StartTime.ToString("HH:mm:ss"); } catch { started = "?"; }
#if BROKER_TEST_HOOK
        LastVerifyTrace = read + " file-identity ok pid=" + pid + " started=" + started + " session=" + proc.SessionId;
#endif
        return "pid=" + pid + " session=" + proc.SessionId + " started=" + started + " (" + read + ", file-identity)";
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
    /// <summary>CI-only: run the real peer verification against an arbitrary pid (the
    /// e2e's alias/denied-handle negative control: a same-named copy from a different
    /// folder, running as SYSTEM, must be REJECTED under a filtered token).</summary>
    internal static void TestHookVerifyPid(uint pid) => VerifyOwnImage(pid);

    /// <summary>CI negative control for the rev17 fix: what the PRE-rev17 handle-based
    /// server check saw from THIS token. Under a restricted (standard-like) token this
    /// must be ERROR_ACCESS_DENIED (5) against the SYSTEM broker, proving the CI token
    /// really matches Ezzy's condition, while the rev17 check still passes. Compiled
    /// into the e2e test build only.</summary>
    internal static (uint Pid, int Win32) TestHookLegacyProbe(NamedPipeClientStream pipe)
        => TestHookLegacyProbe(pipe, PROCESS_QUERY_LIMITED_INFORMATION);

    /// <summary>rev19 anomaly dissection: mask-parameterized variant so the e2e can
    /// tell a mask effect (0x1000 vs 0x0400) from a time/order effect.</summary>
    internal static (uint Pid, int Win32) TestHookLegacyProbe(NamedPipeClientStream pipe, uint access)
    {
        if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out var pid))
            return (0, Marshal.GetLastWin32Error());
        var h = OpenProcess(access, false, pid);
        var err = h == IntPtr.Zero ? Marshal.GetLastWin32Error() : 0;
        if (h != IntPtr.Zero) CloseHandle(h);
        return (pid, err);
    }
#endif
}
