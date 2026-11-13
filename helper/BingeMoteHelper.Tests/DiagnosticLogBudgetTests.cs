using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

public class DiagnosticLogBudgetTests
{
    [Fact]
    public void ChangingStateDoesNotBypassSixtyLineLimit()
    {
        var budget = new DiagnosticLogBudget();
        for (var i = 0; i < 60; i++) Assert.True(budget.TryTake(i));
        for (var i = 60; i < 10000; i++) Assert.False(budget.TryTake(i));
    }

    [Fact]
    public void RollingWindowDoesNotAllowDoubleBurstAtMinuteBoundary()
    {
        var budget = new DiagnosticLogBudget();
        for (var i = 0; i < 60; i++) Assert.True(budget.TryTake(59999));
        Assert.False(budget.TryTake(60000));
        Assert.False(budget.TryTake(119998));
        Assert.True(budget.TryTake(119999));
    }
}
