#if WINDOW_SHOT_HOOK
using System.Runtime.InteropServices;
namespace BingeMoteHelper;

// CI only. Exercise the production dialog constructors and native title bars.
internal static class DialogCapture
{
    [DllImport("user32.dll")] private static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr h, int a, out int v, int size);

    internal static void Run(string directory)
    {
        Directory.CreateDirectory(directory);
        using var owner = new Form { Text = "Dialog test owner", Location = new Point(0, 0), StartPosition = FormStartPosition.Manual };
        owner.Show();
        Capture("confirm", () => ConfirmDialog.Ask(owner, "Remove Test phone? It stops controlling this PC right away. You can pair it again any time with a new code.", "Remove"));
        Capture("note", () => NoteDialog.Show(owner, "This Wi-Fi is now marked as Private."));
        Capture("wifi", () => { using var dlg = new WifiDialog(new NetProfile { Name = "Test home network", Category = "Public" }); dlg.ShowDialog(owner); });
        CheckKey("enter", "{ENTER}");
        CheckKey("escape", "{ESC}");
        owner.Close();

        void CheckKey(string name, string key)
        {
            bool fired = false;
            bool timedOut = false;
            using var timer = new System.Windows.Forms.Timer { Interval = 700 };
            using var timeout = new System.Windows.Forms.Timer { Interval = 5000 };
            timer.Tick += (_, _) => {
                timer.Stop();
                var dlg = Form.ActiveForm;
                if (dlg == null || dlg == owner || dlg.ActiveControl != dlg.CancelButton)
                    throw new Exception("Initial focus is not Cancel");
                fired = true;
                SendKeys.SendWait(key);
            };
            timeout.Tick += (_, _) => { timedOut = true; timeout.Stop(); Form.ActiveForm?.Close(); };
            timer.Start(); timeout.Start();
            bool removed = ConfirmDialog.Ask(owner, "Harmless test phone fixture. No phone is removed by this check.", "Remove");
            timeout.Stop();
            File.WriteAllText(Path.Combine(directory, "dialog-key-" + name + ".txt"), $"key={name}; fired={fired}; timedOut={timedOut}; confirmed={removed}");
            if (!fired || timedOut || removed) throw new Exception("Initial " + name + " confirmed destructive action");
        }

        void Capture(string name, Action show)
        {
            Exception? failure = null;
            using var timer = new System.Windows.Forms.Timer { Interval = 1000 };
            timer.Tick += (_, _) =>
            {
                timer.Stop();
                var dlg = Form.ActiveForm;
                try
                {
                    if (dlg == null || dlg == owner) throw new Exception("Modal dialog not active");
                    int hr = DwmGetWindowAttribute(dlg.Handle, 20, out int dark, sizeof(int));
                    File.WriteAllText(Path.Combine(directory, "dialog-" + name + ".txt"), $"darkTitleBar={dark}; hresult={hr}; type={dlg.GetType().Name}; size={dlg.Size}");
                    if (hr != 0 || dark != 1) throw new Exception("Dark native title-bar attribute not applied");
                    if (name == "confirm" && (dlg.AcceptButton != dlg.CancelButton || dlg.ActiveControl != dlg.CancelButton))
                        throw new Exception("Destructive confirmation lost safe initial Cancel focus");
                    using var bitmap = new Bitmap(dlg.Width, dlg.Height);
                    using (var g = Graphics.FromImage(bitmap))
                    {
                        var dc = g.GetHdc();
                        bool ok = PrintWindow(dlg.Handle, dc, 2);
                        g.ReleaseHdc(dc);
                        if (!ok) throw new Exception("PrintWindow failed");
                    }
                    bitmap.Save(Path.Combine(directory, "dialog-" + name + ".png"));
                    using var screen = new Bitmap(dlg.Width, dlg.Height);
                    using (var g = Graphics.FromImage(screen)) g.CopyFromScreen(dlg.Left, dlg.Top, 0, 0, dlg.Size);
                    screen.Save(Path.Combine(directory, "dialog-" + name + "-screen.png"));
                }
                catch (Exception e) { failure = e; }
                finally { if (dlg != null && dlg != owner) { dlg.DialogResult = DialogResult.Cancel; dlg.Close(); } }
            };
            timer.Start();
            show();
            if (failure != null) throw failure;
        }
    }
}
#endif
