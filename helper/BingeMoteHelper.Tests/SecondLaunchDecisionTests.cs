using BingeMoteHelper;
using Xunit;
using static BingeMoteHelper.Program;

namespace BingeMoteHelper.Tests;

// rev19 (Instinct 2): the second-launch state table as real unit tests. These
// replace the unsupported "unit-covered" claim: every row of the table is pinned.
public class SecondLaunchDecisionTests
{
    private const string Running = @"C:\Users\ezzy\AppData\Local\BingeMote\BingeMote.exe";
    private const string Self = @"C:\Users\ezzy\AppData\Local\BingeMote\BingeMote.exe";

    [Fact]
    public void SameExeIsShowOnly()
    {
        var a = DecideSecondLaunch(sameExe: true, samePathIdentityConflict: false, copyShadowed: false,
            runningPath: Running, selfPath: Self, mineVersion: "1.3.0", theirsVersion: "1.3.0",
            runningCopyHasQuitEvent: true, out var reason);
        Assert.Equal(SecondLaunchAction.ShowOnly, a);
        Assert.Contains("already running", reason);
    }

    [Fact]
    public void SamePathIdentityConflictIsShowOnly()
    {
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: true, copyShadowed: false,
            runningPath: Running, selfPath: Self, mineVersion: "1.3.0", theirsVersion: "1.3.0",
            runningCopyHasQuitEvent: true, out var reason);
        Assert.Equal(SecondLaunchAction.ShowOnly, a);
        Assert.Contains("file identity", reason);
    }

    [Fact]
    public void UnreadableShadowHolderIsShowOnly()
    {
        // The SYSTEM broker (or an elevated copy) shadows the mutex holder.
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: false, copyShadowed: true,
            runningPath: null, selfPath: Self, mineVersion: null, theirsVersion: null,
            runningCopyHasQuitEvent: true, out var reason);
        Assert.Equal(SecondLaunchAction.ShowOnly, a);
        Assert.Contains("could not be read", reason);
    }

    [Fact]
    public void UnidentifiedHolderIsShowOnly_NeverQuits()
    {
        // The rev19 fall-through bug: runningPath null, not shadowed -> used to reach
        // the cooperative quit. Unknown is show-only.
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: false, copyShadowed: false,
            runningPath: null, selfPath: Self, mineVersion: null, theirsVersion: null,
            runningCopyHasQuitEvent: true, out var reason);
        Assert.Equal(SecondLaunchAction.ShowOnly, a);
        Assert.Contains("could not be identified", reason);
    }

    [Fact]
    public void NullSelfPathIsShowOnly()
    {
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: false, copyShadowed: false,
            runningPath: Running, selfPath: null, mineVersion: null, theirsVersion: null,
            runningCopyHasQuitEvent: true, out _);
        Assert.Equal(SecondLaunchAction.ShowOnly, a);
    }

    [Fact]
    public void DowngradeIsShowOnly()
    {
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: false, copyShadowed: false,
            runningPath: Running, selfPath: Self, mineVersion: "1.2.0", theirsVersion: "1.3.0",
            runningCopyHasQuitEvent: true, out var reason);
        Assert.Equal(SecondLaunchAction.ShowOnly, a);
        Assert.Contains("older exe", reason);
    }

    [Fact]
    public void MissingVersionIsUnknown_ShowOnly()
    {
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: false, copyShadowed: false,
            runningPath: Running, selfPath: Self, mineVersion: null, theirsVersion: "1.3.0",
            runningCopyHasQuitEvent: true, out var reason);
        Assert.Equal(SecondLaunchAction.ShowOnly, a);
        Assert.Contains("unknown", reason);
    }

    [Fact]
    public void UnparseableVersionIsUnknown_ShowOnly()
    {
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: false, copyShadowed: false,
            runningPath: Running, selfPath: Self, mineVersion: "not-a-version", theirsVersion: "1.3.0",
            runningCopyHasQuitEvent: true, out _);
        Assert.Equal(SecondLaunchAction.ShowOnly, a);
    }

    [Fact]
    public void NewerCopyWithoutQuitEventAsksForManualQuit()
    {
        // Running copy predates the named events (1.0.0/1.0.1): nobody may quit it for him.
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: false, copyShadowed: false,
            runningPath: Running, selfPath: Self, mineVersion: "1.3.0", theirsVersion: "1.0.1",
            runningCopyHasQuitEvent: false, out _);
        Assert.Equal(SecondLaunchAction.AskManualQuit, a);
    }

    [Fact]
    public void NewerCopyWithQuitEventTakesOverCooperatively()
    {
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: false, copyShadowed: false,
            runningPath: Running, selfPath: Self, mineVersion: "1.3.0", theirsVersion: "1.2.0",
            runningCopyHasQuitEvent: true, out _);
        Assert.Equal(SecondLaunchAction.CooperativeQuitTakeover, a);
    }

    [Fact]
    public void EqualVersionDifferentPathTakesOverCooperatively()
    {
        var a = DecideSecondLaunch(sameExe: false, samePathIdentityConflict: false, copyShadowed: false,
            runningPath: Running, selfPath: @"C:\Users\ezzy\Downloads\BingeMote.exe",
            mineVersion: "1.3.0", theirsVersion: "1.3.0",
            runningCopyHasQuitEvent: true, out _);
        Assert.Equal(SecondLaunchAction.CooperativeQuitTakeover, a);
    }
}
