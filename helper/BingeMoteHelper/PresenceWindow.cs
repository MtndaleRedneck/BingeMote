using System.Runtime.InteropServices;

namespace BingeMoteHelper;

/// <summary>
/// rev10 (test build): a 1x1 fully transparent, click-through, never-activated topmost tool
/// window that the helper owns for its whole lifetime, independent of the main/tray window.
/// Evidence on the owner's PC: injected input dies exactly when the helper owns no window
/// (closing the helper window kills the mouse on a bare desktop; a minimized helper window
/// keeps it alive; a real trackpad click revives it). Keeping one invisible window open at
/// all times keeps the process in the state where input works.
///
/// Runs on a dedicated background thread with its own GetMessage loop: an unpumped window
/// stalls system broadcasts for everyone. The window is created once and never shown,
/// activated, moved or resized after. Pure P/Invoke, no new dependency.
/// </summary>
internal static class PresenceWindow
{
    private const uint WS_POPUP = 0x80000000, WS_VISIBLE = 0x10000000;
    private const uint WS_EX_TOOLWINDOW = 0x00000080, WS_EX_TOPMOST = 0x00000008,
        WS_EX_NOACTIVATE = 0x08000000, WS_EX_LAYERED = 0x00080000, WS_EX_TRANSPARENT = 0x00000020;
    private const uint LWA_ALPHA = 0x00000002;

    private delegate IntPtr WndProcDelegate(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

    // Root the delegate for the process lifetime so the native class never calls freed code.
    private static readonly WndProcDelegate Proc = static (h, m, w, l) => DefWindowProc(h, m, w, l);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WNDCLASS
    {
        public uint style;
        public WndProcDelegate lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int x;
        public int y;
    }

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern ushort RegisterClass(ref WNDCLASS wc);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateWindowEx(uint exStyle, string className, string windowName,
        uint style, int x, int y, int width, int height,
        IntPtr parent, IntPtr menu, IntPtr instance, IntPtr param);

    [DllImport("user32.dll")] private static extern IntPtr DefWindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool SetLayeredWindowAttributes(IntPtr hwnd, uint colorKey, byte alpha, uint flags);
    [DllImport("user32.dll")] private static extern bool DestroyWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern int GetMessage(out MSG msg, IntPtr hwnd, uint minFilter, uint maxFilter);
    [DllImport("user32.dll")] private static extern bool TranslateMessage(ref MSG msg);
    [DllImport("user32.dll")] private static extern IntPtr DispatchMessage(ref MSG msg);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr GetModuleHandle(string? name);

    /// <summary>The window handle once created (IntPtr.Zero before, or on failure).</summary>
    internal static IntPtr Hwnd { get; private set; }

    /// <summary>Start the dedicated thread that creates and pumps the presence window.
    /// Never throws; a failure is logged to the local helper.log and the app runs without it.</summary>
    internal static void Start()
    {
        try
        {
            var thread = new Thread(Run) { IsBackground = true, Name = "BingeMotePresence" };
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
        }
        catch (Exception e)
        {
            DiagnosticLog.Write($"presence_window=FAILED exception={e.GetType().Name} (start_thread)");
        }
    }

    private static void Run()
    {
        try
        {
            var instance = GetModuleHandle(null);
            var wc = new WNDCLASS
            {
                lpfnWndProc = Proc,
                hInstance = instance,
                lpszClassName = "BingeMotePresence",
            };
            if (RegisterClass(ref wc) == 0)
            {
                var regErr = Marshal.GetLastWin32Error();
                if (regErr != 1410) // ERROR_CLASS_ALREADY_EXISTS: another copy registered it; still fine to create
                {
                    DiagnosticLog.Write($"presence_window=FAILED win32={regErr} (register_class)");
                    return;
                }
            }
            var hwnd = CreateWindowEx(
                WS_EX_TOOLWINDOW | WS_EX_TOPMOST | WS_EX_NOACTIVATE | WS_EX_LAYERED | WS_EX_TRANSPARENT,
                "BingeMotePresence", "BingeMotePresence",
                WS_POPUP | WS_VISIBLE,
                0, 0, 1, 1,
                IntPtr.Zero, IntPtr.Zero, instance, IntPtr.Zero);
            if (hwnd == IntPtr.Zero)
            {
                DiagnosticLog.Write($"presence_window=FAILED win32={Marshal.GetLastWin32Error()} (create)");
                return;
            }
            if (!SetLayeredWindowAttributes(hwnd, 0, 0, LWA_ALPHA))
            {
                // Without alpha 0 the 1x1 topmost dot could be visible; destroy rather than risk it.
                DiagnosticLog.Write($"presence_window=FAILED win32={Marshal.GetLastWin32Error()} (layered_alpha)");
                try { DestroyWindow(hwnd); } catch { }
                return;
            }
            Hwnd = hwnd;
            DiagnosticLog.Write($"presence_window=created hwnd=0x{hwnd.ToInt64():X}");
            while (GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
                TranslateMessage(ref msg);
                DispatchMessage(ref msg);
            }
        }
        catch (Exception e)
        {
            DiagnosticLog.Write($"presence_window=FAILED exception={e.GetType().Name} (window_thread)");
        }
    }
}
