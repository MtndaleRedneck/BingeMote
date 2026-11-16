# BingeMote Helper runtime tests on a real Windows machine (CI runner).
# Exercises: the elevated firewall/URL-ACL setup, the pairing flow, token auth,
# every input endpoint, the volume endpoints, and UDP discovery.
$ErrorActionPreference = 'Stop'
# rev20 (Instinct 2 round 3): bound every query. Get-CimInstance gets a default 15s
# operation timeout; the scheduled-task cmdlets take no per-call timeout, so they all
# run on one CIM session created with a 15s operation bound. A wedged WMI provider or
# schedule service now fails the affected query instead of hanging the run.
$PSDefaultParameterValues['Get-CimInstance:OperationTimeoutSec'] = 15
try {
    $script:cimSession = New-CimSession -OperationTimeoutSec 15 -ErrorAction Stop
    # Named list, not a wildcard: the New-ScheduledTask* object builders take no
    # -CimSession and a wildcard default would break them at bind time.
    foreach ($cmd in 'Get-ScheduledTask','Get-ScheduledTaskInfo','Enable-ScheduledTask','Disable-ScheduledTask','Start-ScheduledTask','Stop-ScheduledTask','Register-ScheduledTask','Unregister-ScheduledTask','Set-ScheduledTask') {
        $PSDefaultParameterValues["${cmd}:CimSession"] = $script:cimSession
    }
} catch { throw "bounded CIM session unavailable ($($_.Exception.Message)) - FAILING the run instead of falling back to unbounded scheduled-task calls (Instinct 2 first pass, finding 4)" }
$exe = "helper/out/BingeMote.exe"
if (-not (Test-Path $exe)) { throw "helper exe missing at $exe" }

# --- Part 0: the installer owns the firewall rule now: silent install, verify, silent uninstall ---
Write-Host "== installer firewall setup =="
$setup = Get-ChildItem "helper/Output/BingeMote-PC-*.exe" | Select-Object -First 1
if (-not $setup) { throw "installer exe missing in helper/Output" }
& $setup.FullName /VERYSILENT /SUPPRESSMSGBOXES /NORESTART "/LOG=$env:TEMP\bingemote-install.log" | Out-Null
if ($LASTEXITCODE -ne 0) { Get-Content "$env:TEMP\bingemote-install.log" -Tail 60 -ErrorAction SilentlyContinue; throw "silent install failed with exit $LASTEXITCODE" }
$rule = netsh advfirewall firewall show rule name="BingeMote"
if (-not ($rule | Select-String 'BingeMote')) { throw "installer did not add the BingeMote firewall rule" }
$acl = netsh http show urlacl
if (-not ($acl | Select-String ':8488/')) { throw "installer did not add the listen permission" }
Write-Host "ok: installer added the firewall rule and listen permission"

# --- rev14: the SYSTEM task target must live in an admin-only location (Instinct 2 blocker) ---
Write-Host "== rev14 install ACL =="
$appDir = Join-Path $env:ProgramFiles 'BingeMote'
$appExe = Join-Path $appDir 'BingeMote.exe'
if (-not (Test-Path $appExe)) { throw "rev14: install did not land in Program Files ($appExe missing)" }
if (Test-Path (Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\BingeMote.exe')) { throw "rev14: a user-writable copy still installs under LOCALAPPDATA" }
$task = Get-ScheduledTask -TaskName 'BingeMote Input Broker'
$target = $task.Actions[0].Execute
if ($target -ne $appExe) { throw "rev14: broker task target is '$target', expected '$appExe'" }
Write-Host "ok: broker task target is the protected copy: $target"
Write-Host "== ACL evidence: task target directory and exe =="
# rev19 (Instinct 2 trial preconditions): the namespace is protected only if the
# install root AND its ancestors are admin-controlled. Print the chain + path form.
if (-not $appDir.StartsWith($env:ProgramFiles, [StringComparison]::OrdinalIgnoreCase)) { throw "install root is under Program Files (admin-controlled); a user-writable install is outside the trial's YES: $appDir" }
Write-Host "install root: $appDir (exe: $appExe)"
icacls $env:ProgramFiles
icacls $appDir
icacls $appExe
$userAces = (Get-Acl $appExe).Access | Where-Object { $_.IdentityReference.Value -match 'BUILTIN\\Users' }
if (-not $userAces) { throw "rev14: no explicit Users ACE on the task target" }
foreach ($ace in $userAces) {
    if ("$($ace.FileSystemRights)" -ne 'ReadAndExecute, Synchronize') { throw "rev14: Users ACE is '$($ace.FileSystemRights)', expected read+execute only" }
}
Write-Host "ok: Users have read+execute only on the task target"

# Prove it with a real non-admin process: a standard user tries to replace the exe
# and to drop a file beside it; both must be denied. Stop the auto-relaunched app
# first so an exe denial means ACL, not file-in-use.
Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
$low = 'bm-e2e-low'
$lowPass = 'E2e!' + (Get-Random -Minimum 100000 -Maximum 999999)
net user $low $lowPass /add | Out-Null
$probeDir = "$env:PUBLIC\bm-acl-probe"
New-Item -ItemType Directory -Path $probeDir -Force | Out-Null
$probe = "$probeDir\result.txt"
$probeScript = "$probeDir\probe.ps1"
Remove-Item $probe -Force -ErrorAction SilentlyContinue
@'
try { [IO.File]::WriteAllText('<EXE>', 'x'); 'EXE_WRITE_OK' | Out-File '<OUT>' } catch { ("EXE_DENIED: " + $_.Exception.GetType().Name) | Out-File '<OUT>' }
try { [IO.File]::WriteAllText('<DIR>\probe-bm-e2e-low.txt', 'x'); 'DIR_WRITE_OK' | Out-File '<OUT>' -Append } catch { ("DIR_DENIED: " + $_.Exception.GetType().Name) | Out-File '<OUT>' -Append }
'@ -replace '<EXE>', $appExe -replace '<DIR>', $appDir -replace '<OUT>', $probe | Set-Content $probeScript
$sec = ConvertTo-SecureString $lowPass -AsPlainText -Force
$cred = [pscredential]::new($low, $sec)
try {
  $proc = Start-Process powershell.exe -Credential $cred -WindowStyle Hidden -Wait -PassThru `
    -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',$probeScript
  $probeOut = if (Test-Path $probe) { Get-Content $probe -Raw } else { "(probe never wrote; exit=$($proc.ExitCode))" }
} catch {
  $probeOut = "(probe launch failed: $($_.Exception.Message))"
}
Write-Host "== non-admin probe result =="
Write-Host $probeOut
net user $low /delete | Out-Null
Remove-Item $probeDir -Recurse -Force -ErrorAction SilentlyContinue
if ($probeOut -match 'WRITE_OK') { throw "rev14: a standard user wrote to the SYSTEM task target - $probeOut" }
if (-not ($probeOut -match 'EXE_DENIED' -and $probeOut -match 'DIR_DENIED')) { throw "rev14: non-admin probe inconclusive - $probeOut" }
Write-Host "ok: a standard user cannot write or replace the SYSTEM task target"

# rev15: plant a legacy per-user install first so the upgrade must remove it and
# repoint the user's Run entry and Watchdog task at the protected copy.
$legacy = Join-Path $env:LOCALAPPDATA 'Programs\BingeMote'
New-Item -ItemType Directory -Path $legacy -Force | Out-Null
Set-Content (Join-Path $legacy 'BingeMote.exe') 'legacy-stub'
Set-ItemProperty -Path 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run' -Name 'BingeMote' -Value "`"$legacy\BingeMote.exe`" --autostart"
# Upgrade path (P7): a silent LIVE upgrade over the running app must succeed - rev15
# aborted with exit 5 here (RestartManager could not close the tray app and the
# suppressed prompt defaulted to Abort). Start the installed copy, prove it serves,
# then reinstall over it.
$liveApp = Start-Process $appExe -PassThru
$liveUp = $false
foreach ($i in 1..40) {
  try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8488); $c.Close(); $liveUp = $true; break }
  catch { Start-Sleep -Milliseconds 500 }
}
if (-not $liveUp) { throw "installed copy did not start serving before the live upgrade" }
& $setup.FullName /VERYSILENT /SUPPRESSMSGBOXES /NORESTART "/LOG=$env:TEMP\bingemote-upgrade.log" | Out-Null
if ($LASTEXITCODE -ne 0) { Get-Content "$env:TEMP\bingemote-upgrade.log" -Tail 60 -ErrorAction SilentlyContinue; throw "silent LIVE upgrade failed with exit $LASTEXITCODE (P7: rev15 aborted with 5 here)" }
if (-not $liveApp.HasExited) { throw "live upgrade left the old app process running (pid $($liveApp.Id))" }
Write-Host "ok: silent live upgrade over the running app succeeded and replaced the process"
# The silent install relaunches the app; it re-registers the Watchdog with its own
# (protected) path after register-broker.ps1 deleted the stale task.
$wdOk = $false
for ($i = 0; $i -lt 90 -and -not $wdOk; $i++) {
  # rev20: schtasks.exe is an external process with no timeout knob; run it in a job
  # with a hard 15s bound so a wedged schedule service cannot hang the run.
  $wdJob = Start-Job { schtasks.exe /Query /TN "BingeMote Watchdog" /XML 2>$null | Out-String }
  $wdXml = if (Wait-Job $wdJob -Timeout 15) { Receive-Job $wdJob } else { Write-Host "schtasks /Query TIMED OUT after 15s"; '' }
  Remove-Job $wdJob -Force -ErrorAction SilentlyContinue
  if ($wdXml -match [regex]::Escape($appExe)) { $wdOk = $true } else {
    if ($i % 15 -eq 14) { Write-Host "watchdog re-registration still pending after $((($i)+1)*2)s: task $(if ($wdXml.Trim()) { 'exists, action path differs' } else { 'not found' })" }
    Start-Sleep -Seconds 2
  }
}
if (-not $wdOk) {
  Write-Host "== watchdog re-registration diagnostics =="
  Write-Host "last task XML query: $wdXml"
  Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | ForEach-Object { Write-Host "process: pid=$($_.ProcessId) session=$($_.SessionId) cmd=$($_.CommandLine)" }
  $diagLog = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
  if (Test-Path $diagLog) { Write-Host "== helper.log =="; Get-Content $diagLog } else { Write-Host "(no helper.log)" }
  throw "rev15: watchdog task not re-registered at the protected copy"
}
Write-Host "ok: watchdog task re-registered at the protected copy"
# rev20b (run 43788656623: the 5-minute Watchdog fired mid-run at 01:55:01 and
# launched an UNTRACKED --ensure-running UI copy; the rev17 second-launch section
# then signaled that copy to show and its tray-tucked control failed - the second
# time this race went red): registration is proven above, so park the Watchdog for
# the rest of the run. Parking is NOT set-and-forget (Instinct 2 first pass, finding
# 2: the round-3 logs show it back to Ready at 548 and 863): any UI start re-registers
# it ENABLED (RegisterWatchdog does schtasks /Create /F), so the heartbeat watcher
# re-parks it every tick - the drift window is under one 60s tick, not zero. The
# outer finally re-enables it unconditionally and now verifies the restore, and the
# rev19 unidentified-holder test still pause/restores it locally.
Disable-ScheduledTask -TaskName 'BingeMote Watchdog' -ErrorAction SilentlyContinue | Out-Null
Write-Host "ok: watchdog parked (re-parked every heartbeat tick; restored and verified in the outer finally)"

# rev17: install/upgrade must leave the broker UP in the same session - no sign-out,
# and the harness must not start the task itself here. Until rev16 the broker only
# started at the next logon (register-broker.ps1 registered AtLogOn but never ran
# Start-ScheduledTask), which is the exact hole behind Ezzy's midnight rev16 mouse
# failure. Poll the pipe: the broker task must come up on its own after the upgrade.
$pipeUp = $false
for ($i = 0; $i -lt 30 -and -not $pipeUp; $i++) {
  if (Test-Path '\\.\pipe\BingeMoteInputBroker') { $pipeUp = $true } else { Start-Sleep -Seconds 2 }
}
if (-not $pipeUp) {
  Write-Host "== same-session broker diagnostics =="
  try { Get-ScheduledTask -TaskName 'BingeMote Input Broker' | Format-List TaskName,State | Out-String | Write-Host } catch { Write-Host "(no broker task)" }
  Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | ForEach-Object { Write-Host "process: pid=$($_.ProcessId) session=$($_.SessionId) cmd=$($_.CommandLine)" }
  throw "rev17: broker pipe never came up after install+live-upgrade in the same session (no sign-out, no manual task start)"
}
Write-Host "ok: broker pipe is up in the installing session (no sign-out, no manual task start)"
Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
if (-not (Test-Path $appExe)) { throw "rev14: upgrade removed the protected copy" }
$task = Get-ScheduledTask -TaskName 'BingeMote Input Broker'
if ($task.Actions[0].Execute -ne $appExe) { throw "rev14: upgrade changed the task target to $($task.Actions[0].Execute)" }
Write-Host "ok: upgrade re-copies the protected exe and keeps the task target"
if (Test-Path $legacy) { throw "rev15: upgrade left the legacy per-user copy at $legacy" }
$runVal = (Get-ItemProperty -Path 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run' -Name 'BingeMote').BingeMote
if ($runVal -notlike "*$appExe*") { throw "rev15: Run entry not repointed at the protected copy: $runVal" }
Write-Host "ok: upgrade removed the legacy per-user copy and repointed the Run entry"

$unins = Join-Path $env:ProgramFiles 'BingeMote\unins000.exe'
& $unins /VERYSILENT /NORESTART | Out-Null
Start-Sleep -Seconds 3
if (Test-Path $appExe) { throw "rev14: uninstall left the protected copy behind" }
Write-Host "ok: uninstall removed the protected copy"
try { $rule = netsh advfirewall firewall show rule name="BingeMote" 2>$null | Out-String } catch { $rule = '' }
if ($rule -match 'BingeMote') { throw "uninstaller left the firewall rule behind" }
try { $acl = netsh http show urlacl 2>$null | Out-String } catch { $acl = '' }
if ($acl -match ':8488/') { throw "uninstaller left the listen permission behind" }
Write-Host "ok: uninstaller removed the firewall rule and listen permission"

# A silent install now reopens the app on purpose (an update must come back up for him),
# and the silent uninstall can miss that brand-new process. Nothing from the
# install/uninstall phase may still be running once the runtime tests start their own exe.
Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2

# --- Part 1: the one-time elevated setup, run as-is (CI runners are already admin) ---
Write-Host "== install-firewall =="
# The helper is a WinExe, so `&` returns immediately and $LASTEXITCODE stays empty.
$fw = Start-Process $exe -ArgumentList '--install-firewall' -PassThru
Wait-Process -Id $fw.Id -Timeout 60 -ErrorAction SilentlyContinue  # void output; timeout is a suppressed error
if (-not $fw.HasExited) {
  Stop-Process -Id $fw.Id -Force -ErrorAction SilentlyContinue
  throw "install-firewall did not exit within 60s (bounded wait, rev18)"
}
$fwExit = $fw.ExitCode
Write-Host "install-firewall exit code: $fwExit"
netsh advfirewall firewall show rule name="BingeMote"
$aclOut = netsh http show urlacl
$aclOut | Select-String '8488'
if ($fwExit -ne 0) { throw "install-firewall failed with exit $fwExit" }
if (-not ($aclOut | Select-String ':8488/')) { throw "URL ACL for port 8488 missing after setup" }

# --- Part 2: run the helper and drive its HTTP API over loopback ---
Write-Host "== helper runtime =="
$env:BINGEMOTE_PAIR_CODE = '424242'
$p = Start-Process $exe -PassThru
try {
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not start listening on 8488" }
    Write-Host "helper is listening"
    # rev18 (Instinct 2, the unexplained 43689454762-cancelled 32efe77 hang): durable
    # phase/process/task/pipe telemetry that survives a step-timeout kill.
    # rev19 (Instinct 2 follow-up): the hidden child's console output went nowhere -
    # snapshots now go to a FILE and Assert splices them into the run log as they
    # land; the trap prints the rest. The pipe enumeration path was wrong ('\.\pipe'
    # resolved nothing) and its errors were swallowed; both fixed, and the child ends
    # with a final DEADLINE dump.
    # rev20 (Instinct 2 round 3): the workflow uploads the heartbeat file as its own
    # artifact with if: always(), so a step-timeout kill or a job cancel still leaves
    # the snapshots (the run log alone was not an unconditional upload path). The
    # child's own queries are bounded the same way as the main script's (15s). On a
    # hang the run log shows the last live state instead of going dark (a job-level
    # cancel keeps no logs; the 28-min step cap keeps them - the artifact keeps the
    # heartbeat even when the log is lost).
    $heartbeatScript = Join-Path $env:TEMP 'bm-e2e-heartbeat.ps1'
    $heartbeatFile = Join-Path $env:TEMP 'bm-e2e-heartbeat.log'
    Remove-Item $heartbeatFile -Force -ErrorAction SilentlyContinue
    @'
param($OutFile)
function Snap($tag) {
    $lines = @("== heartbeat $tag " + (Get-Date).ToString("HH:mm:ss") + " ==")
    try { $lines += Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" -OperationTimeoutSec 15 -ErrorAction Stop | ForEach-Object {
        "process: pid=" + $_.ProcessId + " session=" + $_.SessionId + " started=" + $_.CreationDate + " cmd=" + $_.CommandLine } } catch { $lines += "process: CIM query failed: $($_.Exception.Message)" }
    $taskParams = @{}
    if ($cs) { $taskParams['CimSession'] = $cs }
    try { $lines += Get-ScheduledTask -TaskName 'BingeMote*' @taskParams -ErrorAction Stop | ForEach-Object {
        $i = $_ | Get-ScheduledTaskInfo @taskParams -ErrorAction SilentlyContinue
        "task: " + $_.TaskName + " state=" + $_.State + " last=" + $(if ($i) { $i.LastTaskResult } else { '?' }) } } catch { $lines += "task: query failed: $($_.Exception.Message)" }
    try { $p = [IO.Directory]::GetFiles('\\.\pipe\') | Where-Object { $_ -match 'BingeMote' }
        if ($p) { $lines += ($p | ForEach-Object { "pipe: " + $_ }) } else { $lines += "pipe: (no BingeMote pipe)" } } catch { $lines += "pipe: enumeration failed: $($_.Exception.Message)" }
    # rev20b follow-up (Instinct 2 first pass, finding 2): any UI start re-registers the
    # Watchdog ENABLED (RegisterWatchdog does schtasks /Create /F), un-parking it mid-run.
    # Re-park every tick so the drift window stays under one heartbeat interval.
    try { Disable-ScheduledTask -TaskName 'BingeMote Watchdog' @taskParams -ErrorAction SilentlyContinue | Out-Null } catch { }
    Add-Content $OutFile $lines
}
$cs = $null
try { $cs = New-CimSession -OperationTimeoutSec 15 -ErrorAction Stop } catch { }
$end = (Get-Date).AddMinutes(26)
while ((Get-Date) -lt $end) { Start-Sleep -Seconds 60; Snap "tick" }
Snap "DEADLINE (final dump)"
'@ | Set-Content $heartbeatScript
    $heartbeat = Start-Process pwsh -ArgumentList '-NoProfile','-File',$heartbeatScript,'-OutFile',$heartbeatFile -PassThru -WindowStyle Hidden
    $script:hbPrinted = 0
    function Sync-Heartbeat {
        # Splice new heartbeat snapshots into the run log (called from Assert, the
        # trap, and the outer finally). The workflow also uploads the raw file as a
        # separate always-on artifact, so the newest snapshots survive even a kill
        # where nothing below runs.
        try {
            if (Test-Path $heartbeatFile) {
                $raw = Get-Content $heartbeatFile -Raw
                if ($raw -and $raw.Length -gt $script:hbPrinted) { Write-Host $raw.Substring($script:hbPrinted).TrimEnd(); $script:hbPrinted = $raw.Length }
            }
        } catch { }
    }
    # On any failure from here on, dump the helper log and process state so CI
    # failures are diagnosable from the run log alone.
    trap {
        Write-Host "== e2e failure: $($_.Exception.Message) =="
        Write-Host "== at: $($_.InvocationInfo.PositionMessage) =="
        Write-Host "== e2e failure diagnostics =="
        $logf = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
        if (Test-Path $logf) { Write-Host "== helper.log =="; Get-Content $logf } else { Write-Host "(no helper.log)" }
        Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe' or Name='UipiBlocker.exe'" | ForEach-Object { Write-Host "process: $($_.Name) pid=$($_.ProcessId) session=$($_.SessionId) cmd=$($_.CommandLine)" }
        Write-Host "== powercfg /requests =="
        powercfg /requests
        $sysLog = 'C:\Windows\System32\config\systemprofile\AppData\Local\BingeMote\helper.log'
        if (Test-Path $sysLog) { Write-Host "== SYSTEM-profile helper.log =="; Get-Content $sysLog }
        Write-Host "== heartbeat snapshots =="
        Sync-Heartbeat
        try { if ($heartbeat -and -not $heartbeat.HasExited) { Stop-Process -Id $heartbeat.Id -Force } } catch { }
        foreach ($tn in 'BingeMote Input Broker','BingeMote UIPI Blocker','BingeMote CI RestrictedOp','BingeMote CI RestrictedLaunch','BingeMote CI Squat','BingeMote CI VerifySquat','BingeMote CI LimitedProbe') {
            try { $ti = Get-ScheduledTaskInfo -TaskName $tn; Write-Host "task ${tn}: LastRunTime=$($ti.LastRunTime) LastTaskResult=$($ti.LastTaskResult)" } catch { Write-Host "task ${tn}: not registered" }
        }
        throw
    }

    function Invoke-Helper($path, $body, $token) {
        $headers = @{}
        if ($token) { $headers['X-BingeMote-Token'] = $token }
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:8488$path" -Method Post -Body $body -Headers $headers -UseBasicParsing -TimeoutSec 10 -SkipHttpErrorCheck
        return @{ code = [int]$r.StatusCode; body = $r.Content }
    }
    function Assert($cond, $msg) { Sync-Heartbeat; if (-not $cond) { throw "ASSERT FAILED: $msg" } ; Write-Host "ok: $msg" }

    # rev20 (Instinct 2 round 3): intentional forced-timeout proof. A stalled query
    # must be abandoned at its bound and the run must keep going. (This proves the
    # abandon path itself; the scheduled-task/CIM calls carry their 15s bound via
    # the session defaults set at the top of the script.)
    Write-Host "== rev20 forced-timeout proof =="
    $stall = Start-Job { Start-Sleep -Seconds 30 }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $hitBound = -not (Wait-Job $stall -Timeout 3)
    Stop-Job $stall -ErrorAction SilentlyContinue; Remove-Job $stall -Force -ErrorAction SilentlyContinue
    $sw.Stop()
    Assert $hitBound "a stalled query is abandoned at its 3s bound instead of running 30s"
    Assert ($sw.Elapsed.TotalSeconds -lt 15) "the bound fired in $([int]$sw.Elapsed.TotalSeconds)s"
    Assert ($PSDefaultParameterValues['Get-CimInstance:OperationTimeoutSec'] -eq 15) "every Get-CimInstance carries a 15s operation bound by default"
    # A REAL bounded query, not a synthetic stall (Instinct 2 first pass, finding 4):
    # a CIM session to a dead endpoint (TEST-NET-1, unroutable - RFC 5737) with a 3s
    # operation bound must abandon the query at the bound, not hang for minutes.
    $deadSw = [System.Diagnostics.Stopwatch]::StartNew()
    $deadErr = $null
    try {
        $dead = New-CimSession -ComputerName '192.0.2.1' -OperationTimeoutSec 3 -ErrorAction Stop
        try { Get-CimInstance Win32_Process -CimSession $dead -ErrorAction Stop | Out-Null } finally { Remove-CimSession $dead -ErrorAction SilentlyContinue }
    } catch { $deadErr = $_.Exception.GetType().Name }
    $deadSw.Stop()
    Assert ($null -ne $deadErr) "a real query against a dead endpoint fails instead of hanging"
    Assert ($deadSw.Elapsed.TotalSeconds -lt 30) "the real dead-endpoint query was abandoned at its 3s operation bound (took $([int]$deadSw.Elapsed.TotalSeconds)s, not minutes)"
    function Stop-Tracked($proc) {
        # rev17: an --ensure-running second copy can win the single-instance race and
        # the tracked process then exits on its own; Stop-Process on that dead pid
        # threw and failed runs whose product behavior was correct (watchdog race).
        if ($null -eq $proc) { return }
        $alive = $false
        try { $alive = -not $proc.HasExited } catch { $alive = $false }
        if ($alive) {
            try { Stop-Process -Id $proc.Id -Force -ErrorAction Stop }
            catch { Write-Host "note: tracked pid $($proc.Id) already gone" }
        }
    }
    function Invoke-HelperGet($path, $token) {
        $headers = @{}
        if ($token) { $headers['X-BingeMote-Token'] = $token }
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:8488$path" -Method Get -Headers $headers -UseBasicParsing -TimeoutSec 10 -SkipHttpErrorCheck
        return @{ code = [int]$r.StatusCode; body = $r.Content }
    }

    $r = Invoke-Helper '/ping' '{}' $null
    Assert ($r.code -eq 401) "ping without token is 401"

    $r = Invoke-Helper '/pair' '{"code":"000000"}' $null
    Assert ($r.code -eq 403) "pair with wrong code is 403"

    $r = Invoke-Helper '/pair' '{"code":"424242"}' $null
    Write-Host "right-code pair -> $($r.code): $($r.body)"
    if ($r.code -ne 200) {
        Get-Process | Where-Object { $_.ProcessName -like '*BingeMote*' } | ForEach-Object { Write-Host "process: $($_.ProcessName) pid=$($_.Id) path=$($_.Path)" }
        $logf = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
        if (Test-Path $logf) { Write-Host "== helper.log =="; Get-Content $logf }
        $cfgf = Join-Path $env:LOCALAPPDATA 'BingeMote\config.json'
        if (Test-Path $cfgf) { Write-Host "== config.json =="; Get-Content $cfgf -Raw }
    }
    Assert ($r.code -eq 200) "pair with right code is 200"
    $paired = $r.body | ConvertFrom-Json
    $token = $paired.token
    Assert ($token.Length -eq 64) "token is 64 hex chars"

    # Automatic-first VLC setup: the code-gated pair response hands over the PC's
    # generated VLC password, and the one-shot adopt fetch behaves exactly once.
    Assert ($paired.vlcPassword.Length -ge 16) "pair response carries the generated VLC password"
    Assert ($paired.vlcPassword -notmatch '\s') "generated password is a clean token"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-1"}' $token
    Assert ($r.code -eq 200) "adopt serves the password once"
    $adopted = ($r.body | ConvertFrom-Json).password
    Assert ($adopted -eq $paired.vlcPassword) "adopted password matches the pair password"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-1"}' $token
    Assert ($r.code -eq 200) "adopt replays for the same request id (lost response)"
    Assert ((($r.body | ConvertFrom-Json).password) -eq $adopted) "replay returns the same password"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-2"}' $token
    Assert ($r.code -eq 409) "a different request id in the retry window conflicts"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-1","ack":true}' $token
    Assert ($r.code -eq 200) "the consumed-ack spends the grant"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-3"}' $token
    Assert ($r.code -eq 410) "after the ack the grant is spent (410)"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-1","ack":true}' $token
    Assert ($r.code -eq 200) "a retried ack is an idempotent no-op"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":""}' $token
    Assert ($r.code -eq 400) "an empty request id is rejected"
    Write-Host "paired with machine: $($paired.name)"
    # Paired phones get stored with a name; a code made outside the window pairs as "Phone N".
    $cfg = Join-Path $env:LOCALAPPDATA 'BingeMote/config.json'
    $cfgText = Get-Content $cfg -Raw
    Assert ($cfgText -match '"name":"Phone 1"') "first paired phone gets a default name: $cfgText"

    $r = Invoke-Helper '/pair' '{"code":"424242"}' $null
    Assert ($r.code -eq 403) "pairing code is single-use"

    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "ping with token is 200"

    $r = Invoke-Helper '/ping' '{}' 'deadbeef'
    Assert ($r.code -eq 401) "ping with wrong token is 401"

    $r = Invoke-Helper '/mouse/move' '{"dx":10,"dy":-5}' $token
    Assert ($r.code -eq 200) "mouse/move"
    $r = Invoke-Helper '/mouse/button' '{"button":"left","down":true}' $token
    Assert ($r.code -eq 200) "mouse/button down"
    $r = Invoke-Helper '/mouse/button' '{"button":"left","down":false}' $token
    Assert ($r.code -eq 200) "mouse/button up"
    $r = Invoke-Helper '/mouse/scroll' '{"dy":-120}' $token
    Assert ($r.code -eq 200) "mouse/scroll"
    $r = Invoke-Helper '/mouse/zoom' '{"delta":0.2}' $token
    Assert ($r.code -eq 200) "mouse/zoom"
    $r = Invoke-Helper '/key' '{"key":"enter"}' $token
    Assert ($r.code -eq 200) "key"
    $r = Invoke-Helper '/type' '{"text":"hi"}' $token
    Assert ($r.code -eq 200) "type"
    # Keep-awake hold, watched at the OS level: engaging it must add a SYSTEM power
    # request for the helper (visible in powercfg /requests), releasing must remove it.
    # The endpoint runs on a listener pool thread, so this also proves the
    # pool-to-UI-thread marshal (execution state is per-thread; a pool-thread hold would
    # not survive reliably). The helper's own 10s timer can re-sync, so check promptly.
    # Section-scoped: the hold is a SYSTEM request, so match BingeMote inside the
    # SYSTEM: section of powercfg /requests output, not anywhere in the text.
    function SystemSection([string]$req) {
        $m = [regex]::Match($req, '(?ms)^SYSTEM:\s*\r?\n(.*?)(?=^[A-Z][A-Z ]+:|\z)')
        if ($m.Success) { $m.Groups[1].Value } else { '' }
    }
    $reqBefore = (powercfg /requests) -join "`n"
    Assert ($reqBefore -notmatch 'BingeMote') "no helper power request before the hold: $reqBefore"
    $r = Invoke-Helper '/ci/hold' '{"on":true}' $null
    Assert ($r.code -eq 200) "ci/hold on"
    Start-Sleep -Seconds 2
    $reqOn = (powercfg /requests) -join "`n"
    Assert ((SystemSection $reqOn) -match 'BingeMote') "hold shows a SYSTEM request: $reqOn"
    $r = Invoke-Helper '/ci/hold' '{"on":false}' $null
    Assert ($r.code -eq 200) "ci/hold off"
    Start-Sleep -Seconds 2
    $reqOff = (powercfg /requests) -join "`n"
    Assert ((SystemSection $reqOff) -notmatch 'BingeMote') "released hold clears the SYSTEM request: $reqOff"

    $r = Invoke-Helper '/nope' '{}' $token
    Assert ($r.code -eq 404) "unknown path is 404"

    $r = Invoke-Helper '/volume/get' '{}' $token
    if ($r.code -eq 200) {
        $v = $r.body | ConvertFrom-Json
        Write-Host "volume/get: volume=$($v.volume) mute=$($v.mute)"
        $r = Invoke-Helper '/volume/set' '{"volume":50}' $token
        Assert ($r.code -eq 200) "volume/set"
        $r = Invoke-Helper '/volume/mute' '{"mute":true}' $token
        Assert ($r.code -eq 200) "volume/mute on"
        $r = Invoke-Helper '/volume/mute' '{"mute":false}' $token
        Assert ($r.code -eq 200) "volume/mute off"
    } else {
        # Headless runners have no audio device; the helper must say so clearly, not crash.
        Assert ($r.code -eq 503) "volume/get without an audio device is 503, got $($r.code)"
        Assert ($r.body -match 'no audio device') "503 body names the reason: $($r.body)"
        Write-Host "no audio device on this machine; 503 path verified"
    }

    # A second copy must exit quietly instead of failing to bind the port.
    Write-Host "== single instance =="
    $second = Start-Process $exe -PassThru
    Wait-Process -Id $second.Id -Timeout 60 -ErrorAction SilentlyContinue  # void output; timeout is a suppressed error
    if (-not $second.HasExited) {
        Stop-Process -Id $second.Id -Force -ErrorAction SilentlyContinue
        throw "second instance did not exit within 60s (bounded wait, rev18)"
    }
    Assert ($second.ExitCode -eq 0) "second instance exits quietly (code $($second.ExitCode))"
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "first instance still serving"

    # After pairing, a fresh launch shows no live code: re-pairing must fail.
    Write-Host "== no silent re-pair =="
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    Remove-Item Env:BINGEMOTE_PAIR_CODE -ErrorAction SilentlyContinue
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart" }
    $r = Invoke-Helper '/pair' '{"code":"424242"}' $null
    Assert ($r.code -eq 403) "no live pair code after pairing (got $($r.code))"
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "saved token still works after restart"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-after-restart"}' $token
    Assert ($r.code -eq 410) "the spent adopt grant stays spent across a helper restart"
    $pObj = Get-Process -Id $p.Id
    # rev12: the rev10 invisible presence window is gone (review blocker B4). The helper
    # must own NO visible window while it serves from the tray/headless. Enumerate its
    # windows and fail on any visible one.
    $winSrc = @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class WinEnum {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, StringBuilder s, int n);
}
"@
    Add-Type -TypeDefinition $winSrc
    $windows = New-Object System.Collections.Generic.List[string]
    $cb = [WinEnum+EnumProc]{ param($h, $l)
        $wpid = 0; [void][WinEnum]::GetWindowThreadProcessId($h, [ref]$wpid)
        if ($wpid -eq $p.Id) {
            $t = New-Object System.Text.StringBuilder 256; [void][WinEnum]::GetWindowTextW($h, $t, $t.Capacity)
            $c = New-Object System.Text.StringBuilder 128; [void][WinEnum]::GetClassNameW($h, $c, $c.Capacity)
                        $windows.Add(('hwnd=0x{0:X} visible={1} class={2} title="{3}"' -f $h.ToInt64(), [WinEnum]::IsWindowVisible($h), $c, $t))
        }
        return $true
    }
    [void][WinEnum]::EnumWindows($cb, [IntPtr]::Zero)
    Write-Host ("helper windows: " + ($windows -join " | "))
    $visibleStrangers = @($windows | Where-Object { $_ -match 'visible=True' })
    Assert ($visibleStrangers.Count -eq 0) "the listener stays bound with no visible UI window (tray-hidden/headless): $($visibleStrangers -join ' | ')"

    # A second phone pairs with a fresh code and both phones stay connected.
    Write-Host "== multi-phone pairing =="
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    $env:BINGEMOTE_PAIR_CODE = '123456'
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart for second pairing" }
    $r = Invoke-Helper '/pair' '{"code":"123456"}' $null
    Assert ($r.code -eq 200) "second phone pairs with a fresh code"
    $token2 = ($r.body | ConvertFrom-Json).token
    Assert ($token2 -ne $token) "second phone gets its own token"
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "first phone still works after second pairs"
    $r = Invoke-Helper '/ping' '{}' $token2
    Assert ($r.code -eq 200) "second phone works"
    $r = Invoke-Helper '/ping' '{}' ('0' * 64)
    Assert ($r.code -eq 401) "unknown token is still 401 with two phones paired"

    # An unacked adopt grant must survive a helper restart from its PERSISTED marker:
    # same request id replays, a different one conflicts, and the ack still spends it.
    Write-Host "== pre-ack adopt survives a restart =="
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-pre-restart"}' $token2
    Assert ($r.code -eq 200) "second phone fetches its VLC password"
    $preRestart = ($r.body | ConvertFrom-Json).password
    $cfgText = Get-Content $cfg -Raw
    Assert ($cfgText -match '"adoptRequestId":"req-pre-restart"') "the served marker is persisted at serve time"
    Assert (-not ($cfgText -match $preRestart)) "the password value is never written to config.json"
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart with an unacked adopt" }
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-pre-restart"}' $token2
    Assert ($r.code -eq 200) "same request id replays after the restart (no re-arm)"
    Assert ((($r.body | ConvertFrom-Json).password) -eq $preRestart) "replay returns the same password after restart"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-different"}' $token2
    Assert ($r.code -eq 409) "a different request id still conflicts after the restart"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-pre-restart","ack":true}' $token2
    Assert ($r.code -eq 200) "the ack still spends the persisted grant"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-after-ack"}' $token2
    Assert ($r.code -eq 410) "the spent grant stays spent"

    # A 1.0/1.1 config stored one token; that phone must stay paired after upgrading.
    Write-Host "== old config migration =="
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    Remove-Item Env:BINGEMOTE_PAIR_CODE -ErrorAction SilentlyContinue
    $cfg = Join-Path $env:LOCALAPPDATA 'BingeMote/config.json'
    Set-Content $cfg ('{"token":"' + $token + '","moviesFolder":null}')
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart on old config" }
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "old single-token config keeps the phone paired"
    $r = Invoke-Helper '/ping' '{}' $token2
    Assert ($r.code -eq 401) "old config no longer carries the second phone (fresh format rewrites on next save)"

    # A vlcrc write deferred because VLC was open must survive a helper restart:
    # the pending value is persisted, and a restarted helper completes it once VLC is gone.
    Write-Host "== pending vlcrc write survives a restart =="
    # Clear vlcrc so the completed write is proof, not a leftover of the first pair's write.
    Remove-Item (Join-Path $env:APPDATA 'vlc\vlcrc') -Force -ErrorAction SilentlyContinue
    Copy-Item "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" (Join-Path $env:TEMP 'vlc.exe') -Force
    $fakeVlc = Start-Process (Join-Path $env:TEMP 'vlc.exe') -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 600' -PassThru
    Start-Sleep -Seconds 2
    Assert (-not $fakeVlc.HasExited) "the simulated VLC process is running"
    try {
        Stop-Tracked $p
        Start-Sleep -Seconds 1
        $env:BINGEMOTE_PAIR_CODE = '555555'
        $p = Start-Process $exe -PassThru
        $up = $false
        foreach ($i in 1..40) {
            try {
                $c = New-Object Net.Sockets.TcpClient
                $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $up) { throw "helper did not restart for the pending-write test" }
        $r = Invoke-HelperGet '/vlc/setup-status' $token
        $ss = $r.body | ConvertFrom-Json
        Assert ($ss.configured -eq $false -and $ss.pending -eq $false) "with vlcrc deleted, setup-status attests neither configured nor pending"
        $r = Invoke-Helper '/pair' '{"code":"555555"}' $null
        Assert ($r.code -eq 200) "third phone pairs while VLC is running"
        $paired3 = $r.body | ConvertFrom-Json
        Assert ($paired3.vlcPending -eq $true) "pair reports the vlcrc write is pending while VLC runs"
        $pendingPw = $paired3.vlcPassword
        Assert ($pendingPw.Length -ge 16) "pair hands over the pending password"
        $cfgText = Get-Content $cfg -Raw
        Assert ($cfgText -match '"pendingVlcPassword"') "the pending write is persisted in config.json"
        Stop-Tracked $p
        Start-Sleep -Seconds 1
        Stop-Process -Id $fakeVlc.Id -Force
        Start-Sleep -Seconds 1
        Remove-Item Env:BINGEMOTE_PAIR_CODE -ErrorAction SilentlyContinue
        $p = Start-Process $exe -PassThru
        $up = $false
        foreach ($i in 1..40) {
            try {
                $c = New-Object Net.Sockets.TcpClient
                $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $up) { throw "helper did not restart after VLC closed" }
        $vlcrc = Join-Path $env:APPDATA 'vlc\vlcrc'
        Assert (Test-Path $vlcrc) "the restarted helper completed the deferred vlcrc write"
        $vlcrcText = Get-Content $vlcrc -Raw
        Assert ($vlcrcText -match "http-password=$pendingPw") "vlcrc has the pending password"
        Assert ($vlcrcText -match 'extraintf=http') "vlcrc enables the web interface"
        Assert ($vlcrcText -match 'http-host=0.0.0.0') "vlcrc listens on the LAN"
        Start-Sleep -Seconds 1
        $cfgText = Get-Content $cfg -Raw
        Assert (-not ($cfgText -match '"pendingVlcPassword":"')) "the completed pending write is cleared from config.json"
        $r = Invoke-HelperGet '/vlc/setup-status' $token
        Assert (($r.body | ConvertFrom-Json).configured -eq $true) "setup-status attests configured after the completed write"

        # Re-pairing while VLC runs with those exact settings must NOT defer: the running
        # VLC read this vlcrc at startup, so control works now - a pending write here would
        # falsely tell the owner a reopen is required.
        $fakeVlc2 = Start-Process (Join-Path $env:TEMP 'vlc.exe') -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 600' -PassThru
        Start-Sleep -Seconds 2
        Assert (-not $fakeVlc2.HasExited) "the simulated VLC is running for the re-pair test"
        Stop-Tracked $p
        Start-Sleep -Seconds 1
        $env:BINGEMOTE_PAIR_CODE = '777777'
        $p = Start-Process $exe -PassThru
        $up = $false
        foreach ($i in 1..40) {
            try {
                $c = New-Object Net.Sockets.TcpClient
                $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $up) { throw "helper did not restart for the re-pair test" }
        $r = Invoke-Helper '/pair' '{"code":"777777"}' $null
        Assert ($r.code -eq 200) "fourth phone pairs while VLC runs with the settings already written"
        Assert ((($r.body | ConvertFrom-Json).vlcPending) -ne $true) "no false pending when vlcrc already has the settings"
        $r = Invoke-HelperGet '/vlc/setup-status' $token
        $ss = $r.body | ConvertFrom-Json
        Assert ($ss.configured -eq $true -and $ss.pending -eq $false) "setup-status attests configured, not pending, while VLC runs with the settings"
        Stop-Process -Id $fakeVlc2.Id -Force -ErrorAction SilentlyContinue
    } finally {
        Get-Process vlc -ErrorAction SilentlyContinue | Stop-Process -Force
    }

    # Movies: the folder is jailed, listed, and a real launch starts VLC.
    Write-Host "== movies =="
    Assert ((Test-Path 'C:\Program Files\VideoLAN\VLC\vlc.exe') -or (Get-Command vlc.exe -ErrorAction SilentlyContinue)) "VLC installed by the workflow step"
    $movies = Join-Path $env:TEMP 'bingemote-e2e-movies'
    New-Item -ItemType Directory -Force $movies | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $movies 'Sub') | Out-Null
    Set-Content (Join-Path $movies 'clip.mkv') 'fake'
    Set-Content (Join-Path $movies 'Sub/inner.mkv') 'fake'
    Set-Content (Join-Path $movies 'notes.txt') 'not a video'
    $folderJson = '{"moviesFolder":"' + ($movies -replace '\\','/') + '"}'
    $r = Invoke-Helper '/config' $folderJson $token
    Assert ($r.code -eq 200) "movies folder set from the phone"

    # Volume pop-up setting: reported by GET, written by POST, defaults on.
    $r = Invoke-HelperGet '/config' $token
    Assert (($r.body | ConvertFrom-Json).volumeOsd -eq $true) "config reports the volume pop-up setting (default on)"
    $r = Invoke-Helper '/config' '{"volumeOsd":false}' $token
    Assert ($r.code -eq 200) "volume pop-up setting written from the phone"
    $r = Invoke-HelperGet '/config' $token
    Assert (($r.body | ConvertFrom-Json).volumeOsd -eq $false) "config reflects the volume pop-up setting off"
    $r = Invoke-Helper '/config' '{"volumeOsd":true}' $token
    $r = Invoke-HelperGet '/config' $token
    Assert (($r.body | ConvertFrom-Json).volumeOsd -eq $true) "config reflects the volume pop-up setting back on"
    $r = Invoke-HelperGet '/movies/list?path=' $token
    Assert ($r.code -eq 200) "list the movies folder root"
    $list = $r.body | ConvertFrom-Json
    Assert ($list.atRoot -eq $true) "root reports atRoot"
    Assert ($list.entries[0].name -eq 'Sub' -and $list.entries[0].dir -eq $true) "folders list first"
    Assert (($list.entries | Where-Object { $_.name -eq 'clip.mkv' }).Count -eq 1) "video file listed"
    Assert (($list.entries | Where-Object { $_.name -eq 'notes.txt' }).Count -eq 0) "non-video files hidden"
    $r = Invoke-HelperGet '/movies/list?path=Sub' $token
    Assert (($r.body | ConvertFrom-Json).entries[0].name -eq 'inner.mkv') "subfolder lists"
    $r = Invoke-HelperGet '/movies/list?path=..' $token
    Assert ($r.code -eq 404) "list outside the folder is refused (got $($r.code))"
    $r = Invoke-Helper '/movies/play' '{"path":"../Windows/win.ini"}' $token
    Assert ($r.code -eq 400) "play outside the folder is refused (got $($r.code))"
    $r = Invoke-Helper '/movies/play' '{"path":"clip.mkv"}' $token
    Assert ($r.code -eq 200) "play starts VLC: $($r.body)"
    $vlcUp = $false
    foreach ($i in 1..20) {
        if (Get-Process vlc -ErrorAction SilentlyContinue) { $vlcUp = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Assert $vlcUp "VLC is running after play"
    Get-Process vlc -ErrorAction SilentlyContinue | Stop-Process -Force

    # c6: a junction inside the jail must not escape it. rev15 exposed an outside
    # listing through one; rev16 resolves the reparse target and jails it.
    $outside = Join-Path $env:TEMP 'bingemote-e2e-outside'
    New-Item -ItemType Directory -Force $outside | Out-Null
    Set-Content (Join-Path $outside 'secret.mkv') 'fake'
    New-Item -ItemType Junction -Path (Join-Path $movies 'escape') -Target $outside | Out-Null
    $r = Invoke-HelperGet '/movies/list?path=escape' $token
    Assert ($r.code -eq 404) "listing through a junction out of the jail is refused (got $($r.code))"
    $r = Invoke-Helper '/movies/play' '{"path":"escape/secret.mkv"}' $token
    Assert ($r.code -eq 400) "playing through a junction out of the jail is refused (got $($r.code))"
    Remove-Item (Join-Path $movies 'escape') -Force

    # c1: a saved resume place survives helper restarts and the no-VLC poll cycle.
    # rev15 rewrote the store every 5 s with no VLC running and a seeded entry
    # became {}.
    Write-Host "== resume history survives restarts (c1) =="
    $storeDir = Join-Path $env:APPDATA 'BingeMote'
    New-Item -ItemType Directory -Force $storeDir | Out-Null
    $store = Join-Path $storeDir 'resume-places.json'
    $movieKey = Join-Path $movies 'clip.mkv'
    foreach ($cycle in 1..5) {
        $pos = 600 * $cycle
        if ($cycle -eq 5) { $pos = 123456789 }  # an extreme valid value, the review's repeat
        $seed = @{ $movieKey = @{ Position = $pos; Length = ($pos + 3000) } }
        ($seed | ConvertTo-Json -Compress -Depth 3) | Set-Content $store -Encoding UTF8
        # Let at least one 5 s no-VLC poll pass with the helper watching: the store
        # must not be rewritten under us.
        Start-Sleep -Seconds 6
        $onDisk = Get-Content $store -Raw
        Assert ($onDisk -match ('"Position":' + $pos + '[^0-9]')) "resume entry survives the no-VLC poll (cycle $cycle)"
        Assert ($onDisk -match 'clip\.mkv') "resume entry still names its file (cycle $cycle)"
        Stop-Tracked $p
        Start-Sleep -Seconds 1
        $p = Start-Process $exe -PassThru
        $up = $false
        foreach ($i in 1..40) {
            try {
                $c = New-Object Net.Sockets.TcpClient
                $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $up) { throw "helper did not restart in resume cycle $cycle" }
        $after = Get-Content $store -Raw
        Assert ($after -match ('"Position":' + $pos + '[^0-9]')) "resume entry survives helper restart $cycle"
    }

    # c5: a fragmented live-mouse message over the 4KB bound is closed, not buffered
    # without limit (rev15 held a 64 MB fragment open, helper memory 17.9 -> 168 MB).
    Write-Host "== ws fragmented message bound (c5) =="
    $cws = [System.Net.WebSockets.ClientWebSocket]::new()
    $cws.Options.SetRequestHeader('X-BingeMote-Token', $token)
    $cws.ConnectAsync([Uri]'ws://127.0.0.1:8488/ws', [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $frag = [Text.Encoding]::UTF8.GetBytes('x' * 2048)
    foreach ($i in 1..4) {
        try {
            $cws.SendAsync([ArraySegment[byte]]$frag, [System.Net.WebSockets.WebSocketMessageType]::Text, [bool]$false, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        } catch { break }  # the server may already have closed after the bound tripped
    }
    $wsClosed = $false
    $wsBuf = New-Object byte[] 256
    foreach ($i in 1..20) {
        try {
            $cts = [Threading.CancellationTokenSource]::new(500)
            $rr = $cws.ReceiveAsync([ArraySegment[byte]]$wsBuf, $cts.Token).GetAwaiter().GetResult()
            if ($rr.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { $wsClosed = $true; break }
        } catch [OperationCanceledException] {
            if ($cws.State -ne [System.Net.WebSockets.WebSocketState]::Open) { $wsClosed = $true; break }
        } catch { $wsClosed = $true; break }  # a faulted socket means the server dropped us
    }
    Assert $wsClosed "the server closes a fragmented message over the 4KB bound (state $($cws.State))"
    try { $cws.Abort() } catch {}
    $cws.Dispose()

    # UDP discovery answers the app's broadcast.
    Write-Host "== UDP discovery =="
    $udp = New-Object Net.Sockets.UdpClient
    $udp.Client.ReceiveTimeout = 5000
    $magic = [Text.Encoding]::UTF8.GetBytes('BINGEMOTE_DISCOVER_V1')
    $udp.Send($magic, $magic.Length, '127.0.0.1', 8489) | Out-Null
    $ep = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
    $reply = [Text.Encoding]::UTF8.GetString($udp.Receive([ref]$ep))
    $udp.Close()
    Assert ($reply -like 'BINGEMOTE_HERE_V1|*') "discovery reply: $reply"

    # A copy launched from a different folder (an "installed" copy vs a Downloads copy)
    # takes over: the running copy quits, the new one serves the same token.
    Write-Host "== update takeover across paths =="
    $dir2 = Join-Path $env:TEMP "bingemote-e2e-takeover"
    New-Item -ItemType Directory -Force $dir2 | Out-Null
    Copy-Item $exe $dir2 -Force
    $exe2 = Join-Path $dir2 "BingeMote.exe"
    $p2 = Start-Process $exe2 -PassThru
    $oldExited = $p.WaitForExit(25000)
    Assert $oldExited "running copy quits when a copy from another folder launches"
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "taking-over copy did not start listening on 8488" }
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "taking-over copy serves the saved token"
    $p = $p2

    # --- rev11: SYSTEM input broker + UIPI replication (Ezzy's SwUSB mouse-freeze scenario) ---
    Write-Host "== rev11 input broker =="
    # rev12: the broker verifies that a client runs its OWN image path, and the helper
    # verifies the broker the same way. The takeover test above left a TEMP COPY of the
    # exe running as the helper, which (correctly) fails that check - restart the helper
    # from the original path, like a real install where broker and helper are one exe.
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart from the original path for the broker tests" }
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "saved token still works on the original-path helper"
    $probeSrc = @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class Probe {
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll", SetLastError=true)] static extern uint SendInput(uint n, INPUT[] i, int cb);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string cls, string title);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowEx(IntPtr parent, IntPtr after, string cls, string title);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern IntPtr SendMessage(IntPtr h, uint msg, IntPtr w, StringBuilder l);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public MI mi; }
    [StructLayout(LayoutKind.Sequential)] struct MI { public int dx, dy; public uint data, flags, time; public IntPtr extra; }
    public static bool SendMove(int dx, int dy) {
        var a = new INPUT[1]; a[0].type = 0; a[0].mi.dx = dx; a[0].mi.dy = dy; a[0].mi.flags = 0x0001;
        return SendInput(1, a, Marshal.SizeOf<INPUT>()) == 1;
    }
    public static POINT Pos() { POINT p; GetCursorPos(out p); return p; }
    public static uint ForegroundPid() { uint pid; GetWindowThreadProcessId(GetForegroundWindow(), out pid); return pid; }
    public static IntPtr NotepadWindow() { return FindWindow("Notepad", null); }
    public static bool FocusNotepad() { var w = NotepadWindow(); return w != IntPtr.Zero && SetForegroundWindow(w); }
    public static string NotepadText() {
        var w = NotepadWindow(); if (w == IntPtr.Zero) return null;
        var e = FindWindowEx(w, IntPtr.Zero, "Edit", null); if (e == IntPtr.Zero) return null;
        var sb = new StringBuilder(8192);
        SendMessage(e, 0x000D, (IntPtr)sb.Capacity, sb); // WM_GETTEXT
        return sb.ToString();
    }
}
"@
    Add-Type -TypeDefinition $probeSrc

    # Register and start the broker with the exact script the installer ships.
    & powershell -NoProfile -ExecutionPolicy Bypass -File helper/register-broker.ps1 -ExePath (Resolve-Path $exe).Path
    Start-ScheduledTask -TaskName 'BingeMote Input Broker'
    $harnessSession = (Get-Process -Id $PID).SessionId
    Write-Host "harness session: $harnessSession"
    $pipeUp = $false
    foreach ($i in 1..40) { if (Test-Path '\\.\pipe\BingeMoteInputBroker') { $pipeUp = $true; break }; Start-Sleep -Milliseconds 500 }
    if (-not $pipeUp) {
        $ti = Get-ScheduledTaskInfo -TaskName 'BingeMote Input Broker'
        Write-Host "broker task: LastRunTime=$($ti.LastRunTime) LastTaskResult=$($ti.LastTaskResult)"
        Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | ForEach-Object { Write-Host "process: pid=$($_.ProcessId) session=$($_.SessionId) cmd=$($_.CommandLine)" }
        $sysLog = 'C:\Windows\System32\config\systemprofile\AppData\Local\BingeMote\helper.log'
        if (Test-Path $sysLog) { Write-Host "== SYSTEM-profile helper.log =="; Get-Content $sysLog } else { Write-Host "(no SYSTEM-profile helper.log)" }
        Write-Host "== recent Application event log (crash evidence) =="
        Get-WinEvent -FilterHashtable @{ LogName = 'Application'; StartTime = (Get-Date).AddMinutes(-10) } -ErrorAction SilentlyContinue |
            Where-Object { $_.ProviderName -match '\.NET|Application Error|Windows Error Reporting' } |
            Select-Object -First 8 | ForEach-Object { Write-Host ("event: " + $_.TimeCreated + " " + $_.ProviderName + " id=" + $_.Id + " :: " + ($_.Message -split "`n" | Select-Object -First 12 -join " | ")) }
    }
    Assert $pipeUp "input broker pipe is listening"
    # The task starts in session 0 as SYSTEM; the broker relaunches itself into the
    # console session (the psexec -s -i pattern). Find that session copy.
    $brokerProc = $null
    foreach ($i in 1..40) {
        $brokerProc = Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker' -and $_.SessionId -eq $harnessSession } | Select-Object -First 1
        if ($brokerProc) { break }
        Start-Sleep -Milliseconds 500
    }
    Assert ($null -ne $brokerProc) "broker relaunched into the console session $harnessSession (not stuck in session 0)"
    $brokerOwner = Invoke-CimMethod -InputObject $brokerProc -MethodName GetOwner
    Assert ($brokerOwner.User -eq 'SYSTEM') "broker runs as SYSTEM (got $($brokerOwner.Domain)\$($brokerOwner.User))"

    # Phone input routes through the broker and the cursor actually moves.
    $b0 = [Probe]::Pos()
    $r = Invoke-Helper '/mouse/move' '{"dx":60,"dy":0}' $token
    Assert ($r.code -eq 200) "mouse/move with broker present"
    Start-Sleep -Milliseconds 400
    $a0 = [Probe]::Pos()
    Assert ([math]::Abs($a0.X - $b0.X) -ge 40) "cursor moved through the broker ($($b0.X) -> $($a0.X))"
    $helperLog = Get-Content (Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log') -Raw
    Assert ($helperLog -match 'input_routing=broker') "helper log confirms broker routing"

    # --- rev17: a NON-ELEVATED client must be able to verify and use the SYSTEM broker ---
    # Ezzy's PC (Nov 15 2026): the app runs with his UAC-filtered token (Administrators
    # deny-only); rev16's PipePeer.OpenProcess on the SYSTEM broker got win32=5, every op
    # fell back to local SendInput, and the mouse lost to elevated windows. The always-admin
    # runner cannot see that, so this runs one op through the real InputBrokerClient under
    # a restricted token with the same shape (Administrators deny-only, minimal privileges).
    Write-Host "== rev17 non-elevated broker client =="
    # Token shaping via Task Scheduler: a task running as the current interactive user at
    # RunLevel Limited gets a genuine UAC-filtered token (Administrators deny-only) - the
    # exact shape of Ezzy's token. Process-level shaping from the runner's interactive
    # admin token is unreliable (CI: CreateProcessAsUser win32=5). LogonType Interactive
    # needs no password and lands in this session, so the broker's same-session peer
    # check applies exactly as it does for Ezzy.
    $ciUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $limited = New-ScheduledTaskPrincipal -UserId $ciUser -LogonType Interactive -RunLevel Limited
    # rev19 correction (runs 43704201857/43706117121/43708763402): on THIS runner a
    # Limited-runlevel task does NOT produce a UAC-filtered token - whoami shows HIGH
    # integrity, Administrators ENABLED, and SeDebugPrivilege present-but-disabled.
    # The first OpenProcess on the SYSTEM broker is still denied (its DACL grants only
    # SYSTEM/owner-RC/its service SID and its SI label is no-read-up). OBSERVED in
    # run 43708763402: the SAME token's OpenProcess flipped denied->allowed mid-process
    # after a Process.GetProcessById call. HYPOTHESIS (not proven): a .NET Process API
    # enabled the token's present-but-disabled SeDebugPrivilege - consistent with the
    # round-23 privilege dump but not the only possible contributor.
    # rev19 token shaping, FINAL state after six refused mechanisms (rounds 12-23):
    # a genuine standard user cannot CONNECT (the broker pipe DACL grants the console
    # user only - correct product behavior); RunLevel-Limited tasks are unfiltered;
    # CreateRestrictedToken is denied even for a clone (win32=5); Safer is dead
    # (win32=31); explorer's shell token is HIGH IL (this image has UAC DISABLED);
    # AdjustTokenPrivileges SE_PRIVILEGE_REMOVED is refused (win32=1300). The probe
    # therefore runs as the console user, tries every shaping mechanism via
    # --restrict-self, RECORDS each refusal (restrict-self-path in the token dump),
    # and proceeds unshaped when all are refused; the asserts below are strict when
    # shaping lands and print explicit LIMITATION lines when it does not.
    $opOut = Join-Path $env:TEMP 'bingemote-broker-op.txt'
    Remove-Item $opOut -Force -ErrorAction SilentlyContinue
    # Sentinel first: prove a Limited-runlevel task executes at all on this runner
    # (run 43683959884: the op task produced no result file in 60s - never ran or died
    # before Main; this tells us which).
    $sentinel = Join-Path $env:TEMP 'bingemote-limited-probe.txt'
    Remove-Item $sentinel -Force -ErrorAction SilentlyContinue
    $probeAction = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument ('/c echo ran> "{0}"' -f $sentinel)
    Register-ScheduledTask -TaskName 'BingeMote CI LimitedProbe' -Action $probeAction -Principal $limited -Force | Out-Null
    Start-ScheduledTask -TaskName 'BingeMote CI LimitedProbe'
    $sentDeadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path $sentinel) -and (Get-Date) -lt $sentDeadline) { Start-Sleep -Milliseconds 500 }
    $sentInfo = Get-ScheduledTaskInfo -TaskName 'BingeMote CI LimitedProbe'
    Write-Host "limited-task sentinel: $(if (Test-Path $sentinel) { 'ran' } else { 'NEVER RAN' }) (LastRunTime=$($sentInfo.LastRunTime) LastTaskResult=$($sentInfo.LastTaskResult))"
    Unregister-ScheduledTask -TaskName 'BingeMote CI LimitedProbe' -Confirm:$false
    $exeAbs = (Resolve-Path $exe).Path  # Task Scheduler resolves the action path itself; a relative path fails with 0x80070002 (run 43684647851)
    # The standard probe user needs read+execute on the build-output exe tree.
    & icacls.exe (Split-Path $exeAbs) /grant 'BUILTIN\Users:(OI)(CI)(RX)' /T | Out-Null
    function Run-LimitedTask($name, $arguments, $outFile, $timeoutSec) {
        # Runs the probe as the CONSOLE user via a Limited-runlevel scheduled task
        # (the only way into the interactive session from the CI job). The token is
        # NOT filtered on this image - shaping is attempted in-process by the probe
        # itself (--restrict-self); see the comment block above.
        # (round 12: task never ran, LastTaskResult 0x41303).
        Remove-Item $outFile -Force -ErrorAction SilentlyContinue
        $soF = $outFile + '.stdout.txt'; $seF = $outFile + '.stderr.txt'
        Remove-Item $soF, $seF -Force -ErrorAction SilentlyContinue
        $p = Start-Process -FilePath $exeAbs -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $soF -RedirectStandardError $seF
        $dl = (Get-Date).AddSeconds($timeoutSec)
        while (-not (Test-Path $outFile) -and -not $p.HasExited -and (Get-Date) -lt $dl) { Start-Sleep -Milliseconds 500 }
        Start-Sleep -Milliseconds 500 # output-file flush margin
        # A hook that produced its result file is DONE: give it a grace window to
        # exit on its own (killing it mid-flush fakes exit -1 and can truncate the
        # stdout evidence - round 14). Kill only a genuinely wedged child.
        if (Test-Path $outFile) { [void]$p.WaitForExit(15000) }
        if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
        Write-Host "${name}: exited=$($p.HasExited) exit=$(if ($p.HasExited) { $p.ExitCode } else { 'killed' })"
        if (Test-Path $soF) { $soC = Get-Content $soF -Raw; if ($soC) { Write-Host "${name} stdout: $($soC.Trim())" } }
        if (Test-Path $seF) { $seC = Get-Content $seF -Raw; if ($seC) { Write-Host "${name} stderr: $($seC.Trim())" } }

        if (Test-Path $outFile) { return (Get-Content $outFile -Raw).Trim() }
        return "(no result file)"
    }
    # rev19 (Instinct 2): trial preconditions, inspected and reported at the fixed head.
    # The peer namespace is only "protected" if the real install root is
    # admin-controlled: print the root, its ancestors' and the exe's ACLs and path
    # form, then prove a minimum-rights open of the exe works under the filtered token.
    Write-Host "== rev19 trial preconditions: install namespace attestation =="
    # CI TOPOLOGY, stated plainly: this harness registers the broker against the
    # build-output exe (register-broker.ps1 -ExePath $exeAbs), so broker and helper
    # are one file here - the protected-Program-Files namespace itself is attested
    # in Part 0 (ancestor+dir+exe ACLs above) and is machine proof only on a real
    # install. What this section proves under the filtered token is the minimum-
    # rights identity open of the file the broker ACTUALLY runs in this harness.
    Write-Host "broker/helper image under test: $exeAbs"
    # Instinct 2 (rev19 review): the namespace binding is only as strong as the
    # namespace itself - attest that NO ancestor of the image (nor the image) is a
    # reparse point and that the drive letter is not a SUBST/define-dos-device alias,
    # so 'the path resolves inside the namespace' cannot be a caller-local remap.
    $chain = @(); $d = $exeAbs
    while ($d) { $chain += $d; $d = Split-Path $d }
    $reparseHits = @($chain | Where-Object { ((Get-Item $_ -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) })
    Assert ($reparseHits.Count -eq 0) "no reparse point on the image path chain ($(if ($reparseHits) { $reparseHits -join ', ' } else { 'checked: ' + ($chain -join ' <- ') }))"
    Write-Host "ok: namespace chain is reparse-free: $($chain -join ' <- ')"
    $driveLetter = $exeAbs.Substring(0,2)
    $substOut = (& subst.exe 2>$null | Out-String)
    Assert ($substOut -notmatch [regex]::Escape($driveLetter)) "the image drive letter $driveLetter is not a SUBST alias"
    Write-Host "ok: $driveLetter is not a SUBST alias"
    $tryOut = Join-Path $env:TEMP 'bingemote-try-open.txt'
    $tryLine = Run-LimitedTask 'BingeMote CI TryOpen' ('--try-open "{0}" "{1}" --restrict-self' -f $exeAbs, $tryOut) $tryOut 60
    Write-Host "filtered-token minimum-rights open of the broker image: $tryLine"
    Assert ($tryLine -match 'open-ok') "the filtered token can open the broker image read-only (identity-check precondition); a denied open fails closed"

    # rev18 (Instinct 2): repeated runs, measured effect, endpoint-bound trace, token dump.
    # rev19: the broker must be at steady state before the rounds - a broker that
    # (re)started seconds ago is not Ezzy's condition (his broker runs since boot),
    # and run 43704201857 showed OpenProcess succeeding against a 2-second-old broker
    # while the same token was denied a moment earlier.
    $pipeReady = $false
    foreach ($i in 1..40) { if (Test-Path '\\.\pipe\BingeMoteInputBroker') { $pipeReady = $true; break } Start-Sleep -Milliseconds 500 }
    Assert $pipeReady "broker pipe is up before the filtered-token rounds"
    # Poll for genuine steady state instead of a fixed sleep: a broker that
    # (re)starts right before the rounds is not Ezzy's condition, and a fixed 15s
    # proved too short when the restart lands inside the attestation section.
    $steadyDeadline = (Get-Date).AddSeconds(150)
    for (;;) {
        # Instinct 2 (rev19 review): the deadline must bound EVERY path through this
        # loop - the old order let an absent broker sleep+continue past it forever.
        if ((Get-Date) -ge $steadyDeadline) { Write-Host "steady-state poll deadline hit (broker $(if ($brokerProc) { "age $((New-TimeSpan -Start $brokerProc.CreationDate -End (Get-Date)).TotalSeconds)s" } else { 'ABSENT' }))"; break }
        $brokerProc = Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker' } | Select-Object -First 1
        if (-not $brokerProc) { Start-Sleep -Seconds 5; continue }
        $ageNow = (New-TimeSpan -Start $brokerProc.CreationDate -End (Get-Date)).TotalSeconds
        if ($ageNow -ge 60) { break }
        Start-Sleep -Seconds 5
    }
    Assert ($null -ne $brokerProc) "broker process is running before the filtered-token rounds"
    $brokerPid = [uint32]$brokerProc.ProcessId
    $brokerAge = (New-TimeSpan -Start $brokerProc.CreationDate -End (Get-Date)).TotalSeconds
    Write-Host "broker age(s) before rounds: $brokerAge (pid $brokerPid)"
    # Anomaly dissection (runs 43704201857 + 43706117121): same-token OpenProcess on
    # the broker flips denied->allowed within seconds. Print the broker's actual
    # DACL+mandatory-label SDDL (read as the local admin) before and after round 1 so
    # the log shows whether the security descriptor itself changes.
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ProcSd {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
    [DllImport("advapi32.dll", SetLastError=true)] public static extern uint GetSecurityInfo(IntPtr handle, int objectType, uint securityInfo, out IntPtr ppsidOwner, out IntPtr ppsidGroup, out IntPtr ppDacl, out IntPtr ppSacl, out IntPtr ppSecurityDescriptor);
    [DllImport("advapi32.dll", SetLastError=true, CharSet=CharSet.Unicode)] public static extern bool ConvertSecurityDescriptorToStringSecurityDescriptor(IntPtr sd, uint revision, uint securityInfo, out IntPtr sddl, out IntPtr len);
    [DllImport("kernel32.dll")] public static extern IntPtr LocalFree(IntPtr p);
    public static string Dump(uint pid) {
        IntPtr h = OpenProcess(0x00020000 | 0x1000, false, pid); // READ_CONTROL | PROCESS_QUERY_LIMITED_INFORMATION
        if (h == IntPtr.Zero) return "open:win32=" + Marshal.GetLastWin32Error();
        try {
            uint rc = GetSecurityInfo(h, 6 /*SE_KERNEL_OBJECT*/, 0x1|0x2|0x4|0x10 /*owner|group|dacl|label*/, out var o, out var g, out var d, out var s, out var sd);
            if (rc != 0) return "getsecurityinfo:win32=" + rc;
            try {
                if (!ConvertSecurityDescriptorToStringSecurityDescriptor(sd, 1, 0x1|0x2|0x4|0x10, out var sddl, out var len))
                    return "convert:win32=" + Marshal.GetLastWin32Error();
                try { return Marshal.PtrToStringUni(sddl) ?? "(empty)"; } finally { LocalFree(sddl); }
            } finally { LocalFree(sd); }
        } finally { CloseHandle(h); }
    }
}
public struct SCRIPTPOINT { public int X; public int Y; }
public static class ScriptCursor {
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out SCRIPTPOINT p);
    public static SCRIPTPOINT Get() { SCRIPTPOINT p; GetCursorPos(out p); return p; }
}
'@
    Write-Host "broker process SDDL before round 1: $([ProcSd]::Dump($brokerPid))"
    foreach ($round in 1..3) {
        $cur0 = [ScriptCursor]::Get()
        $opLine = Run-LimitedTask 'BingeMote CI RestrictedOp' ('--broker-op "m 80 0" "{0}" --restrict-self' -f $opOut) $opOut 90
        Start-Sleep -Milliseconds 500 # let the broker's SendInput land before reading the session cursor
        $cur1 = [ScriptCursor]::Get()
        Write-Host "restricted client (round $round): $opLine; session cursor $($cur0.X),$($cur0.Y)->$($cur1.X),$($cur1.Y) (script-side)"
        if ($round -eq 1) {
            # Token + SDDL evidence BEFORE any assert can abort the round (anomaly
            # dissection: the assert log must always carry the probe's token shape
            # and whether the broker's DACL changed across the flip).
            $whoFile0 = $opOut + '.whoami.txt'
            if (Test-Path $whoFile0) { Write-Host "== round 1 probe token (whoami /user /groups /priv) =="; Write-Host (Get-Content $whoFile0 -Raw) }
            Write-Host "broker process SDDL after round 1: $([ProcSd]::Dump($brokerPid))"
        }
        # Token evidence FIRST (the assert log must always carry the probe's token
        # shape): read the probe's own dump, then decide which asserts the shape supports.
        $whoFile = $opOut + '.whoami.txt'
        Assert (Test-Path $whoFile) "round ${round}: the probe dumped its token (whoami)"
        $who = Get-Content $whoFile -Raw
        $shaped = ($who -match 'il-sid: S-1-16-8192')
        Write-Host "== round ${round} probe token evidence =="; Write-Host $who
        # Denial visibility: only a shaped (medium-IL, no-SeDebug) token guarantees the
        # legacy probe is denied. Unshaped, SeDebug is already enabled before the hook
        # runs (round 23 evidence) and the probe may open - that is the runner's
        # limitation, not the product's behavior.
        if ($shaped) {
            Assert ($opLine -match 'legacy_win32=5') "round ${round}: filtered token is standard-like: the pre-rev17 handle check IS denied (legacy_win32=5), so this test can see Ezzy's condition"
        } elseif ($opLine -match 'legacy_win32=5') {
            Write-Host "round ${round}: legacy probe denied even UNSHAPED (broker DACL denies runneradmin) - denial property visible without shaping"
        } else {
            Write-Host "round ${round}: LIMITATION (unshaped runner token, SeDebug enabled) - legacy probe denial not re-provable on this image; covered by round-11 first-attempt win32=5 and Ezzy's helper.log"
        }
        Assert ($opLine -match 'result=Played') "round ${round}: non-elevated client verified the SYSTEM broker and played an op"
        # Endpoint-bound verification proof: the trace comes from the SAME connection
        # TryRun used. handlefree:win32=5 = denied-handle fallback ran; openprocess
        # file-identity ok = identity verified with a handle (unshaped runner token).
        Assert ($opLine -match 'trace="(handlefree:win32=5|openprocess file-identity ok)') "round ${round}: TryRun verified the broker on its own connection (trace in result line)"
        if (-not $shaped) {
            Write-Host "round ${round}: LIMITATION (unshaped runner token): trace took the identity-ok route; handlefree:win32=5 not re-provable on this image (UAC disabled, all 6 shaping mechanisms refused - see restrict-self-path); covered by round-11 first-attempt denial + Ezzy helper.log"
        }
        # Measured effect: result=Played must mean the pointer actually moved (m 80 0).
        $dx = $cur1.X - $cur0.X
        Assert ($dx -ge 40) "round ${round}: the played op visibly moved the session cursor (dx=$dx of 80 requested, measured script-side)"
        if ($shaped) {
            Assert ($who -notmatch 'privilege: SeDebugPrivilege') "round ${round}: the probe token has NO SeDebugPrivilege, so the OpenProcess denial can never be privilege-bypassed mid-process (runner anomaly runs 43704201857+43706117121)"
            Write-Host ("round ${round} probe-token note: " + (($who -match 'group: S-1-5-32-544') ? 'Administrators group state shown in whoami evidence above' : 'no Administrators group listed'))
        } else {
            Write-Host "round ${round}: token shape asserts SKIPPED (shaping refused by this image - see restrict-self-path above); the probe token's actual shape is printed in full for the record"
        }
    }
    # rev18 (Instinct 2): alias/denied-handle squatter negative control. A copy of this
    # same exe from a DIFFERENT folder runs idle as SYSTEM; a filtered client cannot
    # open it and the handle-free identity check must REJECT it as a pipe peer.
    Write-Host "== rev18 squatter negative control =="
    $squatDir = Join-Path $env:TEMP 'bingemote-squat'
    New-Item -ItemType Directory -Force $squatDir | Out-Null
    Copy-Item $exeAbs (Join-Path $squatDir 'BingeMote.exe') -Force
    $squatPidFile = Join-Path $env:TEMP 'bingemote-squat-pid.txt'
    Remove-Item $squatPidFile -Force -ErrorAction SilentlyContinue
    $sysPrincipal = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $idleAction = New-ScheduledTaskAction -Execute (Join-Path $squatDir 'BingeMote.exe') -Argument ('--idle 60 "{0}"' -f $squatPidFile)
    Register-ScheduledTask -TaskName 'BingeMote CI Squat' -Action $idleAction -Principal $sysPrincipal -Force | Out-Null
    Start-ScheduledTask -TaskName 'BingeMote CI Squat'
    $squatDeadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path $squatPidFile) -and (Get-Date) -lt $squatDeadline) { Start-Sleep -Milliseconds 500 }
    Assert (Test-Path $squatPidFile) "squatter copy is running as SYSTEM"
    $squatPid = (Get-Content $squatPidFile -Raw).Trim()
    Unregister-ScheduledTask -TaskName 'BingeMote CI Squat' -Confirm:$false
    $verifyOut = Join-Path $env:TEMP 'bingemote-verify-pid.txt'
    $verifyLine = Run-LimitedTask 'BingeMote CI VerifySquat' ('--verify-pid {0} "{1}" --restrict-self' -f $squatPid, $verifyOut) $verifyOut 90
    Write-Host "squatter verification: $verifyLine"
    Assert ($verifyLine -match 'outcome=rejected') "same-named copy from another folder is REJECTED as a pipe peer"
    $vWhoFile = $verifyOut + '.whoami.txt'
    $vShaped = (Test-Path $vWhoFile) -and ((Get-Content $vWhoFile -Raw) -match 'il-sid: S-1-16-8192')
    if ($vShaped) {
        Assert ($verifyLine -match 'handlefree:win32=5') "the squatter rejection went through the handle-free path (OpenProcess was denied)"
    } else {
        # Unshaped runner token: OpenProcess succeeds, so the different-file negative
        # reaches the IDENTITY check instead - the rejection must still happen, via the
        # namespace binding or file-identity mismatch.
        Assert ($verifyLine -match 'handlefree:win32=5|mismatch') "the squatter was rejected (denied-handle fallback, or identity/namespace mismatch with a readable handle on the unshaped runner token)"
        Write-Host "LIMITATION (unshaped runner token): squatter handlefree route not re-provable on this image; rejection route shown in the verify line above"
    }
    Assert ($verifyLine -match 'namespace:mismatch') "the squatter rejection reason is the install-namespace binding (its path resolves outside it)"
    Stop-Process -Id ([int]$squatPid) -Force -ErrorAction SilentlyContinue

    # rev19 (Instinct 2): reconnect re-verification. Kill the SYSTEM broker, bring it
    # back through its scheduled task, and a fresh filtered-token op must verify the
    # NEW broker instance again (verification happens per connection, not once).
    Write-Host "== rev19 reconnect re-verification =="
    $exeName = Split-Path $exeAbs -Leaf
    Get-CimInstance Win32_Process -Filter "Name='$exeName'" | Where-Object { $_.CommandLine -match '--input-broker' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $pipeGone = $false
    foreach ($i in 1..20) { if (-not (Test-Path '\\.\pipe\BingeMoteInputBroker')) { $pipeGone = $true; break } Start-Sleep -Milliseconds 500 }
    Assert $pipeGone "broker pipe vanished after the broker was killed"
    Start-ScheduledTask -TaskName 'BingeMote Input Broker'
    $pipeBack = $false
    foreach ($i in 1..60) { if (Test-Path '\\.\pipe\BingeMoteInputBroker') { $pipeBack = $true; break } Start-Sleep -Seconds 1 }
    Assert $pipeBack "broker pipe returned after the task restart"
    $reLine = Run-LimitedTask 'BingeMote CI RestrictedOp' ('--broker-op "m 80 0" "{0}" --restrict-self' -f $opOut) $opOut 90
    Write-Host "reconnect op: $reLine"
    Assert ($reLine -match 'result=Played') "a fresh connection to the restarted broker verified and played"
    Assert ($reLine -match 'trace="(handlefree:win32=5|openprocess file-identity ok)') "the reconnect re-verified the NEW broker on its own connection (handle-free fallback, or identity-ok on the unshaped runner token)"

    # --- rev17: a second launch must SHOW the running copy, not take it over, when the
    # same-named SYSTEM broker shadows RunningCopyPath for a non-elevated user. Ezzy:
    # double-click never opened the window; each launch killed and replaced the running
    # copy ("Update launched while an older copy ran" churn in his helper.log).
    Write-Host "== rev17 second launch shows window despite SYSTEM broker shadow =="
    # Restart the UI copy AFTER the broker exists so the SYSTEM broker (lower pid) sorts
    # first in process enumeration and shadows the readable UI copy, as on Ezzy's PC.
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart for the second-launch shadow test" }
    # Consumer-side evidence (Instinct 2): read the actual window state, not only the
    # second instance's log line. The copy starts tucked in the tray: no visible
    # BingeMote window BEFORE the second launch.
    Add-Type -TypeDefinition @"
using System; using System.Text; using System.Runtime.InteropServices;
public static class BmWinEnum {
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumProc cb, IntPtr l);
    private delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    public static string FindVisible(string needle) {
        string found = null;
        EnumWindows((h, l) => {
            if (!IsWindowVisible(h)) return true;
            var sb = new StringBuilder(512); GetWindowText(h, sb, sb.Capacity);
            var t = sb.ToString();
            if (t.Length > 0 && t.IndexOf(needle, StringComparison.OrdinalIgnoreCase) >= 0) { found = t; return false; }
            return true;
        }, IntPtr.Zero);
        return found;
    }
}
"@
    $visBefore = [BmWinEnum]::FindVisible('BingeMote')
    Write-Host "visible BingeMote window before second launch: $(if ($visBefore) { "'$visBefore'" } else { '(none)' })"
    Assert (-not $visBefore) "control: no visible BingeMote window while tucked in the tray"
    $logPath = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
    $logBefore = (Get-Item $logPath).Length
    # Same filtered-token shaping: a plain second launch from a non-elevated user.
    $launchAction = New-ScheduledTaskAction -Execute $exeAbs
    Register-ScheduledTask -TaskName 'BingeMote CI RestrictedLaunch' -Action $launchAction -Principal $limited -Force | Out-Null
    Start-ScheduledTask -TaskName 'BingeMote CI RestrictedLaunch'
    $showDeadline = (Get-Date).AddSeconds(60)
    $newLog = ''
    while ((Get-Date) -lt $showDeadline) {
        Start-Sleep -Milliseconds 500
        $fullLog = Get-Content $logPath -Raw
        $newLog = $fullLog.Substring([Math]::Min($logBefore, $fullLog.Length))
        if ($newLog -match 'Launched while already running' -or $newLog -match 'Update launched while an older copy ran') { break }
    }
    Unregister-ScheduledTask -TaskName 'BingeMote CI RestrictedLaunch' -Confirm:$false
    Assert ($newLog -match 'Launched while already running') "non-elevated second launch asked the running copy to show its window despite the unreadable SYSTEM broker (no update takeover)"
    Assert ($newLog -notmatch 'could NOT signal') "the show-event signal is reported truthfully: it fired, and the log does not hide a failure (rev19 honest show outcome)"
    Assert ($newLog -notmatch 'Update launched while an older copy ran') "no bogus update-takeover of the running copy"
    Start-Sleep -Milliseconds 500
    Assert (-not $p.HasExited) "running UI copy survived the second launch"
    # The signal CONSUMER: the running copy's own window must now be visible
    # (deterministic branch evidence, not just the requester's log line).
    $visAfter = $null
    $visDeadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $visDeadline) {
        $visAfter = [BmWinEnum]::FindVisible('BingeMote')
        if ($visAfter) { break }
        Start-Sleep -Milliseconds 500
    }
    Assert $visAfter "the running copy's window is VISIBLE after the filtered second launch's signal (consumer side: '$visAfter')"

    # --- rev19 (Instinct 2 review BLOCKER): an UNIDENTIFIED single-instance holder
    # (no readable process path, not the recognized unreadable shadow) must be
    # show-only - never asked to quit. Fixture: a RENAMED copy of this exe holds the
    # mutex + both named events, so RunningCopyPath finds no same-named process.
    Write-Host "== rev19 unidentified holder is show-only =="
    # The SYSTEM broker is same-named and readable by this elevated harness, so it
    # would mask the fixture (RunningCopyPath would identify IT). Take the broker
    # down for this test; the UI copy goes down too (it is a same-named candidate).
    Stop-ScheduledTask -TaskName 'BingeMote Input Broker' -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker|--broker-launcher' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Stop-Tracked $p
    # The 5-minute Watchdog would relaunch a SAME-NAMED UI copy mid-test and re-mask
    # the unidentified holder; it is already parked for the whole run (rev20b), and
    # this local disable keeps the test self-contained if the park is ever lifted.
    # The outer finally re-enables it unconditionally, so it is never left off.
    Disable-ScheduledTask -TaskName 'BingeMote Watchdog' -ErrorAction SilentlyContinue | Out-Null
    Start-Sleep -Seconds 1
    $unDir = Join-Path $env:TEMP 'bingemote-unidentified'
    New-Item -ItemType Directory -Force $unDir | Out-Null
    Copy-Item $exeAbs (Join-Path $unDir 'NotBinge.exe') -Force
    $holdPidFile = Join-Path $env:TEMP 'bingemote-hold-events-pid.txt'
    Remove-Item $holdPidFile, "$holdPidFile.result" -Force -ErrorAction SilentlyContinue
    $holder = Start-Process (Join-Path $unDir 'NotBinge.exe') -ArgumentList ('--hold-events 45 "{0}"' -f $holdPidFile) -PassThru
    $holdDeadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path $holdPidFile) -and (Get-Date) -lt $holdDeadline) { Start-Sleep -Milliseconds 500 }
    $holdEarly = (Test-Path "$holdPidFile.result") ? (Get-Content "$holdPidFile.result" -Raw).Trim() : ''
    Assert (Test-Path $holdPidFile) "unidentified holder acquired the mutex + events$(if ($holdEarly) { " (holder: $holdEarly)" })"
    $logPathU = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
    $logBeforeU = (Get-Item $logPathU).Length
    $launcher = Start-Process $exeAbs -PassThru
    $launcher.WaitForExit(20000) | Out-Null
    Assert $launcher.HasExited "the launcher exited against the unidentified holder (show-only, no takeover wait)"
    Start-Sleep -Seconds 1
    $fullLogU = Get-Content $logPathU -Raw
    $newLogU = $fullLogU.Substring([Math]::Min($logBeforeU, $fullLogU.Length))
    Write-Host "unidentified-holder launch log: $($newLogU.Trim())"
    Assert ($newLogU -match 'could not be identified') "the launcher treated the UNIDENTIFIED holder as unknown"
    Assert ($newLogU -match 'not taking over') "unknown holder: show-only, no takeover"
    Assert ($newLogU -notmatch 'asked it to quit') "unknown holder is NEVER asked to quit (the rev19 fall-through bug: unidentified skipped every flag and reached the cooperative quit)"
    Assert (-not $holder.HasExited) "the unidentified holder survived the launch (no quit, no kill)"
    $holder.WaitForExit(50000) | Out-Null
    $holdResult = (Test-Path "$holdPidFile.result") ? (Get-Content "$holdPidFile.result" -Raw).Trim() : '(no result file)'
    Write-Host "holder side: $holdResult"
    Assert ($holdResult -eq 'no-quit-signal') "holder-observed truth: the quit event was never set"
    # Restore the broker and the running UI copy for the sections below. The
    # Watchdog stays parked between heartbeat ticks (rev20c: the watcher re-parks
    # it every tick - UI starts re-register it ENABLED; restored and verified in
    # the outer finally).
    Start-ScheduledTask -TaskName 'BingeMote Input Broker'
    foreach ($i in 1..40) { if (Test-Path '\\.\pipe\BingeMoteInputBroker') { break }; Start-Sleep -Milliseconds 500 }
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart after the unidentified-holder test" }

    # --- rev12: broker hardening (Instinct 2 review blockers) ---
    Write-Host "== rev12 broker hardening =="
    $sysLogPath = 'C:\Windows\System32\config\systemprofile\AppData\Local\BingeMote\helper.log'

    # B3: fail-closed session hop. Every --input-broker process still alive must serve
    # from the harness (console) session only. The session-0 --broker-launcher that
    # hopped it here and now watches its lifetime serves nothing and holds no pipe, so
    # it is not a stray - a *serving* broker outside the console session is.
    $strays = @(Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker' -and $_.SessionId -ne $harnessSession })
    Assert ($strays.Count -eq 0) "no broker copy serves outside the console session (fail-closed session hop)"

    # B1: an arbitrary local process (this script - right user, wrong image) connects
    # fine at the DACL level and is then rejected by the broker's client verification.
    # Rejection can arrive as an explicit 'err unauthorized' reply, a closed pipe, or a
    # broken-pipe error on write/read - all three prove the op was never served. The
    # broker-side REJECTED log line is asserted separately below.
    $bA = [Probe]::Pos()
    $reply = $null
    $pipeError = $null
    try {
    $pipeClient = New-Object System.IO.Pipes.NamedPipeClientStream('.', 'BingeMoteInputBroker', 'InOut')
        $pipeClient.Connect(5000)
        # rev20 follow-up (Instinct 2 first pass, finding 1): PipeStream's
        # ReadTimeout/WriteTimeout setters THROW on .NET 8 ("Timeouts are not
        # supported on this stream") - the old assignments threw here, the catch
        # passed on a null reply, and the op was never sent (vacuous pass; the green
        # run 43792887604's own line shows that exact pipeError). Bound the round
        # trip with task waits, the same shape as InputBrokerClient.RoundTrip.
        $pw = New-Object System.IO.StreamWriter($pipeClient); $pw.AutoFlush = $true
        $pr = New-Object System.IO.StreamReader($pipeClient)
        $wt = $pw.WriteLineAsync('m 10 0')
        if (-not $wt.Wait(5000)) { throw "the write to the broker pipe was not abandoned at its 5s bound" }
        $rt = $pr.ReadLineAsync()
        if (-not $rt.Wait(5000)) { throw "the broker's answer was not abandoned at its 5s bound" }
        $reply = $rt.Result
    } catch { $pipeError = $_.Exception.Message }
    finally { if ($pipeClient) { $pipeClient.Dispose() } }
    Assert ($pipeError -notmatch 'Timeouts are not supported') "no local harness fault before the request reached the broker (the rev20 vacuous pass) - pipeError: '$pipeError'"
    Assert (($reply -like 'err unauthorized*') -or ($null -eq $reply) -or ($pipeError -match 'pipe')) "a client that is not the installed helper is rejected (reply: '$reply' pipeError: '$pipeError')"
    Start-Sleep -Milliseconds 300
    $aA = [Probe]::Pos()
    Assert ([math]::Abs($aA.X - $bA.X) -lt 20) "the rejected op was never played ($($bA.X) -> $($aA.X))"

    # B2: an authorized but wedged client (connects, then goes silent) neither blocks
    # other connections nor lives forever: multi-instance server + server read timeout.
    $holdSecs = 25
    $hold = Start-Process $exe -ArgumentList '--broker-hold', "$holdSecs" -PassThru
    Start-Sleep -Seconds 2
    $bB = [Probe]::Pos()
    $r = Invoke-Helper '/mouse/move' '{"dx":60,"dy":0}' $token
    Assert ($r.code -eq 200) "mouse/move while a wedged client holds a connection"
    Start-Sleep -Milliseconds 400
    $aB = [Probe]::Pos()
    Assert ([math]::Abs($aB.X - $bB.X) -ge 40) "broker keeps serving while another client is wedged ($($bB.X) -> $($aB.X))"
    $holdExited = $hold.WaitForExit(($holdSecs + 10) * 1000)
    Assert $holdExited "the wedge client exits on schedule"
    # The broker's 15s read timeout drops the silent connection before the hold ends.
    $sysLogText = ''
    foreach ($i in 1..30) {
        if (Test-Path $sysLogPath) { $sysLogText = Get-Content $sysLogPath -Raw }
        if ($sysLogText -match 'idle timeout') { break }
        Start-Sleep -Seconds 1
    }
    Assert ($sysLogText -match 'idle timeout') "the broker idle-dropped the wedged client (server read timeout)"
    Assert ($sysLogText -match 'client REJECTED') "the broker logged the unauthorized-client rejection"

    # B5: a flooding client hits the per-connection rate cap and is dropped; the real
    # helper's own connection is unaffected.
    $floodOut = Join-Path $env:TEMP 'broker-flood.txt'
    Remove-Item $floodOut -Force -ErrorAction SilentlyContinue
    $floodProc = Start-Process $exe -ArgumentList '--broker-flood', '2000', $floodOut -PassThru
    Wait-Process -Id $floodProc.Id -Timeout 120 -ErrorAction SilentlyContinue  # void output; timeout is a suppressed error
    if (-not $floodProc.HasExited) {
        Stop-Process -Id $floodProc.Id -Force -ErrorAction SilentlyContinue
        throw "broker-flood hook did not exit within 120s (bounded wait, rev18)"
    }
    Assert (Test-Path $floodOut) "flood hook wrote its result file"
    $floodResult = Get-Content $floodOut -Raw
    Assert ($floodResult -match 'rate_limited') "a flooding client is rate-limited and dropped: $floodResult"
    $r = Invoke-Helper '/mouse/move' '{"dx":10,"dy":0}' $token
    Assert ($r.code -eq 200) "the helper's own connection is unaffected by another client's flood"

    # B2 client side: a fake broker squatting the pipe name is never trusted. Kill the
    # real broker, squat the name from this script, and the helper must refuse it and
    # fall back to local input instead of streaming phone input into the impostor.
    # Disable the task first: the launcher's restart-on-failure would otherwise revive
    # the real broker mid-test and race the squatter.
    Write-Host "== rev12 fake broker rejected =="
    Disable-ScheduledTask -TaskName 'BingeMote Input Broker' | Out-Null
    Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker|--broker-launcher' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep -Seconds 2
    $fake = Start-Job {
        # Loop: Test-Path probes and the helper's own connects each consume one accept,
        # so re-listen after every connection or the real client finds the pipe busy.
        while ($true) {
            $srv = New-Object System.IO.Pipes.NamedPipeServerStream('BingeMoteInputBroker', 'InOut', 4, 'Byte', 'None')
            # rev20: bound the accept so a wedged listener cannot pin this job forever.
            try { if (-not $srv.WaitForConnectionAsync().Wait(10000)) { $srv.Dispose(); continue } } catch { $srv.Dispose(); continue }
            Start-Sleep -Milliseconds 250
            $srv.Dispose()
        }
    }
    foreach ($i in 1..20) { if (Test-Path '\\.\pipe\BingeMoteInputBroker') { break }; Start-Sleep -Milliseconds 250 }
    # Fresh helper so its client has no cached connection or backoff state.
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    Remove-Item Env:BINGEMOTE_PAIR_CODE -ErrorAction SilentlyContinue
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart for the fake-broker test" }
    $r = Invoke-Helper '/mouse/move' '{"dx":60,"dy":0}' $token
    Assert ($r.code -eq 200) "mouse/move while a fake broker squats the pipe"
    Start-Sleep -Milliseconds 400
    $helperLog = Get-Content (Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log') -Raw
    # rev19 checks the install-namespace binding BEFORE file identity, so either
    # rejection line proves the impostor was refused (namespace-first is the rev19 order).
    Assert ($helperLog -match 'peer image path is outside the install namespace|peer image is not the installed helper') "the helper rejected the fake broker's server identity (rev19 namespace binding fires first; file-identity line when readable and in-namespace)"
    Assert ($helperLog -match 'input_routing=local') "the helper fell back to local input instead of trusting the impostor"
    Stop-Job $fake -ErrorAction SilentlyContinue
    Remove-Job $fake -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    # Restore the real broker and prove end-to-end routing recovers.
    Enable-ScheduledTask -TaskName 'BingeMote Input Broker' | Out-Null
    Start-ScheduledTask -TaskName 'BingeMote Input Broker'
    $pipeUp = $false
    foreach ($i in 1..40) { if (Test-Path '\\.\pipe\BingeMoteInputBroker') { $pipeUp = $true; break }; Start-Sleep -Milliseconds 500 }
    Assert $pipeUp "real broker pipe is back after the fake-broker test"
    $brokerProc = $null
    foreach ($i in 1..40) {
        $brokerProc = Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker' -and $_.SessionId -eq $harnessSession } | Select-Object -First 1
        if ($brokerProc) { break }
        Start-Sleep -Milliseconds 500
    }
    Assert ($null -ne $brokerProc) "real broker is back in the console session"
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart after broker restore" }
    $bR = [Probe]::Pos()
    # Move LEFT: the flood test above drives the cursor into the right screen edge,
    # where a rightward move would be clipped and look like a routing failure.
    $r = Invoke-Helper '/mouse/move' '{"dx":-60,"dy":0}' $token
    Assert ($r.code -eq 200) "mouse/move with the real broker restored"
    Start-Sleep -Milliseconds 400
    $aR = [Probe]::Pos()
    Assert ([math]::Abs($aR.X - $bR.X) -ge 40) "input routes through the real broker again ($($bR.X) -> $($aR.X))"
    $helperLog = Get-Content (Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log') -Raw
    Assert ($helperLog -match 'input_routing=broker') "fresh helper confirms broker routing after restore"

    # P2 (review): a delayed broker ack must not double-apply an op. On rev15 the same
    # 3s ack delay applied every op three times - broker, retry, local fallback - so
    # HELLO typed 15 keydowns, Enter 3, and dx=100 moved 300. rev16 runs the REAL
    # broker (dedup, validator, rate limits) under the same fault via a CI hook; the
    # helper's wider ack window consumes the late "ok", so each op lands exactly once
    # and the local fallback never fires.
    Write-Host "== P2 delayed-ack dedup =="
    Disable-ScheduledTask -TaskName 'BingeMote Input Broker' | Out-Null
    Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker|--broker-launcher' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep -Seconds 2
    $delayed = Start-Process $exe -ArgumentList '--broker-delayed-run', '3000' -PassThru
    $pipeUp = $false
    foreach ($i in 1..40) { if (Test-Path '\\.\pipe\BingeMoteInputBroker') { $pipeUp = $true; break }; Start-Sleep -Milliseconds 500 }
    Assert $pipeUp "delayed-ack broker is listening"
    # Fresh helper: no cached connection or backoff state.
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart for the delayed-ack test" }
    # Keys land in notepad so every applied keydown is countable.
    $notepad = Start-Process notepad.exe -PassThru
    $npUp = $false
    foreach ($i in 1..40) {
        if ([Probe]::NotepadWindow() -ne [IntPtr]::Zero) { $npUp = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Assert $npUp "notepad is up for keystroke counting"
    $focused = $false
    foreach ($i in 1..20) {
        [Probe]::FocusNotepad() | Out-Null
        if ([Probe]::ForegroundPid() -eq $notepad.Id) { $focused = $true; break }
        Start-Sleep -Milliseconds 250
    }
    Assert $focused "notepad holds the foreground"
    $r = Invoke-Helper '/type' '{"text":"HELLO"}' $token
    Assert ($r.code -eq 200) "type HELLO through the delayed-ack broker"
    Start-Sleep -Seconds 1
    $typed = [Probe]::NotepadText()
    Assert ($typed -eq 'HELLO') "HELLO applied exactly once through a 3s ack delay (got '$typed')"
    $r = Invoke-Helper '/key' '{"key":"enter"}' $token
    Assert ($r.code -eq 200) "Enter through the delayed-ack broker"
    Start-Sleep -Seconds 1
    $typed = [Probe]::NotepadText()
    Assert ($typed -eq "HELLO`r`n") "Enter applied exactly once (text is $($typed.Length) chars)"
    [Probe]::SetCursorPos(500, 400) | Out-Null
    Start-Sleep -Milliseconds 300
    $bD = [Probe]::Pos()
    $r = Invoke-Helper '/mouse/move' '{"dx":100,"dy":0}' $token
    Assert ($r.code -eq 200) "move through the delayed-ack broker"
    Start-Sleep -Seconds 1
    $aD = [Probe]::Pos()
    $dD = $aD.X - $bD.X
    Assert ($dD -ge 60 -and $dD -le 140) "dx=100 applied exactly once through a 3s ack delay (moved ${dD}px)"
    Stop-Process -Id $notepad.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $delayed.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    # Restore the real broker and a fresh helper for the UIPI section.
    Enable-ScheduledTask -TaskName 'BingeMote Input Broker' | Out-Null
    Start-ScheduledTask -TaskName 'BingeMote Input Broker'
    $brokerProc = $null
    foreach ($i in 1..40) {
        $brokerProc = Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker' -and $_.SessionId -eq $harnessSession } | Select-Object -First 1
        if ($brokerProc) { break }
        Start-Sleep -Milliseconds 500
    }
    Assert ($null -ne $brokerProc) "real broker is back after the delayed-ack test"
    Stop-Tracked $p
    Start-Sleep -Seconds 1
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart after the delayed-ack test" }

    # Recreate the SwUSB condition: a SYSTEM-integrity window holds the foreground.
    Write-Host "== rev11 UIPI replication =="
    dotnet publish scripts/UipiBlocker -c Release -o "$env:TEMP\uipiblocker" | Out-Host
    $blockerExe = Join-Path $env:TEMP 'uipiblocker\UipiBlocker.exe'
    Assert (Test-Path $blockerExe) "blocker built"
    $bAction = New-ScheduledTaskAction -Execute $blockerExe
    $bTrigger = New-ScheduledTaskTrigger -AtLogOn
    $bPrincipal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType Interactive -RunLevel Highest
    Register-ScheduledTask -TaskName 'BingeMote UIPI Blocker' -Action $bAction -Trigger $bTrigger -Principal $bPrincipal -Force | Out-Null
    Start-ScheduledTask -TaskName 'BingeMote UIPI Blocker'
    $blockerProc = $null
    foreach ($i in 1..40) {
        $blockerProc = Get-CimInstance Win32_Process -Filter "Name='UipiBlocker.exe'" | Where-Object { $_.SessionId -eq $harnessSession } | Select-Object -First 1
        if ($blockerProc) { break }
        Start-Sleep -Milliseconds 500
    }
    Assert ($null -ne $blockerProc) "blocker process is running in the console session $harnessSession"
    $blockerOwner = Invoke-CimMethod -InputObject $blockerProc -MethodName GetOwner
    Assert ($blockerOwner.User -eq 'SYSTEM') "blocker runs as SYSTEM (got $($blockerOwner.Domain)\$($blockerOwner.User))"
    $fgIsBlocker = $false
    foreach ($i in 1..40) {
        if ([Probe]::ForegroundPid() -eq $blockerProc.ProcessId) { $fgIsBlocker = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Assert $fgIsBlocker "the SYSTEM blocker window holds the foreground (SwUSB condition reproduced)"

    # The bug: a plain SendInput move from this harness (below SYSTEM IL) is swallowed.
    [Probe]::SetCursorPos(300, 250) | Out-Null
    Start-Sleep -Milliseconds 300
    $b1 = [Probe]::Pos()
    [Probe]::SendMove(80, 0) | Out-Null
    Start-Sleep -Milliseconds 400
    $a1 = [Probe]::Pos()
    Assert ([math]::Abs($a1.X - $b1.X) -lt 20) "direct SendInput is blocked while a SYSTEM window holds foreground (bug reproduced: $($b1.X) -> $($a1.X))"

    # The fix: phone input still moves the cursor, because the broker plays it at SYSTEM IL.
    $b2 = [Probe]::Pos()
    $r = Invoke-Helper '/mouse/move' '{"dx":80,"dy":0}' $token
    Assert ($r.code -eq 200) "mouse/move under UIPI blocking"
    Start-Sleep -Milliseconds 400
    $a2 = [Probe]::Pos()
    Assert ([math]::Abs($a2.X - $b2.X) -ge 40) "phone input still moves the cursor under UIPI blocking ($($b2.X) -> $($a2.X)) - fix verified"

    Write-Host "HELPER E2E PASSED"
} finally {
    # rev20 (Instinct 2 round 3): unconditional restores/cleanup, success or failure.
    # The rev19 test pauses the Watchdog; never leave it off after a failed run.
    Enable-ScheduledTask -TaskName 'BingeMote Watchdog' -ErrorAction SilentlyContinue | Out-Null
    # Verify the restore instead of assuming it (Instinct 2 first pass, finding 2: the
    # Enable used to be suppressed and unchecked).
    try {
        $wdState = (Get-ScheduledTask -TaskName 'BingeMote Watchdog' -ErrorAction Stop).State
        Write-Host "watchdog restore check: state=$wdState"
        if ($wdState -eq 'Disabled') { Write-Host "WARNING: watchdog still Disabled after the restore" }
    } catch { Write-Host "WARNING: watchdog restore check failed: $($_.Exception.Message)" }
    # Splice and stop the heartbeat child either way (the trap also stops it; the
    # workflow uploads the raw file as an always-on artifact independent of this).
    try { Sync-Heartbeat } catch { }
    try { if ($heartbeat -and -not $heartbeat.HasExited) { Stop-Process -Id $heartbeat.Id -Force } } catch { }
    # The rev19 unidentified-holder fixture must not survive the run either way.
    try { if ($holder -and -not $holder.HasExited) { Stop-Process -Id $holder.Id -Force -ErrorAction SilentlyContinue } } catch { }
    if ($p -and -not $p.HasExited) { Stop-Tracked $p }
    Get-Job -ErrorAction SilentlyContinue | Stop-Job -ErrorAction SilentlyContinue
    Get-Job -ErrorAction SilentlyContinue | Remove-Job -Force -ErrorAction SilentlyContinue
    Get-Process UipiBlocker -ErrorAction SilentlyContinue | Stop-Process -Force
    Get-Process notepad -ErrorAction SilentlyContinue | Stop-Process -Force
    Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match '--input-broker|--broker-launcher|--broker-delayed-run' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Unregister-ScheduledTask -TaskName 'BingeMote UIPI Blocker' -Confirm:$false -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'BingeMote Input Broker' -Confirm:$false -ErrorAction SilentlyContinue
    netsh advfirewall firewall delete rule name="BingeMote" | Out-Null
}
