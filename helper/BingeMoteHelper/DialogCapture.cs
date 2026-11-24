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
        void Stage(string s) => File.AppendAllText(Path.Combine(directory, "dialog-progress.log"), $"{DateTime.UtcNow:HH:mm:ss.fff} {s}\n");
        // Runs 45363186746/45365558033/45386135883 hung to the outer 45s kill: a one-shot
        // timer tick that fires before the modal is ActiveForm records the failure but
        // closes nothing, so the modal pump never returns. Poll instead, and on a deadline
        // close whatever modal this capture opened so the run always ends.
        void CloseOwnedModals(Form owner0)
        {
            foreach (Form f in Application.OpenForms.Cast<Form>().ToArray())
                if (f != owner0 && f.Modal) { f.DialogResult = DialogResult.Cancel; f.Close(); }
        }
        Stage("run start");
        using var owner = new Form { Text = "Dialog test owner", Location = new Point(0, 0), StartPosition = FormStartPosition.Manual };
        owner.Show();
        Capture("confirm", () => ConfirmDialog.Ask(owner, "Remove Test phone? It stops controlling this PC right away. You can pair it again any time with a new code.", "Remove"));
        Capture("note", () => NoteDialog.Show(owner, "This Wi-Fi is now marked as Private."));
        Capture("wifi", () => { using var dlg = new WifiDialog(new NetProfile { Name = "Test home network", Category = "Public" }); dlg.ShowDialog(owner); });
        CheckKey("enter", "{ENTER}");
        CheckKey("escape", "{ESC}");
        owner.Close();
        Stage("run end");

        void CheckKey(string name, string key)
        {
            bool fired = false;
            Exception? failure = null;
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using var timer = new System.Windows.Forms.Timer { Interval = 100 };
            using var timeout = new System.Windows.Forms.Timer { Interval = 5000 };
            timer.Tick += (_, _) => {
                var dlg = Form.ActiveForm;
                if (dlg == null || dlg == owner)
                {
                    if (sw.Elapsed > TimeSpan.FromSeconds(10))
                    {
                        timer.Stop();
                        Stage(name + ": dialog not active within 10s; closing owned modals");
                        CloseOwnedModals(owner);
                    }
                    return;
                }
                timer.Stop();
                Stage(name + ": dialog active");
                if (dlg.ActiveControl != dlg.CancelButton)
                {
                    failure = new Exception("Initial focus is not Cancel");
                    Stage(name + ": initial focus is not Cancel; closing owned modals");
                    CloseOwnedModals(owner);
                    return;
                }
                fired = true;
                SendKeys.SendWait(key);
            };
            timeout.Tick += (_, _) => { timeout.Stop(); Form.ActiveForm?.Close(); };
            Stage(name + ": showing");
            timer.Start(); timeout.Start();
            bool removed = ConfirmDialog.Ask(owner, "Harmless test phone fixture. No phone is removed by this check.", "Remove");
            timeout.Stop();
            Stage(name + $": closed fired={fired} confirmed={removed}");
            File.WriteAllText(Path.Combine(directory, "dialog-key-" + name + ".txt"), $"key={name}; fired={fired}; confirmed={removed}");
            if (failure != null) throw failure;
            if (!fired || removed) throw new Exception("Initial " + name + " confirmed destructive action");
        }

        void Capture(string name, Action show)
        {
            Exception? failure = null;
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using var timer = new System.Windows.Forms.Timer { Interval = 250 };
            timer.Tick += (_, _) =>
            {
                var dlg = Form.ActiveForm;
                if (dlg == null || dlg == owner)
                {
                    if (sw.Elapsed > TimeSpan.FromSeconds(10))
                    {
                        timer.Stop();
                        failure = new Exception("Modal dialog not active within 10s");
                        Stage(name + ": dialog not active within 10s; closing owned modals");
                        CloseOwnedModals(owner);
                    }
                    return;
                }
                timer.Stop();
                Stage(name + ": dialog active");
                try
                {
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
                finally { dlg.DialogResult = DialogResult.Cancel; dlg.Close(); }
            };
            Stage(name + ": showing");
            timer.Start();
            show();
            Stage(name + ": closed");
            if (failure != null) throw failure;
        }
    }
}
#endif
