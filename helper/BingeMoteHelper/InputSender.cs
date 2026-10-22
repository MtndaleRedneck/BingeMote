using System.Runtime.InteropServices;

namespace BingeMoteHelper;

/// <summary>Plays mouse and keyboard input back into Windows with SendInput.</summary>
internal static class InputSender
{
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

    private static void Send(params INPUT[] inputs) => SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());

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

    public static void Move(float dx, float dy)
    {
        lock (Gate)
        {
            _fracX += dx; _fracY += dy;
            var ix = (int)Math.Round(_fracX); var iy = (int)Math.Round(_fracY);
            _fracX -= ix; _fracY -= iy;
            if (ix != 0 || iy != 0) Send(Mouse(MOUSEEVENTF_MOVE, ix, iy));
        }
    }

    public static void Button(string button, bool down)
    {
        var flags = button == "right"
            ? (down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP)
            : (down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP);
        lock (Gate) Send(Mouse(flags));
    }

    public static void Scroll(float dy)
    {
        // Windows wheel units: 120 per notch. Positive = up.
        lock (Gate) Send(Mouse(MOUSEEVENTF_WHEEL, data: (uint)(int)(dy * 8)));
    }

    public static void Zoom(float delta)
    {
        // Ctrl+wheel is what most apps treat as zoom.
        lock (Gate)
        {
            Send(KeyVk(VK_CONTROL, false));
            Send(Mouse(MOUSEEVENTF_WHEEL, data: (uint)(int)(delta * 40)));
            Send(KeyVk(VK_CONTROL, true));
        }
    }

    public static void TypeText(string text)
    {
        if (text.Length > 2000) text = text[..2000];
        lock (Gate)
        {
            foreach (var c in text)
            {
                if (char.IsHighSurrogate(c) || char.IsLowSurrogate(c)) continue; // plain text only
                Send(KeyChar(c, false), KeyChar(c, true));
            }
        }
    }

    public static void NamedKey(string key)
    {
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
