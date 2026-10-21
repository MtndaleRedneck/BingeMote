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
    /// <summary>Guards Phones: the window mutates it on the UI thread while listener threads enumerate it.</summary>
    internal static readonly object PhonesLock = new();
    internal static PairedPhone[] PhonesSnapshot() { lock (PhonesLock) { return Phones.ToArray(); } }
    internal static int PhonesCount { get { lock (PhonesLock) { return Phones.Count; } } }
    /// <summary>The name typed for the phone that is about to pair; consumed by a successful pairing.</summary>
    internal static string? PendingPhoneName;
    /// <summary>The window re-reads the phone list on the UI thread when this fires (any thread).</summary>
    internal static Action? PhonesChanged;
    /// <summary>Wi-Fi names where he chose "Not now" on the public-network prompt; not asked again.</summary>
    internal static readonly List<string> DeclinedNetworks = new();
    /// <summary>Wi-Fi names he marked as home. A home network is never buried by "Not now":
    /// if it shows as Public again, it is asked about again.</summary>
    internal static readonly List<string> HomeNetworks = new();
    /// <summary>More phones than this on one PC is a mistake, not a household.</summary>
    internal const int MaxPhones = 8;
    internal static bool Paired => PhonesCount > 0;
    /// <summary>The folder the phone's Movies tab opens; set from this window or from the phone.</summary>
    internal static string? MoviesFolder;
    internal static string? PairCode;
    /// <summary>A newer published helper version, found by the background update check.</summary>
    internal static (string Version, string ExeUrl, string? Sha256)? AvailableUpdate;
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

#if WINDOW_SHOT_HOOK
        // CI-only: render the window (Wi-Fi strip + first-run line visible) to a PNG and exit.
        if (args.Contains("--window-shot"))
        {
            Directory.CreateDirectory(DataDir);
            LoadConfig();
            lock (PhonesLock) { Phones.Clear(); }
            ApplicationConfiguration.Initialize();
            using var shot = new HelperWindow(windowShot: true);
            var shotPath = Environment.GetEnvironmentVariable("BINGEMOTE_WINDOW_SHOT")
                ?? Path.Combine(DataDir, "window.png");
            shot.ShowOffscreenAndCapture(shotPath);
            return 0;
        }
#endif

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
        Log("Started. " + (!Paired ? "Not paired - showing a pairing code." : "Paired (" + PhonesCount + (PhonesCount == 1 ? " phone" : " phones") + ") - starting tucked in the tray."));
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
            lock (PhonesLock)
            {
            Phones.Clear();
            DeclinedNetworks.Clear();
            HomeNetworks.Clear();
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
            if (doc.RootElement.TryGetProperty("homeNetworks", out var hn))
                foreach (var n in hn.EnumerateArray())
                {
                    var v = n.GetString();
                    if (!string.IsNullOrWhiteSpace(v)) HomeNetworks.Add(v);
                }
            MoviesFolder = doc.RootElement.TryGetProperty("moviesFolder", out var mf) ? mf.GetString() : null;
            }
        }
        catch { lock (PhonesLock) { Phones.Clear(); } DeclinedNetworks.Clear(); HomeNetworks.Clear(); MoviesFolder = null; }
    }

    internal static void SaveConfig()
    {
        var json = JsonSerializer.Serialize(new
        {
            phones = PhonesSnapshot().Select(p => new { token = p.Token, name = p.Name }).ToArray(),
            moviesFolder = MoviesFolder,
            declinedNetworks = DeclinedNetworks.ToArray(),
            homeNetworks = HomeNetworks.ToArray(),
        });
        File.WriteAllText(ConfigPath, json);
    }

    internal static string MachineLabel() => Environment.MachineName;

    /// <summary>"1.3.1" - the running build, for the Updates card and feedback reports.</summary>
    internal static string VersionText
    {
        get
        {
            var v = typeof(Program).Assembly.GetName().Version;
            return v == null ? "0.0.0" : v.Major + "." + v.Minor + "." + v.Build;
        }
    }

    /// <summary>Background update check: on launch, and the window re-runs it daily.</summary>
    internal static void CheckForUpdate()
    {
        Task.Run(() =>
        {
            try
            {
                var current = typeof(Program).Assembly.GetName().Version?.ToString() ?? "0.0.0";
                var newer = UpdateCheck.LatestNewerThan(current);
                if (newer != null) Program.AvailableUpdate = newer.Value;
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
    internal static readonly Color DarkCard2 = Color.FromArgb(46, 46, 54);
    internal static readonly Color DarkText = Color.FromArgb(235, 235, 240);
    internal static readonly Color DarkMuted = Color.FromArgb(150, 150, 160);
    internal static readonly Color LightBg = Color.FromArgb(243, 243, 243);
    internal static readonly Color LightCard = Color.White;
    internal static readonly Color LightText = Color.FromArgb(26, 26, 26);
    internal static readonly Color LightMuted = Color.FromArgb(110, 110, 110);

    private bool _allowShow;
    private bool _updateAnnounced;
    private bool _hideHintShown;
    private bool _manualDownload;
    private readonly Label _pairHeader;
    private readonly TextBox _nameBox;
    private readonly Button _pair;
    private readonly Label _codeCaption;
    private readonly Label _code;
    private readonly Label _codeNote;
    private readonly Label _phonesHeader;
    private readonly FlowLayoutPanel _phoneList;
    private readonly Button _removeAll;
    private readonly Label _aboutHeader;
    private readonly Panel _feedbackCard;
    private readonly Button _feedbackBtn;
    private readonly Panel _updatesCard;
    private readonly Label _updatesCaption;
    private readonly Button _checkUpdates;
    private readonly NotifyIcon _tray;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly ContextMenuStrip _menu;
    private readonly LinkLabel _firstRun;
    private readonly Panel _wifiBorder;
    private readonly Label _wifiNote;
    private readonly Button _wifiBtn;
    private NetProfile? _wifiProfile;
    private bool _wifiStripVisible;
    private bool _wifiDialogOpen;
    private readonly System.Windows.Forms.Timer? _wifiTimer;
    private readonly Dictionary<Control, int> _baseTops = new();
    private int _baseHeight;
    private bool _downloading;

    public HelperWindow(bool windowShot = false)
    {
        Text = "BingeMote";
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        try { Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!); } catch { }

        const int margin = 24;
        const int contentW = 490 - margin * 2;
        var y = margin;

        _pairHeader = Header("Pair new phone", margin, y); y += 20 + 12;
        _firstRun = new LinkLabel
        {
            Text = "New here? Get the BingeMote app on your phone, then give the phone a name and tap Pair.",
            Font = new Font("Segoe UI", 8f),
            AutoSize = true, Left = margin, Top = y,
            LinkColor = Orange, ActiveLinkColor = Orange, VisitedLinkColor = Orange,
        };
        _firstRun.Links.Add(18, 13, "https://mtndaleredneck.github.io/BingeMote/");
        _firstRun.LinkClicked += (_, e) =>
        {
            try { Process.Start(new ProcessStartInfo((string)e.Link!.LinkData!) { UseShellExecute = true }); } catch { }
        };
        y += 18;
        _nameBox = new TextBox
        {
            Left = margin, Top = y + 3, Width = contentW - 60 - 8,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Segoe UI", 10),
            PlaceholderText = "Phone name",
        };
        Round(_nameBox, 4);
        _pair = MakeButton("Pair", margin + contentW - 60, y, 60);
        _pair.Height = 32;
        _pair.Enabled = false;
        StylePrimary(_pair);
        _pair.Click += (_, _) => StartPairing();
        _nameBox.TextChanged += (_, _) => _pair.Enabled = _nameBox.Text.Trim().Length > 0;
        y += 32 + 12;
        _codeCaption = new Label
        {
            Text = "Pairing code:",
            Font = new Font("Segoe UI", 9),
            AutoSize = true, Left = margin, Top = y + 8,
        };
        _code = new Label
        {
            Text = Program.PairCode ?? "------",
            Font = new Font("Consolas", 15, FontStyle.Bold),
            ForeColor = Orange,
            AutoSize = true, Left = margin + 118, Top = y,
        };
        y += 28 + 6;
        _codeNote = new Label
        {
            Text = "Enter this code in the BingeMote app on your phone. Works once, for 10 minutes.",
            Font = new Font("Segoe UI", 8),
            AutoSize = true, Left = margin, Top = y,
        };
        y += 16 + 24;

        _phonesHeader = Header("Paired phones", margin, y); y += 20 + 12;
        _phoneList = new FlowLayoutPanel
        {
            Left = margin, Top = y, Width = contentW, Height = 3 * 52,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
        };
        y += 3 * 52 + 8;
        _removeAll = MakeButton("Remove all phones...", margin, y, 150);
        _removeAll.Height = 32;
        _removeAll.Click += (_, _) => RemoveAllPhones();
        y += 32 + 24;

        _aboutHeader = Header("About", margin, y); y += 20 + 12;
        _feedbackCard = new Panel { Left = margin, Top = y, Width = contentW, Height = 48 };
        Round(_feedbackCard, 8);
        var feedbackLabel = new Label
        {
            Text = "Feedback & bugs",
            Font = new Font("Segoe UI", 10.5f),
            AutoSize = true, Left = 14, Top = 14,
            UseMnemonic = false,
        };
        _feedbackBtn = MakeButton("Send feedback...", contentW - 130 - 10, 9, 130);
        _feedbackBtn.Height = 30;
        _feedbackBtn.Click += (_, _) => { using var dlg = new FeedbackDialog(); dlg.ShowDialog(this); };
        _feedbackCard.Controls.Add(feedbackLabel);
        _feedbackCard.Controls.Add(_feedbackBtn);
        y += 48 + 4;
        _updatesCard = new Panel { Left = margin, Top = y, Width = contentW, Height = 48 };
        Round(_updatesCard, 8);
        var updatesLabel = new Label
        {
            Text = "Updates",
            Font = new Font("Segoe UI", 10.5f),
            AutoSize = true, Left = 14, Top = 6,
        };
        _updatesCaption = new Label
        {
            Text = "Version " + Program.VersionText,
            Font = new Font("Segoe UI", 8),
            AutoSize = true, Left = 14, Top = 26,
        };
        _checkUpdates = MakeButton("Check for updates", contentW - 140 - 10, 9, 140);
        _checkUpdates.Height = 30;
        _checkUpdates.Click += (_, _) => ManualUpdateCheck();
        _updatesCard.Controls.Add(updatesLabel);
        _updatesCard.Controls.Add(_updatesCaption);
        _updatesCard.Controls.Add(_checkUpdates);
        y += 48 + margin;

        ClientSize = new Size(490, y);

        // The public-Wi-Fi strip: sits at the top of the window while the network is
        // public, so the fix is never buried. The 30s poll keeps it in sync.
        _wifiBorder = new Panel
        {
            Left = margin, Top = margin, Width = contentW, Height = 62,
            BackColor = Orange, Visible = false, Padding = new Padding(1),
        };
        var wifiCard = new Panel { Dock = DockStyle.Fill };
        var wifiText = new Label
        {
            Text = "Windows is treating this Wi-Fi as public, so it's blocking your phone.",
            Font = new Font("Segoe UI", 9f),
            Left = 12, Top = 8, Width = contentW - 12 - 160 - 16, Height = 62 - 16,
        };
        _wifiBtn = MakeButton("This is my home Wi-Fi", contentW - 160 - 10, 7, 160);
        _wifiBtn.Height = 28;
        StylePrimary(_wifiBtn);
        _wifiBtn.Click += (_, _) => FixHomeWifi();
        _wifiNote = new Label
        {
            Text = "(Windows will ask for permission)",
            Font = new Font("Segoe UI", 7.5f),
            Width = 160, TextAlign = ContentAlignment.TopCenter,
            Left = contentW - 160 - 10, Top = 40, Height = 16,
        };
        wifiCard.Controls.Add(wifiText);
        wifiCard.Controls.Add(_wifiBtn);
        wifiCard.Controls.Add(_wifiNote);
        _wifiBorder.Controls.Add(wifiCard);
        Round(wifiCard, 7);
        Round(_wifiBorder, 8);

        Controls.AddRange(new Control[]
        {
            _wifiBorder,
            _pairHeader, _firstRun, _nameBox, _pair, _codeCaption, _code, _codeNote,
            _phonesHeader, _phoneList, _removeAll,
            _aboutHeader, _feedbackCard, _updatesCard,
        });
        foreach (Control c in Controls) _baseTops[c] = c.Top;
        _baseHeight = y;

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
        _tray.BalloonTipClicked += (_, _) => { ShowFromTray(); StartUpdateDownload(); };

        // The server adds and drops phones off-thread; repaint the list when that happens.
        Program.PhonesChanged = () =>
        {
            try { BeginInvoke(new Action(() => { RefreshPhones(); TickStatus(); })); } catch { }
        };

        // Public Wi-Fi silently blocks the phone: an in-window strip with the one-click fix
        // stays up while the network is public, and the popup still asks at launch and on
        // network changes (at most once per Wi-Fi name, except home networks, which re-ask).
        Action<NetProfile> showWifi = p =>
        {
            try { BeginInvoke(new Action(() => { _wifiProfile = p; SetWifiStripVisible(true); ShowPublicWifiDialog(p); })); } catch { }
        };
        if (windowShot)
        {
            _wifiProfile = new NetProfile { Name = "Home Wi-Fi", Category = "Public" };
            SetWifiStripVisible(true);
        }
        else
        Task.Run(() => { try { NetworkCheck.CheckNow(showWifi); } catch { /* never stop startup */ } });
        System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged += (_, _) =>
        {
            try { NetworkCheck.QueueCheck(showWifi); } catch { }
        };
        // A profile flipped to Private in Windows Settings raises no network event, so the
        // strip re-checks every 30 seconds. The same poll re-asks for a home network.
        if (!windowShot)
        {
            _wifiTimer = new System.Windows.Forms.Timer { Interval = 30000 };
            _wifiTimer.Tick += (_, _) => ProbeWifi();
            _wifiTimer.Start();
        }

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

    private const int StripH = 74;
    private const int FirstRunH = 18;

    /// <summary>Base layout + the two optional blocks (Wi-Fi strip on top, first-run line
    /// under the pair header): everything below a visible block shifts down.</summary>
    private void ApplyLayout()
    {
        if (_baseHeight == 0 || _firstRun == null || _wifiBorder == null) return;
        var stripDy = _wifiStripVisible ? StripH : 0;
        var firstRunDy = _firstRun.Visible ? 0 : -FirstRunH;
        var frTop = _baseTops[_firstRun];
        foreach (Control c in Controls)
        {
            if (c == _wifiBorder) { c.Top = _baseTops[c]; continue; }
            var dy = stripDy;
            if (_baseTops[c] > frTop) dy += firstRunDy;
            c.Top = _baseTops[c] + dy;
        }
        ClientSize = new Size(490, _baseHeight + stripDy + firstRunDy);
    }

    private void SetWifiStripVisible(bool show)
    {
        if (show == _wifiStripVisible) return;
        _wifiStripVisible = show;
        _wifiBorder.Visible = show;
        ApplyLayout();
    }

    /// <summary>Which home network already got its one re-ask for this Public flip; cleared
    /// when the network is Private again.</summary>
    private string? _homeReasked;

    /// <summary>The 30-second profile re-check: sync the strip with reality (a network fixed
    /// in Windows Settings raises no event). The popup itself belongs to launch and real
    /// network changes; the only popup from the poll is one re-ask per Public flip for a
    /// network marked home, which "Not now" must not bury.</summary>
    private void ProbeWifi()
    {
        if (_wifiDialogOpen) return;
        Task.Run(() =>
        {
            var p = NetworkCheck.DefaultRouteProfile();
            var isPublic = p != null && p.Category.Equals("Public", StringComparison.OrdinalIgnoreCase);
            try { BeginInvoke(new Action(() =>
            {
                _wifiProfile = isPublic ? p : null;
                SetWifiStripVisible(isPublic);
                if (!isPublic || p == null) { _homeReasked = null; return; }
                var knownHome = Program.HomeNetworks.Any(n => string.Equals(n, p.Name, StringComparison.OrdinalIgnoreCase));
                if (knownHome && _homeReasked != p.Name)
                {
                    _homeReasked = p.Name;
                    ShowPublicWifiDialog(p);
                }
            })); } catch { }
        });
    }

    /// <summary>The strip's one-click fix: mark this Wi-Fi Private (one elevated Windows prompt).</summary>
    private void FixHomeWifi()
    {
        var profile = _wifiProfile;
        if (profile == null) return;
        _wifiBtn.Enabled = false;
        Task.Run(() =>
        {
            var ok = NetworkCheck.MarkPrivate(profile);
            try { BeginInvoke(new Action(() =>
            {
                _wifiBtn.Enabled = true;
                if (ok)
                {
                    Program.DeclinedNetworks.RemoveAll(n => string.Equals(n, profile.Name, StringComparison.OrdinalIgnoreCase));
                    if (!Program.HomeNetworks.Any(n => string.Equals(n, profile.Name, StringComparison.OrdinalIgnoreCase)))
                        Program.HomeNetworks.Add(profile.Name);
                    Program.SaveConfig();
                    Program.Log("Network \"" + profile.Name + "\" marked Private from the window strip.");
                    _wifiProfile = null;
                    SetWifiStripVisible(false);
                    NoteDialog.Show(this,
                        "\u2713 Done. Your phone can reach this PC now.\n\nThis Wi-Fi is now marked Private in Windows, and Windows remembers it.");
                }
                else
                {
                    NoteDialog.Show(this,
                        "The change did not go through. Opening the Windows setting so you can switch this Wi-Fi to Private by hand.");
                    OpenWifiSettings();
                }
            })); } catch { }
        });
    }

    /// <summary>CI-only: show the window offscreen, let layout settle, and save a PNG of it.
    /// DrawToBitmap renders in memory, so no visible desktop is needed.</summary>
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool PrintWindow(IntPtr hwnd, IntPtr hdcBlt, uint nFlags);

    internal void ShowOffscreenAndCapture(string path)
    {
        // Shown at (0,0): offscreen coordinates make DWM skip composing the window,
        // which is what dropped the placeholder and link text in earlier captures.
        StartPosition = FormStartPosition.Manual;
        Location = new Point(0, 0);
        ShowInTaskbar = false;
        Show();
        Activate();
        Application.DoEvents();
        System.Threading.Thread.Sleep(600);
        // Re-assert the final first-run state and re-run layout right before the shot:
        // whatever order the strip/phones events landed in, this is the settled state
        // a real desktop reaches after OnLoad.
        _firstRun.Visible = Program.PhonesCount == 0;
        ApplyLayout();
        Application.DoEvents();
        // Capture 1: DWM-composed (PW_RENDERFULLCONTENT) so cue banners and
        // LinkLabel text that WM_PRINT (DrawToBitmap) drops still show.
        using (var bmp = new Bitmap(Width, Height))
        {
            using (var g = Graphics.FromImage(bmp))
            {
                IntPtr hdc = g.GetHdc();
                bool captured = PrintWindow(Handle, hdc, 2);
                g.ReleaseHdc(hdc);
                if (!captured)
                {
                    g.Clear(BackColor);
                    DrawToBitmap(bmp, new Rectangle(0, 0, Width, Height));
                }
            }
            bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
        }
        // Diagnostics: is the Pair row really collapsed, or is the capture lying?
        try
        {
            var diagPath = System.IO.Path.Combine(
                System.IO.Path.GetDirectoryName(path) ?? ".", "window-diag.txt");
            var sb = new System.Text.StringBuilder();
            sb.AppendLine("DeviceDpi=" + DeviceDpi);
            foreach (var kv in _baseTops) sb.AppendLine("base " + kv.Key.Name + "=" + kv.Value);
            sb.AppendLine("AutoScaleMode=" + AutoScaleMode + " AutoScaleDimensions=" + CurrentAutoScaleDimensions);
            foreach (var (n, c) in new (string, Control)[] {
                ("firstRun", _firstRun), ("nameBox", _nameBox), ("pair", _pair),
                ("codeCaption", _codeCaption), ("wifiBorder", _wifiBorder) })
            {
                string region;
                try { using var g = Graphics.FromHwnd(IntPtr.Zero);
                      var r = c.Region?.GetBounds(g) ?? System.Drawing.RectangleF.Empty;
                      region = r.Width + "x" + r.Height; }
                catch { region = "?"; }
                sb.AppendLine(n + " bounds=" + c.Bounds + " region=" + region + " visible=" + c.Visible);
            }
            System.IO.File.WriteAllText(diagPath, sb.ToString());
        }
        catch { }
        // Capture 2: what the user would see on the desktop (needs an interactive
        // session on the runner; skipped silently where there is none).
        try
        {
            var screenPath = System.IO.Path.Combine(
                System.IO.Path.GetDirectoryName(path) ?? ".",
                System.IO.Path.GetFileNameWithoutExtension(path) + "-screen.png");
            using var bmp2 = new Bitmap(Width, Height);
            using var g2 = Graphics.FromImage(bmp2);
            g2.CopyFromScreen(Left, Top, 0, 0, new Size(Width, Height));
            bmp2.Save(screenPath, System.Drawing.Imaging.ImageFormat.Png);
        }
        catch { }
        Close();
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

    /// <summary>Rebuild the paired-phones list from the current config. Each phone is one
    /// settings-style card: name on the left, Remove inside at the right.</summary>
    private void RefreshPhones()
    {
        var dark = IsDarkMode();
        var card = dark ? DarkCard : LightCard;
        var btnBg = dark ? DarkCard2 : LightBg;
        var text = dark ? DarkText : LightText;
        _phoneList.Controls.Clear();
        var rowWidth = Program.PhonesCount > 3 ? 442 - 17 : 442; // leave room for the scroll bar
        foreach (var ph in Program.PhonesSnapshot())
        {
            var row = new Panel { Width = rowWidth, Height = 48, Margin = new Padding(0, 0, 0, 4), BackColor = card };
            Round(row, 8);
            var name = new Label
            {
                Text = ph.Name,
                AutoEllipsis = true, AutoSize = false,
                Left = 14, Top = 13, Width = rowWidth - 14 - 64 - 10 - 10, Height = 22,
                TextAlign = ContentAlignment.MiddleLeft,
                Font = new Font("Segoe UI", 10.5f),
                ForeColor = text, BackColor = card,
            };
            var remove = new Button
            {
                Text = "Remove", Width = 64, Height = 30, Left = rowWidth - 64 - 10, Top = 9,
                FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 8.5f),
                BackColor = btnBg, ForeColor = text,
            };
            remove.FlatAppearance.BorderSize = 0;
            Round(remove, 4);
            var phone = ph;
            remove.Click += (_, _) => RemovePhone(phone);
            row.Controls.Add(name);
            row.Controls.Add(remove);
            _phoneList.Controls.Add(row);
        }
        _removeAll.Visible = Program.PhonesCount > 0;
        _firstRun.Visible = Program.PhonesCount == 0;
        ApplyLayout();
    }

    private void RemovePhone(Program.PairedPhone ph)
    {
        if (!ConfirmDialog.Ask(this,
                "Remove " + ph.Name + "? It stops controlling this PC right away. You can pair it again any time with a new code.",
                "Remove")) return;
        lock (Program.PhonesLock) { Program.Phones.Remove(ph); }
        Program.SaveConfig();
        Program.Log("Phone removed: " + ph.Name + " (" + Program.PhonesCount + " left).");
        RefreshPhones();
    }

    private void RemoveAllPhones()
    {
        if (Program.PhonesCount == 0) return;
        if (!ConfirmDialog.Ask(this,
                "Remove all phones? Every phone stops controlling this PC right away. Pair them again with new codes.",
                "Remove all")) return;
        lock (Program.PhonesLock) { Program.Phones.Clear(); }
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
        var v = Program.AvailableUpdate.Value.Version;
        _menu.Items.Insert(0, new ToolStripMenuItem("Download update v" + v, null, (_, _) => { ShowFromTray(); StartUpdateDownload(); }));
        _tray.BalloonTipTitle = "BingeMote update";
        _tray.BalloonTipText = "Version " + v + " is available. Click here to download it.";
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
        var btnBg = dark ? DarkCard : LightCard;
        var cardBtn = dark ? DarkCard2 : LightBg;
        var text = dark ? DarkText : LightText;
        var muted = dark ? DarkMuted : LightMuted;
        BackColor = bg;
        ForeColor = text;
        _pairHeader.ForeColor = text;
        _phonesHeader.ForeColor = text;
        _aboutHeader.ForeColor = text;
        _nameBox.BackColor = card;
        _nameBox.ForeColor = text;
        _codeCaption.ForeColor = text;
        _code.ForeColor = Orange;
        _codeNote.ForeColor = muted;
        _phoneList.BackColor = bg;
        _firstRun.BackColor = bg;
        _firstRun.ForeColor = muted;
        _firstRun.LinkColor = Orange;
        _firstRun.ActiveLinkColor = Orange;
        _firstRun.VisitedLinkColor = Orange;
        _wifiBorder.BackColor = Orange;
        if (_wifiBorder.Controls.Count > 0)
        {
            var wc = _wifiBorder.Controls[0];
            wc.BackColor = card;
            foreach (Control c in wc.Controls)
            {
                if (c is Button) continue;
                c.BackColor = card;
                c.ForeColor = c == _wifiNote ? muted : text;
            }
        }
        Round(_wifiBorder, 8);
        _feedbackCard.BackColor = card;
        _updatesCard.BackColor = card;
        foreach (Control c in _feedbackCard.Controls) { c.BackColor = c is Button ? cardBtn : card; c.ForeColor = text; }
        foreach (Control c in _updatesCard.Controls) { if (c is Button) continue; c.BackColor = card; c.ForeColor = c == _updatesCaption ? muted : text; }
        _feedbackBtn.FlatAppearance.BorderSize = 0;
        _checkUpdates.FlatAppearance.BorderSize = 0;
        foreach (var b in new[] { _removeAll })
        {
            b.BackColor = btnBg;
            b.ForeColor = text;
            b.FlatAppearance.BorderColor = muted;
        }
        StylePrimary(_pair);
        if (_manualDownload) StylePrimary(_checkUpdates); else { _checkUpdates.BackColor = cardBtn; _checkUpdates.ForeColor = text; }
        Round(_feedbackCard, 8);
        Round(_updatesCard, 8);
        RefreshPhones();
        ApplyTitleBarTheme();
    }

    /// <summary>The window's title bar follows the Windows dark/light setting (immersive dark mode).</summary>
    internal void ApplyTitleBarTheme()
    {
        if (!IsHandleCreated) return;
        var dark = IsDarkMode() ? 1 : 0;
        try { DwmSetWindowAttribute(Handle, 20, ref dark, 4); } catch { /* older Windows */ }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        ApplyTitleBarTheme();
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    /// <summary>4px on buttons and boxes, 8px on cards - the Win11 corner radii.</summary>
    internal static void Round(Control c, int radius)
    {
        if (c.Width <= 0 || c.Height <= 0) return;
        var d = radius * 2;
        var path = new System.Drawing.Drawing2D.GraphicsPath();
        path.AddArc(0, 0, d, d, 180, 90);
        path.AddArc(c.Width - d - 1, 0, d, d, 270, 90);
        path.AddArc(c.Width - d - 1, c.Height - d - 1, d, d, 0, 90);
        path.AddArc(0, c.Height - d - 1, d, d, 90, 90);
        path.CloseFigure();
        c.Region = new Region(path);
    }

    /// <summary>The one orange button: the window's primary action.</summary>
    internal static void StylePrimary(Button b)
    {
        b.BackColor = Orange;
        b.ForeColor = OnOrange;
        b.FlatAppearance.BorderSize = 0;
        Round(b, 4);
    }

    private static Label Header(string text, int x, int y) => new()
    {
        Text = text,
        Font = new Font("Segoe UI", 10.5f, FontStyle.Bold),
        AutoSize = true, Left = x, Top = y,
    };

    /// <summary>The Updates card's manual check: result shows inline, never a tray balloon.</summary>
    private void ManualUpdateCheck()
    {
        if (_manualDownload) { StartUpdateDownload(); return; }
        _checkUpdates.Enabled = false;
        _updatesCaption.Text = "Checking...";
        Task.Run(() =>
        {
            (string Version, string ExeUrl, string? Sha256)? newer;
            try { newer = UpdateCheck.LatestNewerThanOrThrow(Program.VersionText); }
            catch
            {
                try { BeginInvoke(new Action(() =>
                {
                    _updatesCaption.Text = "Couldn't check right now. Try again later.";
                    _checkUpdates.Enabled = true;
                })); } catch { }
                return;
            }
            try { BeginInvoke(new Action(() =>
            {
                if (newer != null)
                {
                    Program.AvailableUpdate = newer.Value;
                    _updatesCaption.Text = "Version " + newer.Value.Version + " is available.";
                    _manualDownload = true;
                    _checkUpdates.Text = "Download";
                    _checkUpdates.Enabled = true;
                    StylePrimary(_checkUpdates);
                }
                else
                {
                    _updatesCaption.Text = "You're up to date. Nothing installs on its own.";
                    _checkUpdates.Enabled = true;
                }
            })); } catch { }
        });
    }

    /// <summary>Open the verified installer on the UI thread and quit so it can replace the
    /// running exe. A "no" at the Windows permission prompt cancels the launch quietly.</summary>
    private void LaunchUpdate(string path)
    {
        try
        {
            SelfUpdate.LaunchAndQuit(path);
        }
        catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            Program.Log("Update launch canceled at the Windows permission prompt.");
            _updatesCaption.Text = "Update canceled.";
            _checkUpdates.Enabled = true;
            _downloading = false;
        }
    }

    /// <summary>Download the update's installer, check it against the release's SHA-256, and
    /// open it. Nothing installs on its own: the installer still shows and Windows asks once.</summary>
    private void StartUpdateDownload()
    {
        var u = Program.AvailableUpdate;
        if (u == null || _downloading) return;
        _downloading = true;
        _checkUpdates.Enabled = false;
        _updatesCaption.Text = "Downloading version " + u.Value.Version + "...";
        var upd = u.Value;
        Task.Run(async () =>
        {
            try
            {
                var path = await SelfUpdate.Download(upd.Version, upd.ExeUrl, upd.Sha256);
                Program.Log("Update " + upd.Version + " downloaded and verified; opening the installer.");
                try { BeginInvoke(new Action(() =>
                {
                    _updatesCaption.Text = "Checked. Opening the installer - Windows will ask for permission.";
                    LaunchUpdate(path);
                })); } catch { }
            }
            catch
            {
                Program.Log("Update download failed or failed its check.");
                try { BeginInvoke(new Action(() =>
                {
                    _updatesCaption.Text = "The download didn't check out. Try again.";
                    _checkUpdates.Enabled = true;
                    _downloading = false;
                })); } catch { }
            }
        });
    }

    /// <summary>The public-Wi-Fi popup: names the network, one click marks it Private.</summary>
    private void ShowPublicWifiDialog(NetProfile profile)
    {
        if (_wifiDialogOpen) return;
        _wifiDialogOpen = true;
        try
        {
        using var dlg = new WifiDialog(profile);
        switch (dlg.ShowDialog(this))
        {
            case WifiDialog.Result.Home:
                if (NetworkCheck.MarkPrivate(profile))
                {
                    Program.DeclinedNetworks.RemoveAll(n => string.Equals(n, profile.Name, StringComparison.OrdinalIgnoreCase));
                    if (!Program.HomeNetworks.Any(n => string.Equals(n, profile.Name, StringComparison.OrdinalIgnoreCase)))
                        Program.HomeNetworks.Add(profile.Name);
                    Program.SaveConfig();
                    _wifiProfile = null;
                    SetWifiStripVisible(false);
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
                // Saying "Not now" on a network once marked home means it is not home after all.
                Program.HomeNetworks.RemoveAll(n => string.Equals(n, profile.Name, StringComparison.OrdinalIgnoreCase));
                Program.SaveConfig();
                Program.Log("Public-network popup declined for \"" + profile.Name + "\" - not asking again.");
                break;
        }
        }
        finally { _wifiDialogOpen = false; }
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

/// <summary>The Feedback & bugs dialog. Sends through the same two channels as the phone's
/// feedback page: a public GitHub issue or a private form. The preview shows exactly what goes out.</summary>
internal sealed class FeedbackDialog : Form
{
    private const string RepoIssues = "https://github.com/MtndaleRedneck/BingeMote/issues/new";
    private const string BugFormUrl = "https://docs.google.com/forms/d/e/1FAIpQLSeD1ytVTxoA0GgftLr5wSu2kZm1yxRwCHcOV4Pb100Lm6hLcQ/viewform";

    private readonly TextBox _message;
    private readonly Label _preview;
    private readonly Button _issue;
    private readonly Button _form;

    public FeedbackDialog()
    {
        Text = "BingeMote";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterParent;
        ClientSize = new Size(440, 420);
        Font = new Font("Segoe UI", 9.5f);

        var title = new Label { Text = "Feedback & bugs", Font = new Font("Segoe UI", 10.5f, FontStyle.Bold), AutoSize = true, Left = 24, Top = 16, UseMnemonic = false };
        _message = new TextBox
        {
            Multiline = true, Left = 24, Top = 44, Width = 392, Height = 96,
            PlaceholderText = "Your message", BorderStyle = BorderStyle.FixedSingle,
            ScrollBars = ScrollBars.Vertical,
        };
        var whatHeader = new Label { Text = "What will be sent", Font = new Font("Segoe UI", 9f, FontStyle.Bold), AutoSize = true, Left = 24, Top = 152 };
        _preview = new Label
        {
            Left = 24, Top = 172, Width = 392, Height = 176,
            Font = new Font("Segoe UI", 8.5f), AutoEllipsis = false,
        };
        _issue = new Button { Text = "Open GitHub issue", Width = 130, Height = 32, Left = 24, Top = 368, FlatStyle = FlatStyle.Flat, Enabled = false };
        _form = new Button { Text = "Open private form", Width = 130, Height = 32, Left = 440 - 24 - 130, Top = 368, FlatStyle = FlatStyle.Flat, Enabled = false };

        _message.TextChanged += (_, _) => RefreshPreview();
        _issue.Click += (_, _) => OpenUrl(IssueUrl());
        _form.Click += (_, _) => OpenUrl(FormUrl());

        Controls.Add(title);
        Controls.Add(_message);
        Controls.Add(whatHeader);
        Controls.Add(_preview);
        Controls.Add(_issue);
        Controls.Add(_form);
        ApplyTheme();
        RefreshPreview();
    }

    private void ApplyTheme()
    {
        var dark = HelperWindow.IsDarkMode();
        var bg = dark ? HelperWindow.DarkBg : HelperWindow.LightBg;
        var card = dark ? HelperWindow.DarkCard : HelperWindow.LightCard;
        var cardBtn = dark ? HelperWindow.DarkCard2 : HelperWindow.LightBg;
        var text = dark ? HelperWindow.DarkText : HelperWindow.LightText;
        var muted = dark ? HelperWindow.DarkMuted : HelperWindow.LightMuted;
        BackColor = bg;
        ForeColor = text;
        foreach (Control c in Controls) { c.ForeColor = text; }
        _message.BackColor = card;
        _message.ForeColor = text;
        _preview.ForeColor = muted;
        _issue.BackColor = cardBtn;
        _issue.ForeColor = text;
        _issue.FlatAppearance.BorderSize = 0;
        HelperWindow.StylePrimary(_form);
        HelperWindow.Round(_issue, 4);
        TitleBarTheme();
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        TitleBarTheme();
    }

    private void TitleBarTheme()
    {
        if (!IsHandleCreated) return;
        var dark = HelperWindow.IsDarkMode() ? 1 : 0;
        try { DwmSetWindowAttribute(Handle, 20, ref dark, 4); } catch { }
    }

    [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    private void RefreshPreview()
    {
        var msg = Clean(_message.Text, 3000);
        _preview.Text = msg + "\n\nBingeMote PC version: " + Program.VersionText +
            "\nWindows version: " + OsVersion() +
            "\nDevice: PC" +
            "\n\nNothing else. No PC name, user name, Wi-Fi, phones, or paths.";
        var has = !string.IsNullOrWhiteSpace(msg);
        _issue.Enabled = has;
        _form.Enabled = has;
    }

    private static string OsVersion()
    {
        var v = Environment.OSVersion.Version;
        return v.Build >= 22000 ? $"Windows 11 (build {v.Build})" : $"Windows 10 (build {v.Build})";
    }

    private static string Clean(string s, int max)
    {
        s = s.Trim();
        return s.Length <= max ? s : s.Substring(0, max);
    }

    private string IssueUrl()
    {
        var msg = Clean(_message.Text, 3000);
        var title = "Feedback: " + (msg.Length <= 60 ? msg : msg.Substring(0, 57) + "...");
        var body = msg + "\n\nBingeMote PC version: " + Program.VersionText + "\nWindows version: " + OsVersion();
        var url = RepoIssues + "?title=" + Uri.EscapeDataString(title) + "&body=" + Uri.EscapeDataString(body);
        return url.Length <= 8000 ? url : RepoIssues + "?title=" + Uri.EscapeDataString(title) + "&body=" + Uri.EscapeDataString(Clean(body, 4500));
    }

    private string FormUrl()
    {
        var msg = Clean(_message.Text, 3000);
        return BugFormUrl +
            "?entry.1980878269=" + Uri.EscapeDataString(msg) +
            "&entry.857472167=" + Uri.EscapeDataString("PC") +
            "&entry.2017214675=" + Uri.EscapeDataString(OsVersion()) +
            "&entry.446486963=" + Uri.EscapeDataString(Program.VersionText);
    }

    private static void OpenUrl(string url)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
    }
}

