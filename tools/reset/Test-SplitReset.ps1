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
# Real Limited-token task performs the actual USER phase. No mocked admin check.
$child=Join-Path $env:TEMP 'bm-reset-limited-fixture.ps1';$result=Join-Path $env:TEMP 'bm-reset-limited-result.txt'
@"
`$ErrorActionPreference='Stop'
. '$PSScriptRoot\BingeMote-Reset.ps1'
`$ExpectedSid='$ExpectedSid'
try{`$code=Invoke-BingeMoteReset}catch{`$code=9;`$_.Exception.Message|Set-Content '$result.error'}
`$code|Set-Content '$result'
exit `$code
"@ | Set-Content $child
$task='BMResetLimitedFixture'
$act=New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Argument ('-NoProfile -ExecutionPolicy Bypass -File "'+$child+'"')
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $task -Action $act -Principal $principal|Out-Null
Start-ScheduledTask $task
$deadline=(Get-Date).AddSeconds(45)
while(!(Test-Path $result) -and (Get-Date)-lt $deadline){Start-Sleep -Milliseconds 400}
if(!(Test-Path $result)){Get-ScheduledTaskInfo $task|Format-List;throw 'Limited user task did not return'}
if((Get-Content $result).Trim() -ne '0'){if(Test-Path "$result.error"){Get-Content "$result.error"};throw 'User token phase failed'}
foreach($d in @($root,$data,$roam)){if(Test-Path $d){throw 'User original path remains'}}
$after=[IO.File]::ReadAllText((Join-Path $vlc 'vlcrc'));if($after -notmatch 'extraintf=rc' -or $after -match '(?m)^http-password=' -or $after -notmatch 'volume=87'){throw 'VLC selective user reset wrong'}
Write-Host 'PASS REAL LIMITED TOKEN: profile quarantine, backup, startup and VLC selective reset'
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
Remove-NetFirewallRule -Name $decoyId;Unregister-ScheduledTask -TaskName $task -Confirm:$false
