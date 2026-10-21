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
using WinForms = System.Windows.Forms;

namespace BingeMoteHelper;

internal static class Program
{
    internal const int HttpPort = 8488;
    internal const int DiscoveryPort = 8489;
    internal const string DiscoverMagic = "BINGEMOTE_DISCOVER_V1";
    internal const string ReplyMagic = "BINGEMOTE_HERE_V1";
    internal const string FirewallRule = "BingeMote";
    /// <summary>The 1.x rule name, removed by the firewall setup so upgrades do not leave it behind.</summary>
    internal const string LegacyFirewallRule = "BingeMote Helper";

    internal static readonly string DataDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BingeMoteHelper");
    private static readonly string ConfigPath = Path.Combine(DataDir, "config.json");
    private static readonly string FirewallMarker = Path.Combine(DataDir, "firewall.ok");

    private const string ShowEventName = @"Local\BingeMoteHelper.ShowWindow";
    private const string QuitEventName = @"Local\BingeMoteHelper.Quit";

    /// <summary>A phone paired to this PC. The name is typed in the window when pairing starts.</summary>
    internal sealed class PairedPhone
    {
        public string Token { get; set; } = "";
        public string Name { get; set; } = "";
    }

    /// <summary>One entry per paired phone. Pairing adds one; Remove takes one out.</summary>
    internal static readonly List<PairedPhone> Phones = new();
    /// <summary>The name typed for the phone that is about to pair; consumed by a successful pairing.</summary>
    internal static string? PendingPhoneName;
    /// <summary>The window re-reads the phone list on the UI thread when this fires (any thread).</summary>
    internal static Action? PhonesChanged;
    /// <summary>Wi-Fi names where he chose "Not now" on the public-network prompt; not asked again.</summary>
    internal static readonly List<string> DeclinedNetworks = new();
    /// <summary>More phones than this on one PC is a mistake, not a household.</summary>
    internal const int MaxPhones = 8;
    internal static bool Paired => Phones.Count > 0;
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
            var runningPath = RunningCopyPath();
            var selfPath = Environment.ProcessPath;
            var sameExe = runningPath != null && selfPath != null &&
                string.Equals(runningPath, selfPath, StringComparison.OrdinalIgnoreCase);
            // A different exe is an update only when this launch is the same or a newer
            // version; an older exe (e.g. left in Downloads) must not downgrade the running copy.
            var isDowngrade = false;
            if (!sameExe && runningPath != null && selfPath != null)
            {
                try
                {
                    var mine = FileVersionInfo.GetVersionInfo(selfPath).FileVersion;
                    var theirs = FileVersionInfo.GetVersionInfo(runningPath).FileVersion;
                    if (mine != null && theirs != null &&
                        new Version(mine).CompareTo(new Version(theirs)) < 0) isDowngrade = true;
                }
                catch { /* cannot tell versions - treat as an update, as before */ }
            }
            if (sameExe || isDowngrade)
            {
                try { EventWaitHandle.OpenExisting(ShowEventName).Set(); } catch { }
                Log(isDowngrade
                    ? "An older exe launched while a newer copy ran - showed the running copy's window."
                    : "Launched while already running - asked the running copy to show its window.");
                return 0;
            }
            EventWaitHandle? oldQuit = null;
            try { oldQuit = EventWaitHandle.OpenExisting(QuitEventName); } catch { }
            if (oldQuit == null)
            {
                Log("An older copy (before 1.1.0) is running - asked him to quit it by hand.");
                MessageBox.Show(
                    "An older BingeMote is already running. Right-click its icon by the clock (click the ^ arrow if you don't see it) and choose Quit, then open this one again.",
                    "BingeMote", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }
            Log("Update launched while an older copy ran - asked it to quit, taking over.");
            oldQuit.Set();
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
                    "The older BingeMote is still closing. Open this one again in a few seconds.",
                    "BingeMote", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }
        }

        Directory.CreateDirectory(DataDir);
        LoadConfig();
        Log("Started. " + (!Paired ? "Not paired - showing a pairing code." : "Paired (" + Phones.Count + (Phones.Count == 1 ? " phone" : " phones") + ") - starting tucked in the tray."));
        RegisterAutostart();

        if (!File.Exists(FirewallMarker))
        {
            // The Setup download adds the rule and listen permission during install; a
            // first run of the plain exe adds them here with one admin prompt.
            if (FirewallSetup.RuleExists() && FirewallSetup.UrlAclExists())
            {
                try { File.WriteAllText(FirewallMarker, DateTime.UtcNow.ToString("O")); } catch { }
            }
            else
            {
                FirewallSetup.ExplainAndRelaunch();
            }
        }

        // A live pairing code is a way in, so one exists only when this PC still needs
        // pairing. After pairing, a new code comes only from the window's Pair button.
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
        else if (!Paired)
        {
            PendingPhoneName = null;
            PairCode = NewPairCode();
            PairCodeExpires = DateTime.UtcNow + PairCodeLifetime;
        }

        using var http = new HttpServer();
        using var discovery = new DiscoveryResponder();
        CheckForUpdate();
        try { http.Start(); discovery.Start(); }
        catch (Exception ex) {
            Log("Could not start listening: " + ex.Message);
            MessageBox.Show("Could not start listening for the phone. If Windows asked for administrator permission earlier, choose Yes - it is needed once to open the port.\n\n" + ex.Message,
                "BingeMote", MessageBoxButtons.OK, MessageBoxIcon.Error);
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
    /// <summary>The exe path of the other running copy, or null when there is none or it cannot be read.</summary>
    private static string? RunningCopyPath()
    {
        try
        {
            var me = Environment.ProcessId;
            foreach (var proc in Process.GetProcessesByName("BingeMote").Concat(Process.GetProcessesByName("BingeMoteHelper")))
            {
                if (proc.Id == me) continue;
                try { return proc.MainModule?.FileName; } catch { return null; }
            }
        }
        catch { }
        return null;
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
            Phones.Clear();
            DeclinedNetworks.Clear();
            if (doc.RootElement.TryGetProperty("phones", out var ps))
                foreach (var p in ps.EnumerateArray())
                {
                    var t = p.TryGetProperty("token", out var pt) ? pt.GetString() : null;
                    if (t == null) continue;
                    var n = p.TryGetProperty("name", out var pn) ? pn.GetString() : null;
                    Phones.Add(new PairedPhone { Token = t, Name = string.IsNullOrWhiteSpace(n) ? "Phone " + (Phones.Count + 1) : n });
                }
            // 1.2 stored bare tokens without names; those phones show as "Phone 1" etc.
            if (Phones.Count == 0 && doc.RootElement.TryGetProperty("tokens", out var ts))
                foreach (var t in ts.EnumerateArray())
                {
                    var v = t.GetString();
                    if (v != null) Phones.Add(new PairedPhone { Token = v, Name = "Phone " + (Phones.Count + 1) });
                }
            // 1.0/1.1 configs stored one token; keep that phone paired.
            if (Phones.Count == 0 && doc.RootElement.TryGetProperty("token", out var old))
            {
                var v = old.GetString();
                if (v != null) Phones.Add(new PairedPhone { Token = v, Name = "Phone 1" });
            }
            if (doc.RootElement.TryGetProperty("declinedNetworks", out var dn))
                foreach (var n in dn.EnumerateArray())
                {
                    var v = n.GetString();
                    if (!string.IsNullOrWhiteSpace(v)) DeclinedNetworks.Add(v);
                }
            MoviesFolder = doc.RootElement.TryGetProperty("moviesFolder", out var mf) ? mf.GetString() : null;
        }
        catch { Phones.Clear(); DeclinedNetworks.Clear(); MoviesFolder = null; }
    }

    internal static void SaveConfig()
    {
        var json = JsonSerializer.Serialize(new
        {
            phones = Phones.Select(p => new { token = p.Token, name = p.Name }).ToArray(),
            moviesFolder = MoviesFolder,
            declinedNetworks = DeclinedNetworks.ToArray(),
        });
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
            key?.DeleteValue("BingeMoteHelper", false); // the 1.x entry name
            key?.SetValue("BingeMote", "\"" + Environment.ProcessPath + "\"");
        }
        catch { /* autostart is a convenience, not a requirement */ }
    }
}


/// <summary>The helper window: the movies folder and pairing. Sits in the tray; closing it
/// only hides it. Follows the Windows dark/light mode setting, repainting live when it changes.</summary>
internal sealed class HelperWindow : Form
{
    // The app's palette: dark mode uses the app's colors, light mode their light counterparts.
    internal static readonly Color Orange = Color.FromArgb(255, 149, 0);
    internal static readonly Color OnOrange = Color.FromArgb(27, 27, 30);
    internal static readonly Color DarkBg = Color.FromArgb(27, 27, 30);
    internal static readonly Color DarkCard = Color.FromArgb(38, 38, 44);
    internal static readonly Color DarkText = Color.FromArgb(235, 235, 240);
    internal static readonly Color DarkMuted = Color.FromArgb(150, 150, 160);
    internal static readonly Color LightBg = Color.FromArgb(243, 243, 243);
    internal static readonly Color LightCard = Color.White;
    internal static readonly Color LightText = Color.FromArgb(26, 26, 26);
    internal static readonly Color LightMuted = Color.FromArgb(110, 110, 110);

    private bool _allowShow;
    private bool _updateAnnounced;
    private bool _hideHintShown;
    private readonly Label _title;
    private readonly Label _moviesLabel;
    private readonly TextBox _folderBox;
    private readonly Button _browse;
    private readonly Panel _sep1;
    private readonly Label _phonesHeader;
    private readonly FlowLayoutPanel _phoneList;
    private readonly Button _removeAll;
    private readonly Panel _sep2;
    private readonly Label _pairHeader;
    private readonly TextBox _nameBox;
    private readonly Button _pair;
    private readonly Label _codeCaption;
    private readonly Label _code;
    private readonly Label _codeNote;
    private readonly NotifyIcon _tray;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly ContextMenuStrip _menu;

    public HelperWindow()
    {
        Text = "BingeMote";
        Width = 620; Height = 500;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        try { Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!); } catch { }

        _title = new Label
        {
            Text = "BingeMote",
            Font = new Font("Segoe UI", 14, FontStyle.Bold),
            AutoSize = true, Left = 20, Top = 14,
        };
        _moviesLabel = new Label
        {
            Text = "Movies folder (what the phone's Movies tab opens):",
            Font = new Font("Segoe UI", 9),
            AutoSize = true, Left = 20, Top = 52,
        };
        _folderBox = new TextBox
        {
            ReadOnly = true,
            Left = 20, Top = 74, Width = 410,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Segoe UI", 9),
        };
        _browse = MakeButton("Browse...", 440, 72, 110);
        _browse.Click += (_, _) => PickFolder();
        _sep1 = new Panel { Left = 20, Top = 110, Width = 545, Height = 1 };
        _phonesHeader = new Label
        {
            Text = "Paired phones",
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            AutoSize = true, Left = 20, Top = 122,
        };
        _phoneList = new FlowLayoutPanel
        {
            Left = 20, Top = 148, Width = 545, Height = 108,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
        };
        _removeAll = MakeButton("Remove all phones...", 20, 264, 170);
        _removeAll.Click += (_, _) => RemoveAllPhones();
        _sep2 = new Panel { Left = 20, Top = 308, Width = 545, Height = 1 };
        _pairHeader = new Label
        {
            Text = "Pair new phone",
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            AutoSize = true, Left = 20, Top = 320,
        };
        _nameBox = new TextBox
        {
            Left = 20, Top = 346, Width = 280,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Segoe UI", 9),
        };
        _pair = MakeButton("Pair", 312, 344, 90);
        _pair.Enabled = false;
        _pair.Click += (_, _) => StartPairing();
        _nameBox.TextChanged += (_, _) => _pair.Enabled = _nameBox.Text.Trim().Length > 0;
        _codeCaption = new Label
        {
            Text = "Pairing code:",
            Font = new Font("Segoe UI", 9),
            AutoSize = true, Left = 20, Top = 392,
        };
        _code = new Label
        {
            Text = Program.PairCode ?? "------",
            Font = new Font("Consolas", 24, FontStyle.Bold),
            ForeColor = Orange,
            AutoSize = true, Left = 130, Top = 382,
        };
        _codeNote = new Label
        {
            Text = "Type it on the phone. Works once, for 10 minutes.",
            Font = new Font("Segoe UI", 8),
            AutoSize = true, Left = 20, Top = 428,
        };

        Controls.AddRange(new Control[]
        {
            _title, _moviesLabel, _folderBox, _browse, _sep1,
            _phonesHeader, _phoneList, _removeAll, _sep2,
            _pairHeader, _nameBox, _pair, _codeCaption, _code, _codeNote,
        });

        _menu = new ContextMenuStrip();
        _menu.Items.Add("Show BingeMote", null, (_, _) => ShowFromTray());
        _menu.Items.Add("Quit BingeMote", null, (_, _) => { Program.Log("Quit from the tray menu."); Application.Exit(); });
        _tray = new NotifyIcon
        {
            Text = "BingeMote",
            Icon = Icon,
            Visible = true,
            ContextMenuStrip = _menu,
        };
        _tray.DoubleClick += (_, _) => ShowFromTray();
        _tray.BalloonTipClicked += (_, _) => OpenUpdatePage();

        // The server adds and drops phones off-thread; repaint the list when that happens.
        Program.PhonesChanged = () =>
        {
            try { BeginInvoke(new Action(() => { RefreshPhones(); TickStatus(); })); } catch { }
        };

        // Public Wi-Fi silently blocks the phone: check at launch and whenever the network
        // changes, asking at most once per Wi-Fi name.
        Action<NetProfile> showWifi = p =>
        {
            try { BeginInvoke(new Action(() => ShowPublicWifiDialog(p))); } catch { }
        };
        Task.Run(() => { try { NetworkCheck.CheckNow(showWifi); } catch { /* never stop startup */ } });
        System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged += (_, _) =>
        {
            try { NetworkCheck.QueueCheck(showWifi); } catch { }
        };

        // Follow the Windows dark/light mode setting, live.
        Microsoft.Win32.SystemEvents.UserPreferenceChanged += (_, _) =>
        {
            try { BeginInvoke(new Action(ApplyTheme)); } catch { }
        };
        ApplyTheme();
        RefreshPhones();

        // Daily re-check for updates while running.
        var daily = new System.Windows.Forms.Timer { Interval = 24 * 60 * 60 * 1000 };
        daily.Tick += (_, _) => { Program.CheckForUpdate(); AnnounceUpdateIfNeeded(); };
        daily.Start();
        // The startup update check finishes on its own task; announce it once it lands.
        Task.Run(async () =>
        {
            await Task.Delay(20000);
            try { BeginInvoke(new Action(AnnounceUpdateIfNeeded)); } catch { }
        });

        // The pairing-code countdown only matters while the window is on screen, so this
        // timer sleeps whenever the window is tucked in the tray: with the window hidden the
        // helper wakes only for the phone, the network check, or the daily update check.
        _timer = new System.Windows.Forms.Timer { Interval = 1000 };
        _timer.Tick += (_, _) => TickStatus();
        // First launch with the window already on screen (unpaired PCs): start ticking now.
        if (Visible) { TickStatus(); _timer.Start(); }
    }

    /// <summary>Roll an expired pairing code (window-visible work).</summary>
    private void TickStatus()
    {
        if (Program.PairCode != null && DateTime.UtcNow > Program.PairCodeExpires)
        {
            Program.PairCode = Program.NewPairCode();
            Program.PairCodeExpires = DateTime.UtcNow + Program.PairCodeLifetime;
            Program.PendingPhoneName = null; // an expired code keeps no name
        }
        _code.Text = Program.PairCode ?? "------";
    }

    /// <summary>Rebuild the paired-phones list from the current config.</summary>
    private void RefreshPhones()
    {
        var dark = IsDarkMode();
        var card = dark ? DarkCard : LightCard;
        var text = dark ? DarkText : LightText;
        var muted = dark ? DarkMuted : LightMuted;
        _phoneList.Controls.Clear();
        foreach (var ph in Program.Phones.ToArray())
        {
            var row = new Panel { Width = 525, Height = 34, Margin = new Padding(0, 0, 0, 6), BackColor = card };
            var name = new Label
            {
                Text = ph.Name,
                AutoSize = true, Left = 10, Top = 8,
                Font = new Font("Segoe UI", 9, FontStyle.Bold),
                ForeColor = text, BackColor = card,
            };
            var remove = new Button
            {
                Text = "Remove", Width = 90, Height = 26, Left = 425, Top = 4,
                FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 8.5f),
                BackColor = card, ForeColor = text,
            };
            remove.FlatAppearance.BorderColor = muted;
            var phone = ph;
            remove.Click += (_, _) => RemovePhone(phone);
            row.Controls.Add(name);
            row.Controls.Add(remove);
            _phoneList.Controls.Add(row);
        }
        _removeAll.Visible = Program.Phones.Count > 0;
    }

    private void RemovePhone(Program.PairedPhone ph)
    {
        if (!ConfirmDialog.Ask(this,
                "Remove " + ph.Name + "? It stops controlling this PC right away. You can pair it again any time with a new code.",
                "Remove")) return;
        Program.Phones.Remove(ph);
        Program.SaveConfig();
        Program.Log("Phone removed: " + ph.Name + " (" + Program.Phones.Count + " left).");
        RefreshPhones();
    }

    private void RemoveAllPhones()
    {
        if (Program.Phones.Count == 0) return;
        if (!ConfirmDialog.Ask(this,
                "Remove all phones? Every phone stops controlling this PC right away. Pair them again with new codes.",
                "Remove all")) return;
        Program.Phones.Clear();
        Program.SaveConfig();
        Program.Log("All phones removed.");
        RefreshPhones();
    }

    /// <summary>Type the phone's name, tap Pair, and the code for it appears below.</summary>
    private void StartPairing()
    {
        var name = _nameBox.Text.Trim();
        if (name.Length == 0) return;
        Program.PendingPhoneName = name;
        Program.PairCode = Program.NewPairCode();
        Program.PairCodeExpires = DateTime.UtcNow + Program.PairCodeLifetime;
        Program.PairFailures = 0;
        _nameBox.Clear();
        Program.Log("Pairing code created for \"" + name + "\".");
        TickStatus();
    }

    private void AnnounceUpdateIfNeeded()
    {
        if (Program.AvailableUpdate == null || _updateAnnounced) return;
        _updateAnnounced = true;
        _menu.Items.Insert(0, new ToolStripMenuItem("Download update v" + Program.AvailableUpdate, null, (_, _) => OpenUpdatePage()));
        _tray.BalloonTipTitle = "BingeMote update";
        _tray.BalloonTipText = "Version " + Program.AvailableUpdate + " is available. Click here to download it.";
        _tray.ShowBalloonTip(8000);
    }

    protected override void OnVisibleChanged(EventArgs e)
    {
        base.OnVisibleChanged(e);
        if (_timer == null) return;
        if (Visible) { TickStatus(); AnnounceUpdateIfNeeded(); _timer.Start(); }
        else _timer.Stop();
    }

    private static Button MakeButton(string text, int left, int top, int width) => new()
    {
        Text = text,
        Left = left, Top = top, Width = width, Height = 30,
        FlatStyle = FlatStyle.Flat,
        Font = new Font("Segoe UI", 8.5f),
    };

    internal static bool IsDarkMode()
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
        _moviesLabel.ForeColor = text;
        _folderBox.BackColor = card;
        _folderBox.ForeColor = muted;
        _sep1.BackColor = card;
        _sep2.BackColor = card;
        _phonesHeader.ForeColor = text;
        _phoneList.BackColor = bg;
        _pairHeader.ForeColor = text;
        _nameBox.BackColor = card;
        _nameBox.ForeColor = text;
        _codeCaption.ForeColor = text;
        _code.ForeColor = Orange;
        _codeNote.ForeColor = muted;
        foreach (var b in new[] { _browse, _removeAll, _pair })
        {
            b.BackColor = card;
            b.ForeColor = text;
            b.FlatAppearance.BorderColor = muted;
        }
        RefreshPhones();
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

    /// <summary>The public-Wi-Fi popup: names the network, one click marks it Private.</summary>
    private void ShowPublicWifiDialog(NetProfile profile)
    {
        using var dlg = new WifiDialog(profile);
        switch (dlg.ShowDialog(this))
        {
            case WifiDialog.Result.Home:
                if (NetworkCheck.MarkPrivate(profile))
                {
                    Program.DeclinedNetworks.RemoveAll(n => string.Equals(n, profile.Name, StringComparison.OrdinalIgnoreCase));
                    Program.SaveConfig();
                    Program.Log("Network \"" + profile.Name + "\" marked Private from the popup.");
                    NoteDialog.Show(this,
                        "\u2713 Done. Your phone can reach this PC now.\n\nThis Wi-Fi is now marked Private in Windows, and Windows remembers it.");
                }
                else
                {
                    NoteDialog.Show(this,
                        "The change did not go through. Opening the Windows setting so you can switch this Wi-Fi to Private by hand.");
                    OpenWifiSettings();
                }
                break;
            case WifiDialog.Result.Settings:
                OpenWifiSettings();
                break;
            case WifiDialog.Result.NotNow:
                Program.DeclinedNetworks.Add(profile.Name);
                Program.SaveConfig();
                Program.Log("Public-network popup declined for \"" + profile.Name + "\" - not asking again.");
                break;
        }
    }

    private static void OpenWifiSettings()
    {
        try
        {
            Process.Start(new ProcessStartInfo("ms-settings:network-wifi") { UseShellExecute = true });
        }
        catch { /* the popup text still tells him what to do */ }
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
        if (!_allowShow && Program.Paired) value = false;
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
            e.Cancel = true; // closing tucks it into the tray; the tray menu's Quit exits for real
            Hide();
            if (!_hideHintShown)
            {
                _hideHintShown = true;
                _tray.BalloonTipTitle = "BingeMote";
                _tray.BalloonTipText = "Still running down here by the clock. Right-click the icon to show the window or quit.";
                _tray.ShowBalloonTip(6000);
            }
            return;
        }
        _tray.Visible = false;
        base.OnFormClosing(e);
    }
}


/// <summary>The public-Wi-Fi popup: names the network and offers the one-click Private fix,
/// the manual Settings route, or Not now (remembered per Wi-Fi name).</summary>
internal sealed class WifiDialog : Form
{
    public enum Result { None, Home, Settings, NotNow }
    public Result Answer { get; private set; } = Result.None;

    public WifiDialog(NetProfile profile)
    {
        var dark = HelperWindow.IsDarkMode();
        var bg = dark ? HelperWindow.DarkBg : HelperWindow.LightBg;
        var card = dark ? HelperWindow.DarkCard : HelperWindow.LightCard;
        var text = dark ? HelperWindow.DarkText : HelperWindow.LightText;
        var muted = dark ? HelperWindow.DarkMuted : HelperWindow.LightMuted;

        Text = "BingeMote";
        Width = 520; Height = 330;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false; MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        TopMost = true; // at login other windows can cover it
        BackColor = bg;
        ForeColor = text;

        var body = new Label
        {
            Left = 20, Top = 18, Width = 460, Height = 160,
            Font = new Font("Segoe UI", 9.5f),
            ForeColor = text,
            Text = "Windows is treating this Wi-Fi (" + profile.Name + ") like a public hotspot, so your phone cannot reach this PC.\n\n" +
                   "If this is your home network, BingeMote can mark it as Private in Windows for you. This also turns on Windows features meant for home networks, like printer and file sharing.\n\n" +
                   "On a network you don't know (hotel, cafe), choose Not now.",
        };
        var small = new Label
        {
            Left = 20, Top = 186, Width = 460, Height = 34,
            Font = new Font("Segoe UI", 8f),
            ForeColor = muted,
            Text = "This only changes this one Wi-Fi. Windows will ask for permission once.",
        };
        Button Btn(string label, int left, int width, Result r)
        {
            var b = new Button
            {
                Text = label, Left = left, Top = 240, Width = width, Height = 32,
                FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 8.5f),
                BackColor = card, ForeColor = text,
            };
            b.FlatAppearance.BorderColor = muted;
            b.Click += (_, _) => { Answer = r; Close(); };
            return b;
        }
        Controls.Add(body);
        Controls.Add(small);
        Controls.Add(Btn("This is my home Wi-Fi", 20, 170, Result.Home));
        Controls.Add(Btn("Open Settings instead", 198, 150, Result.Settings));
        Controls.Add(Btn("Not now", 356, 90, Result.NotNow));
    }

    public new Result ShowDialog(IWin32Window owner)
    {
        base.ShowDialog(owner);
        return Answer;
    }
}


/// <summary>A small themed box whose confirm button says what it does (MessageBox can't relabel).</summary>
internal static class ConfirmDialog
{
    public static bool Ask(IWin32Window owner, string text, string confirmLabel)
    {
        var dark = HelperWindow.IsDarkMode();
        var bg = dark ? HelperWindow.DarkBg : HelperWindow.LightBg;
        var card = dark ? HelperWindow.DarkCard : HelperWindow.LightCard;
        var fg = dark ? HelperWindow.DarkText : HelperWindow.LightText;
        var muted = dark ? HelperWindow.DarkMuted : HelperWindow.LightMuted;
        var answer = false;
        using var dlg = new Form
        {
            Text = "BingeMote",
            Width = 460, Height = 200,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false, MinimizeBox = false,
            StartPosition = FormStartPosition.CenterParent,
            BackColor = bg, ForeColor = fg,
        };
        var body = new Label
        {
            Left = 20, Top = 18, Width = 400, Height = 80,
            Font = new Font("Segoe UI", 9.5f), ForeColor = fg, Text = text,
        };
        var yes = new Button
        {
            Text = confirmLabel, Left = 190, Top = 112, Width = 110, Height = 30,
            FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 8.5f),
            BackColor = card, ForeColor = fg, DialogResult = DialogResult.OK,
        };
        yes.FlatAppearance.BorderColor = muted;
        var no = new Button
        {
            Text = "Cancel", Left = 310, Top = 112, Width = 90, Height = 30,
            FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 8.5f),
            BackColor = card, ForeColor = fg, DialogResult = DialogResult.Cancel,
        };
        no.FlatAppearance.BorderColor = muted;
        dlg.Controls.Add(body);
        dlg.Controls.Add(yes);
        dlg.Controls.Add(no);
        dlg.AcceptButton = yes;
        dlg.CancelButton = no;
        answer = dlg.ShowDialog(owner) == DialogResult.OK;
        return answer;
    }
}


/// <summary>A one-button themed note (the Wi-Fi popup's success line).</summary>
internal static class NoteDialog
{
    public static void Show(IWin32Window owner, string text)
    {
        var dark = HelperWindow.IsDarkMode();
        var bg = dark ? HelperWindow.DarkBg : HelperWindow.LightBg;
        var card = dark ? HelperWindow.DarkCard : HelperWindow.LightCard;
        var fg = dark ? HelperWindow.DarkText : HelperWindow.LightText;
        var muted = dark ? HelperWindow.DarkMuted : HelperWindow.LightMuted;
        using var dlg = new Form
        {
            Text = "BingeMote",
            Width = 460, Height = 200,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false, MinimizeBox = false,
            StartPosition = FormStartPosition.CenterParent,
            BackColor = bg, ForeColor = fg,
        };
        var body = new Label
        {
            Left = 20, Top = 18, Width = 400, Height = 90,
            Font = new Font("Segoe UI", 9.5f), ForeColor = fg, Text = text,
        };
        var ok = new Button
        {
            Text = "OK", Left = 310, Top = 116, Width = 90, Height = 30,
            FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 8.5f),
            BackColor = card, ForeColor = fg, DialogResult = DialogResult.OK,
        };
        ok.FlatAppearance.BorderColor = muted;
        dlg.Controls.Add(body);
        dlg.Controls.Add(ok);
        dlg.AcceptButton = ok;
        dlg.CancelButton = ok;
        dlg.ShowDialog(owner);
    }
}
