using System.Diagnostics;

namespace BingeMoteHelper;

/// <summary>
/// The firewall rule is Private-networks-only, which is the right posture. But if Windows
/// thinks his Wi-Fi is a "Public" network, the phone is silently blocked. Detect that and
/// show him exactly how to switch it.
/// </summary>
internal static class NetworkCheck
{
    /// <summary>True when an active connection is in the Public profile. Never throws.</summary>
    public static bool AnyPublicProfile()
    {
        try
        {
            var psi = new ProcessStartInfo("powershell",
                "-NoProfile -NonInteractive -Command \"@(Get-NetConnectionProfile | Where-Object NetworkCategory -eq 'Public').Count\"")
            {
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var p = Process.Start(psi)!;
            var output = p.StandardOutput.ReadToEnd().Trim();
            p.WaitForExit(15000);
            return int.TryParse(output, out var n) && n > 0;
        }
        catch { return false; }
    }

    /// <summary>Warn him the Public profile blocks the phone and offer to open the right Settings page.</summary>
    public static void WarnIfPublic()
    {
        if (!AnyPublicProfile()) return;
        var answer = MessageBox.Show(
            "Windows has your Wi-Fi set as a Public network, so it blocks the phone app.\n\n" +
            "To fix it: Settings > Network & internet > Wi-Fi > your network > choose Private.\n\n" +
            "Open those settings now?",
            "BingeMote Helper",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning);
        if (answer != DialogResult.Yes) return;
        try
        {
            Process.Start(new ProcessStartInfo("ms-settings:network-wifi") { UseShellExecute = true });
        }
        catch { /* the steps above still tell him what to do */ }
    }
}
