#if BROKER_TEST_HOOK
using System.Collections.Concurrent;
using System.Diagnostics;
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
            if (args.Contains("--run-restricted")) return RunRestricted(args);
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
    // ----- rev17: standard-user (restricted-token) stand-ins on the always-admin runner -----
    // Ezzy's PC: the app runs with his UAC-filtered token (Administrators deny-only), and
    // the SYSTEM broker's process DACL grants Administrators - a deny-only admin SID reads
    // as a deny (open_process:win32=5), exactly like a standard user. These hooks shape a
    // token the same way so CI finally sees his condition.

    private const uint TOKEN_DUPLICATE = 0x0002;
    private const uint TOKEN_QUERY = 0x0008;
    private const uint TOKEN_ADJUST_PRIVILEGES = 0x0020;
    private const uint DISABLE_MAX_PRIVILEGE = 0x1;
    private const uint CREATE_NO_WINDOW = 0x08000000;
    private const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private const uint SE_PRIVILEGE_ENABLED = 0x2;

    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool OpenProcessToken(IntPtr h, uint access, out IntPtr token);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool ConvertStringSidToSid(string sid, out IntPtr pSid);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool CreateRestrictedToken(IntPtr existing, uint flags, uint disableCount, IntPtr sidsToDisable, uint privDeleteCount, IntPtr privsToDelete, uint groupCount, IntPtr groupsToRestrict, out IntPtr newToken);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool CreateProcessAsUser(IntPtr token, string? app, string cmd, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr env, string? dir, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool LookupPrivilegeValue(string? system, string name, out long luid);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool AdjustTokenPrivileges(IntPtr token, bool disableAll, ref TOKEN_PRIVILEGES newState, uint len, IntPtr prev, IntPtr retLen);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint WaitForSingleObject(IntPtr h, uint ms);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetExitCodeProcess(IntPtr h, out uint code);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr h);

    [StructLayout(LayoutKind.Sequential)] private struct SID_AND_ATTRIBUTES { public IntPtr Sid; public uint Attributes; }
    [StructLayout(LayoutKind.Sequential)] private struct TOKEN_PRIVILEGES { public uint Count; public long Luid; public uint Attributes; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct STARTUPINFO { public int cb; public string? reserved, desktop, title; public uint x, y, xSize, ySize, xCount, yCount, fill, flags; public short show, reserved2; public IntPtr reserved3, stdinH, stdoutH, stderrH; }
    [StructLayout(LayoutKind.Sequential)] private struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public uint pid, tid; }

    /// <summary>Our own token restricted the way UAC filters an admin's: BUILTIN
    /// Administrators becomes deny-only and only default-user privileges survive.</summary>
    private static IntPtr NewStandardLikeToken()
    {
        if (!OpenProcessToken(Process.GetCurrentProcess().Handle, TOKEN_DUPLICATE | TOKEN_QUERY, out var own))
            throw new InvalidOperationException("OpenProcessToken:win32=" + Marshal.GetLastWin32Error());
        try
        {
            if (!ConvertStringSidToSid("S-1-5-32-544", out var admins))
                throw new InvalidOperationException("ConvertStringSidToSid:win32=" + Marshal.GetLastWin32Error());
            var sa = Marshal.AllocHGlobal(Marshal.SizeOf<SID_AND_ATTRIBUTES>());
            try
            {
                Marshal.StructureToPtr(new SID_AND_ATTRIBUTES { Sid = admins, Attributes = 0 }, sa, false);
                if (!CreateRestrictedToken(own, DISABLE_MAX_PRIVILEGE, 1, sa, 0, IntPtr.Zero, 0, IntPtr.Zero, out var restricted))
                    throw new InvalidOperationException("CreateRestrictedToken:win32=" + Marshal.GetLastWin32Error());
                return restricted;
            }
            finally { Marshal.FreeHGlobal(sa); LocalFree(admins); }
        }
        finally { CloseHandle(own); }
    }

    private static void EnablePrivilege(IntPtr token, string name)
    {
        if (!LookupPrivilegeValue(null, name, out var luid)) return;
        var tp = new TOKEN_PRIVILEGES { Count = 1, Luid = luid, Attributes = SE_PRIVILEGE_ENABLED };
        AdjustTokenPrivileges(token, false, ref tp, 0, IntPtr.Zero, IntPtr.Zero);
    }

    /// <summary>--broker-op "&lt;line&gt;" &lt;outfile&gt;: run one op through the REAL
    /// InputBrokerClient (connect, server verification, framing, retry) and write one
    /// result line: the broker pid, what the PRE-rev17 handle-based peer check sees from
    /// THIS token, and the BrokerResult. Run it as a --run-restricted child to test the
    /// standard-user case: there the legacy probe must read win32=5 (negative control -
    /// the token really matches Ezzy's UAC-filtered condition) while the op must still be
    /// Played (the rev17 handle-free check passes where the old one was denied).</summary>
    private static int BrokerOp(string[] args)
    {
        var oi = Array.IndexOf(args, "--broker-op");
        var op = oi + 1 < args.Length ? args[oi + 1] : null;
        var outfile = oi + 2 < args.Length ? args[oi + 2] : null;
        if (op == null || outfile == null) { Console.Error.WriteLine("--broker-op needs <line> <outfile>"); return 1; }
        string pidNote = "nopipe"; var legacy = -1;
        try
        {
            using var raw = new NamedPipeClientStream(".", InputBroker.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            raw.Connect(5000);
            var probe = PipePeer.TestHookLegacyProbe(raw);
            legacy = probe.Win32; pidNote = probe.Pid.ToString();
        }
        catch (Exception ex) { pidNote = "probe:" + ex.GetType().Name; }
        var result = InputBrokerClient.TryRun(op);
        File.WriteAllText(outfile, $"broker_pid={pidNote} legacy_win32={legacy} result={result}");
        return result == InputBrokerClient.BrokerResult.Played ? 0 : 1;
    }

    /// <summary>--run-restricted [--] [args]: relaunch this exe with the given args (none =
    /// a plain launch) as a CHILD PROCESS under the standard-like restricted token, same
    /// session. Unlike --broker-op-restricted (thread token, pipe path only) the child runs
    /// the full Program.Main unprivileged - used to prove a second UI launch from a
    /// non-elevated user asks the running copy to show its window instead of an "update
    /// takeover" when the same-named SYSTEM broker shadows RunningCopyPath.</summary>
    private static int RunRestricted(string[] args)
    {
        var sep = Array.IndexOf(args, "--run-restricted");
        var rest = args[(sep + 1)..];
        if (rest.Length > 0 && rest[0] == "--") rest = rest[1..];
        var ownExe = Environment.ProcessPath ?? throw new InvalidOperationException("own path");
        var cmdLine = "\"" + ownExe + "\"";
        foreach (var a in rest) cmdLine += " \"" + a.Replace("\"", "\\\"") + "\"";

        if (!OpenProcessToken(Process.GetCurrentProcess().Handle, TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ADJUST_PRIVILEGES, out var own))
            throw new InvalidOperationException("OpenProcessToken:win32=" + Marshal.GetLastWin32Error());
        EnablePrivilege(own, "SeAssignPrimaryTokenPrivilege");
        EnablePrivilege(own, "SeIncreaseQuotaPrivilege");
        CloseHandle(own);
        var restricted = NewStandardLikeToken();
        try
        {
            var si = new STARTUPINFO { cb = Marshal.SizeOf<STARTUPINFO>() };
            if (!CreateProcessAsUser(restricted, null, cmdLine, IntPtr.Zero, IntPtr.Zero, false, CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT, IntPtr.Zero, null, ref si, out var pi))
                throw new InvalidOperationException("CreateProcessAsUser:win32=" + Marshal.GetLastWin32Error());
            CloseHandle(pi.hThread);
            WaitForSingleObject(pi.hProcess, 90000);
            GetExitCodeProcess(pi.hProcess, out var code);
            CloseHandle(pi.hProcess);
            return unchecked((int)code);
        }
        finally { CloseHandle(restricted); }
    }
}
#endif
