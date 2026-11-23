$ErrorActionPreference='Stop'
. "$PSScriptRoot\BingeMote-Reset.ps1"
$dir=Join-Path $env:TEMP "BingeMote launcher & space's"
New-Item -ItemType Directory $dir -Force | Out-Null
Copy-Item "$PSScriptRoot\BingeMote-Reset.cmd" $dir
$source=@'
using System;
using System.IO;
class LauncherProbe { static int Main(string[] args) { File.WriteAllLines(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"args.txt"),args);return Int32.Parse(File.ReadAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"exit.txt"))); } }
'@
Add-Type -TypeDefinition $source -OutputAssembly (Join-Path $dir 'powershell.exe') -OutputType ConsoleApplication
foreach($exitCode in @(0,2,5)) {
 Set-Content (Join-Path $dir 'exit.txt') $exitCode
 $psi=New-Object Diagnostics.ProcessStartInfo
 $psi.FileName=$env:ComSpec;$psi.Arguments='/d /c ""'+(Join-Path $dir 'BingeMote-Reset.cmd')+'""';$psi.WorkingDirectory=$dir;$psi.UseShellExecute=$false;$psi.RedirectStandardInput=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
 $p=[Diagnostics.Process]::Start($psi);$p.StandardInput.WriteLine();$p.StandardInput.Close()
 if(!$p.WaitForExit(15000)){$p.Kill();throw 'Launcher timed out'}
 $out=$p.StandardOutput.ReadToEnd();$err=$p.StandardError.ReadToEnd();Write-Host $out;Write-Host $err
 if($p.ExitCode -ne $exitCode){throw 'CMD exit code propagation failed'}
 $a=[IO.File]::ReadAllLines((Join-Path $dir 'args.txt'))
 if($a.Count -ne 5 -or $a[0] -ne '-NoProfile' -or $a[1] -ne '-ExecutionPolicy' -or $a[2] -ne 'Bypass' -or $a[3] -ne '-File' -or $a[4] -cne (Join-Path $dir 'BingeMote-Reset.ps1')){throw 'CMD arguments changed'}
 Write-Host "PASS CMD quoted path/metacharacters and exit $exitCode"
}
$line=Get-ElevationArguments (Join-Path $dir 'BingeMote-Reset.ps1') 'S-1-5-21-123'
if($line -cne ('-NoProfile -ExecutionPolicy Bypass -File "'+(Join-Path $dir 'BingeMote-Reset.ps1')+'" -ExpectedSid S-1-5-21-123')){throw 'Elevation commandline wrong'}
Write-Host 'PASS elevation argument path quoting and same-account SID transport'
# Real PowerShell child, bad SID, never reaches mutations. Feed the final Read-Host.
$psi=New-Object Diagnostics.ProcessStartInfo
$psi.FileName="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe";$psi.Arguments=Get-ElevationArguments "$PSScriptRoot\BingeMote-Reset.ps1" 'S-1-5-0';$psi.UseShellExecute=$false;$psi.RedirectStandardInput=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
$p=[Diagnostics.Process]::Start($psi);$p.StandardInput.WriteLine();$p.StandardInput.Close()
if(!$p.WaitForExit(15000)){$p.Kill();throw 'Guard child timed out'}
Write-Host $p.StandardOutput.ReadToEnd();Write-Host $p.StandardError.ReadToEnd()
if($p.ExitCode -ne 1){throw 'Real child profile refusal exit incorrect'}
Write-Host 'PASS real Windows PowerShell child profile guard/error exit'
Write-Host 'NOT COVERED: interactive UAC consent dialog or human desktop pixels on hosted service runner.'
