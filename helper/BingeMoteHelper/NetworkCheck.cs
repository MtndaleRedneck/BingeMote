using System.Diagnostics;

namespace BingeMoteHelper;

/// <summary>The network profile of the adapter the phone's traffic actually arrives on.</summary>
internal sealed class NetProfile
{
    public string Name = "";
    public string Category = "";
    public int InterfaceIndex;
}

/// <summary>
/// The firewall rule is Private-networks-only, which is the right posture. But if Windows
/// thinks his Wi-Fi is a "Public" network, the phone is silently blocked. Detect that and
/// offer the one-click fix, at launch and whenever the network changes, asking at most
/// once per Wi-Fi name.
/// </summary>
internal static class NetworkCheck
{
    /// <summary>
    /// The profile of the adapter that owns the default IPv4 route, so a VPN or second
    /// adapter doesn't trip the warning. Null when it can't be determined. Never throws.
    /// </summary>
    public static NetProfile? DefaultRouteProfile()
    {
        try
        {
            var psi = new ProcessStartInfo("powershell",
                "-NoProfile -NonInteractive -Command \"$i=(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1).InterfaceIndex; $p=Get-NetConnectionProfile -InterfaceIndex $i -ErrorAction SilentlyContinue; if ($p) { \"$($p.Name)|$($p.NetworkCategory)|$($p.InterfaceIndex)\" }\"")
            {
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var p = Process.Start(psi)!;
            // Wait first, read after: reading before the timeout would hang forever on a
            // stuck PowerShell (the read blocks, the timeout never gets to run).
            if (!p.WaitForExit(15000))
            {
                try { p.Kill(); } catch { }
                return null;
            }
            var output = p.StandardOutput.ReadToEnd().Trim();
            var parts = output.Split('|');
            if (parts.Length != 3 || !int.TryParse(parts[2], out var idx)) return null;
            return new NetProfile { Name = parts[0], Category = parts[1], InterfaceIndex = idx };
        }
        catch { return null; }
    }

    /// <summary>
    /// Probe the active network; when it is Public and he has not said "Not now" for this
    /// Wi-Fi name before, hand it to [show]. Runs on a background thread; [show] marshals.
    /// </summary>
    public static void CheckNow(Action<NetProfile> show)
    {
        Task.Run(() =>
        {
            var p = DefaultRouteProfile();
            if (p == null || !p.Category.Equals("Public", StringComparison.OrdinalIgnoreCase)) return;
            if (Program.DeclinedNetworks.Any(n => string.Equals(n, p.Name, StringComparison.OrdinalIgnoreCase))) return;
            show(p);
        });
    }

    private static readonly object Gate = new();
    private static bool _queued;

    /// <summary>Network changes arrive in bursts; probe once, three seconds after the first of a burst.</summary>
    public static void QueueCheck(Action<NetProfile> show)
    {
        lock (Gate)
        {
            if (_queued) return;
            _queued = true;
        }
        Task.Run(async () =>
        {
            await Task.Delay(3000);
            lock (Gate) _queued = false;
            CheckNow(show);
        });
    }

    /// <summary>Mark this network Private (one elevated Windows prompt). True when it stuck.</summary>
    public static bool MarkPrivate(NetProfile profile)
    {
        try
        {
            var psi = new ProcessStartInfo("powershell",
                "-NoProfile -NonInteractive -Command \"Set-NetConnectionProfile -InterfaceIndex " + profile.InterfaceIndex + " -NetworkCategory Private\"")
            {
                UseShellExecute = true,
                Verb = "runas",
            };
            using var proc = Process.Start(psi)!;
            proc.WaitForExit(30000);
        }
        catch { return false; } // he cancelled the Windows permission prompt
        var again = DefaultRouteProfile();
        return again != null && again.Category.Equals("Private", StringComparison.OrdinalIgnoreCase);
    }
}
