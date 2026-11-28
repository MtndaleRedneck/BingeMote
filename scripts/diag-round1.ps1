# diag-round1.ps1 - DIAGNOSTIC ONLY (Round1). Observes the SHIPPED pc-v1.9.1 installer and its REAL helper on an ephemeral GitHub-hosted Windows runner. No product code is built or changed.
# Round0 (surrogate locks) showed RM shutting down windowless holders; Round1 asks the same questions of the REAL
# helper that the shipped [Run] entry starts. Scope of every result: hosted runner, runneradmin, this session; a
# result is an observation, never proof of absence of a race.
#
#  R1-0 control   install into an isolated dir; the shipped [Run] starts the real helper H1. Record helper
#                 PID/session/windows, task/Run/firewall state, payload hash. Required for all later legs.
#  R1-A natural   install over the RUNNING real helper, /VERYSILENT, no FORCECLOSE. Record H1 exit (observed by
#                 exact PID via a cached handle: HasExited/ExitCode), Setup log RM lines, whether the exe entry
#                 was logged as installed, new helper H2 PID and windows. Replacement is judged from the log and
#                 PIDs; the payload hash cannot show it (same payload both times, [Files] has ignoreversion).
#  R1-A2 forced   /FORCECLOSEAPPLICATIONS install-over. RUNS ONLY IF R1-A was not clean (H1 survived, install
#                 failed/timed out, or exe entry not logged installed); otherwise printed as SKIPPED (redundant).
#  R1-U uninstall shipped unins000.exe /VERYSILENT /NORESTART with the real helper still RUNNING (no helper is started
#                 or restarted by the harness; the leg is skipped if none is alive). The shipped installer has
#                 CloseApplications for install only; whether the shipped uninstaller closes, outlives or is blocked
#                 by the helper is the question. Records helper PID/ExitCode, bounded wait on the direct process PLUS a
#                 bounded wait (60s) for self-relaunched uninstaller children carrying the leg's unique /LOG path,
#                 file residue in the target dir, task/Run/firewall/uninstall-key state before and after.
#  HELD OUT (separate decision, NOT in this script): R1-W (clock-aligned deliberate real-watchdog timing intervention).
#  Plain legs here are seconds long; they do NOT exercise the 5-minute watchdog race and a task left registered is
#  not proof it fired. Task LastRunTime/NextRunTime/LastTaskResult and UTC timestamps are recorded before/after
#  each leg so a natural firing inside a leg window would at least be visible (not expected, not evidence if absent).
# Not tested: the marker route, T10, any interactive click, any network capture. Helper network self-update and
# pairing are ungated; their effects are not captured here. Locks are not used in Round1 (real helper only).
# Isolation: /DIR target under RUNNER_TEMP; processes are matched by exact exe path under that root or the leg's
# /LOG path; Watchdog task / HKCU Run are removed only when their path is exactly under the isolated root; the
# scan is bounded and makes no universal zero-descendant claim. Capture errors fail the job. A timed-out leg is
# HARNESS-KILLED, not a natural result. Retained registry/uninstall state: R1-A.. are install-over-existing.
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'ephemeral-runner guard: refusing to run outside GitHub Actions' }
if ($env:GITHUB_REF -ne 'refs/heads/tmp/round1-diag-20261128') { throw 'ref guard: bound to refs/heads/tmp/round1-diag-20261128' }
$ErrorActionPreference = 'Continue'
$script:fail = @()
function Need($cond, $msg) { if ($cond) { Write-Host "ASSERT-OK: $msg" } else { $script:fail += $msg; Write-Host "ASSERT-FAIL: $msg" } }

$ExpectedSha = 'f90535d5458c025ac4c8a472d93d3d9742a51f5daf96b2f0dd9cac7a09cffe6c'  # pc-v1.9.1 asset digest, verified independently against the downloaded bytes
$Installer = $env:ROUND1_INSTALLER
$tmp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$out = Join-Path $tmp 'round1-artifacts'
New-Item -ItemType Directory -Force $out | Out-Null
$work = Join-Path $tmp 'r1work'
New-Item -ItemType Directory -Force $work | Out-Null

Add-Type -TypeDefinition @'
using System; using System.Text; using System.Collections.Generic; using System.Runtime.InteropServices;
public static class R0Win {
  delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc p, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr h, EnumProc p, IntPtr l);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  public static string Dump(int[] pidList) {
    var pids = new HashSet<uint>(); foreach (var x in pidList) pids.Add((uint)x);
    var sb = new StringBuilder();
    EnumProc outer = (h, l) => {
      uint pid; GetWindowThreadProcessId(h, out pid);
      if (!pids.Contains(pid)) return true;
      var t = new StringBuilder(512); var c = new StringBuilder(256);
      GetWindowText(h, t, 512); GetClassName(h, c, 256);
      sb.AppendLine("WIN pid=" + pid + " vis=" + IsWindowVisible(h) + " class=" + c + " title=" + t);
      EnumProc inner = (ch, l2) => {
        var ct = new StringBuilder(2048); var cc = new StringBuilder(256);
        GetWindowText(ch, ct, 2048); GetClassName(ch, cc, 256);
        if (ct.Length > 0) sb.AppendLine("   CHILD class=" + cc + " text=" + ct);
        return true;
      };
      EnumChildWindows(h, inner, IntPtr.Zero);
      return true;
    };
    EnumWindows(outer, IntPtr.Zero);
    return sb.ToString();
  }
}
'@

function Get-Tree([int]$rootPid) {
  # Returns int[] (comma-wrapped so the pipeline does not unroll/collapse it).
  $all = @(Get-CimInstance Win32_Process -ErrorAction Stop)
  $seen = New-Object 'System.Collections.Generic.List[int]'
  $q = New-Object System.Collections.Queue
  $q.Enqueue($rootPid); $seen.Add($rootPid)
  while ($q.Count -gt 0) {
    $c = $q.Dequeue()
    foreach ($p in $all) { if ([int]$p.ParentProcessId -eq $c -and -not $seen.Contains([int]$p.ProcessId)) { $seen.Add([int]$p.ProcessId); $q.Enqueue([int]$p.ProcessId) } }
  }
  return ,($seen.ToArray())
}
function Snap-Tree($rootPid, $label) {
  [int[]]$ids = Get-Tree $rootPid
  $rows = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $ids -contains [int]$_.ProcessId } |
    ForEach-Object { "  pid=$($_.ProcessId) ppid=$($_.ParentProcessId) name=$($_.Name) path=$($_.ExecutablePath)" })
  "TREE[$label]:`n" + ($rows -join "`n")
}
$script:capErr = @()
function Try-Cap($name, [scriptblock]$sb) {
  try { $ErrorActionPreference = 'Stop'; & $sb }
  catch { $m = "CAPTURE-ERROR[$name]: $($_.Exception.Message)"; Write-Host $m; $script:capErr += $m }
}
# taskkill nonzero can be a benign race (process already gone). Flag harness-invalid only if the pid is still alive.
function Check-Kill([string]$what, [int]$procId, [int]$rc) {
  if ($rc -eq 0) { return }
  $alive = [bool](Get-Process -Id $procId -ErrorAction SilentlyContinue)
  if ($alive) { Need $false "HARNESS INVALID: $what taskkill rc=$rc and pid $procId still alive" }
  else { Write-Host "  note: $what taskkill rc=$rc but pid $procId is not running (benign race, verified by liveness)" }
}
# Processes whose exe is under a root, or whose command line contains a marker (case-insensitive).
function Get-Under([string[]]$roots, [string[]]$cmdMarks) {
  $res = @()
  foreach ($p in @(Get-CimInstance Win32_Process -ErrorAction Stop)) {
    if ([int]$p.ProcessId -eq $PID) { continue }
    $hit = $false
    if ($p.ExecutablePath) { foreach ($r in $roots) { if ($p.ExecutablePath.StartsWith(($r.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) { $hit = $true } } }
    if ($p.CommandLine) { foreach ($m in $cmdMarks) { if ($m -and $p.CommandLine.IndexOf($m, [StringComparison]::OrdinalIgnoreCase) -ge 0) { $hit = $true } } }
    if ($hit) { $res += $p }
  }
  return $res
}
function Test-HeldOpen([string]$path) {
  if (-not (Test-Path $path)) { return 'ABSENT' }
  try { $fs = [IO.File]::Open($path, 'Open', 'Write', 'ReadWrite,Delete'); $fs.Close(); return 'open-for-write-OK (not held)' }
  catch [IO.IOException] {
    $hr = $_.Exception.HResult -band 0xFFFF
    if ($hr -eq 32 -or $hr -eq 33) { return "HELD (win32=$hr sharing/lock violation: $($_.Exception.Message))" }
    return "UNKNOWN (IOException win32=${hr}: $($_.Exception.Message))"
  }
  catch { return "UNKNOWN ($($_.Exception.GetType().Name): $($_.Exception.Message))" }
}
# Task state is three-valued. 'absent' only when the Get-ScheduledTask query itself fails with error
# category ObjectNotFound raised by that command (no locale text or error-id matching). Anything else,
# including any Export-ScheduledTask failure after a successful query, is 'unknown'. Full error detail
# (category, error id, command, message) is always printed so the classifier is auditable.
function Get-TaskState([string]$name) {
  # All three cmdlets must exist; Get/Export/Unregister all use the same -TaskPath '\' root; exactly one match required.
  foreach ($c in 'Get-ScheduledTask','Export-ScheduledTask','Unregister-ScheduledTask') {
    if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { return [pscustomobject]@{ State = 'unknown'; Xml = ''; Detail = "cmdlet $c unavailable" } }
  }
  $t = $null
  try { $t = @(Get-ScheduledTask -TaskName $name -TaskPath '\' -ErrorAction Stop) }
  catch {
    $er = $_
    $cat = [string]$er.CategoryInfo.Category
    $cmd = [string]$er.InvocationInfo.MyCommand.Name
    $detail = "category=$cat id=$($er.FullyQualifiedErrorId) command=$cmd type=$($er.Exception.GetType().Name) msg=$($er.Exception.Message)"
    if ($cat -eq 'ObjectNotFound' -and $cmd -eq 'Get-ScheduledTask') { return [pscustomobject]@{ State = 'absent'; Xml = ''; Detail = "query-not-found $detail" } }
    return [pscustomobject]@{ State = 'unknown'; Xml = ''; Detail = "query-error $detail" }
  }
  if ($t.Count -ne 1) { return [pscustomobject]@{ State = 'unknown'; Xml = ''; Detail = "expected exactly 1 task at root path, got $($t.Count)" } }
  try {
    $x = Export-ScheduledTask -TaskName $name -TaskPath '\' -ErrorAction Stop
    return [pscustomobject]@{ State = 'present'; Xml = [string]$x; Detail = "path=$($t[0].TaskPath)" }
  } catch {
    $er = $_
    return [pscustomobject]@{ State = 'unknown'; Xml = ''; Detail = "export-error category=$($er.CategoryInfo.Category) id=$($er.FullyQualifiedErrorId) type=$($er.Exception.GetType().Name) msg=$($er.Exception.Message)" }
  }
}
function Get-State([string]$label, [string]$root, [string]$exePath) {
  Write-Host "----- STATE[$label] -----"
  $sf = Join-Path $out "state.$label.txt"
  $buf = New-Object System.Collections.Generic.List[string]
  $emit = { param($t) Write-Host $t; $buf.Add($t) }
  Try-Cap "$label procs" {
    $ps = @(Get-Under @($root) @())
    & $emit "PROCS-under-root count=$($ps.Count) (root=$root)"
    foreach ($p in $ps) { & $emit "  pid=$($p.ProcessId) ppid=$($p.ParentProcessId) path=$($p.ExecutablePath) cmd=$($p.CommandLine)" }
    $other = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.Name -like 'BingeMote*' -and (-not $_.ExecutablePath -or -not $_.ExecutablePath.StartsWith(($root.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) })
    & $emit "PROCS-BingeMote*-outside-root count=$($other.Count) (reported only, never stopped)"
    foreach ($p in $other) { & $emit "  pid=$($p.ProcessId) path=$($p.ExecutablePath)" }
  }
  Try-Cap "$label exe-held" { if ($exePath) { & $emit "EXE-HELD[$exePath]: $(Test-HeldOpen $exePath)" } }
  Try-Cap "$label task" {
    $ts = Get-TaskState 'BingeMote Watchdog'
    & $emit "TASK 'BingeMote Watchdog' state=$($ts.State) $($ts.Detail)`n$($ts.Xml)"
    if ($ts.State -eq 'present') { $ti = Get-ScheduledTaskInfo -TaskName 'BingeMote Watchdog' -TaskPath '\' -ErrorAction Stop; & $emit "TASKINFO LastRunTime=$($ti.LastRunTime.ToUniversalTime().ToString('o')) NextRunTime=$($ti.NextRunTime.ToUniversalTime().ToString('o')) LastTaskResult=$($ti.LastTaskResult) Missed=$($ti.NumberOfMissedRuns) nowUtc=$((Get-Date).ToUniversalTime().ToString('o'))" }
    if ($ts.State -eq 'unknown') { throw "task state UNKNOWN (not verified absent): $($ts.Detail)" }
  }
  Try-Cap "$label runkey" {
    if (-not (Test-Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run')) { & $emit "RUNKEY HKCU Run key container absent" }
    else {
      $k = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -ErrorAction Stop
      $pr = $k.PSObject.Properties['BingeMote']
      & $emit "RUNKEY HKCU Run BingeMote = $(if ($pr) { $pr.Value } else { '<absent>' })"
    }
  }
  Try-Cap "$label firewall" {
    foreach ($n in 'BingeMote','BingeMote Helper') {
      $o = (& netsh.exe advfirewall firewall show rule name="$n" 2>&1 | Out-String)
      & $emit "FIREWALL[$n] rc=$LASTEXITCODE`n$o"
    }
  }
  Try-Cap "$label uninstallkeys" {
    foreach ($h in 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall','HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall') {
      foreach ($k in @(Get-ChildItem $h -ErrorAction Stop)) {
        $v = Get-ItemProperty $k.PSPath -ErrorAction Stop
        if ($v.DisplayName -like 'BingeMote*') { & $emit "UNINSTALLKEY $($k.PSChildName) DisplayName=$($v.DisplayName) InstallLocation=$($v.InstallLocation)" }
      }
    }
  }
  Set-Content -Path $sf -Value ($buf -join "`n")
}
# Stop what was found by exact path/log marker, rescan (bounded). Not a universal zero-descendant claim.
function Clean-Scoped([string]$label, [string]$root, [string[]]$marks, [int[]]$keepPids, [int]$boundSec) {
  $end = (Get-Date).AddSeconds($boundSec); $script:cEmpty = 0; $script:cKilled = @()
  Try-Cap "$label clean" {
    while ((Get-Date) -lt $end -and $script:cEmpty -lt 3) {
      $ps = @(Get-Under @($root) $marks | Where-Object { $keepPids -notcontains [int]$_.ProcessId })
      if ($ps.Count -eq 0) { $script:cEmpty++ } else {
        $script:cEmpty = 0
        foreach ($p in $ps) { Write-Host "  CLEAN[$label] stop pid=$($p.ProcessId) path=$($p.ExecutablePath)"; $script:cKilled += [int]$p.ProcessId; & taskkill.exe /T /F /PID $p.ProcessId 2>&1 | ForEach-Object { Write-Host "    taskkill: $_" }; Check-Kill "$label cleanup" ([int]$p.ProcessId) $LASTEXITCODE }
      }
      Start-Sleep -Seconds 1
    }
  }
  Need ($script:cEmpty -ge 3) "$label final rescan reached 3 consecutive empty scans (else harness invalid: scoped processes may remain; no clean claim)"
  Write-Host "CLEAN[$label] stopped pids=[$($script:cKilled -join ',')]; final rescan: $($script:cEmpty) consecutive empty scans within ${boundSec}s bound (scope: exe under root or leg /LOG marker; later/other spawns are NOT excluded)"
}
# Remove task/Run only when the registered executable path is exactly under the isolated root
# (root + '\' prefix on the parsed Command / first token; no loose substring identity). A delete is a
# REQUEST: the script re-queries and reports, and does not claim completion (late/pending operations
# on the ephemeral runner are not excluded).
function Test-UnderRoot([string]$exe, [string]$root) {
  if (-not $exe) { return $false }
  $e = [Environment]::ExpandEnvironmentVariables($exe.Trim().Trim('"'))
  return $e.StartsWith(($root.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)
}
function Clean-Persistence([string]$label, [string]$root) {
  Try-Cap "$label persist-task" {
    $ts = Get-TaskState 'BingeMote Watchdog'
    if ($ts.State -eq 'absent') { Write-Host "PERSIST[$label] task verified absent ($($ts.Detail)); nothing to delete" }
    elseif ($ts.State -eq 'unknown') { throw "PERSIST[$label] task state UNKNOWN, NOT deleted and NOT treated as absent: $($ts.Detail)" }
    else {
      $xml = [xml]$ts.Xml
      $cmds = @($xml.GetElementsByTagName('Command') | ForEach-Object { $_.InnerText })
      Write-Host "PERSIST[$label] task Command value(s): $($cmds -join ' | ')"
      if (@($cmds | Where-Object { Test-UnderRoot $_ $root }).Count -gt 0) {
        Unregister-ScheduledTask -TaskName 'BingeMote Watchdog' -TaskPath '\' -Confirm:$false -ErrorAction Stop
        $after = Get-TaskState 'BingeMote Watchdog'
        Write-Host "PERSIST[$label] delete REQUESTED (Command exactly under isolated root); re-query state=$($after.State) (completion not claimed; pending ops not excluded)"
      } else { Write-Host "PERSIST[$label] task NOT deleted (no Command exactly under isolated root)" }
    }
  }
  Try-Cap "$label persist-run" {
    if (-not (Test-Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run')) { Write-Host "PERSIST[$label] Run key container absent"; return }
    $k = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -ErrorAction Stop
    $pr = $k.PSObject.Properties['BingeMote']
    if (-not $pr) { Write-Host "PERSIST[$label] Run value absent" }
    else {
      $v = [string]$pr.Value
      $first = if ($v.TrimStart().StartsWith('"')) { ($v.TrimStart() -split '"')[1] } else { ($v.Trim() -split '\s+')[0] }
      Write-Host "PERSIST[$label] Run value=$v ; parsed exe=$first"
      if (Test-UnderRoot $first $root) { Remove-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name BingeMote -ErrorAction Stop; Write-Host "PERSIST[$label] Run value removed (exe exactly under isolated root)" }
      else { Write-Host "PERSIST[$label] Run value NOT removed (exe not exactly under isolated root)" }
    }
  }
}
function Sha($p) { if (Test-Path $p) { (Get-FileHash $p -Algorithm SHA256).Hash.ToLower() } else { 'ABSENT' } }

# Bounded installer run. Waits on the direct process, kills the whole tree on timeout,
# then enumerates survivors by tree membership captured BEFORE the kill.
function Run-Installer($label, $argsLine, $boundSec, [bool]$captureWindows, $exePath = $Installer) {
  Write-Host "===== $label ====="
  $log = Join-Path $out "$label.setup.log"
  $a = "$argsLine /LOG=`"$log`""
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $p = Start-Process -FilePath $exePath -ArgumentList $a -PassThru
  $null = $p.Handle  # cache the process handle so ExitCode is readable after exit
  $rootPid = $p.Id
  $script:winDump = ''
  $treeBefore = $null
  $deadline = (Get-Date).AddSeconds($boundSec)
  while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 700
    if ($captureWindows -and ($sw.Elapsed.TotalSeconds -gt 6) -and ($sw.Elapsed.TotalSeconds -lt 40)) {
      Try-Cap "$label windows" {
        [int[]]$ids = Get-Tree $rootPid
        $d = [R0Win]::Dump($ids)
        if ($d -and ($d -ne $script:winDump)) { $script:winDump = $d; Write-Host "WINDOWS@$([int]$sw.Elapsed.TotalSeconds)s (top-level titles + child control text only; dialog body/buttons NOT guaranteed):`n$d" }
      }
    }
  }
  $timedOut = -not $p.HasExited
  if ($timedOut) {
    Write-Host (Snap-Tree $rootPid "$label pre-kill")
    [int[]]$treeIds = Get-Tree $rootPid
    & taskkill.exe /T /F /PID $rootPid 2>&1 | ForEach-Object { Write-Host "  taskkill: $_" }
    Check-Kill "$label installer-tree" $rootPid $LASTEXITCODE
    Start-Sleep -Seconds 3
    $surv = @($treeIds | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
    Write-Host "HARNESS-KILLED: TIMED OUT after ${boundSec}s; pre-kill tree ids=$($treeIds -join ','); survivors of that snapshot after kill+3s=$($surv -join ',')"
    Need ($surv.Count -eq 0) "$label no survivors of the pre-kill installer tree after taskkill /T (later/orphan spawns covered only by the scoped rescan)"
    Clean-Scoped "$label post-kill" $script:IsoRoot @($log) @() 20
  }
  $rc = if ($p.HasExited) { $p.ExitCode } else { 'NOT-EXITED' }
  Write-Host "RESULT[$label]: $(if ($timedOut) { 'HARNESS-KILLED (partial state below is NOT a natural installer result)' } else { 'natural exit' }) exit=$rc timedOut=$timedOut elapsed=$([int]$sw.Elapsed.TotalSeconds)s"
  if ($script:winDump) { Set-Content -Path (Join-Path $out "$label.windows.txt") -Value $script:winDump }
  if (Test-Path $log) {
    Write-Host "SETUPLOG[$label] lines=$((Get-Content $log).Count); relevant:"
    Get-Content $log | Select-String -Pattern 'Deleting|Removed|Will delete|Shutting down|Successfully installed|Installing the file|Dest filename|Restart ?Manager|CloseApplications|close the application|in use|Retry|Abort|Ignore|Failed|Exception|Installation process|exit code|Application.*running|locked' |
      Select-Object -First 80 | ForEach-Object { Write-Host "  $($_.Line)" }
  } else { Write-Host "SETUPLOG[$label]: ABSENT (no log written)" }
  return [pscustomobject]@{ Exit = $rc; TimedOut = $timedOut; Log = $log }
}

function New-Target($name) {
  $d = Join-Path $work $name
  if (Test-Path $d) { Remove-Item -Recurse -Force $d }
  New-Item -ItemType Directory -Force $d | Out-Null
  return $d
}
function Report-Target($label, $dir, $payloadSha, $lockSha, $timedOut) {
  if ($timedOut) { Write-Host "FINAL[$label] *** HARNESS-KILLED: files below are the state when the harness killed the installer, not a natural result ***" }
  $exe = Join-Path $dir 'BingeMote.exe'
  $h = Sha $exe
  Write-Host "FINAL[$label] listing:"
  Get-ChildItem $dir -Force -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $($_.Name) $($_.Length)" }
  Write-Host "FINAL[$label] BingeMote.exe sha256=$h  equals-shipped-payload=$($h -eq $payloadSha)  equals-sacrificial-lock=$($h -eq $lockSha)"
  # Partial-copy hint: a leftover *.tmp next to the exe or a size that matches neither side.
  $sz = if (Test-Path $exe) { (Get-Item $exe).Length } else { -1 }
  Write-Host "FINAL[$label] size=$sz (neither payload nor lock => partial/unknown state)"
}
"ACCOUNT/SESSION: whoami=$(whoami) sid=$([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) sessionId=$((Get-Process -Id $PID).SessionId) userInteractive=$([Environment]::UserInteractive) admin=$(([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))"
"OS: $([Environment]::OSVersion.VersionString)  $((Get-CimInstance Win32_OperatingSystem).Caption)"
Need ($Installer -and (Test-Path $Installer)) "shipped installer present at ROUND1_INSTALLER"
$got = Sha $Installer
Need ($got -eq $ExpectedSha) "shipped installer sha256 matches pinned pc-v1.9.1 digest ($got)"
if ($got -ne $ExpectedSha) { "HASH MISMATCH - refusing to run anything"; exit 1 }
"INSTALLER VersionInfo: $((Get-Item $Installer).VersionInfo | Format-List | Out-String)"

function Post-Leg($label, $dir, $lockProc, $log) {
  Get-State "$label.post-install" $dir (Join-Path $dir 'BingeMote.exe')
  $keep = @()
  Clean-Scoped "$label.cleanup" $dir @($log) $keep 20
  Get-State "$label.post-cleanup(pre-persistence-clean)" $dir (Join-Path $dir 'BingeMote.exe')
  Clean-Persistence $label $dir
  # Final scoped scan AFTER persistence cleanup: a watchdog/Run entry could have respawned the helper during the earlier scans.
  Clean-Scoped "$label.final" $dir @($log) $keep 10
  Get-State "$label.final" $dir (Join-Path $dir 'BingeMote.exe')
}

$script:IsoRoot = $work
$script:r1Exe = $null
function Utc() { (Get-Date).ToUniversalTime().ToString('o') }
function Wait-Helper([string]$dir, [int]$sec, [int[]]$notPids) {
  $exe = Join-Path $dir 'BingeMote.exe'
  $end = (Get-Date).AddSeconds($sec)
  while ((Get-Date) -lt $end) {
    $m = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.ExecutablePath -and ($_.ExecutablePath -ieq $exe) -and ($notPids -notcontains [int]$_.ProcessId) })
    if ($m.Count -gt 0) { return $m[0] }
    Start-Sleep -Milliseconds 500
  }
  return $null
}
# Record a real helper by exact PID; returns a Process object with a cached handle so ExitCode is readable later.
function Rec-Helper([string]$label, $wp) {
  $p = [Diagnostics.Process]::GetProcessById([int]$wp.ProcessId)
  $null = $p.Handle
  $p.Refresh()
  Write-Host "HELPER[$label] pid=$($p.Id) path=$($wp.ExecutablePath) cmd=$($wp.CommandLine) session=$($p.SessionId) start=$($p.StartTime.ToUniversalTime().ToString('o')) mainWindowHandle=$($p.MainWindowHandle) title='$($p.MainWindowTitle)'"
  Try-Cap "$label helper-windows" {
    $d = [R0Win]::Dump([int[]]@($p.Id))
    Write-Host "HELPER[$label] top-level windows (titles + child text only; dialog body/buttons NOT guaranteed): $(if ($d) { "`n$d" } else { '<none enumerated>' })"
    Set-Content -Path (Join-Path $out "$label.helper-windows.txt") -Value $d
  }
  return $p
}
# Log-derived replacement evidence for the exe entry of one Setup log.
function Exe-Entry-Result([string]$log, [string]$exe) {
  $lines = @(Get-Content $log)
  $idx = -1
  for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match 'Dest filename:' -and $lines[$i].IndexOf($exe, [StringComparison]::OrdinalIgnoreCase) -ge 0) { $idx = $i; break } }
  if ($idx -lt 0) { return 'exe-entry-not-in-log' }
  $seg = ($lines[$idx..([Math]::Min($idx + 8, $lines.Count - 1))] -join "`n")
  if ($seg -match 'Successfully installed the file') { return 'logged-installed' }
  return 'logged-NOT-installed'
}
function Over-Leg([string]$name, [string]$args1, [string]$dir, $h1, [string]$payloadSha) {
  $exe = Join-Path $dir 'BingeMote.exe'
  Get-State "$name.pre" $dir $exe
  $t0 = Utc
  Write-Host "OVER[$name] installerStartUtc=$t0 H1 pid=$($h1.Id) aliveBefore=$(-not $h1.HasExited)"
  $res = Run-Installer $name "$args1 /DIR=`"$dir`"" 90 $false
  $t1 = Utc
  $h1.Refresh()
  $h1Exited = $h1.HasExited
  $h1Code = if ($h1Exited) { $h1.ExitCode } else { 'n/a' }
  $h2 = $null
  Try-Cap "$name h2" { $script:h2tmp = Wait-Helper $dir 25 @([int]$h1.Id) }
  $h2 = $script:h2tmp; $script:h2tmp = $null
  $entry = Exe-Entry-Result $res.Log $exe
  $shaNow = Sha $exe
  Write-Host "OVER[$name] installerEndUtc=$t1 installerExit=$($res.Exit) timedOut=$($res.TimedOut) H1exited=$h1Exited H1exitCode=$h1Code exeEntry=$entry exeShaEqualsPayload=$($shaNow -eq $payloadSha) newHelperPid=$(if ($h2) { $h2.ProcessId } else { 'NONE' }) (replacement is judged from the log entry and PIDs: the payload hash cannot differ, [Files] has ignoreversion)"
  $h2p = $null
  if ($h2) { $h2p = Rec-Helper "$name.H2" $h2 }
  Get-State "$name.post" $dir $exe
  $clean = ((-not $res.TimedOut) -and ($res.Exit -eq 0) -and $h1Exited -and ($entry -eq 'logged-installed') -and ($null -ne $h2))
  Write-Host "OVER[$name] clean=$clean"
  return [pscustomobject]@{ Clean = $clean; H2 = $h2p; H1Exited = $h1Exited }
}

# ---- R1-0 control: install, then keep the real helper and watchdog running ----
$dir = New-Target 'r1'
$exe = Join-Path $dir 'BingeMote.exe'
Get-State 'R1-0.pre' $dir $exe
$r = Run-Installer 'R1-0-control' "/VERYSILENT /NORESTART /SUPPRESSMSGBOXES /DIR=`"$dir`"" 120 $false
$payloadSha = Sha $exe
Report-Target 'R1-0-control' $dir $payloadSha 'n/a' $r.TimedOut
$controlValid = (($r.Exit -eq 0) -and (-not $r.TimedOut) -and ($payloadSha -ne 'ABSENT'))
Need $controlValid "control install exit 0, not timed out, payload present (harness validity)"
"PAYLOAD sha256 (shipped helper exe) = $payloadSha"
$h1 = $null
if ($controlValid) {
  Try-Cap 'R1-0 h1' { $script:h1tmp = Wait-Helper $dir 25 @() }
  $h1w = $script:h1tmp
  Need ($null -ne $h1w) "real helper started by the shipped [Run] entry (exact exe path under isolated dir)"
  if ($h1w) {
    $h1 = Rec-Helper 'R1-0.H1' $h1w
    Start-Sleep -Seconds 5
    $h1.Refresh()
    Need (-not $h1.HasExited) "real helper H1 pid=$($h1.Id) still alive 5s after start (stable before install-over)"
    Get-State 'R1-0.helper-running' $dir $exe
  }
} else { Write-Host "CONTROL INVALID: install-over legs SKIPPED" }

# ---- R1-A natural install-over (no cleanup between installs; helper and task retained) ----
if ($h1 -and (-not $h1.HasExited)) {
  $A = @(Over-Leg 'R1-A' '/VERYSILENT /NORESTART' $dir $h1 $payloadSha)[-1]
  if ($A.Clean) {
    Write-Host "R1-A2 SKIPPED: R1-A was clean (H1 exited, exe entry logged installed, install exit 0, new helper started); a FORCECLOSEAPPLICATIONS leg would be redundant"
  } else {
    # precondition: a live helper to install over
    $h1b = $null
    if ($A.H2 -and (-not $A.H2.HasExited)) { $h1b = $A.H2 }
    elseif (-not $h1.HasExited) { $h1b = $h1 }
    if ($h1b) { $null = @(Over-Leg 'R1-A2' '/VERYSILENT /NORESTART /FORCECLOSEAPPLICATIONS' $dir $h1b $payloadSha) }
    else { Need $false "R1-A2 SKIPPED: no live helper to install over after R1-A (observation: helper absent after R1-A)" }
  }
} else { Need $false "R1-A SKIPPED: no valid control/helper" }

# ---- R1-U shipped uninstall with the real helper still running ----
$unins = Join-Path $dir 'unins000.exe'
$hu = $null
Try-Cap 'R1-U helper' { $script:hutmp = Wait-Helper $dir 10 @() }
$huw = $script:hutmp
if ($controlValid -and $huw -and (Test-Path $unins)) {
  $hu = Rec-Helper 'R1-U.H' $huw
  Get-State 'R1-U.pre' $dir $exe
  $u0 = Utc
  Write-Host "UNINST[R1-U] startUtc=$u0 helper pid=$($hu.Id) aliveBefore=$(-not $hu.HasExited) exeHeldBefore=$(Test-HeldOpen $exe)"
  $ru = Run-Installer 'R1-U' '/VERYSILENT /NORESTART' 90 $false $unins
  $wEnd = (Get-Date).AddSeconds(60); $kids = @()
  Try-Cap 'R1-U kids' {
    do { $kids = @(Get-Under @() @($ru.Log)); if ($kids.Count -eq 0) { break }; Start-Sleep -Seconds 1 } while ((Get-Date) -lt $wEnd)
  }
  $hu.Refresh()
  Write-Host "UNINST[R1-U] endUtc=$(Utc) directExit=$($ru.Exit) timedOut=$($ru.TimedOut) uninstallerChildrenStillRunningAfterWait=$($kids.Count) helperExited=$($hu.HasExited) helperExitCode=$(if ($hu.HasExited) { $hu.ExitCode } else { 'n/a' }) exeExists=$(Test-Path $exe) exeHeld=$(Test-HeldOpen $exe) dirExists=$(Test-Path $dir)"
  Write-Host "UNINST[R1-U] target dir residue:"
  Get-ChildItem $dir -Force -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $($_.Name) $($_.Length)" }
  Get-State 'R1-U.post' $dir $exe
} else { Need $false "R1-U SKIPPED: no valid control, no live helper to uninstall under, or unins000.exe absent (nothing was started by the harness)" }

# ---- final scoped cleanup AFTER all legs (helper and task intentionally retained until here) ----
Post-Leg 'R1-final' $dir $null ''

"===== SUMMARY ====="
Need ($script:capErr.Count -eq 0) "all state/window captures completed without error ($($script:capErr.Count) capture errors)"
$script:capErr | ForEach-Object { "  $_" }
if ($script:fail.Count -gt 0) { "FAILED ASSERTIONS ($($script:fail.Count)):"; $script:fail | ForEach-Object { "  - $_" } } else { 'all assertions ok' }
Get-ChildItem $out | ForEach-Object { "artifact: $($_.Name) $($_.Length)" }
# Observation legs report behavior; only harness-validity assertions fail the job.
if ($script:fail.Count -gt 0) { exit 1 } else { exit 0 }
