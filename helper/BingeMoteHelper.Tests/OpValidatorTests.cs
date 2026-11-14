using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

/// <summary>rev12: broker op validation at the privileged boundary (review blocker B5).</summary>
public class OpValidatorTests
{
    [Fact]
    public void Valid_ops_parse()
    {
        Assert.Equal("m", OpValidator.Parse("m 12.5 -7").Verb);
        var b = OpValidator.Parse("b right down");
        Assert.Equal("right", b.Arg);
        Assert.Equal(1, b.Y);
        Assert.Equal("s", OpValidator.Parse("s -120").Verb);
        Assert.Equal("z", OpValidator.Parse("z 0.4").Verb);
        Assert.Equal("hello world", OpValidator.Parse("t hello%20world").Arg);
        Assert.Equal("enter", OpValidator.Parse("k enter").Arg);
    }

    [Theory]
    [InlineData("m NaN 0")]
    [InlineData("m 0 NaN")]
    [InlineData("m Infinity 0")]
    [InlineData("m -Infinity 0")]
    [InlineData("s Infinity")]
    [InlineData("z NaN")]
    public void Nonfinite_numbers_are_rejected(string line) =>
        Assert.Throws<ArgumentException>(() => OpValidator.Parse(line));

    [Fact]
    public void Out_of_range_numbers_are_clamped_not_played_raw()
    {
        var m = OpValidator.Parse("m 99999 -99999");
        Assert.Equal(OpValidator.MaxMove, m.X);
        Assert.Equal(-OpValidator.MaxMove, m.Y);
        Assert.Equal(OpValidator.MaxScroll, OpValidator.Parse("s 1e9").X);
        Assert.Equal(OpValidator.MaxZoom, OpValidator.Parse("z 1e9").X);
    }

    [Theory]
    [InlineData("m 1")]            // missing axis
    [InlineData("m 1 2 3")]        // extra axis
    [InlineData("m x y")]          // not numbers
    [InlineData("b middle down")]  // unknown button
    [InlineData("b left")]         // missing state
    [InlineData("b left sideways")]// unknown state
    [InlineData("k f13")]          // key outside the whitelist
    [InlineData("k ")]             // empty key
    [InlineData("t ")]             // empty text
    [InlineData("x 1 2")]          // unknown verb
    [InlineData("")]               // empty line
    public void Malformed_ops_are_rejected(string line) =>
        Assert.Throws<ArgumentException>(() => OpValidator.Parse(line));

    [Fact]
    public void Text_is_length_bound()
    {
        var atLimit = new string('a', OpValidator.MaxText);
        Assert.Equal(atLimit, OpValidator.Parse("t " + atLimit).Arg);
        Assert.Throws<ArgumentException>(() => OpValidator.Parse("t " + new string('a', OpValidator.MaxText + 1)));
    }
}

/// <summary>rev12: per-connection rate cap (review blocker B5).</summary>
public class OpRateLimiterTests
{
    [Fact]
    public void Up_to_the_limit_passes_then_the_connection_is_cut()
    {
        var rl = new OpRateLimiter(10);
        for (var i = 0; i < 10; i++) Assert.True(rl.Allow(1000), $"op {i} should pass");
        Assert.False(rl.Allow(1000));
        Assert.False(rl.Allow(1999)); // same window
    }

    [Fact]
    public void The_window_slides()
    {
        var rl = new OpRateLimiter(2);
        Assert.True(rl.Allow(0));
        Assert.True(rl.Allow(500));
        Assert.False(rl.Allow(999));
        Assert.True(rl.Allow(1000)); // new window
        Assert.True(rl.Allow(1001));
        Assert.False(rl.Allow(1999));
    }
}
