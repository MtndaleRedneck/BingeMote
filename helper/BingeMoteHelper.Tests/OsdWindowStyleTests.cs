using System.Reflection;
using Xunit;

namespace BingeMoteHelper.Tests;

/// <summary>
/// The volume OSD's window-style and spec constants, proven by reflection against the
/// real private OsdWindow class (no CI hook, no product change). This pins the
/// click-through/no-focus/no-alt-tab contract and the accepted SPEC numbers at the
/// code level. What it cannot prove: real desktop compositing over fullscreen VLC,
/// real focus behavior with an actual foreground app, or the visual fade - those need
/// a real machine with an audio device.
/// </summary>
public class OsdWindowStyleTests
{
    [Fact]
    public void OsdWindowStylesAndSpecConstants()
    {
        System.Exception? failure = null;
        var thread = new System.Threading.Thread(() =>
        {
            object? w = null;
            try
            {
                var nt = typeof(VolumeOsd).GetNestedType("OsdWindow", BindingFlags.NonPublic)
                    ?? throw new System.Exception("OsdWindow nested type not found");
                w = System.Activator.CreateInstance(nt, nonPublic: true)
                    ?? throw new System.Exception("OsdWindow construction failed");

                var cp = nt.GetProperty("CreateParams", BindingFlags.Instance | BindingFlags.NonPublic)!
                    .GetValue(w)!;
                int ex = (int)cp.GetType().GetProperty("ExStyle")!.GetValue(cp)!;
                Assert.True((ex & 0x80000) != 0, "WS_EX_LAYERED must be set");
                Assert.True((ex & 0x20) != 0, "WS_EX_TRANSPARENT (click-through) must be set");
                Assert.True((ex & 0x80) != 0, "WS_EX_TOOLWINDOW (no taskbar/alt-tab entry) must be set");
                Assert.True((ex & 0x08000000) != 0, "WS_EX_NOACTIVATE (never steals focus) must be set");

                // Topmost is pinned at the property contract, not the style bit: the bit
                // is absent from CreateParams on .NET 8 even with TopMost=true (run 41845334304).
                var form = (System.Windows.Forms.Form)w;
                Assert.True(form.TopMost, "TopMost (above fullscreen video) must be set");
                Assert.False(form.ShowInTaskbar, "no taskbar entry");
                Assert.Equal(System.Windows.Forms.FormBorderStyle.None, form.FormBorderStyle);

                // The accepted SPEC numbers and the mute/unmute-same-1.5s contract.
                var hide = nt.GetField("_hideTimer", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(w)!;
                Assert.Equal(1500, (int)hide.GetType().GetProperty("Interval")!.GetValue(hide)!);
                var fade = nt.GetField("_fadeTimer", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(w)!;
                Assert.Equal(15, (int)fade.GetType().GetProperty("Interval")!.GetValue(fade)!);
                Assert.Equal(300, (int)nt.GetField("DesignW", BindingFlags.Static | BindingFlags.NonPublic)!.GetValue(null)!);
                Assert.Equal(64, (int)nt.GetField("DesignH", BindingFlags.Static | BindingFlags.NonPublic)!.GetValue(null)!);
                Assert.Equal(242, (int)nt.GetField("PanelAlpha", BindingFlags.Static | BindingFlags.NonPublic)!.GetValue(null)!);
            }
            catch (System.Exception e) { failure = e; }
            finally { (w as System.IDisposable)?.Dispose(); }
        });
        thread.SetApartmentState(System.Threading.ApartmentState.STA); // WinForms wants STA
        thread.Start();
        thread.Join();
        if (failure != null) throw failure;
    }
}
