// VLC's web remote, configured for him: the phone's Finish step hands the chosen password
// to the PC, and the PC writes extraintf=http + http-password into vlcrc, so a VLC opened
// by hand has the remote too. Written only while VLC is closed - a running VLC rewrites
// vlcrc on exit and would lose the change.
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;

namespace BingeMoteHelper;

internal static class VlcSetup
{
    /// <summary>I2 companion ruling (atomic internal serialization): every owed-pending
    /// mutation (stage/clear/restore) commits under ConfigSaveLock, so the write holds the SAME
    /// lock across obligation-guard -> stage/write -> verify -> obligation-clear: NO internal
    /// pending mutation can land between the guard and the commit (a pre-check + mutation
    /// window was explicitly rejected). Lock graph unchanged: ConfigSaveLock -> VlcSetup.Gate
    /// (the order MutateAndPersist already uses; Monitor re-entrancy covers the pair/adopt
    /// paths that call in already holding ConfigSaveLock; no Gate -> ConfigSaveLock acquisition
    /// exists anywhere). NAMED UNRESOLVED COST (observed, not accepted or permitted by
    /// anyone): vlcrc disk IO and the VLC process check now serialize with config saves
    /// (responsiveness, not correctness). The EXTERNAL race
    /// (VLC itself rewriting vlcrc) is unchanged and still covered by the E5.6 guard below.
    /// </summary>
    /// <summary>Explicit phone-facing write (the /vlc/setup route): the strict planning read
    /// IS this request's consent baseline, and an unresolvable owed record can be resolved by
    /// the explicit request (I2 N1). Autonomous/internal applies use WriteVlcrcInternal.
    /// Test hook (null in production): BeforePlanningReadForTests lands an external change
    /// inside the decision-to-planning-read window.</summary>
    public static string WriteVlcrc(string password)
    {
        lock (Program.ConfigSaveLock) { return WriteVlcrcUnderConfigLock(password, resolveUnresolved: true, expectedBaseline: null, hasExpectedBaseline: false); }
    }

    /// <summary>Autonomous/internal apply (retry timer, restart restore, R3 apply, host
    /// repair): NO resolution intent (an incidental internal caller never clears an
    /// obligation), and the DECISION's expected baseline is carried into the write (I2
    /// baseline window) - a change between the decision and the planning read aborts into a
    /// re-decide instead of writing over the discovered state.</summary>
    internal static string WriteVlcrcInternal(string password, string? expectedBaseline)
    {
        lock (Program.ConfigSaveLock) { return WriteVlcrcUnderConfigLock(password, resolveUnresolved: false, expectedBaseline, hasExpectedBaseline: true); }
    }

    private static string WriteVlcrcUnderConfigLock(string password, bool resolveUnresolved, string? expectedBaseline, bool hasExpectedBaseline)
    {
        if (password.Length == 0 || password.Length > 200 || password.Contains('\r') || password.Contains('\n'))
            return JsonSerializer.Serialize(new { ok = false, reason = "bad password" });
        Directory.CreateDirectory(VlcDir);
        var path = Path.Combine(VlcDir, "vlcrc");
        BeforePlanningReadForTests?.Invoke();
        // E5.1: STRICT merge read of the current vlcrc (I2 D4): null is PROVEN absent
        // (FileNotFound/DirectoryNotFound only) - never File.Exists, which reports false for a
        // file it merely cannot reach and would launder "inaccessible" into a certain-absent
        // staged-over baseline (a later decision would stale-clear the owed write over the file
        // that was really there). Unreadable = the whole write refuses (the route maps 503).
        List<string>? before = ReadVlcrcLinesStrict();
        // I2Wip10/D2: the staged-over baseline for any pending THIS write stages comes from
        // THIS strict planning read (proven certain by construction), never a later re-read.
        var plannedOver = before == null ? null : FindValue(before, "http-password");
        // I2 baseline window: an autonomous apply carries the DECISION's expected baseline -
        // if the world changed since (an external write landed in between), never write over
        // it: re-decide on the discovered state exactly like the E5.6 abort.
        if (hasExpectedBaseline && plannedOver != expectedBaseline)
        {
            Program.Log("vlcrc changed between the decision and the write - re-deciding instead of writing over it.");
            return ServeDiscoveredAfterChange(password);
        }
        // E5.2: merge, don't overwrite - another interface may already sit in extraintf.
        var lines = before == null ? new List<string>() : new List<string>(before);
        Upsert(lines, "extraintf", MergeValue(FindValue(lines, "extraintf"), "http"));
        Upsert(lines, "http-password", password);
        // Listen on every address (not just localhost) so a hand-opened VLC takes the phone
        // too; the password and the vlc.exe firewall rules (Private; Public subnet-only) guard it.
        Upsert(lines, "http-host", "0.0.0.0");
        // E5.3: the merged state is already on disk - no write and no deferral (a running VLC
        // read exactly this vlcrc at startup, so control already works now). Any pending value
        // is cleared as ONE save unit (E2), never by a separate save.
        if (LinesEqual(lines, before))
        {
            // I2Wip10: even a no-op ATTESTS state - verify it against a strict re-read at the
            // attestation point first. A concurrent change since the merge read is discovered
            // and re-decided by the caller, never attested stale (same identity contract as E5.6).
            BeforeReplaceGuardForTests?.Invoke();
            List<string>? currentNow = ReadVlcrcLinesStrict(); // strict (I2 D4): unreadable refuses, never reads as absent
            if (!LinesEqual(currentNow, before))
            {
                Program.Log("vlcrc changed while the existing setup was being verified - re-checking instead of attesting a stale state.");
                return JsonSerializer.Serialize(new { ok = false, reason = "changed" });
            }
            // I2 companion: clear only the obligation THIS value satisfies - an unrelated owed
            // pending is never cleared by another value's no-op.
            string? toClear; lock (Gate) { toClear = _pendingPassword == password ? _pendingPassword : null; }
            if (toClear != null) ClearPending(toClear);
            // I2 N1: explicit intent + the requested value VERIFIED in effect (the strict
            // re-read above): an unresolvable owed record (uncertain baseline, different from
            // the verified current) is cleared now - after the verification, never before a
            // commit. Internal/incidental callers (resolveUnresolved false) never trigger it.
            if (resolveUnresolved)
            {
                string? unres; lock (Gate) { unres = _pendingPassword != null && !_pendingSuperseded && _pendingStagedOverUncertain && _pendingPassword != password ? _pendingPassword : null; }
                if (unres != null)
                {
                    Program.Log("Clearing the unresolvable deferred vlcrc write - the requested value is verified in effect.");
                    // I2: the clear's persist result is never ignored - if it cannot save,
                    // the record stands over the verified current (a coherent unresolved
                    // state the next explicit request resolves); the failure is observable.
                    if (!ClearPending(unres))
                        Program.Log("pending vlc password: the replaced unresolved record could not be cleared durably - it stands over the verified current; the next explicit request resolves it.");
                }
            }
            Program.Log("vlcrc already has the web remote set up - nothing to write.");
            return JsonSerializer.Serialize(new { ok = true });
        }
        // I2 companion ruling: a DIFFERENT live owed pending is an existing obligation held by
        // phone(s) - this write must not silently replace it or make it disappear, BEFORE any
        // stage or write. Retryable busy (ok:false over 200, never 503: not a PC-unavailable
        // signal), state unchanged. Same value (or none) proceeds.
        // I2 N1: an UNRESOLVABLE standing pending (uncertain baseline + this strict planning
        // read proves a DIFFERENT current) never blocks an EXPLICIT request - but the old
        // record is NOT cleared up front: the replacement commits atomically (defer paths: one
        // save unit via StagePendingReplacingUnresolved) or the old record is cleared only
        // AFTER the new value is verified in effect (direct write). No crash window loses both.
        string? replacingUnresolved = null;
        lock (Gate)
        {
            if (_pendingPassword != null && !_pendingSuperseded && _pendingPassword != password)
            {
                if (!(resolveUnresolved && _pendingStagedOverUncertain && plannedOver != null && plannedOver != _pendingPassword))
                    return JsonSerializer.Serialize(new { ok = false, reason = "pending-busy" });
                replacingUnresolved = _pendingPassword;
            }
        }
        // I2 same-value explicit consent: an explicit request for the OWED value itself, with
        // an uncertain baseline and a proven different current, re-baselines on this request's
        // proven planning read instead of converging on the unprovable one - the requester is
        // present and the read is strict. (VLC closed: the direct write applies and the
        // matching clear resolves; VLC running: the defer stage re-baselines, see below.)
        var explicitRebaseline = false;
        lock (Gate) { explicitRebaseline = resolveUnresolved && _pendingPassword == password && !_pendingSuperseded && _pendingStagedOverUncertain && plannedOver != null && plannedOver != password; }
        // E5.4: VLC running - stage the deferred write as ONE save unit (E2/E8); the retry
        // timer applies it when VLC closes.
        if (VlcRunning())
        {
            var deferred = replacingUnresolved != null
                ? StagePendingReplacingUnresolved(password, plannedOver, true, replacingUnresolved) // ONE save unit: the old record is restored whole on persist failure (I2 N1)
                : StagePendingGuarded(password, plannedOver, true, explicitRebaseline);
            if (deferred == "busy")
                return JsonSerializer.Serialize(new { ok = false, reason = "pending-busy" });
            if (deferred == "failed")
            {
                // Truthful: VLC is running AND nothing was durably staged - NOT a deferral.
                Program.Log("VLC is running and the deferred write could not be persisted - nothing is owed; the caller re-decides.");
                return JsonSerializer.Serialize(new { ok = false, reason = "stage-failed" });
            }
            Program.Log("vlcrc write skipped - VLC is running (it rewrites vlcrc on exit). Will retry when VLC closes.");
            return JsonSerializer.Serialize(new { ok = false, reason = "vlc running" });
        }
        // E5.5: write the merged lines to a temp file in the SAME directory, flushed to disk.
        // F11: the temp is UNIQUE to this call - concurrent writers (e.g. /vlc/setup and the
        // retry timer) never share one, and every cleanup below only ever deletes our own.
        // NAMED LIMITATIONS: the temp briefly holds the secret with the VLC directory's
        // inherited permissions (a restrictive ACL is a Windows-gate verification, not claimed
        // here); a crash can orphan a temp - orphans are NEVER swept (no unknown-file
        // deletion); bounded by one small file per crashed write.
        var tmp = path + ".tmp." + Guid.NewGuid().ToString("N");
        try
        {
            // Re-stage after a failed/interrupted apply: the atomic replace when this call
            // resolves an unresolvable record, else the compare-guarded stage (with explicit
            // re-baseline consent). Always the planning read's baseline, never the
            // post-failure disk state (I2 D2).
            string? RestageAfterFailedApply() => replacingUnresolved != null
                ? StagePendingReplacingUnresolved(password, plannedOver, true, replacingUnresolved)
                : StagePendingGuarded(password, plannedOver, true, explicitRebaseline);
            using (var fs = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                var bytes = System.Text.Encoding.UTF8.GetBytes(string.Join("\n", lines) + "\n");
                fs.Write(bytes, 0, bytes.Length);
                fs.Flush(true);
            }
            BeforeReplaceGuardForTests?.Invoke();
            // Shared abort (E5.6 guard + F11 create race): our temp is discarded and the
            // discovered on-disk state is the truth - re-decide via E1 (strict; may throw
            // VlcrcUnreadableException), serve what is configured, or stage the request.
            // Never an overwrite of someone else's content, never a bare 500.
            string AbortAndServeDiscovered(string logLine)
            {
                try { File.Delete(tmp); } catch { }
                Program.Log(logLine);
                return ServeDiscoveredAfterChange(password);
            }
            // E5.6: content-identity guard - strict re-read and compare with `before`
            // immediately before the replace. Changed under us -> abort, do not overwrite.
            List<string>? now;
            try { now = ReadVlcrcLinesStrict(); } // strict (I2 D4)
            catch (VlcrcUnreadableException) { try { File.Delete(tmp); } catch { } throw; }
            if (!LinesEqual(now, before))
                return AbortAndServeDiscovered("vlcrc changed while the write was being prepared - re-checking instead of overwriting.");
            // File.Replace preserves the destination ACL/metadata; a new file takes the
            // directory default (named, matches the base).
            if (now != null) File.Replace(tmp, path, null);
            else
            {
                BeforeNewFileMoveForTests?.Invoke();
                try { File.Move(tmp, path); }
                catch (IOException) when (File.Exists(path))
                {
                    // F11: a concurrent writer won the first-create race between the guard read
                    // and the move - its content is the truth, re-decide exactly like the guard.
                    return AbortAndServeDiscovered("vlcrc was created by a concurrent writer - re-checking instead of overwriting.");
                }
            }
            AfterReplaceForTests?.Invoke();
            // E5.7: post-replace verify - the full merged state must be on disk. Mismatch =
            // failed apply: the value stays staged as pending and the caller reports it.
            List<string>? after;
            try { after = File.ReadAllLines(path).ToList(); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { RestageAfterFailedApply(); throw new VlcrcUnreadableException(); } // busy: the owed pending stands
            if (!LinesEqual(after, lines))
            {
                var st = RestageAfterFailedApply(); // the planning read's baseline, never the post-failure disk state (I2 D2)
                if (st == "busy")
                    return JsonSerializer.Serialize(new { ok = false, reason = "pending-busy" });
                if (st == "failed") Program.Log("the failed write could not be re-staged durably - nothing is owed; the caller re-decides.");
                else Program.Log("vlcrc did not hold the written state - the write stays queued.");
                return JsonSerializer.Serialize(new { ok = false, reason = "verify failed" });
            }
        }
        catch
        {
            try { if (File.Exists(tmp)) File.Delete(tmp); } catch { }
            throw;
        }
        // Written and verified: clear the obligation THIS value satisfied, as ONE save unit
        // (E2). An unrelated owed pending is never cleared by this write (I2 companion).
        string? written; lock (Gate) { written = _pendingPassword == password ? _pendingPassword : null; }
        if (written != null) ClearPending(written);
        // I2 N1: the requested value is VERIFIED on disk - only NOW is the replaced unresolved
        // record cleared (never before its replacement was durable: no crash window loses
        // both). A failure before this point leaves the old record standing over the new
        // current - bounded: the next explicit request resolves it.
        if (replacingUnresolved != null)
        {
            Program.Log("Cleared the unresolvable deferred vlcrc write - the explicit replacement is verified in effect.");
            // I2: the clear's persist result is never ignored - a failed save leaves the old
            // record standing over the new current (coherent, surfaced as unresolved, resolved
            // by the next explicit request) and is observable, never silent.
            if (!ClearPending(replacingUnresolved))
                Program.Log("pending vlc password: the replaced unresolved record could not be cleared durably - it stands over the new current; the next explicit request resolves it.");
        }
        Program.Log("VLC web remote written into vlcrc.");
        return JsonSerializer.Serialize(new { ok = true });
    }

    /// <summary>Shared re-decide after a discovered external change (E5.6 abort, F11 create
    /// race, or the I2 baseline window): the discovered on-disk state is the truth - stale /
    /// matching pending records reconcile, ok:true ONLY when the requested value is truly in
    /// effect; any other serving state stands untouched and NOTHING is staged over it; only
    /// when nothing serves at all does the requested write wait as pending.</summary>
    private static string ServeDiscoveredAfterChange(string password)
    {
        var d = ResolvePasswordDecision(); // strict; may throw VlcrcUnreadableException
        if (d.StalePending != null) ClearPending(d.StalePending);
        else if (d.ClearMatchingPending) ClearPending(d.Serve!);
        // F13/I2-1: ok:true ONLY when the REQUESTED value is truly in effect (someone else
        // installed exactly it). Any other serving state (different current OR an owed
        // pending) is authoritative and stands untouched - stage NOTHING over it. Only when
        // nothing serves at all does the requested write wait as pending.
        if (d.Serve == password && !d.DeferredOwed) return JsonSerializer.Serialize(new { ok = true });
        if (d.Serve == null)
        {
            var st = StagePendingGuarded(password, null, true); // the strict re-decide proved nothing serves (certain)
            if (st == "busy") Program.Log("an owed pending landed during the write - it stands, nothing staged over it.");
            else if (st == "failed") Program.Log("the deferred write could not be persisted after the abort - the next decide re-checks.");
        }
        return JsonSerializer.Serialize(new { ok = false, reason = "changed" });
    }

    /// <summary>VLC process check; the test hook (null in production) overrides it.</summary>
    internal static Func<bool>? VlcRunningForTests;
    private static bool VlcRunning() => VlcRunningForTests?.Invoke() ?? Process.GetProcessesByName("vlc").Length > 0;

    private static bool LinesEqual(List<string>? a, List<string>? b)
    {
        if (a == null || b == null) return a == null && b == null;
        return a.SequenceEqual(b);
    }

    /// <summary>Test hook (null in production): runs at the top of WriteVlcrc before the
    /// strict planning read, to land an external change inside the decision-to-write window
    /// (I2 baseline window).</summary>
    internal static Action? BeforePlanningReadForTests;
    /// <summary>Test hooks (null in production): run around the E5 content-identity guard.</summary>
    internal static Action? BeforeReplaceGuardForTests;
    internal static Action? AfterReplaceForTests;
    /// <summary>Test hook (null in production): runs just before the first-create File.Move, to
    /// force the F11 concurrent-create collision.</summary>
    internal static Action? BeforeNewFileMoveForTests;
    /// <summary>Test hook (null in production): runs at the top of ClearPending's mutate, before
    /// the under-Gate re-verify, to simulate a racing stage landing in between (F10).</summary>
    internal static Action? ClearPendingPreMutateForTests;

    private static readonly object Gate = new();
    private static string? _pendingPassword;
    // E1: a pending value superseded by a newer plan is NEVER served or applied again. In-memory
    // only (an observed design fact, not a reviewed-or-accepted risk waiver): a restart
    // re-derives safety from the strict read in branch 2.
    private static bool _pendingSuperseded;
    // I2Wip10: the current value this pending was STAGED OVER, captured from the PLANNING READ
    // with its read status (I2 D2: a tolerant re-read at stage time could capture a LATER
    // external change and launder it into a consent baseline). null = the planning read proved
    // NOTHING was configured; _pendingStagedOverUncertain = the baseline could not be established
    // (read failure, or a pre-marker config migrated) - fail-CLOSED: the decision never
    // stale-clears such a pending (could strand a phone holding it) and never applies it over
    // an arbitrary current; it stands until a later state resolves it.
    // NAMED LIMITATIONS (honest scope, I2): the marker tracks the PASSWORD only - a host- or
    // extraintf-only external change, and an ABA password round-trip (X -> Z -> X), are not
    // detected; the verified serve/write guards still leave a bounded read-to-reply TOCTOU
    // window; and until the owed apply lands, the CURRENT value on disk is what controls VLC.
    private static string? _pendingStagedOver;
    private static bool _pendingStagedOverUncertain;

    /// <summary>VLC was open, so the write was skipped; retry when VLC closes.</summary>
    public static bool Pending { get { lock (Gate) return _pendingPassword != null; } }

    // The DURABLE pending value lives Program-side under ConfigSaveLock (Program.PendingVlcDurable,
    // authoritative for serialization, E8); _pendingPassword is the Gate-guarded mirror used for
    // decisions. Both move together inside the E2 one-unit stage/clear operations.

    /// <summary>The PC's attestation for the phone's setup check: vlcrc right now carries
    /// everything phone control needs - the web interface enabled (extraintf=http), a bind
    /// the phone can reach (http-host set and not loopback-only), and a live password - and
    /// no write is still waiting. A running VLC that has not reloaded yet reports Pending
    /// instead. Password equality alone is never enough.</summary>
    public static bool Configured
    {
        get
        {
            // E6: strict reads - an unreadable vlcrc throws VlcrcUnreadableException (the route
            // maps it to 503) instead of attesting "not configured".
            if (Pending) return false;
            return StrictConfiguredOnDisk(ReadVlcrcSnapshotStrict());
        }
    }

    /// <summary>ONE strict read of vlcrc for every status component: an absent file is all
    /// nulls; an existing-but-unreadable file throws VlcrcUnreadableException (same semantics
    /// as ReadVlcrcValueStrict). Several separate strict reads could mix generations under an
    /// EXTERNAL writer (VLC itself); one read cannot. An external rewrite AFTER this read is
    /// the pre-existing named external residual (E5.6 guards writes against it).</summary>
    private sealed class VlcrcSnapshot { public string? Password; public string? Extraintf; public string? Host; }
    private static VlcrcSnapshot ReadVlcrcSnapshotStrict()
    {
        var path = Path.Combine(VlcDir, "vlcrc");
        List<string> lines;
        try { lines = File.ReadAllLines(path).ToList(); }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException) { return new VlcrcSnapshot(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { throw new VlcrcUnreadableException(); }
        return new VlcrcSnapshot { Password = FindValue(lines, "http-password"), Extraintf = FindValue(lines, "extraintf"), Host = FindValue(lines, "http-host") };
    }

    /// <summary>The staged-over baseline WITH its read status (I2 D2): a strict read, so a
    /// null value means PROVEN absent - never conflated with unreadable. Uncertain = fail-closed.</summary>
    private static (string? value, bool certain) CaptureStagedOverBaseline()
    {
        try { return (ReadVlcrcValueStrict("http-password"), true); }
        catch (VlcrcUnreadableException) { return (null, false); }
    }

    /// <summary>The full STRICT configured checks (E6): a live password, the web interface on,
    /// and a bind the phone can reach - never password presence alone.</summary>
    private static bool StrictConfiguredOnDisk(VlcrcSnapshot s)
    {
        if (string.IsNullOrEmpty(s.Password)) return false;
        var parts = (s.Extraintf ?? "").Split(new[] { ',', ':', ' ' }, StringSplitOptions.RemoveEmptyEntries).Select(x => x.Trim()).ToList();
        if (!parts.Contains("http") && !parts.Contains("luahttp")) return false; // interface off
        if (string.IsNullOrEmpty(s.Host)) return false; // VLC's default binds localhost only
        if (s.Host is "127.0.0.1" or "localhost" or "::1") return false; // the phone could never reach it
        return true;
    }

    /// <summary>DELTA-H (I2-qualified semantics): ONE coherent status read - ConfigSaveLock,
    /// then Gate (the documented order, Program.cs; WriteVlcrc holds ConfigSaveLock across its
    /// whole critical section, so a status read never observes a half-committed write/pending
    /// pair). READ-ONLY: no mutation and no reconciliation here - that stays with the E1
    /// decision paths. pending = a LIVE obligation: a non-superseded pending a decision would
    /// still APPLY - nothing configured on disk (branch 5), a deferral whose staged-over
    /// current is unchanged (I2Wip10), or an uncertain baseline standing unresolved (I2 D2).
    /// When the pending's proven baseline was EXTERNALLY replaced, that pending is stale (R2):
    /// the next decision clears it UNAPPLIED and control already works with the current value -
    /// configured, never "waiting". Applied-equal and poisoned superseded records likewise
    /// report pending=false. configured = the full strict checks, never true while a live
    /// obligation waits. An unreadable vlcrc throws VlcrcUnreadableException: the route
    /// answers 503, never an empty/false attestation.</summary>
    public static (bool configured, bool pending) StatusSnapshot()
    {
        lock (Program.ConfigSaveLock)
        {
            string? pending; bool superseded; string? stagedOver; bool overUncertain;
            lock (Gate) { pending = _pendingPassword; superseded = _pendingSuperseded; stagedOver = _pendingStagedOver; overUncertain = _pendingStagedOverUncertain; }
            var snap = ReadVlcrcSnapshotStrict(); // ONE strict read; propagates
            // I2Wip10/D1: a live obligation is a non-superseded pending a decision would still
            // APPLY - nothing on disk (branch 5), OR a deferral whose staged-over current is
            // unchanged (owed), OR an uncertain baseline standing unresolved (I2 D2 fail-closed).
            // A pending whose proven baseline was externally replaced is stale (R2): the current
            // works, the next decision clears it - not a live obligation.
            var liveOwed = !string.IsNullOrEmpty(pending) && !superseded &&
                (string.IsNullOrEmpty(snap.Password) ||
                 (snap.Password != pending && (overUncertain || snap.Password == stagedOver)));
            return (!liveOwed && StrictConfiguredOnDisk(snap), liveOwed);
        }
    }

    /// <summary>The ONE authoritative password decision (E1). Strict semantics, evaluated in
    /// this exact order; VlcrcUnreadableException propagates (callers map to their refusal):
    /// 2. current exists AND pending exists AND pending != current: pending is STALE - clear it
    ///    (E2 unit), serve current, never apply/rewrite.
    /// 3. current exists AND pending == current: already applied - clear WITHOUT rewrite.
    /// 4. current exists, no pending: serve current.
    /// 5. no current AND pending exists AND NOT superseded: serve pending (deferred apply owed).
    /// 6. no current AND (no pending OR superseded): nothing configured - a superseded pending
    ///    is NEVER served or applied again.</summary>
    internal sealed class PasswordDecision
    {
        public string? Serve;             // the value a caller serves (branches 2-5); null on 6
        public string? StalePending;      // branch 2: clear via E2, never serve/apply
        public bool ClearMatchingPending; // branch 3: clear without rewrite
        public bool DeferredOwed;         // branch 5 (or staged-over deferral): the served pending still owes its apply
        public bool SupersededPending;    // branch 6 with a superseded record on file
        public bool PendingUnresolved;    // I2 D2: uncertain baseline + a different current - the pending STANDS (never cleared, never applied over it)
        public string? CurrentRead;       // the strict-read current this decision saw (null = proven absent); planning-read provenance for callers that stage
    }

    internal static PasswordDecision ResolvePasswordDecision()
    {
        string? pending; bool superseded; string? stagedOver; bool overUncertain;
        lock (Gate) { pending = _pendingPassword; superseded = _pendingSuperseded; stagedOver = _pendingStagedOver; overUncertain = _pendingStagedOverUncertain; }
        var current = ReadVlcrcValueStrict("http-password"); // strict; propagates
        var d = new PasswordDecision { CurrentRead = current };
        if (!string.IsNullOrEmpty(current))
        {
            // I2Wip10: a DIFFERENT current is the stale signal (R2) ONLY when it is proven not to
            // be the value the pending was staged over. When it IS the staged-over value, the
            // write was deferred (VLC running) over exactly this current and the obligation
            // stands: serve the owed value as deferred; the apply completes when VLC closes.
            // An UNCERTAIN baseline (I2 D2) fails closed: serve the current truth, but the
            // pending STANDS - never cleared (could strand a phone holding it), never applied
            // over an arbitrary current.
            if (!string.IsNullOrEmpty(pending) && !superseded && pending != current)
            {
                if (!overUncertain && current == stagedOver)
                {
                    d.Serve = pending; d.DeferredOwed = true;
                    return d;
                }
                if (overUncertain)
                {
                    d.Serve = current; d.PendingUnresolved = true;
                    return d;
                }
            }
            d.Serve = current;
            if (!string.IsNullOrEmpty(pending))
            {
                if (pending != current) d.StalePending = pending;
                else d.ClearMatchingPending = true;
            }
            return d;
        }
        if (!string.IsNullOrEmpty(pending) && !superseded)
        {
            d.Serve = pending; d.DeferredOwed = true;
            return d;
        }
        d.SupersededPending = !string.IsNullOrEmpty(pending) && superseded;
        return d;
    }

    /// <summary>Clear the pending record as ONE save unit (E2): mutation + persist under
    /// ConfigSaveLock. On failure the record is restored EXACTLY as it stood - value, baseline,
    /// uncertain flag AND superseded flag (I2 faithful whole-state rollback): the next decision
    /// re-derives its disposition (a stale/matching record is re-cleared, an unresolved record
    /// stands for explicit intent, an owed record completes only over its proven baseline).
    /// Callers must NOT hold Gate; the pending value is re-verified
    /// under Gate inside the lock before clearing. F10: a mismatch is a no-op success that
    /// persists NOTHING, and a failed save's rollback only runs when this call actually
    /// mutated - it can never wipe a different pending another thread staged.</summary>
    internal static bool ClearPending(string expected)
    {
        lock (Gate) { if (_pendingPassword != expected) return true; } // F10: mismatch = no-op success, no persist
        string? prior = null; bool priorSup = false; string? priorOver = null; var priorOverUncertain = false; var mutated = false;
        return Program.MutateAndPersist(
            () =>
            {
                ClearPendingPreMutateForTests?.Invoke();
                lock (Gate)
                {
                    if (_pendingPassword != expected) return; // re-verify: someone changed it
                    prior = _pendingPassword; priorSup = _pendingSuperseded; priorOver = _pendingStagedOver; priorOverUncertain = _pendingStagedOverUncertain;
                    _pendingPassword = null; _pendingSuperseded = false; _pendingStagedOver = null; _pendingStagedOverUncertain = false;
                    mutated = true;
                }
                Program.PendingVlcDurable = null;
                Program.PendingVlcStagedOverDurable = null;
                Program.PendingVlcStagedOverUncertainDurable = false;
            },
            () =>
            {
                if (!mutated) return; // F10: no mutation happened - leave the untouched pending alone
                Program.PendingVlcDurable = prior;
                Program.PendingVlcStagedOverDurable = priorOver;
                Program.PendingVlcStagedOverUncertainDurable = priorOverUncertain;
                // Faithful whole-state rollback (I2): the record returns EXACTLY as it stood -
                // value, baseline, uncertain flag AND the prior superseded flag. Forcing
                // superseded here would let a later autonomous retry dispose of a restored
                // UNRESOLVED record as stale, bypassing explicit-only recovery; staleness is
                // re-derived by every decision, so a faithfully restored stale/matching record
                // is simply re-cleared on the next pass - never applied (the baseline check
                // guards every write).
                lock (Gate) { _pendingPassword = prior; _pendingSuperseded = priorSup; _pendingStagedOver = priorOver; _pendingStagedOverUncertain = priorOverUncertain; }
            },
            "clearing the deferred VLC password");
    }

    /// <summary>TEST-ONLY: install the superseded-pending state directly. NO production path
    /// produces it today (the old failed-clear poison marker was retired with the I2 faithful
    /// whole-state rollback); the SupersededPending decision branch and the pair save-lock
    /// clear remain as defense-in-depth, and the hook keeps that guard's contract tested.</summary>
    internal static void SupersedePendingForTests()
    {
        lock (Gate) { if (_pendingPassword != null) _pendingSuperseded = true; }
    }

    /// <summary>Stage a deferred vlcrc write as ONE save unit (E2/E8): the durable record and
    /// the Gate mirror move together; a failed persist restores the prior state.</summary>
    internal static bool StagePending(string password)
    {
        string? prior = null; bool priorSup = false; string? priorOver = null; string? priorOverMirror = null; var priorOverUncertainMirror = false; var converge = false;
        var (over, overCertain) = CaptureStagedOverBaseline(); // planning-read provenance with status (I2 D2)
        return Program.MutateAndPersist(
            () =>
            {
                prior = Program.PendingVlcDurable;
                priorOver = Program.PendingVlcStagedOverDurable;
                Program.PendingVlcDurable = password;
                lock (Gate)
                {
                    // Converging on the SAME owed value keeps its original baseline: re-capturing
                    // could launder an external change into "still owed" (I2 D2).
                    converge = _pendingPassword == password && !_pendingSuperseded;
                    priorSup = _pendingSuperseded; priorOverMirror = _pendingStagedOver; priorOverUncertainMirror = _pendingStagedOverUncertain;
                    _pendingPassword = password; _pendingSuperseded = false;
                    if (!converge) { _pendingStagedOver = over; _pendingStagedOverUncertain = !overCertain; Program.PendingVlcStagedOverDurable = over; Program.PendingVlcStagedOverUncertainDurable = !overCertain; }
                }
            },
            () =>
            {
                Program.PendingVlcDurable = prior;
                Program.PendingVlcStagedOverDurable = priorOver;
                Program.PendingVlcStagedOverUncertainDurable = priorOverUncertainMirror;
                lock (Gate) { _pendingPassword = prior; _pendingSuperseded = priorSup; _pendingStagedOver = priorOverMirror; _pendingStagedOverUncertain = priorOverUncertainMirror; }
            },
            "staging the deferred VLC password");
    }

    /// <summary>I2 companion ruling: stage as an ATOMIC compare-and-set on the owed
    /// obligation - the compare and the set are one Gate acquisition inside the one save unit.
    /// A DIFFERENT live (non-superseded) pending is never replaced: "busy" (nothing changed).
    /// The same value (or none) converges and keeps its ORIGINAL baseline (I2 D2). The baseline
    /// comes from the CALLER's planning read with its read status - never a fresh tolerant
    /// re-read here, which could capture a later external change as a consent baseline.
    /// Returns null on persist success, "busy", or "failed" (persist rolled back).</summary>
    /// <param name="explicitRebaseline">I2 same-value explicit consent: an explicit request
    /// for the owed value itself re-baselines an uncertain record on the caller's proven
    /// planning read instead of converging on the unprovable baseline.</param>
    internal static string? StagePendingGuarded(string password, string? over, bool overCertain, bool explicitRebaseline = false)
    {
        var busy = false; var converge = false;
        string? prior = null; bool priorSup = false; string? priorOver = null; string? priorOverMirror = null; var priorOverUncertainMirror = false;
        var saved = Program.MutateAndPersist(
            () =>
            {
                lock (Gate)
                {
                    if (_pendingPassword != null && !_pendingSuperseded && _pendingPassword != password) { busy = true; return; }
                    converge = _pendingPassword == password && !_pendingSuperseded && !explicitRebaseline;
                    priorSup = _pendingSuperseded; priorOverMirror = _pendingStagedOver; priorOverUncertainMirror = _pendingStagedOverUncertain;
                    _pendingPassword = password; _pendingSuperseded = false;
                    if (!converge) { _pendingStagedOver = over; _pendingStagedOverUncertain = !overCertain; }
                }
                prior = Program.PendingVlcDurable;
                priorOver = Program.PendingVlcStagedOverDurable;
                Program.PendingVlcDurable = password;
                if (!converge) { Program.PendingVlcStagedOverDurable = over; Program.PendingVlcStagedOverUncertainDurable = !overCertain; }
            },
            () =>
            {
                if (busy) return; // nothing was changed: leave the owed pending alone
                Program.PendingVlcDurable = prior;
                Program.PendingVlcStagedOverDurable = priorOver;
                Program.PendingVlcStagedOverUncertainDurable = priorOverUncertainMirror;
                lock (Gate) { _pendingPassword = prior; _pendingSuperseded = priorSup; _pendingStagedOver = priorOverMirror; _pendingStagedOverUncertain = priorOverUncertainMirror; }
            },
            "staging the deferred VLC password");
        if (busy) return "busy";
        return saved ? null : "failed";
    }

    /// <summary>I2 N1: replace an UNRESOLVABLE pending with a new owed value in ONE save
    /// unit - the compare (the standing record is still the exact unresolved record the
    /// caller resolved) and the replace are one Gate acquisition inside the unit, and a failed
    /// persist restores the old record whole (value + baseline + flag): the old and new
    /// obligations are never both absent. Returns null on persist success, "busy" when the
    /// standing record changed under the caller (re-decide next request), "failed" when the
    /// persist rolled back.</summary>
    internal static string? StagePendingReplacingUnresolved(string password, string? over, bool overCertain, string expectedUnresolved)
    {
        var mismatch = false;
        string? prior = null; bool priorSup = false; string? priorOver = null; string? priorOverMirror = null; var priorOverUncertainMirror = false;
        var saved = Program.MutateAndPersist(
            () =>
            {
                lock (Gate)
                {
                    if (_pendingPassword != expectedUnresolved || _pendingSuperseded || !_pendingStagedOverUncertain) { mismatch = true; return; }
                    priorSup = _pendingSuperseded; priorOverMirror = _pendingStagedOver; priorOverUncertainMirror = _pendingStagedOverUncertain;
                    _pendingPassword = password; _pendingSuperseded = false; _pendingStagedOver = over; _pendingStagedOverUncertain = !overCertain;
                }
                prior = Program.PendingVlcDurable;
                priorOver = Program.PendingVlcStagedOverDurable;
                Program.PendingVlcDurable = password;
                Program.PendingVlcStagedOverDurable = over;
                Program.PendingVlcStagedOverUncertainDurable = !overCertain;
            },
            () =>
            {
                if (mismatch) return;
                Program.PendingVlcDurable = prior;
                Program.PendingVlcStagedOverDurable = priorOver;
                Program.PendingVlcStagedOverUncertainDurable = priorOverUncertainMirror;
                lock (Gate) { _pendingPassword = prior; _pendingSuperseded = priorSup; _pendingStagedOver = priorOverMirror; _pendingStagedOverUncertain = priorOverUncertainMirror; }
            },
            "replacing the unresolvable deferred VLC password");
        if (mismatch) return "busy";
        return saved ? null : "failed";
    }

    /// <summary>E2: called inside the pair transaction's save lock - a superseded pending
    /// record is cleared in the SAME save as the new phone record (no separate restage).
    /// Returns the prior state for the pair save's rollback path.</summary>
    internal static (string? prior, bool priorSup, string? priorOver, bool priorOverUncertain) ClearSupersededPendingWithinSaveLock()
    {
        lock (Gate)
        {
            var prior = _pendingPassword; var priorSup = _pendingSuperseded; var priorOver = _pendingStagedOver; var priorOverUncertain = _pendingStagedOverUncertain;
            if (_pendingSuperseded) { _pendingPassword = null; _pendingSuperseded = false; _pendingStagedOver = null; _pendingStagedOverUncertain = false; Program.PendingVlcDurable = null; Program.PendingVlcStagedOverDurable = null; Program.PendingVlcStagedOverUncertainDurable = false; }
            return (prior, priorSup, priorOver, priorOverUncertain);
        }
    }

    internal static void RestorePendingSnapshotWithinSaveLock(string? prior, bool priorSup, string? priorOver, bool priorOverUncertain)
    {
        lock (Gate) { _pendingPassword = prior; _pendingSuperseded = priorSup; _pendingStagedOver = priorOver; _pendingStagedOverUncertain = priorOverUncertain; Program.PendingVlcDurable = prior; Program.PendingVlcStagedOverDurable = priorOver; Program.PendingVlcStagedOverUncertainDurable = priorOverUncertain; }
    }

    /// <summary>F12/D1: raw Gate-level stage inside an already-held save lock - no persist of
    /// its own; the enclosing save's rollback (RestorePendingSnapshotWithinSaveLock) restores
    /// the prior state on failure. No production callers since TrySavePair moved to the
    /// compare-guarded TryStage below: retained deliberately as the raw mutation the F10
    /// rollback-isolation test drives (that simulated interleaving is now structurally
    /// impossible in production - mutations serialize under ConfigSaveLock and stage paths
    /// compare-guard - so the test is defense-in-depth for the rollback contract).</summary>
    internal static void StagePendingWithinSaveLock(string password)
    {
        var (over, overCertain) = CaptureStagedOverBaseline(); // planning-read provenance with status (I2 D2)
        lock (Gate) { _pendingPassword = password; _pendingSuperseded = false; _pendingStagedOver = over; _pendingStagedOverUncertain = !overCertain; Program.PendingVlcDurable = password; Program.PendingVlcStagedOverDurable = over; Program.PendingVlcStagedOverUncertainDurable = !overCertain; }
    }

    /// <summary>I2-3: inside the pair save lock - stage a fresh candidate ONLY when no live
    /// (non-superseded, different) pending exists. The plan predates the lock: a /vlc/setup
    /// deferral staged in between is owed and durable and is NEVER overwritten; the pair then
    /// serves that value instead (false = an existing live pending was preserved).</summary>
    internal static bool TryStageFreshCandidateWithinSaveLock(string candidate)
    {
        lock (Gate)
        {
            if (_pendingPassword != null && !_pendingSuperseded && _pendingPassword != candidate) return false;
            // A fresh candidate stands over NOTHING configured (branch 6, certain) - but only
            // reset the marker when this call actually changes the record: a converge on the
            // same owed value keeps the staged-over its apply still expects (I2 D2).
            if (_pendingPassword != candidate || _pendingSuperseded) { _pendingStagedOver = null; _pendingStagedOverUncertain = false; Program.PendingVlcStagedOverDurable = null; Program.PendingVlcStagedOverUncertainDurable = false; }
            _pendingPassword = candidate; _pendingSuperseded = false; Program.PendingVlcDurable = candidate;
            return true;
        }
    }

    /// <summary>The live pending value under Gate; callers hold ConfigSaveLock (I2-3).</summary>
    internal static string? PendingValueWithinSaveLock { get { lock (Gate) return _pendingPassword; } }

    /// <summary>F15: drop a plan candidate that was never staged (a different owed pending won
    /// the race) so no later caller can apply it.</summary>
    internal static void DropCandidateWithinSaveLock(string candidate)
    {
        lock (Gate) { if (_candidate == candidate) _candidate = null; }
    }

    /// <summary>Re-arm a deferred write after a helper restart: decide via E1 BEFORE any
    /// write - a stale or already-applied pending is cleared instead of written; only a live
    /// deferred value proceeds (when VLC is closed).</summary>
    public static void RestorePending(string password, string? stagedOver, bool stagedOverUncertain)
    {
        lock (Gate) { _pendingPassword = password; _pendingSuperseded = false; _pendingStagedOver = stagedOver; _pendingStagedOverUncertain = stagedOverUncertain; }
        try
        {
            var d = ResolvePasswordDecision();
            if (d.StalePending != null) { ClearPending(d.StalePending); return; }
            if (d.ClearMatchingPending) { ClearPending(d.Serve!); return; }
            if (!d.DeferredOwed) { if (!d.PendingUnresolved) ClearPending(password); return; } // unresolved (uncertain baseline): stand; the retry timer re-decides
            if (VlcRunning())
            {
                Program.SaveConfig(); // the deferred write must survive a helper restart
                Program.Log("Restored the vlcrc write deferred before the restart - it completes when VLC closes.");
                return;
            }
            Program.Log("VLC closed while BingeMote was off - completing the deferred vlcrc write now.");
            WriteVlcrcInternal(password, d.CurrentRead); // the decision's baseline carried into the write (I2 baseline window)
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // F14: vlcrc unreadable or an expected IO/access failure at startup: the durable
            // pending stays queued; the retry timer re-decides later. Never apply blindly.
            Program.Log("vlcrc could not be read at startup - the deferred write stays queued.");
        }
    }

    /// <summary>Called on a timer: if an earlier write lost to a running VLC and VLC has
    /// since closed, write now. True while a write is still waiting. Decides via E1 BEFORE any
    /// write: a stale/applied/superseded pending is cleared, never written.</summary>
    public static bool RetryPending()
    {
        string? pw;
        lock (Gate) pw = _pendingPassword;
        if (pw == null) return false;
        try
        {
            var d = ResolvePasswordDecision();
            if (d.StalePending != null) { ClearPending(d.StalePending); return false; }
            if (d.ClearMatchingPending) { ClearPending(d.Serve!); return false; }
            if (!d.DeferredOwed)
            {
                // I2 D2: an UNCERTAIN staged-over baseline fails closed - the pending stands
                // (never cleared, never applied over an arbitrary current); keep waiting.
                if (d.PendingUnresolved) return true;
                ClearPending(pw); return false;
            }
            if (VlcRunning()) return true;
            Program.Log("VLC closed - writing the skipped vlcrc web-remote setup now.");
            using var doc = JsonDocument.Parse(WriteVlcrcInternal(pw, d.CurrentRead)); // the decision's baseline carried into the write (I2 baseline window)
            return doc.RootElement.GetProperty("ok").GetBoolean() == false;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // F14: strict-read refusal OR an expected IO/access failure from the write: keep
            // waiting, never treat as "nothing configured" - the timer retries later.
            return true;
        }
    }

    /// <summary>The password the phone must use right now: the read-only wrapper over the E1
    /// decision (branches 2-5 serve semantics), with IN-EFFECT separation (I2): a deferred write
    /// becomes vlcrc the moment VLC closes, but UNTIL it does VLC still accepts the current on
    /// disk - so while a deferral stands over an existing current, this serves the current (the
    /// owed value is attested via Pending/StatusSnapshot, and becomes effective at the apply).
    /// A deferred write over NOTHING configured still serves the owed value. A stale or applied
    /// pending is cleared as it is seen. The strict read propagates VlcrcUnreadableException -
    /// a control call fails instead of serving a stale value. null when nothing is configured.</summary>
    public static string? EffectivePassword()
    {
        var d = ResolvePasswordDecision();
        if (d.StalePending != null) { ClearPending(d.StalePending); return d.Serve; }
        if (d.ClearMatchingPending) { ClearPending(d.Serve!); return d.Serve; }
        // I2 in-effect separation: while an owed write is DEFERRED over an existing current,
        // VLC still accepts the CURRENT value - serving the owed one would 401 every control
        // call until the apply lands. Control serves the value actually in effect; the owed
        // value stays attested via Pending/StatusSnapshot and becomes effective at the apply.
        if (d.DeferredOwed && d.CurrentRead != null) return d.CurrentRead;
        return d.Serve;
    }

    internal sealed class VlcrcUnreadableException : IOException
    {
        public VlcrcUnreadableException() : base("vlcrc exists but could not be read") { }
    }

    /// <summary>Strict live vlcrc read: null only when the file is really not there; a vlcrc
    /// that exists but cannot be read throws VlcrcUnreadableException. No File.Exists pre-check:
    /// it returns false for a file it merely cannot reach (untraversable parent, access fault),
    /// which would read as "nothing configured" and plan a fresh password over an existing one.</summary>
    internal static string? VlcDirOverride;
    private static string VlcDir => VlcDirOverride ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "vlc");

    private static string? ReadVlcrcValueStrict(string key)
    {
        var path = Path.Combine(VlcDir, "vlcrc");
        try { return FindValue(File.ReadAllLines(path).ToList(), key); }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException) { return null; }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { throw new VlcrcUnreadableException(); }
    }

    /// <summary>Strict live vlcrc LINE read (I2 D4): null ONLY when the file is proven absent
    /// (FileNotFound/DirectoryNotFound). Anything else unreadable - including a path File.Exists
    /// would report as missing - throws VlcrcUnreadableException. The WriteVlcrc planning read
    /// uses this so a null staged-over baseline is always PROVEN, never an access fault in
    /// disguise.</summary>
    private static List<string>? ReadVlcrcLinesStrict()
    {
        var path = Path.Combine(VlcDir, "vlcrc");
        try { return File.ReadAllLines(path).ToList(); }
        catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException) { return null; }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { throw new VlcrcUnreadableException(); }
    }

    /// <summary>The plan for a pairing (F12): Value is the password to serve/apply;
    /// IsFreshCandidate is true ONLY on branch 6 (nothing configured) - a fresh candidate the
    /// pair save must stage as pending in the SAME save as the phone record (D1). All serve
    /// branches (2-5) stage nothing: the current is already in effect, or the deferred pending
    /// is already durable.</summary>
    internal readonly struct PasswordPlan
    {
        public PasswordPlan(string value, bool isFreshCandidate) { Value = value; IsFreshCandidate = isFreshCandidate; }
        public string Value { get; }
        public bool IsFreshCandidate { get; }
    }

    /// <summary>Decide the password BEFORE anything is saved or spent, changing nothing: the
    /// pending value when one waits, else the live vlcrc value (an unreadable vlcrc is a
    /// refusal, not a fresh password), else a fresh candidate that is not written yet.</summary>
    internal static PasswordPlan PlanPassword()
    {
        // E1: the decision is authoritative - no blind pending-first return. Stale/applied
        // pending records are cleared as they are seen (E2 units).
        var d = ResolvePasswordDecision();
        if (d.StalePending != null) { ClearPending(d.StalePending); return new PasswordPlan(d.Serve!, false); }
        if (d.ClearMatchingPending) { ClearPending(d.Serve!); return new PasswordPlan(d.Serve!, false); }
        if (d.Serve != null) return new PasswordPlan(d.Serve, false); // current, or the deferred pending
        // Branch 6 only: ONE fresh candidate under Gate (RNG only, no I/O), so two callers can
        // never allocate different passwords from the same "nothing configured" read. A
        // superseded pending is cleared by the pair transaction's single save, never restaged.
        lock (Gate) return new PasswordPlan(_candidate ??= Convert.ToHexString(RandomNumberGenerator.GetBytes(12)).ToLowerInvariant(), true);
    }

    private static string? _candidate;

    /// <summary>Test hook: drop the cached plan candidate so the next PlanPassword re-reads.</summary>
    internal static void ForgetCandidateForTests() { lock (Gate) _candidate = null; }

    /// <summary>Test-only reset of the process-global pending snapshot. The pending lives in
    /// these statics (not only in Program.PendingVlcDurable), so nulling the durable mirror alone
    /// leaves a residue: a class that deliberately ends with a live pending (e.g. the F10 raced
    /// stage) leaks it into the NEXT class, where TrySavePair captures it as the prior snapshot
    /// and its failure rollback restores it. Every class that can stage a pending calls this in
    /// ctor AND Dispose. The statics are process-global by design (single-instance helper); the
    /// suite owns isolating them.</summary>
    internal static void ResetPendingForTests()
    {
        // Lock order is the documented ConfigSaveLock -> Gate. Also clears the durable MARKER
        // fields (I2Wip10): they are new process-global state and would otherwise leak into the
        // next class exactly like the mirror residue this method exists for. PendingVlcDurable
        // itself stays with the fixtures, as documented above.
        lock (Program.ConfigSaveLock)
            lock (Gate)
            {
                _pendingPassword = null; _pendingSuperseded = false; _pendingStagedOver = null; _pendingStagedOverUncertain = false;
                Program.PendingVlcStagedOverDurable = null; Program.PendingVlcStagedOverUncertainDurable = false;
            }
    }

    /// <summary>Carry out a plan after the phone is durably saved, via the E1 decision:
    /// a live current that appeared since the plan is served back and never rewritten
    /// (read-only serve-current); a deferred pending is served as-is; only branch 6 writes,
    /// through the E5-guarded path. If vlcrc turns unreadable, the planned value is staged as
    /// ONE durable save unit (E2) and served as pending - the caller reports vlcPending, never
    /// a bare failure that strands a saved phone without a password path.
    /// F12/D2: also returns whether the served value is VERIFIED IN EFFECT - true only for the
    /// serve-current branches (2/3/4: the strict read just observed it) and a WriteVlcrc
    /// ok:true (post-replace verify passed, or no-op already on disk). False for branch 5 (a
    /// deferred write is still owed), every ok:false, and the catch path - INCLUDING when the
    /// emergency StagePending's persist failed (value served, nothing durable, nothing
    /// applied): an unverified password must never read as "ready".</summary>
    internal static (string value, bool verifiedInEffect) ApplyPlannedPassword(string planned)
    {
        string? decisionBaseline = null; var decisionRead = false; // planning-read provenance for any emergency stage (I2 D2)
        var plannedProvenStale = false; // I2 D5: set when THIS call clears the planned value as a stale pending
        try
        {
            var d = ResolvePasswordDecision();
            decisionBaseline = d.CurrentRead; decisionRead = true;
            // point. A change between the decision read and the reply (external writer, VLC
            // rewrite) is discovered and the discovered truth is served instead - the pair
            // reply's verifiedInEffect=true must never attest a stale snapshot value.
            (string value, bool verifiedInEffect) VerifiedServe(string expected)
            {
                BeforeReplaceGuardForTests?.Invoke();
                var nowValue = ReadVlcrcValueStrict("http-password"); // strict; propagates
                if (nowValue == expected) return (expected, true);
                var d2 = ResolvePasswordDecision();
                if (d2.StalePending != null) { if (d2.StalePending == planned) plannedProvenStale = true; ClearPending(d2.StalePending); }
                else if (d2.ClearMatchingPending) ClearPending(d2.Serve!);
                if (d2.Serve != null) return (d2.Serve, !d2.DeferredOwed);
                // Nothing serves: the planned value is owed and must be durable before it is served.
                // I2: a plan ALREADY PROVEN STALE in this call is never staged here - the
                // staleness proof stands, so the only truthful outcome is explicit failure
                // (the outer catch's stale branch re-decides, then fails with a retryable 503):
                // a reply must never carry a credential that is neither on disk nor owed.
                if (plannedProvenStale) throw new VlcrcUnreadableException();
                var st = StagePendingGuarded(planned, null, true); // the strict re-decide just proved nothing serves (certain)
                if (st == "busy")
                {
                    try
                    {
                        var d3 = ResolvePasswordDecision();
                        // Mirror the d2 handling: a served decision's pending bookkeeping is
                        // reconciled exactly the same way before serving (I2 review finding 3).
                        if (d3.StalePending != null) { if (d3.StalePending == planned) plannedProvenStale = true; ClearPending(d3.StalePending); }
                        else if (d3.ClearMatchingPending) ClearPending(d3.Serve!);
                        if (d3.Serve != null) return (d3.Serve, false);
                    }
                    catch (IOException) { }
                }
                else if (st == "failed") Program.Log("the pending stage after a discovered change could not be persisted - the served value is owed but not durable.");
                return (planned, false);
            }
            if (d.StalePending != null) { if (d.StalePending == planned) plannedProvenStale = true; ClearPending(d.StalePending); return VerifiedServe(d.Serve!); }
            if (d.ClearMatchingPending) { ClearPending(d.Serve!); return VerifiedServe(d.Serve!); }
            if (d.Serve != null)
            {
                if (!d.DeferredOwed) { lock (Gate) { if (_candidate == planned) _candidate = null; } return VerifiedServe(d.Serve); }
                // R3: branch 5 - a deferred write is still owed. When the owed value IS the
                // planned one and VLC is closed, complete it NOW through the same E5-guarded
                // write instead of always waiting for the retry timer (the F12 same-save stage
                // made every fresh pair land here; deferring even with VLC closed was a
                // WIP8->WIP9 regression). Defer only when VLC is running or a DIFFERENT value
                // is owed - a different owed value is never overwritten by this plan.
                if (d.Serve != planned || VlcRunning()) return (d.Serve, false);
            }
            // Branch 6 (or R3 branch 5: the planned value is owed and VLC is closed) - apply
            // the planned value via the E5-guarded write.
            var result = JsonDocument.Parse(WriteVlcrcInternal(planned, d.CurrentRead)).RootElement; // the decision's baseline carried into the write (I2 baseline window)
            lock (Gate) { if (_candidate == planned) _candidate = null; }
            var wrote = result.TryGetProperty("ok", out var okEl) && okEl.GetBoolean();
            if (!wrote && result.TryGetProperty("reason", out var rs) && rs.GetString() == "changed")
            {
                // The file changed under the write: re-decide on the discovered state and serve
                // that truth (discovered current, or the pending the abort staged).
                var d2 = ResolvePasswordDecision();
                if (d2.StalePending != null) { if (d2.StalePending == planned) plannedProvenStale = true; ClearPending(d2.StalePending); }
                else if (d2.ClearMatchingPending) ClearPending(d2.Serve!);
                if (d2.Serve != null) return (d2.Serve, !d2.DeferredOwed);
                return (planned, false);
            }
            return (planned, wrote); // ok:true = verified on disk; ok:false = write still owed
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // I2-2: VlcrcUnreadableException OR an expected IO/access failure from the write
            // itself (temp create / replace / move / access). The phone is durable and the
            // pair code spent - the reply MUST still carry the token with a truthful
            // vlcPending, never a bare 500 that strands the phone without its token.
            // I2 companion: the emergency stage is compare-guarded - a different owed pending
            // stands and is served instead.
            // I2 D5: a plan THIS call already proved stale (cleared as StalePending above) is
            // never re-staged: its only available baseline is the external current that replaced
            // it, which would launder proven-stale into owed-over-that-current (a later apply
            // would overwrite it). The value is served unverified; a fresh request re-plans with
            // a fresh strict read.
            if (plannedProvenStale)
            {
                // I2 N2: never hand the phone a credential that is neither on disk nor owed.
                // Serve the discovered current when it verifies NOW; on a read error an
                // explicit failure (the route maps it to a retryable 503) - never the stale plan.
                Program.Log("the planned VLC password was discovered stale during the apply - it is not re-staged over the discovered current.");
                try
                {
                    var dS = ResolvePasswordDecision();
                    if (dS.StalePending != null) ClearPending(dS.StalePending);
                    else if (dS.ClearMatchingPending) ClearPending(dS.Serve!);
                    if (dS.Serve != null) return (dS.Serve, !dS.DeferredOwed);
                }
                catch (IOException) { }
                throw new VlcrcUnreadableException();
            }
            var stage = StagePendingGuarded(planned, decisionBaseline, decisionRead);
            if (stage == "busy")
            {
                // A different owed pending stands; serve THAT truth, never the plan.
                try
                {
                    var d2 = ResolvePasswordDecision();
                    if (d2.StalePending != null) { if (d2.StalePending == planned) plannedProvenStale = true; ClearPending(d2.StalePending); }
                    else if (d2.ClearMatchingPending) ClearPending(d2.Serve!);
                    if (d2.Serve != null) return (d2.Serve, false);
                }
                catch (IOException) { }
            }
            else if (stage == "failed")
            {
                // NAMED RESIDUAL (F14 edge): the phone save committed but this re-stage's
                // persist failed (config store failing between the two saves). The value is
                // served unverified and is nowhere durable; the adopt-side F14b guard refuses
                // exactly this state, but the pair reply must still carry the token (I2-2).
                Program.Log("the emergency pending stage could not be persisted - the served value is owed but not durable.");
            }
            return (planned, false);
        }
    }

    /// <summary>The automatic-first setup: the PC picks the password. Reuses the one
    /// already configured (including a write still waiting for VLC to close); generates a
    /// fresh one only when nothing exists. The value is never logged.</summary>
    public static string ReadOrCreatePassword()
    {
        // E1: serve the authoritative decision; only branch 6 creates (via the E5-guarded write).
        var d = ResolvePasswordDecision();
        if (d.StalePending != null) { ClearPending(d.StalePending); return d.Serve!; }
        if (d.ClearMatchingPending) { ClearPending(d.Serve!); return d.Serve!; }
        var requested = d.Serve; // branch 6 (incl. superseded) already serves null
        if (string.IsNullOrEmpty(requested))
        {
            requested = Convert.ToHexString(RandomNumberGenerator.GetBytes(12)).ToLowerInvariant();
            Program.Log("VLC web-remote password generated for the phone (value never logged).");
        }
        // A vlcrc can carry a password with the web interface itself off: the idempotent write
        // also ensures extraintf=http and http-host, not just the password. Defers to the
        // pending write when VLC is running; EffectivePassword serves the in-effect current
        // until the owed write lands (I2 in-effect separation). F13/I2-1: the
        // write result is authoritative - ok:true means the REQUESTED value is in effect;
        // anything else re-decides via E1 and serves the discovered truth, so the phone's
        // one-shot adopt fetch is never spent on a value that is not in effect.
        var wr = JsonDocument.Parse(WriteVlcrcInternal(requested, d.CurrentRead)).RootElement; // host repair carries the decision baseline, never resolution intent (I2 N1)
        if (wr.TryGetProperty("ok", out var okEl) && okEl.GetBoolean()) return requested;
        var d2 = ResolvePasswordDecision(); // strict; propagates VlcrcUnreadableException
        if (d2.StalePending != null) ClearPending(d2.StalePending);
        else if (d2.ClearMatchingPending) ClearPending(d2.Serve!);
        if (d2.Serve != null) return d2.Serve; // discovered current, or the owed pending
        // Nothing else serves: the requested value only when it is the live pending record -
        // the E2 stage units move the mirror and the durable record together (a failed persist
        // rolls BOTH back), so a live mirror IS a durably staged obligation.
        lock (Gate) { if (_pendingPassword == requested && !_pendingSuperseded) return requested; }
        // F14b / I2 adopt contract: the value would be NEITHER verified on disk NOR durably
        // staged (e.g. the defer/abort stage's persist failed). Never serve it - the adopt
        // route maps this IOException to a retryable 503 and leaves the one-shot grant
        // unspent, instead of spending it on a nowhere-password that would 401 -> unpair.
        throw new IOException("no verifiable or durable VLC password state to serve");
    }

    /// <summary>The live (uncommented) value of a vlcrc key, or null. Used for the
    /// web-remote password the resume poller needs.</summary>
    internal static string? ReadVlcrcValue(string key)
    {
        try
        {
            var path = Path.Combine(VlcDir, "vlcrc");
            if (!File.Exists(path)) return null;
            return FindValue(File.ReadAllLines(path).ToList(), key);
        }
        catch { return null; }
    }

    private static string? FindValue(List<string> lines, string key)
    {
        var prefix = key + "=";
        foreach (var line in lines)
        {
            var raw = line.TrimStart();
            if (raw.StartsWith('#')) continue; // only a live setting counts
            if (raw.StartsWith(prefix, StringComparison.Ordinal)) return raw[prefix.Length..].Trim();
        }
        return null;
    }

    private static string MergeValue(string? existing, string wanted)
    {
        if (string.IsNullOrEmpty(existing)) return wanted;
        // VLC's own preferences writer separates with ':' ("rc:http") and its loader also
        // takes space and ','; "luahttp" is its old name for the same interface.
        var parts = existing.Split(new[] { ',', ':', ' ' }, StringSplitOptions.RemoveEmptyEntries)
                            .Select(p => p.Trim()).ToList();
        if (parts.Contains(wanted) || (wanted == "http" && parts.Contains("luahttp"))) return existing;
        parts.Add(wanted);
        return string.Join(",", parts);
    }

    /// <summary>Replace the (usually commented-out) vlcrc line for a key, or append it.</summary>
    private static void Upsert(List<string> lines, string key, string value)
    {
        var prefix = key + "=";
        for (var i = 0; i < lines.Count; i++)
        {
            var t = lines[i].TrimStart().TrimStart('#').TrimStart();
            if (t.StartsWith(prefix, StringComparison.Ordinal))
            {
                lines[i] = prefix + value;
                return;
            }
        }
        lines.Add(prefix + value);
    }
}
