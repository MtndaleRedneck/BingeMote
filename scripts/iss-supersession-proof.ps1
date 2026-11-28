# iss-supersession-proof.ps1 - isolated proof for the RunOnceId "delacl"
# neutralization (inert-entry supersession of the inherited blind delete).
#
# ISOLATED: no product code, no migration code. The only product-repo change it
# proves is the installer.iss [UninstallRun] "delacl" entry becoming inert.
# This proof is NOT a migration green-light and does not depend on one.
#
# Environment pins (I2 conditions): run elevated (admin), 32-bit helper, same
# AppId (default: AppName) so the uninstall log merges, uninstall registry
# under HKLM. Compiler version: NO global pin or upgrade - the harness parses
# each install log's "Setup version: Inno Setup version X" line, ASSERTS both
# installers were built by the same compiler, and records the observed version
# (6.7.1 observed on the current runner).
#
# Invocation route (NO workflow edits): a temporary proof branch carries this
# script plus an scripts/helper-e2e.ps1 whose job step invokes it - the same
# content-only pattern the A/B diagnostic branch used - and the existing
# build.yml workflow_dispatch (only=pc-e2e, web UI) runs it on the existing
# Windows runner. No .github/workflows change.
#
# Leg map (I2 proof conditions):
#   P1  FIXED leg: harness-planted FOREIGN sentinel on http://+:8488/ survives
#       (the installer IU fixture occupying that URL is removed first, and ONLY
#       after exact-composite verification - never a blind delete).
#       the fixed-chain uninstall (the inherited blind entry no longer fires).
#   P2  OLD-CONTROL leg: 1.9.1 installed twice (merged log, duplicate delacl
#       entries) fires EXACTLY ONE blind delete and the sentinel is DELETED -
#       proves the sentinel discriminates and the at-most-once firing claim.
#   P3  JOURNAL leg: PENDING the UrlAclMigrate journal Kind (migration code,
#       out of scope here). The current helper reads an unknown Kind as Corrupt
#       and fails closed (exit 9), which is not a future-recovery proof. This
#       leg stays required for the migration e2e.
#   P4  ORDER: the Inno uninstall log is parsed for the inert entry, the blind
#       command, and the [Code] --uninstall-tls exec; their log order and the
#       sentinel state give per-phase attribution.
#   P5  OWNED-HTTPS cleanup: asserted only against an actual proven owned
#       fixture (product-provisioned HTTPS reservation + identity marker). On
#       runners where install-time setup cannot provision (known exit-3), this
#       leg reports DEFERRED to the migration e2e - never a silent pass. The
#       firewall rule removal (delrule) is asserted separately.
#   P6  READABLE LOG: fixed-chain uninstall log must show no blind
#       "http delete urlacl" execution; full logs are collected for review.
#   P7  DOWNGRADE control: fixed then OLD downgrade re-arms the blind delete
#       (old entry becomes latest again); sentinel DELETED. This documents the
#       scope boundary: supersession protects only chains whose latest install
#       is the fixed build or newer.
#
# The sentinel is disposable and CI-only: an exact-probe reservation granting a
# synthetic test SID (never Everyone, never Interactive, never the helper's),
# created by this script and removed in every finally block, pass or fail.

param(
    [string]$OldInstaller,
    [string]$NewInstaller,
    [string]$WorkDir = (Join-Path $PSScriptRoot '..\helper\supersession-proof')
)

$ErrorActionPreference = 'Stop'
$SentinelSid = 'S-1-5-21-1111111111-2222222222-3333333333-5123' # synthetic, disposable, CI-only
$SentinelUrl = 'http://+:8488/'
$AppDir = Join-Path $env:LOCALAPPDATA 'Programs\BingeMote'
$script:Deferred = @()
$script:Failures = @()

# The installer itself creates http://+:8488/ granting Interactive (S-1-5-4)
# on every install, so the sentinel add would collide (183). Delete ONLY that
# exact verified fixture first - any other state (foreign, Everyone-grant,
# unexpected SDDL) refuses the delete rather than touching it.
function Remove-InstallerIuReservation {
    $read = (netsh http show urlacl "url=$SentinelUrl") | Out-String
    if ($read -notmatch 'Reserved URL') { throw "expected this install's IU reservation on $SentinelUrl, found none - refusing to touch state" }
    if ($read -match 'Everyone|S-1-1-0') { throw "$SentinelUrl grants Everyone - not this install's known fixture; refusing to delete" }
    if ($read -notmatch 'S-1-5-4|INTERACTIVE') { throw "$SentinelUrl is not the installer IU fixture (foreign state?) - refusing to delete: $read" }
    netsh http delete urlacl "url=$SentinelUrl" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "verified IU reservation delete failed (exit $LASTEXITCODE)" }
    $gone = (netsh http show urlacl "url=$SentinelUrl") | Out-String
    if ($gone -match 'Reserved URL') { throw 'verified IU reservation still present after delete' }
    Write-Host 'verified installer IU fixture removed (exact-match delete, never blind)'
}
function Add-Sentinel {
    Remove-InstallerIuReservation
    netsh http add urlacl "url=$SentinelUrl" "sddl=D:(A;;GX;;;$SentinelSid)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "sentinel add failed (exit $LASTEXITCODE)" }
    $read = (netsh http show urlacl "url=$SentinelUrl") | Out-String
    if ($read -notmatch [regex]::Escape($SentinelSid)) { throw 'sentinel readback does not show the synthetic test SID' }
    if ($read -match 'Everyone|S-1-1-0|S-1-5-4|INTERACTIVE') { throw 'sentinel readback shows a non-test grant' }
    Write-Host 'sentinel planted (exit-checked, exact readback)'
}
function Test-Sentinel {
    $read = (netsh http show urlacl "url=$SentinelUrl") | Out-String
    return $read -match [regex]::Escape($SentinelSid)
}
function Remove-Sentinel {
    # Delete only what this harness planted: if the reservation no longer shows
    # the synthetic test SID, something replaced it mid-leg - leave it and say
    # so loudly rather than deleting foreign state.
    $read = (netsh http show urlacl "url=$SentinelUrl") | Out-String
    if ($read -notmatch 'Reserved URL') { Write-Host 'sentinel already absent (expected after blind-delete legs)'; return }
    if ($read -notmatch [regex]::Escape($SentinelSid)) { Write-Host "WARNING: $SentinelUrl no longer shows the synthetic test SID - NOT deleting (foreign state?)"; return }
    if ($read -match 'Everyone|S-1-1-0|S-1-5-4|INTERACTIVE') { Write-Host "WARNING: $SentinelUrl shows a non-test grant alongside the sentinel - NOT deleting"; return }
    netsh http delete urlacl "url=$SentinelUrl" | Out-Null
    Write-Host 'sentinel removed (verified synthetic SID before delete)'
}
# Every silent install auto-launches the helper ([Run] skipifnotsilent
# runasoriginaluser), so an over-install hits Inno RestartManager with
# /SUPPRESSMSGBOXES and Abort-defaults (observed: run 46109790484 exit 5).
# Stop ONLY the helper this harness installed: the image path must EQUAL
# $HelperExe (ordinal-ignore-case, no prefix match - a BingeMote2 sibling
# or any other image is foreign state: refuse, never kill it).
$HelperExe = Join-Path $AppDir 'BingeMote.exe'
function Stop-InstalledHelper([bool]$waitForLaunch) {
    $tries = 1; if ($waitForLaunch) { $tries = 10 }  # the [Run] nowait auto-launch can lag installer exit
    $procs = @()
    foreach ($i in 1..$tries) {
        $procs = @(Get-CimInstance Win32_Process -Filter "Name='BingeMote.exe'" -ErrorAction SilentlyContinue)
        if ($procs.Count -gt 0) { break }
        if ($waitForLaunch) { Start-Sleep -Milliseconds 500 }
    }
    if ($procs.Count -eq 0) { Write-Host 'no helper process running (nothing to stop)'; return }
    foreach ($p in $procs) {
        if (-not [string]::Equals($p.ExecutablePath, $HelperExe, [StringComparison]::OrdinalIgnoreCase)) {
            throw "BingeMote.exe pid $($p.ProcessId) image '$($p.ExecutablePath)' is not exactly $HelperExe - foreign state, refusing to stop"
        }
    }
    foreach ($p in $procs) {
        # kill-time identity recheck: re-read the live image before terminating
        $now = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.ProcessId)" -ErrorAction SilentlyContinue
        if (-not $now) { continue }  # exited on its own
        if (-not [string]::Equals($now.ExecutablePath, $HelperExe, [StringComparison]::OrdinalIgnoreCase)) {
            throw "BingeMote.exe pid $($p.ProcessId) image changed before stop - foreign state, refusing"
        }
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(15)
    foreach ($p in $procs) {
        while ((Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue) -and ((Get-Date) -lt $deadline)) { Start-Sleep -Milliseconds 200 }
        if (Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue) { throw "installed helper pid $($p.ProcessId) did not exit within 15s" }
    }
    Write-Host "stopped $($procs.Count) harness-installed helper process(es): exact image $HelperExe"
}
function Install($pkg, $logName) {
    $log = Join-Path $WorkDir $logName
    & $pkg /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG="$log" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path $log) { Get-Content $log -Tail 60 | Write-Host }
        throw "install failed ($LASTEXITCODE): $pkg"
    }
}
function Uninstall($logName) {
    $unins = Join-Path $AppDir 'unins000.exe'
    if (-not (Test-Path $unins)) { throw "uninstaller not found at $unins" }
    $log = Join-Path $WorkDir $logName
    & $unins /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG="$log" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "uninstall failed ($LASTEXITCODE)" }
    return (Get-Content $log -Raw)
}
# P2/P6/P7: locate phase markers in the uninstall log. Inno never logs [Code]-
# driven Exec calls (observed run 46110921073; consistent with issrc
# Setup.Uninstall.pas), so the [Code] --uninstall-tls exec is NOT greped here -
# its attribution is Assert-CodeCleanupFresh's job via helper.log freshness.
function Assert-LogOrder($logText, [bool]$expectBlind) {
    $inert = $logText.IndexOf('/c exit 0')
    $blind = $logText.IndexOf('http delete urlacl')
    if ($expectBlind) {
        if ($blind -lt 0) { throw 'P2/P7: expected blind http delete urlacl execution in the log, none found' }
        $count = ([regex]::Matches($logText, 'http delete urlacl')).Count
        if ($count -ne 1) { throw "P2/P7: expected exactly ONE blind delete execution (RunOnceId at-most-once), log shows $count" }
    } else {
        if ($blind -ge 0) { throw 'P6: blind http delete urlacl execution found in the FIXED-chain uninstall log' }
        if ($inert -lt 0) { throw 'P6: inert delacl entry execution not found in the fixed-chain uninstall log' }
    }
}
# P4, corrected against Inno 6.7.1 source (Setup.Uninstall.pas: CurUninstallStep-
# Changed(usUninstall), which Execs the helper's --uninstall-tls, fires BEFORE
# UninstLog.PerformUninstall processes [UninstallRun]). Real order is therefore
# code-exec FIRST, then the inert [UninstallRun] entry - the reverse of the old
# doc-derived expectation. Attribution: a FRESH 'tls removal' helper.log entry
# (line index beyond the pre-uninstall count) inside this uninstall's
# wall-clock window, timestamped no later than the inert entry. Marker choice:
# TlsSetup.Remove logs 'tls removal:' on EVERY outcome (retained, removed,
# enumeration failure), while 'uninstall cleanup:' is absent on the successful
# owned-delete path (FirewallSetup.cs RunUninstallCleanup) - so 'tls removal:'
# is the unconditional signature. helper.log is
# second-resolution; the uninstall log is millisecond. Both logs use the
# runner's local zone (UTC on the CI runner).
# Resolve at CALL time, never once at load: on a fresh runner the helper
# creates helper.log during the legs, so a one-time top-level Test-Path picks
# the legacy fallback permanently and every later check reads the wrong path
# (observed: run 46112197533 threw "helper.log missing" at the assert while the
# real log existed at the primary path - load-before-log regression). Primary
# is the current data dir; legacy is the pre-PickDataDir data dir.
function Resolve-HelperLog {
    $p = Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'
    if (Test-Path $p) { return $p }
    $l = Join-Path $env:LOCALAPPDATA 'BingeMoteHelper\helper.log'
    if (Test-Path $l) { return $l }
    return $null
}
function Count-CleanupLines {
    $h = Resolve-HelperLog
    if (-not $h) { return 0 }
    return ([regex]::Matches((Get-Content $h -Raw), 'tls removal:')).Count
}
function Assert-CodeCleanupFresh([int]$hlBefore, $t0, $t1, $uninstallLogText) {
    $h = Resolve-HelperLog
    if (-not $h) { throw 'P4: helper.log missing after uninstall (neither current nor legacy data dir) - cannot attribute the [Code] --uninstall-tls exec (non-vacuous requirement)' }
    $all = [regex]::Matches((Get-Content $h -Raw), '(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+tls removal:')
    if ($all.Count -le $hlBefore) { throw 'P4: no FRESH helper tls-removal entry written during this uninstall - the [Code] --uninstall-tls exec cannot be attributed' }
    $cleanAt = [datetime]::ParseExact($all[$hlBefore].Groups[1].Value, 'yyyy-MM-dd HH:mm:ss', $null)
    if ($cleanAt -lt $t0.AddSeconds(-1) -or $cleanAt -gt $t1.AddSeconds(1)) { throw "P4: fresh cleanup entry at $cleanAt outside this uninstall's window ($t0 - $t1) - stale or foreign invocation" }
    $inertM = [regex]::Match($uninstallLogText, '(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.\d{3}\s+Running Exec parameters: /c exit 0')
    if (-not $inertM.Success) { throw 'P4: inert [UninstallRun] entry timestamp not found in the uninstall log' }
    $inertAt = [datetime]::ParseExact($inertM.Groups[1].Value, 'yyyy-MM-dd HH:mm:ss', $null)
    if ($cleanAt -gt $inertAt) { throw "P4: order inverted vs Inno 6.7.1 source: cleanup $cleanAt AFTER inert entry $inertAt ([Code] usUninstall must precede [UninstallRun] processing)" }
    if ($cleanAt -eq $inertAt) { Write-Host 'P4 note: cleanup and inert entry share a second; order corroborated structurally (Inno source), timestamps are second-resolution' }
    Write-Host "ok P4: fresh [Code] --uninstall-tls cleanup at $cleanAt inside the window, preceding the inert entry at $inertAt (source-corrected order)"
}
function Assert-FirewallRemoved {
    $rule = (netsh advfirewall firewall show rule name="BingeMote") | Out-String
    if ($rule -match 'BingeMote') { throw 'P5 (separate): BingeMote firewall rule still present after uninstall (delrule failed)' }
    Write-Host 'ok: firewall rule removed (delrule, asserted separately)'
}
# P5: owned-HTTPS cleanup, non-vacuous: requires the product's own PROVEN-OWNED
# fixture - IU-scoped https reservation (not Everyone), a 0.0.0.0:8488 binding,
# and an identity marker passing the product's field shapes. Anything less
# defers loudly; the after-state asserts reservation AND bindings removed (the
# identity/marker intentionally survives uninstall by product design).
$MarkerPath = Join-Path $env:ProgramData 'BingeMote\tls-identity.json'
function Test-HttpsProvisioned {
    $acl = (netsh http show urlacl 'url=https://+:8488/') | Out-String
    if ($acl -notmatch 'Reserved URL' -or $acl -match 'Everyone|S-1-1-0' -or $acl -notmatch 'S-1-5-4|INTERACTIVE') { return $false }
    $binds = (netsh http show sslcert) | Out-String
    if ($binds -notmatch '0\.0\.0\.0:8488') { return $false }
    if (-not (Test-Path $MarkerPath)) { return $false }
    try {
        $m = Get-Content $MarkerPath -Raw | ConvertFrom-Json
        return ($m.Thumbprint -match '^[0-9A-Fa-f]{40}$' -and $m.SpkiSha256Hex -match '^[0-9a-f]{64}$' -and [guid]::TryParse($m.AppId, [ref][guid]::Empty) -and $m.Port -eq 8488)
    } catch { return $false }
}
function Assert-OwnedHttpsCleanup([bool]$wasProvisioned) {
    if (-not $wasProvisioned) {
        $script:Deferred += 'P5: no proven-owned HTTPS fixture (reservation + binding + valid marker) in this environment; owned-cleanup leg DEFERRED to the migration e2e (explicit, non-vacuous)'
        return
    }
    $https = (netsh http show urlacl 'url=https://+:8488/') | Out-String
    if ($https -match 'Reserved URL') { throw 'P5: owned HTTPS reservation survived uninstall - helper cleanup did not run or refused its own state' }
    $binds = (netsh http show sslcert) | Out-String
    if ($binds -match '0\.0\.0\.0:8488') { throw 'P5: owned TLS binding survived uninstall - TlsSetup.Remove did not remove it' }
    Write-Host 'ok: proven-owned HTTPS reservation AND TLS binding removed by the classified helper cleanup'
}

New-Item -ItemType Directory -Force $WorkDir | Out-Null

# Compiler version: observed, asserted equal across both builds, never pinned up.
function Assert-SameCompilerVersion($logA, $logB) {
    $read = { param($n) ([regex]::Match((Get-Content (Join-Path $WorkDir $n) -Raw), 'Setup version: Inno Setup version ([0-9.]+)')).Groups[1].Value }
    $va = & $read $logA; $vb = & $read $logB
    if (-not $va -or -not $vb) { throw "compiler version line missing from an install log ($logA='$va' $logB='$vb')" }
    if ($va -ne $vb) { throw "compiler version mismatch across legs: $va vs $vb - rebuild both installers with one compiler" }
    Write-Host "ok: both installers built by the same observed Inno Setup $va"
}

# ---- P1/P4/P5/P6: FIXED chain (old 1.9.1 -> new inert build) ----
Write-Host '== FIXED leg: 1.9.1 -> inert-delacl build =='
try {
    Stop-InstalledHelper $false  # leg start: clear any helper a prior leg's uninstall left running
    Install $OldInstaller 'fixed-install-old.log'
    Stop-InstalledHelper $true   # baseline auto-launched its helper; over-install would Abort in RestartManager
    Install $NewInstaller 'fixed-install-new.log'
    Assert-SameCompilerVersion 'fixed-install-old.log' 'fixed-install-new.log'
    $provisioned = Test-HttpsProvisioned
    Add-Sentinel
    $hlBefore = Count-CleanupLines
    $t0 = Get-Date
    $log = Uninstall 'fixed-uninstall.log'
    $t1 = Get-Date
    Assert-LogOrder $log $false
    Assert-CodeCleanupFresh $hlBefore $t0 $t1 $log
    if (-not (Test-Sentinel)) { throw 'P1: foreign sentinel was DELETED on the fixed chain - supersession did not hold' }
    Write-Host 'ok P1/P6: sentinel survived; no blind delete in the fixed-chain log'
    Assert-OwnedHttpsCleanup $provisioned
    Assert-FirewallRemoved
} finally {
    Remove-Sentinel
}

# ---- P2: OLD-CONTROL (merged duplicate delacl entries fire exactly once) ----
Write-Host '== OLD-CONTROL leg: 1.9.1 -> 1.9.1 (merged log) =='
try {
    Stop-InstalledHelper $false  # leg start cleanup
    Install $OldInstaller 'old-install-1.log'
    Stop-InstalledHelper $true   # same over-install hazard on the 1.9.1 -> 1.9.1 transition
    Install $OldInstaller 'old-install-2.log'
    Add-Sentinel
    $log = Uninstall 'old-uninstall.log'
    Assert-LogOrder $log $true
    if (Test-Sentinel) { throw 'P2: sentinel SURVIVED the old-chain uninstall - the sentinel does not discriminate; P1 would be vacuous' }
    Write-Host 'ok P2: exactly one blind delete fired; sentinel deleted (discriminating control)'
} finally {
    Remove-Sentinel
}

# ---- P7: DOWNGRADE (fixed -> old re-arms the blind delete; scope boundary) ----
Write-Host '== DOWNGRADE leg: inert build -> 1.9.1 over it =='
try {
    Stop-InstalledHelper $false  # leg start cleanup
    Install $NewInstaller 'down-install-new.log'
    Stop-InstalledHelper $true   # same over-install hazard on the downgrade transition
    Install $OldInstaller 'down-install-old.log'
    Assert-SameCompilerVersion 'down-install-new.log' 'down-install-old.log'
    Add-Sentinel
    $log = Uninstall 'down-uninstall.log'
    Assert-LogOrder $log $true
    if (Test-Sentinel) { throw 'P7: sentinel survived after downgrade to the old build - expected the re-armed blind delete' }
    Write-Host 'ok P7: downgrade re-armed the blind delete (documented scope boundary)'
} finally {
    Remove-Sentinel
}

Write-Host ''
foreach ($d in $script:Deferred) { Write-Host "DEFERRED: $d" }
Write-Host '== supersession proof complete; logs in' $WorkDir '=='
