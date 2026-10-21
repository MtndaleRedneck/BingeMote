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

    private const string ShowEventName = @"Local\BingeMoteHelper.ShowWindow";
    private const string QuitEventName = @"Local\BingeMoteHelper.Quit";

    internal static string? Token;
    /// <summary>The folder the phone's Movies tab opens; set from this window or from the phone.</summary>
    internal static string? MoviesFolder;
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

        // One copy at a time. What a second launch does depends on what is already running:
        // the same exe just pops the running copy's window up; a different exe (an update)
        // asks the old copy to quit and takes over; a copy too old to have the events
        // (1.0.0/1.0.1) has to be quit by hand first.
        using var single = new Mutex(true, @"Local\BingeMoteHelper.SingleInstance", out var onlyCopy);
        if (!onlyCopy)
        {
            if (RunningCopyIsThisExe())
            {
                try { EventWaitHandle.OpenExisting(ShowEventName).Set(); } catch { }
                Log("Launched while already running - asked the running copy to show its window.");
                return 0;
            }
            EventWaitHandle? quitEvent = null;
            try { quitEvent = EventWaitHandle.OpenExisting(QuitEventName); } catch { }
            if (quitEvent == null)
            {
                Log("An older helper (before 1.0.2) is running - asked him to quit it by hand.");
                MessageBox.Show(
                    "An older BingeMote Helper is already running. Right-click its icon by the clock (click the ^ arrow if you don't see it) and choose Quit, then open this one again.",
                    "BingeMote Helper", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }
            Log("Update launched while an older copy ran - asked it to quit, taking over.");
            quitEvent.Set();
            // The old copy exits and releases the mutex; take it over (give it a few seconds).
            var tookOver = false;
            for (var i = 0; i < 40 && !tookOver; i++)
            {
                try { tookOver = single.WaitOne(250); } catch (AbandonedMutexException) { tookOver = true; }
            }
            if (!tookOver)
            {
                Log("The older copy did not exit in time - giving up.");
                MessageBox.Show(
                    "The older BingeMote Helper is still closing. Open this one again in a few seconds.",
                    "BingeMote Helper", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }
        }

        Directory.CreateDirectory(DataDir);
        LoadConfig();
        Log("Started. " + (Token == null ? "Not paired - showing a pairing code." : "Paired - starting tucked in the tray."));
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
            Log("Could not start listening: " + ex.Message);
            MessageBox.Show("Could not start listening for the phone. If Windows asked for administrator permission earlier, choose Yes - it is needed once to open the helper's port.\n\n" + ex.Message,
                "BingeMote Helper", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }

        ApplicationConfiguration.Initialize();
        using var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        using var quitEvent = new EventWaitHandle(false, EventResetMode.AutoReset, QuitEventName);
        var window = new HelperWindow();
        _ = window.Handle; // force handle creation so a second launch can always signal us
        var watcher = new Thread(() =>
        {
            var events = new WaitHandle[] { showEvent, quitEvent };
            while (true)
            {
                try
                {
                    var which = WaitHandle.WaitAny(events);
                    if (window.IsDisposed) return;
                    if (which == 1)
                    {
                        // A newer copy launched: step aside so it can take over the mutex.
                        Log("A newer copy launched - quitting so it can take over.");
                        window.BeginInvoke(new Action(Application.Exit));
                        return;
                    }
                    window.BeginInvoke(new Action(window.ShowFromTray));
                }
                catch { return; }
            }
        }) { IsBackground = true };
        watcher.Start();
        Application.Run(window);
        Log("Exited.");
        return 0;
    }

    /// <summary>True when the copy holding the mutex runs from the same exe file as this launch.</summary>
    private static bool RunningCopyIsThisExe()
    {
        try
        {
            var self = Environment.ProcessPath;
            var me = Environment.ProcessId;
            foreach (var proc in Process.GetProcessesByName("BingeMoteHelper"))
            {
                if (proc.Id == me) continue;
                string? other = null;
                try { other = proc.MainModule?.FileName; } catch { }
                if (other == null || self == null) return false; // cannot tell - treat as a different exe
                return string.Equals(other, self, StringComparison.OrdinalIgnoreCase);
            }
        }
        catch { }
        return false;
    }

    internal static string NewPairCode() => RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");

    internal static string NewToken() => Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();

    /// <summary>One line in %LocalAppData%\BingeMoteHelper\helper.log, so a bad launch leaves a trail.</summary>
    internal static void Log(string message)
    {
        try
        {
            var path = Path.Combine(DataDir, "helper.log");
            var info = new FileInfo(path);
            if (info.Exists && info.Length > 1_000_000)
            {
                var lines = File.ReadAllLines(path);
                File.WriteAllLines(path, lines.Skip(lines.Length / 2)); // keep the newer half
            }
            File.AppendAllText(path,
                DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + message + Environment.NewLine);
        }
        catch { /* logging must never break the helper */ }
    }

    internal static void LoadConfig()
    {
        try
        {
            if (!File.Exists(ConfigPath)) return;
            using var doc = JsonDocument.Parse(File.ReadAllText(ConfigPath));
            Token = doc.RootElement.GetProperty("token").GetString();
            MoviesFolder = doc.RootElement.TryGetProperty("moviesFolder", out var mf) ? mf.GetString() : null;
        }
        catch { Token = null; MoviesFolder = null; }
    }

    internal static void SaveConfig()
    {
        var json = JsonSerializer.Serialize(new { token = Token, moviesFolder = MoviesFolder });
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

using WinForms = System.Windows.Forms;

/// <summary>The helper window: status, the movies folder, and pairing controls. Sits in the tray;
/// follows the Windows dark/light mode setting, repainting live when it changes.</summary>
internal sealed class HelperWindow : Form
{
    // The app's palette: dark mode uses the app's colors, light mode their light counterparts.
    private static readonly Color Orange = Color.FromArgb(255, 149, 0);
    private static readonly Color OnOrange = Color.FromArgb(27, 27, 30);
    private static readonly Color DarkBg = Color.FromArgb(27, 27, 30);
    private static readonly Color DarkCard = Color.FromArgb(38, 38, 44);
    private static readonly Color DarkText = Color.FromArgb(235, 235, 240);
    private static readonly Color DarkMuted = Color.FromArgb(150, 150, 160);
    private static readonly Color LightBg = Color.FromArgb(243, 243, 243);
    private static readonly Color LightCard = Color.White;
    private static readonly Color LightText = Color.FromArgb(26, 26, 26);
    private static readonly Color LightMuted = Color.FromArgb(110, 110, 110);

    private bool _allowShow;
    private bool _updateAnnounced;
    private bool _hideHintShown;
    private readonly Label _title;
    private readonly Label _status;
    private readonly Label _moviesLabel;
    private readonly TextBox _folderBox;
    private readonly Button _browse;
    private readonly Label _folderHint;
    private readonly Panel _sep1;
    private readonly Label _pairingHeader;
    private readonly Label _codeCaption;
    private readonly Label _code;
    private readonly Label _codeNote;
    private readonly Button _newCode;
    private readonly Button _resetPairing;
    private readonly Panel _sep2;
    private readonly Label _footer;
    private readonly Button _quit;
    private readonly NotifyIcon _tray;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly ContextMenuStrip _menu;

    public HelperWindow()
    {
        Text = "BingeMote Helper";
        Width = 620; Height = 460;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        try { Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!); } catch { }

        _title = new Label
        {
            Text = "BingeMote Helper",
            Font = new Font("Segoe UI", 14, FontStyle.Bold),
            AutoSize = true, Left = 20, Top = 16,
        };
        _status = new Label
        {
            Text = "",
            Font = new Font("Segoe UI", 9),
            AutoSize = true, Left = 20, Top = 48,
        };
        _moviesLabel = new Label
        {
            Text = "Movies folder (what the phone's Movies tab opens):",
            Font = new Font("Segoe UI", 9),
            AutoSize = true, Left = 20, Top = 88,
        };
        _folderBox = new TextBox
        {
            ReadOnly = true,
            Left = 20, Top = 110, Width = 410,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Segoe UI", 9),
        };
        _browse = MakeButton("Browse...", 440, 108, 110);
        _browse.Click += (_, _) => PickFolder();
        _folderHint = new Label
        {
            Text = "The normal Windows folder picker - no typing.",
            Font = new Font("Segoe UI", 8),
            AutoSize = true, Left = 20, Top = 140,
        };
        _sep1 = new Panel { Left = 20, Top = 168, Width = 545, Height = 1 };
        _pairingHeader = new Label
        {
            Text = "Pairing",
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            AutoSize = true, Left = 20, Top = 182,
        };
        _codeCaption = new Label
        {
            Text = "Pairing code:",
            Font = new Font("Segoe UI", 9),
            AutoSize = true, Left = 20, Top = 214,
        };
        _code = new Label
        {
            Text = Program.PairCode ?? "------",
            Font = new Font("Consolas", 24, FontStyle.Bold),
            ForeColor = Orange,
            AutoSize = true, Left = 130, Top = 204,
        };
        _codeNote = new Label
        {
            Text = "A code only shows while no phone is paired, or after New/Reset. It works once, for 10 minutes.",
            Font = new Font("Segoe UI", 8),
            AutoSize = true, Left = 20, Top = 248,
        };
        _newCode = MakeButton("New pairing code", 20, 274, 150);
        _newCode.Click += (_, _) => NewPairingCode();
        _resetPairing = MakeButton("Reset pairing...", 180, 274, 130);
        _resetPairing.Click += (_, _) => ResetPairing();
        _sep2 = new Panel { Left = 20, Top = 322, Width = 545, Height = 1 };
        _footer = new Label
        {
            Text = "Runs in the tray by the clock and starts with Windows.",
            Font = new Font("Segoe UI", 8),
            AutoSize = true, Left = 20, Top = 336,
        };
        _quit = MakeButton("Quit", 475, 330, 90);
        _quit.Click += (_, _) => { Program.Log("Quit from the window."); Application.Exit(); };

        Controls.AddRange(new Control[]
        {
            _title, _status, _moviesLabel, _folderBox, _browse, _folderHint, _sep1,
            _pairingHeader, _codeCaption, _code, _codeNote, _newCode, _resetPairing,
            _sep2, _footer, _quit,
        });

        _menu = new ContextMenuStrip();
        _menu.Items.Add("Show BingeMote Helper", null, (_, _) => ShowFromTray());
        _menu.Items.Add("New pairing code", null, (_, _) => NewPairingCode());
        _menu.Items.Add("Reset pairing...", null, (_, _) => ResetPairing());
        _menu.Items.Add("Quit BingeMote Helper", null, (_, _) => { Program.Log("Quit from the tray menu."); Application.Exit(); });
        _tray = new NotifyIcon
        {
            Text = "BingeMote Helper",
            Icon = Icon,
            Visible = true,
            ContextMenuStrip = _menu,
        };
        _tray.DoubleClick += (_, _) => ShowFromTray();
        _tray.BalloonTipClicked += (_, _) => OpenUpdatePage();

        // Follow the Windows dark/light mode setting, live.
        Microsoft.Win32.SystemEvents.UserPreferenceChanged += (_, _) =>
        {
            try { BeginInvoke(new Action(ApplyTheme)); } catch { }
        };
        ApplyTheme();

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
                _menu.Items.Insert(0, new ToolStripMenuItem("Download update v" + Program.AvailableUpdate, null, (_, _) => OpenUpdatePage()));
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

    private static Button MakeButton(string text, int left, int top, int width) => new()
    {
        Text = text,
        Left = left, Top = top, Width = width, Height = 30,
        FlatStyle = FlatStyle.Flat,
        Font = new Font("Segoe UI", 8.5f),
    };

    private static bool IsDarkMode()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return ((int)(key?.GetValue("AppsUseLightTheme") ?? 1)) == 0;
        }
        catch { return true; }
    }

    private void ApplyTheme()
    {
        var dark = IsDarkMode();
        var bg = dark ? DarkBg : LightBg;
        var card = dark ? DarkCard : LightCard;
        var text = dark ? DarkText : LightText;
        var muted = dark ? DarkMuted : LightMuted;
        BackColor = bg;
        ForeColor = text;
        _title.ForeColor = text;
        _status.ForeColor = muted;
        _moviesLabel.ForeColor = text;
        _folderBox.BackColor = card;
        _folderBox.ForeColor = muted;
        _folderHint.ForeColor = muted;
        _sep1.BackColor = card;
        _sep2.BackColor = card;
        _pairingHeader.ForeColor = text;
        _codeCaption.ForeColor = text;
        _code.ForeColor = Orange;
        _codeNote.ForeColor = muted;
        _footer.ForeColor = muted;
        foreach (var b in new[] { _browse, _newCode, _resetPairing, _quit })
        {
            b.BackColor = card;
            b.ForeColor = text;
            b.FlatAppearance.BorderColor = muted;
        }
    }

    private void PickFolder()
    {
        using var dlg = new FolderBrowserDialog
        {
            Description = "Pick the folder the phone's Movies tab opens.",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = true,
            InitialDirectory = Directory.Exists(Program.MoviesFolder) ? Program.MoviesFolder! : "",
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        Program.MoviesFolder = dlg.SelectedPath;
        Program.SaveConfig();
        _folderBox.Text = Program.MoviesFolder;
        Program.Log("Movies folder set on the PC: " + Program.MoviesFolder);
    }

    private void NewPairingCode()
    {
        Program.PairCode = Program.NewPairCode();
        Program.PairCodeExpires = DateTime.UtcNow + Program.PairCodeLifetime;
        Program.PairFailures = 0;
        Program.Log("New pairing code from the tray menu.");
        ShowFromTray();
    }

    private void ResetPairing()
    {
        var sure = MessageBox.Show(
            "Forget the paired phone and show a new pairing code? Pair the phone again with the new code to reconnect it.",
            "BingeMote Helper", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
        if (sure != DialogResult.Yes) return;
        Program.Token = null;
        Program.SaveConfig();
        Program.PairCode = Program.NewPairCode();
        Program.PairCodeExpires = DateTime.UtcNow + Program.PairCodeLifetime;
        Program.PairFailures = 0;
        Program.Log("Pairing reset - new code shown.");
        ShowFromTray();
    }

    /// <summary>Bring the window up from the tray (menu, double-click, or a second launch of the exe).</summary>
    internal void ShowFromTray()
    {
        _allowShow = true;
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
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
            if (!_hideHintShown)
            {
                _hideHintShown = true;
                _tray.BalloonTipTitle = "BingeMote Helper";
                _tray.BalloonTipText = "Still running down here by the clock. Right-click the icon for a pairing code or to quit.";
                _tray.ShowBalloonTip(6000);
            }
            return;
        }
        _tray.Visible = false;
        base.OnFormClosing(e);
    }
}
