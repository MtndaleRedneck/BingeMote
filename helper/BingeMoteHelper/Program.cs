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

[assembly: InternalsVisibleTo("BingeMoteHelper.Tests")]

namespace BingeMoteHelper;

internal static class Program
{
    internal const int HttpPort = 8488;
    internal const int DiscoveryPort = 8489;
    internal const string DiscoverMagic = "BINGEMOTE_DISCOVER_V1";
    internal const string ReplyMagic = "BINGEMOTE_HERE_V1";
    internal const string FirewallRule = "BingeMote";

    /// <summary>Private-networks-only inbound rule for vlc.exe (the web remote the app sets up).</summary>
    internal const string VlcFirewallRule = "BingeMote VLC";
    /// <summary>The 1.x rule name, removed by the firewall setup so upgrades do not leave it behind.</summary>
    internal const string LegacyFirewallRule = "BingeMote Helper";

    private static readonly string LocalRoot = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);

    /// <summary>The data folder: the new name once it exists, the 1.x name until the one-time
    /// move runs. The move itself waits for the single-instance mutex in Main, so two copies
    /// racing at sign-in never fight over it.</summary>
    internal static string DataDir { get; private set; } = PickDataDir();

    private static string PickDataDir()
    {
        var dir = Path.Combine(LocalRoot, "BingeMote");
        var old = Path.Combine(LocalRoot, "BingeMoteHelper");
        return Directory.Exists(dir) || !Directory.Exists(old) ? dir : old;
    }

    /// <summary>The data folder used to be %LocalAppData%\BingeMoteHelper; it is now ...\BingeMote.
    /// One move carries pairing, the movies folder and the log over. Called from Main only by
    /// the copy holding the single-instance mutex.</summary>
    private static void MigrateDataDir()
    {
        var dir = Path.Combine(LocalRoot, "BingeMote");
        var old = Path.Combine(LocalRoot, "BingeMoteHelper");
        try
        {
            if (Directory.Exists(old) && !Directory.Exists(dir))
            {
                Directory.Move(old, dir);
                DataDir = dir;
            }
        }
        catch { /* the next start tries again */ }
    }

    private static string ConfigPath => Path.Combine(DataDir, "config.json");
    private static string FirewallMarker => Path.Combine(DataDir, "firewall.ok");
    private static string QuitMarkerPath => Path.Combine(DataDir, "quit-on-purpose");
    /// <summary>Fresh while an update installer runs: the watchdog skips its round so it cannot
    /// start a copy mid-install. Any normal start deletes it.</summary>
    internal static string UpdatingMarkerPath => Path.Combine(DataDir, "updating");
    /// <summary>Set after the tray notice about a failed listen, so the 5-minute watchdog does
    /// not repeat it; cleared on the next normal start (one notice per sign-in).</summary>
    private static string ListenFailNoticePath => Path.Combine(DataDir, "listen-fail-noticed");

    private const string ShowEventName = @"Local\BingeMoteHelper.ShowWindow";
    private const string QuitEventName = @"Local\BingeMoteHelper.Quit";

    /// <summary>A phone paired to this PC. The name is typed in the window when pairing starts.</summary>
    internal sealed class PairedPhone
    {
        public string Token { get; set; } = "";
        public string Name { get; set; } = "";
        /// <summary>The one-shot VLC-password fetch was consumed (adopt flow). Persisted,
        /// so a helper restart never re-arms it.</summary>
        public bool AdoptSpent { get; set; }
        /// <summary>The in-flight adopt fetch's request ID (persisted at serve time, so a
        /// helper restart replays/spends from the record instead of re-arming the grant).
        /// The password value itself is never stored here.</summary>
        public string? AdoptRequestId { get; set; }
        public DateTime? AdoptServedAtUtc { get; set; }
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

    /// <summary>Keep the PC awake while a video is loaded in VLC and VLC is not minimized
    /// (screen may still turn off). The behavior ships ON with a setting to disable it. A saved choice is kept.</summary>
    internal static bool KeepAwakeEnabled = true;

    /// <summary>"Show volume on PC screen" - the small pop-up on each phone volume change. Default ON.</summary>
    internal static bool VolumeOsdEnabled = true;
    internal static string? PairCode;
    /// <summary>The version that last ran; a change means an upgrade just landed.</summary>
    internal static string? LastRunVersion;
    /// <summary>A newer published helper version, found by the background update check.</summary>
    internal static (string Version, string ExeUrl, string? Sha256)? AvailableUpdate;
    internal static int PairFailures;
    internal const int MaxPairFailures = 5;

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--install-firewall"))
        {
            return FirewallSetup.RunElevated(FirewallMarker);
        }

#if WINDOW_SHOT_HOOK
        if (args.Contains("--dialog-shots"))
        {
            ApplicationConfiguration.Initialize();
            DialogCapture.Run(Environment.GetEnvironmentVariable("BINGEMOTE_DIALOG_SHOTS") ?? "dialog-shots");
            return 0;
        }

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

        // CI-only: render the volume pop-up (SPEC's 60% example, normal + muted) and exit.
        if (args.Contains("--osd-shot"))
        {
            var osdPath = Environment.GetEnvironmentVariable("BINGEMOTE_OSD_SHOT")
                ?? Path.Combine(DataDir, "osd.png");
            var osdMutedPath = Environment.GetEnvironmentVariable("BINGEMOTE_OSD_SHOT_MUTED")
                ?? Path.Combine(DataDir, "osd-muted.png");
            VolumeOsd.CaptureShot(osdPath, osdMutedPath, 60);
            return 0;
        }
#endif

        // The watchdog relaunches us with --ensure-running every few minutes: stay silent when
        // the app already runs or when he quit it on purpose. Any real launch (double-click,
        // sign-in autostart, installer, update, crash restart) cancels a purposeful quit.
        var ensureRunning = args.Contains("--ensure-running");
        if (ensureRunning)
        {
            if (File.Exists(QuitMarkerPath)) return 0;
            // A fresh update marker means the installer is running right now: starting the app
            // mid-install could fight it, so the watchdog skips this round.
            try
            {
                if (File.Exists(UpdatingMarkerPath)
                    && DateTime.UtcNow - File.GetLastWriteTimeUtc(UpdatingMarkerPath) < TimeSpan.FromMinutes(3))
                    return 0;
            }
            catch { }
            // Off in Settings > Startup apps means off: the watchdog honors the Windows switch.
            if (StartupDisabledByUser()) return 0;
        }
        else
        {
            try { File.Delete(QuitMarkerPath); } catch { }
            try { File.Delete(UpdatingMarkerPath); } catch { }
            try { File.Delete(ListenFailNoticePath); } catch { }
        }
        // Sign-in autostart and the Windows crash-restart are automatic launches: when a copy
        // is already up they stay quiet instead of counting as "opened by hand" and popping
        // the window. They still count as real launches for the quit marker (a sign-in revives
        // a tray quit).
        var quietLaunch = ensureRunning || args.Contains("--autostart") || args.Contains("/restarted");

        // One copy at a time. What a second launch does depends on what is already running:
        // the same exe just pops the running copy's window up; a different exe (an update)
        // asks the old copy to quit and takes over; a copy too old to have the events
        // (1.0.0/1.0.1) has to be quit by hand first.
        using var single = new Mutex(true, @"Local\BingeMoteHelper.SingleInstance", out var onlyCopy);
        if (!onlyCopy)
        {
            if (quietLaunch) return 0; // automatic launch: another copy is up, nothing to do, no window
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

        MigrateDataDir(); // the copy holding the mutex owns the one-time 1.x folder move
        Directory.CreateDirectory(DataDir);
        LoadConfig();
        if (LastRunVersion != VersionText)
        {
            // An upgrade just landed. Windows caches icons aggressively; tell the shell
            // associations changed so the desktop shortcut and taskbar pin pick up the
            // new icon now instead of after a sign-out.
            try { SHChangeNotify(0x08000000 /* SHCNE_ASSOCCHANGED */, 0x3000 /* SHCNF_IDLIST|SHCNF_FLUSH */, IntPtr.Zero, IntPtr.Zero); } catch { }
            LastRunVersion = VersionText;
            SaveConfig();
        }
        Log("Started. " + (!Paired ? "Not paired - open the window and tap Pair to get a code." : "Paired (" + PhonesCount + (PhonesCount == 1 ? " phone" : " phones") + ") - starting tucked in the tray."));
        RegisterAutostart();
        try { RegisterApplicationRestart("/restarted", 0); } catch { } // crash or hang: Windows relaunches us

        // Markers from older versions do not attest to the current listen-permission scope.
        // Recheck the exact URL on upgrades too; a broad legacy grant needs one UAC repair.
        if (!File.Exists(FirewallMarker) || !FirewallSetup.UrlAclExists())
        {
            // The Setup download adds the rule and listen permission during install; a
            // first run of the plain exe adds them here with one admin prompt.
            if (FirewallSetup.RuleExists() && FirewallSetup.UrlAclExists())
            {
                try { File.WriteAllText(FirewallMarker, DateTime.UtcNow.ToString("O")); } catch { }
            }
            else
            {
                // Watchdog launch: never pop the admin prompt every 5 minutes - stay quiet
                // until he opens the app himself and finishes the one-time setup.
                if (ensureRunning) { Log("Watchdog start skipped: firewall setup not done yet."); return 0; }
                FirewallSetup.ExplainAndRelaunch();
            }
            // Surface the vlc.exe rule inventory the elevated step saved (report only).
            FirewallSetup.LogVlcRuleInventory();
            // Cancellation or failed verification must not proceed under a broader legacy ACL.
            if (!FirewallSetup.RuleExists() || !FirewallSetup.UrlAclExists()) return 1;
        }

        // Resume places must load on every startup, not only on first-time firewall setup.
        ResumeTracker.Start();

        // A live pairing code is a way in, so one comes only from the window's Pair button,
        // never from startup. The fixed-code hook below exists only for CI's own copy.
        var overrideCode = Environment.GetEnvironmentVariable("BINGEMOTE_PAIR_CODE");
#if !PAIR_CODE_HOOK
        // Shipped builds never honor a fixed code. CI defines PAIR_CODE_HOOK for its own copy.
        overrideCode = null;
#endif
        if (overrideCode is { Length: 4 } && overrideCode.All(char.IsDigit))
        {
            PairCode = overrideCode;
        }

        using var http = new HttpServer();
        using var discovery = new DiscoveryResponder();
        CheckForUpdate();
        try { http.Start(); discovery.Start(); }
        catch (Exception ex) {
            Log("Could not start listening: " + ex.Message);
            // Watchdog: no pop-up loop, but not silence either - one quiet tray notice per sign-in.
            if (ensureRunning) { NotifyListenFailureOnce(); return 1; }
            MessageBox.Show("Could not start listening for the phone. If Windows asked for administrator permission earlier, choose Yes - it is needed once to open the port.\n\n" + ex.Message,
                "BingeMote", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        RegisterWatchdog(); // only a working app gets the watchdog: no 5-minute relaunch loop after a failed setup

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

    internal static string NewPairCode() => RandomNumberGenerator.GetInt32(0, 10_000).ToString("D4");

    internal static string NewToken() => Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();

    /// <summary>One line in %LocalAppData%\BingeMote\helper.log, so a bad launch leaves a trail.</summary>
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
                    var spent = p.TryGetProperty("adoptSpent", out var asp) && asp.GetBoolean();
                    string? adoptReq = p.TryGetProperty("adoptRequestId", out var ari) ? ari.GetString() : null;
                    DateTime? adoptAt = null;
                    if (p.TryGetProperty("adoptServedAtUtc", out var asa) && asa.GetString() is string asas
                        && DateTime.TryParse(asas, null, System.Globalization.DateTimeStyles.RoundtripKind, out var asad))
                        adoptAt = asad;
                    Phones.Add(new PairedPhone { Token = t, Name = string.IsNullOrWhiteSpace(n) ? "Phone " + (Phones.Count + 1) : n, AdoptSpent = spent, AdoptRequestId = adoptReq, AdoptServedAtUtc = adoptAt });
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
            KeepAwakeEnabled = !doc.RootElement.TryGetProperty("keepAwake", out var ka) || ka.ValueKind != JsonValueKind.False;
            VolumeOsdEnabled = !doc.RootElement.TryGetProperty("volumeOsd", out var vo) || vo.ValueKind != JsonValueKind.False;
            LastRunVersion = doc.RootElement.TryGetProperty("lastRunVersion", out var lv) ? lv.GetString() : null;
            _pendingVlcFromConfig = doc.RootElement.TryGetProperty("pendingVlcPassword", out var pvp) ? pvp.GetString() : null;
            }
        }
        catch { lock (PhonesLock) { Phones.Clear(); } DeclinedNetworks.Clear(); HomeNetworks.Clear(); MoviesFolder = null; }
        // A vlcrc write that was waiting for VLC to close survives a helper restart:
        // complete it now when VLC is already closed, or re-arm the retry when it is not.
        var pendingVlc = _pendingVlcFromConfig;
        _pendingVlcFromConfig = null;
        if (!string.IsNullOrEmpty(pendingVlc)) VlcSetup.RestorePending(pendingVlc);
    }

    private static string? _pendingVlcFromConfig;

    internal static void SaveConfig()
    {
        Directory.CreateDirectory(DataDir);
        var json = JsonSerializer.Serialize(new
        {
            phones = PhonesSnapshot().Select(p => new { token = p.Token, name = p.Name, adoptSpent = p.AdoptSpent, adoptRequestId = p.AdoptRequestId, adoptServedAtUtc = p.AdoptServedAtUtc?.ToString("o") }).ToArray(),
            pendingVlcPassword = VlcSetup.PendingValue,
            moviesFolder = MoviesFolder,
            keepAwake = KeepAwakeEnabled,
            volumeOsd = VolumeOsdEnabled,
            declinedNetworks = DeclinedNetworks.ToArray(),
            homeNetworks = HomeNetworks.ToArray(),
            lastRunVersion = LastRunVersion,
        });
        File.WriteAllText(ConfigPath, json);
    }

    internal static string MachineLabel() => Environment.MachineName;

    /// <summary>"1.3.1" - the running build, for the Updates card and feedback reports.</summary>
    [DllImport("shell32.dll")]
    private static extern void SHChangeNotify(int eventId, int flags, IntPtr item1, IntPtr item2);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern int RegisterApplicationRestart(string? pwzCommandLine, int dwFlags);

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

    /// <summary>True when he turned BingeMote off in Settings > Startup apps (or Task Manager).
    /// Windows records it in StartupApproved; the watchdog honors it so off stays off.</summary>
    private static bool StartupDisabledByUser()
    {
        try
        {
            using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run");
            return k?.GetValue("BingeMote") is byte[] b && b.Length > 0 && (b[0] & 1) == 1; // 03/01 = disabled, 02 = enabled
        }
        catch { return false; }
    }

    /// <summary>A watchdog start that cannot listen used to fail silently every 5 minutes.
    /// Show one quiet tray balloon per sign-in instead; the marker file dedupes the repeats.</summary>
    private static void NotifyListenFailureOnce()
    {
        try
        {
            if (File.Exists(ListenFailNoticePath)) return;
            File.WriteAllText(ListenFailNoticePath, DateTime.UtcNow.ToString("O"));
            using var ni = new WinForms.NotifyIcon { Visible = true, BalloonTipTitle = "BingeMote" };
            try { ni.Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!); }
            catch { ni.Icon = System.Drawing.SystemIcons.Application; }
            ni.BalloonTipText = "BingeMote could not start listening for your phone. Open BingeMote from the Start menu to finish its setup.";
            ni.ShowBalloonTip(8000);
            Thread.Sleep(4000); // the balloon dies with the process; give it a moment to appear
        }
        catch { }
    }

    /// <summary>Start with Windows for this user (no admin needed).</summary>
    private static void RegisterAutostart()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run", writable: true);
            key?.DeleteValue("BingeMoteHelper", false); // the 1.x entry name
            key?.SetValue("BingeMote", "\"" + Environment.ProcessPath + "\" --autostart");
        }
        catch { /* autostart is a convenience, not a requirement */ }
    }

    /// <summary>The tray Quit: remember it was on purpose so the watchdog does not revive the
    /// app until he starts it again or signs in.</summary>
    internal static void QuitOnPurpose()
    {
        try { File.WriteAllText(QuitMarkerPath, ""); } catch { }
        Log("Quit from the tray menu (staying off until the next sign-in or manual start).");
        Application.Exit();
    }

    /// <summary>A per-user scheduled task relaunches the app (silent --ensure-running) every
    /// 5 minutes when it is not running. Runs as him, so no admin prompt; uninstall deletes it.</summary>
    private static void RegisterWatchdog()
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (exe == null) return;
            // XML, not schtasks flags: the defaults stop the task after 3 days, pause it on
            // battery and run it below normal priority - all wrong for keeping the app alive.
            var xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n"
                + "<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\n"
                + "  <Triggers><TimeTrigger><StartBoundary>2020-01-01T00:00:00</StartBoundary>"
                + "<Repetition><Interval>PT5M</Interval></Repetition></TimeTrigger></Triggers>\n"
                + "  <Settings>"
                + "<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>"
                + "<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>"
                + "<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>"
                + "<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>"
                + "<StartWhenAvailable>true</StartWhenAvailable>"
                + "<Priority>4</Priority>"
                + "</Settings>\n"
                + "  <Actions><Exec><Command>" + System.Security.SecurityElement.Escape(exe) + "</Command>"
                + "<Arguments>--ensure-running</Arguments></Exec></Actions>\n"
                + "</Task>";
            var tmp = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "bingemote-watchdog.xml");
            System.IO.File.WriteAllText(tmp, xml, System.Text.Encoding.Unicode);
            var psi = new System.Diagnostics.ProcessStartInfo("schtasks",
                "/Create /F /TN \"BingeMote Watchdog\" /XML \"" + tmp + "\"")
            { CreateNoWindow = true, UseShellExecute = false };
            using var p = System.Diagnostics.Process.Start(psi);
            p?.WaitForExit(10000);
            try { System.IO.File.Delete(tmp); } catch { }
        }
        catch { /* the watchdog is a safety net, not a requirement */ }
    }
}
