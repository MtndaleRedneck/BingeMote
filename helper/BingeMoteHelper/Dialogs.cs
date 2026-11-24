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

/// <summary>Shared look for the small dialogs: 20px margins, measured text (never clipped),
/// smooth rounded buttons (4px) right-aligned with 8px gaps.</summary>
internal class DarkDialog : Form
{
    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Body colors do not theme the non-client Windows title bar.
        var dark = HelperWindow.IsDarkMode() ? 1 : 0;
        try { DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int)); }
        catch (DllNotFoundException) { }
        catch (EntryPointNotFoundException) { }
    }

    [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}

internal static class DialogLook
{
    internal const int Margin = 20;
    internal const int Gap = 8;

    /// <summary>A wrapping label exactly as tall as its text at this width.</summary>
    internal static Label Text(string text, Font font, Color fg, int left, int top, int width)
    {
        var h = TextRenderer.MeasureText(text, font, new Size(width, 0),
            TextFormatFlags.WordBreak | TextFormatFlags.TextBoxControl).Height;
        return new Label
        {
            Left = left, Top = top, Width = width, Height = h + 2,
            Font = font, ForeColor = fg, Text = text, UseMnemonic = false,
        };
    }

    /// <summary>The dialogs' button: card fill, 1px muted edge, as before; now drawn smooth.</summary>
    internal static HelperWindow.ReadableButton Button(string text, Font font, int minWidth, int height, Color card, Color fg, Color edge)
    {
        var b = new HelperWindow.ReadableButton
        {
            Text = text, Font = font, Height = height,
            Width = Math.Max(minWidth, TextRenderer.MeasureText(text, font).Width + 28),
            BackColor = card, ForeColor = fg,
        };
        b.FlatAppearance.BorderSize = 1;
        b.FlatAppearance.BorderColor = edge;
        return b;
    }

    /// <summary>Lay buttons out left to right, ending at <paramref name="right"/>.</summary>
    internal static void RowRight(int right, int top, params Control[] buttons)
    {
        var x = right;
        for (var i = buttons.Length - 1; i >= 0; i--)
        {
            x -= buttons[i].Width;
            buttons[i].Left = x;
            buttons[i].Top = top;
            x -= Gap;
        }
    }
}

/// <summary>The public-Wi-Fi popup: names the network and offers the one-click Private fix,
/// the manual Settings route, or Not now (remembered per Wi-Fi name).</summary>
internal sealed class WifiDialog : DarkDialog
{
    public enum Result { None, Home, Settings, NotNow }
    public Result Answer { get; private set; } = Result.None;

    public WifiDialog(NetProfile profile)
    {
        AutoScaleMode = AutoScaleMode.Dpi;
        var dark = HelperWindow.IsDarkMode();
        var bg = dark ? HelperWindow.DarkBg : HelperWindow.LightBg;
        var card = dark ? HelperWindow.DarkCard : HelperWindow.LightCard;
        var text = dark ? HelperWindow.DarkText : HelperWindow.LightText;
        var muted = dark ? HelperWindow.DarkMuted : HelperWindow.LightMuted;

        Text = "BingeMote";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false; MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        TopMost = true; // at login other windows can cover it
        BackColor = bg;
        ForeColor = text;

        const int clientW = 500;
        var m = DialogLook.Margin;
        var contentW = clientW - 2 * m;
        var body = DialogLook.Text(
            "Windows is treating this Wi-Fi (" + profile.Name + ") like a public hotspot, so your phone cannot reach this PC.\n\n" +
            "If this is your home network, BingeMote can mark it as Private in Windows for you. This also lets Windows use its home-network settings on this Wi-Fi.\n\n" +
            "On a network you don't know (hotel, cafe), choose Not now.",
            new Font("Segoe UI", 9.5f), text, m, m, contentW);
        var small = DialogLook.Text(
            "This only changes this one Wi-Fi. Windows will ask for permission once.",
            new Font("Segoe UI", 8f), text, m, body.Bottom + 12, contentW);
        Button Btn(string label, int width, Result r)
        {
            var b = DialogLook.Button(label, new Font("Segoe UI", 8.5f), width, 32, card, text, muted);
            b.Click += (_, _) => { Answer = r; Close(); };
            return b;
        }
        var home = Btn("This is my home Wi-Fi", 170, Result.Home);
        HelperWindow.StylePrimary(home);
        var settings = Btn("Open Settings instead", 150, Result.Settings);
        var notNow = Btn("Not now", 90, Result.NotNow);
        CancelButton = notNow; // Esc means Not now
        DialogLook.RowRight(clientW - m, small.Bottom + 20, home, settings, notNow);
        ClientSize = new Size(clientW, home.Bottom + m);
        Controls.Add(body);
        Controls.Add(small);
        Controls.Add(home);
        Controls.Add(settings);
        Controls.Add(notNow);
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
        using var dlg = new DarkDialog
        {
            AutoScaleMode = AutoScaleMode.Dpi,
            Text = "BingeMote",
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false, MinimizeBox = false,
            StartPosition = FormStartPosition.CenterParent,
            BackColor = bg, ForeColor = fg,
        };
        const int clientW = 440;
        var m = DialogLook.Margin;
        var body = DialogLook.Text(text, new Font("Segoe UI", 9.5f), fg, m, m, clientW - 2 * m);
        var btnFont = new Font("Segoe UI", 8.5f);
        var yes = DialogLook.Button(confirmLabel, btnFont, 110, 30, card, fg, muted);
        yes.DialogResult = DialogResult.OK;
        var no = DialogLook.Button("Cancel", btnFont, 90, 30, card, fg, muted);
        no.DialogResult = DialogResult.Cancel;
        DialogLook.RowRight(clientW - m, Math.Max(body.Bottom, m + 40) + 20, yes, no);
        dlg.ClientSize = new Size(clientW, yes.Bottom + m);
        dlg.Controls.Add(body);
        dlg.Controls.Add(yes);
        dlg.Controls.Add(no);
        yes.TabIndex = 1;
        no.TabIndex = 0;
        dlg.ActiveControl = no;
        dlg.Shown += (_, _) => no.Select();
        dlg.AcceptButton = no; // Enter on a destructive prompt must mean No
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
        using var dlg = new DarkDialog
        {
            AutoScaleMode = AutoScaleMode.Dpi,
            Text = "BingeMote",
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false, MinimizeBox = false,
            StartPosition = FormStartPosition.CenterParent,
            BackColor = bg, ForeColor = fg,
        };
        const int clientW = 440;
        var m = DialogLook.Margin;
        var body = DialogLook.Text(text, new Font("Segoe UI", 9.5f), fg, m, m, clientW - 2 * m);
        var ok = DialogLook.Button("OK", new Font("Segoe UI", 8.5f), 90, 30, card, fg, muted);
        ok.DialogResult = DialogResult.OK;
        DialogLook.RowRight(clientW - m, Math.Max(body.Bottom, m + 40) + 20, ok);
        dlg.ClientSize = new Size(clientW, ok.Bottom + m);
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
    private readonly HelperWindow.RoundedPanel _messageFrame;
    private readonly HelperWindow.RoundedPanel _previewCard;
    private DarkScrollStrip _scroll = null!;
    private readonly Label _preview;
    private readonly Button _issue;
    private readonly Button _form;

    public FeedbackDialog()
    {
        AutoScaleMode = AutoScaleMode.Dpi;
        Text = "BingeMote";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterParent;
        Font = new Font("Segoe UI", 9.5f);

        // One column, 24px margins (like the main window), measured heights so nothing clips.
        const int clientW = 440, m = 24, contentW = clientW - 2 * m;
        var y = m;
        var title = new Label { Text = "Feedback & bugs", Font = new Font("Segoe UI", 10.5f, FontStyle.Bold), AutoSize = true, Left = m, Top = y, UseMnemonic = false };
        y += title.PreferredHeight + 12;

        // The message box: a smooth rounded frame (4px, 1px edge) with a borderless box inside.
        _messageFrame = new HelperWindow.RoundedPanel { Left = m, Top = y, Width = contentW, Height = 104, Radius = 4 };
        _message = new TextBox
        {
            Multiline = true, Left = 10, Top = 7, Width = contentW - 10 - 4, Height = 104 - 14,
            PlaceholderText = "Your message", BorderStyle = BorderStyle.None,
            ScrollBars = ScrollBars.Vertical,
        };
        _messageFrame.Controls.Add(_message);
        _messageFrame.Click += (_, _) => _message.Focus();
        y += _messageFrame.Height + 20;

        var whatHeader = new Label { Text = "What will be sent", Font = new Font("Segoe UI", 9f, FontStyle.Bold), AutoSize = true, Left = m, Top = y, UseMnemonic = false };
        y += whatHeader.PreferredHeight + 8;

        // The preview sits on a card (8px corners, 12px padding) so it reads as one block.
        _previewCard = new HelperWindow.RoundedPanel { Left = m, Top = y, Width = contentW, Height = 168, Radius = 8 };
        _preview = new Label
        {
            Left = 12, Top = 10, Width = contentW - 24, Height = 168 - 20,
            Font = new Font("Segoe UI", 8.5f), AutoEllipsis = false, UseMnemonic = false,
        };
        _previewCard.Controls.Add(_preview);
        y += _previewCard.Height + 20;

        // Measured, never fixed: at larger Windows text a fixed 130px puts the words on the borders.
        var issueW = Math.Max(130, TextRenderer.MeasureText("Open GitHub issue", Font).Width + 32);
        var formW = Math.Max(130, TextRenderer.MeasureText("Open private form", Font).Width + 32);
        _issue = new HelperWindow.ReadableButton { Text = "Open GitHub issue", Width = issueW, Height = 32, FlatStyle = FlatStyle.Flat, Enabled = false };
        _form = new HelperWindow.ReadableButton { Text = "Open private form", Width = formW, Height = 32, FlatStyle = FlatStyle.Flat, Enabled = false };
        // Both actions together at the right, 8px apart; the orange one last.
        DialogLook.RowRight(clientW - m, y, _issue, _form);
        y += 32 + m;
        ClientSize = new Size(clientW, y);

        // The native scrollbar stays (all scrolling behavior intact) but is covered by a
        // dark strip that follows the theme: WinForms scrollbars can't be themed.
        _scroll = new DarkScrollStrip(_message);
        _messageFrame.Controls.Add(_scroll);
        _scroll.BringToFront();
        var scrollTimer = new System.Windows.Forms.Timer { Interval = 60 };
        scrollTimer.Tick += (_, _) => _scroll.SyncThumb();
        scrollTimer.Start();
        Disposed += (_, _) => { scrollTimer.Stop(); scrollTimer.Dispose(); };

        _message.TextChanged += (_, _) => RefreshPreview();
        _issue.Click += (_, _) => OpenUrl(IssueUrl());
        _form.Click += (_, _) => OpenUrl(FormUrl());

        Controls.Add(title);
        Controls.Add(_messageFrame);
        Controls.Add(whatHeader);
        Controls.Add(_previewCard);
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
        var border = dark ? HelperWindow.DarkBorder : HelperWindow.LightBorder;
        foreach (Control c in Controls) { c.ForeColor = text; }
        _messageFrame.BackColor = card;
        _messageFrame.BorderColor = border;
        _previewCard.BackColor = card;
        _previewCard.BorderColor = border;
        _message.BackColor = card;
        _message.ForeColor = text;
        _scroll.Track = dark ? Color.FromArgb(32, 34, 44) : Color.FromArgb(224, 224, 228);
        _scroll.Thumb = dark ? Color.FromArgb(74, 80, 96) : Color.FromArgb(180, 180, 190);
        _scroll.Arrows = muted;
        _scroll.Card = card;
        _scroll.Invalidate();
        _preview.ForeColor = text;
        _preview.BackColor = card;
        _issue.BackColor = cardBtn;
        _issue.ForeColor = text;
        _issue.FlatAppearance.BorderSize = 0;
        HelperWindow.StylePrimary(_form);
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
        // Long messages preview their start and keep the footer: the preview always shows
        // everything else that goes out.
        var shortMsg = msg.Length > 280 ? msg[..280] + "…" : msg;
        _preview.Text = shortMsg + "\n\nBingeMote PC version: " + Program.VersionText +
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

    /// <summary>A slim dark strip drawn over the message box's native scrollbar. The native
    /// bar keeps working underneath (wheel, keys, caret); this paints the theme look on top
    /// and forwards drags and page clicks to the textbox.</summary>
    private sealed class DarkScrollStrip : Control
    {
        internal Color Track, Thumb, Arrows, Card;
        private readonly TextBox _box;
        private int _first, _lines, _visible;
        private bool _dragging;
        private int _dragY, _dragFirst;

        internal DarkScrollStrip(TextBox box)
        {
            _box = box;
            SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint, true);
            var w = SystemInformation.VerticalScrollBarWidth;
            var edge = box.BorderStyle == BorderStyle.None ? 0 : 1; // a borderless box's bar sits flush
            SetBounds(box.Left + box.Width - w - edge, box.Top + edge, w, box.Height - 2 * edge);
            TabStop = false;
            SyncThumb();
        }

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern IntPtr SendMessage(IntPtr h, int m, IntPtr w, IntPtr l);

        internal void SyncThumb()
        {
            if (_box.IsDisposed || !_box.IsHandleCreated) return;
            var first = (int)SendMessage(_box.Handle, 0x00CE /* EM_GETFIRSTVISIBLELINE */, IntPtr.Zero, IntPtr.Zero);
            var lines = (int)SendMessage(_box.Handle, 0x00BA /* EM_GETLINECOUNT */, IntPtr.Zero, IntPtr.Zero);
            var lh = Math.Max(1, TextRenderer.MeasureText("Ag", _box.Font).Height);
            var visible = Math.Max(1, (_box.ClientSize.Height - 8) / lh);
            if (first != _first || lines != _lines || visible != _visible)
            {
                _first = first; _lines = lines; _visible = visible;
                Invalidate();
            }
        }

        private bool Needed => _lines > _visible;
        private int TrackH => Height - 24;
        private int ThumbH => Math.Max(18, TrackH * _visible / Math.Max(1, _lines));
        private int MaxFirst => Math.Max(1, _lines - _visible);
        private int ThumbY => 12 + (TrackH - ThumbH) * Math.Min(_first, MaxFirst) / MaxFirst;

        protected override void OnPaint(PaintEventArgs e)
        {
            // The strip blends into the box; the track and thumb are smooth slim pills.
            e.Graphics.Clear(Card);
            if (!Needed) return;
            var g = e.Graphics;
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
            // arrows
            using var ab = new SolidBrush(Arrows);
            var cx = Width / 2f;
            g.FillPolygon(ab, new[] { new PointF(cx, 3), new PointF(cx - 3.5f, 8), new PointF(cx + 3.5f, 8) });
            g.FillPolygon(ab, new[] { new PointF(cx, Height - 3), new PointF(cx - 3.5f, Height - 8), new PointF(cx + 3.5f, Height - 8) });
            const float pill = 6f;
            var px = (Width - pill) / 2f;
            using (var track = HelperWindow.RoundedRect(new RectangleF(px, 12, pill, TrackH), pill / 2))
            using (var trb = new SolidBrush(Track))
                g.FillPath(trb, track);
            using var thumb = HelperWindow.RoundedRect(new RectangleF(px, ThumbY, pill, ThumbH), pill / 2);
            using var tb = new SolidBrush(Thumb);
            g.FillPath(tb, thumb);
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            if (!Needed || e.Button != MouseButtons.Left) return;
            if (e.Y >= ThumbY && e.Y <= ThumbY + ThumbH)
            {
                _dragging = true;
                _dragY = e.Y;
                _dragFirst = _first;
                Capture = true;
            }
            else
            {
                SendMessage(_box.Handle, 0x00B5 /* EM_SCROLL */,
                    (IntPtr)(e.Y < ThumbY ? 2 /* SB_PAGEUP */ : 3 /* SB_PAGEDOWN */), IntPtr.Zero);
                SyncThumb();
            }
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            if (!_dragging) return;
            var target = _dragFirst + (e.Y - _dragY) * MaxFirst / Math.Max(1, TrackH - ThumbH);
            var delta = Math.Max(0, Math.Min(MaxFirst, target)) - _first;
            if (delta != 0)
            {
                SendMessage(_box.Handle, 0x00B6 /* EM_LINESCROLL */, IntPtr.Zero, (IntPtr)delta);
                SyncThumb();
            }
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            _dragging = false;
            Capture = false;
        }

        protected override void OnMouseWheel(MouseEventArgs e)
        {
            base.OnMouseWheel(e);
            SendMessage(_box.Handle, 0x00B6 /* EM_LINESCROLL */, IntPtr.Zero, (IntPtr)(-3 * Math.Sign(e.Delta)));
            SyncThumb();
        }
    }

    private string IssueUrl()
    {
        var msg = Clean(_message.Text, 4000);
        var title = "Feedback: " + (msg.Length <= 60 ? msg : msg.Substring(0, 57) + "...");
        // Trim the message, never the body: the version lines always survive a long report.
        string Body(string m) => m + "\n\nBingeMote PC version: " + Program.VersionText + "\nWindows version: " + OsVersion();
        var url = RepoIssues + "?title=" + Uri.EscapeDataString(title) + "&body=" + Uri.EscapeDataString(Body(msg));
        while (url.Length > 8000 && msg.Length > 280)
        {
            msg = Clean(msg, msg.Length / 2);
            url = RepoIssues + "?title=" + Uri.EscapeDataString(title) + "&body=" + Uri.EscapeDataString(Body(msg));
        }
        return url;
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
