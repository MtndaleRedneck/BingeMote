# diag-round0.ps1 - DIAGNOSTIC ONLY (Round0). Observes the SHIPPED pc-v1.9.1 installer and
# helper on an ephemeral GitHub-hosted Windows runner. No product code is built or changed.
# Observation can expose behavior; it cannot prove absence of a race. Hosted runner is NOT a
# desktop Win10/11 user session; every result is scoped to "hosted runner, this account".
#
#  T6-0  control: unlocked install into an isolated dir; payload hash recorded (harness validity)
#  T6-a  sacrificial RUNNING image locking {app}\BingeMote.exe, /VERYSILENT (bounded)
#  T6-b  same lock + /VERYSILENT /FORCECLOSEAPPLICATIONS (bounded)
#  T6-c  handle-only lock (separate sacrificial child, no mapped image), /VERYSILENT (bounded)
#  T6-d  same lock as T6-a with /SILENT (window-capture only: enumerates the installer tree's
#        windows and child-control text; NO button is clicked; interactive choices are NOT
#        automated and a silent result is never reported as interactive behavior)
#  T10   (shipped-helper marker/event probe) is NOT in this version: held out until the core test
#        is useful.
# LOCK MODEL: the locks are a WINDOWLESS SURROGATE (a renamed ping.exe image, or a pwsh child holding
# an exclusive handle). They are NOT the real tray helper and NOT Restart Manager behavior. Whatever
# the shipped installer does with the real helper is observed only where the control and the
# silent [Run] entry launch it (see below).
# SHIPPED [Run]: the installer's skipifnotsilent entry starts {app}\BingeMote.exe after a silent install.
# No supported Inno switch skips [Run], so the real helper can start from the isolated dir (network
# self-update/pairing are not gated). It is isolated and CAPTURED, never assumed absent: per leg the
# script inventories processes (exact exe path under the isolated root or the leg's unique /LOG path
# on the command line), the 'BingeMote Watchdog' task XML, the HKCU Run value, firewall rules and
# uninstall keys BEFORE and AFTER, then stops what it found by exact path and rescans (bounded).
# Task/Run entries are removed only if they reference the isolated root; nothing else is deleted.
# No claim of zero descendants beyond the bounded scan is made. Capture errors are recorded and fail
# the job. A timed-out leg is labeled HARNESS-KILLED: its final files are not a natural installer result.
# RETAINED STATE: HKLM uninstall registration, shortcuts and firewall rules persist between legs, so
# legs after the control are install-over-existing-registration, not clean-first installs. Observations
# are annotated, not reset. A taskkill failure or an unverified scoped cleanup marks the harness invalid.
# Every installer run uses an isolated /DIR target and a bounded wait; on timeout the whole
# process tree is killed (taskkill /T /F) and survivors are enumerated. Per run: exit code,
# elapsed, tree snapshot, final target listing and SHA-256, Setup log. All artifacts go to
# RUNNER_TEMP/round0-artifacts. Runner-level side effects of the shipped installer are expected
# and accepted on an ephemeral VM: HKCU Run value, shortcuts, firewall rules, uninstall key.
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'ephemeral-runner guard: refusing to run outside GitHub Actions' }
if ($env:GITHUB_REF -ne 'refs/heads/tmp/round0-diag-20261128') { throw 'ref guard: bound to refs/heads/tmp/round0-diag-20261128' }
$ErrorActionPreference = 'Continue'
$script:fail = @()
function Need($cond, $msg) { if ($cond) { Write-Host "ASSERT-OK: $msg" } else { $script:fail += $msg; Write-Host "ASSERT-FAIL: $msg" } }

$ExpectedSha = 'f90535d5458c025ac4c8a472d93d3d9742a51f5daf96b2f0dd9cac7a09cffe6c'  # pc-v1.9.1 asset digest, verified independently against the downloaded bytes
$Installer = $env:ROUND0_INSTALLER
$tmp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$out = Join-Path $tmp 'round0-artifacts'
New-Item -ItemType Directory -Force $out | Out-Null
$work = Join-Path $tmp 'r0work'
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
    if ($ts.State -eq 'unknown') { throw "task state UNKNOWN (not verified absent): $($ts.Detail)" }
  }
  Try-Cap "$label runkey" {
    $k = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -ErrorAction Stop
    $pr = $k.PSObject.Properties['BingeMote']
    & $emit "RUNKEY HKCU Run BingeMote = $(if ($pr) { $pr.Value } else { '<absent>' })"
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
function Run-Installer($label, $argsLine, $boundSec, [bool]$captureWindows) {
  Write-Host "===== $label ====="
  $log = Join-Path $out "$label.setup.log"
  $a = "$argsLine /LOG=`"$log`""
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $p = Start-Process -FilePath $Installer -ArgumentList $a -PassThru
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
    Get-Content $log | Select-String -Pattern 'Restart ?Manager|CloseApplications|close the application|in use|Retry|Abort|Ignore|Failed|Exception|Installation process|exit code|Application.*running|locked' |
      Select-Object -First 40 | ForEach-Object { Write-Host "  $($_.Line)" }
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
function Start-Lock($dir, $mode) {
  # mode 'image': running copy of ping.exe named BingeMote.exe. mode 'handle': file present, held by a
  # separate sacrificial pwsh child with an exclusive handle (no mapped image).
  $exe = Join-Path $dir 'BingeMote.exe'
  Copy-Item "$env:SystemRoot\System32\PING.EXE" $exe -Force
  if ($mode -eq 'image') {
    $p = Start-Process -FilePath $exe -ArgumentList '-t','127.0.0.1' -WindowStyle Hidden -PassThru
  } else {
    $cmd = "`$f=[IO.File]::Open('$exe','Open','Read','None'); Start-Sleep -Seconds 600"
    $p = Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList '-NoProfile','-Command',$cmd -WindowStyle Hidden -PassThru
  }
  Start-Sleep -Seconds 2
  $alive = ($p -and -not $p.HasExited)
  Need $alive "lock holder ($mode) is alive pid=$(if ($p) { $p.Id } else { 'none' })"
  if (-not $alive) { return $null }
  # Positive acquisition gate: alive is not proof the file is held. Require a real sharing/lock violation
  # on a write-open of the target (retry up to ~10s for the child to acquire it).
  $held = ''
  for ($i = 0; $i -lt 10; $i++) { $held = Test-HeldOpen $exe; if ($held -like 'HELD*') { break }; Start-Sleep -Seconds 1 }
  Write-Host "LOCK-ACQUIRE[$mode] $held"
  Need ($held -like 'HELD*') "lock ($mode) positively holds $exe before installer (else leg skipped)"
  if ($held -notlike 'HELD*') { Stop-Lock $p; return $null }
  return $p
}
function Stop-Lock($p) {
  if ($p -and -not $p.HasExited) { & taskkill.exe /T /F /PID $p.Id | Out-Null; Check-Kill 'lock holder' ([int]$p.Id) $LASTEXITCODE }
  Start-Sleep -Seconds 1
  if ($p) { $p.Refresh(); Need $p.HasExited "lock holder pid=$($p.Id) verified exited after stop (else harness invalid)" }
}

"ACCOUNT/SESSION: whoami=$(whoami) sid=$([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) sessionId=$((Get-Process -Id $PID).SessionId) userInteractive=$([Environment]::UserInteractive) admin=$(([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))"
"OS: $([Environment]::OSVersion.VersionString)  $((Get-CimInstance Win32_OperatingSystem).Caption)"
Need ($Installer -and (Test-Path $Installer)) "shipped installer present at ROUND0_INSTALLER"
$got = Sha $Installer
Need ($got -eq $ExpectedSha) "shipped installer sha256 matches pinned pc-v1.9.1 digest ($got)"
if ($got -ne $ExpectedSha) { "HASH MISMATCH - refusing to run anything"; exit 1 }
"INSTALLER VersionInfo: $((Get-Item $Installer).VersionInfo | Format-List | Out-String)"

$script:IsoRoot = $work
Write-Host "LOCK MODEL: windowless surrogate (ping image / pwsh exclusive handle); NOT the real tray helper, NOT Restart Manager behavior."
function Post-Leg($label, $dir, $lockProc, $log) {
  Get-State "$label.post-install" $dir (Join-Path $dir 'BingeMote.exe')
  if ($lockProc) { Stop-Lock $lockProc }
  $keep = @()
  Clean-Scoped "$label.cleanup" $dir @($log) $keep 20
  Get-State "$label.post-cleanup(pre-persistence-clean)" $dir (Join-Path $dir 'BingeMote.exe')
  Clean-Persistence $label $dir
  # Final scoped scan AFTER persistence cleanup: a watchdog/Run entry could have respawned the helper during the earlier scans.
  Clean-Scoped "$label.final" $dir @($log) $keep 10
  Get-State "$label.final" $dir (Join-Path $dir 'BingeMote.exe')
}

# ---- T6-0 control ----
$ctl = New-Target 't60'
Get-State 'T6-0-control.pre' $ctl (Join-Path $ctl 'BingeMote.exe')
$r = Run-Installer 'T6-0-control' "/VERYSILENT /NORESTART /SUPPRESSMSGBOXES /DIR=`"$ctl`"" 120 $false
$payloadSha = Sha (Join-Path $ctl 'BingeMote.exe')
Report-Target 'T6-0-control' $ctl $payloadSha 'n/a' $r.TimedOut
Need (($r.Exit -eq 0) -and ($payloadSha -ne 'ABSENT')) "control install exit 0 and payload present (harness validity)"
"PAYLOAD sha256 (shipped helper exe) = $payloadSha"
$lockSha = Sha "$env:SystemRoot\System32\PING.EXE"
Post-Leg 'T6-0-control' $ctl $null $r.Log
$controlValid = (($r.Exit -eq 0) -and (-not $r.TimedOut) -and ($payloadSha -ne 'ABSENT'))
if (-not $controlValid) { Write-Host "CONTROL INVALID: ALL dependent legs (T6-a..d) are SKIPPED (no result is meaningful without a valid control)" }

# ---- T6-a / T6-b / T6-c / T6-d ----
$legs = @(
  @{ n = 'T6-a'; mode = 'image';  args = '/VERYSILENT /NORESTART'; win = $false },
  @{ n = 'T6-b'; mode = 'image';  args = '/VERYSILENT /NORESTART /FORCECLOSEAPPLICATIONS'; win = $false },
  @{ n = 'T6-c'; mode = 'handle'; args = '/VERYSILENT /NORESTART'; win = $false },
  @{ n = 'T6-d'; mode = 'image';  args = '/SILENT /NORESTART'; win = $true }
)
foreach ($l in $legs) {
  if (-not $controlValid) { Need $false "$($l.n) SKIPPED: control invalid"; continue }
  $dir = New-Target ($l.n -replace '-', '')
  Get-State "$($l.n).pre" $dir (Join-Path $dir 'BingeMote.exe')
  Write-Host "NOTE[$($l.n)]: install-over-existing-registration (control/prior legs left uninstall key, shortcuts, firewall rules); not a clean-first install"
  $lock = Start-Lock $dir $l.mode
  if (-not $lock) {
    Need $false "$($l.n) SKIPPED: lock holder not alive, installer NOT run"
    Clean-Scoped "$($l.n).lock-dead" $dir @() @() 10
    continue
  }
  "LOCK[$($l.n)] mode=$($l.mode) (windowless surrogate) holder pid=$($lock.Id) preInstall sha256=$(Sha (Join-Path $dir 'BingeMote.exe'))"
  Get-State "$($l.n).pre-install(locked)" $dir (Join-Path $dir 'BingeMote.exe')
  $res = Run-Installer $l.n ("$($l.args) /DIR=`"$dir`"") 90 $l.win
  $lock.Refresh()
  "LOCK-VALIDITY[$($l.n)] after installer (recorded, not asserted; installer may legitimately close the holder): holderAlive=$(-not $lock.HasExited) exeHeld=$(Test-HeldOpen (Join-Path $dir 'BingeMote.exe'))"
  Report-Target $l.n $dir $payloadSha $lockSha $res.TimedOut
  Post-Leg $l.n $dir $lock $res.Log
  Start-Sleep -Seconds 1
}

"===== SUMMARY ====="
Need ($script:capErr.Count -eq 0) "all state/window captures completed without error ($($script:capErr.Count) capture errors)"
$script:capErr | ForEach-Object { "  $_" }
if ($script:fail.Count -gt 0) { "FAILED ASSERTIONS ($($script:fail.Count)):"; $script:fail | ForEach-Object { "  - $_" } } else { 'all assertions ok' }
Get-ChildItem $out | ForEach-Object { "artifact: $($_.Name) $($_.Length)" }
# Observation legs report behavior; only harness-validity assertions fail the job.
if ($script:fail.Count -gt 0) { exit 1 } else { exit 0 }
