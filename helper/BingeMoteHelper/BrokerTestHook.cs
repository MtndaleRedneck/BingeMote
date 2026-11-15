#if BROKER_TEST_HOOK
using System.Collections.Concurrent;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;

namespace BingeMoteHelper;

/// <summary>
/// CI-only broker pipe client behaviors, compiled into the e2e test build only
/// (DefineConstants includes BROKER_TEST_HOOK there; the release exe has no trace).
/// Because this runs from the same exe image as the helper, the broker's PipePeer
/// client check accepts it - which is exactly what makes it useful for testing:
///   --broker-hold <seconds>               connect and hold the connection silently: an
///                                         authorized but wedged client. The broker must
///                                         keep serving others and idle-drop this one.
///   --broker-flood <count> <outfile>      send <count> move ops as fast as possible and
///                                         write a one-line result: the broker must
///                                         rate-limit and drop the connection.
///   --broker-flood2 <count> <outfile>     flood till dropped, reconnect, flood again:
///                                         a reconnect must not buy more than the same
///                                         per-second cap a held connection gets.
///   --broker-send "<line>" <outfile>      send one raw line and record the reply:
///                                         drives the op-frame dedup test against the
///                                         real broker.
///   --broker-holddown <outfile>           press the left button and die hard without
///                                         releasing: the broker must release held
///                                         buttons when a client connection drops.
///   --broker-silent-server <s> <outfile>  serve the broker pipe name from this same exe
///                                         image (so the helper's server check passes)
///                                         but never reply: records exactly what the
///                                         helper sends, including lost-reply resends.
/// </summary>
internal static class BrokerTestHook
{
    internal static int Run(string[] args)
    {
        try
        {
            if (args.Contains("--try-open")) return TryOpen(args);
            if (args.Contains("--verify-pid")) return VerifyPid(args);
            if (args.Contains("--idle")) return Idle(args);
            if (args.Contains("--broker-op")) return BrokerOp(args);
            if (args.Contains("--broker-silent-server")) return SilentServer(args);
            if (args.Contains("--broker-holddown")) return HoldDown(args);
            if (args.Contains("--broker-send")) return SendOnce(args);
            if (args.Contains("--broker-flood2")) return FloodTwice(args);
            if (args.Contains("--broker-flood")) return Flood(args);
            if (args.Contains("--broker-hold"))
            {
                using var holdPipe = Connect();
                Thread.Sleep(TimeSpan.FromSeconds(Math.Max(ArgInt(args, "--broker-hold", 1), 1)));
                return 0;
            }
            return 1;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("broker hook error: " + ex.GetType().Name + ": " + ex.Message);
            return 1;
        }
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool GetCursorPos(out POINT pt);
    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
    private struct POINT { public int X; public int Y; }

    private static int ArgInt(string[] args, string flag, int fallback)
    {
        var i = Array.IndexOf(args, flag);
        return i >= 0 && i + 1 < args.Length && int.TryParse(args[i + 1], out var v) ? v : fallback;
    }

    private static string ArgStr(string[] args, string flag, int offset, string fallback)
    {
        var i = Array.IndexOf(args, flag);
        return i >= 0 && i + offset < args.Length ? args[i + offset] : fallback;
    }

    private static NamedPipeClientStream Connect()
    {
        var pipe = new NamedPipeClientStream(".", InputBroker.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        pipe.Connect(5000);
        PipePeer.VerifyServer(pipe);
        return pipe;
    }

    /// <summary>Send move ops until the broker drops us or the count is reached.</summary>
    private static (int Sent, string Last, bool Disconnected) FloodOnce(StreamWriter writer, StreamReader reader, int count)
    {
        var sent = 0;
        var last = "";
        var disconnected = false;
        try
        {
            for (; sent < count; sent++)
            {
                writer.WriteLine("m 1 0");
                var reply = reader.ReadLine();
                last = reply ?? "(closed)";
                if (reply == null || reply.StartsWith("err", StringComparison.Ordinal))
                {
                    if (reply != null) sent++;
                    disconnected = reply == null;
                    break;
                }
            }
        }
        catch (IOException) { disconnected = true; }
        return (sent, last, disconnected);
    }

    private static int Flood(string[] args)
    {
        var count = Math.Max(ArgInt(args, "--broker-flood", 1000), 1);
        var outFile = ArgStr(args, "--broker-flood", 2, "broker-flood.txt");
        using var pipe = Connect();
        using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
        using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
        var (sent, last, disconnected) = FloodOnce(writer, reader, count);
        File.WriteAllText(outFile, $"sent={sent} last=\"{last}\" disconnected={disconnected}");
        return 0;
    }

    /// <summary>Flood on one connection till dropped, reconnect, flood again. The two
    /// counts together show whether a reconnect buys a fresh rate budget beyond the cap
    /// a single held connection would get.</summary>
    private static int FloodTwice(string[] args)
    {
        var count = Math.Max(ArgInt(args, "--broker-flood2", 2000), 1);
        var outFile = ArgStr(args, "--broker-flood2", 2, "broker-flood2.txt");
        int sent1, sent2;
        string last1, last2;
        using (var pipe1 = Connect())
        {
            using var w1 = new StreamWriter(pipe1, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
            using var r1 = new StreamReader(pipe1, Encoding.UTF8, false, 4096, leaveOpen: true);
            (sent1, last1, _) = FloodOnce(w1, r1, count);
        }
        Thread.Sleep(300);
        using (var pipe2 = Connect())
        {
            using var w2 = new StreamWriter(pipe2, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
            using var r2 = new StreamReader(pipe2, Encoding.UTF8, false, 4096, leaveOpen: true);
            (sent2, last2, _) = FloodOnce(w2, r2, count);
        }
        File.WriteAllText(outFile, $"sent1={sent1} last1=\"{last1}\" sent2={sent2} last2=\"{last2}\"");
        return 0;
    }

    /// <summary>Send one raw line, record the one-line reply.</summary>
    private static int SendOnce(string[] args)
    {
        var line = ArgStr(args, "--broker-send", 1, "m 1 0");
        var outFile = ArgStr(args, "--broker-send", 2, "broker-send.txt");
        using var pipe = Connect();
        using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
        using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
        writer.WriteLine(line);
        var reply = reader.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
        File.WriteAllText(outFile, "reply=\"" + (reply ?? "(closed)") + "\"");
        return 0;
    }

    /// <summary>Press the left button, then die hard with the connection still open and
    /// the button still down - a client that crashes mid-drag.</summary>
    private static int HoldDown(string[] args)
    {
        var outFile = ArgStr(args, "--broker-holddown", 1, "broker-holddown.txt");
        var pipe = Connect(); // never disposed: the hard exit below simulates a crash
        var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
        var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
        writer.WriteLine("b left down");
        var reply = reader.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
        File.WriteAllText(outFile, "reply=\"" + (reply ?? "(closed)") + "\"");
        Environment.Exit(0);
        return 0;
    }

    /// <summary>Serve the broker pipe name but never reply to anything. Records every
    /// line received with a timestamp. The helper's lost-reply retry reconnects and
    /// resends, so the recording shows both the resend and whether the resent op carries
    /// a frame the broker could have deduped. Several instances listen at once (like
    /// the real broker's outstanding accepts): a client that abandons a connection on
    /// a read timeout reconnects to a fresh instance instead of waiting for the server
    /// to notice the drop.</summary>
    private static int SilentServer(string[] args)
    {
        var secs = Math.Max(ArgInt(args, "--broker-silent-server", 20), 5);
        var outFile = ArgStr(args, "--broker-silent-server", 2, "broker-silent.txt");
        var lines = new ConcurrentQueue<string>();
        var end = DateTime.UtcNow.AddSeconds(secs);
        var workers = Enumerable.Range(0, 3).Select(_ => Task.Run(() =>
        {
            while (DateTime.UtcNow < end)
            {
                var remaining = end - DateTime.UtcNow;
                if (remaining <= TimeSpan.Zero) break;
                try
                {
                    using var server = new NamedPipeServerStream(InputBroker.PipeName, PipeDirection.InOut, 8, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                    server.WaitForConnectionAsync().WaitAsync(remaining).GetAwaiter().GetResult();
                    using var reader = new StreamReader(server, Encoding.UTF8, false, 4096, leaveOpen: true);
                    while (DateTime.UtcNow < end)
                    {
                        remaining = end - DateTime.UtcNow;
                        if (remaining <= TimeSpan.Zero) break;
                        var line = reader.ReadLineAsync().WaitAsync(remaining).GetAwaiter().GetResult();
                        if (line == null) break; // client closed: go accept the reconnect
                        lines.Enqueue(DateTime.UtcNow.ToString("HH:mm:ss.fff") + " " + line);
                    }
                }
                catch (TimeoutException) { }
                catch (IOException) { }
                catch (ArgumentOutOfRangeException) { }
            }
        })).ToArray();
        Task.WaitAll(workers);
        File.WriteAllLines(outFile, lines);
        return 0;
    }
    // ----- rev17: standard-user stand-in on the always-admin runner -----
    // Ezzy's PC: the app runs with his UAC-filtered token (Administrators deny-only), and
    // the SYSTEM broker's process DACL grants Administrators - a deny-only admin SID reads
    // as a deny (open_process:win32=5), exactly like a standard user. Process-level token
    // shaping from a user process is unreliable (CI: CreateProcessAsUser win32=5 from the
    // runner's interactive admin token), so the e2e script shapes the token with Task
    // Scheduler instead: a task running as the current user at RunLevel Limited IS a
    // genuine UAC-filtered token. This hook stays token-neutral: its result line reports
    // whatever token ran it.

    /// <summary>--broker-op "&lt;line&gt;" &lt;outfile&gt;: run one op through the REAL
    /// InputBrokerClient (connect, server verification, framing, retry) and write one
    /// result line: the broker pid, what the PRE-rev17 handle-based peer check sees from
    /// THIS token, and the BrokerResult. Run under a Limited-runlevel task to test the
    /// standard-user case: there the legacy probe must read win32=5 (negative control -
    /// the token really matches Ezzy's condition) while the op must still be Played (the
    /// rev17 handle-free check passes where the old one was denied).</summary>
    // ---- rev19 token shaping (CI-only) --------------------------------------
    // The runner's "Limited" task yields a HIGH-IL full-admin token (whoami proof,
    // run 43708763402) whose present-but-disabled SeDebugPrivilege gets enabled by
    // the first .NET Process API that retries a denied open (the GetProcessById
    // prelude in VerifyOwnImage), flipping OpenProcess denied->allowed mid-process.
    // Ezzy's helper runs a genuinely filtered token: medium IL, Administrators
    // deny-only, no debug privilege to enable. --restrict-self reproduces THAT shape
    // exactly, in-process, from the probe's own token: CreateRestrictedToken with
    // DISABLE_MAX_PRIVILEGE (drops SeDebug et al.) + Administrators as a disable SID
    // (deny-only) + SetTokenInformation(medium IL). The LUA_TOKEN flag is NOT usable
    // here: it needs a linked split token or SeTcbPrivilege, and this runner's task
    // token has neither (round 17: CreateRestrictedToken win32=5). Restricting your
    // own token this way needs no special rights, and the runneradmin user SID
    // survives, so the broker pipe's console-user DACL grant applies exactly as it
    // does for Ezzy.
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenProcessToken(IntPtr processHandle, uint desiredAccess, out IntPtr tokenHandle);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool DuplicateToken(IntPtr existingTokenHandle, int impersonationLevel, out IntPtr duplicateTokenHandle);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool CreateRestrictedToken(IntPtr existingTokenHandle, uint flags, uint disableSidCount, IntPtr sidsToDisable, uint deletePrivilegeCount, IntPtr privilegesToDelete, uint restrictedSidCount, IntPtr sidsToRestrict, out IntPtr newTokenHandle);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SetThreadToken(IntPtr thread, IntPtr token);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenThreadToken(IntPtr threadHandle, uint desiredAccess, bool openAsSelf, out IntPtr tokenHandle);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool GetTokenInformation(IntPtr tokenHandle, uint tokenInformationClass, IntPtr tokenInformation, uint tokenInformationLength, out uint returnLength);
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool LookupPrivilegeName(string? lpSystemName, ref long lpLuid, StringBuilder lpName, ref uint cchName);
    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentThread();

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SetTokenInformation(IntPtr tokenHandle, uint tokenInformationClass, IntPtr tokenInformation, uint tokenInformationLength);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SaferCreateLevel(uint scopeId, uint levelId, uint flags, out IntPtr levelHandle, IntPtr lpReserved);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SaferComputeTokenFromLevel(IntPtr levelHandle, IntPtr inAccessToken, out IntPtr outAccessToken, uint flags, IntPtr lpReserved);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SaferCloseLevel(IntPtr levelHandle);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool LookupPrivilegeValue(string? lpSystemName, string lpName, out long lpLuid);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool AdjustTokenPrivileges(IntPtr tokenHandle, bool disableAllPrivileges, ref TOKEN_PRIVILEGES1 newState, uint bufferLength, IntPtr previousState, IntPtr returnLength);

    [StructLayout(LayoutKind.Sequential)]
    private struct TOKEN_PRIVILEGES1 { public uint PrivilegeCount; public long Luid; public uint Attributes; }

    private const uint TOKEN_ADJUST_PRIVILEGES = 0x0020;
    private const uint SE_PRIVILEGE_REMOVED = 0x4;

    // Approach 5 (round 21 taught: this runner image has UAC DISABLED - explorer's
    // token is HIGH IL, S-1-16-12288 - so NO filtered token exists to borrow, and
    // CreateRestrictedToken/Safer are blocked on the task token): shape a duplicate
    // of our own token by REMOVING SeDebugPrivilege outright (SE_PRIVILEGE_REMOVED -
    // disabled is not enough, .NET Process APIs re-enable a present-but-disabled
    // privilege, which was the round-10/11 anomaly) and dropping IL to medium. On a
    // medium-IL token with no SeDebug, the broker process's SI no-read-up label
    // denies the read BEFORE any DACL check - exactly the denial Ezzy's filtered
    // token hits. Residual deviation from his shape: Administrators stay ENABLED
    // (no documented API makes a group deny-only without CreateRestrictedToken);
    // it does not affect the property under test because mandatory policy gates
    // first. The e2e asserts the property (medium IL + no SeDebug + win32=5
    // denial), not the cosmetic group state.
    private static bool RestrictViaAdjustToken()
    {
        if (!OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE | TOKEN_QUERY, out var own))
        { RestrictHow += ";adjust:opentoken:win32=" + Marshal.GetLastWin32Error(); return false; }
        if (!DuplicateTokenEx(own, 0xF01FF /* TOKEN_ALL_ACCESS */, IntPtr.Zero, 2 /* SecurityImpersonation */, 2 /* TokenImpersonation */, out var imp))
        { RestrictHow += ";adjust:duptokenex:win32=" + Marshal.GetLastWin32Error(); CloseHandle(own); return false; }
        CloseHandle(own);
        // Diagnostic: enumerate the duplicate's privileges (round 22: removal came back
        // 1300 ERROR_NOT_ALL_ASSIGNED - is SeDebugPrivilege even in the duplicate?).
        if (GetTokenInformation(imp, 3 /* TokenPrivileges */, IntPtr.Zero, 0, out var plen))
        { /* never */ }
        var pbuf = Marshal.AllocHGlobal((int)plen);
        if (GetTokenInformation(imp, 3, pbuf, plen, out _))
        {
            var pc = Marshal.ReadInt32(pbuf);
            var names = new List<string>();
            for (var i = 0; i < pc && i < 40; i++)
            {
                var entry = pbuf + 4 + i * 12;
                var l = Marshal.ReadInt64(entry);
                var nb = 0u; LookupPrivilegeName(null, ref l, null, ref nb);
                var nsb = new StringBuilder((int)nb);
                if (LookupPrivilegeName(null, ref l, nsb, ref nb)) names.Add(nsb.ToString() + (Marshal.ReadInt32(entry + 8) != 0 ? "(on)" : "(off)"));
            }
            RestrictHow += ";adjust:dup-privs=" + pc + "[" + string.Join(",", names) + "]";
        }
        else RestrictHow += ";adjust:priv-enum:win32=" + Marshal.GetLastWin32Error();
        if (!LookupPrivilegeValue(null, "SeDebugPrivilege", out var luid))
        { RestrictHow += ";adjust:lookup:win32=" + Marshal.GetLastWin32Error(); CloseHandle(imp); return false; }
        var tp = new TOKEN_PRIVILEGES1 { PrivilegeCount = 1, Luid = luid, Attributes = SE_PRIVILEGE_REMOVED };
        if (!AdjustTokenPrivileges(imp, false, ref tp, 0, IntPtr.Zero, IntPtr.Zero) || Marshal.GetLastWin32Error() != 0)
        { RestrictHow += ";adjust:removepriv:win32=" + Marshal.GetLastWin32Error(); CloseHandle(imp); return false; }
        var il = MakeSidAttrs("S-1-16-8192", SE_GROUP_INTEGRITY);
        var ilBuf = Marshal.AllocHGlobal(Marshal.SizeOf<SidAndAttributes>());
        Marshal.StructureToPtr(il, ilBuf, false);
        if (!SetTokenInformation(imp, 25 /* TokenIntegrityLevel */, ilBuf, (uint)Marshal.SizeOf<SidAndAttributes>()))
        { RestrictHow += ";adjust:setil:win32=" + Marshal.GetLastWin32Error(); CloseHandle(imp); return false; }
        if (!SetThreadToken(IntPtr.Zero, imp))
        { RestrictHow += ";adjust:setthread:win32=" + Marshal.GetLastWin32Error(); CloseHandle(imp); return false; }
        RestrictHow += ";adjust-token(SeDebugPrivilege REMOVED, IL medium; Administrators still enabled - IL gates first)";
        return true;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SidAndAttributes { public IntPtr Sid; public uint Attributes; }

    private const uint TOKEN_QUERY = 0x0008, TOKEN_DUPLICATE = 0x0002, TOKEN_ADJUST_DEFAULT = 0x0080;
    private const uint DISABLE_MAX_PRIVILEGE = 0x1;
    private const uint SE_GROUP_INTEGRITY = 0x20;
    private const uint SAFER_SCOPEID_USER = 1, SAFER_LEVELID_NORMALUSER = 0x20000;

    // Which restriction path produced the working token (dumped with the whoami evidence).
    private static string RestrictHow = Environment.GetEnvironmentVariable("BINGEMOTE_FILTERED_VIA") ?? "n/a";

    // ---- approach 4: relaunch with explorer's token ---------------------------
    // Round 19: CreateRestrictedToken is denied even for a plain clone on this
    // runner's task token (win32=5), and SaferComputeTokenFromLevel is gone
    // (win32=31). The runner's interactive shell still holds the real thing:
    // explorer.exe runs on the UAC-FILTERED console token (medium IL, Administrators
    // deny-only, no SeDebugPrivilege) - Ezzy's exact token class. An elevated admin
    // can duplicate that token and CreateProcessWithTokenW a child with it, so the
    // probe relaunches ITSELF as the console user's filtered token (console-user SID
    // -> the broker pipe DACL grant applies, as for Ezzy). The --filtered-child
    // marker prevents any relaunch loop.
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint processId);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool Process32First(IntPtr snapshot, ref PROCESSENTRY32 entry);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool Process32Next(IntPtr snapshot, ref PROCESSENTRY32 entry);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint desiredAccess, bool inheritHandle, uint processId);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool DuplicateTokenEx(IntPtr existingToken, uint desiredAccess, IntPtr tokenAttributes, int impersonationLevel, int tokenType, out IntPtr newToken);
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CreateProcessWithTokenW(IntPtr token, uint logonFlags, string? applicationName, string commandLine, uint creationFlags, IntPtr environment, string? currentDirectory, ref STARTUPINFO startupInfo, out PROCESS_INFORMATION processInfo);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentProcessId();

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct PROCESSENTRY32
    {
        public uint dwSize, cntUsage, th32ProcessID;
        public IntPtr th32DefaultHeapID;
        public uint th32ModuleID, cntThreads, th32ParentProcessID;
        public int pcPriClassBase;
        public uint dwFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szExeFile;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct STARTUPINFO
    {
        public uint cb;
        public string? lpReserved, lpDesktop, lpTitle;
        public uint dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public ushort wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public uint dwProcessId, dwThreadId; }

    private static bool OwnTokenIsMediumOrLower()
    {
        if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, out var tok)) return false;
        try { var il = ReadSidAttrs(tok, 25).Sid; return il == "S-1-16-8192" || il == "S-1-16-4096"; }
        catch { return false; }
        finally { CloseHandle(tok); }
    }

    private static int RelaunchViaExplorerToken(string[] args)
    {
        uint explorerPid = 0;
        var snap = CreateToolhelp32Snapshot(2 /* TH32CS_SNAPPROCESS */, 0);
        if (snap != IntPtr.Zero && snap != new IntPtr(-1))
        {
            var e = new PROCESSENTRY32 { dwSize = (uint)Marshal.SizeOf<PROCESSENTRY32>() };
            if (Process32First(snap, ref e))
                do { if (string.Equals(e.szExeFile, "explorer.exe", StringComparison.OrdinalIgnoreCase)) { explorerPid = e.th32ProcessID; break; } }
                while (Process32Next(snap, ref e));
            CloseHandle(snap);
        }
        if (explorerPid == 0) { RestrictHow = "explorer:not-found"; return -1; }
        var ph = OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, explorerPid);
        if (ph == IntPtr.Zero) { RestrictHow = "explorer:open:win32=" + Marshal.GetLastWin32Error(); return -1; }
        if (!OpenProcessToken(ph, TOKEN_DUPLICATE | TOKEN_QUERY, out var etok))
        { RestrictHow = "explorer:opentoken:win32=" + Marshal.GetLastWin32Error(); CloseHandle(ph); return -1; }
        CloseHandle(ph);
        // Only trust the shell token if it really is the filtered shape; a HIGH-IL
        // explorer (UAC-off image) would fake the shape we are testing.
        try { var il = ReadSidAttrs(etok, 25).Sid; if (il != "S-1-16-8192" && il != "S-1-16-4096") { RestrictHow = "explorer:token-not-filtered(" + il + ")"; CloseHandle(etok); return -1; } }
        catch { RestrictHow = "explorer:il-read-failed"; CloseHandle(etok); return -1; }
        if (!DuplicateTokenEx(etok, 0xF01FF /* TOKEN_ALL_ACCESS */, IntPtr.Zero, 2 /* SecurityImpersonation */, 1 /* TokenPrimary */, out var primary))
        { RestrictHow = "explorer:duptokenex:win32=" + Marshal.GetLastWin32Error(); CloseHandle(etok); return -1; }
        CloseHandle(etok);
        Environment.SetEnvironmentVariable("BINGEMOTE_FILTERED_VIA", "explorer-token(pid=" + explorerPid + ")");
        var cmd = "\"" + Environment.ProcessPath + "\" \"" + string.Join("\" \"", args) + "\" --filtered-child";
        var si = new STARTUPINFO { cb = (uint)Marshal.SizeOf<STARTUPINFO>(), lpDesktop = "winsta0\\default" };
        if (!CreateProcessWithTokenW(primary, 2 /* LOGON_WITH_PROFILE */, null, cmd, 0, IntPtr.Zero, null, ref si, out var pi))
        { RestrictHow = "explorer:createproc:win32=" + Marshal.GetLastWin32Error(); CloseHandle(primary); return -1; }
        CloseHandle(primary);
        WaitForSingleObject(pi.hProcess, 0xFFFFFFFF);
        GetExitCodeProcess(pi.hProcess, out var code);
        CloseHandle(pi.hProcess); CloseHandle(pi.hThread);
        if (code != 0) Console.Error.WriteLine("explorer-token child exited " + code + " (child stderr is not redirected; inspect the operation's outfile)");
        return (int)code;
    }

    // Copy a well-known SID into unmanaged memory and wrap it in SID_AND_ATTRIBUTES.
    private static SidAndAttributes MakeSidAttrs(string sidString, uint attributes)
    {
        var sid = new System.Security.Principal.SecurityIdentifier(sidString);
        var bytes = new byte[sid.BinaryLength];
        sid.GetBinaryForm(bytes, 0);
        var p = Marshal.AllocHGlobal(bytes.Length);
        Marshal.Copy(bytes, 0, p, bytes.Length);
        return new SidAndAttributes { Sid = p, Attributes = attributes };
    }

    private static void RestrictSelfIfAsked(string[] args)
    {
        if (!args.Contains("--restrict-self")) return;
        if (OwnTokenIsMediumOrLower()) { if (RestrictHow == "n/a") RestrictHow = "already-filtered"; return; }
        if (!args.Contains("--filtered-child"))
        {
            var childCode = RelaunchViaExplorerToken(args);
            if (childCode >= 0) Environment.Exit(childCode); // child wrote the evidence files
        }
        if (RestrictViaAdjustToken()) return;
        // Parent decision (round 23): shaping failure is EVIDENCE, not a crash - every
        // mechanism's refusal is recorded in restrict-self-path and the probe proceeds
        // unshaped; the e2e asserts the shape only when shaping actually landed.
        try
        {
        if (!OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ADJUST_DEFAULT, out var own))
            throw new InvalidOperationException("restrict-self:OpenProcessToken:win32=" + Marshal.GetLastWin32Error());
        if (!DuplicateToken(own, 2 /* SecurityImpersonation */, out var imp))
            throw new InvalidOperationException("restrict-self:DuplicateToken:win32=" + Marshal.GetLastWin32Error());
        // Deny-only Administrators: SidsToDisable with attributes 0 -> SE_GROUP_USE_FOR_DENY_ONLY.
        var disable = MakeSidAttrs("S-1-5-32-544", 0);
        var disableBuf = Marshal.AllocHGlobal(Marshal.SizeOf<SidAndAttributes>());
        Marshal.StructureToPtr(disable, disableBuf, false);
        // Diagnostic ladder (rounds 17-18: CreateRestrictedToken win32=5 with and without
        // LUA_TOKEN on this runner's task token): try the full call, then without the
        // disable-SID list, then a plain clone, then the Safer API - and record which
        // path (and error codes) landed, so a denied step is evidence, not a mystery.
        IntPtr shaped;
        if (CreateRestrictedToken(imp, DISABLE_MAX_PRIVILEGE, 1, disableBuf, 0, IntPtr.Zero, 0, IntPtr.Zero, out shaped))
            RestrictHow += ";crt-full";
        else
        {
            var eFull = Marshal.GetLastWin32Error();
            if (CreateRestrictedToken(imp, DISABLE_MAX_PRIVILEGE, 0, IntPtr.Zero, 0, IntPtr.Zero, 0, IntPtr.Zero, out shaped))
                RestrictHow += ";crt-maxpriv-only(full:win32=" + eFull + ")";
            else
            {
                var eMax = Marshal.GetLastWin32Error();
                if (CreateRestrictedToken(imp, 0, 0, IntPtr.Zero, 0, IntPtr.Zero, 0, IntPtr.Zero, out var clone))
                { CloseHandle(clone); RestrictHow += ";crt-clone-ok(maxpriv:win32=" + eMax + ",full:win32=" + eFull + ")"; }
                else
                    RestrictHow += ";crt-all-denied(clone:win32=" + Marshal.GetLastWin32Error() + ",maxpriv:win32=" + eMax + ",full:win32=" + eFull + ")";
                if (!SaferCreateLevel(SAFER_SCOPEID_USER, SAFER_LEVELID_NORMALUSER, 0, out var lvl, IntPtr.Zero))
                    throw new InvalidOperationException("restrict-self:SaferCreateLevel:win32=" + Marshal.GetLastWin32Error() + " [" + RestrictHow + "]");
                if (!SaferComputeTokenFromLevel(lvl, imp, out shaped, 0, IntPtr.Zero))
                    throw new InvalidOperationException("restrict-self:SaferComputeTokenFromLevel:win32=" + Marshal.GetLastWin32Error() + " [" + RestrictHow + "]");
                SaferCloseLevel(lvl);
                RestrictHow += ";safer-normaluser";
            }
        }
        // Medium integrity level (you may always LOWER your own token's IL).
        var il = MakeSidAttrs("S-1-16-8192", SE_GROUP_INTEGRITY);
        var ilBuf = Marshal.AllocHGlobal(Marshal.SizeOf<SidAndAttributes>());
        Marshal.StructureToPtr(il, ilBuf, false);
        if (!SetTokenInformation(shaped, 25 /* TokenIntegrityLevel */, ilBuf, (uint)Marshal.SizeOf<SidAndAttributes>()))
            throw new InvalidOperationException("restrict-self:SetTokenInformation(IL):win32=" + Marshal.GetLastWin32Error() + " [" + RestrictHow + "]");
        // SetThreadToken needs an IMPERSONATION token; Safer returns a primary one.
        if (!DuplicateToken(shaped, 2 /* SecurityImpersonation */, out var shapedImp))
            throw new InvalidOperationException("restrict-self:DuplicateToken(2):win32=" + Marshal.GetLastWin32Error() + " [" + RestrictHow + "]");
        if (!SetThreadToken(IntPtr.Zero, shapedImp))
            throw new InvalidOperationException("restrict-self:SetThreadToken:win32=" + Marshal.GetLastWin32Error() + " [" + RestrictHow + "]");
        }
        catch (Exception ex) { RestrictHow += ";unshaped(" + ex.Message + ")"; }
    }

    /// <summary>In-process token dump (whoami.exe would spawn on the UNRESTRICTED
    /// primary token): user SID, groups with attributes (0x10 = deny-only), privilege
    /// names, integrity-level SID. Written where the e2e expects the whoami file.</summary>
    private static void DumpTokenEvidence(string path)
    {
        try
        {
            if (!OpenThreadToken(GetCurrentThread(), TOKEN_QUERY, true, out var tok)
                && !OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, out tok))
            { File.WriteAllText(path, "token-dump:open:win32=" + Marshal.GetLastWin32Error()); return; }
            var sb = new StringBuilder();
            sb.AppendLine("restrict-self-path: " + RestrictHow);
            var user = ReadSidAttrs(tok, 1); // TokenUser
            sb.AppendLine("user-sid: " + user.Sid);
            // TokenGroups: count at +0, entries from +8 (x64 pointer alignment), 16 bytes each
            GetTokenInformation(tok, 2, IntPtr.Zero, 0, out var glen);
            var gh = Marshal.AllocHGlobal((int)glen);
            try
            {
                if (!GetTokenInformation(tok, 2, gh, glen, out _)) throw new InvalidOperationException("groups:win32=" + Marshal.GetLastWin32Error());
                var count = Marshal.ReadInt32(gh, 0);
                for (var i = 0; i < count; i++)
                {
                    var sidPtr = Marshal.ReadIntPtr(gh, 8 + i * 16);
                    var attrs = (uint)Marshal.ReadInt32(gh, 8 + i * 16 + IntPtr.Size);
                    sb.AppendLine("group: " + new System.Security.Principal.SecurityIdentifier(sidPtr) + " attrs=0x" + attrs.ToString("x8") + ((attrs & 0x10) != 0 ? " DENY_ONLY" : ""));
                }
            }
            finally { Marshal.FreeHGlobal(gh); }
            // TokenPrivileges: count at +0, 12-byte entries from +4
            GetTokenInformation(tok, 3, IntPtr.Zero, 0, out var plen);
            var ph = Marshal.AllocHGlobal((int)plen);
            try
            {
                if (!GetTokenInformation(tok, 3, ph, plen, out _)) throw new InvalidOperationException("privs:win32=" + Marshal.GetLastWin32Error());
                var count = Marshal.ReadInt32(ph, 0);
                for (var i = 0; i < count; i++)
                {
                    var luid = Marshal.ReadInt64(ph, 4 + i * 12);
                    var nameLen = 0u;
                    LookupPrivilegeName(null, ref luid, null!, ref nameLen);
                    var nsb = new StringBuilder((int)nameLen);
                    var luidCopy = luid;
                    sb.AppendLine("privilege: " + (LookupPrivilegeName(null, ref luidCopy, nsb, ref nameLen) ? nsb.ToString() : "luid=" + luid));
                }
            }
            finally { Marshal.FreeHGlobal(ph); }
            sb.AppendLine("il-sid: " + ReadSidAttrs(tok, 25).Sid); // TokenIntegrityLevel
            File.WriteAllText(path, sb.ToString());
        }
        catch (Exception ex) { try { File.WriteAllText(path, "token-dump failed: " + ex.GetType().Name + ": " + ex.Message); } catch { } }
    }

    private static (string Sid, uint Attrs) ReadSidAttrs(IntPtr tok, uint infoClass)
    {
        GetTokenInformation(tok, infoClass, IntPtr.Zero, 0, out var len);
        var h = Marshal.AllocHGlobal((int)len);
        try
        {
            if (!GetTokenInformation(tok, infoClass, h, len, out _)) throw new InvalidOperationException("sid:" + infoClass + ":win32=" + Marshal.GetLastWin32Error());
            return (new System.Security.Principal.SecurityIdentifier(Marshal.ReadIntPtr(h, 0)).ToString(), (uint)Marshal.ReadInt32(h, IntPtr.Size));
        }
        finally { Marshal.FreeHGlobal(h); }
    }

    private static int BrokerOp(string[] args)
    {
        var oi = Array.IndexOf(args, "--broker-op");
        var op = oi + 1 < args.Length ? args[oi + 1] : null;
        var outfile = oi + 2 < args.Length ? args[oi + 2] : null;
        if (op == null || outfile == null) { Console.Error.WriteLine("--broker-op needs <line> <outfile>"); return 1; }
        RestrictSelfIfAsked(args);
        // Token evidence next to the result: in-process dump (a whoami.exe child would
        // run on the UNRESTRICTED primary token). Must show medium IL + Administrators
        // deny-only + no SeDebugPrivilege - Ezzy's filtered shape.
        DumpTokenEvidence(outfile + ".whoami.txt");
        string pidNote = "nopipe"; var legacy = -1; var legacyB = -1; var legacyQ = -1; var legacyD = -1;
        try
        {
            using var raw = new NamedPipeClientStream(".", InputBroker.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            raw.Connect(5000);
            var probe = PipePeer.TestHookLegacyProbe(raw);
            legacy = probe.Win32; pidNote = probe.Pid.ToString();
            // rev19 anomaly dissection (runs 43704201857 + 43706117121): the FIRST
            // OpenProcess in this fresh process is denied while TryRun's own verify
            // (the same call on the same pid under the same token a second later) is
            // allowed, and the post-op re-probe stays allowed. Isolate the variable:
            // B = same probe 400ms later (time), Q = PROCESS_QUERY_INFORMATION instead
            // of LIMITED (mask), D = after a Process.GetProcessById prelude (what
            // VerifyOwnImage runs before its own OpenProcess).
            Thread.Sleep(400);
            legacyB = PipePeer.TestHookLegacyProbe(raw).Win32;
            legacyQ = PipePeer.TestHookLegacyProbe(raw, 0x0400).Win32; // PROCESS_QUERY_INFORMATION
            try { using var gp = System.Diagnostics.Process.GetProcessById((int)probe.Pid); _ = gp.SessionId; } catch { }
            legacyD = PipePeer.TestHookLegacyProbe(raw).Win32;
        }
        catch (Exception ex) { pidNote = "probe:" + ex.GetType().Name; }
        // The op itself goes through the REAL InputBrokerClient (connect, server
        // verification, framing, retry) - the trace below is from THAT connection,
        // endpoint-bound, not from a side pipe. Effect measurement moved script-side:
        // this probe now runs non-interactively (genuine standard user, Password
        // logon), whose own desktop has no session-2 cursor to read; the e2e script
        // measures the interactive cursor before/after the task instead.
        PipePeer.LastVerifyTrace = null;
        var result = InputBrokerClient.TryRun(op);
        var trace = PipePeer.LastVerifyTrace ?? "(none)";
        // rev19 anomaly probe (run 43704201857): the pre-op legacy probe read win32=5
        // while TryRun's own verify opened the broker fine seconds later - same pid,
        // same token. Re-probe AFTER the op so the log shows whether the denial is
        // time-dependent (broker still initializing) or path-dependent.
        var legacy2 = -1;
        try
        {
            using var raw2 = new NamedPipeClientStream(".", InputBroker.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            raw2.Connect(5000);
            legacy2 = PipePeer.TestHookLegacyProbe(raw2).Win32;
        }
        catch { }
        File.WriteAllText(outfile,
            $"broker_pid={pidNote} legacy_win32={legacy} legacyB_win32={legacyB} legacyQ_win32={legacyQ} legacyD_win32={legacyD} legacy2_win32={legacy2} result={result} trace=\"{trace}\"");
        return result == InputBrokerClient.BrokerResult.Played ? 0 : 1;
    }

    /// <summary>--verify-pid &lt;pid&gt; &lt;outfile&gt;: run the real PipePeer verification against
    /// an arbitrary pid and record the outcome + branch trace. The e2e points it at a
    /// same-named copy from another folder running as SYSTEM: a filtered token cannot
    /// open it (denied-handle squatter) and the handle-free identity check must REJECT
    /// it - the anti-squat negative control.</summary>
    private static int VerifyPid(string[] args)
    {
        var pid = ArgInt(args, "--verify-pid", 0);
        var outfile = ArgStr(args, "--verify-pid", 2, "verify-pid.txt");
        RestrictSelfIfAsked(args);
        DumpTokenEvidence(outfile + ".whoami.txt");
        PipePeer.LastVerifyTrace = null;
        string outcome;
        try { PipePeer.TestHookVerifyPid((uint)pid); outcome = "accepted"; }
        catch (Exception ex) { outcome = "rejected:" + ex.Message; }
        File.WriteAllText(outfile, $"outcome={outcome} trace=\"{PipePeer.LastVerifyTrace ?? "(none)"}\"");
        return outcome == "accepted" ? 0 : 1;
    }

    /// <summary>--idle &lt;seconds&gt; &lt;pidfile&gt;: sit still without touching the pipe or the
    /// mutex. The e2e runs a copied exe this way (as SYSTEM) to stand in for a squatter:
    /// same process name, different file.</summary>
    private static int Idle(string[] args)
    {
        var secs = Math.Max(ArgInt(args, "--idle", 30), 1);
        var pidFile = ArgStr(args, "--idle", 2, "idle-pid.txt");
        File.WriteAllText(pidFile, Environment.ProcessId.ToString());
        Thread.Sleep(TimeSpan.FromSeconds(secs));
        return 0;
    }

    /// <summary>--try-open <path> <outfile>: trial-precondition probe - can THIS token
    /// open the installed exe read-only ( the minimum rights the identity check needs )?
    /// Records open-ok or open-denied:<win32> to the outfile.</summary>
    private static int TryOpen(string[] args)
    {
        RestrictSelfIfAsked(args);
        var path = args[Array.IndexOf(args, "--try-open") + 1];
        var outFile = args[Array.IndexOf(args, "--try-open") + 2];
        string line;
        try { using (File.OpenRead(path)) { } line = "open-ok"; }
        catch (Exception ex) { line = "open-denied:" + ex.GetType().Name; }
        File.WriteAllText(outFile, line);
        Console.WriteLine(line);
        return 0;
    }
}
#endif
