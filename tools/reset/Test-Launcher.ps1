$ErrorActionPreference='Stop'
. "$PSScriptRoot\BingeMote-Reset.ps1"
$dir=Join-Path $env:TEMP "BingeMote launcher & space's"
New-Item -ItemType Directory $dir -Force | Out-Null
Copy-Item "$PSScriptRoot\BingeMote-Reset.cmd" $dir
$cmd=Get-Content "$PSScriptRoot\BingeMote-Reset.cmd" -Raw
if($cmd -notmatch '"%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"'){throw 'CMD does not use absolute Windows PowerShell'}
if($cmd -notmatch 'set "ResetCode=%ERRORLEVEL%"' -or $cmd -notmatch 'exit /b %ResetCode%'){throw 'CMD exit propagation missing'}
Write-Host 'PASS CMD protected absolute PowerShell resolution and captured exit-code source checks'
$line=Get-ElevationArguments (Join-Path $dir 'BingeMote-Reset.ps1') 'S-1-5-21-123'
if($line -cne ('-NoProfile -ExecutionPolicy Bypass -File "'+(Join-Path $dir 'BingeMote-Reset.ps1')+'" -ExpectedSid S-1-5-21-123')){throw 'Elevation commandline wrong'}
Write-Host 'PASS elevation argument path quoting and same-account SID transport'
# Real PowerShell child, bad SID, never reaches mutations. Feed the final Read-Host.
$psi=New-Object Diagnostics.ProcessStartInfo
$psi.FileName="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe";$psi.Arguments=Get-ElevationArguments "$PSScriptRoot\BingeMote-Reset.ps1" 'S-1-5-0';$psi.UseShellExecute=$false;$psi.RedirectStandardInput=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
$p=[Diagnostics.Process]::Start($psi);$p.StandardInput.WriteLine();$p.StandardInput.Close()
if(!$p.WaitForExit(15000)){$p.Kill();throw 'Guard child timed out'}
$childOut=$p.StandardOutput.ReadToEnd();$childErr=$p.StandardError.ReadToEnd();Write-Host $childOut;Write-Host $childErr
if($childOut -notmatch 'Different Windows account' -or $childOut -match 'cannot be retrieved'){throw 'Wrong-profile child failed for wrong reason'}
if($p.ExitCode -ne 1){throw 'Real child profile refusal exit incorrect'}
Write-Host 'PASS real Windows PowerShell child profile guard/error exit'
Write-Host 'NOT COVERED: interactive UAC consent dialog or human desktop pixels on hosted service runner.'
# Actual CMD entrypoint with absolute PowerShell. Hosted runner admin must safely refuse user phase.
Copy-Item "$PSScriptRoot\BingeMote-Reset.ps1" $dir
$psi=New-Object Diagnostics.ProcessStartInfo
$psi.FileName=$env:ComSpec;$psi.Arguments='/d /c ""'+(Join-Path $dir 'BingeMote-Reset.cmd')+'""';$psi.WorkingDirectory=$dir;$psi.UseShellExecute=$false;$psi.RedirectStandardInput=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
$p=[Diagnostics.Process]::Start($psi);$p.StandardInput.WriteLine();$p.StandardInput.WriteLine();$p.StandardInput.Close()
if(!$p.WaitForExit(15000)){$p.Kill();throw 'Real CMD entry timed out'}
$entryOut=$p.StandardOutput.ReadToEnd();$entryErr=$p.StandardError.ReadToEnd();Write-Host $entryOut;Write-Host $entryErr
if($p.ExitCode -ne 1 -or $entryOut -notmatch 'NOT as administrator' -or $entryOut -notmatch 'Reset did not finish completely'){throw 'Real CMD did not preserve correct safe refusal/error'}
Write-Host 'PASS REAL CMD ENTRYPOINT: absolute native PowerShell path with spaces/metacharacters, admin refusal, exit1 propagated'
