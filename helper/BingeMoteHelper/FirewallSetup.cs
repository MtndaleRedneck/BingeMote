using System.Diagnostics;

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
            // Let the helper (a normal, non-admin app) listen on its port.
            Run("netsh", "http delete urlacl url=http://+:" + Program.HttpPort + "/");
            var acl = Run("netsh", "http add urlacl url=http://+:" + Program.HttpPort + "/ sddl=D:(A;;GX;;;WD)");
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
            "BingeMote needs to let your phone reach this PC on your home network. Windows will now ask for administrator permission once - it is used only to add one firewall rule, for home (Private) networks only. Click Yes.",
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
            if (p == null || !p.WaitForExit(60_000) || p.ExitCode != 0 || !RuleExists())
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

    private static void AddRule(string protocol, int port)
    {
        Run("netsh", $"advfirewall firewall delete rule name=\"{Program.FirewallRule}\" protocol={protocol} localport={port}");
        var code = Run("netsh", $"advfirewall firewall add rule name=\"{Program.FirewallRule}\" dir=in action=allow protocol={protocol} localport={port} profile=private");
        if (code != 0) throw new InvalidOperationException($"netsh add rule exited {code}");
    }

    internal static bool UrlAclExists()
    {
        var psi = new ProcessStartInfo("netsh", "http show urlacl")
        {
            RedirectStandardOutput = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var p = Process.Start(psi)!;
        var output = p.StandardOutput.ReadToEnd();
        p.WaitForExit();
        return output.Contains(":" + Program.HttpPort + "/");
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
