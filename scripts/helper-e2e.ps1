# BingeMote Helper runtime tests on a real Windows machine (CI runner).
# Exercises: the elevated firewall/URL-ACL setup, the pairing flow, token auth,
# every input endpoint, the volume endpoints, and UDP discovery.
$ErrorActionPreference = 'Stop'
$exe = "helper/out/BingeMote.exe"
if (-not (Test-Path $exe)) { throw "helper exe missing at $exe" }

# --- Part 0: the installer owns the firewall rule now: silent install, verify, silent uninstall ---
Write-Host "== installer firewall setup =="
$setup = Get-ChildItem "helper/Output/BingeMote-PC-*.exe" | Select-Object -First 1
if (-not $setup) { throw "installer exe missing in helper/Output" }
& $setup.FullName /VERYSILENT /SUPPRESSMSGBOXES /NORESTART | Out-Null
if ($LASTEXITCODE -ne 0) { throw "silent install failed with exit $LASTEXITCODE" }
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
# Upgrade path: a silent reinstall re-copies the protected exe and keeps the task target.
& $setup.FullName /VERYSILENT /SUPPRESSMSGBOXES /NORESTART | Out-Null
if ($LASTEXITCODE -ne 0) { throw "silent upgrade reinstall failed with exit $LASTEXITCODE" }
# The silent install relaunches the app; it re-registers the Watchdog with its own
# (protected) path after register-broker.ps1 deleted the stale task.
$wdOk = $false
for ($i = 0; $i -lt 45 -and -not $wdOk; $i++) {
  $wdXml = try { schtasks.exe /Query /TN "BingeMote Watchdog" /XML 2>$null | Out-String } catch { '' }
  if ($wdXml -match [regex]::Escape($appExe)) { $wdOk = $true } else { Start-Sleep -Seconds 2 }
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
$fw = Start-Process $exe -ArgumentList '--install-firewall' -Wait -PassThru
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
        foreach ($tn in 'BingeMote Input Broker','BingeMote UIPI Blocker') {
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
    function Assert($cond, $msg) { if (-not $cond) { throw "ASSERT FAILED: $msg" } ; Write-Host "ok: $msg" }
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
    $second = Start-Process $exe -Wait -PassThru
    Assert ($second.ExitCode -eq 0) "second instance exits quietly (code $($second.ExitCode))"
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "first instance still serving"

    # After pairing, a fresh launch shows no live code: re-pairing must fail.
    Write-Host "== no silent re-pair =="
    Stop-Process -Id $p.Id -Force
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
    Stop-Process -Id $p.Id -Force
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
    Stop-Process -Id $p.Id -Force
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
    Stop-Process -Id $p.Id -Force
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
        Stop-Process -Id $p.Id -Force
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
        Stop-Process -Id $p.Id -Force
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
        Stop-Process -Id $p.Id -Force
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
    Stop-Process -Id $p.Id -Force
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
public static class Probe {
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll", SetLastError=true)] static extern uint SendInput(uint n, INPUT[] i, int cb);
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public MI mi; }
    [StructLayout(LayoutKind.Sequential)] struct MI { public int dx, dy; public uint data, flags, time; public IntPtr extra; }
    public static bool SendMove(int dx, int dy) {
        var a = new INPUT[1]; a[0].type = 0; a[0].mi.dx = dx; a[0].mi.dy = dy; a[0].mi.flags = 0x0001;
        return SendInput(1, a, Marshal.SizeOf<INPUT>()) == 1;
    }
    public static POINT Pos() { POINT p; GetCursorPos(out p); return p; }
    public static uint ForegroundPid() { uint pid; GetWindowThreadProcessId(GetForegroundWindow(), out pid); return pid; }
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

    # --- rev12: broker hardening (Instinct 2 review blockers) ---
    Write-Host "== rev12 broker hardening =="
    $sysLogPath = 'C:\Windows\System32\config\systemprofile\AppData\Local\BingeMote\helper.log'

    # B3: fail-closed session hop. The relaunching session-0 copy must be gone; every
    # --input-broker process still alive serves from the harness (console) session only.
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
        $pw = New-Object System.IO.StreamWriter($pipeClient); $pw.AutoFlush = $true
        $pr = New-Object System.IO.StreamReader($pipeClient)
        $pw.WriteLine('m 10 0')
        $reply = $pr.ReadLine()
        $pipeClient.Dispose()
    } catch { $pipeError = $_.Exception.Message }
    Assert (($reply -like 'err unauthorized*') -or ($null -eq $reply)) "a client that is not the installed helper is rejected (reply: '$reply' pipeError: '$pipeError')"
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
    Start-Process $exe -ArgumentList '--broker-flood', '2000', $floodOut -Wait | Out-Null
    Assert (Test-Path $floodOut) "flood hook wrote its result file"
    $floodResult = Get-Content $floodOut -Raw
    Assert ($floodResult -match 'rate_limited') "a flooding client is rate-limited and dropped: $floodResult"
    $r = Invoke-Helper '/mouse/move' '{"dx":10,"dy":0}' $token
    Assert ($r.code -eq 200) "the helper's own connection is unaffected by another client's flood"

    # B2 client side: a fake broker squatting the pipe name is never trusted. Kill the
    # real broker, squat the name from this script, and the helper must refuse it and
    # fall back to local input instead of streaming phone input into the impostor.
    Write-Host "== rev12 fake broker rejected =="
    Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep -Seconds 2
    $fake = Start-Job {
        # Loop: Test-Path probes and the helper's own connects each consume one accept,
        # so re-listen after every connection or the real client finds the pipe busy.
        while ($true) {
            $srv = New-Object System.IO.Pipes.NamedPipeServerStream('BingeMoteInputBroker', 'InOut', 4, 'Byte', 'None')
            $srv.WaitForConnection()
            Start-Sleep -Milliseconds 250
            $srv.Dispose()
        }
    }
    foreach ($i in 1..20) { if (Test-Path '\\.\pipe\BingeMoteInputBroker') { break }; Start-Sleep -Milliseconds 250 }
    # Fresh helper so its client has no cached connection or backoff state.
    Stop-Process -Id $p.Id -Force
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
    Assert ($helperLog -match 'peer image is not the installed helper') "the helper rejected the fake broker's server identity"
    Assert ($helperLog -match 'input_routing=local') "the helper fell back to local input instead of trusting the impostor"
    Stop-Job $fake -ErrorAction SilentlyContinue
    Remove-Job $fake -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    # Restore the real broker and prove end-to-end routing recovers.
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
    Stop-Process -Id $p.Id -Force
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
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    Get-Job -ErrorAction SilentlyContinue | Stop-Job -ErrorAction SilentlyContinue
    Get-Job -ErrorAction SilentlyContinue | Remove-Job -Force -ErrorAction SilentlyContinue
    Get-Process UipiBlocker -ErrorAction SilentlyContinue | Stop-Process -Force
    Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match '--input-broker' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Unregister-ScheduledTask -TaskName 'BingeMote UIPI Blocker' -Confirm:$false -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'BingeMote Input Broker' -Confirm:$false -ErrorAction SilentlyContinue
    netsh advfirewall firewall delete rule name="BingeMote" | Out-Null
}
