using NAudio.CoreAudioApi;

namespace BingeMoteHelper;

/// <summary>Windows system volume and mute for the default output device, via Core Audio.</summary>
internal static class VolumeControl
{
    /// <summary>Windows has no default audio output device (headless PC, disabled device).</summary>
    internal sealed class NoAudioDeviceException : Exception
    {
        public NoAudioDeviceException(string message, Exception inner) : base(message, inner) { }
    }

    private static MMDevice DefaultDevice(MMDeviceEnumerator enumerator)
    {
        try
        {
            return enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        }
        catch (Exception ex)
        {
            throw new NoAudioDeviceException("Windows has no default audio output device.", ex);
        }
    }

    public static (int Volume, bool Mute) Get()
    {
        using var enumerator = new MMDeviceEnumerator();
        var device = DefaultDevice(enumerator);
        return ((int)Math.Round(device.AudioEndpointVolume.MasterVolumeLevelScalar * 100),
                device.AudioEndpointVolume.Mute);
    }

    public static void Set(int percent)
    {
        percent = Math.Clamp(percent, 0, 100);
        using var enumerator = new MMDeviceEnumerator();
        var device = DefaultDevice(enumerator);
        device.AudioEndpointVolume.MasterVolumeLevelScalar = percent / 100f;
    }

    public static void SetMute(bool mute)
    {
        using var enumerator = new MMDeviceEnumerator();
        var device = DefaultDevice(enumerator);
        device.AudioEndpointVolume.Mute = mute;
    }
}
