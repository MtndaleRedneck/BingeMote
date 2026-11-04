using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

// CI-only probe: a magenta window parked exactly under the volume pop-up's panel rect
// (inflated 30px each side, 100%-DPI layout). At 3s it moves the cursor to its own
// center and synthesizes a click. If the form receives the click while the pop-up is
// showing, the pop-up let the click through (WS_EX_TRANSPARENT) - logged as CLICK.
// Exits at 10s either way. Layout constants mirror VolumeOsd's SPEC (300x64 panel,
// 48px above the screen bottom) and assume the runner's 96 DPI (asserted by the e2e).
internal static class Probe
{
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);

    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        var log = Environment.GetEnvironmentVariable("OSD_PROBE_LOG") ?? "osd-probe.log";
        var screen = Screen.PrimaryScreen!.Bounds;
        const int panelW = 300, panelH = 64, bottomGap = 48, pad = 30;
        var form = new Form
        {
            FormBorderStyle = FormBorderStyle.None,
            BackColor = Color.Magenta,
            StartPosition = FormStartPosition.Manual,
            Width = panelW + pad * 2,
            Height = panelH + pad * 2,
            Left = screen.X + (screen.Width - panelW) / 2 - pad,
            Top = screen.Bottom - bottomGap - panelH - pad,
        };
        form.MouseClick += (_, _) => File.AppendAllText(log, "CLICK" + Environment.NewLine);
        var click = new System.Windows.Forms.Timer { Interval = 3000 };
        click.Tick += (_, _) =>
        {
            click.Stop();
            File.AppendAllText(log, "SENT" + Environment.NewLine);
            SetCursorPos(form.Left + form.Width / 2, form.Top + form.Height / 2);
            mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero); // LEFTDOWN
            mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero); // LEFTUP
        };
        var quit = new System.Windows.Forms.Timer { Interval = 10000 };
        quit.Tick += (_, _) => { quit.Stop(); Application.Exit(); };
        click.Start();
        quit.Start();
        form.Show();
        Application.Run();
    }
}
