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
        // Tri-state: an unreadable firewall object is a read failure that elevation cannot fix (same contract as the TLS reads below), not a reason to prompt again.
        var rules = ReadAll();
        if (rules.Values.Any(v => v == RuleState.Unreadable)) { LastReadFailed = true; return false; }
        if (!(rules.Values.All(v => v == RuleState.Exact) && UrlAclExists())) return false;
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
            var createdRules = new List<RuleEntry>();
            var uncertainRules = new List<UncertainEntry>();
            try { EnsureRules(createdRules, uncertainRules); }
            finally
            {
                // Journal what THIS run created, and keep any create attempt whose outcome is uncertain as recovery evidence (never delete authority), even if a later rule fails.
                if ((createdRules.Count > 0 || uncertainRules.Count > 0) && !LifecycleJournalStore.Merge(createdRules, false, null, uncertainRules))
                    Program.Log("lifecycle journal could NOT be written. Created: " + string.Join("; ", createdRules.Select(e => e.DisplayName + " key " + e.Key))
                        + ". Uncertain: " + string.Join("; ", uncertainRules.Select(u => u.DisplayName + " key " + u.Key + " (" + u.Outcome + ")"))
                        + ". These are recorded here only; setup and uninstall will never delete them automatically (possible orphan rules).");
            }
            // VLC's web remote is dialled by the phone directly (VLC's own http port, default 8080, from vlcrc), so it needs inbound too. Two NEW journaled rules, same create-only / Exact-read / journal path as above, never a delete or
            // rewrite of anything: a Private rule scoped to vlc.exe (any port, as the old rule was) and a Public rule scoped to vlc.exe + TCP + the vlcrc http port + LocalSubnet4. A pre-existing legacy rule named
            // "BingeMote VLC" (older builds) is neither read, changed nor removed; it stays active and is not removed by uninstall (it never was). A failure here does not fail the main setup.
            var vlc = Movies.FindVlc();
            if (vlc != null)
            {
                var vcreated = new List<RuleEntry>();
                var vuncertain = new List<UncertainEntry>();
                try { EnsureRules(VlcSpecs(vlc, VlcHttpPort()), vcreated, vuncertain, false); }
                catch (Exception ex) { Program.Log("VLC firewall rules: " + ex.Message); }
                if ((vcreated.Count > 0 || vuncertain.Count > 0) && !LifecycleJournalStore.Merge(vcreated, false, null, vuncertain))
                    Program.Log("lifecycle journal could NOT be written for the VLC rules. Created: " + string.Join("; ", vcreated.Select(e => e.DisplayName + " key " + e.Key))
                        + ". Uncertain: " + string.Join("; ", vuncertain.Select(u => u.DisplayName + " key " + u.Key + " (" + u.Outcome + ")")) + ". Recorded here only; never deleted automatically.");
                if (vuncertain.Count > 0 || vcreated.Count < 2 && !VlcRulesAllExact(vlc)) Console.Error.WriteLine("The VLC firewall rules were not all added (the phone may not reach VLC; see the helper log).");
            }
            else Console.Error.WriteLine("VLC is not installed - its firewall rules were skipped. Install VLC first, then run BingeMote setup again.");
            // Report, never touch: any broad vlc.exe rules from elsewhere (all networks, or
            // all programs) are listed for the helper log. BingeMote only adds its own
            // two rules above.
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
                    if (acl != 0) LifecycleJournalStore.Merge(Array.Empty<RuleEntry>(), false, null, null, "netsh add urlacl exited " + acl + "; outcome unknown (recovery evidence only, never delete authority)");
                    Program.Log("firewall setup: the listen permission could not be added (netsh exit " + acl + ").");
                    Console.Error.WriteLine("partial=true tls=ready firewall=" + (RulesExist() ? "ready" : "failed") + " urlacl=failed");
                    Console.Error.WriteLine("The listen permission could not be added.");
                    return 3;
                }
                // Presence plus interactive-only scope is not an exact read-back. The ownership witness needs the exact classification; otherwise the attempt is recovery evidence only.
                if (ClassifyHttpsUrlAcl(InteractiveSid) == AclOwnership.Ours)
                    LifecycleJournalStore.Merge(Array.Empty<RuleEntry>(), true, null);
                else
                {
                    Program.Log("firewall setup: the listen permission was added (exit 0) but its exact SDDL read-back is not the expected one; journaled as UNCERTAIN, not as ours.");
                    LifecycleJournalStore.Merge(Array.Empty<RuleEntry>(), false, null, null, "netsh add urlacl exited 0 but exact SDDL classification was not Ours (recovery evidence only, never delete authority)");
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

    /// <summary>Shown instead of the admin prompt when setup state could not be read (LastReadFailed). Logs the fact; asks for nothing.</summary>
    public static void ReportReadFailure()
    {
        Program.Log("firewall setup: the firewall or secure-setup state could not be read; not asking for administrator permission because that would not fix a read failure.");
        MessageBox.Show(
            "BingeMote could not read its Windows setup state, so it cannot tell whether setup is complete. Asking for administrator permission would not fix that, so it will not ask. BingeMote will close; see helper.log in %LOCALAPPDATA%\\BingeMote for details.",
            "BingeMote setup",
            MessageBoxButtons.OK,
            MessageBoxIcon.Warning);
    }

    /// <summary>Explains the prompt, then relaunches elevated for the one-time setup. Non-blocking.</summary>
    public static void ExplainAndRelaunch()
    {
        var answer = MessageBox.Show(
            "One Windows permission, then setup is done.\n\n" +
            "BingeMote needs to let your phone reach this PC over Wi-Fi. Windows will now ask for administrator permission once - it is used only to set up the firewall rules, the Windows listen permission and the secure pairing identity. Click Yes.\n\n" +
            "BingeMote adds rules for networks Windows calls Private (any device on that network) and for networks Windows calls Public (only devices on this PC's own subnet), for itself and, if VLC is installed, for VLC's web port. It does not change your network type. Your phone and PC must be on the same Wi-Fi. Other Windows firewall rules on this PC may allow more or less, and some Wi-Fi (guest or hotel networks) blocks device-to-device traffic no matter what; BingeMote cannot change that.",
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
    private static void EnsureRules(List<RuleEntry> created, List<UncertainEntry> uncertain) => EnsureRules(ExpectedRules, created, uncertain, true);

    private static void EnsureRules(RuleSpec[] specs, List<RuleEntry> created, List<UncertainEntry> uncertain, bool failFast)
    {
        foreach (var r in specs)
        {
            var state = ReadAll(specs)[r.Name];
            if (state == RuleState.Absent)
            {
                // One create path for all four rules: New-NetFirewallRule with an explicit unique -Name key chosen now. Documented contract: only one rule with a given Name can exist in a store, so a
                // create under an existing Name is an error, not a replacement, and nothing can be adopted. Private rules: remote Any (the default). Public rules: LocalSubnet4.
                var key = "BingeMote-" + Guid.NewGuid().ToString("N");
                var remote = r.Remote == "Any" ? "" : " -RemoteAddress " + r.Remote;
                var proto = r.Protocol == "Any" ? "" : " -Protocol " + r.Protocol;
                var port = r.Port == 0 ? "" : " -LocalPort " + r.Port;
                var prog = r.Program == null ? "" : " -Program '" + r.Program.Replace("'", "''") + "'";
                var code = Run("powershell", "-NoProfile -NonInteractive -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(
                    $"New-NetFirewallRule -Name '{key}' -DisplayName '{r.Name}' -Direction Inbound -Action Allow{proto}{port}{prog} -Profile {r.Profile}{remote} -ErrorAction Stop | Out-Null")));
                if (code != 0) { uncertain.Add(new UncertainEntry(key, r.Name, "create exited " + code)); if (failFast) throw new InvalidOperationException($"adding firewall rule '{r.Name}' exited {code}"); else continue; }
                state = ReadAll(specs)[r.Name];
                if (state == RuleState.Exact && ReadByKey(key) is { Found: Found.One } rb && rb.Display == r.Name) created.Add(new RuleEntry(key, r.Name, rb.Props));
                else uncertain.Add(new UncertainEntry(key, r.Name, "create returned 0 but read-back was " + state + " by display name and not exactly one rule under this key"));   // absent before, our create under a fresh key succeeded, read back exact
            }
            if (state != RuleState.Exact)
            {
                Program.Log($"firewall setup: rule '{r.Name}' is {state} (expected exactly one enabled inbound allow rule, profile {r.Profile}, {r.Protocol} {(r.Port == 0 ? "any port" : r.Port.ToString())}, remote {r.Remote}, program {r.Program ?? "any"}, any service); left untouched.");
                if (failFast) throw new InvalidOperationException($"firewall rule '{r.Name}' is {state}; not changed");
                continue;
            }
        }
    }

    internal sealed record RuleSpec(string Name, string Profile, string Protocol, int Port, string Remote, string? Program = null);

    /// <summary>VLC's http port as the phone dials it: vlcrc http-port when it is a valid port, else VLC's default 8080 (the same fallback the helper's own VLC calls use). Read once, at setup.</summary>
    internal static int VlcHttpPort() => int.TryParse(VlcSetup.ReadVlcrcValue("http-port"), out var p) && p is > 0 and <= 65535 ? p : 8080;

    /// <summary>The two VLC rules for one vlc.exe path and port: Private = program only (as the old rule), Public = program + TCP + port + LocalSubnet4 only.</summary>
    internal static RuleSpec[] VlcSpecs(string vlcPath, int port) => new[]
    {
        new RuleSpec(Program.RuleVlcPrivate, "Private", "Any", 0, "Any", vlcPath),
        new RuleSpec(Program.RuleVlcPublic, "Public", "TCP", port, "LocalSubnet4", vlcPath),
    };

    internal static readonly string[] JournalableNames = { Program.RuleTcpPrivate, Program.RuleUdpPrivate, Program.RuleTcpPublic, Program.RuleUdpPublic, Program.RuleVlcPrivate, Program.RuleVlcPublic };

    private static bool VlcRulesAllExact(string vlc) => ReadAll(VlcSpecs(vlc, VlcHttpPort())).Values.All(v => v == RuleState.Exact);
    internal enum RuleState { Absent, Exact, Mismatch, Unreadable }

    internal static readonly RuleSpec[] ExpectedRules =
    {
        new(Program.RuleTcpPrivate, "Private", "TCP", Program.HttpPort, "Any"),
        new(Program.RuleUdpPrivate, "Private", "UDP", Program.DiscoveryPort, "Any"),
        new(Program.RuleTcpPublic, "Public", "TCP", Program.HttpPort, "LocalSubnet4"),
        new(Program.RuleUdpPublic, "Public", "UDP", Program.DiscoveryPort, "LocalSubnet4"),
    };

    /// <summary>Reads the four expected rules as effective firewall objects (not netsh text) in ONE query. Absent means only this: the full, unfiltered Get-NetFirewallRule enumeration SUCCEEDED and no object has that exact DisplayName (an empty enumeration is not an error; filtering is done in the script, so there is no reliance on the cmdlet's "not found" error id). Any failure of the enumeration or of a per-rule filter read (access denied, service down, timeout, bad output) leaves the rule Unreadable, never Absent. Unreadable is never "current" and never triggers an add.</summary>
    internal static Dictionary<string, RuleState> ReadAll(RuleSpec[]? specs = null)
    {
        specs ??= ExpectedRules;
        var result = specs.ToDictionary(r => r.Name, _ => RuleState.Unreadable);
        var names = string.Join(",", specs.Select(r => "'" + r.Name + "'"));
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
                var spec = specs.FirstOrDefault(r => r.Name == f[0]);
                if (spec == null) continue;
                if (f[1] == "ABSENT") result[spec.Name] = RuleState.Absent;
                else if (f[1] == "DUP") result[spec.Name] = RuleState.Mismatch;
                else if (f[1] == "ONE" && f.Length == 14)
                {
                    bool Is(string got, string want) => string.Equals(got.Trim(), want, StringComparison.OrdinalIgnoreCase);
                    var ok = Is(f[2], "True") && Is(f[3], "Inbound") && Is(f[4], "Allow") && Is(f[5], spec.Profile) && Is(f[6], spec.Protocol) &&
                        Is(f[7], spec.Port == 0 ? "Any" : spec.Port.ToString()) && Is(f[8], "Any") && Is(f[9], spec.Remote) && Is(f[10], "Any") && Is(f[11], spec.Program ?? "Any") && Is(f[12], "Any") && Is(f[13], "Block");
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
    /// only when the lifecycle journal records that THIS install run added it (add exited 0 and the
    /// exact read-back was the interactive-users grant) AND the reservation's SDDL is still exactly that
    /// grant AND no interrupted-setup TLS journal is pending or corrupt. The identity marker is not a
    /// delete condition; it is only logged. Residual: the reservation is deleted by URL, so an
    /// administrator who replaced it with the same SDDL after setup is not distinguishable
    /// (the journal holds ALL TLS transport state, bindings and reservation alike, so crash
    /// recovery still sees the whole picture); anything else stays and is logged. Idempotent.</summary>
    public static int RunUninstallCleanup()
    {
        var (ms, _) = TlsMarkerStore.Read();
        var (js, _) = TlsJournalStore.Read();
        var (ls, lj) = LifecycleJournalStore.Read();
        // Ownership of the reservation is the lifecycle journal entry written by the install run that created it (not the identity marker).
        var urlAclWitness = ls == MarkerState.Present && lj!.UrlAcl ? MarkerState.Present : (ls == MarkerState.Corrupt ? MarkerState.Corrupt : MarkerState.Absent);
        var own = ClassifyHttpsUrlAcl(InteractiveSid);
        var urlAclRemoved = false;
        if (own == AclOwnership.None)
        {
            Program.Log("uninstall cleanup: no HTTPS reservation present.");
        }
        else if (HttpsReservationCleanupPlanner.Plan(true, urlAclWitness, own, js) == HttpsReservationCleanupPlanner.Action.Delete)
        {
            if (Run("netsh", "http delete urlacl url=https://+:" + Program.HttpPort + "/") != 0)
                Program.Log("uninstall cleanup: the owned HTTPS listen permission could not be deleted; left in place.");
            else urlAclRemoved = true;
        }
        else
        {
            Program.Log("uninstall cleanup: HTTPS reservation left (identity marker " + ms + ", lifecycle journal " + ls + ", class " + own + ", journal " + js + "): not provably removable.");
        }
        if (own == AclOwnership.None) urlAclRemoved = true;
        RemoveJournaledObjects(ls, lj, urlAclRemoved);
        return TlsSetup.Remove(Program.HttpPort);
    }

    internal enum Found { Absent, One, Dup, Unreadable }
    internal sealed record KeyRead(Found Found, string Display, string Props);

    /// <summary>Reads one rule by its actual Name key (not DisplayName): Props is the full property line (enabled, direction, action, profile, protocol, ports, addresses, program, service, edge traversal).</summary>
    internal static KeyRead ReadByKey(string key)
    {
        var bad = new KeyRead(Found.Unreadable, "", "");
        if (!Regex.IsMatch(key, @"\ABingeMote-[0-9a-f]{32}\z")) return bad;
        var script =
            "$ErrorActionPreference='Stop'; try { $all=@(Get-NetFirewallRule) } catch { 'ERR'; exit 0 }; " +
            "try { $r=@($all | Where-Object { $_.Name -ceq '" + key + "' }); " +
            "if ($r.Count -eq 0) { 'ABSENT'; exit 0 }; if ($r.Count -ne 1) { 'DUP'; exit 0 }; $x=$r[0]; " +
            "$p=$x|Get-NetFirewallPortFilter; $a=$x|Get-NetFirewallAddressFilter; $ap=$x|Get-NetFirewallApplicationFilter; $sv=$x|Get-NetFirewallServiceFilter; " +
            "'ONE|'+$x.DisplayName+'|'+$x.Enabled+'|'+$x.Direction+'|'+$x.Action+'|'+$x.Profile+'|'+$p.Protocol+'|'+($p.LocalPort -join ',')+'|'+($p.RemotePort -join ',')+'|'+($a.RemoteAddress -join ',')+'|'+($a.LocalAddress -join ',')+'|'+$ap.Program+'|'+$sv.Service+'|'+$x.EdgeTraversalPolicy } catch { 'ERR' }";
        try
        {
            var psi = new ProcessStartInfo("powershell", "-NoProfile -NonInteractive -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(script)))
            { RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
            using var p = Process.Start(psi)!;
            var read = p.StandardOutput.ReadToEndAsync();
            if (!p.WaitForExit(60000)) { try { p.Kill(); } catch { } return bad; }
            var line = read.GetAwaiter().GetResult().Trim();
            if (line == "ABSENT") return new KeyRead(Found.Absent, "", "");
            if (line == "DUP") return new KeyRead(Found.Dup, "", "");
            if (!line.StartsWith("ONE|")) return bad;
            var f = line.Split('|');
            return f.Length == 14 ? new KeyRead(Found.One, f[1], string.Join("|", f.Skip(2))) : bad;
        }
        catch { return bad; }
    }

    /// <summary>Removes only the firewall rules this build journaled, by their actual unique Name key: the rule must read back as exactly one rule under that key with the journaled DisplayName and the journaled full property line, then Remove-NetFirewallRule -Name key. Anything else (changed, duplicated, unreadable) is left and logged, and stays in the journal. Documented limit: read-then-delete is not atomic, so an administrator changing the rule between the read and the delete is not excluded; this is a provenance check within the administrator boundary, not an identity guarantee and not a race fix. The watchdog task is handled the same way by its journaled DACL.</summary>
    private static void RemoveJournaledObjects(MarkerState ls, LifecycleJournal? lj, bool urlAclGone)
    {
        if (ls == MarkerState.Absent) { Program.Log("uninstall cleanup: no lifecycle journal; firewall rules and watchdog task left as found."); return; }
        if (ls == MarkerState.Corrupt || lj == null) { Program.Log("uninstall cleanup: lifecycle journal unreadable; firewall rules and watchdog task left."); return; }
        var keepRules = new List<RuleEntry>();
        foreach (var e in lj.Rules)
        {
            var now = ReadByKey(e.Key);
            if (now.Found == Found.Absent) continue;   // already gone: nothing to remove
            if (now.Found == Found.One && now.Display == e.DisplayName && now.Props == e.Props
                && Run("powershell", "-NoProfile -NonInteractive -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(
                    "$ErrorActionPreference='Stop'; Remove-NetFirewallRule -Name '" + e.Key + "'"))) == 0
                && ReadByKey(e.Key).Found == Found.Absent) continue;   // exit 0 alone is not removal: the key must read back Absent before the entry is dropped
            Program.Log("uninstall cleanup: firewall rule '" + e.DisplayName + "' (key " + e.Key + ") left (" + now.Found + ", not provably unchanged, or removal failed).");
            keepRules.Add(e);
        }
        var keepWatchdog = lj.Watchdog != null && !WatchdogSetup.RemoveJournaled(lj.Watchdog) ? lj.Watchdog : null;
        if (lj.UrlAclUncertain != null) Program.Log("uninstall cleanup: HTTPS reservation recorded as uncertain at setup (" + lj.UrlAclUncertain + "): not removed on that basis.");
        foreach (var u in lj.Uncertain) Program.Log("uninstall cleanup: possible orphan firewall rule '" + u.DisplayName + "' key " + u.Key + " (" + u.Outcome + "): never created-witnessed, not removed.");
        LifecycleJournalStore.Replace(new LifecycleJournal(1, keepRules, lj.UrlAcl && !urlAclGone, keepWatchdog, lj.Uncertain, lj.UrlAclUncertain));
    }

    private static int Run(string file, string args)
    {
        var psi = new ProcessStartInfo(file, args) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true };
        using var p = Process.Start(psi)!;
        p.WaitForExit();
        return p.ExitCode;
    }
}
