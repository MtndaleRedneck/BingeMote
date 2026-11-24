using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.RegularExpressions;

namespace BingeMoteHelper;

/// <summary>
/// The one-time firewall step. Windows needs admin rights to add a firewall rule and the
/// listen permission, so the helper restarts itself once with the UAC prompt, adds a
/// Private-networks-only rule, checks both are really there, and leaves a marker so it
/// never asks again.
/// </summary>
internal static class FirewallSetup
{
    /// <summary>Runs inside the elevated copy. Returns the process exit code.</summary>
    public static int RunElevated(string markerPath)
    {
        try
        {
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
            Run("netsh", "http delete urlacl url=http://+:" + Program.HttpPort + "/");
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
            var acl = Run("netsh", "http add urlacl url=http://+:" + Program.HttpPort + "/ sddl=D:(A;;GX;;;S-1-5-4)");
            if (acl != 0 || !UrlAclExists())
            {
                Console.Error.WriteLine("The listen permission could not be added.");
                return 3;
            }
            if (!RuleExists())
            {
                Console.Error.WriteLine("The firewall rule could not be verified after adding it.");
                return 2;
            }
            Directory.CreateDirectory(Path.GetDirectoryName(markerPath)!);
            File.WriteAllText(markerPath, DateTime.UtcNow.ToString("O"));
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("Firewall setup failed: " + ex.Message);
            return 1;
        }
    }

    /// <summary>Explains the prompt, then relaunches elevated for the one-time rule. Non-blocking.</summary>
    public static void ExplainAndRelaunch()
    {
        var answer = MessageBox.Show(
            "One Windows permission, then setup is done.\n\n" +
            "BingeMote needs to let your phone reach this PC on your home network. Windows will now ask for administrator permission once - it is used only to set up the home (Private) network firewall rules and the Windows listen permission. Click Yes.",
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
            if (p == null || !p.WaitForExit(60_000) || p.ExitCode != 0 || !RuleExists() || !UrlAclExists())
                throw new InvalidOperationException("Setup could not be verified.");
        }
        catch
        {
            // The user said no at the UAC prompt. Without the rule and listen permission
            // the helper cannot accept connections, so it will close and offer again next launch.
            MessageBox.Show(
                "No firewall rule was added, so the phone cannot reach this PC yet. BingeMote will now close and offer to set up again next time you open it.",
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
            var psi = new ProcessStartInfo("netsh", "http show urlacl url=http://+:" + Program.HttpPort + "/")
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
        var match = Regex.Match(output, @"(?m)^.*?:\s*(D:[^\r\n]+)$");
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
