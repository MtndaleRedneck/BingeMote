using System.Diagnostics;

namespace BingeMoteHelper;

/// <summary>
/// The firewall rule is Private-networks-only, which is the right posture. But if Windows
/// thinks his Wi-Fi is a "Public" network, the phone is silently blocked. Detect that and
/// show him exactly how to switch it.
/// </summary>
internal static class NetworkCheck
{
    /// <summary>
    /// The profile category of the adapter that owns the default IPv4 route - the one phone
    /// traffic actually arrives on - so a VPN or second adapter doesn't trip the warning.
    /// Empty string when it can't be determined. Never throws.
    /// </summary>
    public static string DefaultRouteCategory()
    {
        try
        {
            var psi = new ProcessStartInfo("powershell",
                "-NoProfile -NonInteractive -Command \"$i=(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1).InterfaceIndex; $p=Get-NetConnectionProfile -InterfaceIndex $i -ErrorAction SilentlyContinue; if ($p) { $p.NetworkCategory }\"")
            {
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var p = Process.Start(psi)!;
            var output = p.StandardOutput.ReadToEnd().Trim();
            p.WaitForExit(15000);
            return output;
        }
        catch { return ""; }
    }

    /// <summary>Warn him the Public profile blocks the phone and offer to open the right Settings page.</summary>
    public static void WarnIfPublic()
    {
        if (!DefaultRouteCategory().Equals("Public", StringComparison.OrdinalIgnoreCase)) return;
        var answer = MessageBox.Show(
            "Windows has your Wi-Fi set as a Public network, so it blocks the phone app.\n\n" +
            "To fix it: Settings > Network & internet > Wi-Fi > your network > choose Private.\n\n" +
            "Open those settings now?",
            "BingeMote PC",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning,
            MessageBoxDefaultButton.Button1,
            // At login other windows can cover an ownerless box; keep it visible.
            MessageBoxOptions.DefaultDesktopOnly);
        if (answer != DialogResult.Yes) return;
        try
        {
            Process.Start(new ProcessStartInfo("ms-settings:network-wifi") { UseShellExecute = true });
        }
        catch { /* the steps above still tell him what to do */ }
    }
}
