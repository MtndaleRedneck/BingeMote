using BingeMoteHelper;
using Xunit;
namespace BingeMoteHelper.Tests;
public class DiagnosticLogBudgetTests
{
    [Fact] public void MoveSamplesCannotStarveFaults()
    {
        var b = new DiagnosticLogBudget();
        for (int i=0;i<12;i++) Assert.True(b.TryTake(i));
        for (int i=12;i<10000;i++) Assert.False(b.TryTake(i));
        for (int i=0;i<48;i++) Assert.True(b.TryTake(10000+i, true));
        Assert.False(b.TryTake(11000,true));
    }
    [Fact] public void RollingWindowDoesNotAllowDoubleBurstAtBoundary()
    {
        var b = new DiagnosticLogBudget();
        for(int i=0;i<12;i++) Assert.True(b.TryTake(59999));
        Assert.False(b.TryTake(60000)); Assert.False(b.TryTake(119998));
        Assert.True(b.TryTake(119999));
    }
}
