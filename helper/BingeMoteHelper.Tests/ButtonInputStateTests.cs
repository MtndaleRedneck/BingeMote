using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class ButtonInputStateTests
{
    [Fact]
    public void AcceptedDownAlwaysGetsReleaseEvenIfWakeFilterWouldNowSwallow()
    {
        var state = new ButtonInputState();
        var sent = new List<bool>();
        bool Inject(string button, bool down) { sent.Add(down); return true; }
        Assert.True(state.Apply("left", true, () => false, Inject));
        Assert.True(state.Apply("left", false, () => throw new Exception("up must bypass wake filter"), Inject));
        Assert.Equal(new[] { true, false }, sent);
    }

    [Fact]
    public void SwallowedOrRejectedDownDoesNotArmUnmatchedUp()
    {
        foreach (var swallowed in new[] { true, false })
        {
            var state = new ButtonInputState();
            Assert.False(state.Apply("left", true, () => swallowed, (_, _) => false));
            Assert.False(state.Apply("left", false, () => false, (_, _) => throw new Exception("unmatched up")));
        }
    }

    [Fact]
    public void FailedUpCanBeRetriedAndDisconnectReleasesHeldButtons()
    {
        var state = new ButtonInputState();
        Assert.True(state.Apply("right", true, () => false, (_, _) => true));
        Assert.False(state.Apply("right", false, () => true, (_, _) => false));
        var released = new List<string>();
        state.ReleaseAll((button, down) => { Assert.False(down); released.Add(button); return true; });
        Assert.Equal(new[] { "right" }, released);
        state.ReleaseAll((_, _) => throw new Exception("already released"));
    }

    [Fact]
    public void DifferentPhoneCannotReleaseAnotherPhonesDown()
    {
        var first = new ButtonInputState(); var second = new ButtonInputState();
        Assert.True(first.Apply("left", true, () => false, (_, _) => true));
        Assert.False(second.Apply("left", false, () => false, (_, _) => throw new Exception("different owner")));
    }
}
