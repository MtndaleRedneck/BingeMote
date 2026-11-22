# CI test for BingeMote-PC-Scan-1.0.1.ps1: clean run, positive-detection fixtures,
# mocked section failure, and the keep-open .cmd launcher.
$ErrorActionPreference = 'Stop'
$script = 'helper/scanner/BingeMote-PC-Scan-1.0.1.ps1'
$script:fail = 0
function Assert($cond, $msg) {
    if ($cond) { Write-Host "ok: $msg" } else { Write-Host "TEST FAIL: $msg"; $script:fail++ }
}

# --- Test 1: clean run completes and claims all-clear only when readable ---
pwsh -NoProfile -File $script | Out-File clean-report.txt -Encoding utf8
$clean = Get-Content clean-report.txt -Raw
Assert ($clean -match 'look gone') 'clean run reaches all-clear summary'
Assert ($clean -notmatch 'COULD NOT READ') 'clean run has no unreadable sections'
Assert ($clean -notmatch 'FOUND:') 'clean run finds nothing on a fresh runner'

# --- Test 2: fixtures must be detected (positive path) ---
$act = New-ScheduledTaskAction -Execute 'C:\Windows\System32\notepad.exe' -Argument '-fixture'
Register-ScheduledTask -TaskName 'BingeMoteFixtureScanTest' -Action $act -Force | Out-Null
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\BingeMote" | Out-Null
Copy-Item C:\Windows\System32\notepad.exe "$env:LOCALAPPDATA\BingeMote\BingeMoteFixture.exe"
New-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'BingeMoteFixture' -Value 'C:\fixture\BingeMote.exe' -Force | Out-Null
New-NetFirewallRule -DisplayName 'BingeMoteFixtureRule' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8488 | Out-Null
netsh http add urlacl 'url=http://+:8488/bingemotefixture' user=Everyone | Out-Null
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 8488)
$listener.Start()

pwsh -NoProfile -File $script | Out-File fixture-report.txt -Encoding utf8
$fx = Get-Content fixture-report.txt -Raw
Assert ($fx -match 'BingeMoteFixtureScanTest') 'fixture task found'
Assert ($fx -match 'notepad\.exe -fixture') 'fixture task action exe+args shown'
Assert ($fx -match 'BingeMoteFixture\.exe') 'fixture exe found'
Assert ($fx -match 'version=\d') 'exe file version shown'
Assert ($fx -match 'BingeMoteFixture') 'fixture Run-key entry found'
Assert ($fx -match 'BingeMoteFixtureRule') 'fixture firewall rule found'
Assert ($fx -match 'bingemotefixture') 'fixture URLACL reservation found'
Assert ($fx -match '8488 listener') 'port 8488 listener found with process'
Assert ($fx -match 'item\(s\) listed above') 'fixture run does not claim all-clear'
Assert ($fx -notmatch 'COULD NOT READ') 'fixture run still fully readable'

$listener.Stop()
Unregister-ScheduledTask -TaskName 'BingeMoteFixtureScanTest' -Confirm:$false
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\BingeMote"
Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'BingeMoteFixture'
Remove-NetFirewallRule -DisplayName 'BingeMoteFixtureRule'
netsh http delete urlacl 'url=http://+:8488/bingemotefixture' | Out-Null

# --- Test 3: a denied/failed section must kill the all-clear ---
$mock = @'
function Get-ScheduledTask { throw "access denied (mock)" }
. .\helper\scanner\BingeMote-PC-Scan-1.0.1.ps1
'@
[System.IO.File]::WriteAllText("$PWD\mock-run.ps1", $mock)
pwsh -NoProfile -File mock-run.ps1 | Out-File mock-report.txt -Encoding utf8
$mk = Get-Content mock-report.txt -Raw
Assert ($mk -match 'COULD NOT READ') 'mocked task-section failure is reported as unreadable'
Assert ($mk -match 'INCOMPLETE') 'summary says INCOMPLETE on section failure'
Assert ($mk -notmatch 'look gone') 'no all-clear when a section failed'

# --- Test 4: the keep-open launcher actually runs the delivered script ---
cmd /c "helper\scanner\BingeMote-PC-Scan-1.0.1.cmd < NUL" | Out-File launcher-report.txt -Encoding utf8
$ln = Get-Content launcher-report.txt -Raw
Assert ($ln -match 'BingeMote PC Scan 1\.0\.1') 'launcher .cmd runs the delivered script and keeps open (pause consumed)'

foreach ($r in 'clean-report.txt','fixture-report.txt','mock-report.txt','launcher-report.txt') {
    if (Test-Path $r) { Write-Host "===== $r ====="; Get-Content $r | Write-Host }
}
if ($script:fail -gt 0) { throw "$($script:fail) scanner test assertion(s) failed" }
Write-Host 'ALL SCANNER TESTS PASSED'
