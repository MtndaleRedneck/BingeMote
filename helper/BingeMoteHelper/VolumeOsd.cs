using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace BingeMoteHelper;

/// <summary>
/// The small volume pop-up the PC screen shows when the phone changes the volume (his
/// "like it does when you hit the volume buttons on the laptop", Nov 3; Tab's SPEC +
/// Nov 4 verdict): a 300x64 rounded panel at 100% scale (scales with Windows DPI),
/// bottom-center 48px above the screen bottom - speaker icon, bar with orange fill +
/// thumb, percentage. Muted shows the slashed speaker, grey bar/thumb, and the number
/// keeps the level it returns to when unmuted. Appears on each change, updates live,
/// auto-hides 1.5s after the last change (150ms fade). Never takes focus, clicks pass
/// through, topmost so it shows over fullscreen VLC. The panel fill alone is ~95%
/// opaque (icon, track and number fully opaque), with a soft ~8px shadow and a faint
/// 1px white edge. Never shown while the screen is dark or unknown (a wake-only press
/// is swallowed before it reaches the helper; this is the second wall) and never while
/// "Show volume on PC screen" is off (default ON).
/// </summary>
internal static class VolumeOsd
{
    private static readonly object Gate = new();
    private static SynchronizationContext? _ui;
    private static OsdWindow? _window;

    /// <summary>The helper window's thread owns the pop-up, same pattern as the
    /// keep-awake hold. Called once from the window's constructor.</summary>
    public static void BindUi(SynchronizationContext ui) { lock (Gate) _ui = ui; }

    /// <summary>Show or update the pop-up for a volume/mute change. Called from the
    /// listener's pool threads; marshals to the UI thread. Silent no-op when the
    /// setting is off or the screen is dark/unknown.</summary>
    public static void Show(int percent, bool muted)
    {
        if (!Program.VolumeOsdEnabled) return;
        if (PowerWake.DisplayDarkOrUnknown) return;
        SynchronizationContext? ui;
        lock (Gate) ui = _ui;
        if (ui == null) return; // no owning thread: the pop-up stays off (fail-safe)
        ui.Post(_ => ShowOnUi(Math.Clamp(percent, 0, 100), muted), null);
    }

    private static void ShowOnUi(int percent, bool muted)
    {
        lock (Gate)
        {
            _window ??= new OsdWindow();
            _window.ShowValue(percent, muted);
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
    /// <summary>CI-only (never in shipped builds): render the pop-up (60% normal and
    /// muted) to PNGs, same idea as --window-shot, so the layout gets a pixel review
    /// without a phone. The bitmap written here is the exact window content; what it
    /// still does not show is real desktop compositing over fullscreen VLC.</summary>
    public static void CaptureShot(string path, string mutedPath, int percent)
    {
        OsdWindow.RenderPng(path, percent, muted: false);
        OsdWindow.RenderPng(mutedPath, percent, muted: true);
    }

    /// <summary>CI-only (never in shipped builds): show the REAL pop-up window on the
    /// desktop for a while, so an outer screenshot can prove actual desktop compositing
    /// (over fullscreen VLC) and a probe window can prove click-through - the bitmap
    /// proofs alone show neither. Re-asserts the value faster than the 1.5s auto-hide
    /// so it never fades, and pumps messages itself (no message loop needed from the
    /// caller). Returns when the time is up.</summary>
    public static int ShowForCapture(int percent, bool muted, int seconds)
    {
        ApplicationConfiguration.Initialize();
        using var w = new OsdWindow();
        w.ShowValue(Math.Clamp(percent, 0, 100), muted);
        var until = Environment.TickCount64 + Math.Max(1, seconds) * 1000L;
        while (Environment.TickCount64 < until)
        {
            Application.DoEvents();
            Thread.Sleep(500); // well under the 1.5s hide timer
            w.ShowValue(Math.Clamp(percent, 0, 100), muted);
        }
        w.HideImmediately();
        return 0;
    }
#endif

    private sealed class OsdWindow : Form
    {
        // SPEC layout in 96-DPI units; everything multiplies by the screen's DPI scale.
        private const int DesignW = 300, DesignH = 64, DesignRadius = 12, DesignBottomGap = 48;
        private const int ShadowPad = 14; // room for the ~8px soft shadow around the panel
        private const int PanelAlpha = 242; // #202020 at ~95% - the panel fill ONLY
        private static readonly Color PanelColor = Color.FromArgb(0x20, 0x20, 0x20);
        private static readonly Color IconColor = Color.FromArgb(0xE6, 0xE6, 0xE6);
        private static readonly Color TrackColor = Color.FromArgb(0x8B, 0x8B, 0x8B);
        private static readonly Color Orange = Color.FromArgb(0xF8, 0x7C, 0x02);

        private int _percent;
        private bool _muted;
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
            BackColor = Color.Black; // never visible: the layered bitmap owns every pixel
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
                // Layered + transparent = clicks pass straight through (the shadow area
                // included); noactivate + toolwindow = never steals focus, never a
                // taskbar button. Topmost is what keeps it over fullscreen VLC.
                cp.ExStyle |= WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE;
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            _scale = DeviceDpi / 96f;
            var pad = PadPx();
            Size = new Size(PanelW() + pad * 2, PanelH() + pad * 2);
            var screen = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1920, 1080);
            // The PANEL (not the shadow padding) sits bottom-center, 48px above the bottom.
            Location = new Point(
                screen.X + (screen.Width - PanelW()) / 2 - pad,
                screen.Bottom - (int)Math.Round(DesignBottomGap * _scale) - PanelH() - pad);
        }

        private int PanelW() => (int)Math.Round(DesignW * _scale);
        private int PanelH() => (int)Math.Round(DesignH * _scale);
        private int PadPx() => (int)Math.Ceiling(ShadowPad * _scale);

        public void ShowValue(int percent, bool muted)
        {
            _percent = percent;
            _muted = muted;
            _fadeTimer.Stop();
            if (!Visible) Show();
            Present(1f);
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
            var left = Math.Max(0f, 1f - _fadeStep / 10f);
            if (_fadeStep >= 10)
            {
                _fadeTimer.Stop();
                Hide();
                return;
            }
            Present(left);
        }

        /// <summary>Render the current state and make it the window's pixels.</summary>
        private void Present(float contentAlpha)
        {
            using var bmp = RenderBitmap(PanelW(), PanelH(), PadPx(), _scale, _percent, _muted, contentAlpha);
            SetLayeredBitmap(bmp);
        }

        private void SetLayeredBitmap(Bitmap bmp)
        {
            var screenDc = GetDC(IntPtr.Zero);
            var memDc = CreateCompatibleDC(screenDc);
            var hBitmap = bmp.GetHbitmap(Color.FromArgb(0));
            var oldBitmap = SelectObject(memDc, hBitmap);
            try
            {
                var size = new SIZE { cx = bmp.Width, cy = bmp.Height };
                var src = new POINT { x = 0, y = 0 };
                var dst = new POINT { x = Left, y = Top };
                var blend = new BLENDFUNCTION { BlendOp = 0, BlendFlags = 0, SourceConstantAlpha = 255, AlphaFormat = 1 };
                UpdateLayeredWindow(Handle, screenDc, ref dst, ref size, memDc, ref src, 0, ref blend, 0x02 /* ULW_ALPHA */);
            }
            finally
            {
                SelectObject(memDc, oldBitmap);
                DeleteObject(hBitmap);
                DeleteDC(memDc);
                ReleaseDC(IntPtr.Zero, screenDc);
            }
        }

        /// <summary>The full window bitmap: shadow padding around the panel, per-pixel
        /// alpha (premultiplied for UpdateLayeredWindow). Panel fill at 95%, content at
        /// full opacity, everything scaled by the fade factor.</summary>
        private static Bitmap RenderBitmap(int panelW, int panelH, int pad, float s, int percent, bool muted, float fade, bool forLayered = true)
        {
            var w = panelW + pad * 2;
            var h = panelH + pad * 2;
            var bmp = new Bitmap(w, h, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                var panel = new RectangleF(pad, pad, panelW - 1, panelH - 1);
                var radius = DesignRadius * s;
                // Soft shadow: stacked rounded rects, low-opacity black, ~8px spread.
                using (var path = RoundedRect(panel, radius))
                {
                    for (var i = 8; i >= 1; i--)
                    {
                        var a = (int)(7 * fade);
                        if (a <= 0) break;
                        using var shadow = new SolidBrush(Color.FromArgb(a, 0, 0, 0));
                        using var shadowPath = RoundedRect(new RectangleF(panel.X - i * s * 0.5f, panel.Y - i * s * 0.35f + 2 * s * 0.5f, panel.Width + i * s, panel.Height + i * s * 0.7f), radius + i * s * 0.5f);
                        g.FillPath(shadow, shadowPath);
                    }
                    // Panel fill #202020 at ~95%; the fill alone is translucent.
                    using var brush = new SolidBrush(Color.FromArgb((int)(PanelAlpha * fade), PanelColor));
                    g.FillPath(brush, path);
                    // Faint 1px white edge (~8%).
                    using var edge = new Pen(Color.FromArgb((int)(20 * fade), 255, 255, 255), Math.Max(1f, s));
                    g.DrawPath(edge, path);
                }
                DrawContent(g, panel, s, percent, muted, fade);
            }
            if (forLayered) Premultiply(bmp);
            return bmp;
        }

        private static void DrawContent(Graphics g, RectangleF panel, float s, int percent, bool muted, float fade)
        {
            byte A(int full) => (byte)(full * fade);
            var cy = panel.Y + panel.Height / 2f;
            // Speaker icon: 26px at 22px left inset; muted = slashed, no waves.
            var iconSize = 26f * s;
            var ix = panel.X + 22f * s;
            var iy = cy - iconSize / 2f;
            using (var brush = new SolidBrush(Color.FromArgb(A(255), IconColor)))
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
            using (var pen = new Pen(Color.FromArgb(A(255), IconColor), 2f * s) { StartCap = LineCap.Round, EndCap = LineCap.Round })
            {
                if (muted)
                {
                    g.DrawLine(pen, ix + iconSize * 0.14f, iy + iconSize * 0.12f, ix + iconSize * 0.95f, iy + iconSize * 0.9f);
                }
                else
                {
                    var wcx = ix + iconSize * 0.58f;
                    g.DrawArc(pen, wcx - iconSize * 0.10f, cy - iconSize * 0.22f, iconSize * 0.44f, iconSize * 0.44f, -55, 110);
                    g.DrawArc(pen, wcx - iconSize * 0.06f, cy - iconSize * 0.36f, iconSize * 0.72f, iconSize * 0.72f, -55, 110);
                }
            }
            // Bar: x 70..232, 4px tall, vertically centered; fill + thumb orange, grey
            // while muted; the number keeps the level it returns to when unmuted.
            var barX = panel.X + 70f * s;
            var barRight = panel.X + 232f * s;
            var barW = barRight - barX;
            var barH = Math.Max(2f, 4f * s);
            using (var track = new SolidBrush(Color.FromArgb(A(255), TrackColor)))
                g.FillRectangle(track, barX, cy - barH / 2f, barW, barH);
            var fillW = barW * Math.Clamp(percent, 0, 100) / 100f;
            var fillColor = muted ? TrackColor : Orange;
            using (var fill = new SolidBrush(Color.FromArgb(A(255), fillColor)))
            {
                if (fillW > 0) g.FillRectangle(fill, barX, cy - barH / 2f, fillW, barH);
                var thumbD = 20f * s;
                var thumbCx = Math.Clamp(barX + fillW, barX, barRight);
                g.FillEllipse(fill, thumbCx - thumbD / 2f, cy - thumbD / 2f, thumbD, thumbD);
            }
            var label = percent.ToString();
            Font font;
            try { font = new Font("Segoe UI Semibold", 24f * s, GraphicsUnit.Pixel); }
            catch { font = new Font(FontFamily.GenericSansSerif, 24f * s, GraphicsUnit.Pixel); }
            using (font)
            using (var textBrush = new SolidBrush(Color.FromArgb(A(255), IconColor)))
            {
                var size = g.MeasureString(label, font);
                g.DrawString(label, font, textBrush, panel.Right - 20f * s - size.Width, cy - size.Height / 2f);
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

        /// <summary>UpdateLayeredWindow wants premultiplied pixels; GDI+ renders straight.</summary>
        private static void Premultiply(Bitmap bmp)
        {
            var rect = new Rectangle(0, 0, bmp.Width, bmp.Height);
            var data = bmp.LockBits(rect, System.Drawing.Imaging.ImageLockMode.ReadWrite, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
            var bytes = new byte[Math.Abs(data.Stride) * data.Height];
            Marshal.Copy(data.Scan0, bytes, 0, bytes.Length);
            for (var i = 0; i + 3 < bytes.Length; i += 4) // B,G,R,A
            {
                var a = bytes[i + 3];
                bytes[i] = (byte)(bytes[i] * a / 255);
                bytes[i + 1] = (byte)(bytes[i + 1] * a / 255);
                bytes[i + 2] = (byte)(bytes[i + 2] * a / 255);
            }
            Marshal.Copy(bytes, 0, data.Scan0, bytes.Length);
            bmp.UnlockBits(data);
        }

#if WINDOW_SHOT_HOOK
        /// <summary>CI-only: the exact window bitmap as a PNG (transparent corners,
        /// shadow, 95% panel), at the SPEC's 100% scale.</summary>
        public static void RenderPng(string path, int percent, bool muted)
        {
            using var bmp = RenderBitmap(DesignW, DesignH, ShadowPad, 1f, percent, muted, 1f, forLayered: false);
            bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
        }
#endif

        [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr hWnd);
        [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);
        [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr hdc);
        [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr hdc);
        [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr hdc, IntPtr h);
        [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr h);
        [DllImport("user32.dll")] private static extern bool UpdateLayeredWindow(IntPtr hwnd, IntPtr hdcDst, ref POINT pptDst, ref SIZE psize, IntPtr hdcSrc, ref POINT pptSrc, int crKey, ref BLENDFUNCTION pblend, int dwFlags);

        [StructLayout(LayoutKind.Sequential)] private struct POINT { public int x; public int y; }
        [StructLayout(LayoutKind.Sequential)] private struct SIZE { public int cx; public int cy; }
        [StructLayout(LayoutKind.Sequential)] private struct BLENDFUNCTION { public byte BlendOp; public byte BlendFlags; public byte SourceConstantAlpha; public byte AlphaFormat; }
    }
}
