# BingeMote BROKER FAULT suite (rev16): confirms or refutes Instinct 2's static review
# findings with concrete runtime evidence, then locks each rev16 fix in as an assertion.
# Findings-not-fail: every phase records ok:/FAIL:, and the script exits nonzero at the
# end if anything failed, so a pre-fix run produces the reproduction evidence instead of
# dying at the first failure.
#
#   T-xml  the broker task restarts the broker on failure (F10, config half)
#   T2     a framed op resent on a second connection is deduped by the real broker (F2)
#   T5     a client that dies holding a button does not leave it held (F9)
#   T9     flood-then-reconnect stays under the same per-second cap (rate budget)
#   T8     pipe slot pile-up: the 9th client cannot connect; the helper keeps working
#   T7     a wedged broker trips the circuit breaker and bounds per-op latency (F18)
#   T1a    a non-finite move over HTTP is a 400, not a 200 (F1, API half)
#   T6     a killed broker comes back within 90s (F10, runtime half)
#   T1b    a broker-rejected op replayed locally cannot poison later moves (F1, runtime)
#   T4     a silent server records the helper's lost-reply resend AND its dedup frame (F2)
$ErrorActionPreference = 'Stop'
$exe = "helper/out/BingeMote.exe"
if (-not (Test-Path $exe)) { throw "helper exe missing at $exe" }

$script:failures = @()
function Check($cond, $msg) {
    if ($cond) { Write-Host "ok: $msg" } else { Write-Host "FAIL: $msg"; $script:failures += $msg }
}
function Invoke-Helper($path, $body, $token) {
    $headers = @{}
    if ($token) { $headers['X-BingeMote-Token'] = $token }
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:8488$path" -Method Post -Body $body -Headers $headers -UseBasicParsing -TimeoutSec 15 -SkipHttpErrorCheck
    return @{ code = [int]$r.StatusCode; body = $r.Content }
}
function Wait-HelperUp($seconds) {
    foreach ($i in 1..($seconds * 2)) {
        try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8488); $c.Close(); return $true }
        catch { Start-Sleep -Milliseconds 500 }
    }
    return $false
}
function Wait-BrokerInSession($sessionId, $seconds) {
    foreach ($i in 1..($seconds * 2)) {
        $proc = Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker' -and $_.SessionId -eq $sessionId } | Select-Object -First 1
        if ($proc) { return $proc }
        Start-Sleep -Milliseconds 500
    }
    return $null
}
function Stop-AllBrokers {
    Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" |
        Where-Object { $_.CommandLine -match '--input-broker' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

# --- setup: firewall/URL-ACL like a real install, run the helper, pair, start the broker ---
$fw = Start-Process $exe -ArgumentList '--install-firewall' -Wait -PassThru
if ($fw.ExitCode -ne 0) { throw "install-firewall failed with exit $($fw.ExitCode)" }
Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1
$env:BINGEMOTE_PAIR_CODE = '424242'
$p = Start-Process $exe -PassThru
if (-not (Wait-HelperUp 20)) { throw "helper did not start listening on 8488" }

trap {
    Write-Host "== faults failure: $($_.Exception.Message) =="
    Write-Host "== at: $($_.InvocationInfo.PositionMessage) =="
    $logf = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
    if (Test-Path $logf) { Write-Host "== helper.log =="; Get-Content $logf } else { Write-Host "(no helper.log)" }
    $sysLog = 'C:\Windows\System32\config\systemprofile\AppData\Local\BingeMote\helper.log'
    if (Test-Path $sysLog) { Write-Host "== SYSTEM-profile helper.log =="; Get-Content $sysLog }
    throw
}

$probeSrc = @"
using System;
using System.Runtime.InteropServices;
public static class Probe {
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int vKey);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    public static POINT Pos() { POINT p; GetCursorPos(out p); return p; }
    public static bool LeftDown() { return (GetAsyncKeyState(0x01) & 0x8000) != 0; }
    public static void ForceLeftUp() { mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero); }
}
"@
Add-Type -TypeDefinition $probeSrc
$ntSrc = @"
using System;
using System.Runtime.InteropServices;
public static class NtProc {
    [DllImport("ntdll.dll")] public static extern int NtSuspendProcess(IntPtr processHandle);
    [DllImport("ntdll.dll")] public static extern int NtResumeProcess(IntPtr processHandle);
}
"@
Add-Type -TypeDefinition $ntSrc

$r = Invoke-Helper '/pair' '{"code":"424242"}' $null
if ($r.code -ne 200) { throw "pair failed: $($r.code) $($r.body)" }
$token = ($r.body | ConvertFrom-Json).token
Write-Host "paired for the fault run"

& powershell -NoProfile -ExecutionPolicy Bypass -File helper/register-broker.ps1 -ExePath (Resolve-Path $exe).Path
Start-ScheduledTask -TaskName 'BingeMote Input Broker'
$harnessSession = (Get-Process -Id $PID).SessionId
$brokerProc = Wait-BrokerInSession $harnessSession 30
if ($null -eq $brokerProc) { throw "broker did not come up in the harness session $harnessSession" }
[Probe]::SetCursorPos(500, 400) | Out-Null
Start-Sleep -Milliseconds 300
$r = Invoke-Helper '/mouse/move' '{"dx":60,"dy":0}' $token
Start-Sleep -Milliseconds 400
if ($r.code -ne 200) { throw "warm-up move failed: $($r.code)" }
Write-Host "broker is up and serving"

# --- T-xml: the task restarts the broker on failure (F10, config half) ---
Write-Host "== T-xml: broker task restart settings (F10) =="
$taskDef = Get-ScheduledTask -TaskName 'BingeMote Input Broker'
Write-Host "task settings: RestartCount=$($taskDef.Settings.RestartCount) RestartInterval=$($taskDef.Settings.RestartInterval)"
Check ($taskDef.Settings.RestartCount -eq 3) "task restarts the broker 3x on failure"
$riMinutes = ([System.Xml.XmlConvert]::ToTimeSpan([string]$taskDef.Settings.RestartInterval)).TotalMinutes
Check ($riMinutes -eq 1) "task restart interval is one minute"
$xml = Export-ScheduledTask -TaskName 'BingeMote Input Broker'
Check ($xml -match '--broker-launcher') "task action runs the launcher (which hops and watches the serving broker)"

# --- T2: framed op dedup against the real broker (F2) ---
Write-Host "== T2: framed op resend dedup (F2) =="
$line = '#deduptst1.00000001 m 60 0'
$o1 = Join-Path $env:TEMP 'bf-send1.txt'
$o2 = Join-Path $env:TEMP 'bf-send2.txt'
[Probe]::SetCursorPos(500, 400) | Out-Null
Start-Sleep -Milliseconds 200
$b2 = [Probe]::Pos()
Start-Process $exe -ArgumentList '--broker-send', "`"$line`"", $o1 -Wait | Out-Null
Start-Process $exe -ArgumentList '--broker-send', "`"$line`"", $o2 -Wait | Out-Null
Start-Sleep -Milliseconds 500
$a2 = [Probe]::Pos()
$reply1 = (Get-Content $o1 -Raw).Trim()
$reply2 = (Get-Content $o2 -Raw).Trim()
Write-Host "reply1: $reply1"
Write-Host "reply2: $reply2"
$d2 = [math]::Abs($a2.X - $b2.X)
Check ($reply1 -match '"ok"') "first framed op accepted"
Check ($reply2 -match '"ok"') "resent framed op acked, not errored"
Check ($d2 -ge 40 -and $d2 -le 90) "resent op deduped: cursor moved once (~60), not twice (dx=$d2)"

# --- T5: dead client must not leave a button held (F9) ---
Write-Host "== T5: dead client held button (F9) =="
[Probe]::ForceLeftUp()
Start-Sleep -Milliseconds 200
$hd = Join-Path $env:TEMP 'bf-holddown.txt'
Start-Process $exe -ArgumentList '--broker-holddown', $hd -Wait | Out-Null
Write-Host "holddown hook reply: $((Get-Content $hd -Raw).Trim())"
Start-Sleep -Seconds 1
$held1 = [Probe]::LeftDown()
Write-Host "button held right after the client died: $held1 (True before the fix)"
Start-Sleep -Seconds 3
$held2 = [Probe]::LeftDown()
[Probe]::ForceLeftUp() # never leave the runner's mouse held, whatever happened
Check (-not $held2) "broker released the dead client's held button"

# --- T9: flood-then-reconnect rate budget ---
Write-Host "== T9: flood + reconnect rate budget =="
$f2 = Join-Path $env:TEMP 'bf-flood2.txt'
Start-Process $exe -ArgumentList '--broker-flood2', '2000', $f2 -Wait | Out-Null
$res2 = (Get-Content $f2 -Raw).Trim()
Write-Host $res2
$sent1 = [int]([regex]::Match($res2, 'sent1=(\d+)').Groups[1].Value)
$sent2 = [int]([regex]::Match($res2, 'sent2=(\d+)').Groups[1].Value)
Check ($res2 -match 'rate_limited') "floods are rate-limited and dropped"
Check ($sent1 -le 700 -and $sent2 -le 700) "a reconnect buys no budget beyond the standing per-second cap (sent1=$sent1 sent2=$sent2)"

# --- T8: pipe slot pile-up ---
Write-Host "== T8: pipe slot pile-up =="
Invoke-Helper '/mouse/move' '{"dx":5,"dy":0}' $token | Out-Null
$holds = @()
foreach ($i in 1..4) { $holds += Start-Process $exe -ArgumentList '--broker-hold', '30' -PassThru }
Start-Sleep -Seconds 3
$b8 = [Probe]::Pos()
$r = Invoke-Helper '/mouse/move' '{"dx":-60,"dy":0}' $token
Start-Sleep -Milliseconds 500
$a8 = [Probe]::Pos()
Check ($r.code -eq 200 -and [math]::Abs($a8.X - $b8.X) -ge 40) "helper is served while 4 extra clients hold slots"
$o9 = Join-Path $env:TEMP 'bf-ninth.txt'
$ninth = Start-Process $exe -ArgumentList '--broker-send', '"m 1 0"', $o9 -Wait -PassThru
# Observation only: neither this suite nor the review's P6 load test proved 9th-client
# starvation, so there is no fix to lock in here.
if ($ninth.ExitCode -ne 0) { Write-Host "ok: a 9th simultaneous client cannot connect while every slot is full" }
else { Write-Host "note: a 9th client connected with every slot full (report-only: starvation unproven by both sides, P6)" }
$holds | ForEach-Object { if (-not $_.HasExited) { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } }
Start-Sleep -Seconds 1

# --- T7: wedged broker latency (F18 circuit breaker) ---
Write-Host "== T7: wedged broker circuit breaker (F18) =="
$brokerProc = Wait-BrokerInSession $harnessSession 10
if ($null -eq $brokerProc) { throw "broker gone before T7" }
$bhandle = (Get-Process -Id $brokerProc.ProcessId).Handle
[void][NtProc]::NtSuspendProcess($bhandle)
$lat = @()
foreach ($i in 1..8) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Helper '/mouse/move' '{"dx":5,"dy":0}' $token | Out-Null
    $sw.Stop()
    $lat += $sw.ElapsedMilliseconds
    Start-Sleep -Milliseconds 1500
}
[void][NtProc]::NtResumeProcess($bhandle)
Write-Host "per-op latencies ms: $($lat -join ', ')"
$tail = ($lat[4..7] | Measure-Object -Average).Average
Check ($tail -lt 1500) "circuit breaker bounds per-op latency once the broker is wedged (ops 5-8 avg $([int]$tail)ms)"

# --- T1a: non-finite move is rejected at the API (F1, API half) ---
Write-Host "== T1a: non-finite move rejected at the API (F1) =="
$r = Invoke-Helper '/mouse/move' '{"dx":1e999,"dy":0}' $token
Check ($r.code -eq 400) "non-finite move gets HTTP 400 (got $($r.code))"

# --- T-http: command API hygiene (rev16: 400s instead of 500s / silent wrong-input) ---
Write-Host "== T-http: command API hygiene =="
$r = Invoke-Helper '/mouse/move' '{"dx":' $token
Check ($r.code -eq 400) "malformed JSON body is a 400 (got $($r.code))"
$r = Invoke-Helper '/mouse/move' '{"dx":NaN,"dy":0}' $token
Check ($r.code -eq 400) "bare NaN literal is a 400 (got $($r.code))"
$bigBody = '{"text":"' + ('x' * 70000) + '"}'
$r = Invoke-Helper '/type' $bigBody $token
Check ($r.code -eq 400) "an over-64KB body is a 400 (got $($r.code))"
$r = Invoke-Helper '/mouse/move' '{"dy":1}' $token
Check ($r.code -eq 400) "missing field is a 400 (got $($r.code))"
$r = Invoke-Helper '/mouse/move' '{"dx":"fast","dy":0}' $token
Check ($r.code -eq 400) "wrong-typed field is a 400 (got $($r.code))"
$r = Invoke-Helper '/mouse/button' '{"button":"middle","down":true}' $token
Check ($r.code -eq 400) "unknown button name is a 400 (got $($r.code))"
Check (-not [Probe]::LeftDown()) "an unknown button name pressed nothing"
$r = Invoke-Helper '/key' '{"key":"f13"}' $token
Check ($r.code -eq 400) "unknown key name is a 400 (got $($r.code))"
$longText = 'x' * 3000
$r = Invoke-Helper '/type' ('{"text":"' + $longText + '"}' ) $token
Check ($r.code -eq 200) "over-cap /type is accepted and truncated to 2000 chars, not an error (got $($r.code))"

# --- T6: killed broker auto-restarts (F10, runtime half) ---
Write-Host "== T6: killed broker auto-restarts (F10) =="
$t6start = Get-Date
Stop-AllBrokers
$back = $false
$backAfter = 0
foreach ($i in 1..30) {
    Start-Sleep -Seconds 5
    $bp = Wait-BrokerInSession $harnessSession 1
    if ($bp -and (Test-Path '\\.\pipe\BingeMoteInputBroker')) { $back = $true; $backAfter = [int]((Get-Date) - $t6start).TotalSeconds; break }
}
if ($back) { Write-Host "broker back after ${backAfter}s" } else { Write-Host "broker NOT back within 150s" }
Check ($back -and $backAfter -le 90) "a killed broker is back serving within 90s"
if (-not $back) {
    $ti = Get-ScheduledTaskInfo -TaskName 'BingeMote Input Broker'
    Write-Host "broker task: LastRunTime=$($ti.LastRunTime) LastTaskResult=$($ti.LastTaskResult) NextRunTime=$($ti.NextRunTime)"
    Write-Host (Export-ScheduledTask -TaskName 'BingeMote Input Broker')
    Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | ForEach-Object { Write-Host "proc: pid=$($_.ProcessId) session=$($_.SessionId) cmd=$($_.CommandLine)" }
    $sysLogT6 = 'C:\Windows\System32\config\systemprofile\AppData\Local\BingeMote\helper.log'
    if (Test-Path $sysLogT6) { Write-Host "== SYSTEM helper.log tail =="; Get-Content $sysLogT6 -Tail 30 }
}

# --- T1b: rejected-op local replay cannot poison later moves (F1, runtime half) ---
Write-Host "== T1b: rejected-op replay poison check (F1) =="
# Post-fix the API 400s the op outright. Pre-fix the broker refuses it and the helper
# replays it on the LOCAL path, which poisons the fractional-motion state; with the
# broker still dead (pre-fix T6 leaves it dead) every later move takes that poisoned
# path. Controlled -40px moves must land near -120 total; a teleport or a frozen
# cursor means poison.
Invoke-Helper '/mouse/move' '{"dx":1e999,"dy":0}' $token | Out-Null
$bp1 = [Probe]::Pos()
foreach ($i in 1..3) { Invoke-Helper '/mouse/move' '{"dx":-40,"dy":0}' $token | Out-Null; Start-Sleep -Milliseconds 400 }
$ap1 = [Probe]::Pos()
$d1 = $bp1.X - $ap1.X
Check ($d1 -ge 40 -and $d1 -le 160) "controlled moves still work after a rejected non-finite op (moved ${d1}px left of 120 expected)"

# --- T4: silent server records the lost-reply resend and its frame (F2 mechanism) ---
Write-Host "== T4: lost-reply resend carries a dedup frame (F2) =="
Disable-ScheduledTask -TaskName 'BingeMote Input Broker' | Out-Null
Stop-AllBrokers
Start-Sleep -Seconds 1
$silentOut = Join-Path $env:TEMP 'bf-silent.txt'
Remove-Item $silentOut -ErrorAction SilentlyContinue
$silent = Start-Process $exe -ArgumentList '--broker-silent-server', '60', $silentOut -PassThru
Start-Sleep -Seconds 2
# let any broker-down backoff cool off so the helper actually tries the pipe
Start-Sleep -Seconds 32
foreach ($i in 1..3) { Invoke-Helper '/mouse/move' "{`"dx`":$($i * 7),`"dy`":0}" $token | Out-Null; Start-Sleep -Seconds 2 }
$silent.WaitForExit(45000) | Out-Null
$rec = @(Get-Content $silentOut -ErrorAction SilentlyContinue)
Write-Host "silent server recorded $($rec.Count) line(s):"
$rec | ForEach-Object { Write-Host "  $_" }
Write-Host "== helper.log tail over T4 =="
Get-Content (Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log') -Tail 15
# The mechanism proof: the SAME op line went out more than once, framed, so the broker
# can ack the replay without re-applying it. (A perfect run records 6 lines - 3 ops x
# first try + resend - but the assert only needs the one proven replay.)
Check ($rec.Count -ge 2) "the helper resends a lost-reply op ($($rec.Count) line(s) recorded)"
$framed = @($rec | Where-Object { $_ -match '#[0-9a-f]{8}\.[0-9a-f]{8}' })
Check ($framed.Count -ge 2) "resent ops carry a #session.id frame the broker can dedup"
$frameIds = @($framed | ForEach-Object { [regex]::Match($_, '#[0-9a-f]{8}\.[0-9a-f]{8}').Value })
Check ((@($frameIds | Group-Object | Where-Object { $_.Count -ge 2 })).Count -ge 1) "the resend carries the SAME frame, so the broker dedups the replay"

# --- restore + final sanity ---
Write-Host "== final: broker restored, helper functional =="
Enable-ScheduledTask -TaskName 'BingeMote Input Broker' | Out-Null
Start-ScheduledTask -TaskName 'BingeMote Input Broker'
$brokerProc = Wait-BrokerInSession $harnessSession 30
Check ($null -ne $brokerProc) "broker restored after the silent-server phase"
Start-Sleep -Seconds 32 # let the helper's post-failure backoff cool
[Probe]::SetCursorPos(500, 400) | Out-Null
Start-Sleep -Milliseconds 200
$bf = [Probe]::Pos()
Invoke-Helper '/mouse/move' '{"dx":60,"dy":0}' $token | Out-Null
Start-Sleep -Milliseconds 500
$af = [Probe]::Pos()
Check ([math]::Abs($af.X - $bf.X) -ge 40) "cursor moves after the full fault run"

# --- teardown ---
Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Remove-Item Env:BINGEMOTE_PAIR_CODE -ErrorAction SilentlyContinue

Write-Host ""
if ($script:failures.Count -gt 0) {
    Write-Host "BROKER FAULTS: $($script:failures.Count) check(s) failed:"
    $script:failures | ForEach-Object { Write-Host "  FAIL: $_" }
    exit 1
}
Write-Host "BROKER FAULTS PASSED"
