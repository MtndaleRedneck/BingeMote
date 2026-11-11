using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace BingeMoteHelper;

/// <summary>
/// Frame-relative vector volume pill. It never takes focus or captures clicks, and
/// retains the existing display-power/setting gates and 1.5s hold + 150ms fade.
/// All art is drawn here at output resolution; no photo or bitmap assets are shipped.
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
#endif

    internal static Rectangle PanelBounds(Rectangle frame)
    {
        if (frame.Width <= 0 || frame.Height <= 0) throw new ArgumentOutOfRangeException(nameof(frame));
        var w = Math.Max(2, (int)Math.Round(frame.Width * 0.167));
        var h = Math.Max(2, (int)Math.Round(frame.Height * 0.0375));
        var gap = (int)Math.Round(frame.Height * 0.059);
        return new Rectangle(frame.X + (frame.Width - w) / 2, frame.Bottom - gap - h, w, h);
    }

    private sealed class OsdWindow : Form
    {
        private const int PanelAlpha = 140; // #141418, 55% fill only
        private static readonly Color PanelColor = Color.FromArgb(0x14, 0x14, 0x18);
        private static readonly Color IconColor = Color.FromArgb(0xEB, 0xEB, 0xEB);
        private static readonly Color MutedColor = Color.FromArgb(0xAA, 0xAA, 0xAA);
        private static readonly Color Orange = Color.FromArgb(0xFF, 0x96, 0x28);
        private Rectangle _panelBounds;

        private int _percent;
        private bool _muted;
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
            PositionOnScreen();
        }

        private void PositionOnScreen()
        {
            // Use the same primary output as the existing overlay. Bounds, not the
            // taskbar work area: the pill must keep its position over full-screen video.
            var screen = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1920, 1080);
            _panelBounds = PanelBounds(screen);
            Bounds = _panelBounds;
        }

        public void ShowValue(int percent, bool muted)
        {
            _percent = percent;
            _muted = muted;
            _fadeTimer.Stop();
            if (!Visible) Show();
            PositionOnScreen(); // refresh after display/resolution changes, without DPI double-scaling
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
            using var bmp = RenderBitmap(_panelBounds.Width, _panelBounds.Height, _percent, _muted, contentAlpha);
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

        /// <summary>Per-pixel alpha pill at final output resolution, no edge or shadow.</summary>
        private static Bitmap RenderBitmap(int panelW, int panelH, int percent, bool muted, float fade, bool forLayered = true)
        {
            var bmp = new Bitmap(panelW, panelH, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                var panel = new RectangleF(0.5f, 0.5f, panelW - 1f, panelH - 1f);
                using var path = RoundedRect(panel, panel.Height / 2f);
                using var brush = new SolidBrush(Color.FromArgb((int)(PanelAlpha * fade), PanelColor));
                g.FillPath(brush, path);
                DrawContent(g, panel, percent, muted, fade);
            }
            if (forLayered) Premultiply(bmp);
            return bmp;
        }

        private static void DrawContent(Graphics g, RectangleF panel, int percent, bool muted, float fade)
        {
            byte A(int full) => (byte)(full * fade);
            var h = panel.Height;
            var cy = panel.Y + h / 2f;
            var iconSize = h * 0.5f;
            var ix = panel.X + panel.Width * 0.08f;
            var iy = cy - iconSize / 2f;
            using (var brush = new SolidBrush(Color.FromArgb(A(224), IconColor)))
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
            // Original vector speaker with two arcs, also visible behind the mute slash.
            using (var pen = new Pen(Color.FromArgb(A(224), IconColor), Math.Max(1f, h * 0.045f)) { StartCap = LineCap.Round, EndCap = LineCap.Round })
            {
                var wcx = ix + iconSize * 0.58f;
                g.DrawArc(pen, wcx - iconSize * 0.10f, cy - iconSize * 0.22f, iconSize * 0.44f, iconSize * 0.44f, -55, 110);
                g.DrawArc(pen, wcx - iconSize * 0.06f, cy - iconSize * 0.36f, iconSize * 0.72f, iconSize * 0.72f, -55, 110);
            }
            if (muted)
            {
                var from = new PointF(ix - iconSize * 0.04f, iy + iconSize * 0.98f);
                var to = new PointF(ix + iconSize * 1.08f, iy + iconSize * 0.02f);
                using var outline = new Pen(Color.FromArgb(A(255), PanelColor), Math.Max(2f, h * 0.11f)) { StartCap = LineCap.Round, EndCap = LineCap.Round };
                using var slash = new Pen(Color.FromArgb(A(255), 0xEB, 0x32, 0x2D), Math.Max(1f, h * 0.06f)) { StartCap = LineCap.Round, EndCap = LineCap.Round };
                g.DrawLine(outline, from, to);
                g.DrawLine(slash, from, to);
            }
            var barX = panel.X + panel.Width * 0.24f;
            var barRight = panel.X + panel.Width * 0.77f;
            var barW = barRight - barX;
            var barH = h * 0.10f;
            using (var track = new Pen(Color.FromArgb(A(89), Color.White), barH) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                g.DrawLine(track, barX, cy, barRight, cy);
            var fillW = barW * Math.Clamp(percent, 0, 100) / 100f;
            var fillColor = muted ? MutedColor : Orange;
            using (var pen = new Pen(Color.FromArgb(A(255), fillColor), barH) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                if (fillW > 0) g.DrawLine(pen, barX, cy, barX + fillW, cy);
            using (var fill = new SolidBrush(Color.FromArgb(A(255), fillColor)))
            {
                var thumbD = h * 0.25f;
                g.FillEllipse(fill, barX + fillW - thumbD / 2f, cy - thumbD / 2f, thumbD, thumbD);
            }
            using var font = new Font("Segoe UI", h * 0.5f, FontStyle.Bold, GraphicsUnit.Pixel);
            using var textBrush = new SolidBrush(Color.FromArgb(A(muted ? 255 : 224), muted ? MutedColor : IconColor));
            using var format = new StringFormat { Alignment = StringAlignment.Far, LineAlignment = StringAlignment.Center, FormatFlags = StringFormatFlags.NoWrap };
            g.DrawString(Math.Clamp(percent, 0, 100).ToString(), font, textBrush,
                new RectangleF(panel.X + panel.Width * 0.80f, panel.Y, panel.Width * 0.15f, h), format);
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
        /// <summary>CI-only: exact output pixels on a neutral 1920x1080 frame.</summary>
        public static void RenderPng(string path, int percent, bool muted)
        {
            var bounds = PanelBounds(new Rectangle(0, 0, 1920, 1080));
            using var bmp = RenderBitmap(bounds.Width, bounds.Height, percent, muted, 1f, forLayered: false);
            bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
            using var frame = new Bitmap(1920, 1080);
            using (var g = Graphics.FromImage(frame))
            {
                g.Clear(Color.FromArgb(48, 54, 62));
                g.DrawImageUnscaled(bmp, bounds.Location);
            }
            frame.Save(Path.ChangeExtension(path, ".frame.png"), System.Drawing.Imaging.ImageFormat.Png);
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
