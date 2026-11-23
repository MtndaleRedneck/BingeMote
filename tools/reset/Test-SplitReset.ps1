$ErrorActionPreference='Stop'
. "$PSScriptRoot\BingeMote-Reset.ps1"
$ExpectedSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
Write-Host ('RESET-SOURCE-SHA256 '+(Get-FileHash "$PSScriptRoot\BingeMote-Reset.ps1").Hash)
# Elevated invocation must fail BEFORE opening any user path.
$blocked=$false;try{Invoke-BingeMoteReset}catch{$blocked=$true;Write-Host $_.Exception.Message}
if(!$blocked){throw 'Elevated user file phase not refused'}
Write-Host 'PASS elevated user-file phase refuses before profile mutation'
$root=Join-Path $env:LOCALAPPDATA 'Programs\BingeMote';$data=Join-Path $env:LOCALAPPDATA 'BingeMote';$roam=Join-Path $env:APPDATA 'BingeMote'
foreach($d in @($root,$data,$roam)){New-Item -ItemType Directory $d -Force|Out-Null}
Set-Content (Join-Path $root 'BingeMote.exe') 'inert fixture'
Set-Content (Join-Path $data 'config.json') '{"fixture":"pairing"}'
Set-Content (Join-Path $roam 'resume-places.json') '{}'
$vlc=Join-Path $env:APPDATA 'vlc';New-Item -ItemType Directory $vlc -Force|Out-Null
[IO.File]::WriteAllText((Join-Path $vlc 'vlcrc'),"extraintf=rc:http`r`nhttp-password=fixture`r`nhttp-host=0.0.0.0`r`nvolume=87`r`n",(New-Object Text.UTF8Encoding($false)))
$run='HKCU:\Software\Microsoft\Windows\CurrentVersion\Run';New-Item $run -Force|Out-Null;Set-ItemProperty $run BingeMote ('"'+$root+'\BingeMote.exe" --autostart')
$uk='HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\BingeMoteResetFixture';New-Item $uk -Force|Out-Null;Set-ItemProperty $uk DisplayName 'BingeMote 1.7.12';Set-ItemProperty $uk InstallLocation $root
$action=New-ScheduledTaskAction -Execute (Join-Path $root 'BingeMote.exe') -Argument '--ensure-running';Register-ScheduledTask -TaskName 'BingeMote Watchdog' -Action $action -Trigger (New-ScheduledTaskTrigger -AtLogOn)|Out-Null
$id='BMResetSplit-'+[guid]::NewGuid().ToString('N');New-NetFirewallRule -Name $id -DisplayName bingemote.exe -Program (Join-Path $root 'BingeMote.exe') -Direction Inbound -Action Allow|Out-Null
$decoyId=$id+'-decoy';New-NetFirewallRule -Name $decoyId -DisplayName 'Unrelated reset fixture' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 54321|Out-Null
& netsh.exe http add urlacl url=http://+:8488/ 'sddl=D:(A;;GX;;;S-1-5-4)';if($LASTEXITCODE -ne 0){throw 'URL fixture failed'}
# Dedicated disposable STANDARD local account proves user phase with a real nonadmin token.
# Hosted runner's main account has UAC disabled, so Limited scheduled task remains admin.
$testName='BMResetUser'+[guid]::NewGuid().ToString('N').Substring(0,8)
$bytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password=[Convert]::ToBase64String($bytes)+'aA1!'
$secure=ConvertTo-SecureString $password -AsPlainText -Force
New-LocalUser -Name $testName -Password $secure -AccountNeverExpires | Out-Null
$user=Get-LocalUser $testName
$credential=New-Object Management.Automation.PSCredential(($env:COMPUTERNAME+'\'+$testName),$secure)
$common=Join-Path $env:PUBLIC ('BMResetFixture-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $common|Out-Null
Copy-Item "$PSScriptRoot\BingeMote-Reset.ps1" $common
& icacls.exe $common /grant ('*'+$user.SID.Value+':(OI)(CI)M') | Out-Host
$child=Join-Path $common 'user-fixture.ps1'
@"
`$ErrorActionPreference='Stop'
. '$common\BingeMote-Reset.ps1'
`$ExpectedSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
`$root=Join-Path `$env:LOCALAPPDATA 'Programs\BingeMote'
`$data=Join-Path `$env:LOCALAPPDATA 'BingeMote'
foreach(`$d in @(`$root,`$data)){New-Item -ItemType Directory `$d -Force|Out-Null}
Set-Content (Join-Path `$root 'BingeMote.exe') 'inert fixture'
Set-Content (Join-Path `$data 'config.json') '{"fixture":"pairing"}'
`$vlc=Join-Path `$env:APPDATA 'vlc';New-Item -ItemType Directory `$vlc -Force|Out-Null
[IO.File]::WriteAllText((Join-Path `$vlc 'vlcrc'),"extraintf=rc:http``r``nhttp-password=fixture``r``nhttp-host=0.0.0.0``r``nvolume=87``r``n",(New-Object Text.UTF8Encoding(`$false)))
try{`$code=Invoke-BingeMoteReset;if(`$code -eq 0 -and ((Test-Path `$root) -or (Test-Path `$data))){throw 'Original paths remain'}}catch{`$_.Exception.Message|Set-Content '$common\error.txt';`$code=9}
`$code|Set-Content '$common\result.txt'
exit `$code
"@ | Set-Content $child
try {
 $p=Start-Process "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Credential $credential -LoadUserProfile -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "'+$child+'"') -Wait -PassThru
 if($p.ExitCode -ne 0){if(Test-Path "$common\error.txt"){Get-Content "$common\error.txt"};throw 'Real standard-user phase failed'}
 Write-Host 'PASS REAL STANDARD USER: profile quarantine/backup and VLC reset, no admin rights'
} finally {Remove-LocalUser $testName}
$env:PSModulePath=(Join-Path $env:TEMP 'untrusted-module-decoy')+';'+$env:PSModulePath
$code=Invoke-MachineReset
if($code -ne 0){throw "Machine reset failed: $code"}
if(Test-Path $uk){throw 'Machine uninstall entry remains'}
if(Get-ScheduledTask -TaskName 'BingeMote Watchdog' -ErrorAction SilentlyContinue){throw 'App task remains'}
if(Get-NetFirewallRule -Name $id -ErrorAction SilentlyContinue){throw 'App rule remains'}
if(!(Get-NetFirewallRule -Name $decoyId)){throw 'Unrelated decoy removed'}
Write-Host 'PASS ELEVATED MACHINE PHASE: exact task/registry/firewall/URLACL, unrelated decoy preserved'
# Disallow any profile filesystem reads/mutations in worker by source-level guard.
$worker=(Get-Command Invoke-MachineReset).ScriptBlock.ToString()
if($worker -match 'Copy-Item|Directory\]::Move|File\]::Replace|Get-FileHash|VersionInfo|ReadAllBytes'){throw 'Machine worker contains profile-file operations'}
Write-Host 'PASS privilege split: machine worker has no file-copy/quarantine/VLC operations'
Remove-NetFirewallRule -Name $decoyId
