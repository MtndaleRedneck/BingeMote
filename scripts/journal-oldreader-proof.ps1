# journal-oldreader-proof.ps1 - old-reader fail-closed proof for a journal
# carrying the future UrlAclMigrate Kind.
#
# This is the P3 leg, scoped honestly: it proves the CURRENT reader's behavior
# against a future journal - unknown Kind reads Corrupt, the helper fails
# closed, and the journal plus all transport state survive byte-identical. It
# is NOT a future-recovery proof: real UrlAclMigrate recovery can only be
# proven once that Kind is implemented (migration code, out of scope here).
#
# Valid-deserialize CONTROL (finite): runs separately as an isolated actual-
# reader test seam (helper/BingeMoteHelper.Tests/JournalOldReaderFixtureTests.cs
# via dotnet test --filter JournalOldReaderFixture): known Kind -> Present,
# unknown Kind -> Corrupt, malformed -> Corrupt. A REAL Fresh-Kind setup run is
# deliberately NOT the control: it can recover and mutate.
#
# Two surfaces, per I2's P3:
#   A. Setup surface: --install-firewall with the planted journal must exit 9
#      (journal read inside the single mutex acquisition, before any mutation -
#      HelperTls.cs:582-586), with the exit ATTRIBUTED via the helper.log
#      "operation journal" line, leaving the journal byte-identical.
#   B. Uninstall surface: --uninstall-tls with the planted journal must KEEP
#      the journal-held state and leave the journal byte-identical (pending
#      journal outside the app is retained intentionally), ATTRIBUTED via the
#      helper.log "journal Corrupt ... not provably removable" line.
#      Reinstall-during-recovery is a future migration test, not part of here.
#
# Transport-hold claims are NON-VACUOUS (finite): they are only asserted when
# a PROVEN-OWNED HTTPS reservation + binding + valid identity marker is
# present - either the product's own provisioned state or a disposable owned
# fixture this script plants (self-signed cert, IU https reservation, sslcert
# binding, matching marker), every piece removed/restored in the finally
# block. If neither is possible (foreign/partial state, or a plant failure),
# the transport claim is reported DEFERRED - loudly - while the journal-hash
# and attributed exit-9 assertions still run. Empty==empty is never a pass.
#
# Ownership-safe journal fixture: prior state of tls-journal.json (absent or
# exact bytes) captured and restored in every finally block.
#
# Runs elevated against an installed helper. No product or migration code.

param(
    [string]$WorkDir = (Join-Path $PSScriptRoot '..\helper\journal-proof')
)

$ErrorActionPreference = 'Stop'
$AppExe    = Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\BingeMote.exe'
$StateDir  = Join-Path $env:ProgramData 'BingeMote'
$Journal   = Join-Path $StateDir 'tls-journal.json'
$Marker    = Join-Path $StateDir 'tls-identity.json'
# DataDir is %LocalAppData%\BingeMote on a fresh machine, but PickDataDir
# (Program.cs:38-43) falls back to the legacy ...\BingeMoteHelper dir when only
# that exists - which the 1.9.1 baseline leg can cause. Attribution checks must
# read whichever candidate log actually received new lines.
$HelperLogCandidates = @(
    (Join-Path $env:LOCALAPPDATA 'BingeMote\helper.log'),
    (Join-Path $env:LOCALAPPDATA 'BingeMoteHelper\helper.log')
)
$FixtureAppId = '{11111111-2222-3333-4444-555555555555}'

if (-not (Test-Path $AppExe)) { throw "helper not installed at $AppExe - install the fixed build first" }
New-Item -ItemType Directory -Force $WorkDir | Out-Null
$script:Deferred = @()
$script:Planted = @{ Cert = $false; Acl = $false; Binding = $false; Marker = $false }
$script:CertThumb = $null

$futureJournal = (@{
    Kind       = 'UrlAclMigrate'
    NewThumb   = $null
    OldThumb   = $null
    CertAdded  = $false
    Bound4     = $false
    Bound6     = $false
    Updated4   = $false
    Updated6   = $false
    StartedUtc = '2026-11-28T00:00:00Z'
} | ConvertTo-Json -Compress)

function Get-TransportState {
    [pscustomobject]@{
        HttpsAcl = ((netsh http show urlacl 'url=https://+:8488/') | Out-String)
        Bindings = ((netsh http show sslcert) | Out-String)
    }
}
# Proven-owned shape: IU-scoped https reservation (not Everyone), a 8488
# binding, and a marker file whose fields pass the product's own regexes.
function Test-OwnedTransportPresent {
    $acl = (netsh http show urlacl 'url=https://+:8488/') | Out-String
    if ($acl -notmatch 'Reserved URL' -or $acl -match 'Everyone|S-1-1-0' -or $acl -notmatch 'S-1-5-4|INTERACTIVE') { return $false }
    $binds = (netsh http show sslcert) | Out-String
    if ($binds -notmatch '0\.0\.0\.0:8488') { return $false }
    if (-not (Test-Path $Marker)) { return $false }
    try {
        $m = Get-Content $Marker -Raw | ConvertFrom-Json
        return ($m.Thumbprint -match '^[0-9A-Fa-f]{40}$' -and $m.SpkiSha256Hex -match '^[0-9a-f]{64}$' -and [guid]::TryParse($m.AppId, [ref][guid]::Empty) -and $m.Port -eq 8488)
    } catch { return $false }
}
function Install-OwnedFixture {
    # Disposable owned fixture: cert + IU https reservation + sslcert binding +
    # matching identity marker. Every planted piece is unwound in the finally.
    $cert = New-SelfSignedCertificate -DnsName 'bingemote-ci-journal-fixture' -CertStoreLocation 'Cert:\LocalMachine\My' -NotAfter (Get-Date).AddDays(1)
    $script:CertThumb = $cert.Thumbprint; $script:Planted.Cert = $true
    $spki = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($cert.PublicKey.EncodedKeyValue.RawData)).Replace('-', '').ToLower()
    netsh http add urlacl 'url=https://+:8488/' 'sddl=D:(A;;GX;;;S-1-5-4)' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "fixture https reservation add failed ($LASTEXITCODE)" }
    $script:Planted.Acl = $true
    netsh http add sslcert ipport=0.0.0.0:8488 "certhash=$($cert.Thumbprint)" "appid=$FixtureAppId" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "fixture sslcert binding failed ($LASTEXITCODE)" }
    $script:Planted.Binding = $true
    $hadMarker = Test-Path $Marker
    if ($hadMarker) { $script:PriorMarkerBytes = [System.IO.File]::ReadAllBytes($Marker) }
    $script:HadMarker = $hadMarker
    (@{ Thumbprint = $cert.Thumbprint; SpkiSha256Hex = $spki; AppId = $FixtureAppId.Trim('{}'); Port = 8488; CreatedUtc = '2026-11-28T00:00:00Z' } | ConvertTo-Json -Compress) | Set-Content $Marker -NoNewline
    $script:Planted.Marker = $true
    if (-not (Test-OwnedTransportPresent)) { throw 'planted fixture does not verify as proven-owned' }
    Write-Host 'disposable owned transport fixture planted and verified'
}
function Remove-OwnedFixture {
    if ($script:Planted.Marker) {
        if ($script:HadMarker) { [System.IO.File]::WriteAllBytes($Marker, $script:PriorMarkerBytes) }
        elseif (Test-Path $Marker) { Remove-Item $Marker -Force }
    }
    if ($script:Planted.Binding) { netsh http delete sslcert ipport=0.0.0.0:8488 | Out-Null }
    if ($script:Planted.Acl) { netsh http delete urlacl 'url=https://+:8488/' | Out-Null }
    if ($script:Planted.Cert -and $script:CertThumb) { Remove-Item "Cert:\LocalMachine\My\$($script:CertThumb)" -Force -ErrorAction SilentlyContinue }
    Write-Host 'owned fixture unwound'
}

# Prior journal state for ownership-safe restore.
$hadJournal = Test-Path $Journal
$priorBytes = $null
if ($hadJournal) { $priorBytes = [System.IO.File]::ReadAllBytes($Journal) }
function Restore-Journal {
    if ($script:hadJournal) { [System.IO.File]::WriteAllBytes($script:Journal, $script:priorBytes) }
    elseif (Test-Path $script:Journal) { Remove-Item $script:Journal -Force }
    Write-Host 'journal prior state restored'
}
function Get-LogPositions {
    $pos = @{}
    foreach ($c in $HelperLogCandidates) { $pos[$c] = (Test-Path $c) ? (Get-Item $c).Length : 0 }
    return $pos
}
function Get-NewLogLines($positions) {
    $text = ''
    foreach ($c in $HelperLogCandidates) {
        if (-not (Test-Path $c)) { continue }
        $fs = [System.IO.File]::Open($c, 'Open', 'Read', 'ReadWrite')
        try { $fs.Position = [Math]::Min([long]$positions[$c], $fs.Length); $sr = New-Object System.IO.StreamReader($fs); $text += $sr.ReadToEnd() } finally { $fs.Dispose() }
    }
    return $text
}

$transportProven = $false
try {
    # Non-vacuous transport gate: proven-owned present, plant a disposable
    # fixture, or DEFER the transport claims loudly.
    if (Test-OwnedTransportPresent) {
        $transportProven = $true
        Write-Host 'proven-owned transport state present (product-provisioned)'
    } else {
        try {
            Install-OwnedFixture
            $transportProven = $true
        } catch {
            $script:Deferred += "transport-hold claims DEFERRED: no proven-owned HTTPS reservation + binding + marker available and the disposable fixture could not be planted ($($_.Exception.Message)). Journal-retention and attributed exit-9 assertions still run."
        }
    }

    [System.IO.File]::WriteAllText($Journal, $futureJournal)
    $plantedHash = (Get-FileHash $Journal -Algorithm SHA256).Hash
    Write-Host "planted future-Kind journal (sha256 $plantedHash)"

    # --- Surface A: setup fails closed, attributed exit 9, nothing mutated ---
    $before = Get-TransportState
    $logPos = Get-LogPositions
    $p = Start-Process $AppExe -ArgumentList '--install-firewall' -Wait -PassThru
    if ($p.ExitCode -ne 9) { throw "surface A: expected fail-closed exit 9 with an unknown-Kind journal, got $($p.ExitCode)" }
    $tail = Get-NewLogLines $logPos
    if ($tail -notmatch 'operation journal') { throw 'surface A: exit 9 not attributed to the journal (expected helper.log "operation journal" line)' }
    if ((Get-FileHash $Journal -Algorithm SHA256).Hash -ne $plantedHash) { throw 'surface A: journal bytes changed across the fail-closed run' }
    if ($transportProven) {
        $after = Get-TransportState
        if ($after.HttpsAcl -ne $before.HttpsAcl -or $after.Bindings -ne $before.Bindings) { throw 'surface A: proven-owned transport state changed across a fail-closed run' }
        Write-Host 'ok surface A: attributed exit 9, journal byte-identical, proven-owned transport unchanged'
    } else {
        Write-Host 'ok surface A: attributed exit 9, journal byte-identical (transport claim DEFERRED above)'
    }

    # --- Surface B: uninstall cleanup holds journal-held state, attributed ---
    $before = Get-TransportState
    $logPos = Get-LogPositions
    $p = Start-Process $AppExe -ArgumentList '--uninstall-tls' -Wait -PassThru
    Write-Host "surface B: --uninstall-tls exit $($p.ExitCode) (journal-held; not asserted - exit contract is the migration plan's)"
    $tail = Get-NewLogLines $logPos
    if ((Get-FileHash $Journal -Algorithm SHA256).Hash -ne $plantedHash) { throw 'surface B: journal bytes changed across the journal-held uninstall cleanup' }
    if ($transportProven) {
        if ($tail -notmatch 'journal Corrupt') { throw 'surface B: journal-held keep not attributed (expected helper.log "journal Corrupt" line)' }
        $after = Get-TransportState
        if ($after.HttpsAcl -ne $before.HttpsAcl -or $after.Bindings -ne $before.Bindings) { throw 'surface B: journal-held proven-owned transport state changed' }
        Write-Host 'ok surface B: attributed journal-held keep (HTTPS reservation + bindings), journal byte-identical'
    } else {
        Write-Host 'ok surface B: journal byte-identical (transport claim DEFERRED above)'
    }
} finally {
    try { Restore-Journal } finally { Remove-OwnedFixture }
}

foreach ($d in $script:Deferred) { Write-Host "DEFERRED: $d" }
Write-Host '== journal old-reader proof complete =='
