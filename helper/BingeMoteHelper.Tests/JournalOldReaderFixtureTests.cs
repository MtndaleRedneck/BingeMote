using System.Text.Json;
using Xunit;
using BingeMoteHelper;

namespace BingeMoteHelper.Tests;

// PROOF BRANCH ONLY - NOT FOR MERGE. Isolated reader fixture for the journal
// old-reader proof: proves the planted UrlAclMigrate journal is schema-valid
// and that the ACTUAL TlsJournalStore reader maps a known Kind to Present and
// an unknown Kind to Corrupt - a Kind decision, not a parse failure. Direct
// reader calls on temp files only: no setup path, no recovery, no mutation.
public class JournalOldReaderFixtureTests
{
    private static string WriteTemp(string content)
    {
        var path = Path.Combine(Path.GetTempPath(), "bingemote-journal-fixture-" + Guid.NewGuid().ToString("N") + ".json");
        File.WriteAllText(path, content);
        return path;
    }

    [Fact]
    public void KnownKind_ReadsPresent()
    {
        var j = new TlsJournal("Fresh", null, null, false, false, false, false, false, "2026-11-28T00:00:00Z");
        var path = WriteTemp(JsonSerializer.Serialize(j));
        try
        {
            var (state, back) = TlsJournalStore.Read(path);
            Assert.Equal(MarkerState.Present, state);
            Assert.NotNull(back);
            Assert.Equal("Fresh", back!.Kind);
        }
        finally { File.Delete(path); }
    }

    [Fact]
    public void UnknownFutureKind_ReadsCorrupt()
    {
        var j = new TlsJournal("UrlAclMigrate", null, null, false, false, false, false, false, "2026-11-28T00:00:00Z");
        var path = WriteTemp(JsonSerializer.Serialize(j));
        try
        {
            var (state, back) = TlsJournalStore.Read(path);
            Assert.Equal(MarkerState.Corrupt, state);
            Assert.Null(back);
        }
        finally { File.Delete(path); }
    }

    [Fact]
    public void Malformed_ReadsCorrupt()
    {
        var path = WriteTemp("{ not json");
        try
        {
            var (state, _) = TlsJournalStore.Read(path);
            Assert.Equal(MarkerState.Corrupt, state);
        }
        finally { File.Delete(path); }
    }
}
