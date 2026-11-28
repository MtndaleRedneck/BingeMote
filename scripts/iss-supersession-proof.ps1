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
    netsh http delete urlacl "url=$SentinelUrl" | Out-Null
    Write-Host 'sentinel cleanup attempted'
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
# P4/P6: locate phase markers in the uninstall log; positions give the order.
function Assert-LogOrder($logText, [bool]$expectBlind) {
    $inert = $logText.IndexOf('/c exit 0')
    $blind = $logText.IndexOf('http delete urlacl')
    $code  = $logText.IndexOf('--uninstall-tls')
    if ($expectBlind) {
        if ($blind -lt 0) { throw 'P2/P7: expected blind http delete urlacl execution in the log, none found' }
        $count = ([regex]::Matches($logText, 'http delete urlacl')).Count
        if ($count -ne 1) { throw "P2/P7: expected exactly ONE blind delete execution (RunOnceId at-most-once), log shows $count" }
    } else {
        if ($blind -ge 0) { throw 'P6: blind http delete urlacl execution found in the FIXED-chain uninstall log' }
        if ($inert -lt 0) { throw 'P6: inert delacl entry execution not found in the fixed-chain uninstall log' }
    }
    if (-not $expectBlind) {
        if ($code -lt 0) { throw 'P4: --uninstall-tls exec marker missing from the fixed-chain uninstall log - phase order cannot be attributed (non-vacuous requirement)' }
        if ($inert -gt $code) { throw 'P4: log order wrong: [UninstallRun] inert entry logged AFTER the [Code] --uninstall-tls exec' }
    }
}
function Assert-FirewallRemoved {
    $rule = (netsh advfirewall firewall show rule name="BingeMote") | Out-String
    if ($rule -match 'BingeMote') { throw 'P5 (separate): BingeMote firewall rule still present after uninstall (delrule failed)' }
    Write-Host 'ok: firewall rule removed (delrule, asserted separately)'
}
# P5: owned-HTTPS cleanup, non-vacuous: requires the product's own proven fixture.
function Assert-OwnedHttpsCleanup([bool]$wasProvisioned) {
    if (-not $wasProvisioned) {
        $script:Deferred += 'P5: product install-time setup did not provision an owned HTTPS reservation in this environment; owned-HTTPS cleanup leg DEFERRED to the migration e2e (explicit, non-vacuous)'
        return
    }
    $https = (netsh http show urlacl 'url=https://+:8488/') | Out-String
    if ($https -match '8488') { throw 'P5: owned HTTPS reservation survived uninstall - helper cleanup did not run or refused its own state' }
    Write-Host 'ok: owned HTTPS reservation removed by the classified helper cleanup'
}
function Test-HttpsProvisioned {
    $https = (netsh http show urlacl 'url=https://+:8488/') | Out-String
    return $https -match 'S-1-5-4|INTERACTIVE'
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
    Install $OldInstaller 'fixed-install-old.log'
    Install $NewInstaller 'fixed-install-new.log'
    Assert-SameCompilerVersion 'fixed-install-old.log' 'fixed-install-new.log'
    $provisioned = Test-HttpsProvisioned
    Add-Sentinel
    $log = Uninstall 'fixed-uninstall.log'
    Assert-LogOrder $log $false
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
    Install $OldInstaller 'old-install-1.log'
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
    Install $NewInstaller 'down-install-new.log'
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
