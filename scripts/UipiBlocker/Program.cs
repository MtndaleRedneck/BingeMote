using System.Diagnostics;
using System.Runtime.InteropServices;
using WinForms = System.Windows.Forms;

// CI-only: a SYSTEM-integrity window that holds the foreground, recreating the
// SwUSB condition from Ezzy's PC so the e2e can prove (a) plain SendInput from a
// lower integrity level is swallowed and (b) the BingeMote input broker is not.
internal static class BlockerProgram
{
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern IntPtr SetFocus(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("user32.dll")] private static extern bool SystemParametersInfo(uint uiAction, uint uiParam, IntPtr pvParam, uint fWinIni);

    private static void Main()
    {
        // Started as SYSTEM by Task Scheduler we land in session 0, whose desktop the
        // test harness cannot see; hop into the active console session first.
        if (SessionLauncher.RelaunchIntoActiveConsoleSession("", "blocker")) return;

        // Take the foreground lock off so SetForegroundWindow always wins.
        SystemParametersInfo(0x2001 /* SPI_SETFOREGROUNDLOCKTIMEOUT */, 0, IntPtr.Zero, 0);
        var t = new Thread(() =>
        {
            var f = new WinForms.Form
            {
                Text = "UipiBlocker",
                Width = 400,
                Height = 300,
                StartPosition = WinForms.FormStartPosition.Manual,
                Location = new System.Drawing.Point(100, 100),
                TopMost = true,
                FormBorderStyle = WinForms.FormBorderStyle.FixedToolWindow,
                ShowInTaskbar = false,
            };
            f.Show();
            var own = f.Handle;
            var timer = new WinForms.Timer { Interval = 200 };
            timer.Tick += (_, _) =>
            {
                var fg = GetForegroundWindow();
                if (fg == own) return;
                var fgThread = GetWindowThreadProcessId(fg, out _);
                var myThread = GetWindowThreadProcessId(own, out _);
                AttachThreadInput(myThread, fgThread, true);
                SetForegroundWindow(own);
                BringWindowToTop(own);
                SetFocus(own);
                AttachThreadInput(myThread, fgThread, false);
            };
            timer.Start();
            WinForms.Application.Run(f);
        });
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        var proc = Process.GetCurrentProcess();
        Console.WriteLine($"blocker=running pid={proc.Id} session={proc.SessionId}");
        Thread.Sleep(Timeout.Infinite);
    }
}
