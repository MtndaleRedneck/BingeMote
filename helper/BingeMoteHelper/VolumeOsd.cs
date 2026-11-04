using System.Drawing.Drawing2D;

namespace BingeMoteHelper;

/// <summary>
/// The small volume pop-up the PC screen shows when the phone changes the volume (his
/// "like it does when you hit the volume buttons on the laptop", Nov 3; Tab's SPEC):
/// a 300x64 rounded panel at 100% scale (scales with Windows DPI), bottom-center 48px
/// above the screen bottom - speaker icon, bar with orange fill + thumb, percentage.
/// Appears on each volume change from the phone, updates live, auto-hides 1.5s after
/// the last change (150ms fade). Never takes focus, clicks pass through, topmost so it
/// shows over fullscreen VLC. Never shown while the screen is dark or unknown (a
/// wake-only press is swallowed before it ever reaches /volume/set; this is the second
/// wall) and never while the "Show volume on PC screen" setting is off (default ON).
/// </summary>
internal static class VolumeOsd
{
    private static readonly object Gate = new();
    private static SynchronizationContext? _ui;
    private static OsdWindow? _window;

    /// <summary>The helper window's thread owns the pop-up, same pattern as the
    /// keep-awake hold. Called once from the window's constructor.</summary>
    public static void BindUi(SynchronizationContext ui) { lock (Gate) _ui = ui; }

    /// <summary>Show or update the pop-up for a volume change. Called from the
    /// listener's pool threads; marshals to the UI thread. Silent no-op when the
    /// setting is off or the screen is dark/unknown.</summary>
    public static void Show(int percent)
    {
        if (!Program.VolumeOsdEnabled) return;
        if (PowerWake.DisplayDarkOrUnknown) return;
        SynchronizationContext? ui;
        lock (Gate) ui = _ui;
        if (ui == null) return; // no owning thread: the pop-up stays off (fail-safe)
        ui.Post(_ => ShowOnUi(Math.Clamp(percent, 0, 100)), null);
    }

    private static void ShowOnUi(int percent)
    {
        lock (Gate)
        {
            _window ??= new OsdWindow();
            _window.ShowValue(percent);
        }
    }

    /// <summary>Hide right away (the setting was switched off mid-show).</summary>
    public static void HideNow()
    {
        SynchronizationContext? ui;
        lock (Gate) ui = _ui;
        if (ui == null) return;
        ui.Post(_ => { lock (Gate) _window?.HideImmediately(); }, null);
    }

#if WINDOW_SHOT_HOOK
    /// <summary>CI-only (never in shipped builds): render the pop-up at 60% to a PNG,
    /// same idea as --window-shot, so the layout gets a pixel review without a phone.</summary>
    public static void CaptureShot(string path, int percent)
    {
        using var window = new OsdWindow();
        window.RenderShot(path, percent);
    }
#endif

    private sealed class OsdWindow : Form
    {
        // SPEC layout in 96-DPI units; everything multiplies by the screen's DPI scale.
        private const int DesignW = 300, DesignH = 64, DesignRadius = 12, DesignBottomGap = 48;
        private const double BaseOpacity = 0.95; // fill #202020 at ~95%
        private static readonly Color PanelColor = Color.FromArgb(0x20, 0x20, 0x20);
        private static readonly Color BorderColor = Color.FromArgb(0x3A, 0x3A, 0x3A);
        private static readonly Color IconColor = Color.FromArgb(0xE6, 0xE6, 0xE6);
        private static readonly Color TrackColor = Color.FromArgb(0x8B, 0x8B, 0x8B);
        private static readonly Color Orange = Color.FromArgb(0xF8, 0x7C, 0x02);

        private int _percent;
        private float _scale = 1f;
        private readonly System.Windows.Forms.Timer _hideTimer = new() { Interval = 1500 };
        private readonly System.Windows.Forms.Timer _fadeTimer = new() { Interval = 15 };
        private int _fadeStep;

        public OsdWindow()
        {
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            DoubleBuffered = true;
            BackColor = PanelColor;
            _hideTimer.Tick += (_, _) => BeginFade();
            _fadeTimer.Tick += (_, _) => FadeTick();
        }

        protected override CreateParams CreateParams
        {
            get
            {
                const int WS_EX_TRANSPARENT = 0x20;
                const int WS_EX_TOOLWINDOW = 0x80;
                const int WS_EX_LAYERED = 0x80000;
                const int WS_EX_NOACTIVATE = 0x08000000;
                var cp = base.CreateParams;
                // Layered + transparent = clicks pass straight through; noactivate +
                // toolwindow = never steals focus, never a taskbar button. Topmost is
                // what keeps it over fullscreen VLC.
                cp.ExStyle |= WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE;
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            _scale = DeviceDpi / 96f;
            Size = new Size((int)Math.Round(DesignW * _scale), (int)Math.Round(DesignH * _scale));
            var radius = DesignRadius * _scale;
            using var path = RoundedRect(new Rectangle(0, 0, Width - 1, Height - 1), radius);
            Region = new Region(path);
            var screen = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1920, 1080);
            Location = new Point(
                screen.X + (screen.Width - Width) / 2,
                screen.Bottom - (int)Math.Round(DesignBottomGap * _scale) - Height);
        }

        public void ShowValue(int percent)
        {
            _percent = percent;
            _fadeTimer.Stop();
            Opacity = BaseOpacity;
            if (!Visible) Show();
            Invalidate();
            _hideTimer.Stop();
            _hideTimer.Start();
        }

        public void HideImmediately()
        {
            _hideTimer.Stop();
            _fadeTimer.Stop();
            if (Visible) Hide();
        }

        private void BeginFade()
        {
            _hideTimer.Stop();
            _fadeStep = 0;
            _fadeTimer.Start();
        }

        private void FadeTick()
        {
            _fadeStep++;
            // 150ms over ~10 15ms ticks.
            var left = Math.Max(0.0, 1.0 - _fadeStep / 10.0);
            Opacity = BaseOpacity * left;
            if (_fadeStep >= 10)
            {
                _fadeTimer.Stop();
                Hide();
            }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            Render(e.Graphics, _percent);
        }

        private void Render(Graphics g, int percent)
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var s = _scale;
            // Panel + 1px border (the form's BackColor is already the panel fill).
            using (var border = new Pen(BorderColor, Math.Max(1f, s)))
            {
                using var path = RoundedRect(new RectangleF(0.5f * s, 0.5f * s, Width - 1f * s - 1, Height - 1f * s - 1), DesignRadius * s);
                g.DrawPath(border, path);
            }
            var cy = Height / 2f;
            // Speaker icon: 26px at 22px left inset.
            var iconSize = 26f * s;
            var ix = 22f * s;
            var iy = cy - iconSize / 2f;
            using (var brush = new SolidBrush(IconColor))
            {
                var body = new PointF[]
                {
                    new(ix, iy + iconSize * 0.36f),
                    new(ix + iconSize * 0.22f, iy + iconSize * 0.36f),
                    new(ix + iconSize * 0.52f, iy + iconSize * 0.12f),
                    new(ix + iconSize * 0.52f, iy + iconSize * 0.88f),
                    new(ix + iconSize * 0.22f, iy + iconSize * 0.64f),
                    new(ix, iy + iconSize * 0.64f),
                };
                g.FillPolygon(brush, body);
            }
            using (var wave = new Pen(IconColor, 2f * s) { StartCap = LineCap.Round, EndCap = LineCap.Round })
            {
                var wcx = ix + iconSize * 0.58f;
                g.DrawArc(wave, wcx - iconSize * 0.10f, cy - iconSize * 0.22f, iconSize * 0.44f, iconSize * 0.44f, -55, 110);
                g.DrawArc(wave, wcx - iconSize * 0.06f, cy - iconSize * 0.36f, iconSize * 0.72f, iconSize * 0.72f, -55, 110);
            }
            // Bar: x 70..232, 4px tall, vertically centered; orange fill + 20px thumb.
            var barX = 70f * s;
            var barRight = 232f * s;
            var barW = barRight - barX;
            var barH = Math.Max(2f, 4f * s);
            using (var track = new SolidBrush(TrackColor))
                g.FillRectangle(track, barX, cy - barH / 2f, barW, barH);
            var fillW = barW * Math.Clamp(percent, 0, 100) / 100f;
            using (var fill = new SolidBrush(Orange))
            {
                if (fillW > 0) g.FillRectangle(fill, barX, cy - barH / 2f, fillW, barH);
                var thumbD = 20f * s;
                var thumbCx = Math.Clamp(barX + fillW, barX, barRight);
                g.FillEllipse(fill, thumbCx - thumbD / 2f, cy - thumbD / 2f, thumbD, thumbD);
            }
            // Percentage: right-aligned, 20px inset, Medium 24px.
            var label = percent.ToString();
            Font font;
            try { font = new Font("Segoe UI Semibold", 24f * s, GraphicsUnit.Pixel); }
            catch { font = new Font(FontFamily.GenericSansSerif, 24f * s, GraphicsUnit.Pixel); }
            using (font)
            using (var textBrush = new SolidBrush(IconColor))
            {
                var size = g.MeasureString(label, font);
                g.DrawString(label, font, textBrush, Width - 20f * s - size.Width, cy - size.Height / 2f);
            }
        }

        private static GraphicsPath RoundedRect(RectangleF rect, float radius)
        {
            var path = new GraphicsPath();
            var d = radius * 2;
            path.AddArc(rect.X, rect.Y, d, d, 180, 90);
            path.AddArc(rect.Right - d, rect.Y, d, d, 270, 90);
            path.AddArc(rect.Right - d, rect.Bottom - d, d, d, 0, 90);
            path.AddArc(rect.X, rect.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

#if WINDOW_SHOT_HOOK
        /// <summary>CI-only: paint the settled pop-up into a PNG without showing anything.</summary>
        public void RenderShot(string path, int percent)
        {
            _scale = 1f; // shots are reviewed at the SPEC's 100% scale
            Size = new Size(DesignW, DesignH); // no handle yet: Render reads Width/Height
            using var bmp = new Bitmap(DesignW, DesignH);
            using (var g = Graphics.FromImage(bmp))
            {
                g.Clear(PanelColor);
                Render(g, percent);
            }
            bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
        }
#endif
    }
}
