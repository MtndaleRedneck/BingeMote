# BingeMote Helper runtime tests on a real Windows machine (CI runner).
# Exercises: the elevated firewall/URL-ACL setup, the pairing flow, token auth,
# every input endpoint, the volume endpoints, and UDP discovery.
$ErrorActionPreference = 'Stop'
$exe = "helper/out/BingeMote.exe"
if (-not (Test-Path $exe)) { throw "helper exe missing at $exe" }

# The plaintext baseline leg speaks http; once the one-time secure setup has run the
# helper serves HTTPS only, and every later call goes over TLS. The identity is the
# product's self-signed pairing key: on loopback CI checks reachability and auth codes,
# not certificate trust (the Android side pins the key; that is what the app tests prove).
$script:Scheme = 'http'
$script:Port = 8488   # the 1.9.x baseline (plain http); switched to 8490 once the candidate under test is installed
function Invoke-Helper($path, $body, $token) {
    $headers = @{}
    if ($token) { $headers['X-BingeMote-Token'] = $token }
    $tls = @{}
    if ($script:Scheme -eq 'https') { $tls['SkipCertificateCheck'] = $true }
    $r = Invoke-WebRequest -Uri "${script:Scheme}://127.0.0.1:${script:Port}$path" -Method Post -Body $body -Headers $headers -UseBasicParsing -TimeoutSec 10 -SkipHttpErrorCheck @tls
    return @{ code = [int]$r.StatusCode; body = $r.Content }
}
# --- Firewall object readback (effective objects, not netsh text) ---------------------------
$script:BingeRules = @(
    @{ Name='BingeMote TLS 8490 Private';              Profile='Private'; Proto='TCP'; Port='8490'; Remote='Any' },
    @{ Name='BingeMote Discovery 8489 Private';        Profile='Private'; Proto='UDP'; Port='8489'; Remote='Any' },
    @{ Name='BingeMote TLS 8490 Public LocalSubnet';   Profile='Public';  Proto='TCP'; Port='8490'; Remote='LocalSubnet4' },
    @{ Name='BingeMote Discovery 8489 Public LocalSubnet'; Profile='Public'; Proto='UDP'; Port='8489'; Remote='LocalSubnet4' }
)
function Get-RuleObject([string]$name) {
    $rs = @(Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)
    return ,$rs
}
function Format-RuleObject($x) {
    $p = $x | Get-NetFirewallPortFilter; $a = $x | Get-NetFirewallAddressFilter; $ap = $x | Get-NetFirewallApplicationFilter; $sv = $x | Get-NetFirewallServiceFilter
    return "$($x.DisplayName)|enabled=$($x.Enabled)|$($x.Direction)|$($x.Action)|profile=$($x.Profile)|$($p.Protocol)|lport=$($p.LocalPort -join ',')|rport=$($p.RemotePort -join ',')|remote=$($a.RemoteAddress -join ',')|local=$($a.LocalAddress -join ',')|program=$($ap.Program)|service=$($sv.Service)|edge=$($x.EdgeTraversalPolicy)"
}
# Exactly one object per expected name with exactly the expected scope.
function Assert-BingeRulesExact([string]$when) {
    foreach ($e in $script:BingeRules) {
        $rs = Get-RuleObject $e.Name
        Assert ($rs.Count -eq 1) "${when}: exactly one firewall rule named '$($e.Name)' (found $($rs.Count))"
        $line = Format-RuleObject $rs[0]
        Write-Host "readback: $line"
        $want = "enabled=True|Inbound|Allow|profile=$($e.Profile)|$($e.Proto)|lport=$($e.Port)|rport=Any|remote=$($e.Remote)|local=Any|program=Any|service=Any|edge=Block"
        Assert ($line.ToLower().Contains(('|' + $want).ToLower())) "${when}: '$($e.Name)' has exactly the expected scope ($want)"
    }
}
# Informational, never deletes: every enabled inbound allow rule that can reach 8489/8490 or is named BingeMote, with its scope.
function Write-BroaderRuleInventory([string]$when) {
    Write-Host "== inbound allow rules reaching 8489/8490 or named BingeMote ($when) =="
    foreach ($x in @(Get-NetFirewallRule -Direction Inbound -Action Allow -Enabled True -ErrorAction SilentlyContinue)) {
        $p = $x | Get-NetFirewallPortFilter
        $hit = ($x.DisplayName -like '*BingeMote*') -or (@($p.LocalPort) -contains '8489') -or (@($p.LocalPort) -contains '8490')
        if ($hit) { Write-Host ("inventory: " + (Format-RuleObject $x)) }
    }
}
function Get-LegacyRuleText { return ((netsh advfirewall firewall show rule name="BingeMote" verbose 2>&1) | Out-String).Trim() }
function Get-LegacyHttpBlock { return [regex]::Match(((netsh http show urlacl) | Out-String), '(?s)Reserved URL\s*:\s*http://\+:8488/.*?(?=Reserved URL|$)').Value.Trim() }
function Assert($cond, $msg) { if (-not $cond) { throw "ASSERT FAILED: $msg" } ; Write-Host "ok: $msg" }

# --- Lifecycle helpers: watchdog task state and absence of journaled rules ---
$script:WdName = 'BingeMote Watchdog (installed)'
function Get-LegacyWatchdogXml { $o = (schtasks /Query /TN 'BingeMote Watchdog' /XML 2>&1 | Out-String).Trim(); if ($LASTEXITCODE -ne 0) { return '' } ; return $o }
function Get-ComHresult($err) { $x = $err.Exception; while ($x.InnerException) { $x = $x.InnerException }; return [int]$x.HResult }
# $null means ONLY "the task does not exist" (HRESULT 0x80070002/0x80070003); any other COM failure is an error, never absence.
function Get-TaskSddl([string]$name) {
    $svc = New-Object -ComObject Schedule.Service; $svc.Connect()
    try { return [string]($svc.GetFolder('\').GetTask($name).GetSecurityDescriptor(4)) }
    catch { $h = Get-ComHresult $_; if ($h -eq -2147024894 -or $h -eq -2147024893) { return $null }; throw ('task read failed, not absence: HRESULT ' + ('{0:X8}' -f $h) + ' ' + $_.Exception.Message) }
}
function Assert-BingeRulesAbsent([string]$when) {
    foreach ($e in $script:BingeRules) { $rs = Get-RuleObject $e.Name; Assert ($rs.Count -eq 0) "${when}: firewall rule '$($e.Name)' is gone (found $($rs.Count))" }
}

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
    $tls = @{}
    if ($script:Scheme -eq 'https') { $tls['SkipCertificateCheck'] = $true }
    $r = Invoke-WebRequest -Uri "${script:Scheme}://127.0.0.1:${script:Port}$path" -Method Get -Headers $headers -UseBasicParsing -TimeoutSec 10 -SkipHttpErrorCheck @tls
    return @{ code = [int]$r.StatusCode; body = $r.Content }
}

if ($env:BINGEMOTE_SHIPPING_ONLY -ne '1') {
# --- Part 0: the real upgrade path: install the actual 1.9.1 build (already scoped
# to interactive users), silent-upgrade to this build, pair the instance the installer
# launches, run the blocked-input notice checks on that installed instance, then
# uninstall and verify ---
Write-Host "== real 1.9.1 -> this-build upgrade =="
# Set before any install so the app the installer relaunches inherits the fixed pair
# code (the pair-code hook exists only in this CI build, never in shipping bytes).
$env:BINGEMOTE_PAIR_CODE = '4242'
$old = Get-ChildItem "helper/Output-old/BingeMote-PC-*.exe" | Select-Object -First 1
if (-not $old) { throw "1.9.1 baseline installer missing in helper/Output-old" }
New-Item -ItemType Directory -Force helper/e2e-diag | Out-Null
# Capture Inno's own install log for both legs: a bare exit code (e.g. 5) does
# not say WHY setup aborted, and the log is the only record of that.
& $old.FullName /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG="$PWD\helper\e2e-diag\install-baseline.log" | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "--- install-baseline.log (tail) ---"
    if (Test-Path helper/e2e-diag/install-baseline.log) { Get-Content helper/e2e-diag/install-baseline.log -Tail 80 }
    throw "1.9.1 baseline install failed with exit $LASTEXITCODE"
}
$acl = (netsh http show urlacl) | Out-String
$aclBlock = [regex]::Match($acl, '(?s)Reserved URL\s*:\s*http://\+:8488/.*?(?=Reserved URL|$)').Value
if (-not $aclBlock) { throw "1.9.1 did not add its listen permission" }
if ($aclBlock -match 'Everyone|S-1-1-0') { throw "1.9.1 baseline grants Everyone (expected interactive-users): $aclBlock" }
if ($aclBlock -notmatch 'S-1-5-4|INTERACTIVE') { throw "1.9.1 baseline not scoped to interactive users: $aclBlock" }
Write-Host "ok: 1.9.1 baseline installed with the interactive-users grant"

# The silent postinstall relaunch is NOWAIT, so the baseline app is still cold-starting
# (single-file unpack, WinForms init, listener) when this script continues. Inno's
# RestartManager shutdown can only close an app whose window/message loop exists; asking
# 1-2s after launch times out after 30s and /SUPPRESSMSGBOXES then defaults the
# Abort/Retry/Ignore box to Abort (installer exit 5, observed on runs 45417217526 /
# 45417241032 / 45422326208, install-upgrade.log). Wait for the listener so the upgrade
# exercises Inno closing a fully running app, like a real update does.
# Readiness = the BASELINE HELPER answering, not merely a listener on 8488: poll
# /ping and require its exact unauthenticated 401, which only BingeMote's auth
# middleware produces. A bare TCP connect could pass on an unrelated listener.
$baselineUp = $false
foreach ($i in 1..60) {
    try {
        $probe = Invoke-Helper '/ping' '{}' $null
        if ($probe.code -eq 401) { $baselineUp = $true; break }
    } catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $baselineUp) { throw "baseline helper did not answer /ping with 401 on 8488 after install" }
Write-Host "ok: baseline helper fully up (authenticated /ping surface) before the upgrade"
# Legacy state exactly as the 1.9.1 baseline left it: the candidate must leave all of it byte-identical.
$script:LegacyRuleBefore = Get-LegacyRuleText
$script:LegacyHttpBefore = Get-LegacyHttpBlock
Assert ($script:LegacyHttpBefore -ne '') "baseline http://+:8488/ reservation recorded before upgrade"
Assert ($script:LegacyRuleBefore -match 'BingeMote') "baseline 'BingeMote' firewall rule(s) recorded before upgrade"
Write-Host "-- legacy rule snapshot --"; Write-Host $script:LegacyRuleBefore
Write-Host "-- legacy http reservation snapshot --"; Write-Host $script:LegacyHttpBefore
# Legacy fixed-name watchdog task: whatever the baseline left, or a harness fixture if it left none. The candidate must neither read-modify nor delete it.
$script:LegacyWdBefore = Get-LegacyWatchdogXml
if ($script:LegacyWdBefore -eq '') {
    schtasks /Create /TN 'BingeMote Watchdog' /TR 'cmd.exe /c exit 0' /SC MINUTE /MO 5 | Out-Host
    $script:LegacyWdBefore = Get-LegacyWatchdogXml
    Write-Host "legacy watchdog task: baseline left none, harness fixture created"
}
Assert ($script:LegacyWdBefore -ne '') "legacy 'BingeMote Watchdog' task recorded before upgrade"

$setup = Get-ChildItem "helper/Output/BingeMote-PC-*.exe" | Select-Object -First 1
if (-not $setup) { throw "installer exe missing in helper/Output" }
& $setup.FullName /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG="$PWD\helper\e2e-diag\install-upgrade.log" | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "--- install-upgrade.log (tail) ---"
    if (Test-Path helper/e2e-diag/install-upgrade.log) { Get-Content helper/e2e-diag/install-upgrade.log -Tail 80 }
    throw "silent upgrade failed with exit $LASTEXITCODE"
}
# Legacy objects untouched by the upgrade (no delete-first, no blind deletes, no re-add).
Assert ((Get-LegacyRuleText) -ceq $script:LegacyRuleBefore) "upgrade left the legacy 'BingeMote' firewall rule(s) byte-identical (name-based readback)"
Assert ((Get-LegacyHttpBlock) -ceq $script:LegacyHttpBefore) "upgrade left the legacy http://+:8488/ reservation byte-identical"
Assert-BingeRulesExact 'after upgrade'
# Explicit unique Name keys, and the documented create-only contract observed on this runner: creating under an existing Name must error and change nothing.
foreach ($e in $script:BingeRules) {
    $o = Get-RuleObject $e.Name
    Assert ($o.Count -eq 1 -and $o[0].Name -cmatch '^BingeMote-[0-9a-f]{32}$') "rule '$($e.Name)' carries a unique key Name (BingeMote-<32 hex>), not its DisplayName"
}
$keyProbe = (Get-RuleObject $script:BingeRules[0].Name)[0].Name
$dupErr = $null
$dupType = $null
try { New-NetFirewallRule -Name $keyProbe -DisplayName 'bm-dup-probe-never-created' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 59999 -ErrorAction Stop | Out-Null }
catch { $dupErr = $_.Exception.Message; $dupType = $_.Exception.GetType().FullName; Write-Host "same-Name create error: type=$dupType id=$($_.FullyQualifiedErrorId) message=$dupErr" }
if ($null -eq $dupErr) {
    # Unexpected success: capture the actual identities and fail; nothing is cleaned up.
    $fx = @(Get-NetFirewallRule -DisplayName 'bm-dup-probe-never-created' -ErrorAction SilentlyContinue | ForEach-Object { 'Name=' + $_.Name })
    Write-Host ("UNEXPECTED SUCCESS of same-Name create; harness-named rules: " + ($fx -join ',') + "; existing key rule now: " + ((Get-NetFirewallRule -Name $keyProbe -ErrorAction SilentlyContinue | ForEach-Object { $_.DisplayName }) -join ','))
    # No cleanup on purpose: the existing key rule may have been rewritten, so nothing is removed or repaired here (disposable runner); the job fails with the identities above.
    throw "ASSERT FAILED: New-NetFirewallRule under an existing Name unexpectedly succeeded (create-only contract not observed); no cleanup attempted"
}
Assert ($dupType -match 'CimException' -and $dupErr -match 'already exists') "same-Name create failed with the already-exists error (type=$dupType): $dupErr"
Assert ((Get-RuleObject 'bm-dup-probe-never-created').Count -eq 0) "the failed same-Name create left no new rule"
Assert-BingeRulesExact 'after same-Name create attempt (unchanged)'
Assert ((Get-LegacyWatchdogXml) -ceq $script:LegacyWdBefore) "upgrade left the legacy 'BingeMote Watchdog' task definition identical"
# Product watchdog (installer step --install-watchdog). On a hosted runner the install session may not be a signed-in user session, so either outcome is recorded and only the two valid ones pass.
$wdLogPath = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
$wdLog = if (Test-Path $wdLogPath) { Get-Content $wdLogPath -Raw } else { '' }
$wdSddl = Get-TaskSddl $script:WdName
Write-Host '-- helper.log watchdog / scheduled task lines --'; ($wdLog -split "`n" | Where-Object { $_ -match 'watchdog|scheduled task' }) | ForEach-Object { Write-Host ("wdlog: " + $_.TrimEnd()) }
if ($wdSddl) {
    Write-Host "watchdog product state: REGISTERED sddl=$wdSddl"
    Assert ($wdLog.Contains('watchdog registered for the installing user')) "log says the watchdog was registered"
    # Parse the descriptor instead of matching SDDL text: SDDL renders well-known account SIDs as aliases (the built-in Administrator account's SID prints as "LA"), so a text match on "S-1-5-21-" is not a SID check.
    $wdMe = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $wdRsd = New-Object Security.AccessControl.RawSecurityDescriptor($wdSddl)
    $wdAcl = @($wdRsd.DiscretionaryAcl | ForEach-Object { [pscustomobject]@{ Type = [string]$_.AceType; Sid = $_.SecurityIdentifier.Value; Mask = [int]$_.AccessMask } })
    Write-Host ("watchdog task ACEs: " + (($wdAcl | ForEach-Object { $_.Type + ':' + $_.Sid + ':0x' + ('{0:X}' -f $_.Mask) }) -join ' ; ') + " ; elevated account SID=" + $wdMe)
    Assert ($wdAcl.Count -eq 3 -and @($wdAcl | Where-Object { $_.Type -eq 'AccessAllowed' }).Count -eq 3) "task DACL has exactly three allow ACEs"
    Assert (@($wdAcl | Where-Object { $_.Mask -eq 0x1f01ff -and ($_.Sid -eq 'S-1-5-18' -or $_.Sid -eq 'S-1-5-32-544') }).Count -eq 2) "task DACL has exactly SYSTEM and Administrators full control"
    Assert (@($wdAcl | Where-Object { $_.Mask -eq 0x120089 -and $_.Sid -eq $wdMe }).Count -eq 1) "task DACL has exactly one other ACE: read-only for the target user SID (the elevated account)"
    $script:WdState = 'REGISTERED'
} else {
    Write-Host "watchdog product state: NOT REGISTERED"
    Assert ($wdLog -match 'watchdog not registered: ') "log gives the exact reason the watchdog was not registered"
    $script:WdState = 'SKIPPED'
    Write-Host ("watchdog skip reason line: " + (($wdLog -split "`n" | Where-Object { $_ -match 'watchdog not registered: ' } | Select-Object -Last 1)))
}
# Idempotence: a second elevated --install-watchdog changes nothing and creates no second task.
$wd2 = Start-Process (Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\BingeMote.exe') -ArgumentList '--install-watchdog' -Wait -PassThru
Write-Host "second --install-watchdog exit: $($wd2.ExitCode)"
if ($script:WdState -eq 'REGISTERED') { Assert ($wd2.ExitCode -eq 0 -and (Get-TaskSddl $script:WdName) -ceq $wdSddl) "second --install-watchdog exits 0 and leaves the task DACL unchanged" }
else { Assert ($wd2.ExitCode -eq 5 -and -not (Get-TaskSddl $script:WdName)) "second --install-watchdog skips again (exit 5) and creates nothing" }
Write-BroaderRuleInventory 'after upgrade'
# Diagnostics only, on any post-upgrade setup assert failure: the product elevated
# step's own account of its reservation decision (helper.log, captured BEFORE the
# re-run appends to it), a fresh elevated re-run's exit code with its appended log
# tail, and the live netsh state. Nothing is provisioned, repaired or deleted for
# the success path; the re-run below happens only after a failure is already
# determined, and the throw still follows.
function Write-SetupDiagnostics([string]$why) {
    Write-Host "== setup diagnostics ($why) =="
    $diagLog = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
    if (Test-Path $diagLog) { Write-Host "-- helper.log tail (original run) --"; Get-Content $diagLog -Tail 40 | ForEach-Object { Redact-Secrets $_ } } else { Write-Host "no helper.log" }
    $prov = Start-Process (Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\BingeMote.exe') -ArgumentList '--install-firewall' -Wait -PassThru
    Write-Host "diagnostic --install-firewall exit: $($prov.ExitCode)"
    if (Test-Path $diagLog) { Write-Host "-- helper.log tail (after diagnostic re-run) --"; Get-Content $diagLog -Tail 15 | ForEach-Object { Redact-Secrets $_ } }
    Write-Host "-- netsh http show urlacl --"; netsh http show urlacl
    Write-Host "-- netsh http show sslcert --"; netsh http show sslcert
    # Failure-only repro of the exact product add, gated to the absent-reservation case:
    # captures netsh's own error text that the product's Run() discards. Runs AFTER the
    # state dumps above so pre-repro state is on record. Cleanup deletes ONLY a
    # reservation this repro just created itself (previously absent) whose readback is
    # exactly the expected interactive-users grant - never an unconditional delete,
    # never a foreign or pre-existing entry, and the original failure still propagates.
    if ($why -eq 'https reservation missing') {
        Write-Host "-- diagnostic netsh add urlacl repro (exact product command) --"
        $reproOut = netsh http add urlacl url=https://+:8490/ 'sddl=D:(A;;GX;;;S-1-5-4)' 2>&1 | Out-String
        $reproExit = $LASTEXITCODE
        Write-Host "netsh add exit: $reproExit"
        Write-Host $reproOut
        if ($reproExit -eq 0) {
            $after = (netsh http show urlacl) | Out-String
            $afterBlock = [regex]::Match($after, '(?s)Reserved URL\s*:\s*https://\+:8490/.*?(?=Reserved URL|$)').Value
            if ($afterBlock -and $afterBlock -match 'S-1-5-4|INTERACTIVE' -and $afterBlock -notmatch 'Everyone|S-1-1-0') {
                Write-Host "repro created the previously-absent reservation with the exact expected grant; removing it to restore pre-repro state"
                netsh http delete urlacl url=https://+:8490/ | Out-Host
            } else {
                Write-Host "repro exit 0 but readback is not the exact expected grant; leaving state untouched"
            }
        }
    }
}

# Install-time secure setup (installer [Run] --install-firewall) must leave the upgraded
# helper fully provisioned on its own: the HTTPS reservation with the interactive-users
# scope, the TLS port binding, and a live authenticated HTTPS surface from the instance
# the installer relaunched. The harness observes all of it and repairs NOTHING on the
# success path: a broken install-time path is a product regression to catch, not fix quietly.
$acl = (netsh http show urlacl) | Out-String
# The grant lives on the SDDL/User lines under the Reserved URL line; match the whole block.
$aclBlock = [regex]::Match($acl, '(?s)Reserved URL\s*:\s*https://\+:8490/.*?(?=Reserved URL|$)').Value
if (-not $aclBlock) { Write-SetupDiagnostics 'https reservation missing'; throw "upgrade did not provision the HTTPS listen permission" }
if ($aclBlock -match 'Everyone|S-1-1-0') { Write-SetupDiagnostics 'https reservation grants Everyone'; throw "listen permission grants Everyone after the real upgrade: $aclBlock" }
if ($aclBlock -notmatch 'S-1-5-4|INTERACTIVE') { Write-SetupDiagnostics 'https reservation scope unexpected'; throw "listen permission not scoped to interactive users after the real upgrade: $aclBlock" }
$bindings = (netsh http show sslcert) | Out-String
if ($bindings -notmatch '0\.0\.0\.0:8490') { Write-SetupDiagnostics 'tls binding missing'; throw "upgrade did not provision the TLS port binding" }
Write-Host "ok: real upgrade provisioned the HTTPS interactive-users grant and TLS binding on 8490"
$script:Scheme = 'https'
$script:Port = 8490
# Pair the instance the installer relaunched, under the retained installer-created state.
$up = $false
foreach ($i in 1..60) {
    try {
        $probe = Invoke-Helper '/ping' '{}' $null
        if ($probe.code -eq 401) { $up = $true; break }
    } catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $up) {
    Write-SetupDiagnostics 'readiness wait exhausted'
    throw "installer-launched helper did not answer HTTPS /ping with 401 on 8490 after install-time secure setup"
}
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
$uninstall = Start-Process $unins -ArgumentList '/VERYSILENT', '/NORESTART', "/LOG=$PWD\helper\e2e-diag\uninstall-candidate.log" -PassThru
if (-not $uninstall.WaitForExit(60000)) { Stop-Process -Id $uninstall.Id -Force; throw 'Uninstall exceeded60seconds' }
Start-Sleep -Seconds 3
# Inherited blind actions from the 1.9.1 install must be shadowed (Inno runs only the newest entry per RunOnceId).
$ulog = Get-Content helper/e2e-diag/uninstall-candidate.log -Raw
Assert ($ulog -match 'Skipping RunOnceId "delrule"') "uninstall log: inherited 'delrule' entry was skipped (newest same-id entry won)"
Assert ($ulog -match 'Skipping RunOnceId "delacl"') "uninstall log: inherited 'delacl' entry was skipped (newest same-id entry won)"
Assert ((Get-LegacyRuleText) -ceq $script:LegacyRuleBefore) "uninstall left the legacy 'BingeMote' firewall rule(s) byte-identical (not deleted by name)"
Assert ((Get-LegacyHttpBlock) -ceq $script:LegacyHttpBefore) "uninstall left the legacy http://+:8488/ reservation byte-identical (not deleted)"
try { $acl = netsh http show urlacl 2>$null | Out-String } catch { $acl = '' }
if ($acl -match ':8490/') { throw "uninstaller left the 8490 listen permission behind" }
try { $leftBindings = netsh http show sslcert 2>$null | Out-String } catch { $leftBindings = '' }
if ($leftBindings -match ':8490') { throw "uninstaller left the 8490 TLS port bindings behind" }
# The four new rules were created by this build's own install run (journaled), so uninstall removes them; the legacy rule stays.
Assert-BingeRulesAbsent 'after uninstall (journaled rules removed)'
Assert ((Get-LegacyWatchdogXml) -ceq $script:LegacyWdBefore) "uninstall left the legacy 'BingeMote Watchdog' task definition identical (inherited delwatchdog skipped)"
Write-Host ("uninstall log: delwatchdog skip line present = " + [bool]($ulog -match 'Skipping RunOnceId "delwatchdog"') + " (informational: depends on the baseline installer having carried that entry)")
if ($script:WdState -eq 'REGISTERED') { Assert (-not (Get-TaskSddl $script:WdName)) "uninstall removed the journaled product watchdog task (state was REGISTERED)" }
else { Assert (-not (Get-TaskSddl $script:WdName)) "no product watchdog task exists after uninstall (state was $($script:WdState); nothing was registered, so nothing was removed: this is NOT a removal proof)" }
Write-Host "legacy watchdog task survived install, upgrade and uninstall untouched; the harness now removes ITS OWN fixture/baseline task so later phases are deterministic (the product never does)"
schtasks /Delete /TN 'BingeMote Watchdog' /F | Out-Host
Write-Host "ok: uninstaller removed the 8490 listen permission, TLS port bindings and journaled rules; legacy objects left in place; product watchdog state was $($script:WdState) (removal is proven only when REGISTERED)"
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
$aclOut = netsh http show urlacl
$aclOut | Select-String '8490'
if ($fwExit -ne 0) { throw "install-firewall failed with exit $fwExit" }
if (-not ($aclOut | Select-String ':8490/')) { throw "URL ACL for port 8490 missing after setup" }
$aclText2 = ($aclOut | Out-String)
# The elevated step reserves the HTTPS URL on 8490 only; it never touches an http:// reservation.
$aclBlock2 = [regex]::Match($aclText2, '(?s)Reserved URL\s*:\s*https://\+:8490/.*?(?=Reserved URL|$)').Value
if (-not $aclBlock2) { throw "helper setup did not add the HTTPS listen permission" }
if ($aclBlock2 -match 'Everyone|S-1-1-0') { throw "helper setup still grants Everyone: $aclBlock2" }
if ($aclBlock2 -notmatch 'S-1-5-4|INTERACTIVE') { throw "helper setup did not scope the listen permission to interactive users: $aclBlock2" }
$script:Scheme = 'https'
Assert-BingeRulesExact 'after install-firewall'
Write-BroaderRuleInventory 'after install-firewall'
# Idempotent re-run: still exactly one object per name.
$fw2 = Start-Process $exe -ArgumentList '--install-firewall' -Wait -PassThru
Assert ($fw2.ExitCode -eq 0) "second install-firewall run exits 0"
Assert-BingeRulesExact 'after second install-firewall'
# Fail-closed on a same-named rule with the wrong state: the harness disables its own fixture; setup must refuse and not repair it.
$pubTcp = $script:BingeRules[2].Name
Disable-NetFirewallRule -DisplayName $pubTcp
$diagLog = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
$logBefore = if (Test-Path $diagLog) { (Get-Content $diagLog -Raw).Length } else { 0 }
$fw3 = Start-Process $exe -ArgumentList '--install-firewall' -Wait -PassThru
Assert ($fw3.ExitCode -ne 0) "install-firewall refuses a same-named rule that is not exactly as expected (exit $($fw3.ExitCode))"
$newLog = (Get-Content $diagLog -Raw).Substring($logBefore)
Assert ($newLog.Contains("rule '$pubTcp' is Mismatch")) "the refusal is the exact named-rule Mismatch for '$pubTcp', not an unrelated failure"
Assert ((Get-RuleObject $pubTcp)[0].Enabled.ToString() -eq 'False') "refused setup left the disabled same-named rule untouched"
foreach ($e in $script:BingeRules) { if ($e.Name -ne $pubTcp) { $o = Get-RuleObject $e.Name; Assert ($o.Count -eq 1 -and $o[0].Enabled.ToString() -eq 'True') "unaffected rule '$($e.Name)' still exactly one and enabled" } }
Enable-NetFirewallRule -DisplayName $pubTcp
Assert-BingeRulesExact 'after harness restored its own fixture'

# --- Standard-user readability of SetupIsCurrent's reads (fixture hook only; ONE execution path, not every branch) ---
# Verdicts: READABLE-CURRENT | UNREADABLE | NOT-CURRENT | UNSUPPORTED | UNVERIFIED. Only READABLE-CURRENT is product proof; anything else
# fails the job at the very end of this script. The password lives only in memory (SecureString/PSCredential): never written, echoed or uploaded.
Write-Host "== standard-user SetupIsCurrent read (fixture hook --setup-status) =="
$script:StdVerdict = 'UNVERIFIED'
$script:StdDetail = 'stage did not complete'
$stdName = 'bmstd' + (Get-Random -Minimum 10000 -Maximum 99999)
$stdDir = Join-Path $env:SystemDrive 'bmstd'
$ctlFile = Join-Path $env:TEMP 'bm-status-admin.txt'
$stdProc = $null
try {
    $plain = (-join ((48..57 + 65..90 + 97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })) + 'aA1!'
    $sec = ConvertTo-SecureString $plain -AsPlainText -Force
    $plain = $null
    New-LocalUser -Name $stdName -Password $sec -PasswordNeverExpires -AccountNeverExpires -UserMayNotChangePassword -ErrorAction Stop | Out-Null
    $usersMembers = @(Get-LocalGroupMember -SID 'S-1-5-32-545' | ForEach-Object { [string]$_.Name })
    if (@($usersMembers | Where-Object { $_ -like "*\${stdName}" }).Count -eq 0) { Add-LocalGroupMember -SID 'S-1-5-32-545' -Member $stdName -ErrorAction Stop }
    $usersMembers = @(Get-LocalGroupMember -SID 'S-1-5-32-545' | ForEach-Object { [string]$_.Name })
    Assert (@($usersMembers | Where-Object { $_ -like "*\${stdName}" }).Count -eq 1) "disposable account is a member of Users"
    $adminMembers = @(Get-LocalGroupMember -SID 'S-1-5-32-544' | ForEach-Object { [string]$_.Name })
    Assert (@($adminMembers | Where-Object { $_ -like "*\${stdName}" }).Count -eq 0) "disposable account is not a member of Administrators"
    New-Item -ItemType Directory -Force -Path $stdDir | Out-Null
    icacls $stdDir /grant "${stdName}:(OI)(CI)M" | Out-Null
    Copy-Item (Resolve-Path $exe).Path (Join-Path $stdDir 'BingeMote.exe') -Force
    # DACL fixture (NOT the product task): the same SDDL shape and registration flags the product uses, registered by this elevated harness for the disposable standard user's SID
    # under a harness-only name. The product's own SDDL builder is pinned to this exact string by a unit test. The standard user then tries to change it (below).
    $script:DaclVerdict = 'UNVERIFIED'
    $stdSid = (Get-LocalUser -Name $stdName).SID.Value
    $fixXml = @'
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Principals><Principal id="Author"><UserId>SIDPLACEHOLDER</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
  <Triggers><TimeTrigger><StartBoundary>2099-01-01T00:00:00</StartBoundary></TimeTrigger></Triggers>
  <Actions Context="Author"><Exec><Command>cmd.exe</Command><Arguments>/c exit 0</Arguments></Exec></Actions>
</Task>
'@
    $fixXml = $fixXml.Replace('SIDPLACEHOLDER', $stdSid)
    Set-Content -Path (Join-Path $stdDir 'fixture.xml') -Value $fixXml -Encoding Unicode
    $fixSddl = "D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FR;;;$stdSid)"
    $svcA = New-Object -ComObject Schedule.Service; $svcA.Connect(); $fldA = $svcA.GetFolder('\')
    $fldA.RegisterTask('bm-dacl-fixture', $fixXml, 2, $null, $null, 3, $fixSddl) | Out-Null
    $fixBeforeSddl = [string]($fldA.GetTask('bm-dacl-fixture').GetSecurityDescriptor(4))
    # Positive control for the SAME probe operations: an administrator performs disable, update, create-or-update and delete on a separate control task with the same SDDL.
    # If these fail for a reason other than rights (bad XML, API shape), the standard-user denials above would prove nothing.
    $fldA.RegisterTask('bm-dacl-control', $fixXml, 2, $null, $null, 3, $fixSddl) | Out-Null
    $ctrlTask = $fldA.GetTask('bm-dacl-control')
    $ctrlTask.Enabled = $false
    $fldA.RegisterTask('bm-dacl-control', $fixXml, 4, $null, $null, 3, $null) | Out-Null
    $fldA.RegisterTask('bm-dacl-control', $fixXml, 6, $null, $null, 3, $null) | Out-Null
    $fldA.DeleteTask('bm-dacl-control', 0)
    Assert ($true) "admin positive control: the same disable/update/create-or-update/delete operations succeed for an administrator on a control task"
    Write-Host "dacl fixture registered; sddl=$fixBeforeSddl"
    # admin control: the same hook in the elevated fixture context, so the standard-user result is compared with real state at this point
    Remove-Item $ctlFile -Force -ErrorAction SilentlyContinue
    $ctl = Start-Process (Resolve-Path $exe).Path -ArgumentList '--setup-status', $ctlFile -Wait -PassThru
    $ctlLines = if (Test-Path $ctlFile) { @(Get-Content $ctlFile) } else { @() }
    Write-Host "-- admin control status (exit $($ctl.ExitCode)) --"; $ctlLines | ForEach-Object { Write-Host "admin: $_" }
    $child = @'
$o = 'C:\bmstd\child-result.txt'
function W($s) { Add-Content -Path $o -Value $s }
function Dt($e) { $x = $e.Exception; while ($x.InnerException) { $x = $x.InnerException }; return ($x.GetType().FullName + '|0x' + ('{0:X8}' -f [int]$x.HResult) + '|' + (($x.Message -replace '[\r\n]+', ' ').Trim())) }
$id = [Security.Principal.WindowsIdentity]::GetCurrent()
W ('identity|' + $id.Name)
W ('isAdminRole|' + ([Security.Principal.WindowsPrincipal]::new($id)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))
W ('hasAdminSid|' + (@($id.Groups | Where-Object { $_.Value -eq 'S-1-5-32-544' }).Count -gt 0))
W ('whoamiGroups|' + ((whoami /groups /fo csv /nh) -join ';'))
$p = Start-Process 'C:\bmstd\BingeMote.exe' -ArgumentList '--setup-status', 'C:\bmstd\status-std.txt' -PassThru
if ($p.WaitForExit(60000)) { W ('hookExit|' + $p.ExitCode) } else { try { $p.Kill() } catch { } ; W 'hookTimeout|True' }
W ('daclIdentity|' + $id.Name)
$svc = New-Object -ComObject Schedule.Service; $svc.Connect(); $fld = $svc.GetFolder('\')
$t = $null
try { $t = $fld.GetTask('bm-dacl-fixture'); W 'daclRead|ok' } catch { W 'daclRead|denied' }
try { $x = [string]$t.GetSecurityDescriptor(4); W ('daclReadSd|' + [bool]$x) } catch { W 'daclReadSd|denied' }
function Get-BmHresult($e) { $x = $e.Exception; while ($x.InnerException) { $x = $x.InnerException }; return [int]$x.HResult }
W 'probesBegin|True'
try { $fxml = Get-Content 'C:\bmstd\fixture.xml' -Raw -ErrorAction Stop; W ('daclFixtureXml|ok|' + $fxml.Length) } catch { W ('daclFixtureXml|' + (Dt $_)); $fxml = '' }
if ($null -eq $t) { W 'daclNoHandle|True' } else { try { $t.Enabled = $false; W 'daclDisable|ALLOWED' } catch { W ('daclDisable|hr' + (Get-BmHresult $_)); W ('daclDetail|disable|' + (Dt $_)) } }
try { $fld.RegisterTask('bm-dacl-fixture', $fxml, 4, $null, $null, 3, $null) | Out-Null; W 'daclUpdate|ALLOWED' } catch { W ('daclUpdate|hr' + (Get-BmHresult $_)); W ('daclDetail|update|' + (Dt $_)) }
try { $fld.RegisterTask('bm-dacl-fixture', $fxml, 6, $null, $null, 3, $null) | Out-Null; W 'daclCreateOrUpdate|ALLOWED' } catch { W ('daclCreateOrUpdate|hr' + (Get-BmHresult $_)); W ('daclDetail|createOrUpdate|' + (Dt $_)) }
try { $fld.DeleteTask('bm-dacl-fixture', 0); W 'daclDelete|ALLOWED' } catch { W ('daclDelete|hr' + (Get-BmHresult $_)); W ('daclDetail|delete|' + (Dt $_)) }
$so = (schtasks /Delete /TN 'bm-dacl-fixture' /F 2>&1 | Out-String)
W ('daclSchtasksDelete|exit' + $LASTEXITCODE + '|accessDeniedText=' + ($so -match 'Access is denied'))
W 'DONE'
'@
    Set-Content -Path (Join-Path $stdDir 'child.ps1') -Value $child -Encoding UTF8
    $ctoks = $null; $cerrs = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $stdDir 'child.ps1'), [ref]$ctoks, [ref]$cerrs)
    if ($cerrs -and $cerrs.Count -gt 0) { throw ("child script does not parse (line " + $cerrs[0].Extent.StartLineNumber + "): " + $cerrs[0].Message) }
    $cred = New-Object System.Management.Automation.PSCredential(($env:COMPUTERNAME + '\' + $stdName), $sec)
    $startErr = $null
    try {
        $stdProc = Start-Process -FilePath 'powershell.exe' -Credential $cred -LoadUserProfile -WorkingDirectory $stdDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $stdDir 'child-stdout.txt') -RedirectStandardError (Join-Path $stdDir 'child-stderr.txt') `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $stdDir 'child.ps1'))
    } catch { $startErr = $_.Exception.Message }
    if ($stdProc -and -not $stdProc.WaitForExit(120000)) { Stop-Process -Id $stdProc.Id -Force -ErrorAction SilentlyContinue }
    $childFile = Join-Path $stdDir 'child-result.txt'
    $statusFile = Join-Path $stdDir 'status-std.txt'
    $childLines = if (Test-Path $childFile) { @(Get-Content $childFile) } else { @() }
    $stdLines = if (Test-Path $statusFile) { @(Get-Content $statusFile) } else { @() }
    foreach ($cf in 'child-stdout.txt', 'child-stderr.txt') { $cp = Join-Path $stdDir $cf; if (Test-Path $cp) { Write-Host "-- $cf --"; Get-Content $cp | ForEach-Object { Write-Host "childio: $_" } } else { Write-Host "-- $cf absent --" } }
    Write-Host '-- standard-user identity / child record --'; $childLines | ForEach-Object { Write-Host "child: $_" }
    Write-Host '-- standard-user status --'; $stdLines | ForEach-Object { Write-Host "std: $_" }
    # DACL verdict: the standard user (the task's own principal) must be unable to disable, update, replace or delete; admin then proves the task is intact.
    $fixAfter = $null; try { $ft = $fldA.GetTask('bm-dacl-fixture'); $fixAfter = @{ enabled = [bool]$ft.Enabled; sddl = [string]$ft.GetSecurityDescriptor(4) } } catch { Write-Host ('admin re-read of the fixture failed: ' + $_.Exception.Message) }
    # Only the actual E_ACCESSDENIED (0x80070005 = -2147024891) counts as denial; any other error code (bad XML, null handle, missing task...) is NOT denial.
    $denyKeys = @('daclDisable|hr-2147024891', 'daclUpdate|hr-2147024891', 'daclCreateOrUpdate|hr-2147024891', 'daclDelete|hr-2147024891')
    $allDenied = ((@($denyKeys | Where-Object { $childLines -notcontains $_ }).Count -eq 0) -and ($childLines -contains 'daclRead|ok'))
    $schDelDenied = @($childLines | Where-Object { $_ -match '^daclSchtasksDelete\|exit[1-9]\|accessDeniedText=True$' }).Count -eq 1
    $intact = ($null -ne $fixAfter) -and $fixAfter.enabled -and ($fixAfter.sddl -ceq $fixBeforeSddl)
    if ($allDenied -and $schDelDenied -and $intact) { $script:DaclVerdict = 'DENIED-ALL' } else { $script:DaclVerdict = 'NOT-DENIED-OR-UNVERIFIED' }
    Write-Host ("DACL-VERDICT: " + $script:DaclVerdict + " | readByUser=" + ($childLines -contains 'daclRead|ok') + " allDenied=$allDenied schtasksDeleteDenied=$schDelDenied taskIntact=$intact")
    $identityProven = ($childLines -contains 'isAdminRole|False') -and ($childLines -contains 'hasAdminSid|False') -and (@($childLines | Where-Object { $_ -like "identity|*\${stdName}" }).Count -eq 1)
    $ctlCurrent = ($ctlLines -contains 'setupIsCurrent|True') -and ($ctlLines -contains 'lastReadFailed|False') -and (@($ctlLines | Where-Object { $_ -like 'hookError|*' -or $_ -like 'rule|*|Unreadable' }).Count -eq 0)
    $stdBad = @($stdLines | Where-Object { $_ -like 'rule|*|Unreadable' -or $_ -eq 'lastReadFailed|True' })
    if ($startErr) { $script:StdVerdict = 'UNSUPPORTED'; $script:StdDetail = "could not start the child as the standard user: $startErr" }
    elseif (-not ($childLines -contains 'DONE')) { $script:StdVerdict = 'UNVERIFIED'; $script:StdDetail = 'standard-user child did not finish' }
    elseif (-not $identityProven) { $script:StdVerdict = 'UNVERIFIED'; $script:StdDetail = 'non-admin token not proven (identity, Administrators role or SID check)' }
    elseif (-not $ctlCurrent) { $script:StdVerdict = 'UNVERIFIED'; $script:StdDetail = 'admin control status is not current/readable, so there is nothing to compare against' }
    elseif ($stdLines.Count -eq 0 -or @($stdLines | Where-Object { $_ -like 'hookError|*' }).Count -gt 0) { $script:StdVerdict = 'UNVERIFIED'; $script:StdDetail = 'standard-user hook produced no usable status (see child record)' }
    elseif ($stdBad.Count -gt 0) { $script:StdVerdict = 'UNREADABLE'; $script:StdDetail = ($stdBad -join ' ; ') }
    elseif (($stdLines -join "`n") -ceq ($ctlLines -join "`n")) { $script:StdVerdict = 'READABLE-CURRENT'; $script:StdDetail = 'standard-user status identical to the admin control and current' }
    else { $script:StdVerdict = 'NOT-CURRENT'; $script:StdDetail = 'readable but differs from the admin control (compare the admin: and std: lines above)' }
}
catch { $script:StdVerdict = 'UNVERIFIED'; $script:StdDetail = "stage error: $($_.Exception.Message)" }
finally {
    # kill anything running as/from the account BEFORE removing the account and its directory
    if ($stdProc) { Stop-Process -Id $stdProc.Id -Force -ErrorAction SilentlyContinue }
    Get-Process -Name BingeMote -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "${stdDir}\*" } | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    $acct = Get-LocalUser -Name $stdName -ErrorAction SilentlyContinue
    if ($acct) { Remove-LocalUser -Name $stdName -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_UserProfile -ErrorAction SilentlyContinue | Where-Object { $_.LocalPath -like "*\${stdName}" } | Remove-CimInstance -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $stdDir -ErrorAction SilentlyContinue
    Remove-Item $ctlFile -Force -ErrorAction SilentlyContinue
    try { $svcC = New-Object -ComObject Schedule.Service; $svcC.Connect(); $svcC.GetFolder('\').DeleteTask('bm-dacl-fixture', 0) } catch { }
    try { $svcC = New-Object -ComObject Schedule.Service; $svcC.Connect(); $svcC.GetFolder('\').DeleteTask('bm-dacl-control', 0) } catch { }
    $acctLeft = [bool](Get-LocalUser -Name $stdName -ErrorAction SilentlyContinue)
    Write-Host ("cleanup: account removed=" + (-not $acctLeft) + ", directory removed=" + (-not (Test-Path $stdDir)))
}
Write-Host "STDUSER-VERDICT: $($script:StdVerdict) | $($script:StdDetail)"
if ($script:StdVerdict -ne 'READABLE-CURRENT') { Write-Host "::error::standard-user SetupIsCurrent read is $($script:StdVerdict): not product proof ($($script:StdDetail))" }
Assert-BingeRulesExact 'after standard-user stage (unchanged)'

# --- Part 2: run the helper and drive its HTTPS API over loopback ---
Write-Host "== helper runtime =="
$env:BINGEMOTE_PAIR_CODE = '4242'
$p = Start-Process $exe -PassThru
try {
    $up = $false
    foreach ($i in 1..40) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not start listening on 8490" }
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
            $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
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
            $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
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
            $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
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
            $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
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
                $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
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
                $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
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
                $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
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
            $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "taking-over copy did not start listening on 8490" }
    $r = Invoke-Helper '/ping' '{}' $token
    Assert ($r.code -eq 200) "taking-over copy serves the saved token"
    $p = $p2

    Write-Host "HELPER E2E PASSED"
} finally {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
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
$priorLabel = '1.9.0 (the exact live public release)'
$prior = Get-ChildItem 'helper/Prior/BingeMote-PC-1.9.0.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $prior) { $priorLabel = '1.7.15'; $prior = Get-ChildItem 'helper/Prior/BingeMote-PC-1.7.15.exe' -ErrorAction SilentlyContinue | Select-Object -First 1 }
if ($env:BINGEMOTE_REQUIRE_PRIOR_VERSION) {
    # Fail closed: the shipping upgrade leg must start from the exact required live version, never a fallback.
    Assert ($prior -and $prior.Name -eq ('BingeMote-PC-' + $env:BINGEMOTE_REQUIRE_PRIOR_VERSION + '.exe')) "required prior installer BingeMote-PC-$($env:BINGEMOTE_REQUIRE_PRIOR_VERSION).exe is missing"
}
if ($prior) { $initialInstaller = $prior.FullName; Write-Host "Initial install: exact prior $priorLabel for paired upgrade retention ($($prior.Name))" }
$script:Port = 8488   # the exact prior release listens on 8488
$shipInstall = Start-Process $initialInstaller -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/LOG=shipping-install.log' -PassThru
if (-not $shipInstall.WaitForExit(90000)) { Stop-Process -Id $shipInstall.Id -Force; Get-Content shipping-install.log -Tail 40; throw 'Initial installer exceeded90seconds' }
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

# Install the pinned shipping build over the genuinely paired prior1.7.15 state.
$installedBefore = (Get-Item (Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\BingeMote.exe')).VersionInfo.FileVersion
Write-Host "installed baseline file version before upgrade: $installedBefore (prior $priorLabel)"
if ($env:BINGEMOTE_REQUIRE_PRIOR_VERSION) { Assert ($installedBefore -like ($env:BINGEMOTE_REQUIRE_PRIOR_VERSION + '*')) "installed baseline is $installedBefore, required $($env:BINGEMOTE_REQUIRE_PRIOR_VERSION)" }
Write-Host "Exact prior $priorLabel -> pinned shipping upgrade begins"
Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
$upgrade = Start-Process $ship.FullName -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/LOG=shipping-upgrade.log' -PassThru
if (-not $upgrade.WaitForExit(90000)) { Stop-Process -Id $upgrade.Id -Force; Get-Content shipping-upgrade.log -Tail 40; throw 'Upgrade installer exceeded90seconds' }
if ($upgrade.ExitCode -ne 0) { Get-Content shipping-upgrade.log -Tail 40; throw "exact shipping upgrade failed: $($upgrade.ExitCode)" }
$script:Port = 8490   # the candidate under test serves on 8490
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
foreach ($marker in @('--dialog-shots', '--window-shot', '--osd-shot', '/ci/hold', '/ci/blockstate', 'DialogCapture', 'BlockNoticeProbe', 'BINGEMOTE_WINDOW_SHOT', '--setup-status', 'setupIsCurrent|', 'hookError|', 'SETUP_STATUS_HOOK', '--wifi-click-proof', 'BINGEMOTE_CLICK_PROOF', 'ProofClickThrough')) {
    Assert (-not $payloadAscii.Contains($marker) -and -not $payloadUtf16.Contains($marker) -and -not $payloadUtf16Odd.Contains($marker)) "test hook leaked into exact shipping payload: $marker"
    Write-Host "exact payload hook absent: $marker"
}
Assert ($payloadAscii.Contains('DarkDialog')) "exact payload missing production DarkDialog metadata"
Write-Host 'exact payload production DarkDialog metadata present'
$up = $false
foreach ($i in 1..40) {
    try {
        $c = New-Object Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', 8490); $c.Close(); $up = $true; break
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $up) { throw "shipping install-over: helper did not start listening on 8490" }
$r = Invoke-Helper '/ping' '{}' $shipToken2
Assert ($r.code -eq 200) "shipping upgrade: the window-paired token survives install-over"
$r = Invoke-Helper '/pair' '{"code":"0000"}' $null
Assert ($r.code -eq 403) "shipping upgrade: no live pair code after install-over (paired state retained)"
# Exercise the actual installed shipping confirmation, on the disposable CI
# phone just paired above. Cancel must have initial native focus, and physical
# Enter/Esc must both leave the fixture's token valid.
Add-Type -AssemblyName System.Windows.Forms
# The runner's network is Public, and the (fixed) Wi-Fi probe now correctly raises the Public-network popup over the window.
# That popup is modal and would block the Remove button. Dismiss it with "Not now" exactly as a user would, and say so in the log.
$notNow = [WinScan]::FindChild('BingeMote', 'Not now')
if ($notNow -ne [IntPtr]::Zero) { Write-Host 'Public-network popup present on the runner (probe works); dismissing it with Not now'; [WinScan]::Click($notNow); Start-Sleep -Milliseconds 800 } else { Write-Host 'No Public-network popup present' }
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
$uninstall = Start-Process $unins -ArgumentList '/VERYSILENT', '/NORESTART' -PassThru
if (-not $uninstall.WaitForExit(60000)) { Stop-Process -Id $uninstall.Id -Force; throw 'Uninstall exceeded60seconds' }
Start-Sleep -Seconds 3
Assert-BingeRulesAbsent 'after shipping uninstall (journaled rules removed)'
try { $acl = netsh http show urlacl 2>$null | Out-String } catch { $acl = '' }
if ($acl -match ':8490/') { throw "shipping uninstall left the 8490 listen permission behind" }
Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "ok: shipping bytes bind, reject unauthenticated and wrong-code calls, uninstall clean"
}

# Final gate: only a READABLE-CURRENT standard-user verdict is product proof; every other verdict fails the job after all other proof ran.
if ($script:DaclVerdict -ne 'DENIED-ALL') { throw "TASK DACL DENY NOT PROVEN: $($script:DaclVerdict)" }
if ($script:StdVerdict -ne 'READABLE-CURRENT') { throw "STANDARD-USER READ NOT PROVEN: $($script:StdVerdict) - $($script:StdDetail)" }
