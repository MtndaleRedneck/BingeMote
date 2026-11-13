using System.Diagnostics;
using System.Globalization;
using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;

namespace BingeMoteHelper;

/// <summary>
/// rev11: SYSTEM-level input broker (the "copy how Unified Remote ticks" fix).
///
/// Why: on Ezzy's PC an elevated Realtek SwUSB dialog grabs foreground on the bare
/// desktop, and UIPI silently discards SendInput/SetCursorPos from every lower
/// integrity level (Unified Remote avoids this whole class with its signed uvhid.sys
/// virtual HID driver; our own signed driver needs an EV cert + business, ruled out).
/// This broker is the same exe started by Task Scheduler as SYSTEM in the interactive
/// session (the psexec -s -i pattern, no service, no driver, no signing): nothing in
/// the session outranks SYSTEM, so injected input is never UIPI-filtered.
///
/// The per-user helper stays at medium IL and just forwards each input op over a local
/// named pipe; when the broker is absent (old installs) the helper falls back to its
/// original local SendInput path, so nothing regresses.
///
/// SECURITY (for review): any signed-in local process can ask this pipe to inject
/// input - a deliberate local UIPI crossing, the same exposure class as installing
/// Interception. The pipe carries input ops only (move/button/scroll/zoom/type/key),
/// no shell, no file access; ACL is SYSTEM/Administrators full, authenticated users
/// read+write.
/// </summary>
internal static class InputBroker
{
    internal const string PipeName = "BingeMoteInputBroker";

    /// <summary>Headless entry point for "BingeMote.exe --input-broker". Returns a process exit code.</summary>
    internal static int Run()
    {
        InputSender.BrokerRoutingEnabled = false; // the broker plays locally; never route back to itself
        Directory.CreateDirectory(Program.DataDir);
        var proc = Process.GetCurrentProcess();
        DiagnosticLog.Write($"input_broker=starting pid={proc.Id} session={proc.SessionId} user={Environment.UserName}");

        // Claim the name with instance 1; a second broker exits quietly instead of splitting input.
        NamedPipeServerStream first;
        try
        {
            first = NewPipe();
        }
        catch (Exception ex)
        {
            DiagnosticLog.Write($"input_broker=FAILED pipe_create {ex.GetType().Name}: {ex.Message}");
            return 0;
        }

        DiagnosticLog.Write($"input_broker=listening pipe={PipeName} session={proc.SessionId}");
        var ops = 0L;
        var lastStats = DateTime.UtcNow;
        while (true)
        {
            NamedPipeServerStream pipe;
            try { pipe = first; }
            catch { break; }
            try
            {
                pipe.WaitForConnection();
            }
            catch { break; }
            DiagnosticLog.Write("input_broker client connected");
            try
            {
                HandleClient(pipe, ref ops);
            }
            catch (Exception ex)
            {
                DiagnosticLog.Write($"input_broker client error {ex.GetType().Name}: {ex.Message}");
            }
            try { pipe.Dispose(); } catch { }
            first = NewPipe();
            if ((DateTime.UtcNow - lastStats).TotalMinutes >= 5)
            {
                lastStats = DateTime.UtcNow;
                DiagnosticLog.Write($"input_broker stats ops_applied={ops}");
            }
        }
        return 0;
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
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null),
            PipeAccessRights.ReadWrite, AccessControlType.Allow));
        return NamedPipeServerStreamAcl.Create(
            PipeName, PipeDirection.InOut, 1,
            PipeTransmissionMode.Byte, PipeOptions.None, 4096, 4096, security);
    }

    /// <summary>One line in, one op played, one line out ("ok" / "err ..."). The client closes when done.</summary>
    private static void HandleClient(NamedPipeServerStream pipe, ref long ops)
    {
        using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
        using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
        string? line;
        while ((line = reader.ReadLine()) != null)
        {
            try
            {
                Apply(line);
                ops++;
                writer.WriteLine("ok");
            }
            catch (Exception ex)
            {
                writer.WriteLine("err " + ex.GetType().Name);
            }
        }
    }

    /// <summary>Parses and plays one op. Shared protocol with InputBrokerClient; keep both in sync.</summary>
    internal static void Apply(string line)
    {
        var sp = line.IndexOf(' ');
        var verb = sp < 0 ? line : line[..sp];
        var rest = sp < 0 ? "" : line[(sp + 1)..];
        switch (verb)
        {
            case "m": // move: m <dx float> <dy float>
            {
                var parts = rest.Split(' ');
                InputSender.Move(
                    float.Parse(parts[0], CultureInfo.InvariantCulture),
                    float.Parse(parts[1], CultureInfo.InvariantCulture));
                break;
            }
            case "b": // button: b left|right down|up
            {
                var parts = rest.Split(' ');
                InputSender.Button(parts[0], parts[1] == "down");
                break;
            }
            case "s": InputSender.Scroll(float.Parse(rest, CultureInfo.InvariantCulture)); break;
            case "z": InputSender.Zoom(float.Parse(rest, CultureInfo.InvariantCulture)); break;
            case "t": InputSender.TypeText(Uri.UnescapeDataString(rest)); break;
            case "k": InputSender.NamedKey(rest); break;
            default: throw new InvalidOperationException("unknown op " + verb);
        }
    }
}
