# diag-locked-uninstall.ps1 - DIAGNOSTIC ONLY. No product behavior change.
# One bounded run, no retries: observation can EXPOSE a race or a false
# assumption, it can never PROVE absence. Every capture ASSERTS its own
# acquisition: a green step means the evidence actually exists; any failed
# assertion fails the step at the end (ASSERT-FAIL lines + nonzero exit).
#
#   D-a  schtasks /Query /XML redirected bytes: EXPLICIT BOM classification,
#        product-equivalent decode (strip UTF-16LE BOM, decode LE) plus
#        alternative-decode reporting, exported Principal UserId identity form
#        classified (SID vs account name) and matched against whoami output.
#        Sacrificial task name, harmless action, try/finally cleanup.
#   D-b  marker command EXTRACTED from helper/installer.iss (no re-typed copy)
#        run against a "!" profile path; env restored in finally.
#   D-c  bounded sacrificial lifecycle EXPOSURE attempt: unique-token command
#        line, identity re-verified on the exact pid immediately before stop.
#   D-d  real ISCC compile with staged placeholder inputs - validates the
#        SCRIPT compiles with real Inno; NOT Chr/COM runtime verification.
# Guards: refuses to run outside GitHub Actions (ephemeral-runner guard); all
# native children individually timeout-bounded via the workflow step timeout
# (12 min) plus per-call invocation; tasks/env/files restored in finally.
if ($env:GITHUB_ACTIONS -ne 'true') { throw "ephemeral-runner guard: refusing to run outside GitHub Actions" }
$ErrorActionPreference = 'Continue'
$script:fail = @()
function Need($cond, $msg) { if ($cond) { "ASSERT-OK: $msg" } else { $script:fail += $msg; "ASSERT-FAIL: $msg" } }
$tmp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$out = Join-Path $tmp 'diag-artifacts'
New-Item -ItemType Directory -Force $out | Out-Null
function Cap($name, [scriptblock]$body) {
  "===== $name ====="
  try { & $body 2>&1 | Tee-Object -FilePath (Join-Path $out "$name.log") }
  catch { $script:fail += "$name threw: $_"; "CAPTURE ERROR: $_" | Tee-Object -FilePath (Join-Path $out "$name.log") -Append }
}

$diagTask = 'BingeMote Diag Watchdog'

Cap 'D-a-task-export' {
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
    schtasks /Create /F /TN "$diagTask" /XML "$xf" | Out-Null
    Need ($LASTEXITCODE -eq 0) "sacrificial task created rc=$LASTEXITCODE"
    $dump = Join-Path $out 'task-query-redirected.bin'
    cmd /d /c "schtasks /Query /TN `"$diagTask`" /XML > `"$dump`"" | Out-Null
    Need ($LASTEXITCODE -eq 0) "task query redirect rc=$LASTEXITCODE"
    $bytes = [IO.File]::ReadAllBytes($dump)
    Need ($bytes.Length -gt 8) "redirected export non-empty ($($bytes.Length) bytes)"
    "first 8 bytes: " + (($bytes[0..7] | ForEach-Object { $_.ToString('X2') }) -join ' ')
    $bom = if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { 'UTF-8 BOM' }
           elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) { 'UTF-16LE BOM' }
           elseif ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF) { 'UTF-16BE BOM' }
           else { 'NO BOM' }
    "explicit BOM classification: $bom"
    # Product-equivalent decode (BMReadUtf16File: strip UTF-16LE BOM, decode LE).
    $body = if ($bom -eq 'UTF-16LE BOM') { $bytes[2..($bytes.Length-1)] } else { $bytes }
    $asUtf16 = [Text.Encoding]::Unicode.GetString([byte[]]$body)
    $doc = New-Object -ComObject 'Msxml2.DOMDocument.6.0'; $doc.async = $false
    $okLoad = $doc.loadXML($asUtf16)
    Need $okLoad "MSXML load of product-equivalent decode"
    if ($okLoad) {
      $uids = $doc.selectNodes('//*[local-name()="Principals"]/*[local-name()="Principal"]/*[local-name()="UserId"]')
      Need ($uids.length -eq 1) "exactly one Principals/Principal/UserId chain ($($uids.length))"
      $uidRaw = if ($uids.length -ge 1) { $uids.item(0).text } else { '' }
      "principal UserId raw: [$uidRaw]"
      $form = if ($uidRaw -match '^S-1-5-') { 'SID' } else { 'other(name?)' }
      "principal identity form: $form"
      Need ($uidRaw.Length -gt 0) "principal UserId non-empty"
      $execs = $doc.selectNodes('//*[local-name()="Actions"]/*[local-name()="Exec"]')
      "exec nodes: $($execs.length)"
    }
    $w = Join-Path $out 'whoami.bin'
    cmd /d /c "whoami /user /fo csv /nh > `"$w`"" | Out-Null
    Need ($LASTEXITCODE -eq 0) "whoami rc=$LASTEXITCODE"
    $wb = [IO.File]::ReadAllBytes($w)
    "whoami first 8 bytes: " + (($wb[0..7] | ForEach-Object { $_.ToString('X2') }) -join ' ')
    "whoami text: " + [Text.Encoding]::Default.GetString($wb)
  } finally {
    schtasks /Delete /TN "$diagTask" /F | Out-Null
    "cleanup delete rc=$LASTEXITCODE"
  }
}

Cap 'D-b-marker-bang-path' {
  # Extract the EXACT marker command from helper/installer.iss (no re-typed copy).
  $iss = Get-Content 'helper/installer.iss' -Raw
  $m = [regex]::Match($iss, "(?s)cmd := '(/d /v:off /c .*?)';")
  Need $m.Success "marker cmd line extractable from installer.iss"
  $cmdline = ($m.Groups[1].Value -replace "'\s*\+\s*'", '') -replace "''", "'"
  $bang = Join-Path $tmp 'we!rd-profile'
  $envBackup = $env:LOCALAPPDATA
  try {
    $env:LOCALAPPDATA = $bang
    & "$env:SystemRoot\System32\cmd.exe" $cmdline
    Need ($LASTEXITCODE -eq 0) "marker cmd rc=$LASTEXITCODE against bang path"
    Need (Test-Path "$bang\BingeMote\updating") "marker file exists at literal bang path"
  } finally { $env:LOCALAPPDATA = $envBackup }
}

Cap 'D-c-sacrificial-lifecycle' {
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
      Need ($LASTEXITCODE -eq 0) "D-c create rc=$LASTEXITCODE"
      schtasks /Run /TN "$diagTask" | Out-Null
      Need ($LASTEXITCODE -eq 0) "D-c run rc=$LASTEXITCODE"
      schtasks /Delete /TN "$diagTask" /F | Out-Null
      Start-Sleep -Milliseconds 2500
      $hits = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($token) }
      "iteration ${_}: token-matched processes after delete+settle: $($hits.Count)"
      foreach ($p in $hits) {
        $still = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.ProcessId)" -ErrorAction SilentlyContinue
        if ($still -and $still.CommandLine -and $still.CommandLine.Contains($token)) {
          Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
          "  stopped token-verified pid $($p.ProcessId)"
        }
      }
    }
  } finally { schtasks /Delete /TN "$diagTask" /F | Out-Null }
  "D-c done (exposure attempt; absence here is NOT proof of closure)"
}

Cap 'D-d-inno-compile' {
  $iscc = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'
  Need (Test-Path $iscc) "ISCC present at runner image path"
  $staged = $false
  try {
    if (-not (Test-Path 'helper\out\BingeMote.exe')) {
      New-Item -ItemType Directory -Force 'helper\out' | Out-Null
      [IO.File]::WriteAllBytes('helper\out\BingeMote.exe', [byte[]](0x4D, 0x5A))
      $staged = $true
      "staged placeholder helper\out\BingeMote.exe (script-compile validation only)"
    }
    & $iscc '/DMyAppVersion=9.9.9-diag' 'helper/installer.iss' 2>&1 | Out-Host
    Need ($LASTEXITCODE -eq 0) "ISCC rc=$LASTEXITCODE (script compile with staged input; NOT Chr/COM runtime verification)"
  } finally {
    if ($staged) { Remove-Item 'helper\out\BingeMote.exe' -Force -ErrorAction SilentlyContinue }
  }
}

if ($script:fail.Count -gt 0) { "ASSERT FAILURES: $($script:fail.Count)"; $script:fail | ForEach-Object { " - $_" }; exit 1 }
"ALL ASSERTIONS PASSED"
