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
    /// <summary>A newer published helper version, found by the background update check.</summary>
    internal static string? AvailableUpdate;
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

        // One copy at a time: a second launch just exits, no confusing error box.
        using var single = new Mutex(true, @"Local\BingeMoteHelper.SingleInstance", out var onlyCopy);
        if (!onlyCopy) return 0;

        Directory.CreateDirectory(DataDir);
        LoadConfig();
        RegisterAutostart();

        if (!File.Exists(FirewallMarker))
        {
            // One-time elevated step: add the Private-network-only firewall rule, then come back.
            FirewallSetup.ExplainAndRelaunch();
        }

        // A live pairing code is a way in, so one exists only when this PC still needs
        // pairing. After pairing, a new code comes only from the tray menu.
        var overrideCode = Environment.GetEnvironmentVariable("BINGEMOTE_PAIR_CODE");
#if !PAIR_CODE_HOOK
        // Shipped builds never honor a fixed code. CI defines PAIR_CODE_HOOK for its own copy.
        overrideCode = null;
#endif
        if (overrideCode is { Length: 6 } && overrideCode.All(char.IsDigit))
        {
            PairCode = overrideCode;
            PairCodeExpires = DateTime.UtcNow + PairCodeLifetime;
        }
        else if (Token == null)
        {
            PairCode = NewPairCode();
            PairCodeExpires = DateTime.UtcNow + PairCodeLifetime;
        }

        using var http = new HttpServer();
        using var discovery = new DiscoveryResponder();
        // Public Wi-Fi silently blocks the phone; check on every launch, paired or not.
        Task.Run(() => { try { NetworkCheck.WarnIfPublic(); } catch { /* never stop startup */ } });
        CheckForUpdate();
        try { http.Start(); discovery.Start(); }
        catch (Exception ex) {
            MessageBox.Show("Could not start listening for the phone. If Windows asked for administrator permission earlier, choose Yes - it is needed once to open the helper's port.\n\n" + ex.Message,
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

    /// <summary>Background update check: on launch, and the window re-runs it daily.</summary>
    internal static void CheckForUpdate()
    {
        Task.Run(() =>
        {
            try
            {
                var current = typeof(Program).Assembly.GetName().Version?.ToString() ?? "0.0.0";
                var newer = UpdateCheck.LatestNewerThan(current);
                if (newer != null) Program.AvailableUpdate = newer.Value.Version;
            }
            catch { /* a failed check is invisible */ }
        });
    }

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
    private bool _allowShow;
    private bool _updateAnnounced;
    private readonly Label _code;
    private readonly Label _status;
    private readonly NotifyIcon _tray;
    private readonly System.Windows.Forms.Timer _timer;

    public HelperWindow()
    {
        Text = "BingeMote Helper";
        Width = 500; Height = 270;
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
            AutoSize = false, Width = 450, Height = 40, Left = 20, Top = 108,
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
        menu.Items.Add("Show pairing code", null, (_, _) => { _allowShow = true; Show(); WindowState = FormWindowState.Normal; Activate(); });
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
        _tray.DoubleClick += (_, _) => { _allowShow = true; Show(); WindowState = FormWindowState.Normal; Activate(); };
        _tray.BalloonTipClicked += (_, _) => OpenUpdatePage();

        // Daily re-check for updates while running.
        var daily = new System.Windows.Forms.Timer { Interval = 24 * 60 * 60 * 1000 };
        daily.Tick += (_, _) => Program.CheckForUpdate();
        daily.Start();

        // Keep the status line and an expired code fresh, and announce a found update once.
        _timer = new System.Windows.Forms.Timer { Interval = 2000 };
        _timer.Tick += (_, _) =>
        {
            if (Program.AvailableUpdate != null && !_updateAnnounced)
            {
                _updateAnnounced = true;
                menu.Items.Insert(0, new ToolStripMenuItem("Download update v" + Program.AvailableUpdate, null, (_, _) => OpenUpdatePage()));
                _tray.BalloonTipTitle = "BingeMote Helper update";
                _tray.BalloonTipText = "Version " + Program.AvailableUpdate + " is available. Click here to download it.";
                _tray.ShowBalloonTip(8000);
            }
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

    // Paired PCs get no popup at login: the helper starts tucked in the tray.
    protected override void SetVisibleCore(bool value)
    {
        if (!_allowShow && Program.Token != null) value = false;
        base.SetVisibleCore(value);
    }

    private static void OpenUpdatePage()
    {
        try
        {
            Process.Start(new ProcessStartInfo(UpdateCheck.ReleasePage) { UseShellExecute = true });
        }
        catch { /* he can open the page from the app's About link too */ }
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
