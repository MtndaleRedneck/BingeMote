using NAudio.CoreAudioApi;

namespace BingeMoteHelper;

/// <summary>Windows system volume and mute for the default output device, via Core Audio.</summary>
internal static class VolumeControl
{
    public static (int Volume, bool Mute) Get()
    {
        using var enumerator = new MMDeviceEnumerator();
        var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        return ((int)Math.Round(device.AudioEndpointVolume.MasterVolumeLevelScalar * 100),
                device.AudioEndpointVolume.Mute);
    }

    public static void Set(int percent)
    {
        percent = Math.Clamp(percent, 0, 100);
        using var enumerator = new MMDeviceEnumerator();
        var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        device.AudioEndpointVolume.MasterVolumeLevelScalar = percent / 100f;
    }

    public static void SetMute(bool mute)
    {
        using var enumerator = new MMDeviceEnumerator();
        var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        device.AudioEndpointVolume.Mute = mute;
    }
}
