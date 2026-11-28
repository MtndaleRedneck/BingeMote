using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace BingeMoteHelper;

/// <summary>What the elevated setup itself created, written only by the elevated side into the
/// admin-only data directory. An entry is written only after the object was created by THIS run
/// (the name was absent before, the add succeeded) and read back exact. Uninstall removes only
/// journaled objects, and only while the live object still equals what was journaled. An object
/// created by an earlier version, by anyone else, or one whose creation could not be journaled is
/// never in this file and is never deleted. Admin-vs-admin replacement is NOT prevented: an
/// administrator can edit this file and the objects alike.</summary>
internal sealed record LifecycleJournal(int Version, List<RuleEntry> Rules, bool UrlAcl, WatchdogEntry? Watchdog, List<UncertainEntry> Uncertain, string? UrlAclUncertain = null);
/// <summary>Recovery evidence only: a create was attempted under this fresh Name but the outcome could not be confirmed (create exit nonzero, or read-back not exact). It is NEVER delete authority; uninstall only reports it as a possible orphan.</summary>
internal sealed record UncertainEntry(string Key, string DisplayName, string Outcome);
/// <summary>A firewall rule this build created: its actual unique Name (a random key chosen at creation, never reused), the DisplayName, and the full property line read back right after creation.</summary>
internal sealed record RuleEntry(string Key, string DisplayName, string Props);
/// <summary>Verified=false: the task was registered but its read-back DACL could not be confirmed. It is journaled so the attempt is on record, but it is never auto-removed or refreshed (orphan cost), and uninstall says so.</summary>
internal sealed record WatchdogEntry(string Task, string Sid, string Sddl, string Exe, bool Verified, string? Note = null);

internal static class LifecycleJournalStore
{
    internal static string FilePath => Path.Combine(ProtectedFile.Dir, "lifecycle.json");

    internal static (MarkerState state, LifecycleJournal? journal) Read(string? path = null)
    {
        var p = path ?? FilePath;
        try
        {
            if (!File.Exists(p)) return (MarkerState.Absent, null);
            if (path == null && !ProtectedFile.DirTrusted(ProtectedFile.Dir)) return (MarkerState.Corrupt, null);
            var j = JsonSerializer.Deserialize<LifecycleJournal>(File.ReadAllText(p));
            if (j != null && j.Version == 1 && j.Rules != null && j.Uncertain != null && j.Uncertain.All(u => u != null && Regex.IsMatch(u.Key ?? "", @"\ABingeMote-[0-9a-f]{32}\z") && !string.IsNullOrEmpty(u.Outcome))
                && j.Rules.All(e => e != null && Regex.IsMatch(e.Key ?? "", @"\ABingeMote-[0-9a-f]{32}\z") && FirewallSetup.JournalableNames.Contains(e.DisplayName) && !string.IsNullOrEmpty(e.Props))
                && (j.Watchdog == null || (WatchdogSetup.SidAcceptable(j.Watchdog.Sid) && j.Watchdog.Task == WatchdogSetup.TaskName)))
                return (MarkerState.Present, j);
            return (MarkerState.Corrupt, null);
        }
        catch { return (MarkerState.Corrupt, null); }   // exists but unreadable: never read as "absent"
    }

    /// <summary>Merge new entries into the journal. Returns false (and logs) when it could not be written; the objects then stay un-journaled, which means they are never deleted.</summary>
    internal static bool Merge(IEnumerable<RuleEntry> rules, bool urlAcl, WatchdogEntry? watchdog, IEnumerable<UncertainEntry>? uncertain = null, string? urlAclUncertain = null)
    {
        try
        {
            var (st, cur) = Read();
            if (st == MarkerState.Corrupt) { Program.Log("lifecycle journal is unreadable or untrusted; not updated. New objects stay un-journaled (never auto-removed)."); return false; }
            var merged = new LifecycleJournal(1,
                (cur?.Rules ?? new List<RuleEntry>()).Concat(rules).DistinctBy(e => e.Key).ToList(),
                (cur?.UrlAcl ?? false) || urlAcl,
                watchdog ?? cur?.Watchdog,
                (cur?.Uncertain ?? new List<UncertainEntry>()).Concat(uncertain ?? Array.Empty<UncertainEntry>()).DistinctBy(u => u.Key).ToList(),
                urlAclUncertain ?? cur?.UrlAclUncertain);
            ProtectedFile.WriteAtomic(FilePath, JsonSerializer.Serialize(merged));
            return true;
        }
        catch (Exception ex) { Program.Log("lifecycle journal not written: " + ex.Message); return false; }
    }

    /// <summary>Rewrite with the given remainder; an empty journal is deleted.</summary>
    internal static bool Replace(LifecycleJournal j)
    {
        try
        {
            if (j.Rules.Count == 0 && !j.UrlAcl && j.Watchdog == null && j.Uncertain.Count == 0 && j.UrlAclUncertain == null) { File.Delete(FilePath); return true; }
            ProtectedFile.WriteAtomic(FilePath, JsonSerializer.Serialize(j));
            return true;
        }
        catch (Exception ex) { Program.Log("lifecycle journal not updated: " + ex.Message); return false; }
    }
}

/// <summary>The per-user relaunch task, registered only by the elevated installer step.
/// Boundary: a standard (non-admin) account, including the task's own user, gets File Read on the
/// task and nothing else, so it should be unable to replace, disable or delete it. That is DESIGN
/// INTENT from the documented ACL semantics, not yet shown at runtime. Any administrator or SYSTEM
/// can still change it. Group rights such as Network Configuration Operators are not examined.
/// The legacy fixed-name task is never read, replaced or deleted.</summary>
internal static class WatchdogSetup
{
    internal const string TaskName = "BingeMote Watchdog (installed)";
    private const int TaskCreate = 0x2, TaskUpdate = 0x4, LogonInteractiveToken = 3, DaclInfo = 4;

    internal static bool SidAcceptable(string? sid) =>
        sid != null && Regex.IsMatch(sid, @"\AS-1-(5-21-\d+-\d+-\d+-\d+|12-1-\d+-\d+-\d+-\d+)\z");

    /// <summary>SYSTEM and Administrators full control; the one target user File Read. Protected: nothing inherited.</summary>
    internal static string BuildSddl(string sid) => "D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FR;;;" + sid + ")";

    internal static string BuildXml(string sid, string exe) =>
        "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n"
        + "<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\n"
        + "  <Principals><Principal id=\"Author\"><UserId>" + sid + "</UserId>"
        + "<LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>\n"
        + "  <Triggers><TimeTrigger><StartBoundary>2020-01-01T00:00:00</StartBoundary>"
        + "<Repetition><Interval>PT5M</Interval></Repetition></TimeTrigger></Triggers>\n"
        + "  <Settings>"
        + "<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>"
        + "<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>"
        + "<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>"
        + "<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>"
        + "<StartWhenAvailable>true</StartWhenAvailable>"
        + "<Priority>4</Priority>"
        + "</Settings>\n"
        + "  <Actions Context=\"Author\"><Exec><Command>" + System.Security.SecurityElement.Escape(exe) + "</Command>"
        + "<Arguments>--ensure-running</Arguments></Exec></Actions>\n"
        + "</Task>";

    /// <summary>ACE-set equality of two SDDL strings (order and SDDL aliases such as SY/BA do not matter). Anything unparseable is unequal.</summary>
    internal static bool SddlEquivalent(string a, string b)
    {
        try
        {
            static HashSet<string>? Aces(string s)
            {
                var d = new RawSecurityDescriptor(s).DiscretionaryAcl;
                if (d == null) return null;
                return d.Cast<GenericAce>().Select(x => x is CommonAce c
                    ? c.AceType + "|" + c.SecurityIdentifier.Value + "|" + c.AccessMask + "|" + c.AceFlags
                    : "other|" + x.AceType).ToHashSet();
            }
            var x1 = Aces(a); var x2 = Aces(b);
            return x1 != null && x2 != null && x1.SetEquals(x2);
        }
        catch { return false; }
    }

    [DllImport("wtsapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool WTSQuerySessionInformation(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out int bytes);
    [DllImport("wtsapi32.dll")]
    private static extern void WTSFreeMemory(IntPtr p);

    private static string? SessionString(int session, int infoClass)
    {
        if (!WTSQuerySessionInformation(IntPtr.Zero, session, infoClass, out var buf, out _)) return null;
        try { return Marshal.PtrToStringUni(buf); } finally { WTSFreeMemory(buf); }
    }

    /// <summary>The user the task will run as is recovered from the actual install context, never assumed: the elevated process's own account must be an administrator AND be the same account that is signed in to this process's own session (WTS user name and domain, resolved to a SID). A different admin account elevating for a standard user, a silent install from session 0, or any lookup failure gives no target and no watchdog.</summary>
    internal static bool TryTargetUser(out string sid, out string why)
    {
        sid = ""; why = "";
        try
        {
            using var id = WindowsIdentity.GetCurrent();
            if (!new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator)) { why = "this step is not elevated"; return false; }
            var mine = id.User?.Value;
            if (!SidAcceptable(mine)) { why = "the installing account is not an ordinary local, domain or work account"; return false; }
            var session = Process.GetCurrentProcess().SessionId;
            var name = SessionString(session, 5);    // WTSUserName
            var domain = SessionString(session, 7);  // WTSDomainName
            if (string.IsNullOrEmpty(name)) { why = "no signed-in user in this install session"; return false; }
            var other = new NTAccount(string.IsNullOrEmpty(domain) ? name : domain + "\\" + name).Translate(typeof(SecurityIdentifier)).Value;
            if (other != mine) { why = "the installing (elevated) account is not the signed-in user of this session"; return false; }
            sid = mine!;
            return true;
        }
        catch (Exception ex) { why = "could not recover the target user: " + ex.Message; return false; }
    }

    internal enum TaskRead { Absent, Present, Unreadable }

    private static TaskRead Read(object folderObj, out string sddl)
    {
        sddl = "";
        try
        {
            dynamic folder = folderObj;
            dynamic t = folder.GetTask(TaskName);
            sddl = (string)t.GetSecurityDescriptor(DaclInfo);
            return TaskRead.Present;
        }
        catch (Exception ex)
        {
            var kind = ClassifyTaskException(ex);
            if (kind == TaskRead.Unreadable) Program.Log("scheduled task read failed (not treated as absent): " + DescribeException(ex));
            return kind;
        }
    }

    /// <summary>Absent only for the "task not found" results (0x80070002 / 0x80070003). A dynamic COM call can surface that HRESULT as a mapped FileNotFoundException/DirectoryNotFoundException rather than a COMException, so the HRESULT is read from the innermost exception whatever its type. A FileNotFoundException that names a file (a missing assembly) is never absence. Everything else is Unreadable.</summary>
    internal static TaskRead ClassifyTaskException(Exception ex)
    {
        var x = ex;
        while (x.InnerException != null) x = x.InnerException;
        int hr = x is COMException ce ? ce.ErrorCode : x.HResult;
        if (hr != unchecked((int)0x80070002) && hr != unchecked((int)0x80070003)) return TaskRead.Unreadable;
        if (x is COMException || x is DirectoryNotFoundException) return TaskRead.Absent;
        if (x is FileNotFoundException f && string.IsNullOrEmpty(f.FileName)) return TaskRead.Absent;
        return TaskRead.Unreadable;
    }

    private static string DescribeException(Exception ex)
    {
        var x = ex;
        while (x.InnerException != null) x = x.InnerException;
        return x.GetType().FullName + " hresult=0x" + (x is COMException ce ? ce.ErrorCode : x.HResult).ToString("X8") + " message=" + x.Message.Replace('\r', ' ').Replace('\n', ' ').Trim();
    }

    /// <summary>Live definition must still be what we registered: one user principal equal to the journaled SID, InteractiveToken, LeastPrivilege, exactly one action running the journaled exe with --ensure-running. Anything unreadable is "no".</summary>
    private static bool IdentityMatches(object folderObj, WatchdogEntry w)
    {
        try
        {
            dynamic folder = folderObj;
            dynamic def = folder.GetTask(TaskName).Definition;
            string userId = (string)def.Principal.UserId;
            string liveSid;
            try { liveSid = new SecurityIdentifier(userId).Value; }
            catch { liveSid = new NTAccount(userId).Translate(typeof(SecurityIdentifier)).Value; }
            if (liveSid != w.Sid) return false;
            if ((int)def.Principal.LogonType != LogonInteractiveToken || (int)def.Principal.RunLevel != 0) return false;   // 0 = TASK_RUNLEVEL_LUA
            if ((int)def.Actions.Count != 1) return false;
            dynamic act = def.Actions.Item(1);
            return string.Equals((string)act.Path, w.Exe, StringComparison.OrdinalIgnoreCase) && (string)act.Arguments == "--ensure-running";
        }
        catch { return false; }
    }

    private static object Folder()
    {
        dynamic svc = Activator.CreateInstance(Type.GetTypeFromProgID("Schedule.Service", true)!)!;
        svc.Connect();
        return (object)svc.GetFolder("\\");
    }

    /// <summary>Elevated install step (--install-watchdog). Create-only under the new name; the legacy task is never read. Exit 0 = registered or already ours and unchanged; 5 = skipped with a logged reason (nothing changed); 6 = failed.</summary>
    internal static int RunInstall()
    {
        try
        {
            if (!TryTargetUser(out var sid, out var why))
            {
                Program.Log("watchdog not registered: " + why + ". No task was created, changed or removed.");
                return 5;
            }
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe) || !Path.IsPathRooted(exe) || !File.Exists(exe)) { Program.Log("watchdog not registered: the app path is unknown."); return 5; }
            var sddl = BuildSddl(sid);
            object folder = Folder();
            var live = Read(folder, out var liveSddl);
            var (js, j) = LifecycleJournalStore.Read();
            var ours = js == MarkerState.Present && j?.Watchdog != null && j.Watchdog.Verified && j.Watchdog.Sid == sid && SddlEquivalent(liveSddl, j.Watchdog.Sddl) && SddlEquivalent(liveSddl, sddl)
                && live == TaskRead.Present && IdentityMatches(folder, j.Watchdog);
            int flags;
            if (live == TaskRead.Absent) flags = TaskCreate;
            else if (live == TaskRead.Present && ours) flags = TaskCreate | TaskUpdate;   // journaled by us and still byte-for-ACE as we left it: refresh the exe path on upgrade
            else { Program.Log("watchdog not registered: a task of this name exists and is not provably ours (" + live + "); left untouched."); return 5; }
            ((dynamic)folder).RegisterTask(TaskName, BuildXml(sid, exe), flags, null, null, LogonInteractiveToken, sddl);
            var after = Read(folder, out var afterSddl);
            var expectedEntry = new WatchdogEntry(TaskName, sid, sddl, exe, true);
            var identityOk = after == TaskRead.Present && IdentityMatches(folder, expectedEntry);   // the same predicate refresh and delete require
            Program.Log("watchdog read-back: " + after + ", access list " + (after == TaskRead.Present && SddlEquivalent(afterSddl, sddl) ? "as expected" : "NOT as expected") + ", principal/action " + (identityOk ? "as expected" : "NOT as expected"));
            if (after != TaskRead.Present || !SddlEquivalent(afterSddl, sddl) || !identityOk)
            {
                // No blind cleanup of what we just created: journal the attempt as unverified so it is on record, never auto-removed, never refreshed.
                // Keep the earlier created-witness fields as history, but mark the CURRENT state uncertain: Verified=false means neither setup nor uninstall will refresh or delete it on the strength of that history.
                var note = "read-back after " + ((flags & TaskUpdate) != 0 ? "update" : "create") + " was " + (after == TaskRead.Present ? (SddlEquivalent(afterSddl, sddl) ? "not the expected principal/action" : "not the expected access list") : after.ToString()) + " at " + DateTime.UtcNow.ToString("O");
                var prior = js == MarkerState.Present ? j?.Watchdog : null;
                var uncertainEntry = prior != null ? prior with { Verified = false, Note = note } : new WatchdogEntry(TaskName, sid, sddl, exe, false, note);
                if (!LifecycleJournalStore.Merge(Array.Empty<RuleEntry>(), false, uncertainEntry))
                    Program.Log("the unverified watchdog attempt could NOT be journaled; it is recorded only here and will never be removed automatically (task " + TaskName + ", user " + sid + ").");
                Program.Log("watchdog registered but its read-back access list is " + (after == TaskRead.Present ? "not the expected one" : "unreadable (" + after + ")") + ". Journaled as UNVERIFIED: it is left in place, not removed or refreshed by setup or uninstall (orphan cost: a task that may run after uninstall until an administrator removes it).");
                return 6;
            }
            if (!LifecycleJournalStore.Merge(Array.Empty<RuleEntry>(), false, expectedEntry))
                Program.Log("the watchdog task was registered and verified but could NOT be journaled; it will never be removed automatically (task " + TaskName + ", user " + sid + ").");
            Program.Log("watchdog registered for the installing user (limited token, admin-only write).");
            return 0;
        }
        catch (Exception ex) { Program.Log("watchdog registration failed: " + ex.Message); return 6; }
    }

    /// <summary>Uninstall: delete only the journaled task while its live access list still equals the journaled one; otherwise leave it and say so. Returns true when nothing journaled remains.</summary>
    internal static bool RemoveJournaled(WatchdogEntry w)
    {
        if (!w.Verified)
        {
            Program.Log("uninstall cleanup: watchdog task left: its registration was never verified (orphan cost; an administrator must remove it if it exists).");
            return false;
        }
        try
        {
            object folder = Folder();
            var live = Read(folder, out var liveSddl);
            if (live == TaskRead.Absent) return true;
            if (live == TaskRead.Present && SddlEquivalent(liveSddl, w.Sddl) && IdentityMatches(folder, w))
            {
                ((dynamic)folder).DeleteTask(TaskName, 0);
                // A returned delete is not removal: the task must read back NotFound before the journal entry is dropped.
                if (Read(folder, out _) == TaskRead.Absent) return true;
                Program.Log("uninstall cleanup: watchdog task delete returned but the task did not read back as absent; left in the journal.");
                return false;
            }
            Program.Log("uninstall cleanup: watchdog task left (" + live + "; access list or principal/exe not provably unchanged).");
            return false;
        }
        catch (Exception ex) { Program.Log("uninstall cleanup: watchdog task not removed: " + ex.Message); return false; }
    }
}
