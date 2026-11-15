using System.Globalization;

namespace BingeMoteHelper;

/// <summary>
/// rev12: server-side validation for broker ops (rev11 review blocker B5). Every op the
/// broker plays is parsed and bounded HERE, at the privileged boundary, not trusted from
/// the wire: non-finite numbers are rejected, magnitudes are clamped, text is length-bound,
/// and only the known verbs/buttons/keys pass. Pure and allocation-light so the unit tests
/// can hammer it without touching SendInput.
/// </summary>
internal static class OpValidator
{
    internal const float MaxMove = 500f;      // px per op, each axis
    internal const float MaxScroll = 1200f;   // wheel delta per op
    internal const float MaxZoom = 10f;       // pinch delta per op
    internal const int MaxText = 2000;        // chars after unescaping
    internal const int MaxKeyName = 16;

    internal sealed record Op(string Verb, float X, float Y, string Arg);

    internal static readonly HashSet<string> Buttons = new(StringComparer.Ordinal) { "left", "right" };
    internal static readonly HashSet<string> Keys = new(StringComparer.Ordinal)
        { "enter", "backspace", "escape", "space", "tab", "up", "down", "left", "right" };

    private static float Number(string s, float clamp)
    {
        if (!float.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var v))
            throw new ArgumentException("bad_number");
        if (float.IsNaN(v) || float.IsInfinity(v)) throw new ArgumentException("nonfinite");
        return Math.Clamp(v, -clamp, clamp);
    }

    /// <summary>Parses one wire line into a validated op. Throws ArgumentException on any violation.</summary>
    internal static Op Parse(string line)
    {
        var sp = line.IndexOf(' ');
        var verb = sp < 0 ? line : line[..sp];
        var rest = sp < 0 ? "" : line[(sp + 1)..];
        switch (verb)
        {
            case "m":
            {
                var parts = rest.Split(' ');
                if (parts.Length != 2) throw new ArgumentException("bad_move");
                return new Op("m", Number(parts[0], MaxMove), Number(parts[1], MaxMove), "");
            }
            case "b":
            {
                var parts = rest.Split(' ');
                if (parts.Length != 2 || !Buttons.Contains(parts[0]) || (parts[1] != "down" && parts[1] != "up"))
                    throw new ArgumentException("bad_button");
                return new Op("b", 0, parts[1] == "down" ? 1 : 0, parts[0]);
            }
            case "s": return new Op("s", Number(rest, MaxScroll), 0, "");
            case "z": return new Op("z", Number(rest, MaxZoom), 0, "");
            case "t":
            {
                var text = Uri.UnescapeDataString(rest);
                if (text.Length == 0 || text.Length > MaxText) throw new ArgumentException("bad_text");
                return new Op("t", 0, 0, text);
            }
            case "k":
                if (rest.Length == 0 || rest.Length > MaxKeyName || !Keys.Contains(rest)) throw new ArgumentException("bad_key");
                return new Op("k", 0, 0, rest);
            default: throw new ArgumentException("unknown_verb");
        }
    }
}

/// <summary>
/// rev12: per-connection op rate cap (rev11 review blocker B5). A sliding 1s window;
/// over the limit the broker rejects and drops the connection. Generous for a real
/// phone (move streams peak far below it), fatal for a flood.
/// </summary>
internal sealed class OpRateLimiter
{
    private readonly int _perSecond;
    private long _windowStartMs = -1;
    private int _count;

    internal OpRateLimiter(int perSecond) => _perSecond = perSecond;

    internal bool Allow() => Allow(Environment.TickCount64);

    internal bool Allow(long nowMs)
    {
        if (_windowStartMs < 0 || nowMs - _windowStartMs >= 1000)
        {
            _windowStartMs = nowMs;
            _count = 0;
        }
        return ++_count <= _perSecond;
    }
}
