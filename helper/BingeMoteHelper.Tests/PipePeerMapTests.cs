using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

/// <summary>
/// rev18 (Instinct 2 HIGH): the strict NT-to-DOS mapping behind the handle-free peer
/// image check. The pre-rev18 prefix match invented "C:0\BingeMote.exe" from a peer at
/// \Device\HarddiskVolume10 when the system volume was HarddiskVolume1; these cases pin
/// the component boundary, rooted output, longest-first ordering and honest unmapped
/// rejection.
/// </summary>
public class PipePeerMapTests
{
    private static readonly IReadOnlyList<(string Letter, string Device)> Map = new[]
    {
        ("C:", @"\Device\HarddiskVolume1"),
        ("D:", @"\Device\HarddiskVolume4"),
    };

    [Fact]
    public void Maps_ordinary_peer_path()
    {
        Assert.True(PipePeer.TryMapNtPath(@"\Device\HarddiskVolume1\Users\ezzy\BingeMote.exe", Map, out var dos));
        Assert.Equal(@"C:\Users\ezzy\BingeMote.exe", dos);
    }

    [Fact]
    public void Volume10_is_not_a_Volume1_match()
    {
        // The Instinct 2 case: no component boundary meant Volume1 prefix-matched Volume10.
        Assert.False(PipePeer.TryMapNtPath(@"\Device\HarddiskVolume10\BingeMote.exe", Map, out _));
    }

    [Fact]
    public void Longest_device_wins_over_a_prefix_lookalike()
    {
        var tricky = new[] { ("E:", @"\Device\HarddiskVolume1"), ("F:", @"\Device\HarddiskVolume1Shadow") };
        Assert.True(PipePeer.TryMapNtPath(@"\Device\HarddiskVolume1Shadow\x\BingeMote.exe", tricky, out var dos));
        Assert.Equal(@"F:\x\BingeMote.exe", dos);
    }

    [Fact]
    public void Bare_volume_root_is_rejected()
    {
        Assert.False(PipePeer.TryMapNtPath(@"\Device\HarddiskVolume1", Map, out _));
        Assert.False(PipePeer.TryMapNtPath(@"\Device\HarddiskVolume1\", Map, out _));
    }

    [Fact]
    public void Output_is_always_rooted_never_drive_relative()
    {
        Assert.True(PipePeer.TryMapNtPath(@"\Device\HarddiskVolume4\media\movie.mkv", Map, out var dos));
        Assert.StartsWith(@"D:\", dos);
        Assert.DoesNotMatch(@"^D:[^\\]", dos); // never "D:media\..." (drive-relative)
    }

    [Fact]
    public void Unmapped_device_is_rejected_not_echoed()
    {
        Assert.False(PipePeer.TryMapNtPath(@"\Device\CdRom0\BingeMote.exe", Map, out _));
    }

    [Fact]
    public void Matching_is_case_insensitive_like_the_object_manager()
    {
        Assert.True(PipePeer.TryMapNtPath(@"\device\harddiskvolume1\BingeMote.exe", Map, out var dos));
        Assert.Equal(@"C:\BingeMote.exe", dos);
    }
}
