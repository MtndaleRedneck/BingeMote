// BingeMote Helper: the small Windows companion for the BingeMote Android app.
// It listens on the home network for mouse, scroll, zoom and keyboard commands from the
// paired phone and plays them back with SendInput. See README.md for setup.
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using WinForms = System.Windows.Forms;

namespace BingeMoteHelper;

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
    // The subtle 1px edge on cards and the name box (Win11 settings-card look).
    internal static readonly Color DarkBorder = Color.FromArgb(56, 56, 64);
    internal static readonly Color LightBorder = Color.FromArgb(222, 222, 226);

    private bool _allowShow;
    private bool _updateAnnounced;
    private bool _manualDownload;
    private readonly Label _pairHeader;
    private readonly TextBox _nameBox;
    private readonly RoundedPanel _nameBoxPanel;
    private readonly Button _pair;
    private readonly Label _codeCaption;
    private readonly Label _code;
    private readonly Label _codeNote;
    private readonly Button _cancelPair;
    private readonly Label _phonesHeader;
    private readonly FlowLayoutPanel _phoneList;
    private readonly Button _removeAll;
    private readonly Label _aboutHeader;
    private readonly RoundedPanel _feedbackCard;
    private readonly Button _feedbackBtn;
    private readonly RoundedPanel _updatesCard;
    private readonly Label _updatesCaption;
    private readonly Label _updatesResult;
    private readonly Button _checkUpdates;
    private readonly NotifyIcon _tray;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly ContextMenuStrip _menu;
    private readonly LinkLabel _firstRun;
    private readonly RoundedPanel _wifiBorder;
    private readonly Label _wifiNote;
    private readonly Button _wifiBtn;
    private NetProfile? _wifiProfile;
    private bool _wifiStripVisible;
    private bool _wifiDialogOpen;
    private readonly System.Windows.Forms.Timer? _wifiTimer;
    private readonly Dictionary<Control, int> _baseTops = new();
    private int _baseHeight;
    private bool _downloading;
    // Layout blocks that come and go: the first-run line (measured, it may wrap) and the
    // pairing code block (only while a code exists, i.e. after Pair is tapped).
    private readonly int _firstRunH;
    private readonly int _codeBlockH;
    private const int ListBaseH = 3 * 52; // the base layout reserves three rows
    private int _listH = ListBaseH;
    private bool _removeAllShown = true;
    private bool _firstRunShown;
    private bool _codeShown;

    public HelperWindow(bool windowShot = false)
    {
        // Scale every control, font and position with the screen's DPI, or fixed-pixel
        // layouts clip text and buttons on scaled displays.
        AutoScaleMode = AutoScaleMode.Dpi;
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
            Text = "Get the BingeMote app on your phone. Connect your phone to the same home network as this PC, then enter a name for the phone and click Pair.",
            Font = new Font("Segoe UI", 8f),
            AutoSize = true, Left = margin, Top = y,
            // Wrap inside the content width instead of running past the right margin.
            MaximumSize = new Size(contentW, 0),
            LinkColor = Orange, ActiveLinkColor = Orange, VisitedLinkColor = Orange,
        };
        _firstRun.Links.Add(8, 13, "https://mtndaleredneck.github.io/BingeMote/");
        _firstRun.LinkClicked += (_, e) =>
        {
            try { Process.Start(new ProcessStartInfo((string)e.Link!.LinkData!) { UseShellExecute = true }); } catch { }
        };
        // Measured, not fixed: larger Windows text wraps the line to two rows.
        _firstRunH = TextRenderer.MeasureText(_firstRun.Text, _firstRun.Font,
            new Size(contentW, 1000), TextFormatFlags.WordBreak).Height + 6;
        y += _firstRunH;
        // One anti-aliased rounded frame, exactly the Pair button's top and height, with a
        // borderless TextBox centered inside it (no docking: a single-line TextBox keeps
        // its own height, which left a grey slab under it).
        // The "type a name" hint lives INSIDE the box as placeholder text:
        // it disappears the moment he types, and nothing sits beside the box.
        _nameBoxPanel = new RoundedPanel
        {
            Left = margin, Top = y, Width = contentW - 60 - 12, Height = 32,
            Radius = 4, Cursor = Cursors.IBeam,
        };
        _nameBox = new TextBox
        {
            BorderStyle = BorderStyle.None,
            Font = new Font("Segoe UI", 10),
        };
        _nameBoxPanel.Controls.Add(_nameBox);
        _nameBoxPanel.Layout += (_, _) =>
        {
            const int padX = 10;
            _nameBox.SetBounds(padX, (_nameBoxPanel.ClientSize.Height - _nameBox.Height) / 2,
                Math.Max(10, _nameBoxPanel.ClientSize.Width - padX * 2), _nameBox.Height);
        };
        _nameBoxPanel.Click += (_, _) => _nameBox.Focus();
        _pair = MakeButton("Pair", margin + contentW - 60, y, 60);
        _pair.Height = 32;
        _pair.Enabled = false;
        StylePrimary(_pair);
        _pair.Click += (_, _) => StartPairing();
        _nameBox.TextChanged += (_, _) =>
        {
            _pair.Enabled = _nameBox.Text.Trim().Length > 0;
        };
        y += 32 + 12;
        _codeCaption = new Label
        {
            Text = "Pairing code:",
            Font = new Font("Segoe UI", 9),
            AutoSize = true, Left = margin, Top = y + 8,
            Visible = false,
        };
        _code = new Label
        {
            Text = Program.PairCode ?? "----",
            Font = new Font("Consolas", 15, FontStyle.Bold),
            ForeColor = Orange,
            AutoSize = true, Left = margin + 98, Top = y,
            Visible = false,
        };
        _cancelPair = MakeButton("Cancel", margin + contentW - 80, y - 2, 80);
        _cancelPair.Visible = false;
        _cancelPair.Click += (_, _) => CancelPairing();
        y += 28 + 6;
        _codeNote = new Label
        {
            Text = "Enter this code in the BingeMote app on your phone. Works once.",
            Font = new Font("Segoe UI", 8),
            AutoSize = false, Left = margin, Top = y, Width = contentW,
            Visible = false,
        };
        // Measure, don't guess: larger Windows text wraps this to three lines.
        _codeNote.Height = TextRenderer.MeasureText(_codeNote.Text, _codeNote.Font,
            new Size(contentW, 1000), TextFormatFlags.WordBreak).Height + 4;
        y += _codeNote.Height + 24;
        // The code block (caption, code, note) shows only while a code exists; without it
        // "Paired phones" sits 24px under the name row, like every other section gap.
        _codeBlockH = 12 + 28 + 6 + _codeNote.Height;

        _phonesHeader = Header("Paired phones", margin, y); y += 20 + 12;
        _phoneList = new FlowLayoutPanel
        {
            Left = margin, Top = y, Width = contentW, Height = 3 * 52,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
        };
        y += 3 * 52 + 8;
        _removeAll = MakeButton("Remove all phones...", margin, y, FitWidth("Remove all phones...", new Font("Segoe UI", 8.5f), 150));
        _removeAll.Height = 32;
        _removeAll.Click += (_, _) => RemoveAllPhones();
        y += 32 + 24;

        _aboutHeader = Header("About", margin, y); y += 20 + 12;
        _feedbackCard = new RoundedPanel { Left = margin, Top = y, Width = contentW, Height = 48, Radius = 8 };
        var feedbackLabel = new Label
        {
            Text = "Feedback & bugs",
            Font = new Font("Segoe UI", 10.5f),
            AutoSize = true, Left = 14,
            UseMnemonic = false,
        };
        var fbw = FitWidth("Send feedback", new Font("Segoe UI", 8.5f), 130);
        _feedbackBtn = MakeButton("Send feedback", contentW - fbw - 10, 9, fbw);
        _feedbackBtn.Height = 30;
        CenterChildrenVertically(_feedbackCard);
        _feedbackBtn.Click += (_, _) => { using var dlg = new FeedbackDialog(); dlg.ShowDialog(this); };
        _feedbackCard.Controls.Add(feedbackLabel);
        _feedbackCard.Controls.Add(_feedbackBtn);
        y += 48 + 4;
        _updatesCard = new RoundedPanel { Left = margin, Top = y, Width = contentW, Height = 48, Radius = 8 };
        var updatesLabel = new Label
        {
            Text = "Updates",
            Font = new Font("Segoe UI", 10.5f),
            AutoSize = true, Left = 14,
        };
        _updatesCaption = new Label
        {
            Text = "v" + Program.VersionText,
            Font = new Font("Segoe UI", 9.5f),
            AutoSize = true,
            // Right after the label, however wide larger Windows text makes it.
            Left = 14 + TextRenderer.MeasureText("Updates", new Font("Segoe UI", 10.5f)).Width + 10,
        };
        _updatesResult = new Label
        {
            Text = "",
            Font = new Font("Segoe UI", 9.5f),
            AutoSize = false,
            Left = _updatesCaption.Left + TextRenderer.MeasureText(_updatesCaption.Text, _updatesCaption.Font).Width + 8,
            AutoEllipsis = true,
        };
        _updatesResult.Height = _updatesResult.PreferredHeight;
        var cuw = FitWidth("Check for updates", new Font("Segoe UI", 8.5f), 140);
        _updatesResult.Width = contentW - cuw - 10 - _updatesResult.Left - 10;
        _checkUpdates = MakeButton("Check for updates", contentW - cuw - 10, 9, cuw);
        _checkUpdates.Height = 30;
        _checkUpdates.Click += (_, _) => ManualUpdateCheck();
        CenterChildrenVertically(_updatesCard);
        _updatesCard.Controls.Add(updatesLabel);
        _updatesCard.Controls.Add(_updatesCaption);
        _updatesCard.Controls.Add(_updatesResult);
        _updatesCard.Controls.Add(_checkUpdates);
        y += 48 + margin;

        ClientSize = new Size(490, y);

        // The public-Wi-Fi strip: sits at the top of the window while the network is
        // public, so the fix is never buried. The 30s poll keeps it in sync.
        // One anti-aliased card with a 1px orange edge (it used to be an orange panel with a
        // card clipped inside it, both cut by pixel regions).
        _wifiBorder = new RoundedPanel
        {
            Left = margin, Top = margin, Width = contentW, Height = 62,
            Radius = 8, BorderColor = Orange, Visible = false,
        };
        var wifiText = new Label
        {
            Text = "Windows is treating this Wi-Fi as public, so it's blocking your phone.",
            Font = new Font("Segoe UI", 9f),
            Left = 13, Top = 9, Width = contentW - 12 - 160 - 16, Height = 62 - 16,
        };
        _wifiBtn = MakeButton("This is my home Wi-Fi", contentW - 160 - 10 + 1, 7 + 1, 160);
        _wifiBtn.Height = 28;
        StylePrimary(_wifiBtn);
        _wifiBtn.Click += (_, _) => FixHomeWifi();
        _wifiNote = new Label
        {
            Text = "(Windows will ask for permission)",
            Font = new Font("Segoe UI", 7.5f),
            Width = 160, TextAlign = ContentAlignment.TopCenter,
            Left = contentW - 160 - 10 + 1, Top = 40 + 1, Height = 16,
        };
        _wifiBorder.Controls.Add(wifiText);
        _wifiBorder.Controls.Add(_wifiBtn);
        _wifiBorder.Controls.Add(_wifiNote);

        Controls.AddRange(new Control[]
        {
            _wifiBorder,
            _pairHeader, _firstRun, _nameBoxPanel, _pair, _codeCaption, _code, _cancelPair, _codeNote,
            _phonesHeader, _phoneList, _removeAll,
            _aboutHeader, _feedbackCard, _updatesCard,
        });
        foreach (Control c in Controls) _baseTops[c] = c.Top;
        _baseHeight = y;

        _menu = new ContextMenuStrip();
        _menu.Items.Add("Show BingeMote", null, (_, _) => ShowFromTray());
        _menu.Items.Add("Quit BingeMote", null, (_, _) => Program.QuitOnPurpose());
        _tray = new NotifyIcon
        {
            Text = "BingeMote",
            Icon = Icon,
            Visible = true,
            ContextMenuStrip = _menu,
        };
        _tray.DoubleClick += (_, _) => ShowFromTray();
        // Route by balloon purpose: only an update notification may start the updater.
        // Any other balloon (e.g. the input-failure warning) just opens the window.
        _tray.BalloonTipClicked += (_, _) =>
        {
            var which = _balloon;
            _balloon = TrayBalloon.None;
            ShowFromTray();
            if (TrayClickStartsUpdate(which)) StartUpdateDownload();
        };

        InputSender.BlockedStateChanged += OnInputBlockedState;
        Disposed += (_, _) => InputSender.BlockedStateChanged -= OnInputBlockedState;

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
            _wifiTimer.Tick += (_, _) =>
            {
                // The 30s re-check exists for the strip/dialog; no PowerShell while nothing
                // Wi-Fi-related is on screen. Network changes raise their own event.
                if (Visible || _wifiProfile != null || _wifiDialogOpen) ProbeWifi();
            };
            _wifiTimer.Start();
        }

        // Follow the Windows dark/light mode setting, live.
        Microsoft.Win32.SystemEvents.UserPreferenceChanged += (_, _) =>
        {
            try { BeginInvoke(new Action(ApplyTheme)); } catch { }
        };
        ApplyTheme();
        RefreshPhones();
        TickStatus(); // sync the code block (hidden until there is a code)

        // A vlcrc write that lost to a running VLC retries once VLC closes.
        var vlcrcRetry = new System.Windows.Forms.Timer { Interval = 15000 };
        vlcrcRetry.Tick += (_, _) => { try { VlcSetup.RetryPending(); } catch { } };
        vlcrcRetry.Start();

        // Every ES_CONTINUOUS hold/release must run on this thread (execution state is
        // per-thread); PowerWake marshals calls from the listener's pool threads here.
        // No context means the hold stays disabled - a plain SynchronizationContext would
        // post to pool threads and the per-thread hold could latch.
        var uiSync = SynchronizationContext.Current;
        if (uiSync != null) { PowerWake.BindUi(uiSync); VolumeOsd.BindUi(uiSync); }
        else Program.Log("No UI synchronization context at startup: the keep-awake hold is disabled (fail-safe).");

        // The movie-session keep-awake: hold the PC (not the display) while a video is
        // loaded in VLC and VLC is not minimized; drop it the moment that stops being true.
        var keepAwake = new System.Windows.Forms.Timer { Interval = 10000 };
        keepAwake.Tick += (_, _) => { try { SyncKeepAwake(); } catch { } };
        keepAwake.Start();
        SyncKeepAwake();

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

    /// <summary>Base layout + the optional blocks (Wi-Fi strip on top, first-run line
    /// under the pair header, pairing code under the name row): everything below a
    /// hidden block moves up. Uses our own flags, not Control.Visible, which reads false
    /// for every child while the window itself is hidden.</summary>
    private void ApplyLayout()
    {
        if (_baseHeight == 0 || _firstRun == null || _wifiBorder == null) return;
        var stripDy = _wifiStripVisible ? StripH : 0;
        var firstRunDy = _firstRunShown ? 0 : -_firstRunH;
        var codeDy = _codeShown ? 0 : -_codeBlockH;
        // The phone list is as tall as its rows (one line when empty, three rows at most,
        // then it scrolls), and with no phones "Remove all phones..." leaves no gap behind.
        var listDy = _listH - ListBaseH;
        var removeDy = _removeAllShown ? 0 : -(8 + _removeAll.Height);
        var frTop = _baseTops[_firstRun];
        var codeEnd = _baseTops[_codeNote];
        var listTop = _baseTops[_phoneList];
        var removeTop = _baseTops[_removeAll];
        foreach (Control c in Controls)
        {
            if (c == _wifiBorder) { c.Top = _baseTops[c]; continue; }
            var dy = stripDy;
            if (_baseTops[c] > frTop) dy += firstRunDy;
            if (_baseTops[c] > codeEnd) dy += codeDy;
            if (_baseTops[c] > listTop) dy += listDy;
            if (_baseTops[c] > removeTop) dy += removeDy;
            c.Top = _baseTops[c] + dy;
        }
        _phoneList.Height = _listH;
        ClientSize = new Size(490, _baseHeight + stripDy + firstRunDy + codeDy + listDy + removeDy);
    }

    // Called from the HTTP listener thread when SendInput starts (or stops) coming back
    // short. The title carries the state for as long as it lasts; the balloon fires at
    // most once a minute so flapping input never spams. No cause is claimed: all the
    // helper knows is that Windows did not play the input. Mouse AND keyboard share this
    // Send path, so the wording says input, not mouse.
    private long _lastBlockBalloonMs = long.MinValue;

    // Which balloon the tray is currently showing, so a click does what THAT notice
    // promises: an update notice downloads; anything else only opens the window.
    internal enum TrayBalloon { None, UpdateAvailable, InputBlocked }
    private TrayBalloon _balloon = TrayBalloon.None;

    // Separate so the routing rule is unit-testable without a tray.
    internal static bool TrayClickStartsUpdate(TrayBalloon kind) => kind == TrayBalloon.UpdateAvailable;

    // Throttle decision, extracted so the first-fire path is unit-testable. The sentinel
    // must be checked BEFORE the subtraction: TickCount64 - long.MinValue overflows.
    // Once set, lastMs <= nowMs (same monotonic session), so the difference cannot wrap.
    internal static bool BalloonDue(long lastMs, long nowMs) =>
        lastMs == long.MinValue || nowMs - lastMs > 60_000;

    private void OnInputBlockedState(bool blocked)
    {
#if BLOCK_NOTICE_HOOK
        // Mirrors the logical state even when the window handle does not exist yet
        // (tray-hidden), where BeginInvoke would be swallowed. Title and Balloons are
        // set on the UI path only, so they always reflect what the user could see.
        BlockNoticeProbe.Blocked = blocked;
#endif
        try
        {
            BeginInvoke(new Action(() =>
            {
                if (blocked)
                {
                    // Monotonic clock: wall-clock changes must not extend or reset the throttle.
                    var nowMs = Environment.TickCount64;
                    if (BalloonDue(_lastBlockBalloonMs, nowMs))
                    {
                        _lastBlockBalloonMs = nowMs;
                        _balloon = TrayBalloon.InputBlocked;
                        _tray.BalloonTipTitle = "BingeMote";
                        _tray.BalloonTipText = "Input could not be sent. Check whether an administrator window is in front.";
                        _tray.ShowBalloonTip(8000);
#if BLOCK_NOTICE_HOOK
                        BlockNoticeProbe.Balloons++;
#endif
                    }
                    Text = "BingeMote - input could not be sent";
                }
                else
                {
                    Text = "BingeMote";
                }
#if BLOCK_NOTICE_HOOK
                BlockNoticeProbe.Title = Text;
#endif
            }));
        }
        catch { }
    }

#if BLOCK_NOTICE_HOOK
    // CI-only window into the notice state, so the e2e can assert title, state and
    // balloon throttle without reading pixels.
    internal static class BlockNoticeProbe
    {
        public static bool Blocked;
        public static string Title = "";
        public static int Balloons;
    }
#endif

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
    [System.Runtime.InteropServices.DllImport("user32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private static extern IntPtr SendMessageW(IntPtr hWnd, int msg, IntPtr wParam, string lParam);
    private const int EM_SETCUEBANNER = 0x1501;
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
        _firstRun.Visible = true;
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
            sb.AppendLine("AutoScaleMode=" + AutoScaleMode + " AutoScaleDimensions=" + CurrentAutoScaleDimensions + " (saved " + AutoScaleDimensions + ")");
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

    /// <summary>Show or hide the pairing code block (window-visible work).</summary>
    private void TickStatus()
    {
        _code.Text = Program.PairCode ?? "----";
        // The block shows only a live code (caption, code, Cancel, note); codes never expire.
        var live = Program.PairCode != null;
        if (live != _codeShown || live != _code.Visible)
        {
            _codeShown = live;
            _codeCaption.Visible = _code.Visible = _cancelPair.Visible = live;
            _codeNote.Visible = live;
            _codeNote.Text = "Enter this code in the BingeMote app on your phone. Works once.";
            ApplyLayout();
        }
    }

    /// <summary>Cancel drops the code and goes back to step one, keeping the typed name.</summary>
    private void CancelPairing()
    {
        var name = Program.PendingPhoneName;
        Program.PairCode = null;
        Program.PendingPhoneName = null;
        if (!string.IsNullOrEmpty(name) && _nameBox.Text.Trim().Length == 0) _nameBox.Text = name;
        Program.Log("Pairing canceled from the window.");
        TickStatus();
    }

    /// <summary>Rebuild the paired-phones list from the current config. Each phone is one
    /// settings-style card: name on the left, Remove inside at the right.</summary>
    private void RefreshPhones()
    {
        var dark = IsDarkMode();
        var card = dark ? DarkCard : LightCard;
        var btnBg = dark ? DarkCard2 : LightBg;
        var text = dark ? DarkText : LightText;
        var muted = dark ? DarkMuted : LightMuted;
        var border = dark ? DarkBorder : LightBorder;
        _phoneList.Controls.Clear();
        var rowWidth = Program.PhonesCount > 3 ? 442 - 17 : 442; // leave room for the scroll bar
        if (Program.PhonesCount == 0)
        {
            var empty = new Label
            {
                Text = "No phones paired yet.",
                Font = new Font("Segoe UI", 9f),
                AutoSize = true, Margin = new Padding(2, 2, 0, 0),
                ForeColor = muted, BackColor = _phoneList.BackColor,
            };
            _phoneList.Controls.Add(empty);
            _listH = empty.PreferredHeight + 4;
        }
        else
        {
            // Each row is 48 tall plus a 4px gap; past three rows the list scrolls.
            _listH = Math.Min(Program.PhonesCount, 3) * 52;
        }
        foreach (var ph in Program.PhonesSnapshot())
        {
            var row = new RoundedPanel
            {
                Width = rowWidth, Height = 48, Margin = new Padding(0, 0, 0, 4),
                BackColor = card, BorderColor = border, Radius = 8,
            };
            var removeFont = new Font("Segoe UI", 8.5f);
            var removeW = FitWidth("Remove", removeFont, 64);
            var name = new Label
            {
                Text = ph.Name,
                AutoEllipsis = true, AutoSize = false,
                Left = 14, Width = rowWidth - 14 - removeW - 10 - 10,
                TextAlign = ContentAlignment.MiddleLeft,
                Font = new Font("Segoe UI", 10.5f),
                ForeColor = text, BackColor = card,
            };
            name.Height = name.PreferredHeight + 4;
            var remove = new ReadableButton
            {
                Text = "Remove", Width = removeW, Height = 30, Left = rowWidth - removeW - 10,
                Font = removeFont,
                BackColor = btnBg, ForeColor = text,
            };
            var phone = ph;
            remove.Click += (_, _) => RemovePhone(phone);
            row.Controls.Add(name);
            row.Controls.Add(remove);
            CenterChildrenVertically(row);
            _phoneList.Controls.Add(row);
        }
        _removeAllShown = Program.PhonesCount > 0;
        _removeAll.Visible = _removeAllShown;
        _firstRunShown = true;
        _firstRun.Visible = true;
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
        _balloon = TrayBalloon.UpdateAvailable;
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

    /// <summary>Width that fits the text at any Windows text size: measured, plus air.</summary>
    private static int FitWidth(string text, Font font, int min)
    {
        var w = TextRenderer.MeasureText(text, font).Width + 28;
        return Math.Max(min, w);
    }

    private static Button MakeButton(string text, int left, int top, int width) => new ReadableButton
    {
        Text = text,
        Left = left, Top = top, Width = width, Height = 30,
        FlatStyle = FlatStyle.Flat,
        Font = new Font("Segoe UI", 8.5f),
    };

    // The helper is dark-only,
    // even when Windows itself is in light mode. The light palette and the live
    // theme watcher stay in the code so the decision is trivially reversible.
    internal static bool IsDarkMode() => true;

    private void ApplyTheme()
    {
        var dark = IsDarkMode();
        var bg = dark ? DarkBg : LightBg;
        var card = dark ? DarkCard : LightCard;
        var btnBg = dark ? DarkCard : LightCard;
        var cardBtn = dark ? DarkCard2 : LightBg;
        var text = dark ? DarkText : LightText;
        var muted = dark ? DarkMuted : LightMuted;
        var border = dark ? DarkBorder : LightBorder;
        BackColor = bg;
        ForeColor = text;
        _pairHeader.ForeColor = text;
        _phonesHeader.ForeColor = text;
        _aboutHeader.ForeColor = text;
        _nameBoxPanel.BackColor = card;
        _nameBoxPanel.BorderColor = border;
        _nameBox.BackColor = card;
        _nameBox.ForeColor = text;
        _codeCaption.ForeColor = text;
        _code.ForeColor = Orange;
        _codeNote.ForeColor = text;
        _phoneList.BackColor = bg;
        _firstRun.BackColor = bg;
        _firstRun.ForeColor = text;
        _firstRun.LinkColor = Orange;
        _firstRun.ActiveLinkColor = Orange;
        _firstRun.VisitedLinkColor = Orange;
        _wifiBorder.BackColor = card;
        _wifiBorder.BorderColor = Orange;
        foreach (Control c in _wifiBorder.Controls)
        {
            if (c is Button) continue;
            c.BackColor = card;
            c.ForeColor = text;
        }
        _feedbackCard.BackColor = card;
        _updatesCard.BackColor = card;
        _feedbackCard.BorderColor = border;
        _updatesCard.BorderColor = border;
        foreach (Control c in _feedbackCard.Controls) { c.BackColor = c is Button ? cardBtn : card; c.ForeColor = text; }
        foreach (Control c in _updatesCard.Controls) { if (c is Button) continue; c.BackColor = card; c.ForeColor = text; }
        _updatesCaption.ForeColor = muted;
        _feedbackBtn.FlatAppearance.BorderSize = 0;
        _checkUpdates.FlatAppearance.BorderSize = 0;
        foreach (var b in new[] { _removeAll, _cancelPair })
        {
            b.BackColor = cardBtn;
            b.ForeColor = text;
            b.FlatAppearance.BorderSize = 0;
        }
        StylePrimary(_pair);
        if (_manualDownload) StylePrimary(_checkUpdates); else { _checkUpdates.BackColor = cardBtn; _checkUpdates.ForeColor = text; }
        RefreshPhones();
        Invalidate(true); // rounded corners blend into the new background
        ApplyTitleBarTheme();
    }

    /// <summary>The window's title bar follows the Windows dark/light setting (immersive dark mode).</summary>
    internal void ApplyTitleBarTheme()
    {
        if (!IsHandleCreated) return;
        var dark = IsDarkMode() ? 1 : 0;
        try { DwmSetWindowAttribute(Handle, 20, ref dark, 4); } catch { /* older Windows */ }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    /// <summary>Win11 corner radii: 4px on buttons and boxes, 8px on cards.</summary>
    internal static System.Drawing.Drawing2D.GraphicsPath RoundedRect(RectangleF r, float radius)
    {
        var path = new System.Drawing.Drawing2D.GraphicsPath();
        var d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
        if (d <= 0.5f) { path.AddRectangle(r); return path; }
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    /// <summary>Paint a smooth (anti-aliased) rounded shape: the corners outside it take
    /// the parent's color, the inside is filled, plus an optional 1px edge. Replaces the
    /// old Control.Region rounding, which is a hard pixel mask (jagged, never smoothed).
    /// Radius is in the same pixels as the layout: the window's positions are fixed
    /// pixels, so the corners stay in proportion with the boxes.</summary>
    internal static void PaintRounded(Graphics g, Control c, Color fill, Color border, int radius)
    {
        g.Clear(c.Parent?.BackColor ?? SystemColors.Control);
        g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
        using (var path = RoundedRect(new RectangleF(0, 0, c.Width, c.Height), radius))
        using (var brush = new SolidBrush(fill))
            g.FillPath(brush, path);
        if (!border.IsEmpty && border.A > 0)
        {
            using var edge = RoundedRect(new RectangleF(0.5f, 0.5f, c.Width - 1, c.Height - 1), Math.Max(0, radius - 0.5f));
            using var pen = new Pen(border, 1f);
            g.DrawPath(pen, edge);
        }
        g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.Default;
        g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.Default;
    }

    /// <summary>Keep every child of a card vertically centered by measurement, at any DPI
    /// or Windows text size (fixed Tops put "Feedback & bugs" low when the text grew).</summary>
    internal static void CenterChildrenVertically(Control card)
    {
        var busy = false;
        void Center()
        {
            if (busy) return;
            busy = true;
            try
            {
                foreach (Control c in card.Controls)
                {
                    var h = c is Label { AutoSize: true } l ? l.PreferredHeight : c.Height;
                    var top = (card.ClientSize.Height - h) / 2;
                    if (c.Top != top) c.Top = top;
                }
            }
            finally { busy = false; }
        }
        card.Layout += (_, _) => Center();
        card.Resize += (_, _) => Center();
        card.ControlAdded += (_, _) => Center();
        Center();
    }

    /// <summary>A panel drawn as a smooth rounded card: BackColor inside, the parent's
    /// color in the corners, an optional 1px BorderColor edge.</summary>
    internal sealed class RoundedPanel : Panel
    {
        private int _radius = 8;
        private Color _borderColor = Color.Empty;

        public RoundedPanel()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint
                | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        }

        [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]
        public int Radius { get => _radius; set { _radius = value; Invalidate(); } }

        [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]
        public Color BorderColor { get => _borderColor; set { _borderColor = value; Invalidate(); } }

        protected override void OnPaintBackground(PaintEventArgs e) =>
            PaintRounded(e.Graphics, this, BackColor, _borderColor, _radius);

        protected override void OnParentBackColorChanged(EventArgs e)
        {
            base.OnParentBackColorChanged(e);
            Invalidate();
        }
    }

    /// <summary>A flat button drawn by hand: smooth rounded background (BackColor), a
    /// slight hover and pressed tone, centered text. Disabled keeps the button's normal
    /// text color: WinForms draws disabled text in GrayText, which vanishes on dark cards
    /// and on orange.</summary>
    internal sealed class ReadableButton : Button
    {
        private int _radius = 4;
        private bool _hover, _pressed;

        public ReadableButton()
        {
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint
                | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        }

        [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]
        public int Radius { get => _radius; set { _radius = value; Invalidate(); } }

        private static Color Mix(Color a, Color b, float t) => Color.FromArgb(
            (int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));

        protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hover = false; _pressed = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { if (e.Button == MouseButtons.Left) { _pressed = true; Invalidate(); } base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { _pressed = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnEnabledChanged(EventArgs e) { _hover = _pressed = false; Invalidate(); base.OnEnabledChanged(e); }
        protected override void OnParentBackColorChanged(EventArgs e) { base.OnParentBackColorChanged(e); Invalidate(); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var fill = BackColor;
            // Disabled reads disabled: the fill leans halfway toward whatever is behind the
            // button and the text halfway toward that.
            if (!Enabled) fill = Mix(BackColor, Parent?.BackColor ?? BackColor, 0.5f);
            // Hover/pressed lean toward the text color: lighter on dark grey, deeper on orange.
            else if (_pressed) fill = Mix(BackColor, ForeColor, 0.16f);
            else if (_hover) fill = Mix(BackColor, ForeColor, 0.08f);
            var border = FlatAppearance.BorderSize > 0 ? FlatAppearance.BorderColor : Color.Empty;
            PaintRounded(e.Graphics, this, fill, border, _radius);
            var fg = Enabled ? ForeColor : Mix(ForeColor, fill, 0.5f);
            TextRenderer.DrawText(e.Graphics, Text, Font, ClientRectangle, fg, fill,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter
                | TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis);
            if (Focused && ShowFocusCues && Enabled)
            {
                e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                using var ring = RoundedRect(new RectangleF(2.5f, 2.5f, Width - 5, Height - 5), Math.Max(1, _radius - 2));
                using var pen = new Pen(Color.FromArgb(140, ForeColor), 1f) { DashStyle = System.Drawing.Drawing2D.DashStyle.Dot };
                e.Graphics.DrawPath(pen, ring);
            }
        }
    }

    /// <summary>The one orange button: the window's primary action.</summary>
    internal static void StylePrimary(Button b)
    {
        b.BackColor = Orange;
        b.ForeColor = OnOrange;
        b.FlatAppearance.BorderSize = 0;
        b.Invalidate();
    }

    protected override void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        RefreshPhones();
        // In-box hint via EM_SETCUEBANNER with wParam=1: the cue stays visible even while
        // the box is focused-but-empty, and vanishes the instant typing starts.
        SendMessageW(_nameBox.Handle, EM_SETCUEBANNER, (IntPtr)1, "Type a name to pair");
        ActiveControl = _nameBox; // the name box is the obvious first step
        // A block that started while the window handle did not exist (tray-hidden launch)
        // was swallowed by BeginInvoke; replay the current state so the title shows it.
        if (InputSender.IsBlocked) OnInputBlockedState(true);
    }

    private static Label Header(string text, int x, int y) => new()
    {
        Text = text,
        Font = new Font("Segoe UI", 10.5f, FontStyle.Bold),
        AutoSize = true, Left = x, Top = y,
    };

    private System.Windows.Forms.Timer? _captionTimer;

    /// <summary>Check results ("You're up to date.") show for a few seconds, then the row
    /// goes back to showing the running version - like the phone app.</summary>
    private void ShowCaptionBriefly(string s)
    {
        _updatesResult.Text = s;
        if (_captionTimer == null)
        {
            _captionTimer = new System.Windows.Forms.Timer { Interval = 20000 };
            _captionTimer.Tick += (_, _) =>
            {
                _captionTimer.Stop();
                if (!_downloading && !_manualDownload) _updatesResult.Text = "";
            };
        }
        _captionTimer.Stop();
        _captionTimer.Start();
    }

    /// <summary>The Updates card's manual check: result shows inline, never a tray balloon.</summary>
    private void ManualUpdateCheck()
    {
        if (_manualDownload) { StartUpdateDownload(); return; }
        _checkUpdates.Enabled = false;
        _updatesResult.Text = "Checking...";
        Task.Run(() =>
        {
            (string Version, string ExeUrl, string? Sha256)? newer;
            try { newer = UpdateCheck.LatestNewerThanOrThrow(Program.VersionText); }
            catch
            {
                try { BeginInvoke(new Action(() =>
                {
                    ShowCaptionBriefly("Couldn't check right now. Try again later.");
                    _checkUpdates.Enabled = true;
                })); } catch { }
                return;
            }
            try { BeginInvoke(new Action(() =>
            {
                if (newer != null)
                {
                    Program.AvailableUpdate = newer.Value;
                    _updatesResult.Text = "Version " + newer.Value.Version + " is available.";
                    _manualDownload = true;
                    _checkUpdates.Text = "Download";
                    _checkUpdates.Enabled = true;
                    StylePrimary(_checkUpdates);
                }
                else
                {
                    ShowCaptionBriefly("Up to date");
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
            ShowCaptionBriefly("Update canceled.");
            _checkUpdates.Enabled = true;
            _downloading = false;
        }
    }

    /// <summary>Download the update's installer, check it against the release's SHA-256, and
    /// run it. He always taps Update first; after one Windows permission prompt it installs
    /// itself and reopens BingeMote.</summary>
    private void StartUpdateDownload()
    {
        var u = Program.AvailableUpdate;
        if (u == null || _downloading) return;
        _downloading = true;
        _checkUpdates.Enabled = false;
        _updatesResult.Text = "Downloading version " + u.Value.Version + "...";
        var upd = u.Value;
        var progress = new Progress<int>(p => _updatesResult.Text = "Downloading " + p + "%...");
        Task.Run(async () =>
        {
            try
            {
                var path = await SelfUpdate.Download(upd.Version, upd.ExeUrl, upd.Sha256, progress);
                Program.Log("Update " + upd.Version + " downloaded and verified; opening the installer.");
                try { BeginInvoke(new Action(() =>
                {
                    _updatesResult.Text = "Checked. Windows will ask for permission once, then the update installs itself.";
                    LaunchUpdate(path);
                })); } catch { }
            }
            catch (OperationCanceledException)
            {
                Program.Log("Update download stalled (60 seconds of silence) - canceled so it cannot sit on Downloading forever.");
                try { BeginInvoke(new Action(() =>
                {
                    _updatesResult.Text = "The download stopped. Try again.";
                    _checkUpdates.Enabled = true;
                    _downloading = false;
                })); } catch { }
            }
            catch
            {
                Program.Log("Update download failed or failed its check.");
                try { BeginInvoke(new Action(() =>
                {
                    _updatesResult.Text = "The download didn't check out. Try again.";
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
                // MarkPrivate waits on the Windows permission prompt (up to 30 s): run it off
                // the UI thread so the window stays alive, like the strip button does.
                Task.Run(() =>
                {
                    var ok = false;
                    try { ok = NetworkCheck.MarkPrivate(profile); } catch { }
                    try { BeginInvoke(new Action(() =>
                    {
                        if (ok)
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
                    })); } catch { }
                });
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

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsIconic(IntPtr hWnd);

    /// <summary>VLC counts as "open" while some VLC main window exists and is
    /// not minimized. A window hidden to the tray has no MainWindowHandle and reads as
    /// closed - the safe direction, the hold just releases.</summary>
    private static bool VlcWindowOpen()
    {
        var procs = Process.GetProcessesByName("vlc");
        try
        {
            // Zero: VLC is closed. More than one: which instance holds the polled media
            // is unproven - release (safe direction) rather than hold on a guess.
            if (procs.Length != 1) return false;
            var h = procs[0].MainWindowHandle;
            return h != IntPtr.Zero && !IsIconic(h);
        }
        catch { return false; }
        finally { foreach (var p in procs) p.Dispose(); }
    }

    private bool _lastKeepAwakeHold;

    private void SyncKeepAwake()
    {
        if (Program.KeepAwakeEnabled) ResumeTracker.EnsurePollingWithCurrentPassword();
        var hold = Program.KeepAwakeEnabled && ResumeTracker.MediaActive && VlcWindowOpen();
        if (hold != _lastKeepAwakeHold)
        {
            // The laptop validation reads these lines: when the hold is on, the PC must
            // not sleep; when it drops, the normal sleep timer applies.
            Program.Log("Keep-awake hold " + (hold ? "ON (video loaded in VLC, window visible)" : "OFF (back to normal sleep rules)") + ".");
            _lastKeepAwakeHold = hold;
        }
        PowerWake.SyncToVlc(hold);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true; // closing tucks it into the tray, silently; the tray menu's Quit exits for real
            Hide();
            return;
        }
        PowerWake.Release();
        _tray.Visible = false;
        base.OnFormClosing(e);
    }

    // Display on/off/dimmed arrives here; feeds the first-tap-only-wakes rule.
    private static readonly Guid ConsoleDisplayStateGuid = new("6FE69556-704A-47A0-8F24-C28D936FDA47");
    private IntPtr _powerNotify;

    [DllImport("user32.dll")]
    private static extern IntPtr RegisterPowerSettingNotification(IntPtr hRecipient, ref Guid PowerSettingGuid, uint Flags);

    [DllImport("user32.dll")]
    private static extern bool UnregisterPowerSettingNotification(IntPtr Handle);

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        ApplyTitleBarTheme();
        var g = ConsoleDisplayStateGuid;
        _powerNotify = RegisterPowerSettingNotification(Handle, ref g, 0); // DEVICE_NOTIFY_WINDOW_HANDLE
    }

    protected override void OnHandleDestroyed(EventArgs e)
    {
        if (_powerNotify != IntPtr.Zero) { UnregisterPowerSettingNotification(_powerNotify); _powerNotify = IntPtr.Zero; }
        base.OnHandleDestroyed(e);
    }

    protected override void WndProc(ref Message m)
    {
        const int WM_POWERBROADCAST = 0x0218;
        const int PBT_POWERSETTINGCHANGE = 0x8013;
        if (m.Msg == WM_POWERBROADCAST && m.WParam.ToInt32() == PBT_POWERSETTINGCHANGE)
        {
            // POWERBROADCAST_SETTING: Guid (16 bytes) + DWORD DataLength + data
            var g = (Guid)Marshal.PtrToStructure(m.LParam, typeof(Guid))!;
            if (g == ConsoleDisplayStateGuid)
            {
                var state = (uint)Marshal.ReadInt32(m.LParam, 20);
                PowerWake.NoteMonitorState(state);
            }
        }
        base.WndProc(ref m);
    }
}
