# BingeMote cross-version UPGRADE e2e (Ezzy's Nov 15 report: "1.7.8 round-3 doesn't
# connect at all" after installing over the rev16 build). The main helper-e2e only
# ever reinstalls the SAME build over itself on a clean machine; this one installs
# the REV16 build, pairs a simulated phone, then upgrades to this branch's build and
# proves the paired phone still connects. A third phase documents the foreign-session
# failure signature (a copy started by an elevated installer under a different
# account holds port 8488 unpaired while the user's own copy cannot bind) and proves
# the phone path heals once the foreign copy is gone.
#
# Installer waits have a four-minute deadline and emit their log tails every 15s.
# HTTP, CIM and scheduled-task operations use their own bounds. These are diagnostic
# bounds, not proof that every OS operation or cleanup path cannot hang.
param(
  [Parameter(Mandatory=$true)][string]$OldSetup,   # rev16 installer exe (TEST build, PAIR_CODE_HOOK)
  [Parameter(Mandatory=$true)][string]$NewSetup    # this branch's installer exe (TEST build, PAIR_CODE_HOOK)
)
$ErrorActionPreference = 'Stop'
function Assert($cond, $msg) { if (-not $cond) { throw "ASSERT FAILED: $msg" }; Write-Host "ok: $msg" }

$appExe = Join-Path $env:ProgramFiles 'BingeMote\BingeMote.exe'
$dataDir = Join-Path $env:LOCALAPPDATA 'BingeMote'
$userLog = Join-Path $dataDir 'helper.log'
$sysLog = 'C:\Windows\System32\config\systemprofile\AppData\Local\BingeMote\helper.log'
$env:BINGEMOTE_PAIR_CODE = '424242'

function Invoke-Helper($path, $body, $token) {
  $headers = @{}
  if ($token) { $headers['X-BingeMote-Token'] = $token }
  $r = Invoke-WebRequest -Uri "http://127.0.0.1:8488$path" -Method Post -Body $body -Headers $headers -UseBasicParsing -TimeoutSec 10 -SkipHttpErrorCheck
  [pscustomobject]@{ code = [int]$r.StatusCode; body = $r.Content }
}
function Wait-Listen([int]$seconds) {
  foreach ($i in 1..($seconds * 2)) {
    try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8488); $c.Close(); return $true } catch { Start-Sleep -Milliseconds 500 }
  }
  return $false
}
function Stop-AllCopies {
  Get-CimInstance Win32_Process -OperationTimeoutSec 15 -Filter "Name='BingeMote.exe'" -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
function Set-TaskState($name, $enabled) {
  # schtasks has no timeout knob: bound it in a job.
  $job = Start-Job { param($n, $e) schtasks.exe /Change /TN $n $(if ($e) { '/ENABLE' } else { '/DISABLE' }) 2>&1 | Out-String } -ArgumentList $name, $enabled
  if (-not (Wait-Job $job -Timeout 15)) { Write-Host "WARNING: schtasks /Change $name timed out" }
  Receive-Job $job | Out-Null; Remove-Job $job -Force -ErrorAction SilentlyContinue
}
function ServingCopies {
  # The helper listens through HTTP.sys (HttpListener), so netstat attributes the
  # 8488 socket to PID 4 (System) and GetOwner on it returns nothing - the socket
  # owner can NEVER identify the serving user. Identify the serving copy by the
  # running process instead: BingeMote.exe instances at the installed path, with
  # each one's real account. Callers assert on the account set; Wait-Listen has
  # already proven 8488 is being served.
  @(Get-CimInstance Win32_Process -OperationTimeoutSec 15 -Filter "Name='BingeMote.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -eq $appExe } |
    ForEach-Object {
      $owner = Invoke-CimMethod -InputObject $_ -MethodName GetOwner -OperationTimeoutSec 15
      [pscustomobject]@{ Pid = $_.ProcessId; User = "$($owner.Domain)\$($owner.User)" }
    })
}

function Invoke-Installer([string]$setup, [string]$logName, [switch]$uninstall) {
  $log = Join-Path $env:TEMP $logName
  $args_ = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', ('/LOG="{0}"' -f $log))
  Write-Host "INSTALLER START path=$setup log=$log deadline=240s"
  $p = Start-Process -FilePath (Resolve-Path $setup).Path -ArgumentList $args_ -PassThru
  $clock = [Diagnostics.Stopwatch]::StartNew()
  while (-not $p.HasExited -and $clock.Elapsed.TotalSeconds -lt 240) {
    Start-Sleep -Seconds 15
    $p.Refresh()
    Write-Host "INSTALLER WAIT pid=$($p.Id) elapsed=$([int]$clock.Elapsed.TotalSeconds)s exited=$($p.HasExited)"
    if (Test-Path $log) { Get-Content $log -Tail 18 | ForEach-Object { Write-Host "INSTALLER LOG: $_" } }
  }
  if (-not $p.HasExited) {
    Write-Host 'INSTALLER TIMEOUT: process snapshot before killing the installer tree'
    Get-CimInstance Win32_Process -OperationTimeoutSec 15 -Filter "Name='BingeMote.exe' OR Name='powershell.exe' OR Name='schtasks.exe' OR Name='netsh.exe' OR Name='taskkill.exe'" |
      Select-Object ProcessId, ParentProcessId, SessionId, Name, CommandLine | Format-List | Out-String | Write-Host
    $killer = Start-Process -FilePath "$env:SystemRoot\System32\taskkill.exe" -ArgumentList @('/F','/T','/PID',"$($p.Id)") -PassThru
    if (-not $killer.WaitForExit(10000)) { Write-Host 'WARNING: installer tree kill did not finish within 10s' }
    throw "Installer exceeded 240s: $setup; see emitted installer log and process snapshot"
  }
  Write-Host "INSTALLER EXIT pid=$($p.Id) code=$($p.ExitCode) elapsed=$([int]$clock.Elapsed.TotalSeconds)s"
  return [int]$p.ExitCode
}

try {
  Write-Host "== Phase 1: install the REV16 build and pair a simulated phone =="
  Assert (Test-Path $OldSetup) "rev16 installer exists at $OldSetup"
  Assert (Test-Path $NewSetup) "new installer exists at $NewSetup"
  $exit = Invoke-Installer $OldSetup bm-old-install.log
  Assert ($exit -eq 0) "rev16 silent install exit 0 (got $exit)"
  Assert (Wait-Listen 60) "rev16 build serves 8488 after install"
  $o1 = ServingCopies
  Write-Host "post-install copies at the installed path: $($o1 | ConvertTo-Json -Compress)"
  # Restart under this script's env so the fixed pair code is honored (the installer's
  # own relaunch does not carry it), then pair.
  Stop-AllCopies
  Start-Process $appExe
  Assert (Wait-Listen 60) "rev16 build serves 8488 under the harness env"
  $r = Invoke-Helper '/pair' '{"code":"424242"}' $null
  Assert ($r.code -eq 200) "rev16 pair with fixed code is 200 (got $($r.code): $($r.body))"
  $token = (($r.body | ConvertFrom-Json).token)
  Assert ($token.Length -eq 64) "rev16 pair token is 64 hex chars"
  $r = Invoke-Helper '/ping' '{}' $token
  Assert ($r.code -eq 200) "rev16 paired phone can ping (got $($r.code))"

  Write-Host "== Phase 2: silent upgrade to THIS build - the phone must stay connected =="
  $exit = Invoke-Installer $NewSetup bm-new-install.log
  Assert ($exit -eq 0) "round-3 silent upgrade exit 0 (got $exit)"
  Assert (Wait-Listen 90) "upgraded build serves 8488 (silent install relaunches it as the original user)"
  $o2 = ServingCopies
  Write-Host "post-upgrade copies at the installed path: $($o2 | ConvertTo-Json -Compress)"
  Assert (@($o2 | Where-Object { $_.User -match [regex]::Escape($env:USERNAME) }).Count -ge 1) "post-upgrade serving copy runs as the installing user ($env:USERNAME) (got $($o2 | ConvertTo-Json -Compress))"
  Assert (@($o2 | Where-Object { $_.User -notmatch [regex]::Escape($env:USERNAME) }).Count -eq 0) "no foreign-account copy at the installed path after the silent upgrade (got $($o2 | ConvertTo-Json -Compress))"
  # The relaunched copy does not carry the harness env, but pairing must have
  # SURVIVED the upgrade: the old token must still authenticate.
  $r = Invoke-Helper '/ping' '{}' $token
  Assert ($r.code -eq 200) "paired phone still connects after the rev16 -> round-3 upgrade (got $($r.code): $($r.body))"
  Write-Host "PAIRING SURVIVED THE UPGRADE - the plain rev16 -> round-3 path is not the bug."

  Write-Host "== Phase 3: foreign-session copy - the suspected real-world failure signature =="
  # A hand-run (interactive) install launches {app}\BingeMote.exe ELEVATED; for a
  # standard user elevating with admin credentials that first serving copy lives in
  # the ADMIN's session/profile. Simulate the shape with a SYSTEM copy (same class:
  # foreign session, foreign profile), deterministically:
  #   SYSTEM copy binds 8488 first -> the user's own copy cannot bind
  #   ("Could not start listening" in the USER's helper.log) and the paired token is
  #   401 against the foreign copy's empty profile. Then prove the phone path heals
  #   once the foreign copy is gone.
  Set-TaskState 'BingeMote Watchdog' $false
  Set-TaskState 'BingeMote Input Broker' $false
  Stop-AllCopies
  if (Test-Path $userLog) { Remove-Item $userLog -Force }
  $sysTask = 'BingeMote Foreign Session Test'
  $create = Start-Job { param($e, $t) schtasks.exe /Create /F /TN $t /SC ONCE /ST 00:00 /RU SYSTEM /TR "`"$e`"" 2>&1 | Out-String } -ArgumentList $appExe, $sysTask
  if (-not (Wait-Job $create -Timeout 15)) { throw "schtasks /Create for the foreign-session copy timed out" }
  Receive-Job $create | Out-Null; Remove-Job $create -Force
  $run = Start-Job { param($t) schtasks.exe /Run /TN $t 2>&1 | Out-String } -ArgumentList $sysTask
  if (-not (Wait-Job $run -Timeout 15)) { throw "schtasks /Run for the foreign-session copy timed out" }
  Receive-Job $run | Out-Null; Remove-Job $run -Force
  Assert (Wait-Listen 60) "foreign-session (SYSTEM) copy serves 8488"
  $o3 = ServingCopies
  Write-Host "foreign-session copies at the installed path: $($o3 | ConvertTo-Json -Compress)"
  # 8488 is served (Wait-Listen above) and the user's own copy logs its bind
  # failure below, so the serving copy is the foreign one.
  Assert (@($o3 | Where-Object { $_.User -match 'SYSTEM' }).Count -ge 1) "the foreign copy is running at the installed path (got $($o3 | ConvertTo-Json -Compress))"
  # The user's own copy now cannot bind:
  Start-Process $appExe
  $bindFailed = $false
  foreach ($i in 1..60) {
    if ((Test-Path $userLog) -and ((Get-Content $userLog -Raw -ErrorAction SilentlyContinue) -match 'Could not start listening')) { $bindFailed = $true; break }
    Start-Sleep -Seconds 1
  }
  Assert $bindFailed "the USER's copy logs 'Could not start listening' while the foreign copy holds 8488 (user helper.log: $(if (Test-Path $userLog) { (Get-Content $userLog -Tail 3) -join ' | ' } else { '(none)' }))"
  # And the paired phone is refused by the foreign copy's empty profile:
  $r = Invoke-Helper '/ping' '{}' $token
  Assert ($r.code -eq 401) "paired phone token is 401 against the foreign copy - Ezzy's 'doesn't connect at all' signature (got $($r.code))"
  Write-Host "SIGNATURE DOCUMENTED: foreign-session copy serves 8488 unpaired; user's copy cannot bind; paired phone is refused."
  # Heal: foreign copy gone, the user's copy serves and the old token works again.
  Stop-AllCopies
  $del = Start-Job { param($t) schtasks.exe /Delete /F /TN $t 2>&1 | Out-String } -ArgumentList $sysTask
  if (-not (Wait-Job $del -Timeout 15)) { Write-Host "WARNING: foreign-session task delete timed out" }
  Receive-Job $del | Out-Null; Remove-Job $del -Force -ErrorAction SilentlyContinue
  Start-Process $appExe
  Assert (Wait-Listen 60) "user copy serves 8488 again after the foreign copy is gone"
  $r = Invoke-Helper '/ping' '{}' $token
  Assert ($r.code -eq 200) "paired phone connects again after the foreign copy is gone (healed)"

  Write-Host "== Phase 4: uninstall leaves nothing =="
  Stop-AllCopies
  $unins = Join-Path $env:ProgramFiles 'BingeMote\unins000.exe'
  $exit = Invoke-Installer $unins bm-uninstall.log
  Assert ($exit -eq 0) "uninstall exits 0 (got $exit)"
  Assert (-not (Test-Path $appExe)) "uninstall removed the protected copy"
  Write-Host "UPGRADE E2E PASSED"
} finally {
  foreach ($log in @((Join-Path $env:TEMP 'bm-old-install.log'), (Join-Path $env:TEMP 'bm-new-install.log'), $userLog, $sysLog)) {
    Write-Host "FINAL LOG TAIL: $log"
    if (Test-Path $log) { Get-Content $log -Tail 60 -ErrorAction Continue | Write-Host }
  }
  Stop-AllCopies
  Set-TaskState 'BingeMote Watchdog' $true
  Set-TaskState 'BingeMote Input Broker' $true
  $del = Start-Job { schtasks.exe /Delete /F /TN 'BingeMote Foreign Session Test' 2>&1 | Out-String }
  Wait-Job $del -Timeout 15 | Out-Null; Remove-Job $del -Force -ErrorAction SilentlyContinue
}
