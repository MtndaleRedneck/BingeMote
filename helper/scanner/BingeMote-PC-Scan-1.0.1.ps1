# BingeMote PC Scan 1.0.1
# One-time, READ-ONLY leftover check for BingeMote on this PC.
# It changes nothing: no deletes, no settings, no fixes. It only looks and reports.
#
# Easiest way to run it: double-click BingeMote-PC-Scan-1.0.1.cmd (sits next to this
# file) - the window stays open when the report finishes.
# Or from a terminal:  powershell -NoProfile -ExecutionPolicy Bypass -File .\BingeMote-PC-Scan-1.0.1.ps1
#
# What it CAN tell you: which BingeMote-related tasks, services, files, auto-start
# entries, firewall rules, URL reservations, and port listeners exist right now.
# What it CANNOT tell you: that the whole PC is problem-free. It is a snapshot of
# the BingeMote items below, not a general PC health scan. Every section says at the
# end whether it could actually be read; an unreadable section means the summary is
# INCOMPLETE, never a silent all-clear.

$scanVersion = '1.0.1'
$script:findings = 0
$script:sectionsFailed = @()

function Write-Section($title) {
    Write-Host ''
    Write-Host ('=' * 70)
    Write-Host $title
    Write-Host ('=' * 70)
}
function Found($line) { $script:findings++; Write-Host "  FOUND: $line" }
function CleanLine($line) { Write-Host "  none:  $line" }
function Section-Result($name, [bool]$ok, [string]$err) {
    if ($ok) { Write-Host "  [read complete] $name" }
    else {
        $script:sectionsFailed += $name
        Write-Host "  [COULD NOT READ] ${name}: $err"
    }
}

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
Write-Host "BingeMote PC Scan $scanVersion - read-only report, $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
if (-not $isAdmin) {
    Write-Host 'NOTE: not running as Administrator. Tasks/services owned by SYSTEM may be hidden from this scan.'
}

Write-Section "1. Scheduled tasks with 'BingeMote' in the name or path"
try {
    $tasks = @(Get-ScheduledTask -ErrorAction Stop | Where-Object { $_.TaskName -match 'BingeMote' -or $_.TaskPath -match 'BingeMote' })
    if ($tasks.Count -eq 0) { CleanLine 'no scheduled tasks mention BingeMote' }
    foreach ($t in $tasks) {
        $actions = ($t.Actions | ForEach-Object { ($_.Execute + ' ' + $_.Arguments).Trim() }) -join ' ; '
        Found ("task: $($t.TaskPath)$($t.TaskName)  state=$($t.State)  runs: $actions")
    }
    Section-Result 'Scheduled tasks' $true ''
} catch { Section-Result 'Scheduled tasks' $false $_.Exception.Message }

Write-Section "2. Services with 'BingeMote' in the name or display name"
try {
    $svcs = @(Get-CimInstance Win32_Service -ErrorAction Stop | Where-Object { $_.Name -match 'BingeMote' -or $_.DisplayName -match 'BingeMote' })
    if ($svcs.Count -eq 0) { CleanLine 'no services mention BingeMote' }
    foreach ($s in $svcs) { Found ("service: $($s.Name)  state=$($s.State)  path=$($s.PathName)") }
    Section-Result 'Services' $true ''
} catch { Section-Result 'Services' $false $_.Exception.Message }

Write-Section '3. BingeMote folders and executables in the usual install spots'
try {
    $dirs = @(
        "$env:ProgramFiles\BingeMote",
        "${env:ProgramFiles(x86)}\BingeMote",
        "$env:LOCALAPPDATA\BingeMote",
        "$env:LOCALAPPDATA\Programs\BingeMote",
        "$env:APPDATA\BingeMote",
        "$env:ProgramData\BingeMote"
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($dirs.Count -eq 0) { CleanLine 'no BingeMote folders in the usual install spots' }
    foreach ($d in $dirs) {
        $files = @(Get-ChildItem $d -Recurse -Include *.exe, *.ps1, *.bat -File -ErrorAction Stop)
        if ($files.Count -eq 0) { Found ("folder exists: $d  (no executables/scripts inside)") }
        foreach ($x in $files) {
            $ver = $x.VersionInfo.FileVersion
            if ([string]::IsNullOrWhiteSpace($ver)) { $ver = '(no version info)' }
            Found ("file: $($x.FullName)  version=$ver")
        }
    }
    Section-Result 'Files' $true ''
} catch { Section-Result 'Files' $false $_.Exception.Message }

Write-Section '4. Auto-start entries (registry Run keys and Startup folder)'
try {
    $anyRun = $false
    foreach ($k in @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Run',
                     'HKLM:\Software\Microsoft\Windows\CurrentVersion\Run')) {
        $props = Get-ItemProperty $k -ErrorAction Stop
        if ($props) {
            foreach ($p in $props.PSObject.Properties) {
                if ($p.Name -match 'BingeMote' -or ($p.Value -is [string] -and $p.Value -match 'BingeMote')) {
                    $anyRun = $true; Found ("registry auto-start: $k -> $($p.Name) = $($p.Value)")
                }
            }
        }
    }
    $startup = @(Get-ChildItem "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup" -ErrorAction Stop | Where-Object { $_.Name -match 'BingeMote' })
    foreach ($f in $startup) { $anyRun = $true; Found ("Startup folder: $($f.FullName)") }
    if (-not $anyRun) { CleanLine 'no BingeMote auto-start entries' }
    Section-Result 'Auto-start' $true ''
} catch { Section-Result 'Auto-start' $false $_.Exception.Message }

Write-Section '5. Installed-program entries mentioning BingeMote'
try {
    $apps = @(Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
                             'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
                             'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction Stop |
              Where-Object { $_.DisplayName -match 'BingeMote' })
    if ($apps.Count -eq 0) { CleanLine 'no installed-program entries mention BingeMote' }
    foreach ($a in $apps) { Found ("installed entry: $($a.DisplayName) $($a.DisplayVersion)") }
    Section-Result 'Installed programs' $true ''
} catch { Section-Result 'Installed programs' $false $_.Exception.Message }

Write-Section "6. What is using BingeMote's connection port (8488) right now"
try {
    $conns = @(Get-NetTCPConnection -LocalPort 8488 -State Listen -ErrorAction Stop)
    if ($conns.Count -eq 0) { CleanLine 'nothing is listening on port 8488 right now (normal if the helper is not running)' }
    foreach ($c in $conns) {
        $proc = Get-Process -Id $c.OwningProcess -ErrorAction Stop
        Found ("port 8488 listener: pid $($c.OwningProcess) ($($proc.ProcessName))  path=$($proc.Path)")
    }
    Section-Result 'Port 8488 listeners' $true ''
} catch {
    if ($_.Exception.Message -match 'No MSFT_NetTCPConnection objects found') {
        CleanLine 'nothing is listening on port 8488 right now (normal if the helper is not running)'
        Section-Result 'Port 8488 listeners' $true ''
    } else { Section-Result 'Port 8488 listeners' $false $_.Exception.Message }
}

Write-Section '7. URL reservations (URLACL) for port 8488'
try {
    $out = netsh http show urlacl 2>&1
    if ($LASTEXITCODE -ne 0) { throw "netsh exited with code $LASTEXITCODE" }
    $hits = @($out | Where-Object { $_ -match '8488' })
    if ($hits.Count -eq 0) { CleanLine 'no URL reservations on port 8488' }
    foreach ($l in $hits) { Found ("urlacl: $($l.Trim())") }
    Section-Result 'URL reservations' $true ''
} catch { Section-Result 'URL reservations' $false $_.Exception.Message }

Write-Section '8. Firewall rules mentioning BingeMote or port 8488'
try {
    $anyRule = $false
    $rules = @(Get-NetFirewallRule -ErrorAction Stop | Where-Object { $_.DisplayName -match 'BingeMote' })
    foreach ($r in $rules) { $anyRule = $true; Found ("firewall rule: $($r.DisplayName)  $($r.Direction)/$($r.Action)  enabled=$($r.Enabled)") }
    $portFilters = @(Get-NetFirewallPortFilter -ErrorAction Stop | Where-Object { $_.LocalPort -eq 8488 -or $_.LocalPort -eq '8488' })
    foreach ($pf in $portFilters) {
        $r = $pf | Get-NetFirewallRule -ErrorAction Stop
        if ($r -and $r.DisplayName -notmatch 'BingeMote') { $anyRule = $true; Found ("firewall rule on port 8488: $($r.DisplayName)  $($r.Direction)/$($r.Action)  enabled=$($r.Enabled)") }
    }
    if (-not $anyRule) { CleanLine 'no firewall rules mention BingeMote or port 8488' }
    Section-Result 'Firewall rules' $true ''
} catch { Section-Result 'Firewall rules' $false $_.Exception.Message }

Write-Section 'Summary'
if ($script:sectionsFailed.Count -gt 0) {
    Write-Host "  INCOMPLETE: $($script:sectionsFailed.Count) section(s) could not be read: $($script:sectionsFailed -join ', ')"
    Write-Host '  Re-run as Administrator to read them. Until then this report is NOT an all-clear.'
}
if ($script:findings -eq 0 -and $script:sectionsFailed.Count -eq 0) {
    Write-Host '  Nothing BingeMote-related found in any section. The old background pieces look gone.'
} elseif ($script:findings -gt 0) {
    Write-Host "  $($script:findings) item(s) listed above still mention BingeMote."
    Write-Host '  This scan changes nothing. If something here looks wrong, send this report back.'
}
Write-Host ''
Write-Host 'Reminder: this is a snapshot of the items above at the time you ran it.'
Write-Host 'It is not a whole-PC health scan and cannot prove the PC is problem-free.'
