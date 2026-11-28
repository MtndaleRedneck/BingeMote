# diag-locked-uninstall.ps1 - DIAGNOSTIC ONLY. No product behavior change; the
# product-side repair lives in helper/installer.iss and is reviewed separately.
# One bounded run, no retries: observation can EXPOSE a race or a false
# assumption, it can never PROVE absence of a race.
#
# Captures (stdout AND $env:RUNNER_TEMP\diag-artifacts; upload step is
# if:always() but a cancelled runner may still fail to upload - stdout is the
# cancellation-safe channel):
#   D-a  actual schtasks /Query /XML redirected bytes: BOM detection EXPLICIT
#        (FF FE / FE FF / EF BB BF / none reported, no UTF-16 assumption), the
#        exported Principal UserId identity form (SID vs account name), and
#        whoami CSV bytes - via the exact RegisterWatchdog XML structure on a
#        SACRIFICIAL task with a HARMLESS action (cmd /c exit 0), cleaned up in
#        try/finally.
#   D-b  the updating-marker command line against a profile path containing
#        "!" (the delayed-expansion failure mode; fixed in the product delta).
#   D-c  bounded sacrificial lifecycle EXPOSURE attempt: unique-token command
#        line, identity re-verified on the held process match at stop time, no
#        unrelated processes touched; a clean observation is NOT proof.
#   D-d  real ISCC compile of helper/installer.iss with a staged placeholder
#        exe so a missing build output cannot abort before script validation.
$ErrorActionPreference = 'Continue'
$tmp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$out = Join-Path $tmp 'diag-artifacts'
New-Item -ItemType Directory -Force $out | Out-Null
function Cap($name, [scriptblock]$body) {
  "===== $name ====="
  try { & $body 2>&1 | Tee-Object -FilePath (Join-Path $out "$name.log") }
  catch { "CAPTURE ERROR: $_" | Tee-Object -FilePath (Join-Path $out "$name.log") -Append }
}

$diagTask = 'BingeMote Diag Watchdog'

Cap 'D-a1-task-register-export' {
  # RegisterWatchdog XML STRUCTURE (Program.cs:739-757), sacrificial name and a
  # harmless action - never the real product path, never StartWhenAvailable
  # with something that must not run. try/finally guarantees cleanup.
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
    "  <Actions><Exec><Command>cmd.exe</Command><Arguments>/c exit 0</Arguments></Exec></Actions>`n" +
    "</Task>"
  $xf = Join-Path $out 'diag-register.xml'
  [IO.File]::WriteAllText($xf, $xml, [Text.Encoding]::Unicode)
  try {
    schtasks /Create /F /TN "$diagTask" /XML "$xf"
    "create rc=$LASTEXITCODE"
    $dump = Join-Path $out 'task-query-redirected.bin'
    cmd /c "schtasks /Query /TN `"$diagTask`" /XML > `"$dump`""
    "query rc=$LASTEXITCODE"
    $bytes = [IO.File]::ReadAllBytes($dump)
    "first 8 bytes: " + (($bytes[0..7] | ForEach-Object { $_.ToString('X2') }) -join ' ')
    $bom = if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { 'UTF-8 BOM' }
           elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) { 'UTF-16LE BOM' }
           elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF) { 'UTF-16BE BOM' }
           else { 'NO BOM' }
    "explicit BOM detection: $bom ; total bytes: $($bytes.Length)"
    $asUtf16 = [Text.Encoding]::Unicode.GetString($bytes)
    $asUtf8  = [Text.Encoding]::UTF8.GetString($bytes)
    foreach ($pair in @(@('UTF-16LE', $asUtf16), @('UTF-8', $asUtf8))) {
      $doc = New-Object -ComObject 'Msxml2.DOMDocument.6.0'
      $doc.async = $false
      $okLoad = $doc.loadXML($pair[1])
      "loadXML as $($pair[0]): $okLoad"
      if ($okLoad) {
        $uids = $doc.selectNodes('//*[local-name()="Principals"]/*[local-name()="Principal"]/*[local-name()="UserId"]')
        "  principal UserId chain nodes: $($uids.length)"
        foreach ($u in $uids) { "  principal UserId raw: [$($u.text)]" }
        $execs = $doc.selectNodes('//*[local-name()="Actions"]/*[local-name()="Exec"]')
        "  exec nodes: $($execs.length)"
      }
    }
    "whoami /user /fo csv /nh bytes:"
    $w = Join-Path $out 'whoami.bin'
    cmd /c "whoami /user /fo csv /nh > `"$w`""
    $wb = [IO.File]::ReadAllBytes($w)
    "whoami first 8 bytes: " + (($wb[0..7] | ForEach-Object { $_.ToString('X2') }) -join ' ')
    [Text.Encoding]::Default.GetString($wb)
  } finally {
    schtasks /Delete /TN "$diagTask" /F | Out-Null
    "cleanup delete rc=$LASTEXITCODE"
  }
}

Cap 'D-b-marker-bang-path' {
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
  # Exposure attempt only. Unique token in the command line; identity is
  # RE-VERIFIED on the matched process immediately before any stop - no broad
  # process-name kills, no PID-reuse-dependent action, no unrelated processes.
  $token = 'diagtoken-' + [guid]::NewGuid().ToString('N')
  $sleeper = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
  $xml = "<?xml version=""1.0"" encoding=""UTF-16""?><Task version=""1.2"" xmlns=""http://schemas.microsoft.com/windows/2004/02/mit/task"">" +
    "<Triggers><TimeTrigger><StartBoundary>2020-01-01T00:00:00</StartBoundary><Repetition><Interval>PT1M</Interval></Repetition></TimeTrigger></Triggers>" +
    "<Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><StartWhenAvailable>true</StartWhenAvailable><ExecutionTimeLimit>PT0S</ExecutionTimeLimit></Settings>" +
    "<Actions><Exec><Command>$sleeper</Command><Arguments>-NoProfile -Command ""Start-Sleep 20 # $token""</Arguments></Exec></Actions></Task>"
  $xf = Join-Path $out 'diag-lifecycle.xml'
  [IO.File]::WriteAllText($xf, $xml, [Text.Encoding]::Unicode)
  try {
    1..3 | ForEach-Object {
      schtasks /Create /F /TN "$diagTask" /XML "$xf" | Out-Null
      schtasks /Run /TN "$diagTask" | Out-Null
      schtasks /Delete /TN "$diagTask" /F | Out-Null
      Start-Sleep -Milliseconds 2500
      $hits = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($token) }
      "iteration ${_}: token-matched processes after delete+settle: $($hits.Count)"
      foreach ($p in $hits) {
        # re-verify identity at stop time; only then terminate that exact pid
        $still = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.ProcessId)" -ErrorAction SilentlyContinue
        if ($still -and $still.CommandLine -and $still.CommandLine.Contains($token)) {
          Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
          "  stopped token-verified pid $($p.ProcessId)"
        }
      }
    }
  } finally {
    schtasks /Delete /TN "$diagTask" /F | Out-Null
  }
  "D-c done (exposure attempt; absence here is NOT proof of closure)"
}

Cap 'D-d-inno-compile' {
  $iscc = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'
  "iscc present: " + (Test-Path $iscc)
  # Stage a placeholder so a missing build artifact cannot abort script validation.
  if (-not (Test-Path 'helper\out\BingeMote.exe')) {
    New-Item -ItemType Directory -Force 'helper\out' | Out-Null
    [IO.File]::WriteAllBytes('helper\out\BingeMote.exe', [byte[]](0x4D, 0x5A))
    "staged placeholder helper\out\BingeMote.exe (compile-validation only)"
  }
  & $iscc '/DMyAppVersion=9.9.9-diag' 'helper/installer.iss' 2>&1
  "iscc rc=$LASTEXITCODE"
}
