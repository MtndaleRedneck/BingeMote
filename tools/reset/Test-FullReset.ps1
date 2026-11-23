$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
. "$PSScriptRoot\BingeMote-Reset.ps1"
$ExpectedSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$root=Join-Path $env:LOCALAPPDATA 'Programs\BingeMote'
$data=Join-Path $env:LOCALAPPDATA 'BingeMote'
$roam=Join-Path $env:APPDATA 'BingeMote'
foreach($d in @($root,$data,$roam)) { if(Test-Path $d) {throw "Fixture path unexpectedly exists: $d"};New-Item -ItemType Directory $d | Out-Null }
Set-Content (Join-Path $root 'BingeMote.exe') 'fixture only, not executable'
Set-Content (Join-Path $data 'config.json') '{"fixture":"pairing must be backed up"}'
Set-Content (Join-Path $roam 'resume-places.json') '{"fixture":true}'
$decoy=Join-Path $env:LOCALAPPDATA 'BingeMote-other';New-Item -ItemType Directory $decoy | Out-Null;Set-Content (Join-Path $decoy 'keep.txt') 'unrelated'
$run='HKCU:\Software\Microsoft\Windows\CurrentVersion\Run';New-Item $run -Force | Out-Null;Set-ItemProperty $run BingeMote ('"'+$root+'\BingeMote.exe" --autostart')
$uk='HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\BingeMoteResetFixture';New-Item $uk -Force | Out-Null;Set-ItemProperty $uk DisplayName 'BingeMote 1.7.12';Set-ItemProperty $uk InstallLocation $root
$action=New-ScheduledTaskAction -Execute (Join-Path $root 'BingeMote.exe') -Argument '--ensure-running'
Register-ScheduledTask -TaskName 'BingeMote Watchdog' -Action $action -Trigger (New-ScheduledTaskTrigger -AtLogOn) | Out-Null
$id='BMResetFull-'+[guid]::NewGuid().ToString('N')
New-NetFirewallRule -Name $id -DisplayName bingemote.exe -Program (Join-Path $root 'BingeMote.exe') -Direction Inbound -Action Allow | Out-Null
$decoyId=$id+'-decoy';New-NetFirewallRule -Name $decoyId -DisplayName 'Unrelated reset fixture' -Program "$env:SystemRoot\System32\notepad.exe" -Direction Inbound -Action Allow | Out-Null
# Clear the intentionally ambiguous decoy name from enumeration while keeping it as unrelated state.
& netsh.exe http add urlacl url=http://+:8488/ 'sddl=D:(A;;GX;;;S-1-5-4)'
if($LASTEXITCODE -ne 0){throw 'URL fixture creation failed'}
$code=Invoke-BingeMoteReset
if($code -ne 0){throw "Full fixture reset returned $code"}
foreach($d in @($root,$data,$roam)){if(Test-Path $d){throw "Fixture not reset: $d"}}
if(!(Test-Path (Join-Path $decoy 'keep.txt'))){throw 'Unrelated folder was touched'}
if(!(Get-NetFirewallRule -Name $decoyId)){throw 'Unrelated firewall rule removed'}
if(Get-NetFirewallRule -Name $id -ErrorAction SilentlyContinue){throw 'Owned firewall rule remains'}
if(Test-Path $uk){throw 'Owned uninstall entry remains'}
if((Get-ItemProperty $run).PSObject.Properties['BingeMote']){throw 'Owned startup entry remains'}
if(Get-ScheduledTask -TaskName 'BingeMote Watchdog' -ErrorAction SilentlyContinue){throw 'Owned task remains'}
$back=Get-ChildItem (Join-Path $env:LOCALAPPDATA 'BingeMote-Recovery') -Directory|Sort-Object LastWriteTime -Descending|Select-Object -First 1
$manifest=Import-Clixml (Join-Path $back.FullName 'folder-manifest.xml')
foreach($m in $manifest){if((@(Get-TreeProof $m.Backup)-join "`n") -cne (@($m.Proof)-join "`n")){throw 'Recovery backup proof differs'}}
Write-Host 'PASS FULL NATIVE RESET: file quarantine, backups, registry, task, firewall IDs, unrelated decoys'
# Read-failure fail-closed test, deliberately override enumeration before any mutations.
function Get-ScheduledTask {throw 'fixture denied read'}
$code=Invoke-BingeMoteReset
if($code -ne 1){throw 'Read failure did not report failure'}
Write-Host 'PASS READ FAILURE: explicit stopped report, no clean verdict'
Remove-NetFirewallRule -Name $decoyId
Remove-Item $decoy -Recurse -Force
# Actual on-disk selective VLC rewrite and BOM/comment preservation fixture.
Remove-Item Function:\Get-ScheduledTask
$vlcdir=Join-Path $env:APPDATA 'vlc';New-Item -ItemType Directory $vlcdir -Force | Out-Null
$vlcpath=Join-Path $vlcdir 'vlcrc'
$original="extraintf=rc:http`r`nhttp-password=fixture-secret`r`nhttp-host=0.0.0.0`r`nvolume=87`r`n"
[IO.File]::WriteAllText($vlcpath,$original,(New-Object Text.UTF8Encoding($false)))
$media=Join-Path $vlcdir 'unrelated-media.txt';Set-Content $media 'must stay'
$code=Invoke-BingeMoteReset
if($code -ne 0){throw "VLC reset fixture returned $code"}
$after=[IO.File]::ReadAllText($vlcpath)
if($after -notmatch 'extraintf=rc' -or $after -notmatch 'volume=87' -or $after -match '(?m)^http-password=' -or $after -match '(?m)^http-host='){throw 'Selective VLC rewrite wrong'}
if(!(Test-Path $media)){throw 'Unrelated VLC fixture file touched'}
$back=Get-ChildItem (Join-Path $env:LOCALAPPDATA 'BingeMote-Recovery') -Directory|Sort-Object LastWriteTime -Descending|Select-Object -First 1
if([IO.File]::ReadAllText((Join-Path $back.FullName 'vlcrc-before')) -cne $original){throw 'VLC backup differs'}
Write-Host 'PASS NATIVE VLC FILE: original backup, selective web reset, unrelated preferences/media retained'
# Force backup copy failure. No owned registry/firewall/file deletion may follow.
New-Item -ItemType Directory $data -Force | Out-Null
Set-Content (Join-Path $data 'config.json') '{"fixture":"must survive backup failure"}'
function Copy-Item {throw 'fixture backup denied'}
$code=Invoke-BingeMoteReset
if($code -ne 1 -or !(Test-Path (Join-Path $data 'config.json'))){throw 'Backup failure did not preserve original data'}
Write-Host 'PASS BACKUP FAILURE: explicit stopped report, original data retained'
Remove-Item Function:\Copy-Item
