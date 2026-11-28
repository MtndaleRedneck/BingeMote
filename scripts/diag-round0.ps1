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
  public static string Dump(HashSet<uint> pids) {
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
  $all = Get-CimInstance Win32_Process
  $seen = New-Object 'System.Collections.Generic.HashSet[uint32]'
  $q = New-Object System.Collections.Queue
  $q.Enqueue([uint32]$rootPid); [void]$seen.Add([uint32]$rootPid)
  while ($q.Count -gt 0) {
    $c = $q.Dequeue()
    foreach ($p in $all) { if ($p.ParentProcessId -eq $c -and $seen.Add([uint32]$p.ProcessId)) { $q.Enqueue([uint32]$p.ProcessId) } }
  }
  return $seen
}
function Snap-Tree($rootPid, $label) {
  $ids = Get-Tree $rootPid
  $rows = Get-CimInstance Win32_Process | Where-Object { $ids.Contains([uint32]$_.ProcessId) } |
    ForEach-Object { "  pid=$($_.ProcessId) ppid=$($_.ParentProcessId) name=$($_.Name) path=$($_.ExecutablePath)" }
  "TREE[$label]:`n" + ($rows -join "`n")
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
  $winDump = ''
  $treeBefore = $null
  $deadline = (Get-Date).AddSeconds($boundSec)
  while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 700
    if ($captureWindows -and ($sw.Elapsed.TotalSeconds -gt 6) -and ($sw.Elapsed.TotalSeconds -lt 40)) {
      $ids = Get-Tree $rootPid
      $d = [R0Win]::Dump($ids)
      if ($d -and ($d -ne $winDump)) { $winDump = $d; Write-Host "WINDOWS@$([int]$sw.Elapsed.TotalSeconds)s:`n$d" }
    }
  }
  $timedOut = -not $p.HasExited
  if ($timedOut) {
    Write-Host (Snap-Tree $rootPid "$label pre-kill")
    $treeIds = @(Get-Tree $rootPid)
    & taskkill.exe /T /F /PID $rootPid 2>&1 | ForEach-Object { Write-Host "  taskkill: $_" }
    Start-Sleep -Seconds 3
    $surv = @($treeIds | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
    Write-Host "TIMED OUT after ${boundSec}s; tree ids=$($treeIds -join ','); survivors after kill+3s=$($surv -join ',')"
    Need ($surv.Count -eq 0) "$label no survivors of the installer tree after taskkill /T"
  }
  $rc = if ($p.HasExited) { $p.ExitCode } else { 'NOT-EXITED' }
  Write-Host "RESULT[$label]: exit=$rc timedOut=$timedOut elapsed=$([int]$sw.Elapsed.TotalSeconds)s"
  if ($winDump) { Set-Content -Path (Join-Path $out "$label.windows.txt") -Value $winDump }
  if (Test-Path $log) {
    Write-Host "SETUPLOG[$label] lines=$((Get-Content $log).Count); relevant:"
    Get-Content $log | Select-String -Pattern 'Restart ?Manager|CloseApplications|close the application|in use|Retry|Abort|Ignore|Failed|Exception|Installation process|exit code|Application.*running|locked' |
      Select-Object -First 40 | ForEach-Object { Write-Host "  $($_.Line)" }
  } else { Write-Host "SETUPLOG[$label]: ABSENT (no log written)" }
  return [pscustomobject]@{ Exit = $rc; TimedOut = $timedOut }
}

function New-Target($name) {
  $d = Join-Path $work $name
  if (Test-Path $d) { Remove-Item -Recurse -Force $d }
  New-Item -ItemType Directory -Force $d | Out-Null
  return $d
}
function Report-Target($label, $dir, $payloadSha, $lockSha) {
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
  Need (-not $p.HasExited) "lock holder ($mode) is alive pid=$($p.Id)"
  return $p
}
function Stop-Lock($p) { if ($p -and -not $p.HasExited) { & taskkill.exe /T /F /PID $p.Id | Out-Null }; Start-Sleep -Seconds 1 }

"ACCOUNT/SESSION: whoami=$(whoami) sid=$([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) sessionId=$((Get-Process -Id $PID).SessionId) userInteractive=$([Environment]::UserInteractive) admin=$(([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))"
"OS: $([Environment]::OSVersion.VersionString)  $((Get-CimInstance Win32_OperatingSystem).Caption)"
Need ($Installer -and (Test-Path $Installer)) "shipped installer present at ROUND0_INSTALLER"
$got = Sha $Installer
Need ($got -eq $ExpectedSha) "shipped installer sha256 matches pinned pc-v1.9.1 digest ($got)"
if ($got -ne $ExpectedSha) { "HASH MISMATCH - refusing to run anything"; exit 1 }
"INSTALLER VersionInfo: $((Get-Item $Installer).VersionInfo | Format-List | Out-String)"

# ---- T6-0 control ----
$ctl = New-Target 't60'
$r = Run-Installer 'T6-0-control' "/VERYSILENT /NORESTART /SUPPRESSMSGBOXES /DIR=`"$ctl`"" 120 $false
$payloadSha = Sha (Join-Path $ctl 'BingeMote.exe')
Report-Target 'T6-0-control' $ctl $payloadSha 'n/a'
Need (($r.Exit -eq 0) -and ($payloadSha -ne 'ABSENT')) "control install exit 0 and payload present (harness validity)"
"PAYLOAD sha256 (shipped helper exe) = $payloadSha"
$lockSha = Sha "$env:SystemRoot\System32\PING.EXE"

# ---- T6-a / T6-b / T6-c / T6-d ----
$legs = @(
  @{ n = 'T6-a'; mode = 'image';  args = '/VERYSILENT /NORESTART'; win = $false },
  @{ n = 'T6-b'; mode = 'image';  args = '/VERYSILENT /NORESTART /FORCECLOSEAPPLICATIONS'; win = $false },
  @{ n = 'T6-c'; mode = 'handle'; args = '/VERYSILENT /NORESTART'; win = $false },
  @{ n = 'T6-d'; mode = 'image';  args = '/SILENT /NORESTART'; win = $true }
)
foreach ($l in $legs) {
  $dir = New-Target ($l.n -replace '-', '')
  $lock = Start-Lock $dir $l.mode
  "LOCK[$($l.n)] mode=$($l.mode) holder pid=$($lock.Id) preInstall sha256=$(Sha (Join-Path $dir 'BingeMote.exe'))"
  $res = Run-Installer $l.n ("$($l.args) /DIR=`"$dir`"") 90 $l.win
  "LOCK[$($l.n)] holder alive after installer finished: $(-not $lock.HasExited)"
  Report-Target $l.n $dir $payloadSha $lockSha
  Stop-Lock $lock
  Start-Sleep -Seconds 1
}

"===== SUMMARY ====="
if ($script:fail.Count -gt 0) { "FAILED ASSERTIONS ($($script:fail.Count)):"; $script:fail | ForEach-Object { "  - $_" } } else { 'all assertions ok' }
Get-ChildItem $out | ForEach-Object { "artifact: $($_.Name) $($_.Length)" }
# Observation legs report behavior; only harness-validity assertions fail the job.
if ($script:fail.Count -gt 0) { exit 1 } else { exit 0 }
