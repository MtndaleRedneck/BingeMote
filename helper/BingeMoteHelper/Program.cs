// BingeMote Helper: the small Windows companion for the BingeMote Android app.
// It listens on the home network for mouse, scroll, zoom and keyboard commands from the
// paired phone and plays them back with SendInput. See README.md for setup.
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BingeMoteHelper;

internal static class Program
{
    internal const int HttpPort = 8488;
    internal const int DiscoveryPort = 8489;
    internal const string DiscoverMagic = "BINGEMOTE_DISCOVER_V1";
    internal const string ReplyMagic = "BINGEMOTE_HERE_V1";
    internal const string FirewallRule = "BingeMote Helper";

    internal static readonly string DataDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BingeMoteHelper");
    private static readonly string ConfigPath = Path.Combine(DataDir, "config.json");
    private static readonly string FirewallMarker = Path.Combine(DataDir, "firewall.ok");

    internal static string? Token;
    internal static string? PairCode;
    internal static DateTime PairCodeExpires = DateTime.MinValue;
    internal static int PairFailures;
    internal const int MaxPairFailures = 5;
    internal static readonly TimeSpan PairCodeLifetime = TimeSpan.FromMinutes(10);

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--install-firewall"))
        {
            return FirewallSetup.RunElevated(FirewallMarker);
        }

        Directory.CreateDirectory(DataDir);
        LoadConfig();
        RegisterAutostart();

        if (!File.Exists(FirewallMarker))
        {
            // One-time elevated step: add the Private-network-only firewall rule, then come back.
            FirewallSetup.ExplainAndRelaunch();
        }

        PairCode = NewPairCode();
        PairCodeExpires = DateTime.UtcNow + PairCodeLifetime;

        using var http = new HttpServer();
        using var discovery = new DiscoveryResponder();
        try { http.Start(); discovery.Start(); }
        catch (Exception ex) {
            MessageBox.Show("Could not start the helper. Check the firewall setup completed and no other copy is running.\n\n" + ex.Message,
                "BingeMote Helper", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new HelperWindow());
        return 0;
    }

    internal static string NewPairCode() => RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");

    internal static string NewToken() => Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();

    internal static void LoadConfig()
    {
        try
        {
            if (!File.Exists(ConfigPath)) return;
            using var doc = JsonDocument.Parse(File.ReadAllText(ConfigPath));
            Token = doc.RootElement.GetProperty("token").GetString();
        }
        catch { Token = null; }
    }

    internal static void SaveConfig()
    {
        var json = JsonSerializer.Serialize(new { token = Token });
        File.WriteAllText(ConfigPath, json);
    }

    internal static string MachineLabel() => Environment.MachineName;

    /// <summary>Start with Windows for this user (no admin needed).</summary>
    private static void RegisterAutostart()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run", writable: true);
            key?.SetValue("BingeMoteHelper", "\"" + Environment.ProcessPath + "\"");
        }
        catch { /* autostart is a convenience, not a requirement */ }
    }
}

/// <summary>The pairing window: shows the current 6-digit code and connection status, sits in the tray.</summary>
internal sealed class HelperWindow : Form
{
    private readonly Label _code;
    private readonly Label _status;
    private readonly NotifyIcon _tray;
    private readonly System.Windows.Forms.Timer _timer;

    public HelperWindow()
    {
        Text = "BingeMote Helper";
        Width = 380; Height = 240;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;

        var title = new Label
        {
            Text = "BingeMote Helper",
            Font = new Font("Segoe UI", 14, FontStyle.Bold),
            AutoSize = true, Left = 20, Top = 16,
        };
        _code = new Label
        {
            Text = Program.PairCode ?? "------",
            Font = new Font("Consolas", 32, FontStyle.Bold),
            ForeColor = Color.DarkOrange,
            AutoSize = true, Left = 20, Top = 52,
        };
        var hint = new Label
        {
            Text = "Enter this code in the BingeMote app to pair. It works once, for 10 minutes.",
            Font = new Font("Segoe UI", 9),
            ForeColor = Color.DimGray,
            AutoSize = true, Left = 20, Top = 108,
        };
        _status = new Label
        {
            Text = "Listening on port " + Program.HttpPort,
            Font = new Font("Segoe UI", 9),
            ForeColor = Color.DimGray,
            AutoSize = true, Left = 20, Top = 150,
        };
        Controls.Add(title); Controls.Add(_code); Controls.Add(hint); Controls.Add(_status);

        var menu = new ContextMenuStrip();
        menu.Items.Add("Show pairing code", null, (_, _) => { Show(); WindowState = FormWindowState.Normal; Activate(); });
        menu.Items.Add("New pairing code", null, (_, _) =>
        {
            Program.PairCode = Program.NewPairCode();
            Program.PairCodeExpires = DateTime.UtcNow + Program.PairCodeLifetime;
            Program.PairFailures = 0;
        });
        menu.Items.Add("Quit BingeMote Helper", null, (_, _) => Application.Exit());
        _tray = new NotifyIcon
        {
            Text = "BingeMote Helper",
            Icon = SystemIcons.Application,
            Visible = true,
            ContextMenuStrip = menu,
        };
        _tray.DoubleClick += (_, _) => { Show(); WindowState = FormWindowState.Normal; Activate(); };

        // Keep the status line and an expired code fresh.
        _timer = new System.Windows.Forms.Timer { Interval = 2000 };
        _timer.Tick += (_, _) =>
        {
            if (Program.PairCode != null && DateTime.UtcNow > Program.PairCodeExpires)
            {
                Program.PairCode = Program.NewPairCode();
                Program.PairCodeExpires = DateTime.UtcNow + Program.PairCodeLifetime;
            }
            _code.Text = Program.PairCode ?? "------";
            _status.Text = Program.Token == null
                ? "Listening on port " + Program.HttpPort + " - not paired yet"
                : "Listening on port " + Program.HttpPort + " - paired";
        };
        _timer.Start();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true; // closing tucks it into the tray; Quit exits for real
            Hide();
            return;
        }
        _tray.Visible = false;
        base.OnFormClosing(e);
    }
}
