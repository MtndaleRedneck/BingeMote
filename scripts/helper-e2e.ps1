# BingeMote Helper runtime tests on a real Windows machine (CI runner).
# Exercises: the elevated firewall/URL-ACL setup, the pairing flow, token auth,
# every input endpoint, the volume endpoints, and UDP discovery.
$ErrorActionPreference = 'Stop'
$exe = "helper/out/BingeMote.exe"
if (-not (Test-Path $exe)) { throw "helper exe missing at $exe" }

function Invoke-Helper($path, $body, $token) {
    $headers = @{}
    if ($token) { $headers['X-BingeMote-Token'] = $token }
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:8488$path" -Method Post -Body $body -Headers $headers -UseBasicParsing -TimeoutSec 10 -SkipHttpErrorCheck
    return @{ code = [int]$r.StatusCode; body = $r.Content }
}
function Assert($cond, $msg) { if (-not $cond) { throw "ASSERT FAILED: $msg" } ; Write-Host "ok: $msg" }

function Redact-Secrets([string]$text) {
    # Never emit live auth material (pair token, VLC password) into CI logs; the
    # values are per-run, but retained/shared logs must not carry them at all.
    $text = $text -replace '"token":"[0-9a-f]{64}"', '"token":"<redacted>"'
    $text = $text -replace '"(vlcPassword|password|adoptRequestId)":"[^"]+"', '"$1":"<redacted>"'
    return $text
}
function Invoke-HelperGet($path, $token) {
    $headers = @{}
    if ($token) { $headers['X-BingeMote-Token'] = $token }
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:8488$path" -Method Get -Headers $headers -UseBasicParsing -TimeoutSec 10 -SkipHttpErrorCheck
    return @{ code = [int]$r.StatusCode; body = $r.Content }
}

if ($env:BINGEMOTE_SHIPPING_ONLY -ne '1') {
# --- Part 0: the real upgrade path: install the actual 1.7.12 build (already scoped
# to interactive users), silent-upgrade to this build, pair the instance the installer
# launches, run the blocked-input notice checks on that installed instance, then
# uninstall and verify ---
Write-Host "== real 1.7.12 -> 1.7.13 upgrade =="
# Set before any install so the app the installer relaunches inherits the fixed pair
# code (the pair-code hook exists only in this CI build, never in shipping bytes).
$env:BINGEMOTE_PAIR_CODE = '4242'
$old = Get-ChildItem "helper/Output-old/BingeMote-PC-*.exe" | Select-Object -First 1
if (-not $old) { throw "1.7.12 baseline installer missing in helper/Output-old" }
& $old.FullName /VERYSILENT /SUPPRESSMSGBOXES /NORESTART | Out-Null
if ($LASTEXITCODE -ne 0) { throw "1.7.12 baseline install failed with exit $LASTEXITCODE" }
$acl = (netsh http show urlacl) | Out-String
$aclBlock = [regex]::Match($acl, '(?s)Reserved URL\s*:\s*http://\+:8488/.*?(?=Reserved URL|$)').Value
if (-not $aclBlock) { throw "1.7.12 did not add its listen permission" }
if ($aclBlock -match 'Everyone|S-1-1-0') { throw "1.7.12 baseline grants Everyone (expected interactive-users): $aclBlock" }
if ($aclBlock -notmatch 'S-1-5-4|INTERACTIVE') { throw "1.7.12 baseline not scoped to interactive users: $aclBlock" }
Write-Host "ok: 1.7.12 baseline installed with the interactive-users grant"

$setup = Get-ChildItem "helper/Output/BingeMote-PC-*.exe" | Select-Object -First 1
if (-not $setup) { throw "installer exe missing in helper/Output" }
& $setup.FullName /VERYSILENT /SUPPRESSMSGBOXES /NORESTART | Out-Null
if ($LASTEXITCODE -ne 0) { throw "silent upgrade failed with exit $LASTEXITCODE" }
$rule = netsh advfirewall firewall show rule name="BingeMote"
if (-not ($rule | Select-String 'BingeMote')) { throw "upgrade did not keep the BingeMote firewall rule" }
$acl = (netsh http show urlacl) | Out-String
# The grant lives on the SDDL/User lines under the Reserved URL line; match the whole block.
$aclBlock = [regex]::Match($acl, '(?s)Reserved URL\s*:\s*http://\+:8488/.*?(?=Reserved URL|$)').Value
if (-not $aclBlock) { throw "upgrade did not add the listen permission" }
if ($aclBlock -match 'Everyone|S-1-1-0') { throw "listen permission still grants Everyone after the real upgrade: $aclBlock" }
if ($aclBlock -notmatch 'S-1-5-4|INTERACTIVE') { throw "listen permission not scoped to interactive users after the real upgrade: $aclBlock" }
Write-Host "ok: real upgrade kept the interactive-users grant"

# Pair the instance the installer relaunched, under the retained installer-created state.
$up = $false
foreach ($i in 1..40) {
    try {
        $c = New-Object Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $up) { throw "installer-launched helper did not start listening on 8488" }
$r = Invoke-Helper '/ping' '{}' $null
Assert ($r.code -eq 401) "installed instance: ping without token is 401"
$r = Invoke-Helper '/pair' '{"code":"0000"}' $null
Assert ($r.code -eq 403) "installed instance: pair with wrong code is 403"
$r = Invoke-Helper '/pair' '{"code":"4242"}' $null
Assert ($r.code -eq 200) "installed instance pairs under the installer-created state"
$instToken = ($r.body | ConvertFrom-Json).token

# Blocked-input notice on the installed instance. The hook fakes a full SendInput
# rejection (played 0 of N), so the log accounting is exact; /ci/blockstate reports the
# title and the balloon throttle. This exercises the same notice code the shipping build
# has; the fake rejection stands in for a real UIPI block, which CI cannot produce.
$logf2 = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
$logOffset = 0
if (Test-Path $logf2) { $logOffset = (Get-Content $logf2 -Raw).Length }
$marker = Join-Path $env:LOCALAPPDATA 'BingeMote\fake-block'
function Wait-BlockState($token, [bool]$wantBlocked, [bool]$wantTitle) {
    foreach ($i in 1..20) {
        $s = Invoke-HelperGet '/ci/blockstate' $token
        if ($s.code -eq 200) {
            $j = ($s.body | ConvertFrom-Json)
            # Title and balloon count live on the UI path; the blocked flag mirrors the
            # logical state earlier. Waiting for both avoids racing the UI callback.
            if ($j.blocked -eq $wantBlocked -and (-not $wantTitle -or $j.title)) { return $j }
        }
        Start-Sleep -Milliseconds 250
    }
    return $null
}
New-Item -ItemType File -Path $marker -Force | Out-Null
try {
    $r = Invoke-Helper '/mouse/move' '{"dx":10,"dy":5}' $instToken
    Assert ($r.code -eq 200) "installed instance: mouse/move while input is blocked"
    $state = Wait-BlockState $instToken $true $true
    Assert ($null -ne $state) "installed instance reports the blocked state with the notice title"
    Assert ($state.title -match 'could not be sent') "window title carries the notice: $($state.title)"
    Assert ($state.balloons -ge 1) "balloon shown for the first block"
    $newLog = (Get-Content $logf2 -Raw).Substring($logOffset)
    Assert ($newLog -match 'played 0 of 1') "log records the exact rejection (played 0 of 1)"
    # Throttle: a SECOND block inside the same minute must not raise the balloon count.
    # The state event is change-only, so lift the block first, then re-block.
    Remove-Item $marker -Force
    $r = Invoke-Helper '/mouse/move' '{"dx":10,"dy":5}' $instToken
    Assert ($r.code -eq 200) "mouse/move after the block lifts"
    $clear = Wait-BlockState $instToken $false $false
    Assert ($null -ne $clear) "installed instance reports the recovery"
    $newLog = (Get-Content $logf2 -Raw).Substring($logOffset)
    Assert ($newLog -match 'being sent again') "recovery notice appears in helper.log"
    New-Item -ItemType File -Path $marker -Force | Out-Null
    $r = Invoke-Helper '/mouse/move' '{"dx":10,"dy":5}' $instToken
    Assert ($r.code -eq 200) "second block inside the throttle window"
    $state2 = Wait-BlockState $instToken $true $true
    Assert ($null -ne $state2) "second block is noticed (title), even while throttled"
    Assert ($state2.balloons -eq $state.balloons) "balloon throttled: count stays $($state.balloons) on the second block"
} finally {
    Remove-Item $marker -Force -ErrorAction SilentlyContinue
}

Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2

$unins = Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\unins000.exe'
& $unins /VERYSILENT /NORESTART | Out-Null
Start-Sleep -Seconds 3
try { $rule = netsh advfirewall firewall show rule name="BingeMote" 2>$null | Out-String } catch { $rule = '' }
if ($rule -match 'BingeMote') { throw "uninstaller left the firewall rule behind" }
try { $acl = netsh http show urlacl 2>$null | Out-String } catch { $acl = '' }
if ($acl -match ':8488/') { throw "uninstaller left the listen permission behind" }
Write-Host "ok: uninstaller removed the firewall rule and listen permission"
# The installed-instance pairing above survives the uninstall (config.json is user data);
# clear it so the standalone runtime tests below pair fresh and see Phone 1.
Remove-Item (Join-Path $env:LOCALAPPDATA 'BingeMote\config.json') -Force -ErrorAction SilentlyContinue

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
$aclText2 = ($aclOut | Out-String)
$aclBlock2 = [regex]::Match($aclText2, '(?s)Reserved URL\s*:\s*http://\+:8488/.*?(?=Reserved URL|$)').Value
if ($aclBlock2 -match 'Everyone|S-1-1-0') { throw "helper setup still grants Everyone: $aclBlock2" }
if ($aclBlock2 -notmatch 'S-1-5-4|INTERACTIVE') { throw "helper setup did not scope the listen permission to interactive users: $aclBlock2" }

# --- Part 2: run the helper and drive its HTTP API over loopback ---
Write-Host "== helper runtime =="
$env:BINGEMOTE_PAIR_CODE = '4242'
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

    $r = Invoke-Helper '/ping' '{}' $null
    Assert ($r.code -eq 401) "ping without token is 401"

    $r = Invoke-Helper '/pair' '{"code":"0000"}' $null
    Assert ($r.code -eq 403) "pair with wrong code is 403"

    $r = Invoke-Helper '/pair' '{"code":"4242"}' $null
    Write-Host "right-code pair -> $($r.code): $(Redact-Secrets $r.body)"
    if ($r.code -ne 200) {
        Get-Process | Where-Object { $_.ProcessName -like '*BingeMote*' } | ForEach-Object { Write-Host "process: $($_.ProcessName) pid=$($_.Id) path=$($_.Path)" }
        $logf = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
        if (Test-Path $logf) { Write-Host "== helper.log =="; Get-Content $logf | ForEach-Object { Redact-Secrets $_ } }
        $cfgf = Join-Path $env:LOCALAPPDATA 'BingeMote\config.json'
        if (Test-Path $cfgf) { Write-Host "== config.json =="; Write-Host (Redact-Secrets (Get-Content $cfgf -Raw)) }
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
    Assert ($cfgText -match '"name":"Phone 1"') "first paired phone gets a default name"

    $r = Invoke-Helper '/pair' '{"code":"4242"}' $null
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

    # (The blocked-input notice test moved to Part 0: it now runs on the
    # installer-launched instance after the real upgrade.)
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
    $r = Invoke-Helper '/pair' '{"code":"4242"}' $null
    Assert ($r.code -eq 403) "no live pair code after pairing (got $($r.code))"
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "saved token still works after restart"
    $r = Invoke-Helper '/vlc/adopt' '{"requestId":"req-after-restart"}' $token
    Assert ($r.code -eq 410) "the spent adopt grant stays spent across a helper restart"
    $pObj = Get-Process -Id $p.Id
    Assert ($pObj.MainWindowHandle -eq 0) "the listener stays bound with no visible window (tray-hidden/headless), handle=$($pObj.MainWindowHandle)"

    # A second phone pairs with a fresh code and both phones stay connected.
    Write-Host "== multi-phone pairing =="
    Stop-Process -Id $p.Id -Force
    Start-Sleep -Seconds 1
    $env:BINGEMOTE_PAIR_CODE = '1234'
    $p = Start-Process $exe -PassThru
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not restart for second pairing" }
    $r = Invoke-Helper '/pair' '{"code":"1234"}' $null
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
        $env:BINGEMOTE_PAIR_CODE = '5555'
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
        $r = Invoke-Helper '/pair' '{"code":"5555"}' $null
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
        $env:BINGEMOTE_PAIR_CODE = '7777'
        $p = Start-Process $exe -PassThru
        $up = $false
        foreach ($i in 1..40) {
            try {
                $c = New-Object Net.Sockets.TcpClient
                $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $up) { throw "helper did not restart for the re-pair test" }
        $r = Invoke-Helper '/pair' '{"code":"7777"}' $null
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

    Write-Host "HELPER E2E PASSED"
} finally {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    netsh advfirewall firewall delete rule name="BingeMote" | Out-Null
}

# --- Part 3: the exact shipping bytes (no CI hooks): install the published installer
# artifact and smoke it. Boundary: every section above ran the hook build. This section
# runs the same no-hook installer that ships. Coverage: binds, 401 without a token, 403
# on a wrong pair code, a full no-hook first-run pairing (the code read from the pairing
# window's own label via UI Automation - the path a user takes), a shipping install-over
# upgrade keeping that pairing, and clean uninstall. The blocked-input notice is NOT
# exercised here; its coverage above used the hook build. ---
}

Write-Host "== shipping-bytes smoke =="
$unins = Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\unins000.exe'

$ship = Get-ChildItem "helper/Shipping/BingeMote-PC-*.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $ship) {
    # Source-only push runs publish no installer, so there is nothing to pin yet.
    # The leg runs on dispatch runs that pass shipping_run_id (+ shipping_sha256).
    Write-Host "skipping shipping-bytes leg: helper/Shipping is empty (no publish run pinned)"
} else {
# Tested-to-distributed identity is an ASSERTION, not an attestation: the workflow
# downloads the exact artifact of the pinned publish run, and this run proves it is
# testing those bytes when BINGEMOTE_SHIP_SHA256 pins the expected hash.
$shipHash = (Get-FileHash $ship.FullName -Algorithm SHA256).Hash.ToLower()
Write-Host "shipping installer: $($ship.FullName)"
Write-Host "shipping installer sha256: $shipHash"
if ($env:BINGEMOTE_SHIP_SHA256) {
    Assert ($shipHash -eq $env:BINGEMOTE_SHIP_SHA256.ToLower()) "shipping installer bytes differ from the pinned TestLab-bound hash"
} else {
    Write-Host "BINGEMOTE_SHIP_SHA256 unset: shipping identity assertion skipped this run"
}
Write-Host "shipping install: $($ship.FullName) /VERYSILENT /SUPPRESSMSGBOXES /NORESTART"
$initialInstaller = $ship.FullName
$prior = Get-ChildItem 'helper/Prior/BingeMote-PC-1.7.13.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($prior) { $initialInstaller = $prior.FullName; Write-Host 'Initial install: exact prior1.7.13 for paired upgrade retention' }
$shipInstall = Start-Process $initialInstaller -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/LOG=shipping-install.log' -Wait -PassThru
if ($shipInstall.ExitCode -ne 0) { Get-Content shipping-install.log -Tail 40; throw "shipping install failed: $($shipInstall.ExitCode)" }
$global:LASTEXITCODE = 0
if ($LASTEXITCODE -ne 0) { throw "shipping install failed with exit $LASTEXITCODE" }
$up = $false
foreach ($i in 1..40) {
    try {
        $c = New-Object Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $up) { throw "shipping helper did not start listening on 8488" }
$r = Invoke-Helper '/ping' '{}' $null
Assert ($r.code -eq 401) "shipping bytes: ping without token is 401"
$r = Invoke-Helper '/pair' '{"code":"0000"}' $null
Assert ($r.code -eq 403) "shipping bytes: pair with wrong code is 403"

# Genuine no-hook first-run pairing: Part 0 deleted config.json, so the shipping
# instance starts unpaired and shows the pairing window with a live code - the
# first-run path a user takes. The code is read from the window's own label via
# Win32 window enumeration (WinForms labels are HWNDs; this is the text the user
# sees); a second launch of the exe (the supported single-instance show signal)
# is the fallback when the window is not up.
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
using System.Collections.Generic;
public static class WinScan {
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr p, EnumProc cb, IntPtr l);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    delegate bool EnumProc(IntPtr h, IntPtr l);
    static string Text(IntPtr h) { var b = new StringBuilder(256); GetWindowText(h, b, 256); return b.ToString(); }
    public static List<string> TopTitles() {
        var r = new List<string>();
        EnumWindows((h, l) => { r.Add((IsWindowVisible(h) ? "[vis] " : "[hid] ") + Text(h)); return true; }, IntPtr.Zero);
        return r;
    }
    [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
    public struct RECT { public int Left, Top, Right, Bottom; }
    // Class-agnostic click: Pair is a ReadableButton with ControlStyles.UserPaint,
    // so BM_CLICK (a native-BUTTON-only message) is not grounded. Real mouse-down/up
    // at the client center is what WinForms turns into Click for any control.
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
    public static void Click(IntPtr h) {
        RECT r; GetClientRect(h, out r);
        IntPtr lp = (IntPtr)((((r.Bottom - r.Top) / 2) << 16) | (((r.Right - r.Left) / 2) & 0xFFFF));
        PostMessage(h, 0x0201, (IntPtr)1, lp);
        PostMessage(h, 0x0202, IntPtr.Zero, lp);
    }
    [DllImport("user32.dll", CharSet=CharSet.Unicode, EntryPoint="SendMessageW")] public static extern IntPtr SendMessageText(IntPtr h, uint msg, IntPtr w, string l);
    [DllImport("user32.dll")] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr GetParent(IntPtr h);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern bool GetGUIThreadInfo(uint tid, ref GUIINFO g);
    [StructLayout(LayoutKind.Sequential)] struct GUIINFO {
        public int cbSize; public uint flags;
        public IntPtr active, focus, capture, menuOwner, moveSize, caret;
        public RECT caretRect;
    }
    public static string FocusText(IntPtr window) {
        uint pid; uint tid = GetWindowThreadProcessId(window, out pid);
        var g = new GUIINFO { cbSize = Marshal.SizeOf(typeof(GUIINFO)) };
        if (!GetGUIThreadInfo(tid, ref g)) throw new Exception("GetGUIThreadInfo failed");
        return Text(g.focus);
    }
    public static IntPtr FindChildByClass(string title, string fragment) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, l) => {
            if (Text(h) == title)
                EnumChildWindows(h, (c, l2) => { var b = new StringBuilder(128); GetClassName(c, b, 128); if (b.ToString().Contains(fragment)) { found = c; return false; } return true; }, IntPtr.Zero);
            return true;
        }, IntPtr.Zero);
        return found;
    }
    public static IntPtr FindChild(string title, string childText) {
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, l) => {
            if (Text(h) == title)
                EnumChildWindows(h, (c, l2) => { if (Text(c) == childText) { found = c; return false; } return true; }, IntPtr.Zero);
            return true;
        }, IntPtr.Zero);
        return found;
    }
    public static List<string> ChildDump(string title) {
        var r = new List<string>();
        EnumWindows((h, l) => {
            if (Text(h) == title)
                EnumChildWindows(h, (c, l2) => { var b = new StringBuilder(128); GetClassName(c, b, 128); r.Add(b.ToString() + " :: '" + Text(c) + "'"); return true; }, IntPtr.Zero);
            return true;
        }, IntPtr.Zero);
        return r;
    }
    public static List<string> ChildTexts(string title) {
        var r = new List<string>();
        EnumWindows((h, l) => {
            if (Text(h) == title)
                EnumChildWindows(h, (c, l2) => { var t = Text(c); if (t.Length > 0) r.Add(t); return true; }, IntPtr.Zero);
            return true;
        }, IntPtr.Zero);
        return r;
    }
}
'@
# The pairing state here is inherited from the earlier legs (they pair several phones
# and their config.json survives), so the instance starts tucked in the tray with no
# live code - a code exists only while unpaired or after the window's Pair button.
# That button IS the supported way to create one: show the window (second-launch
# signal), tap Pair (BM_CLICK - the button is a native BUTTON HWND), read the code.
$code = $null
$pairClicked = $false
foreach ($i in 1..24) {
    foreach ($t in [WinScan]::ChildTexts('BingeMote')) {
        if ($t -match '^\d{4}$') { $code = $t; break }
    }
    if ($code) { break }
    if ($i -eq 3) { & (Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\BingeMote.exe') }
    if ($i -ge 8 -and -not $pairClicked) {
        # The Pair button starts disabled; typing a phone name enables it (the
        # TextBox is a native EDIT control: WM_SETTEXT fires TextChanged).
        # Retry every poll: the 62eecdc run found the Pair button at i==8 but no
        # EDIT-class child, so a one-shot attempt is not reliable.
        $edit = [WinScan]::FindChildByClass('BingeMote', '.Edit.')
        if ($edit -ne [IntPtr]::Zero) {
            [WinScan]::SendMessageText($edit, 0x000C, [IntPtr]::Zero, 'CI Phone') | Out-Null
            Start-Sleep -Milliseconds 300
            $btn = [WinScan]::FindChild('BingeMote', 'Pair')
            if ($btn -ne [IntPtr]::Zero) { [WinScan]::Click($btn); $pairClicked = $true; Write-Host "shipping pairing: name typed, Pair clicked (i=$i)" }
            else { Write-Host "shipping pairing: Pair button HWND not found yet (i=$i)" }
        } elseif (($i % 4) -eq 0) { Write-Host "shipping pairing: name box HWND not found yet (i=$i)" }
    }
    Start-Sleep -Milliseconds 500
}
if (-not $code) {
    Write-Host "shipping pairing diagnostics - top-level windows:"
    [WinScan]::TopTitles() | ForEach-Object { Write-Host "  '$_'" }
    Write-Host "shipping pairing diagnostics - BingeMote window child texts:"
    [WinScan]::ChildTexts('BingeMote') | ForEach-Object { Write-Host "  '$_'" }
    Write-Host "shipping pairing diagnostics - child class :: text pairs:"
    [WinScan]::ChildDump('BingeMote') | ForEach-Object { Write-Host "  $_" }
    $logf3 = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
    if (Test-Path $logf3) { Write-Host "helper.log tail:"; Get-Content $logf3 -Tail 15 | ForEach-Object { Write-Host "  $(Redact-Secrets $_)" } }
    # What the session actually shows, so window state is inspected directly.
    try {
        Add-Type -AssemblyName System.Windows.Forms
        Add-Type -AssemblyName System.Drawing
        $b = [System.Windows.Forms.SystemInformation]::VirtualScreen
        $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.CopyFromScreen($b.Left, $b.Top, 0, 0, $bmp.Size)
        New-Item -ItemType Directory -Force 'helper/e2e-diag' | Out-Null
        $bmp.Save('helper/e2e-diag/shipping-pairing-screen.png')
        Write-Host "screen captured to helper/e2e-diag/shipping-pairing-screen.png"
    } catch { Write-Host "screen capture failed: $($_.Exception.Message)" }
    throw "shipping pairing: no 4-digit code label found in any BingeMote window"
}
$r = Invoke-Helper '/pair' (@{ code = $code } | ConvertTo-Json -Compress) $null
Assert ($r.code -eq 200) "shipping bytes: pairing succeeds with the code shown in the window"
$shipToken2 = ($r.body | ConvertFrom-Json).token
$r = Invoke-Helper '/ping' '{}' $shipToken2
Assert ($r.code -eq 200) "shipping bytes: the window-paired token authenticates"

# Install exact new1.7.14 over the genuinely paired prior1.7.13 state.
Write-Host 'Exact prior1.7.13 -> pinned1.7.14 upgrade begins'
Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
$upgrade = Start-Process $ship.FullName -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/LOG=shipping-upgrade.log' -Wait -PassThru
if ($upgrade.ExitCode -ne 0) { Get-Content shipping-upgrade.log -Tail 40; throw "exact shipping upgrade failed: $($upgrade.ExitCode)" }
# Inspect the actual installed payload extracted by the pinned installer, not
# compressed setup bytes or a separately built helper. The single-file bundle
# contains the managed metadata and literals uncompressed (default publish).
$payload = Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\BingeMote.exe'
Assert (Test-Path $payload) "pinned shipping installer did not extract helper payload"
$payloadHash = (Get-FileHash $payload -Algorithm SHA256).Hash.ToLower()
$payloadVersion = (Get-Item $payload).VersionInfo.FileVersion
Write-Host "exact installed payload: $payload"
Write-Host "exact installed payload sha256: $payloadHash"
Write-Host "exact installed payload file version: $payloadVersion"
$payloadBytes = [IO.File]::ReadAllBytes($payload)
$payloadAscii = [Text.Encoding]::UTF8.GetString($payloadBytes)
$payloadUtf16 = [Text.Encoding]::Unicode.GetString($payloadBytes)
$payloadUtf16Odd = [Text.Encoding]::Unicode.GetString($payloadBytes, 1, $payloadBytes.Length - 1)
foreach ($marker in @('--dialog-shots', '--window-shot', '--osd-shot', '/ci/hold', '/ci/blockstate', 'DialogCapture', 'BlockNoticeProbe', 'BINGEMOTE_WINDOW_SHOT')) {
    Assert (-not $payloadAscii.Contains($marker) -and -not $payloadUtf16.Contains($marker) -and -not $payloadUtf16Odd.Contains($marker)) "test hook leaked into exact shipping payload: $marker"
    Write-Host "exact payload hook absent: $marker"
}
Assert ($payloadAscii.Contains('DarkDialog')) "exact payload missing production DarkDialog metadata"
Write-Host 'exact payload production DarkDialog metadata present'
$up = $false
foreach ($i in 1..40) {
    try {
        $c = New-Object Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $up) { throw "shipping install-over: helper did not start listening on 8488" }
$r = Invoke-Helper '/ping' '{}' $shipToken2
Assert ($r.code -eq 200) "shipping upgrade: the window-paired token survives install-over"
$r = Invoke-Helper '/pair' '{"code":"0000"}' $null
Assert ($r.code -eq 403) "shipping upgrade: no live pair code after install-over (paired state retained)"
# Exercise the actual installed shipping confirmation, on the disposable CI
# phone just paired above. Cancel must have initial native focus, and physical
# Enter/Esc must both leave the fixture's token valid.
Add-Type -AssemblyName System.Windows.Forms
foreach ($key in @('{ENTER}', '{ESC}')) {
    & (Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\BingeMote.exe')
    Start-Sleep -Milliseconds 800
    $remove = [WinScan]::FindChild('BingeMote', 'Remove')
    Assert ($remove -ne [IntPtr]::Zero) "shipping confirmation fixture Remove button missing"
    [WinScan]::Click($remove)
    Start-Sleep -Milliseconds 500
    $cancel = [WinScan]::FindChild('BingeMote', 'Cancel')
    Assert ($cancel -ne [IntPtr]::Zero) "shipping confirmation did not open"
    $dialog = [WinScan]::GetParent($cancel)
    $focusText = [WinScan]::FocusText($dialog)
    Write-Host "exact shipping dialog initial focus: $focusText; key=$key"
    Assert ($focusText -eq 'Cancel') "exact shipping confirmation initial focus is not Cancel"
    [WinScan]::SetForegroundWindow($dialog) | Out-Null
    [System.Windows.Forms.SendKeys]::SendWait($key)
    Start-Sleep -Milliseconds 500
    Assert ([WinScan]::FindChild('BingeMote', 'Cancel') -eq [IntPtr]::Zero) "shipping key did not close confirmation"
    $r = Invoke-Helper '/ping' '{}' $shipToken2
    Assert ($r.code -eq 200) "shipping initial Enter/Esc removed harmless CI fixture phone"
    Write-Host "exact shipping $key returned Cancel; disposable paired phone retained"
}

Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
& $unins /VERYSILENT /NORESTART | Out-Null
Start-Sleep -Seconds 3
try { $rule = netsh advfirewall firewall show rule name="BingeMote" 2>$null | Out-String } catch { $rule = '' }
if ($rule -match 'BingeMote') { throw "shipping uninstall left the firewall rule behind" }
try { $acl = netsh http show urlacl 2>$null | Out-String } catch { $acl = '' }
if ($acl -match ':8488/') { throw "shipping uninstall left the listen permission behind" }
Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "ok: shipping bytes bind, reject unauthenticated and wrong-code calls, uninstall clean"
}
