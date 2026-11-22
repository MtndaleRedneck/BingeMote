# BingeMote PC Scan 1.0.0
# One-time, READ-ONLY leftover check for BingeMote on this PC.
# It changes nothing: no deletes, no settings, no fixes. It only looks and reports.
# Run: right-click > Run with PowerShell, or:  powershell -ExecutionPolicy Bypass -File .\BingeMote-PC-Scan-1.0.0.ps1
# What it CAN tell you: what BingeMote-related tasks, services, files, firewall
# rules, and port listeners exist at the moment you run it.
# What it CANNOT tell you: that the whole PC is problem-free. It is a snapshot of
# the BingeMote items below, not a general PC health scan.

$ErrorActionPreference = 'SilentlyContinue'
$script:findings = 0

function Section($title) {
    Write-Host ""
    Write-Host ("=" * 70)
    Write-Host $title
    Write-Host ("=" * 70)
}
function Found($line) { $script:findings++; Write-Host ("  FOUND: " + $line) }
function Clean($line) { Write-Host ("  none:  " + $line) }

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
Write-Host "BingeMote PC Scan 1.0.0 - read-only report, $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
if (-not $isAdmin) {
    Write-Host "NOTE: not running as Administrator. Tasks/services owned by SYSTEM may be hidden from this scan."
}

Section "1. Scheduled tasks with 'BingeMote' in the name or path"
$tasks = Get-ScheduledTask | Where-Object { $_.TaskName -match 'BingeMote' -or $_.TaskPath -match 'BingeMote' }
if ($tasks) { foreach ($t in $tasks) { Found ("task: $($t.TaskPath)$($t.TaskName)  state=$($t.State)") } }
else { Clean "no scheduled tasks mention BingeMote" }

Section "2. Services with 'BingeMote' in the name or display name"
$svcs = Get-CimInstance Win32_Service | Where-Object { $_.Name -match 'BingeMote' -or $_.DisplayName -match 'BingeMote' }
if ($svcs) { foreach ($s in $svcs) { Found ("service: $($s.Name)  state=$($s.State)  path=$($s.PathName)") } }
else { Clean "no services mention BingeMote" }

Section "3. BingeMote folders and executables in the usual install spots"
$dirs = @(
    "$env:ProgramFiles\BingeMote",
    "${env:ProgramFiles(x86)}\BingeMote",
    "$env:LOCALAPPDATA\BingeMote",
    "$env:LOCALAPPDATA\Programs\BingeMote",
    "$env:APPDATA\BingeMote",
    "$env:ProgramData\BingeMote"
)
$anyDir = $false
foreach ($d in $dirs) {
    if ($d -and (Test-Path $d)) {
        $anyDir = $true
        $exes = Get-ChildItem $d -Recurse -Include *.exe, *.ps1, *.bat -File | Select-Object -ExpandProperty FullName
        Found ("folder exists: $d" + $(if ($exes) { "  with: " + ($exes -join ', ') } else { "  (no executables/scripts inside)" }))
    }
}
if (-not $anyDir) { Clean "no BingeMote folders in the usual install spots" }

Section "4. Auto-start entries (registry Run keys and Startup folder)"
$runKeys = @(
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run',
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Run'
)
$anyRun = $false
foreach ($k in $runKeys) {
    $props = Get-ItemProperty $k
    if ($props) {
        foreach ($p in $props.PSObject.Properties) {
            if ($p.Name -match 'BingeMote' -or ($p.Value -is [string] -and $p.Value -match 'BingeMote')) {
                $anyRun = $true; Found ("registry auto-start: $($k) -> $($p.Name) = $($p.Value)")
            }
        }
    }
}
$startup = Get-ChildItem "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup" | Where-Object { $_.Name -match 'BingeMote' }
if ($startup) { $anyRun = $true; foreach ($f in $startup) { Found ("Startup folder: $($f.FullName)") } }
if (-not $anyRun) { Clean "no BingeMote auto-start entries" }

Section "5. Installed-program entries mentioning BingeMote"
$uninstall = @(
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
$apps = Get-ItemProperty $uninstall | Where-Object { $_.DisplayName -match 'BingeMote' }
if ($apps) { foreach ($a in $apps) { Found ("installed entry: $($a.DisplayName) $($a.DisplayVersion)") } }
else { Clean "no installed-program entries mention BingeMote" }

Section "6. What is using BingeMote's connection port (8488) right now"
$conns = Get-NetTCPConnection -LocalPort 8488 -State Listen
if ($conns) {
    foreach ($c in $conns) {
        $proc = Get-Process -Id $c.OwningProcess
        Found ("port 8488 listener: pid $($c.OwningProcess) ($($proc.ProcessName))  path=$($proc.Path)")
    }
} else { Clean "nothing is listening on port 8488 right now (normal if the helper is not running)" }

Section "7. Firewall rules mentioning BingeMote or port 8488"
$rules = Get-NetFirewallRule | Where-Object { $_.DisplayName -match 'BingeMote' }
if ($rules) { foreach ($r in $rules) { Found ("firewall rule: $($r.DisplayName)  $($r.Direction)/$($r.Action)  enabled=$($r.Enabled)") } }
$portRules = Get-NetFirewallPortFilter | Where-Object { $_.LocalPort -eq 8488 -or $_.LocalPort -eq '8488' }
if ($portRules) {
    foreach ($pf in $portRules) {
        $r = $pf | Get-NetFirewallRule
        if ($r -and $r.DisplayName -notmatch 'BingeMote') { Found ("firewall rule on port 8488: $($r.DisplayName)  $($r.Direction)/$($r.Action)  enabled=$($r.Enabled)") }
    }
}
if (-not $rules -and -not $portRules) { Clean "no firewall rules mention BingeMote or port 8488" }

Section "Summary"
if ($script:findings -eq 0) {
    Write-Host "  Nothing BingeMote-related found in any section. The old background pieces look gone."
} else {
    Write-Host "  $($script:findings) item(s) listed above still mention BingeMote."
    Write-Host "  This scan changes nothing. If something here looks wrong, send this report back."
}
Write-Host ""
Write-Host "Reminder: this is a snapshot of the items above at the time you ran it."
Write-Host "It is not a whole-PC health scan and cannot prove the PC is problem-free."
