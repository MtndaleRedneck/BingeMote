# iss-lint.ps1 - structural lint for helper/installer.iss [UninstallRun].
# Permanent guard for the RunOnceId "delacl" neutralization:
#   1. No [UninstallRun] entry may run a destructive urlacl removal
#      ("http delete urlacl"). Classified HTTP removal is the helper's job
#      (--uninstall-tls), never a blind uninstall command.
#   2. Every [UninstallRun] entry must carry a RunOnceId, so merged upgrade
#      logs can never duplicate-fire an entry.
#   3. RunOnceId "delacl" (exact, lowercase) is reserved for the single inert
#      supersession entry ({cmd} /c exit 0). No other entry may use it, and
#      the inert entry must not regress to a real command.
# EVERY non-empty, non-comment line in the section must parse as an entry or
# the build fails - no entry slips past by malformation. Entries must be in
# canonical order (Filename first): a reordered line fails and gets normalized,
# so the policy checks below can never be bypassed by field order.
# Inno escapes embedded quotes by doubling ("") - the grammar accepts doubled
# quotes inside quoted values and un-doubles before any policy check.
# Runs on every installer change; any failure exits 1 with a readable message.
$ErrorActionPreference = 'Stop'
$issPath = Join-Path $PSScriptRoot '..\helper\installer.iss'
$iss = Get-Content $issPath -Raw

$sectionHits = [regex]::Matches($iss, '(?m)^\[UninstallRun\]\s*$')
if ($sectionHits.Count -gt 1) { throw "iss-lint: $($sectionHits.Count) [UninstallRun] sections found - duplicate sections are rejected (a second section could evade this lint)" }
$section = [regex]::Match($iss, '(?ms)^\[UninstallRun\]\s*\r?\n(.*?)(?=^\[|\z)')
if (-not $section.Success) { throw "iss-lint: no [UninstallRun] section found in $issPath" }

$fail = @()
$entries = @()
$knownKeys = @('Filename', 'Parameters', 'Flags', 'RunOnceId', 'Description', 'StatusMsg', 'Verb', 'WorkingDir', 'Check', 'AfterInstall', 'BeforeInstall', 'Components', 'Tasks', 'Languages', 'MinVersion', 'OnlyBelowVersion', 'ArchitecturesAllowed', 'ArchitecturesInstallIn64BitMode')
$quoted = '"(?:[^"]|"")*"'           # quoted value, Inno-doubled quotes inside
$lineRe = [regex]('^Filename:\s*' + $quoted + '(\s*;\s*[A-Za-z]+:\s*(' + $quoted + '|[^;"]+))*\s*$')
$kvRe   = [regex]'([A-Za-z]+):\s*("(?:[^"]|"")*"|[^;"]+?)\s*(?=;|$)'

foreach ($line in ($section.Groups[1].Value -split '\r?\n')) {
    $t = $line.Trim()
    if (-not $t -or $t.StartsWith(';')) { continue }
    if (-not $lineRe.IsMatch($t)) {
        $fail += "unparsed [UninstallRun] line (refusing to skip; canonical order is Filename first): $t"
        continue
    }
    $map = @{}
    foreach ($kv in $kvRe.Matches($t)) {
        $k = $kv.Groups[1].Value
        if ($k -notin $knownKeys) { $fail += "unknown [UninstallRun] field '$k' in: $t" }
        $v = $kv.Groups[2].Value
        if ($v.StartsWith('"')) { $v = $v.Substring(1, $v.Length - 2).Replace('""', '"') }  # un-double Inno escapes
        $map[$k.ToLower()] = $v
    }
    $entries += $map
}

if ($entries.Count -eq 0 -and $fail.Count -eq 0) { throw 'iss-lint: [UninstallRun] section has no entries' }

$delacl = @()
foreach ($e in $entries) {
    $file = $e['filename']; $parms = $e['parameters']; $id = $e['runonceid']
    if (($file + ' ' + $parms) -match 'http\s+delete\s+urlacl') {
        $fail += "destructive urlacl removal in [UninstallRun]: $file $parms"
    }
    if (-not $id) {
        $fail += "[UninstallRun] entry without RunOnceId: $file $parms"
    }
    if ($id -ceq 'delacl') { $delacl += $e }
    elseif ($id -ieq 'delacl') {
        $fail += "RunOnceId '$id' differs from reserved exact-lowercase 'delacl' (matching is case-sensitive)"
    }
}
if ($delacl.Count -ne 1) {
    $fail += "expected exactly one RunOnceId `"delacl`" inert entry, found $($delacl.Count)"
}
elseif ($delacl[0]['filename'] -ne '{cmd}' -or $delacl[0]['parameters'] -ne '/c exit 0') {
    $fail += 'the "delacl" entry must stay the inert supersession entry: Filename "{cmd}", Parameters "/c exit 0"'
}

if ($fail.Count) {
    $fail | ForEach-Object { Write-Host "iss-lint FAIL: $_" }
    exit 1
}
Write-Host "iss-lint ok: $($entries.Count) [UninstallRun] entries, all parsed and RunOnceId'd, delacl inert-reserved, no destructive urlacl removal"
exit 0
