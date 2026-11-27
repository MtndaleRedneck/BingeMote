using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.RegularExpressions;

namespace BingeMoteHelper;

/// <summary>
/// The one-time firewall step. Windows needs admin rights to add a firewall rule, the
/// listen permission and the HTTPS identity, so the helper restarts itself once with the
/// UAC prompt, sets all three up, checks they are really there, and leaves a marker so it
/// never asks again.
/// </summary>
internal static class FirewallSetup
{
    /// <summary>First line of the firewall.ok marker. Bump when the required shape changes.</summary>
    internal const string MarkerVersion = "v3";   // v3: HTTPS listener reservation plus certificate identity and sslcert bindings

    /// <summary>Who the port's URL reservation belongs to.</summary>
    internal enum AclOwnership { None, Ours, Legacy, Foreign }

    /// <summary>The interactive-users SID the listen permission is granted to (see RunElevatedCore).</summary>
    private const string InteractiveSid = "S-1-5-4";

    /// <summary>Set when a state read failed in a way elevation cannot fix; the caller reports instead of re-prompting.</summary>
    internal static bool LastReadFailed;

    /// <summary>The unelevated "is everything in place" check. firewall.ok only records that this check passed; it holds no identity data and the identity marker is a separate, elevated-written file.</summary>
    internal static bool SetupIsCurrent()
    {
        if (!(RuleExists() && UrlAclExists())) return false;
        if (HelperTls.RuntimeReady(Program.HttpPort, out var why)) return true;
        // An unreadable store or binding (for example an unelevated read that is refused) cannot be fixed by elevating: report it as a read failure, never as a reason to prompt again.
        if (why.Contains("could not be read") || why.Contains("unreadable")) LastReadFailed = true;
        return false;
    }

    /// <summary>Runs inside the elevated copy. Returns the process exit code.</summary>
    public static int RunElevated(string markerPath)
    {
        var code = RunElevatedCore(markerPath);
        if (code != 0 && TlsStepDone)
        {
            // Whole-operation truth: the identity step succeeded and PERSISTS even though a later step did not.
            Program.Log("firewall setup: exit " + code + " after the secure identity step succeeded. Remaining installed from this run: certificate, marker, both sslcert bindings. Not completed: the later firewall/listen-permission step that failed. Setup is idempotent and can be run again.");
            Console.Error.WriteLine("Setup was only partly completed. The secure identity and its bindings were installed and remain; the firewall or listen permission step did not finish. Running setup again continues from here.");
        }
        return code;
    }

    private static bool TlsStepDone;

    private static int RunElevatedCore(string markerPath)
    {
        try
        {
            // TLS identity and both wildcard bindings, in the same elevated step (no new prompt), BEFORE any firewall or urlacl change, so a refusal leaves those untouched. Anything the planner
            // refuses (foreign binding, unreadable store or key, no marker for an existing binding) leaves everything as it was.
            var tlsExit = TlsSetup.Run(Program.HttpPort, true);
            if (tlsExit != 0)
            {
                Program.Log("firewall setup: secure identity setup did not complete (exit " + tlsExit + ").");
                Console.Error.WriteLine("Secure setup could not be completed, so BingeMote did not open the network port. No firewall rule or listen permission was changed by this run" + (tlsExit == 9 ? "; an earlier interrupted secure setup is still unresolved." : "; any partial secure-identity step was rolled back where it could be proven (see the log)."));
                return 8;
            }
            TlsStepDone = true;
            Run("netsh", $"advfirewall firewall delete rule name=\"{Program.LegacyFirewallRule}\""); // 1.x installs
            AddRule("TCP", Program.HttpPort);
            AddRule("UDP", Program.DiscoveryPort);
            // VLC's web remote needs inbound too: one Private-networks-only rule for vlc.exe.
            var vlc = Movies.FindVlc();
            if (vlc != null)
            {
                Run("netsh", $"advfirewall firewall delete rule name=\"{Program.VlcFirewallRule}\"");
                var vcode = Run("netsh", $"advfirewall firewall add rule name=\"{Program.VlcFirewallRule}\" dir=in action=allow program=\"{vlc}\" profile=private");
                if (vcode != 0) Console.Error.WriteLine("The VLC firewall rule could not be added (the phone may still reach VLC if Windows already allows it).");
            }
            else Console.Error.WriteLine("VLC is not installed - its firewall rule was skipped.");
            // Report, never touch: any broad vlc.exe rules from elsewhere (all networks, or
            // all programs) are listed for the helper log. BingeMote only adds its own
            // Private-only rule above.
            try { File.WriteAllText(VlcInventoryPath(), InventoryVlcRules()); } catch { }
            // Let the helper (a normal, non-admin app) listen on its port.
            Run("netsh", "http delete urlacl url=https://+:" + Program.HttpPort + "/");
            // Grant the HTTP.sys URL reservation to interactively signed-in users
            // (S-1-5-4) instead of Everyone. What this actually does: HTTP.sys will
            // register this URL for any interactive user, so the helper (a normal,
            // non-admin app) may listen. Non-interactive identities (services, other
            // accounts' processes) are no longer granted THIS reservation by BingeMote -
            // that is partial hardening, not exclusive ownership: another interactive user
            // can still register the same URL, and anyone can still bind the raw TCP
            // port when the helper is not listening (URLACL governs HTTP.sys only).
            // INTERACTIVE rather than this (elevated) account's SID: a standard user
            // elevating with admin credentials still runs the helper as themselves, and a
            // per-account grant would lock their helper out.
            var acl = Run("netsh", "http add urlacl url=https://+:" + Program.HttpPort + "/ sddl=D:(A;;GX;;;S-1-5-4)");
            if (acl != 0 || !UrlAclExists())
            {
                Program.Log("firewall setup: the listen permission could not be added (netsh exit " + acl + ").");
                Console.Error.WriteLine("partial=true tls=ready firewall=" + (RuleExists() ? "ready" : "failed") + " urlacl=failed");
                Console.Error.WriteLine("The listen permission could not be added.");
                return 3;
            }
            if (!RuleExists())
            {
                Console.Error.WriteLine("The firewall rule could not be verified after adding it.");
                return 2;
            }
            // Old http:// reservation: deleted only when positively ours or legacy, verified gone; a foreign or unreadable one is left.
            try
            {
                var oldOwn = ClassifyOldHttpUrlAcl(InteractiveSid);
                if (UrlAclMigrationPlanner.PlanOld(oldOwn) == UrlAclMigrationPlanner.Action.DeleteOldHttpReservation)
                {
                    Run("netsh", "http delete urlacl url=http://+:" + Program.HttpPort + "/");
                    if (QueryUrlAcl("http://+:" + Program.HttpPort + "/").Found) Program.Log("firewall setup: the old http reservation could not be removed; it remains (plain http is not served).");
                }
                else Program.Log("firewall setup: old http reservation: " + oldOwn + "; left alone.");
            }
            catch (Exception ex) { Program.Log("firewall setup: old http reservation check failed (" + ex.GetType().Name + "); left alone."); }
            Directory.CreateDirectory(Path.GetDirectoryName(markerPath)!);
            File.WriteAllText(markerPath, MarkerVersion + "\n" + DateTime.UtcNow.ToString("O"));
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("Firewall setup failed: " + ex.Message);
            return 1;
        }
    }

    /// <summary>Explains the prompt, then relaunches elevated for the one-time setup. Non-blocking.</summary>
    public static void ExplainAndRelaunch()
    {
        var answer = MessageBox.Show(
            "One Windows permission, then setup is done.\n\n" +
            "BingeMote needs to let your phone reach this PC on your home network. Windows will now ask for administrator permission once - it is used only to set up the home (Private) network firewall rules, the Windows listen permission and the secure pairing identity. Click Yes.",
            "BingeMote setup",
            MessageBoxButtons.OKCancel,
            MessageBoxIcon.Information);
        if (answer != DialogResult.OK) return;

        try
        {
            var exe = Environment.ProcessPath!;
            var psi = new ProcessStartInfo(exe, "--install-firewall")
            {
                Verb = "runas",
                UseShellExecute = true,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            var p = Process.Start(psi);
            if (p == null || !p.WaitForExit(60_000) || p.ExitCode != 0 || !SetupIsCurrent())
                throw new InvalidOperationException("Setup could not be verified.");
        }
        catch
        {
            // The user said no at the UAC prompt. Without the rule and listen permission
            // the helper cannot accept connections, so it will close and offer again next launch.
            MessageBox.Show(
                "Setup did not complete, so the phone cannot reach this PC yet. BingeMote will now close and offer to set up again next time you open it.",
                "BingeMote setup",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
        }
    }

    private static string VlcInventoryPath() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BingeMote", "vlc-firewall-inventory.txt");

    /// <summary>One line per existing vlc.exe firewall rule: name, profile, action, enabled, path.</summary>
    private static string InventoryVlcRules()
    {
        var psi = new ProcessStartInfo("powershell",
            """
            -NoProfile -NonInteractive -Command "Get-NetFirewallRule -ErrorAction SilentlyContinue | ForEach-Object { $af = $_ | Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue; if ($af -and $af.Program -like '*vlc.exe') { $_.DisplayName+'|'+$_.Profile+'|'+$_.Action+'|'+$_.Enabled+'|'+$af.Program } }"
            """)
        {
            RedirectStandardOutput = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var p = Process.Start(psi)!;
        if (!p.WaitForExit(20000)) { try { p.Kill(); } catch { } return ""; }
        return p.StandardOutput.ReadToEnd();
    }

    /// <summary>Log the saved vlc.exe rule inventory (called once after setup ran).</summary>
    public static void LogVlcRuleInventory()
    {
        try
        {
            var path = VlcInventoryPath();
            if (!File.Exists(path)) return;
            var lines = File.ReadAllText(path).Trim();
            if (lines.Length == 0) { Program.Log("vlc.exe firewall rules on this PC: none besides what BingeMote adds."); return; }
            foreach (var line in lines.Split('\n', StringSplitOptions.RemoveEmptyEntries))
                Program.Log("vlc.exe firewall rule present: " + line.Trim());
        }
        catch { }
    }

    private static void AddRule(string protocol, int port)
    {
        Run("netsh", $"advfirewall firewall delete rule name=\"{Program.FirewallRule}\" protocol={protocol} localport={port}");
        // No remote-IP scoping here: the phone app supports typing the PC's address
        // directly, which also works across VPN/routed networks outside the local subnet.
        var code = Run("netsh", $"advfirewall firewall add rule name=\"{Program.FirewallRule}\" dir=in action=allow protocol={protocol} localport={port} profile=private");
        if (code != 0) throw new InvalidOperationException($"netsh add rule exited {code}");
    }

    /// <summary>The exact helper URL must have only the intended interactive-user grant.
    /// A different URL containing the same port, or an Everyone grant, is not sufficient.</summary>
    internal static bool UrlAclExists()
    {
        try
        {
            var psi = new ProcessStartInfo("netsh", "http show urlacl url=https://+:" + Program.HttpPort + "/")
            {
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var p = Process.Start(psi)!;
            var read = p.StandardOutput.ReadToEndAsync();
            if (!p.WaitForExit(10000)) { try { p.Kill(); } catch { } return false; }
            return p.ExitCode == 0 && HasInteractiveOnlyGrant(read.GetAwaiter().GetResult());
        }
        catch { return false; }
    }

    internal static bool HasInteractiveOnlyGrant(string output)
    {
        // Querying the exact URL avoids localized Reserved URL labels and substring matches.
        // SDDL itself has locale-independent syntax; inspect its parsed ACE, not account names.
        var match = Regex.Match(output, @"(?m)^.*?:[^\S\r\n]*(D:[^\r\n]+)\r?$");
        if (!match.Success) return false;
        try
        {
            var descriptor = new RawSecurityDescriptor(match.Groups[1].Value.Trim());
            var acl = descriptor.DiscretionaryAcl;
            return acl is { Count: 1 } && acl[0] is CommonAce ace &&
                ace.AceQualifier == AceQualifier.AccessAllowed &&
                ace.AceFlags == AceFlags.None && !ace.IsCallback &&
                ace.SecurityIdentifier == new SecurityIdentifier(WellKnownSidType.InteractiveSid, null) &&
                ace.AccessMask == unchecked((int)0x20000000); // GENERIC_EXECUTE (GX)
        }
        catch { return false; }
    }

    /// <summary>Ownership of the OLD http:// reservation, for migration planning only (UrlAclMigrationPlanner).</summary>
    internal static AclOwnership ClassifyOldHttpUrlAcl(string? sid)
    {
        var (found, sddl) = QueryUrlAcl("http://+:" + Program.HttpPort + "/");
        return found ? ClassifySddl(sddl, sid) : AclOwnership.None;
    }

    /// <summary>Exact-match classification of a reservation's SDDL. A reservation that
    /// cannot be read or parsed is Foreign, never None (an unparsed block must never be
    /// deleted as if it were absent).</summary>
    internal static AclOwnership ClassifySddl(string? sddl, string? sid)
    {
        if (string.IsNullOrWhiteSpace(sddl)) return AclOwnership.Foreign;
        string normalized;
        try
        {
            normalized = new RawSecurityDescriptor(sddl)
                .GetSddlForm(AccessControlSections.All);
        }
        catch { return AclOwnership.Foreign; }
        if (sid != null)
        {
            try
            {
                var ours = new RawSecurityDescriptor("D:(A;;GX;;;" + sid + ")")
                    .GetSddlForm(AccessControlSections.All);
                if (normalized == ours) return AclOwnership.Ours;
            }
            catch { }
        }
        try
        {
            var legacy = new RawSecurityDescriptor("D:(A;;GX;;;WD)")
                .GetSddlForm(AccessControlSections.All);
            if (normalized == legacy) return AclOwnership.Legacy;
        }
        catch { }
        return AclOwnership.Foreign;
    }

    /// <summary>Structured http.sys query of one reservation (no netsh text parsing).
    /// Any read error is (true, null) = present-but-unreadable, classified Foreign by the caller.</summary>
    private static (bool Found, string? Sddl) QueryUrlAcl(string urlPrefix)
    {
        // http.h lifecycle: HttpInitialize with HTTP_INITIALIZE_CONFIG is required
        // before the query, HttpTerminate after.
        uint initRc = HttpInitialize(new HttpApiVersion { Major = 1, Minor = 0 }, HttpInitializeConfig, IntPtr.Zero);
        if (initRc != 0)
        {
            LastReadFailed = true;
            Program.Log("http.sys init failed (win32 " + initRc + "); treating the reservation as unreadable/foreign.");
            return (true, null);
        }
        try
        {
            var query = new HttpServiceConfigUrlAclQuery
            {
                QueryDesc = HttpServiceConfigQueryExact,
                KeyDesc = new HttpServiceConfigUrlAclKey { UrlPrefix = urlPrefix },
                Token = 0,
            };
            // The record's strings live inside the output buffer past the struct, so
            // size for content: on ERROR_INSUFFICIENT_BUFFER/ERROR_MORE_DATA the API
            // reports the needed size through pReturnLength (http.h). Any other error
            // is a READ error - never "no reservation".
            var outSize = 256;
            for (var attempt = 0; attempt < 3; attempt++)
            {
                var buf = System.Runtime.InteropServices.Marshal.AllocHGlobal(outSize);
                try
                {
                    uint rc = HttpQueryServiceConfiguration(IntPtr.Zero, HttpServiceConfigUrlAclQueryId,
                        ref query, System.Runtime.InteropServices.Marshal.SizeOf(query), buf, outSize,
                        out int needed, IntPtr.Zero);
                    if (rc == ErrorInsufficientBuffer || rc == ErrorMoreData)
                    {
                        if (needed > outSize) { outSize = needed; continue; }
                        LastReadFailed = true;
                        return (true, null);
                    }
                    // No matching record: ERROR_FILE_NOT_FOUND (2) ONLY. Every other
                    // nonzero code is a READ error, i.e. foreign.
                    if (rc == ErrorFileNotFound) return (false, null);
                    if (rc != 0)
                    {
                        LastReadFailed = true;
                        Program.Log("urlacl query failed (win32 " + rc + "); treating the reservation as unreadable/foreign.");
                        return (true, null);
                    }
                    var set = System.Runtime.InteropServices.Marshal.PtrToStructure<HttpServiceConfigUrlAclSet>(buf);
                    return (true, set.Param.StringSecurityDescriptor);
                }
                finally { System.Runtime.InteropServices.Marshal.FreeHGlobal(buf); }
            }
            LastReadFailed = true;
            return (true, null);
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException or System.Runtime.InteropServices.MarshalDirectiveException)
        {
            LastReadFailed = true;
            return (true, null);
        }
        finally { HttpTerminate(HttpInitializeConfig, IntPtr.Zero); }
    }

    private const int HttpServiceConfigUrlAclQueryId = 2; // HttpServiceConfigUrlAclInfo
    private const int HttpServiceConfigQueryExact = 0;
    private const uint HttpInitializeConfig = 0x2;
    private const uint ErrorFileNotFound = 2;
    private const uint ErrorInsufficientBuffer = 122;
    private const uint ErrorMoreData = 234;

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential, CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private struct HttpServiceConfigUrlAclKey
    {
        [System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)]
        public string UrlPrefix;
    }

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential, CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private struct HttpServiceConfigUrlAclQuery
    {
        public int QueryDesc;
        public HttpServiceConfigUrlAclKey KeyDesc;
        public int Token;
    }

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential, CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private struct HttpServiceConfigUrlAclParam
    {
        [System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)]
        public string StringSecurityDescriptor;
    }

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential, CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private struct HttpServiceConfigUrlAclSet
    {
        public HttpServiceConfigUrlAclKey KeyDesc;
        public HttpServiceConfigUrlAclParam Param;
    }

    [System.Runtime.InteropServices.DllImport("httpapi.dll", ExactSpelling = true)]
    private static extern uint HttpQueryServiceConfiguration(IntPtr serviceHandle, int configId,
        ref HttpServiceConfigUrlAclQuery input, int inputLength, IntPtr output, int outputLength,
        out int returnLength, IntPtr overlapped);

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
    private struct HttpApiVersion { public ushort Major; public ushort Minor; }

    [System.Runtime.InteropServices.DllImport("httpapi.dll", ExactSpelling = true)]
    private static extern uint HttpInitialize(HttpApiVersion version, uint flags, IntPtr reserved);

    [System.Runtime.InteropServices.DllImport("httpapi.dll", ExactSpelling = true)]
    private static extern uint HttpTerminate(uint flags, IntPtr reserved);

    internal static bool RuleExists()
    {
        var psi = new ProcessStartInfo("netsh", $"advfirewall firewall show rule name=\"{Program.FirewallRule}\"")
        {
            RedirectStandardOutput = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var p = Process.Start(psi)!;
        var output = p.StandardOutput.ReadToEnd();
        p.WaitForExit();
        return output.Contains(Program.FirewallRule) && !output.Contains("No rules match");
    }

    private static int Run(string file, string args)
    {
        var psi = new ProcessStartInfo(file, args) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true };
        using var p = Process.Start(psi)!;
        p.WaitForExit();
        return p.ExitCode;
    }
}
