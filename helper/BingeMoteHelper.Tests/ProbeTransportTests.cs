using System.Diagnostics;
using BingeMoteHelper;
using Xunit;

namespace BingeMoteHelper.Tests;

/// <summary>Always on, read-only: the probe's real command line must parse and run in Windows PowerShell on any machine.
/// (1.9.0's nested quotes failed with a parser error on every machine, so the strip never appeared.) Does not change anything.</summary>
public class ProbeTransportTests
{
    [Fact]
    public void ProbeCommandLineParsesAndRunsWithoutParserErrors()
    {
        Assert.DoesNotContain("\\\"$(", NetworkCheck.ProbeArguments);
        var psi = new ProcessStartInfo("powershell", NetworkCheck.ProbeArguments)
            { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true };
        using var p = Process.Start(psi)!;
        Assert.True(p.WaitForExit(30000), "probe hung");
        var err = p.StandardError.ReadToEnd();
        var outp = p.StandardOutput.ReadToEnd().Trim();
        Assert.DoesNotContain("ParserError", err);
        Assert.DoesNotContain("Expressions are only allowed", err);
        // Output is either empty (no network on this machine) or exactly name|category|index.
        if (outp.Length > 0) Assert.Equal(3, outp.Split('|').Length);
    }
}
