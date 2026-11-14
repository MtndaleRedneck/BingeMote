using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;

namespace BingeMoteHelper;

/// <summary>
/// rev11: SYSTEM-level input broker (the "copy how Unified Remote ticks" fix), hardened in rev12.
///
/// Why: on Ezzy's PC an elevated Realtek SwUSB dialog grabs foreground on the bare
/// desktop, and UIPI silently discards SendInput/SetCursorPos from every lower
/// integrity level (Unified Remote avoids this whole class with its signed uvhid.sys
/// virtual HID driver; our own signed driver needs an EV cert + business, ruled out).
/// This broker is the same exe started by Task Scheduler as SYSTEM in the interactive
/// session (the psexec -s -i pattern, no service, no driver, no signing): nothing in
/// the session outranks SYSTEM, so injected input is never UIPI-filtered.
///
/// The per-user helper stays at medium IL and forwards each input op over a local
/// named pipe; when the broker is absent (old installs) the helper falls back to its
/// original local SendInput path, so nothing regresses.
///
/// SECURITY (rev12, answering the rev11 review):
/// - The DACL is no longer the sole auth: it is tightened to SYSTEM/Administrators plus
///   the console session's own user (falling back to Authenticated Users only if the
///   session user cannot be resolved), AND every client is verified server-side via
///   PipePeer (client PID's image must be this same installed exe, in this session).
/// - The broker only runs as SYSTEM (Program.Main refuses otherwise), in the active
///   console session only (it exits non-zero - fail closed - when the console hop
///   fails, so the helper falls back to local input instead of trusting a session-0
///   broker that cannot reach the desktop).
/// - A Global mutex is the first-instance claim; the pipe keeps several instances
///   listening at once, so one wedged client neither blocks the others nor opens a
///   re-listen gap. Idle clients are dropped after a read timeout.
/// - Every op passes OpValidator (bounds, whitelist verbs) and an OpRateLimiter.
/// - The pipe still carries input ops only: no shell, no file access, no broker-side
///   command channel. Secure-desktop (UAC prompt) input remains a platform limit no
///   user-mode broker can cross; that is unchanged and not claimed here.
/// </summary>
internal static class InputBroker
{
    internal const string PipeName = "BingeMoteInputBroker";

    private const int MaxPipeInstances = 8;   // connected clients + outstanding accepts share this cap
    private const int OutstandingAccepts = 3; // always listening: a connecting client never hits a gap
    private const int MaxLineLength = 8192;   // fits a max-length escaped text op with headroom
    private const int OpsPerSecondPerClient = 600;
    private static readonly TimeSpan ReadTimeout = TimeSpan.FromSeconds(15); // idle clients are dropped

    [DllImport("wtsapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool WTSQuerySessionInformationW(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out uint bytes);
    [DllImport("wtsapi32.dll")]
    private static extern void WTSFreeMemory(IntPtr ptr);

    /// <summary>Headless entry point for "BingeMote.exe --input-broker". Returns a process exit code.</summary>
    internal static int Run()
    {
        InputSender.BrokerRoutingEnabled = false; // the broker plays locally; never route back to itself
        Directory.CreateDirectory(Program.DataDir);
        var proc = Process.GetCurrentProcess();
        DiagnosticLog.Write($"input_broker=starting pid={proc.Id} session={proc.SessionId} user={Environment.UserName}");

        // rev12: first-instance claim, machine-wide. Claimed only here, in the console
        // session (Program.Main has already hopped or exited), so the relaunching
        // session-0 copy never holds it against the serving copy.
        using var claim = new Mutex(initiallyOwned: true, @"Global\BingeMoteInputBroker", out var createdNew);
        if (!createdNew)
        {
            DiagnosticLog.Write("input_broker=stand_down another broker holds the claim");
            return 0;
        }

        try { return RunAsync().GetAwaiter().GetResult(); }
        catch (Exception ex)
        {
            DiagnosticLog.Write($"input_broker=FAILED {ex.GetType().Name}: {ex.Message}");
            return 0; // helper falls back to local input
        }
    }

    private static async Task<int> RunAsync()
    {
        DiagnosticLog.Write($"input_broker=listening pipe={PipeName} instances={MaxPipeInstances} session={Process.GetCurrentProcess().SessionId}");
        var lastStats = DateTime.UtcNow;
        var pending = new List<Task<NamedPipeServerStream?>>();
        for (var i = 0; i < OutstandingAccepts; i++) pending.Add(AcceptAsync());
        while (true)
        {
            var done = await Task.WhenAny(pending);
            pending.Remove(done);
            pending.Add(AcceptAsync()); // replenish immediately: no listen gap
            if (done is { Status: TaskStatus.RanToCompletion, Result: { } pipe })
            {
                var clientPipe = pipe;
                _ = Task.Run(async () =>
                {
                    try { await HandleClientAsync(clientPipe); }
                    catch (Exception ex) { DiagnosticLog.Write($"input_broker client error {ex.GetType().Name}: {ex.Message}"); }
                    try { clientPipe.Dispose(); } catch { }
                });
            }
            if ((DateTime.UtcNow - lastStats).TotalMinutes >= 5)
            {
                lastStats = DateTime.UtcNow;
                DiagnosticLog.Write($"input_broker stats ops_applied={System.Threading.Interlocked.Read(ref _ops)}");
            }
        }
    }

    private static async Task<NamedPipeServerStream?> AcceptAsync()
    {
        try
        {
            var pipe = NewPipe();
            await pipe.WaitForConnectionAsync();
            return pipe;
        }
        catch (Exception ex)
        {
            DiagnosticLog.Write($"input_broker accept error {ex.GetType().Name}: {ex.Message}");
            await Task.Delay(250); // never spin on a persistent accept failure
            return null;
        }
    }

    private static NamedPipeServerStream NewPipe()
    {
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier("S-1-5-18"), // LocalSystem
            PipeAccessRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            PipeAccessRights.FullControl, AccessControlType.Allow));
        // rev12: grant RW to the console session's own user only, not every authenticated
        // user on the machine. Resolution failure falls back to the rev11 breadth (still
        // backed by the per-client PipePeer check) and is logged, never silent.
        var userSid = TrySessionUserSid(Process.GetCurrentProcess().SessionId);
        if (userSid != null)
        {
            security.AddAccessRule(new PipeAccessRule(userSid, PipeAccessRights.ReadWrite, AccessControlType.Allow));
        }
        else
        {
            DiagnosticLog.Write("input_broker=acl_fallback session user unresolved; granting authenticated users (client identity still verified per connection)");
            security.AddAccessRule(new PipeAccessRule(
                new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null),
                PipeAccessRights.ReadWrite, AccessControlType.Allow));
        }
        return NamedPipeServerStreamAcl.Create(
            PipeName, PipeDirection.InOut, MaxPipeInstances,
            PipeTransmissionMode.Byte, PipeOptions.Asynchronous, 4096, 4096, security);
    }

    /// <summary>SID of the user logged on to the given session, or null when it cannot be resolved.</summary>
    private static SecurityIdentifier? TrySessionUserSid(int sessionId)
    {
        string? Query(int infoClass)
        {
            if (!WTSQuerySessionInformationW(IntPtr.Zero, sessionId, infoClass, out var buf, out _) || buf == IntPtr.Zero)
                return null;
            try { return Marshal.PtrToStringUni(buf); }
            finally { WTSFreeMemory(buf); }
        }
        try
        {
            var name = Query(5 /* WTSUserName */);
            var domain = Query(7 /* WTSDomainName */);
            if (string.IsNullOrEmpty(name)) return null;
            var account = string.IsNullOrEmpty(domain) ? new NTAccount(name) : new NTAccount(domain, name);
            return (SecurityIdentifier)account.Translate(typeof(SecurityIdentifier));
        }
        catch { return null; }
    }

    private static async Task HandleClientAsync(NamedPipeServerStream pipe)
    {
        // rev12: the DACL is a filter, not the auth. Prove the client is our own helper.
        string client;
        try
        {
            client = PipePeer.VerifyClient(pipe);
        }
        catch (Exception ex)
        {
            DiagnosticLog.Write($"input_broker client REJECTED {ex.Message}");
            try
            {
                var w = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
                await w.WriteLineAsync("err unauthorized");
                await w.FlushAsync();
            }
            catch { }
            return;
        }
        DiagnosticLog.Write($"input_broker client connected ({client})");

        var limiter = new OpRateLimiter(OpsPerSecondPerClient);
        using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
        using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
        while (true)
        {
            string? line;
            try
            {
                line = await ReadLineBoundedAsync(reader, MaxLineLength).WaitAsync(ReadTimeout);
            }
            catch (TimeoutException)
            {
                DiagnosticLog.Write($"input_broker client idle timeout ({client})");
                break;
            }
            if (line == null) break;      // client closed
            if (line.Length == 0) continue;
            if (!limiter.Allow())
            {
                await writer.WriteLineAsync("err rate_limited");
                DiagnosticLog.Write($"input_broker rate_limited ({client})");
                break;
            }
            try
            {
                Apply(line);
                System.Threading.Interlocked.Increment(ref _ops);
                await writer.WriteLineAsync("ok");
            }
            catch (ArgumentException ex)
            {
                await writer.WriteLineAsync("err rejected:" + ex.Message);
            }
            catch (Exception ex)
            {
                await writer.WriteLineAsync("err " + ex.GetType().Name);
            }
        }
    }

    private static long _ops;

    /// <summary>Reads one '\n'-terminated line with a hard length cap; over-long lines
    /// fail the op instead of growing memory without bound. Returns null on a clean close.</summary>
    private static async Task<string?> ReadLineBoundedAsync(StreamReader reader, int max)
    {
        var sb = new StringBuilder(256);
        var buf = new char[256];
        while (true)
        {
            var n = await reader.ReadAsync(buf, 0, buf.Length);
            if (n == 0) return sb.Length == 0 ? null : sb.ToString();
            for (var i = 0; i < n; i++)
            {
                var c = buf[i];
                if (c == '\n') return sb.ToString(); // any pipelined remainder is discarded: our protocol is one op per reply
                if (c == '\r') continue;
                if (sb.Length >= max) throw new ArgumentException("line_too_long");
                sb.Append(c);
            }
        }
    }

    /// <summary>Validates (OpValidator) and plays one op. Shared protocol with InputBrokerClient; keep both in sync.</summary>
    internal static void Apply(string line)
    {
        var op = OpValidator.Parse(line);
        switch (op.Verb)
        {
            case "m": InputSender.Move(op.X, op.Y); break;
            case "b": InputSender.Button(op.Arg, op.Y != 0); break;
            case "s": InputSender.Scroll(op.X); break;
            case "z": InputSender.Zoom(op.X); break;
            case "t": InputSender.TypeText(op.Arg); break;
            case "k": InputSender.NamedKey(op.Arg); break;
        }
    }
}
