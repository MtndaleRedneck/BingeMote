using System.Text.Json;
using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class VlcSetupTests : IDisposable
{
    private static readonly string VlcrcPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "vlc", "vlcrc");
    private readonly string? _backup;

    public VlcSetupTests()
    {
        _backup = File.Exists(VlcrcPath) ? File.ReadAllText(VlcrcPath) : null;
    }

    public void Dispose()
    {
        if (_backup != null) File.WriteAllText(VlcrcPath, _backup);
        else if (File.Exists(VlcrcPath)) File.Delete(VlcrcPath);
    }

    private static void WriteVlcrc(params string[] lines)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(VlcrcPath)!);
        File.WriteAllLines(VlcrcPath, lines);
    }

    [Fact]
    public void UnchangedSettingsAreANoOpNotAPendingWrite()
    {
        WriteVlcrc("extraintf=http", "http-host=0.0.0.0", "http-password=pw-1");
        var r = VlcSetup.WriteVlcrc("pw-1");
        using var doc = JsonDocument.Parse(r);
        Assert.True(doc.RootElement.GetProperty("ok").GetBoolean());
        // A running VLC read exactly this vlcrc at startup, so nothing may be deferred:
        // a false pending would tell the owner a reopen is required when control works now.
        Assert.False(VlcSetup.Pending);
        Assert.Equal("pw-1", VlcSetup.ReadVlcrcValue("http-password"));
    }

    [Fact]
    public void ReusingThePasswordAlsoEnsuresInterfaceAndBind()
    {
        WriteVlcrc("http-password=pw-1"); // a vlcrc can carry a password with the interface off
        var r = VlcSetup.WriteVlcrc("pw-1");
        using var doc = JsonDocument.Parse(r);
        Assert.True(doc.RootElement.GetProperty("ok").GetBoolean());
        Assert.Equal("http", VlcSetup.ReadVlcrcValue("extraintf"));
        Assert.Equal("0.0.0.0", VlcSetup.ReadVlcrcValue("http-host"));
        Assert.False(VlcSetup.Pending);
        Assert.True(VlcSetup.Configured);
    }

    [Fact]
    public void PasswordAloneIsNotConfigured()
    {
        File.Delete(VlcrcPath);
        WriteVlcrc("http-password=pw-1");
        Assert.False(VlcSetup.Configured); // no interface, no bind: never a green attestation
    }
}
