namespace BingeMoteHelper;

/// <summary>In-memory per HTTP phone or live connection button ownership. Only an
/// accepted down arms a release; that release never passes through the wake filter.</summary>
internal sealed class ButtonInputState
{
    private readonly object _gate = new();
    private readonly HashSet<string> _pressed = new();
    internal bool Apply(string button, bool down, Func<bool> swallowDown, Func<string, bool, bool> inject)
    {
        button = button == "right" ? "right" : "left";
        lock (_gate)
        {
            if (down)
            {
                if (swallowDown()) return false;
                if (!inject(button, true)) return false;
                _pressed.Add(button);
                return true;
            }
            if (!_pressed.Contains(button)) return false; // no unmatched up after a swallowed down
            if (!inject(button, false)) return false;
            _pressed.Remove(button);
            return true;
        }
    }

    internal void ReleaseAll(Func<string, bool, bool> inject)
    {
        lock (_gate)
        {
            foreach (var button in _pressed.ToArray())
            {
                try
                {
                    if (inject(button, false) || inject(button, false)) _pressed.Remove(button);
                    else DiagnosticLog.Write("Held-button cleanup failed after two release attempts.");
                }
                catch { /* cleanup must continue for other held buttons */ }
            }
        }
    }
}
