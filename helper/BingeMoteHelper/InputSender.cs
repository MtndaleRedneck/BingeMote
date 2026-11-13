using System.Runtime.InteropServices;

namespace BingeMoteHelper;

/// <summary>Plays mouse and keyboard input back into Windows with SendInput.</summary>
internal static class InputSender
{
    // rev11: when false (broker mode), ops play locally and never route back over the pipe.
    internal static bool BrokerRoutingEnabled = true;

    private static string F(float v) => v.ToString("R", System.Globalization.CultureInfo.InvariantCulture);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern bool SetProcessDPIAware();

    private const uint INPUT_MOUSE = 0;
    private const uint INPUT_KEYBOARD = 1;
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint KEYEVENTF_UNICODE = 0x0004;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const ushort VK_CONTROL = 0x11;
    private const ushort VK_RETURN = 0x0D;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT { public uint type; public INPUTUNION U; }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    // Two phones can tap at the same instant; keep one gesture's SendInput calls together.
    private static readonly object Gate = new();

    private static bool Send(params INPUT[] inputs)
    {
        Marshal.SetLastPInvokeError(0);
        var sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        var err = Marshal.GetLastPInvokeError();
        SendInputTracker.Record((uint)inputs.Length, sent, err, DiagnosticLog.Write);
        return sent == (uint)inputs.Length;
    }

    private static INPUT Mouse(uint flags, int dx = 0, int dy = 0, uint data = 0) =>
        new() { type = INPUT_MOUSE, U = new INPUTUNION { mi = new MOUSEINPUT { dx = dx, dy = dy, dwFlags = flags, mouseData = data } } };

    private static INPUT KeyVk(ushort vk, bool up) =>
        new() { type = INPUT_KEYBOARD, U = new INPUTUNION { ki = new KEYBDINPUT { wVk = vk, dwFlags = up ? KEYEVENTF_KEYUP : 0 } } };

    private static INPUT KeyChar(char c, bool up) =>
        new() { type = INPUT_KEYBOARD, U = new INPUTUNION { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0) } } };

    // Sub-pixel remainder: rounding each batch on its own threw the fraction away,
    // so slow moves at 1x speed could round down to nothing. Carry the fraction
    // into the next batch instead.
    private static float _fracX, _fracY;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetCursorPos(int x, int y);

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out POINT p);

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int x, y; }

    // State-change logging for the experiment, like SendInputTracker but for one bool.
    private static bool _setPosFailing;

    // rev9 EXPERIMENT (test build only): move the cursor with SetCursorPos instead of an
    // injected relative SendInput move. On the owner's machine, injected moves stop
    // reaching the cursor whenever no app window exists (bare desktop, helper in tray):
    // SendInput reports success but the position never changes. SetCursorPos sets the
    // position directly - no input events, no foreground or UIPI involvement - so it
    // cannot be gated by which windows exist. GetCursorPos and SetCursorPos share this
    // process's coordinate space (DPI-virtualized the same way), so the pair is
    // self-consistent. Buttons, wheel and keys stay on SendInput. Known trade-off if
    // this ships: no WM_MOUSEMOVE is generated, so hover effects (tooltips, hover
    // highlights) only appear when real input arrives from another device.
    public static void Move(float dx, float dy)
    {
        if (InputBrokerClient.TryRun("m " + F(dx) + " " + F(dy))) return;
        lock (Gate)
        {
            _fracX += dx; _fracY += dy;
            var ix = (int)Math.Round(_fracX); var iy = (int)Math.Round(_fracY);
            _fracX -= ix; _fracY -= iy;
            if (ix == 0 && iy == 0) return;
            if (!GetCursorPos(out var p)) return;
            Marshal.SetLastPInvokeError(0);
            var ok = SetCursorPos(p.x + ix, p.y + iy);
            var err = Marshal.GetLastPInvokeError();
            // Log only on state change, through the bounded writer.
            if (!ok && !_setPosFailing)
            {
                _setPosFailing = true;
                DiagnosticLog.Write($"SetCursorPos FAILED (win32={err}); a clip or a secure desktop can do this.");
            }
            else if (ok && _setPosFailing)
            {
                _setPosFailing = false;
                DiagnosticLog.Write("SetCursorPos recovered.");
            }
        }
    }

    public static bool Button(string button, bool down)
    {
        if (InputBrokerClient.TryRun("b " + button + (down ? " down" : " up"))) return true;
        var flags = button == "right"
            ? (down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP)
            : (down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP);
        lock (Gate) return Send(Mouse(flags));
    }

    // Slow scrolls and pinches lost their leftover fraction when each request rounded on
    // its own; carry it into the next request, like Move does.
    private static float _fracWheel, _fracZoom;

    public static void Scroll(float dy)
    {
        if (InputBrokerClient.TryRun("s " + F(dy))) return;
        // Windows wheel units: 120 per notch. Positive = up.
        lock (Gate)
        {
            _fracWheel += dy * 8;
            var w = (int)MathF.Truncate(_fracWheel);
            _fracWheel -= w;
            if (w != 0) Send(Mouse(MOUSEEVENTF_WHEEL, data: (uint)w));
        }
    }

    public static void Zoom(float delta)
    {
        if (InputBrokerClient.TryRun("z " + F(delta))) return;
        // Ctrl+wheel is what most apps treat as zoom.
        lock (Gate)
        {
            _fracZoom += delta * 40;
            var w = (int)MathF.Truncate(_fracZoom);
            _fracZoom -= w;
            if (w == 0) return;
            Send(KeyVk(VK_CONTROL, false));
            Send(Mouse(MOUSEEVENTF_WHEEL, data: (uint)w));
            Send(KeyVk(VK_CONTROL, true));
        }
    }

    public static void TypeText(string text)
    {
        if (text.Length > 2000) text = text[..2000];
        if (InputBrokerClient.TryRun("t " + Uri.EscapeDataString(text))) return;
        lock (Gate)
        {
            foreach (var c in text)
            {
                if (c == '\r') continue;
                if (c == '\n') { Send(KeyVk(VK_RETURN, false), KeyVk(VK_RETURN, true)); continue; } // Enter submits; a line break helps no search box
                if (char.IsHighSurrogate(c) || char.IsLowSurrogate(c)) continue; // plain text only
                Send(KeyChar(c, false), KeyChar(c, true));
            }
        }
    }

    public static void NamedKey(string key)
    {
        if (InputBrokerClient.TryRun("k " + key)) return;
        var vk = key switch
        {
            "enter" => (ushort)0x0D,
            "backspace" => (ushort)0x08,
            "escape" => (ushort)0x1B,
            "space" => (ushort)0x20,
            "tab" => (ushort)0x09,
            "up" => (ushort)0x26,
            "down" => (ushort)0x28,
            "left" => (ushort)0x25,
            "right" => (ushort)0x27,
            _ => (ushort)0,
        };
        if (vk == 0) return;
        lock (Gate) Send(KeyVk(vk, false), KeyVk(vk, true));
    }
}
