# BingeMote Helper STRESS tests (rev15): what the e2e proves once, this proves under load.
# Phases: sustained rapid mouse streams through the SYSTEM input broker, the same stream
# while a SYSTEM-integrity window holds the foreground (SwUSB condition), broker crash
# mid-stream with fallback + recovery, pipe connection churn + slot pile-up, HTTP API
# abuse, reboot-like full restarts, a mixed-op soak with a memory-growth budget, and a
# held-button move storm. Prints "ok:" lines; ends with HELPER STRESS PASSED.
$ErrorActionPreference = 'Stop'
$exe = "helper/out/BingeMote.exe"
if (-not (Test-Path $exe)) { throw "helper exe missing at $exe" }

function Assert($cond, $msg) { if (-not $cond) { throw "ASSERT FAILED: $msg" } ; Write-Host "ok: $msg" }
function Invoke-Helper($path, $body, $token) {
    $headers = @{}
    if ($token) { $headers['X-BingeMote-Token'] = $token }
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:8488$path" -Method Post -Body $body -Headers $headers -UseBasicParsing -TimeoutSec 15 -SkipHttpErrorCheck
    return @{ code = [int]$r.StatusCode; body = $r.Content }
}
function Wait-HelperUp($seconds) {
    foreach ($i in 1..($seconds * 2)) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); return $true
        } catch { Start-Sleep -Milliseconds 500 }
    }
    return $false
}
function Wait-BrokerInSession($sessionId) {
    foreach ($i in 1..60) {
        $proc = Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" | Where-Object { $_.CommandLine -match '--input-broker' -and $_.SessionId -eq $sessionId } | Select-Object -First 1
        if ($proc) { return $proc }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

# --- setup: firewall/URL-ACL like a real install, then run the helper and pair ---
$fw = Start-Process $exe -ArgumentList '--install-firewall' -Wait -PassThru
if ($fw.ExitCode -ne 0) { throw "install-firewall failed with exit $($fw.ExitCode)" }

Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1
$env:BINGEMOTE_PAIR_CODE = '654321'
$p = Start-Process $exe -PassThru
if (-not (Wait-HelperUp 20)) { throw "helper did not start listening on 8488" }

trap {
    Write-Host "== stress failure: $($_.Exception.Message) =="
    Write-Host "== at: $($_.InvocationInfo.PositionMessage) =="
    $logf = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
    if (Test-Path $logf) { Write-Host "== helper.log =="; Get-Content $logf } else { Write-Host "(no helper.log)" }
    Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe' or Name='UipiBlocker.exe'" | ForEach-Object { Write-Host "process: $($_.Name) pid=$($_.ProcessId) session=$($_.SessionId) cmd=$($_.CommandLine)" }
    $sysLog = 'C:\Windows\System32\config\systemprofile\AppData\Local\BingeMote\helper.log'
    if (Test-Path $sysLog) { Write-Host "== SYSTEM-profile helper.log =="; Get-Content $sysLog }
    foreach ($tn in 'BingeMote Input Broker','BingeMote UIPI Blocker') {
        try { $ti = Get-ScheduledTaskInfo -TaskName $tn; Write-Host "task ${tn}: LastRunTime=$($ti.LastRunTime) LastTaskResult=$($ti.LastTaskResult)" } catch { Write-Host "task ${tn}: not registered" }
    }
    throw
}

try {
    $r = Invoke-Helper '/pair' '{"code":"654321"}' $null
    Assert ($r.code -eq 200) "paired for the stress run"
    $token = ($r.body | ConvertFrom-Json).token

    $probeSrc = @"
using System;
using System.Runtime.InteropServices;
public static class Probe {
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    public static POINT Pos() { POINT p; GetCursorPos(out p); return p; }
    public static uint ForegroundPid() { uint pid; GetWindowThreadProcessId(GetForegroundWindow(), out pid); return pid; }
}
"@
    Add-Type -TypeDefinition $probeSrc

    # Register and start the broker with the exact script the installer ships.
    & powershell -NoProfile -ExecutionPolicy Bypass -File helper/register-broker.ps1 -ExePath (Resolve-Path $exe).Path
    Start-ScheduledTask -TaskName 'BingeMote Input Broker'
    $harnessSession = (Get-Process -Id $PID).SessionId
    $brokerProc = Wait-BrokerInSession $harnessSession
    Assert ($null -ne $brokerProc) "broker is running in the console session $harnessSession"
    $brokerPid = $brokerProc.ProcessId
    [Probe]::SetCursorPos(500, 400) | Out-Null
    Start-Sleep -Milliseconds 300
    $b0 = [Probe]::Pos()
    $r = Invoke-Helper '/mouse/move' '{"dx":60,"dy":0}' $token
    Assert ($r.code -eq 200) "warm-up move"
    Start-Sleep -Milliseconds 400
    $a0 = [Probe]::Pos()
    Assert ([math]::Abs($a0.X - $b0.X) -ge 40) "cursor moves through the broker before the stress begins"

    $userLog = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
    $sysLogPath = 'C:\Windows\System32\config\systemprofile\AppData\Local\BingeMote\helper.log'

    # --- Phase 1: sustained rapid mouse stream through the broker ---
    Write-Host "== phase 1: 3000-op rapid mouse stream =="
    [Probe]::SetCursorPos(500, 400) | Out-Null
    $fails = 0
    $t0 = Get-Date
    foreach ($i in 1..3000) {
        $dx = if ($i % 2 -eq 0) { 2 } else { -2 }
        $r = Invoke-Helper '/mouse/move' "{`"dx`":$dx,`"dy`":0}" $token
        if ($r.code -ne 200) { $fails++ }
    }
    $elapsed = ((Get-Date) - $t0).TotalSeconds
    $opsPerSec = [math]::Round(3000 / $elapsed, 1)
    Assert ($fails -eq 0) "3000 rapid moves, 0 failures ($opsPerSec ops/s over HTTP)"
    $still = Get-Process -Id $brokerPid -ErrorAction SilentlyContinue
    Assert ($null -ne $still) "broker process survived the stream (no restart, same pid $brokerPid)"
    $stillHelper = Get-Process -Id $p.Id -ErrorAction SilentlyContinue
    Assert ($null -ne $stillHelper) "helper process survived the stream"

    # --- Phase 2: the same load while a SYSTEM-integrity window holds the foreground ---
    Write-Host "== phase 2: stream under the SwUSB condition =="
    dotnet publish scripts/UipiBlocker -c Release -o "$env:TEMP\uipiblocker-stress" | Out-Host
    $blockerExe = Join-Path $env:TEMP 'uipiblocker-stress\UipiBlocker.exe'
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
    Assert ($null -ne $blockerProc) "blocker running in the console session"
    $fgIsBlocker = $false
    foreach ($i in 1..40) {
        if ([Probe]::ForegroundPid() -eq $blockerProc.ProcessId) { $fgIsBlocker = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Assert $fgIsBlocker "SYSTEM window holds the foreground (SwUSB condition)"
    [Probe]::SetCursorPos(500, 400) | Out-Null
    $fails = 0
    $t0 = Get-Date
    foreach ($i in 1..2000) {
        $dx = if ($i % 2 -eq 0) { 2 } else { -2 }
        $r = Invoke-Helper '/mouse/move' "{`"dx`":$dx,`"dy`":0}" $token
        if ($r.code -ne 200) { $fails++ }
        if ($i % 100 -eq 0) {
            $r = Invoke-Helper '/mouse/button' '{"button":"left","down":true}' $token
            if ($r.code -ne 200) { $fails++ }
            $r = Invoke-Helper '/mouse/button' '{"button":"left","down":false}' $token
            if ($r.code -ne 200) { $fails++ }
            $r = Invoke-Helper '/mouse/scroll' '{"dy":-120}' $token
            if ($r.code -ne 200) { $fails++ }
        }
    }
    $elapsed = ((Get-Date) - $t0).TotalSeconds
    $opsPerSec = [math]::Round(2060 / $elapsed, 1)
    Assert ($fails -eq 0) "2060 mixed ops under a foreground SYSTEM window, 0 failures ($opsPerSec ops/s)"
    # Input still lands while blocked: a real move under the blocker must move the cursor.
    $b1 = [Probe]::Pos()
    $r = Invoke-Helper '/mouse/move' '{"dx":80,"dy":0}' $token
    Start-Sleep -Milliseconds 400
    $a1 = [Probe]::Pos()
    Assert ([math]::Abs($a1.X - $b1.X) -ge 40) "input lands at SYSTEM integrity during the load ($($b1.X) -> $($a1.X))"
    Get-Process UipiBlocker -ErrorAction SilentlyContinue | Stop-Process -Force
    Unregister-ScheduledTask -TaskName 'BingeMote UIPI Blocker' -Confirm:$false -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    $still = Get-Process -Id $brokerPid -ErrorAction SilentlyContinue
    Assert ($null -ne $still) "broker survived the blocked-foreground stream"

    # --- Phase 3: broker crash mid-stream; fallback, then recovery ---
    Write-Host "== phase 3: broker crash mid-stream =="
    $streamOut = Join-Path $env:TEMP 'stress-stream.txt'
    Remove-Item $streamOut -Force -ErrorAction SilentlyContinue
    $streamJob = Start-Job -ArgumentList $token, $streamOut -ScriptBlock {
        param($tok, $out)
        $fails = 0
        foreach ($i in 1..6000) {
            try {
                $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8488/mouse/move' -Method Post -Body '{"dx":1,"dy":0}' -Headers @{ 'X-BingeMote-Token' = $tok } -UseBasicParsing -TimeoutSec 15 -SkipHttpErrorCheck
                if ([int]$r.StatusCode -ne 200) { $fails++ }
            } catch { $fails++ }
        }
        "fails=$fails" | Out-File $out
    }
    Start-Sleep -Seconds 3
    Stop-Process -Id $brokerPid -Force
    Write-Host "broker pid $brokerPid killed mid-stream"
    $null = Wait-Job $streamJob -Timeout 300
    $streamResult = if (Test-Path $streamOut) { Get-Content $streamOut -Raw } else { '(stream job wrote nothing)' }
    Remove-Job $streamJob -Force -ErrorAction SilentlyContinue
    Assert ($streamResult -match 'fails=0') "the in-flight stream survived the broker crash with 0 failed requests ($streamResult)"
    # Probe ops that MUST cross the dead broker, then give DiagnosticLog's batched
    # flush a few seconds to land the fallback line (run 43665456190 raced both:
    # the stream ended before the kill, and the log check beat the flush).
    foreach ($i in 1..3) { Invoke-Helper '/mouse/move' '{"dx":1,"dy":0}' $token | Out-Null; Start-Sleep -Milliseconds 500 }
    $fellBack = $false
    foreach ($i in 1..20) {
        $helperLog = Get-Content $userLog -Raw
        if ($helperLog -match 'input_routing=local') { $fellBack = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Assert $fellBack "helper fell back to local input when the broker died"
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "helper still serving after the broker crash"
    # Recovery: restart the broker, restart the helper (fresh routing state), and prove
    # input routes through the broker again.
    Start-ScheduledTask -TaskName 'BingeMote Input Broker'
    $brokerProc = Wait-BrokerInSession $harnessSession
    Assert ($null -ne $brokerProc) "broker is back after the crash"
    $brokerPid = $brokerProc.ProcessId
    Stop-Process -Id $p.Id -Force
    Start-Sleep -Seconds 1
    Remove-Item Env:BINGEMOTE_PAIR_CODE -ErrorAction SilentlyContinue
    $p = Start-Process $exe -PassThru
    if (-not (Wait-HelperUp 20)) { throw "helper did not restart after broker recovery" }
    [Probe]::SetCursorPos(500, 400) | Out-Null
    Start-Sleep -Milliseconds 300
    $b2 = [Probe]::Pos()
    $r = Invoke-Helper '/mouse/move' '{"dx":60,"dy":0}' $token
    Assert ($r.code -eq 200) "move after broker recovery"
    Start-Sleep -Milliseconds 400
    $a2 = [Probe]::Pos()
    Assert ([math]::Abs($a2.X - $b2.X) -ge 40) "cursor moves again after broker recovery ($($b2.X) -> $($a2.X))"
    $tail = Get-Content $userLog -Tail 10
    Assert (($tail | Select-String 'input_routing=broker').Count -gt 0) "input routed back through the broker after recovery"

    # --- Phase 4: pipe connection churn + slot pile-up ---
    Write-Host "== phase 4: pipe churn and pile-up =="
    $churnFails = 0
    foreach ($i in 1..150) {
        try {
            $pc = New-Object System.IO.Pipes.NamedPipeClientStream('.', 'BingeMoteInputBroker', 'InOut')
            $pc.Connect(2000)
            $pc.Dispose()
        } catch { $churnFails++ }
    }
    Write-Host "churn connects that errored locally: $churnFails (broker-side rejection is expected and fine)"
    [Probe]::SetCursorPos(500, 400) | Out-Null
    Start-Sleep -Milliseconds 300
    $b3 = [Probe]::Pos()
    $r = Invoke-Helper '/mouse/move' '{"dx":60,"dy":0}' $token
    Assert ($r.code -eq 200) "move after 150 rapid connect/drop cycles"
    Start-Sleep -Milliseconds 400
    $a3 = [Probe]::Pos()
    Assert ([math]::Abs($a3.X - $b3.X) -ge 40) "broker healthy after churn ($($b3.X) -> $($a3.X))"
    $still = Get-Process -Id $brokerPid -ErrorAction SilentlyContinue
    Assert ($null -ne $still) "broker survived 150 churn connections"
    # Pile-up: 6 authorized (hooked-exe) clients hold connections while the helper works.
    $holds = @()
    foreach ($i in 1..6) {
        $holds += Start-Process $exe -ArgumentList '--broker-hold', '20' -PassThru
    }
    Start-Sleep -Seconds 2
    $fails = 0
    foreach ($i in 1..50) {
        $r = Invoke-Helper '/mouse/move' '{"dx":-2,"dy":0}' $token
        if ($r.code -ne 200) { $fails++ }
    }
    Assert ($fails -eq 0) "50 moves while 6 extra clients hold broker connections, 0 failures"
    foreach ($h in $holds) { $null = $h.WaitForExit(30000) }
    $still = Get-Process -Id $brokerPid -ErrorAction SilentlyContinue
    Assert ($null -ne $still) "broker survived the connection pile-up"

    # --- Phase 5: HTTP API abuse ---
    Write-Host "== phase 5: HTTP abuse =="
    $helperPid = $p.Id
    $r = Invoke-Helper '/mouse/move' '{this is not json' $token
    Assert (($r.code -eq 400 -or $r.code -eq 500)) "malformed JSON answered with an error, not a crash (got $($r.code))"
    $big = '{"text":"' + ('a' * 200000) + '"}'
    $r = Invoke-Helper '/type' $big $token
    Assert (($r.code -ge 400 -and $r.code -lt 600)) "200KB body rejected, not served (got $($r.code))"
    $r = Invoke-Helper '/mouse/move' '' $token
    Assert (($r.code -eq 400 -or $r.code -eq 500 -or $r.code -eq 200)) "empty body handled (got $($r.code))"
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8488/mouse/move' -Method Get -UseBasicParsing -TimeoutSec 15 -SkipHttpErrorCheck
    Assert (([int]$r.StatusCode -ge 400)) "GET on a POST endpoint refused (got $([int]$r.StatusCode))"
    $fails = 0
    $t0 = Get-Date
    foreach ($i in 1..500) {
        $r = Invoke-Helper '/ping' '{}' $token
        if ($r.code -ne 200) { $fails++ }
    }
    $elapsed = ((Get-Date) - $t0).TotalSeconds
    $opsPerSec = [math]::Round(500 / $elapsed, 1)
    Assert ($fails -eq 0) "500 rapid pings, 0 failures ($opsPerSec ops/s)"
    $stillHelper = Get-Process -Id $helperPid -ErrorAction SilentlyContinue
    Assert ($null -ne $stillHelper) "helper survived the abuse phase (same pid $helperPid)"
    $r = Invoke-Helper '/mouse/move' '{"dx":10,"dy":0}' $token
    Assert ($r.code -eq 200) "input still served after the abuse phase"

    # --- Phase 6: reboot-like full restarts ---
    Write-Host "== phase 6: reboot-like restarts =="
    foreach ($cycle in 1..4) {
        Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
        Start-Sleep -Seconds 3
        Start-ScheduledTask -TaskName 'BingeMote Input Broker'
        $brokerProc = Wait-BrokerInSession $harnessSession
        Assert ($null -ne $brokerProc) "cycle ${cycle}: broker came up"
        $brokerPid = $brokerProc.ProcessId
        $p = Start-Process $exe -PassThru
        if (-not (Wait-HelperUp 20)) { throw "cycle ${cycle}: helper did not start" }
        $r = Invoke-Helper '/ping' '{}' $token
        Assert ($r.code -eq 200) "cycle ${cycle}: saved token still works"
        [Probe]::SetCursorPos(500, 400) | Out-Null
        Start-Sleep -Milliseconds 300
        $bC = [Probe]::Pos()
        $r = Invoke-Helper '/mouse/move' '{"dx":-60,"dy":0}' $token
        Assert ($r.code -eq 200) "cycle ${cycle}: move works"
        Start-Sleep -Milliseconds 400
        $aC = [Probe]::Pos()
        Assert ([math]::Abs($aC.X - $bC.X) -ge 40) "cycle ${cycle}: cursor moved ($($bC.X) -> $($aC.X))"
    }

    # --- Phase 7: mixed-op soak with a memory budget ---
    Write-Host "== phase 7: 150s mixed soak + memory budget =="
    $helperPid = $p.Id
    $memB0 = (Get-Process -Id $brokerPid).WorkingSet64
    $memH0 = (Get-Process -Id $helperPid).WorkingSet64
    $fails = 0
    $t0 = Get-Date
    $i = 0
    while (((Get-Date) - $t0).TotalSeconds -lt 150) {
        $i++
        switch ($i % 4) {
            0 { $r = Invoke-Helper '/mouse/move' '{"dx":-3,"dy":1}' $token }
            1 { $r = Invoke-Helper '/mouse/scroll' '{"dy":-120}' $token }
            2 { $r = Invoke-Helper '/mouse/scroll' '{"dy":120}' $token }
            3 { $r = Invoke-Helper '/mouse/move' '{"dx":3,"dy":-1}' $token }
        }
        if ($r.code -ne 200) { $fails++ }
        Start-Sleep -Milliseconds 100
    }
    Assert ($fails -eq 0) "soak: $i mixed ops over 150s, 0 failures"
    $memB1 = (Get-Process -Id $brokerPid -ErrorAction SilentlyContinue).WorkingSet64
    $memH1 = (Get-Process -Id $helperPid -ErrorAction SilentlyContinue).WorkingSet64
    Assert ($null -ne $memB1 -and $null -ne $memH1) "broker and helper alive after the soak"
    $growB = [math]::Round(($memB1 - $memB0) / 1MB, 1)
    $growH = [math]::Round(($memH1 - $memH0) / 1MB, 1)
    Write-Host "memory growth over the soak: broker ${growB}MB, helper ${growH}MB"
    Assert ($growB -lt 64) "broker memory growth under budget (${growB}MB < 64MB)"
    Assert ($growH -lt 64) "helper memory growth under budget (${growH}MB < 64MB)"

    # --- Phase 8: held-button move storm (no stuck clicks) ---
    Write-Host "== phase 8: held-button storm =="
    $r = Invoke-Helper '/mouse/button' '{"button":"left","down":true}' $token
    Assert ($r.code -eq 200) "button down"
    $fails = 0
    foreach ($i in 1..300) {
        $dx = if ($i % 2 -eq 0) { 2 } else { -2 }
        $r = Invoke-Helper '/mouse/move' "{`"dx`":$dx,`"dy`":0}" $token
        if ($r.code -ne 200) { $fails++ }
    }
    $r = Invoke-Helper '/mouse/button' '{"button":"left","down":false}' $token
    if ($r.code -ne 200) { $fails++ }
    Assert ($fails -eq 0) "300 moves with the button held + release, 0 failures"
    foreach ($i in 1..20) {
        $r = Invoke-Helper '/mouse/move' '{"dx":-2,"dy":0}' $token
        Assert ($r.code -eq 200) "post-storm move $i"
    }

    # --- Phase 9: functional bug hunt (findings are reported, crashes fail the run) ---
    Write-Host "== phase 9: functional bug hunt =="
    $findings = @()
    $bugs = 0
    function Hunt($desc, $code, $okCodes) {
        if ($okCodes -contains $code) { Write-Host "ok: $desc (got $code)"; return $true }
        Write-Host "finding: $desc - got $code, expected one of $($okCodes -join '/')"
        return $false
    }
    # Every supported key works; unknown keys are refused cleanly.
    foreach ($k in 'enter','backspace','escape','space','tab','up','down','left','right') {
        $r = Invoke-Helper '/key' "{`"key`":`"$k`"}" $token
        if (-not (Hunt "key $k" $r.code @(200))) { $script:findings += "key $k -> $($r.code)" }
    }
    $r = Invoke-Helper '/key' '{"key":"f13"}' $token
    if (-not (Hunt 'unknown key refused' $r.code @(400))) { $script:findings += "unknown key -> $($r.code) (500 would mean user error is reported as a server crash)" }
    $r = Invoke-Helper '/key' '{"key":"enter","junk":1}' $token
    if (-not (Hunt 'key with extra field tolerated' $r.code @(200,400))) { $script:findings += "extra field -> $($r.code)" }
    # Typing: unicode, the documented max length, and just over it.
    $uni = 'héllo wörld - café 123'
    $r = Invoke-Helper '/type' ("{`"text`":`"$uni`"}") $token
    if (-not (Hunt 'unicode text' $r.code @(200))) { $script:findings += "unicode type -> $($r.code)" }
    $maxText = 'x' * 2000
    $r = Invoke-Helper '/type' ("{`"text`":`"$maxText`"}") $token
    if (-not (Hunt '2000-char text (documented max)' $r.code @(200))) { $script:findings += "2000-char type -> $($r.code)" }
    $overText = 'x' * 2001
    $r = Invoke-Helper '/type' ("{`"text`":`"$overText`"}") $token
    if (-not (Hunt '2001-char text refused' $r.code @(400))) { $script:findings += "2001-char type -> $($r.code)" }
    # Mouse magnitudes: clamped or refused, never a crash.
    $r = Invoke-Helper '/mouse/move' '{"dx":100000,"dy":-100000}' $token
    if (-not (Hunt 'huge move clamped/refused' $r.code @(200,400))) { $script:findings += "huge move -> $($r.code)" }
    $r = Invoke-Helper '/mouse/move' '{"dx":"abc","dy":0}' $token
    if (-not (Hunt 'non-numeric move refused' $r.code @(400))) { $script:findings += "non-numeric move -> $($r.code)" }
    $r = Invoke-Helper '/mouse/move' '{"dx":1e999,"dy":0}' $token
    if (-not (Hunt 'non-finite move refused' $r.code @(400))) { $script:findings += "non-finite move -> $($r.code)" }
    $r = Invoke-Helper '/mouse/scroll' '{"dy":100000}' $token
    if (-not (Hunt 'huge scroll clamped/refused' $r.code @(200,400))) { $script:findings += "huge scroll -> $($r.code)" }
    $r = Invoke-Helper '/mouse/zoom' '{"delta":100}' $token
    if (-not (Hunt 'huge zoom clamped/refused' $r.code @(200,400))) { $script:findings += "huge zoom -> $($r.code)" }
    $r = Invoke-Helper '/mouse/button' '{"button":"middle2","down":true}' $token
    if (-not (Hunt 'unknown button refused' $r.code @(400))) { $script:findings += "unknown button -> $($r.code)" }
    $r = Invoke-Helper '/mouse/button' '{"button":"left","down":"yes"}' $token
    if (-not (Hunt 'wrong-typed down refused' $r.code @(400))) { $script:findings += "wrong-typed down -> $($r.code)" }
    # Volume validation (only when the machine has an audio device).
    $vg = Invoke-WebRequest -Uri 'http://127.0.0.1:8488/volume/get' -Method Post -Body '{}' -Headers @{ 'X-BingeMote-Token' = $token } -UseBasicParsing -TimeoutSec 15 -SkipHttpErrorCheck
    if ([int]$vg.StatusCode -eq 200) {
        $r = Invoke-Helper '/volume/set' '{"volume":101}' $token
        if (-not (Hunt 'volume 101 clamped/refused' $r.code @(200,400))) { $script:findings += "volume 101 -> $($r.code)" }
        $r = Invoke-Helper '/volume/set' '{"volume":-5}' $token
        if (-not (Hunt 'volume -5 clamped/refused' $r.code @(200,400))) { $script:findings += "volume -5 -> $($r.code)" }
        $r = Invoke-Helper '/volume/set' '{"volume":"loud"}' $token
        if (-not (Hunt 'volume text refused' $r.code @(400))) { $script:findings += "volume text -> $($r.code)" }
        $r = Invoke-Helper '/volume/set' '{"volume":50}' $token
        if (-not (Hunt 'volume 50 works after the bad ones' $r.code @(200))) { $script:findings += "volume 50 -> $($r.code)" }
    } else {
        Write-Host "no audio device on this machine ($([int]$vg.StatusCode)); volume validation skipped - e2e covers the 503 path"
    }
    # Movies folder jail: a bad folder is refused and the config survives; traversal stays jailed.
    $r = Invoke-Helper '/config' '{"moviesFolder":"C:/definitely/not/a/real/folder"}' $token
    if (-not (Hunt 'nonexistent movies folder refused' $r.code @(400,404))) { $script:findings += "bad movies folder -> $($r.code)" }
    $movies = Join-Path $env:TEMP 'bingemote-stress-movies'
    New-Item -ItemType Directory -Force $movies | Out-Null
    Set-Content (Join-Path $movies 'clip.mkv') 'fake'
    Set-Content (Join-Path $movies 'notes.txt') 'not a video'
    $folderJson = '{"moviesFolder":"' + ($movies -replace '\\','/') + '"}'
    $r = Invoke-Helper '/config' $folderJson $token
    if (-not (Hunt 'real movies folder accepted' $r.code @(200))) { $script:findings += "real movies folder -> $($r.code)" }
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8488/movies/list?path=%2e%2e' -Method Get -Headers @{ 'X-BingeMote-Token' = $token } -UseBasicParsing -TimeoutSec 15 -SkipHttpErrorCheck
    if ([int]$r.StatusCode -eq 200) { $script:bugs++; Write-Host "BUG: URL-encoded traversal escaped the movies jail (200)" } else { Write-Host "ok: URL-encoded traversal refused ($([int]$r.StatusCode))" }
    $r = Invoke-Helper '/movies/play' '{"path":"notes.txt"}' $token
    if ([int]$r.code -eq 200) { $script:bugs++; Write-Host "BUG: a non-video file played (200)" } else { Write-Host "ok: non-video play refused ($($r.code))" }
    $r = Invoke-Helper '/movies/play' '{"path":"ghost.mkv"}' $token
    if ([int]$r.code -eq 200) { $script:bugs++; Write-Host "BUG: a nonexistent file played (200)" } else { Write-Host "ok: nonexistent play refused ($($r.code))" }
    $r = Invoke-Helper '/pair' '{"nocode":1}' $null
    if (-not (Hunt 'pair without a code field refused' $r.code @(400,403))) { $script:findings += "pair without code field -> $($r.code)" }
    # The helper must still be fully healthy after the hunt.
    $stillHelper = Get-Process -Id $p.Id -ErrorAction SilentlyContinue
    Assert ($null -ne $stillHelper) "helper survived the bug hunt"
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "helper still serving after the bug hunt"
    Assert ($bugs -eq 0) "no hard bugs found ($bugs)"
    if ($findings.Count -gt 0) {
        Write-Host "== bug-hunt findings (non-fatal) =="
        $findings | ForEach-Object { Write-Host "finding: $_" }
    } else {
        Write-Host "ok: bug hunt clean - no findings"
    }

    $summary = @{
        phases = 9
        soakOps = $i
        brokerMemGrowthMB = $growB
        helperMemGrowthMB = $growH
        bugHuntFindings = $findings
        hardBugs = $bugs
        at = (Get-Date).ToString('o')
    } | ConvertTo-Json
    Set-Content helper\stress-summary.json $summary
    Write-Host "HELPER STRESS PASSED"
} finally {
    Get-Job -ErrorAction SilentlyContinue | Stop-Job -ErrorAction SilentlyContinue
    Get-Job -ErrorAction SilentlyContinue | Remove-Job -Force -ErrorAction SilentlyContinue
    Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
    Get-Process UipiBlocker -ErrorAction SilentlyContinue | Stop-Process -Force
    Unregister-ScheduledTask -TaskName 'BingeMote UIPI Blocker' -Confirm:$false -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'BingeMote Input Broker' -Confirm:$false -ErrorAction SilentlyContinue
    try { netsh advfirewall firewall delete rule name="BingeMote" | Out-Null } catch { }
}
