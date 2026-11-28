# diag-locked-uninstall.ps1 - DIAGNOSTIC ONLY. No product behavior change; the
# product-side repair lives in helper/installer.iss and is reviewed separately.
# One bounded run, no retries: observation can EXPOSE a race or a false
# assumption, it can never PROVE absence of a race.
#
# Settles (or refutes) the exact runtime prerequisites I2 listed:
#   D-a  actual schtasks /Query /XML redirected bytes (encoding/BOM) and the
#        exported Principal UserId identity form (SID vs account name), using
#        the EXACT RegisterWatchdog XML structure on a SACRIFICIAL task name.
#   D-b  the updating-marker command line against a profile path containing
#        "!" (the delayed-expansion failure mode; script fixed in the product
#        delta under review).
#   D-c  bounded sacrificial lifecycle: whether a watchdog-triggered process
#        can materialize AFTER task delete + verified stop (exposure attempt
#        only; a clean observation does NOT prove the window is closed).
#   D-d  real Inno 6.x compile of helper/installer.iss (ISCC output captured).
#
# Every capture is streamed to stdout AND copied to $env:RUNNER_TEMP\diag-artifacts
# so an if:always() upload step retains it even on cancellation.
$ErrorActionPreference = 'Continue'  # diagnostics must continue past individual failures
$tmp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$out = Join-Path $tmp 'diag-artifacts'
New-Item -ItemType Directory -Force $out | Out-Null
function Cap($name, [scriptblock]$body) {
  "===== $name ====="
  try { & $body 2>&1 | Tee-Object -FilePath (Join-Path $out "$name.log") }
  catch { "CAPTURE ERROR: $_" | Tee-Object -FilePath (Join-Path $out "$name.log") -Append }
}

$diagTask = 'BingeMote Diag Watchdog'
$diagExe = Join-Path $env:LOCALAPPDATA 'Programs\BingeMote\BingeMote.exe'  # path form only; the task is deleted before any launch matters

Cap 'D-a1-task-register-export' {
  # EXACT RegisterWatchdog XML structure (Program.cs:739-757), sacrificial name.
  $xml = "<?xml version=""1.0"" encoding=""UTF-16""?>`n" +
    "<Task version=""1.2"" xmlns=""http://schemas.microsoft.com/windows/2004/02/mit/task"">`n" +
    "  <Triggers><TimeTrigger><StartBoundary>2020-01-01T00:00:00</StartBoundary>" +
    "<Repetition><Interval>PT5M</Interval></Repetition></TimeTrigger></Triggers>`n" +
    "  <Settings>" +
    "<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>" +
    "<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>" +
    "<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>" +
    "<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>" +
    "<StartWhenAvailable>true</StartWhenAvailable>" +
    "<Priority>4</Priority>" +
    "</Settings>`n" +
    "  <Actions><Exec><Command>" + [System.Security.SecurityElement]::Escape($diagExe) + "</Command>" +
    "<Arguments>--ensure-running</Arguments></Exec></Actions>`n" +
    "</Task>"
  $xf = Join-Path $out 'diag-watchdog.xml'
  [IO.File]::WriteAllText($xf, $xml, [Text.Encoding]::Unicode)
  schtasks /Create /F /TN "$diagTask" /XML "$xf"
  "create rc=$LASTEXITCODE"
  # Query exactly as the installer Code does: cmd redirect to a file.
  $dump = Join-Path $out 'task-query-redirected.bin'
  cmd /c "schtasks /Query /TN `"$diagTask`" /XML > `"$dump`""
  "query rc=$LASTEXITCODE"
  $bytes = [IO.File]::ReadAllBytes($dump)
  "first 16 bytes: " + (($bytes[0..15] | ForEach-Object { $_.ToString('X2') }) -join ' ')
  "total bytes: $($bytes.Length)"
  # Parse with the same MSXML contract the installer uses.
  $doc = New-Object -ComObject 'Msxml2.DOMDocument.6.0'
  $doc.async = $false
  $text = [Text.Encoding]::Unicode.GetString($bytes)
  "loadXML: " + $doc.loadXML($text)
  $uids = $doc.selectNodes('//*[local-name()="Principals"]/*[local-name()="Principal"]/*[local-name()="UserId"]')
  "principal UserId nodes: $($uids.length)"
  foreach ($u in $uids) { "principal UserId raw: [$($u.text)]" }
  $execs = $doc.selectNodes('//*[local-name()="Actions"]/*[local-name()="Exec"]')
  "exec nodes: $($execs.length)"
  "whoami /user /fo csv /nh:"
  cmd /c 'whoami /user /fo csv /nh'
  schtasks /Delete /TN "$diagTask" /F
  "delete rc=$LASTEXITCODE"
}

Cap 'D-b-marker-bang-path' {
  # The fixed installer command line (must mirror helper/installer.iss BMWriteUpdatingMarker).
  $bang = Join-Path $tmp 'we!rd-profile'
  $envBackup = $env:LOCALAPPDATA
  $env:LOCALAPPDATA = $bang
  $cmdline = '/c (if exist "%LOCALAPPDATA%\BingeMote" (> "%LOCALAPPDATA%\BingeMote\updating" echo updating) ' +
    'else (if exist "%LOCALAPPDATA%\BingeMoteHelper" (> "%LOCALAPPDATA%\BingeMoteHelper\updating" echo updating) ' +
    'else (mkdir "%LOCALAPPDATA%\BingeMote" & > "%LOCALAPPDATA%\BingeMote\updating" echo updating)))'
  cmd /c $cmdline
  "marker rc=$LASTEXITCODE ; expected at: $bang\BingeMote\updating"
  "exists: " + (Test-Path "$bang\BingeMote\updating")
  $env:LOCALAPPDATA = $envBackup
}

Cap 'D-c-sacrificial-lifecycle' {
  # Exposure attempt only: can a task-triggered process materialize AFTER the
  # registration is deleted and a verified stop pass ran? A clean observation
  # does NOT prove the window is closed.
  $sleeper = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
  $xml = "<?xml version=""1.0"" encoding=""UTF-16""?><Task version=""1.2"" xmlns=""http://schemas.microsoft.com/windows/2004/02/mit/task"">" +
    "<Triggers><TimeTrigger><StartBoundary>2020-01-01T00:00:00</StartBoundary><Repetition><Interval>PT1M</Interval></Repetition></TimeTrigger></Triggers>" +
    "<Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><StartWhenAvailable>true</StartWhenAvailable><ExecutionTimeLimit>PT0S</ExecutionTimeLimit></Settings>" +
    "<Actions><Exec><Command>$sleeper</Command><Arguments>-NoProfile -Command ""Start-Sleep 30""</Arguments></Exec></Actions></Task>"
  $xf = Join-Path $out 'diag-lifecycle.xml'
  [IO.File]::WriteAllText($xf, $xml, [Text.Encoding]::Unicode)
  1..3 | ForEach-Object {
    schtasks /Create /F /TN "$diagTask" /XML "$xf" | Out-Null
    schtasks /Run /TN "$diagTask" | Out-Null
    schtasks /Delete /TN "$diagTask" /F | Out-Null   # delete registration immediately
    Start-Sleep -Milliseconds 2500                    # settle window (bounded)
    $alive = Get-Process -Name powershell -ErrorAction SilentlyContinue |
      Where-Object { $_.StartTime -gt (Get-Date).AddSeconds(-15) }
    "iteration ${_}: post-delete+settle powershell children observed: $($alive.Count)"
    $alive | Stop-Process -Force -ErrorAction SilentlyContinue
  }
  "D-c done (exposure attempt; absence here is NOT proof of closure)"
}

Cap 'D-d-inno-compile' {
  $iscc = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'
  "iscc present: " + (Test-Path $iscc)
  & $iscc '/DMyAppVersion=9.9.9-diag' 'helper/installer.iss' 2>&1
  "iscc rc=$LASTEXITCODE"
}
