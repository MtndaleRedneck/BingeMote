using System.Text.Json;
using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

[Collection("ProgramState")] // touches VlcSetup process-global statics: serialize with every other ProgramState class
public class VlcSetupTests : IDisposable
{
    // Fresh temp fixture on ANY machine: VlcSetup honors VlcDirOverride for every read/write,
    // so the real %AppData%\\vlc\\vlcrc is never touched (previously this class wrote it for
    // real, with only a text backup for protection). Statics restored in Dispose.
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "bmh-vlcsetup-" + Guid.NewGuid().ToString("N"));
    private string VlcrcPath => Path.Combine(_dir, "vlc", "vlcrc");

    public VlcSetupTests()
    {
        Directory.CreateDirectory(Path.Combine(_dir, "vlc"));
        VlcSetup.VlcDirOverride = Path.Combine(_dir, "vlc");
        VlcSetup.ResetPendingForTests();
    }

    public void Dispose()
    {
        VlcSetup.VlcDirOverride = null;
        VlcSetup.ResetPendingForTests();
        try { Directory.Delete(_dir, true); } catch { }
    }

    private void WriteVlcrc(params string[] lines)
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
        Directory.CreateDirectory(Path.GetDirectoryName(VlcrcPath)!);
        if (File.Exists(VlcrcPath)) File.Delete(VlcrcPath);
        WriteVlcrc("http-password=pw-1");
        Assert.False(VlcSetup.Configured); // no interface, no bind: never a green attestation
    }
}
