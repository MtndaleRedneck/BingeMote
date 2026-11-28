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
#        Unique SPACELESS task name and whitespace-asserted redirect paths:
#        raw cmd args carry NO quotes (no argv-splitting prediction).
#   D-b  marker command EXTRACTED from helper/installer.iss (no re-typed copy)
#        and invoked with the raw Params string via ProcessStartInfo
#        (UseShellExecute=false), matching how Inno passes Params; env restored
#        in finally.
#   D-c  bounded sacrificial lifecycle EXPOSURE attempt: unique-token command
#        line, POSITIVE owned-launch control before each delete (zero matches
#        without an acquisition-verified launch observation is recorded as NO
#        EVIDENCE, not closure), task absence checked with not-found
#        discrimination, identity re-verified on the exact pid immediately
#        before stop, bounded post-kill reap.
#   D-d  real ISCC compile with staged placeholder inputs - validates the
#        SCRIPT compiles with real Inno on the hosted image; NOT Chr/COM/
#        original-user runtime verification.
# Scope honesty: D-a/D-d evidence is HOSTED-IMAGE observation only (export
# bytes/BOM/product-equivalent decode/runner principal form). It is NOT
# evidence about the actual Inno Pascal reader, Chr() semantics, in-process
# COM behavior, or original-user contexts; the principal comparison is a FORM
# observation and does not widen product acceptance. COM calls execute
# in-process and cannot be bounded per-call - the 12-min step timeout is the
# only outer bound for them. Invoke-BoundedNative bounds the DIRECT child
# process only; descendants a child spawns are not tracked or killed (the
# sacrificial action spawns none). whoami CSV is decoded with
# [Text.Encoding]::Default (ANSI codepage under Windows PowerShell 5.1, UTF-8
# under pwsh): non-ASCII account NAME matching could be lossy (a lossy NAME
# miss yields NO-MATCH and fails closed, never a false match); comparison is
# ordinal case-insensitive (-ieq); SID comparison is ASCII-safe and
# unaffected.

# Global diagnostic ADMISSION checkpoint: no NEW capture starts after the
# 10-min mark, so per-call worst cases (~818s) no longer sum against the
# 720s step. It is NOT a total-capture cap and reserves no guaranteed
# cleanup window: a call admitted just before the checkpoint can still run
# its own bounded budget (timeout + 5s reap + 2x5s drain) past the step
# timeout. Skipped captures / early-stopped D-c iterations are assertion
# failures, never silent passes.
# Artifact scope: captures the diagnostic RUNNER account identity only
# (whoami name/SID of the ephemeral runner, never user data); only the
# minimal export (task XML bytes, whoami CSV, step logs) is uploaded. CIM/WMI
# calls are in-process like COM: not per-call bounded; the step timeout is
# the outer bound. Stop-Process termination is best-effort; every kill is
# followed by a bounded 5s reap check.
# Guards: refuses to run outside GitHub Actions AND outside the proof branch
# ref (workflow job is likewise ref-gated); every native child goes through
# Invoke-BoundedNative with an explicit per-call wait+kill bound (not merely
# the workflow step timeout); tasks/env/files restored in finally.
if ($env:GITHUB_ACTIONS -ne 'true') { throw "ephemeral-runner guard: refusing to run outside GitHub Actions" }
if ($env:GITHUB_REF -ne 'refs/heads/tmp/iss-supersession-proof') { throw "ref guard: diagnostic bound to refs/heads/tmp/iss-supersession-proof" }
$ErrorActionPreference = 'Continue'
$script:fail = @()
$script:callSeq = 0  # monotonic per-invocation sequence: full-stream logs never overwrite
$script:diagDeadline = (Get-Date).AddSeconds(600)  # admission checkpoint for NEW captures only; an admitted call can still run its bounded budget past it
function Need($cond, $msg) { if ($cond) { "ASSERT-OK: $msg" } else { $script:fail += $msg; "ASSERT-FAIL: $msg" } }
function Invoke-BoundedNative($file, $argsLine, $ms, $label) {
  # Raw-arguments invocation (Inno-Params-like), explicit per-call bound on
  # the DIRECT child only; descendants a child spawns are not tracked or
  # killed. stdout/stderr are drained ASYNCHRONOUSLY from process start so a
  # chatty child (ISCC) can never fill a pipe buffer and block past the
  # timeout. Kill is followed by a bounded 5s reap; stream collection is
  # bounded too: an exited child can leave a descendant-held pipe open, so
  # the drain never waits indefinitely - an incomplete drain/reap FAILS the
  # capture instead of hanging. A Start() failure is classified as a LAUNCH
  # ERROR, never as a timeout. Successfully DRAINED, NON-EMPTY streams
  # persist to artifact log files (an empty stream produces no file; console
  # preview is capped); ALL logging goes to the HOST stream and
  # failures to $script:fail, so the pipeline output is ONLY the result
  # object - a log line can never contaminate a caller's absence check.
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $file
  $psi.Arguments = $argsLine
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $p = $null
  $startErr = $null
  try { $p = [System.Diagnostics.Process]::Start($psi) } catch { $startErr = $_ }
  if ($null -eq $p) {
    $script:fail += "$label failed to START (launch error, not a timeout): $startErr"
    Write-Host "ASSERT-FAIL: $label failed to START (launch error): $startErr"
    return [pscustomobject]@{ ExitCode = -1; StdOut = ''; StdErr = ''; TimedOut = $false; Reaped = $false; StartFailed = $true }
  }
  $outTask = $p.StandardOutput.ReadToEndAsync()
  $errTask = $p.StandardError.ReadToEndAsync()
  $timedOut = $false
  $reaped = $true
  if (-not $p.WaitForExit($ms)) {
    $timedOut = $true
    try { $p.Kill() } catch {}
    $reaped = $p.WaitForExit(5000)
    if (-not $reaped) {
      $script:fail += "$label did not reap within 5s after Kill"
      Write-Host "ASSERT-FAIL: $label did not reap within 5s after Kill"
    }
  }
  $stdout = ''
  $stderr = ''
  $outDrained = $false
  $errDrained = $false
  # Bounded NON-THROWING drain wait: poll IsCompleted (Task.Wait itself throws
  # on a faulted read before any status check could run); read Result only
  # after a completed non-faulted status is confirmed. Every fault, cancel,
  # or timeout is an explicit recorded failure, never swallowed.
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  while (-not $outTask.IsCompleted -and $sw.ElapsedMilliseconds -lt 5000) { Start-Sleep -Milliseconds 25 }
  if (-not $outTask.IsCompleted) { $script:fail += "$label stdout drain incomplete within 5s (descendant-held pipe?)"; Write-Host "ASSERT-FAIL: $label stdout drain incomplete within 5s" }
  elseif ($outTask.IsFaulted -or $outTask.IsCanceled) { $script:fail += "$label stdout read faulted/canceled: $($outTask.Exception)"; Write-Host "ASSERT-FAIL: $label stdout read faulted/canceled" }
  else { try { $stdout = $outTask.Result; $outDrained = $true } catch { $script:fail += "$label stdout read failed: $_"; Write-Host "ASSERT-FAIL: $label stdout read failed: $_" } }
  $sw.Restart()
  while (-not $errTask.IsCompleted -and $sw.ElapsedMilliseconds -lt 5000) { Start-Sleep -Milliseconds 25 }
  if (-not $errTask.IsCompleted) { $script:fail += "$label stderr drain incomplete within 5s (descendant-held pipe?)"; Write-Host "ASSERT-FAIL: $label stderr drain incomplete within 5s" }
  elseif ($errTask.IsFaulted -or $errTask.IsCanceled) { $script:fail += "$label stderr read faulted/canceled: $($errTask.Exception)"; Write-Host "ASSERT-FAIL: $label stderr read faulted/canceled" }
  else { try { $stderr = $errTask.Result; $errDrained = $true } catch { $script:fail += "$label stderr read failed: $_"; Write-Host "ASSERT-FAIL: $label stderr read failed: $_" } }
  $exitCode = if ($p.HasExited) { $p.ExitCode } else { -1 }
  try { $p.Dispose() } catch {}
  $script:callSeq += 1
  $safe = ('{0:d3}-' -f $script:callSeq) + ($label -replace '[^A-Za-z0-9._-]', '-')
  if ($outDrained -and $stdout) {
    $sl = Join-Path $out "$safe.stdout.log"
    try { [IO.File]::WriteAllText($sl, $stdout) } catch { $script:fail += "$label stdout log persistence failed: $_"; Write-Host "ASSERT-FAIL: $label stdout log persistence failed: $_" }
    if (-not (Test-Path $sl)) { $script:fail += "$label stdout log missing after persist"; Write-Host "ASSERT-FAIL: $label stdout log missing after persist" }
    Write-Host ("  [$label stdout] " + ((@($stdout -split "`r?`n") | Select-Object -First 20) -join ' | '))
  }
  if ($errDrained -and $stderr) {
    $el = Join-Path $out "$safe.stderr.log"
    try { [IO.File]::WriteAllText($el, $stderr) } catch { $script:fail += "$label stderr log persistence failed: $_"; Write-Host "ASSERT-FAIL: $label stderr log persistence failed: $_" }
    if (-not (Test-Path $el)) { $script:fail += "$label stderr log missing after persist"; Write-Host "ASSERT-FAIL: $label stderr log missing after persist" }
    Write-Host ("  [$label stderr] " + ((@($stderr -split "`r?`n") | Select-Object -First 20) -join ' | '))
  }
  if ($timedOut) {
    $script:fail += "$label exceeded ${ms}ms bound (killed; reaped=$reaped; direct-child bound only)"
    Write-Host "ASSERT-FAIL: $label exceeded ${ms}ms bound (killed; reaped=$reaped)"
  }
  return [pscustomobject]@{ ExitCode = $exitCode; StdOut = $stdout; StdErr = $stderr; TimedOut = $timedOut; Reaped = $reaped; StartFailed = $false }
}
$tmp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$out = Join-Path $tmp 'diag-artifacts'
New-Item -ItemType Directory -Force $out | Out-Null
foreach ($pth in @($out, $tmp)) { Need ($pth -notmatch '\s') "path whitespace-free for unquoted raw cmd args: $pth" }
function Cap($name, [scriptblock]$body) {
  "===== $name ====="
  if ((Get-Date) -ge $script:diagDeadline) {
    $script:fail += "$name skipped: global diagnostic deadline reached (bounded cleanup/upload budget reserved)"
    Write-Host "SKIP: $name - global diagnostic deadline reached"
    return
  }
  try { & $body 2>&1 6>&1 | Tee-Object -FilePath (Join-Path $out "$name.log") }
  catch { $script:fail += "$name threw: $_"; "CAPTURE ERROR: $_" | Tee-Object -FilePath (Join-Path $out "$name.log") -Append }
}

$diagTask = 'BMDiag-' + [guid]::NewGuid().ToString('N').Substring(0, 8)  # unique, SPACELESS: raw cmd args need no quoting

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
    $rc = (Invoke-BoundedNative "$env:SystemRoot\System32\schtasks.exe" "/Create /F /TN `"$diagTask`" /XML `"$xf`"" 20000 'schtasks create').ExitCode
    Need ($rc -eq 0) "sacrificial task created rc=$rc"
    $dump = Join-Path $out 'task-query-redirected.bin'
    $rc = (Invoke-BoundedNative "$env:SystemRoot\System32\cmd.exe" "/d /c schtasks /Query /TN $diagTask /XML > $dump" 20000 'schtasks query redirect').ExitCode
    Need ($rc -eq 0) "query redirect rc=$rc"
    $bytes = [IO.File]::ReadAllBytes($dump)
    Need ($bytes.Length -gt 0) "export bytes non-empty ($($bytes.Length) bytes)"
    $hexN = [Math]::Min(7, $bytes.Length - 1)
    if ($bytes.Length -gt 0) { "first bytes: " + (($bytes[0..$hexN] | ForEach-Object { $_.ToString('X2') }) -join ' ') }
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
    "product-equivalent UTF-16LE decode loadXML: $okLoad"
    # Alternative-decode reporting (not assumed): attempt raw UTF-8 view too.
    $doc2 = New-Object -ComObject 'Msxml2.DOMDocument.6.0'; $doc2.async = $false
    "alternative UTF-8 decode loadXML: " + $doc2.loadXML([Text.Encoding]::UTF8.GetString($bytes))
    Need $okLoad "MSXML load of product-equivalent decode"
    $uidRaw = ''
    if ($okLoad) {
      $uids = $doc.selectNodes('//*[local-name()="Principals"]/*[local-name()="Principal"]/*[local-name()="UserId"]')
      Need ($uids.length -eq 1) "exactly one Principals/Principal/UserId chain ($($uids.length))"
      if ($uids.length -ge 1) { $uidRaw = $uids.item(0).text }
      "principal UserId raw: [$uidRaw]"
      Need ($uidRaw.Length -gt 0) "principal UserId non-empty"
      $execs = $doc.selectNodes('//*[local-name()="Actions"]/*[local-name()="Exec"]')
      "exec nodes: $($execs.length)"
    }
    $w = Join-Path $out 'whoami.bin'
    $rc = (Invoke-BoundedNative "$env:SystemRoot\System32\cmd.exe" "/d /c whoami /user /fo csv /nh > $w" 15000 'whoami redirect').ExitCode
    Need ($rc -eq 0) "whoami rc=$rc"
    $wb = [IO.File]::ReadAllBytes($w)
    $whexN = [Math]::Min(7, $wb.Length - 1)
    if ($wb.Length -gt 0) { "whoami first bytes: " + (($wb[0..$whexN] | ForEach-Object { $_.ToString('X2') }) -join ' ') }
    $whoTxt = [Text.Encoding]::Default.GetString($wb)
    "whoami text: $whoTxt"
    $wm = [regex]::Match($whoTxt, '"([^"]+)","([^"]+)"')
    Need $wm.Success "whoami CSV parsed"
    if ($wm.Success -and $uidRaw.Length -gt 0) {
      $form = if ($uidRaw -ieq $wm.Groups[2].Value) { 'SID-match' } elseif ($uidRaw -ieq $wm.Groups[1].Value) { 'NAME-match' } else { 'NO-MATCH' }
      "principal identity form vs whoami: $form"
      Need ($form -ne 'NO-MATCH') "principal identity matches whoami SID or name"
    }
  } finally {
    $null = (Invoke-BoundedNative "$env:SystemRoot\System32\schtasks.exe" "/Delete /TN `"$diagTask`" /F" 15000 'D-a cleanup delete').ExitCode
    $q = Invoke-BoundedNative "$env:SystemRoot\System32\schtasks.exe" "/Query /TN `"$diagTask`"" 15000 'D-a cleanup verify'
    $absent = ($q.ExitCode -ne 0) -and (($q.StdOut + ' ' + $q.StdErr) -match '(?i)cannot find|does not exist')
    Need $absent "sacrificial task verified ABSENT after cleanup (query rc=$($q.ExitCode) with scheduler not-found response; a generic query failure is NOT treated as absent)"
  }
}

Cap 'D-b-marker-bang-path' {
  # Extract the EXACT marker command from helper/installer.iss (no re-typed copy).
  $iss = Get-Content 'helper/installer.iss' -Raw
  $m = [regex]::Match($iss, "(?s)cmd := '(/d /v:off /c .*?)';")
  Need $m.Success "marker cmd line extractable from installer.iss"
  $cmdline = ($m.Groups[1].Value -replace "'\s*\+\s*'", '') -replace "''", "'"
  Need ($cmdline.Trim().Length -gt 0) "extracted marker cmd non-empty (no bare cmd invocation)"
  $bang = Join-Path $tmp 'we!rd-profile'
  $envBackup = $env:LOCALAPPDATA
  try {
    $env:LOCALAPPDATA = $bang
    if ($cmdline.Trim().Length -gt 0) {
      Need (-not (Test-Path "$bang\BingeMote\updating")) "marker target absent before invocation (fresh evidence, no stale pass)"
      $rc = (Invoke-BoundedNative "$env:SystemRoot\System32\cmd.exe" $cmdline 15000 'marker cmd').ExitCode
      Need ($rc -eq 0) "marker cmd rc=$rc against bang path"
      Need (Test-Path "$bang\BingeMote\updating") "marker file exists at literal bang path"
    } else {
      $script:fail += "marker cmd extraction empty; invocation skipped (no bare cmd)"
    }
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
    foreach ($iter in 1..3) {
      if ((Get-Date) -ge $script:diagDeadline) { $script:fail += "D-c loop stopped early: global diagnostic deadline reached"; break }
      $rc = (Invoke-BoundedNative "$env:SystemRoot\System32\schtasks.exe" "/Create /F /TN `"$diagTask`" /XML `"$xf`"" 20000 'D-c create').ExitCode
      Need ($rc -eq 0) "D-c create rc=$rc"
      $rc = (Invoke-BoundedNative "$env:SystemRoot\System32\schtasks.exe" "/Run /TN `"$diagTask`"" 20000 'D-c run').ExitCode
      Need ($rc -eq 0) "D-c run rc=$rc"
      # Positive control: the OWNED token process must be OBSERVED launched
      # before any delete; zero matches without an acquisition-verified
      # launch observation is NO EVIDENCE, not closure.
      $seen = $false
      $seenAt = $null
      foreach ($i in 1..10) {
        $launchErr = $null
        $launched = @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorVariable launchErr -ErrorAction SilentlyContinue |
          Where-Object { $_.CommandLine -and $_.CommandLine.Contains($token) })
        Need (-not $launchErr) "Win32_Process launch-poll acquisition succeeded (iteration ${iter}; failure must not read as zero matches)"
        if ($launched.Count -gt 0) { $seen = $true; $seenAt = Get-Date; break }
        Start-Sleep -Milliseconds 1000
      }
      Need $seen "positive control: owned token process observed after run (iteration ${iter})"
      $null = (Invoke-BoundedNative "$env:SystemRoot\System32\schtasks.exe" "/Delete /TN `"$diagTask`" /F" 15000 'D-c in-loop delete').ExitCode
      $q = Invoke-BoundedNative "$env:SystemRoot\System32\schtasks.exe" "/Query /TN `"$diagTask`"" 15000 'D-c post-delete query'
      $taskGone = ($q.ExitCode -ne 0) -and (($q.StdOut + ' ' + $q.StdErr) -match '(?i)cannot find|does not exist')
      Need $taskGone "task verified ABSENT after delete (iteration ${iter}; rc=$($q.ExitCode) with not-found response; generic failure NOT treated as absent)"
      Start-Sleep -Milliseconds 2500
      $cimErr = $null
      $hits = @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorVariable cimErr -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($token) })
      Need (-not $cimErr) "Win32_Process name enumeration acquisition succeeded (failure must not read as zero matches)"
      "iteration ${iter}: token-matched processes after delete+settle: $($hits.Count)"
      if ($seenAt -and ((Get-Date) - $seenAt).TotalSeconds -gt 20) { "iteration ${iter}: note - 20s sleeper window elapsed since launch observation; a zero here is expiry-consistent, NOT proof of closure" }
      foreach ($p in $hits) {
        $stillErr = $null
        $still = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.ProcessId)" -ErrorVariable stillErr -ErrorAction SilentlyContinue
        Need (-not $stillErr) "Win32_Process pid requery acquisition succeeded (failure must not read as process gone)"
        if ($still -and $still.CommandLine -and $still.CommandLine.Contains($token)) {
          Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
          $reapOk = $true
          try { Wait-Process -Id $p.ProcessId -Timeout 5 -ErrorAction Stop } catch {
            # Wait-Process throws for BOTH 'already gone' and 'still running'.
            # Distinguish: a not-found query result is a verified reap; a
            # SURVIVING pid or an ACQUISITION ERROR (never treated as gone) fails.
            $gpErr = $null
            $survivor = Get-Process -Id $p.ProcessId -ErrorVariable gpErr -ErrorAction SilentlyContinue
            $verifiedGone = (-not $survivor) -and $gpErr -and ($gpErr[0].FullyQualifiedErrorId -match 'NoProcessFoundForGivenId')
            if ($survivor) { $reapOk = $false; $script:fail += "token pid $($p.ProcessId) still running 5s after Stop-Process" }
            elseif (-not $verifiedGone) { $reapOk = $false; $script:fail += "reap verification acquisition error for token pid $($p.ProcessId) (not treated as gone): $gpErr" }
          }
          "  stopped token-verified pid $($p.ProcessId) (bounded 5s reap; verified-gone counts as reaped, acquisition error never does)"
        }
      }
    }
  } finally {
    $null = (Invoke-BoundedNative "$env:SystemRoot\System32\schtasks.exe" "/Delete /TN `"$diagTask`" /F" 15000 'D-c cleanup delete').ExitCode
    $q = Invoke-BoundedNative "$env:SystemRoot\System32\schtasks.exe" "/Query /TN `"$diagTask`"" 15000 'D-c cleanup verify'
    $absent = ($q.ExitCode -ne 0) -and (($q.StdOut + ' ' + $q.StdErr) -match '(?i)cannot find|does not exist')
    Need $absent "sacrificial task verified ABSENT after D-c cleanup (query rc=$($q.ExitCode) with scheduler not-found response; a generic query failure is NOT treated as absent)"
  }
  "D-c done (exposure attempt; absence here is NOT proof of closure)"
}

Cap 'D-d-inno-compile' {
  $iscc = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'
  Need (Test-Path $iscc) "ISCC present at runner image path"
  $staged = $false
  $produced = Join-Path $out 'BingeMote-PC-9.9.9-diag.exe'  # explicit /O into OWNED evidence temp
  Need (-not (Test-Path $produced)) "output path not preexisting: $produced"
  Need (Test-Path 'helper\Assets\bingemote.ico') "SetupIconFile prerequisite present: helper\Assets\bingemote.ico"
  try {
    if (-not (Test-Path 'helper\out\BingeMote.exe')) {
      New-Item -ItemType Directory -Force 'helper\out' | Out-Null
      [IO.File]::WriteAllBytes('helper\out\BingeMote.exe', [byte[]](0x4D, 0x5A))
      $staged = $true
      "staged placeholder helper\out\BingeMote.exe (script-compile validation only)"
    }
    $rc = (Invoke-BoundedNative $iscc "/DMyAppVersion=9.9.9-diag /O$out helper/installer.iss" 120000 'ISCC compile').ExitCode
    Need ($rc -eq 0) "ISCC rc=$rc (script compile with staged input; hosted-image only, NOT Chr/COM runtime verification)"
    Need (Test-Path $produced) "ISCC produced expected output $produced"
  } finally {
    if (Test-Path $produced) {
      Remove-Item $produced -Force -ErrorAction SilentlyContinue
      if (Test-Path $produced) { $script:fail += 'produced installer removal failed' }
    }
    if ($staged) {
      Remove-Item 'helper\out\BingeMote.exe' -Force -ErrorAction SilentlyContinue
      if (Test-Path 'helper\out\BingeMote.exe') { $script:fail += 'staged placeholder removal failed' }
    }
  }
}

if ($script:fail.Count -gt 0) { "ASSERT FAILURES: $($script:fail.Count)"; $script:fail | ForEach-Object { " - $_" }; exit 1 }
"ALL ASSERTIONS PASSED"
