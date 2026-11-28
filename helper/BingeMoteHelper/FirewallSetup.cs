using System.Text;
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
        if (!(RulesExist() && UrlAclExists())) return false;
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
            // New-named, create-only rules (see EnsureRules); the pre-1.10 "BingeMote"/"BingeMote Helper" rules are neither read nor deleted.
            EnsureRules();
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
            // Let the helper (a normal, non-admin app) listen on its port, planned from the
            // reservation's classified ownership (HttpsReservationPlanner): absent -> add; already
            // exactly ours -> keep (no delete window); anything else -> refuse loudly and leave it.
            // Never delete+re-add over a reservation that is not provably ours.
            var httpsOwn = ClassifyHttpsUrlAcl(InteractiveSid);
            var httpsPlan = HttpsReservationPlanner.Plan(httpsOwn);
            if (httpsPlan == HttpsReservationPlanner.Action.Refuse)
            {
                Program.Log("firewall setup: an HTTPS reservation for this port exists that is not provably BingeMote's (class " + httpsOwn + "); left untouched.");
                Console.Error.WriteLine("partial=true tls=ready firewall=" + (RulesExist() ? "ready" : "failed") + " urlacl=foreign");
                Console.Error.WriteLine("The listen permission for this port is held by a reservation BingeMote did not create; it was left untouched. Setup cannot finish until it is examined.");
                return 3;
            }
            if (httpsPlan == HttpsReservationPlanner.Action.Add)
            {
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
                    Console.Error.WriteLine("partial=true tls=ready firewall=" + (RulesExist() ? "ready" : "failed") + " urlacl=failed");
                    Console.Error.WriteLine("The listen permission could not be added.");
                    return 3;
                }
            }
            if (!RulesExist())
            {
                Console.Error.WriteLine("The firewall rules could not be verified after adding it.");
                return 2;
            }
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

    /// <summary>Adds each BingeMote rule that is absent. Semantics, exactly: for each of four fixed names the rule is read first; no rule of that name -> add it; exactly one rule that matches the expected parameters -> keep it; anything else (a duplicate, a disabled or differently-scoped rule of that name) -> FAIL setup and change nothing. This is read-then-add, NOT an atomic create-only primitive: two concurrent setups could both add and leave duplicates, which RulesExist then refuses to accept. Nothing is ever deleted or rewritten. Private rules: any remote address. Public rules: remote must be on the PC's own subnet (LocalSubnet4). No Domain rule, no program scoping (HTTP.sys serves the port, so a program rule would not match).</summary>
    private static void EnsureRules()
    {
        foreach (var r in ExpectedRules)
        {
            var state = ReadAll()[r.Name];
            if (state == RuleState.Absent)
            {
                int code;
                if (r.Profile == "Private")
                    code = Run("netsh", $"advfirewall firewall add rule name=\"{r.Name}\" dir=in action=allow protocol={r.Protocol} localport={r.Port} profile=private");
                else
                    code = Run("powershell", "-NoProfile -NonInteractive -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(
                        $"New-NetFirewallRule -DisplayName '{r.Name}' -Direction Inbound -Action Allow -Protocol {r.Protocol} -LocalPort {r.Port} -Profile Public -RemoteAddress {r.Remote} -ErrorAction Stop | Out-Null")));
                if (code != 0) throw new InvalidOperationException($"adding firewall rule '{r.Name}' exited {code}");
                state = ReadAll()[r.Name];
            }
            if (state != RuleState.Exact)
            {
                Program.Log($"firewall setup: rule '{r.Name}' is {state} (expected exactly one enabled inbound allow rule, profile {r.Profile}, {r.Protocol} {r.Port}, remote {r.Remote}, any program/service); left untouched.");
                throw new InvalidOperationException($"firewall rule '{r.Name}' is {state}; not changed");
            }
        }
    }

    internal sealed record RuleSpec(string Name, string Profile, string Protocol, int Port, string Remote);
    internal enum RuleState { Absent, Exact, Mismatch, Unreadable }

    internal static readonly RuleSpec[] ExpectedRules =
    {
        new(Program.RuleTcpPrivate, "Private", "TCP", Program.HttpPort, "Any"),
        new(Program.RuleUdpPrivate, "Private", "UDP", Program.DiscoveryPort, "Any"),
        new(Program.RuleTcpPublic, "Public", "TCP", Program.HttpPort, "LocalSubnet4"),
        new(Program.RuleUdpPublic, "Public", "UDP", Program.DiscoveryPort, "LocalSubnet4"),
    };

    /// <summary>Reads the four expected rules as effective firewall objects (not netsh text) in ONE query. Absent means only this: the full, unfiltered Get-NetFirewallRule enumeration SUCCEEDED and no object has that exact DisplayName (an empty enumeration is not an error; filtering is done in the script, so there is no reliance on the cmdlet's "not found" error id). Any failure of the enumeration or of a per-rule filter read (access denied, service down, timeout, bad output) leaves the rule Unreadable, never Absent. Unreadable is never "current" and never triggers an add.</summary>
    internal static Dictionary<string, RuleState> ReadAll()
    {
        var result = ExpectedRules.ToDictionary(r => r.Name, _ => RuleState.Unreadable);
        var names = string.Join(",", ExpectedRules.Select(r => "'" + r.Name + "'"));
        var script =
            "$ErrorActionPreference='Stop'; try { $all=@(Get-NetFirewallRule) } catch { 'ERR'; exit 0 }; " +
            "foreach ($n in @(" + names + ")) { try { $r=@($all | Where-Object { $_.DisplayName -ceq $n }); " +
            "if ($r.Count -eq 0) { $n+'|ABSENT'; continue }; if ($r.Count -ne 1) { $n+'|DUP'; continue }; $x=$r[0]; " +
            "$p=$x|Get-NetFirewallPortFilter; $a=$x|Get-NetFirewallAddressFilter; $ap=$x|Get-NetFirewallApplicationFilter; $sv=$x|Get-NetFirewallServiceFilter; " +
            "$n+'|ONE|'+$x.Enabled+'|'+$x.Direction+'|'+$x.Action+'|'+$x.Profile+'|'+$p.Protocol+'|'+($p.LocalPort -join ',')+'|'+($p.RemotePort -join ',')+'|'+($a.RemoteAddress -join ',')+'|'+($a.LocalAddress -join ',')+'|'+$ap.Program+'|'+$sv.Service+'|'+$x.EdgeTraversalPolicy } catch { $n+'|ERR' } }";
        var psi = new ProcessStartInfo("powershell", "-NoProfile -NonInteractive -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(script)))
        {
            RedirectStandardOutput = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        try
        {
            using var p = Process.Start(psi)!;
            var read = p.StandardOutput.ReadToEndAsync();
            if (!p.WaitForExit(60000)) { try { p.Kill(); } catch { } return result; }
            if (p.ExitCode != 0) return result;
            foreach (var raw in read.GetAwaiter().GetResult().Split('\n', StringSplitOptions.RemoveEmptyEntries))
            {
                var f = raw.Trim().Split('|');
                if (f.Length < 2) continue;
                var spec = ExpectedRules.FirstOrDefault(r => r.Name == f[0]);
                if (spec == null) continue;
                if (f[1] == "ABSENT") result[spec.Name] = RuleState.Absent;
                else if (f[1] == "DUP") result[spec.Name] = RuleState.Mismatch;
                else if (f[1] == "ONE" && f.Length == 14)
                {
                    bool Is(string got, string want) => string.Equals(got.Trim(), want, StringComparison.OrdinalIgnoreCase);
                    var ok = Is(f[2], "True") && Is(f[3], "Inbound") && Is(f[4], "Allow") && Is(f[5], spec.Profile) && Is(f[6], spec.Protocol) &&
                        Is(f[7], spec.Port.ToString()) && Is(f[8], "Any") && Is(f[9], spec.Remote) && Is(f[10], "Any") && Is(f[11], "Any") && Is(f[12], "Any") && Is(f[13], "Block");
                    result[spec.Name] = ok ? RuleState.Exact : RuleState.Mismatch;
                }
            }
        }
        catch { }
        return result;
    }

    /// <summary>True only when every expected rule is exactly one rule with exactly the expected scope. Disabled, duplicated, broader, narrower or unreadable is NOT current.</summary>
    internal static bool RulesExist() => ReadAll().Values.All(v => v == RuleState.Exact);

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

    /// <summary>Ownership of the HTTPS reservation, for setup planning (HttpsReservationPlanner) and uninstall cleanup (HttpsReservationCleanupPlanner).</summary>
    internal static AclOwnership ClassifyHttpsUrlAcl(string? sid)
    {
        var (found, sddl) = QueryUrlAcl("https://+:" + Program.HttpPort + "/");
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

    /// <summary>Elevated uninstall cleanup, invoked by the uninstaller via --uninstall-tls while
    /// the exe still exists: the HTTPS listen permission and the owned TLS transport bindings go
    /// (the installer's remove-what-you-add contract, docs/details.md); the identity (certificate,
    /// key, marker) is left untouched, matching TlsSetup.Remove's existing transport/identity split.
    /// Every removal is proven first (HttpsReservationCleanupPlanner): the reservation is deleted
    /// only when the identity marker is present AND the reservation's SDDL is exactly the
    /// interactive-users grant setup writes AND no interrupted-setup journal is pending or corrupt
    /// (the journal holds ALL TLS transport state, bindings and reservation alike, so crash
    /// recovery still sees the whole picture); anything else stays and is logged. Idempotent.</summary>
    public static int RunUninstallCleanup()
    {
        var (ms, _) = TlsMarkerStore.Read();
        var (js, _) = TlsJournalStore.Read();
        var own = ClassifyHttpsUrlAcl(InteractiveSid);
        if (own == AclOwnership.None)
        {
            Program.Log("uninstall cleanup: no HTTPS reservation present.");
        }
        else if (HttpsReservationCleanupPlanner.Plan(true, ms, own, js) == HttpsReservationCleanupPlanner.Action.Delete)
        {
            if (Run("netsh", "http delete urlacl url=https://+:" + Program.HttpPort + "/") != 0)
                Program.Log("uninstall cleanup: the owned HTTPS listen permission could not be deleted; left in place.");
        }
        else
        {
            Program.Log("uninstall cleanup: HTTPS reservation left (marker " + ms + ", class " + own + ", journal " + js + "): not provably removable.");
        }
        return TlsSetup.Remove(Program.HttpPort);
    }

    private static int Run(string file, string args)
    {
        var psi = new ProcessStartInfo(file, args) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true };
        using var p = Process.Start(psi)!;
        p.WaitForExit();
        return p.ExitCode;
    }
}
