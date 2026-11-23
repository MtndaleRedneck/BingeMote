# BingeMote-only reset. Windows PowerShell 5.1. No network access.
[CmdletBinding()]
param([string]$ExpectedSid)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

function Get-ElevationArguments([string]$ScriptPath,[string]$Sid) {
    if ($Sid -notmatch '^S-1-[0-9-]+$' -or $ScriptPath.Contains('"')) { throw 'Invalid launch identity/path.' }
    return '-NoProfile -ExecutionPolicy Bypass -File "'+$ScriptPath+'" -ExpectedSid '+$Sid
}
function Same-Path([string]$A,[string]$B) {
    if (!$A -or !$B) { return $false }
    return [IO.Path]::GetFullPath($A).TrimEnd('\').Equals([IO.Path]::GetFullPath($B).TrimEnd('\'),[StringComparison]::OrdinalIgnoreCase)
}
function In-Root([string]$Path,[string[]]$Roots) {
    if (!$Path -or ![IO.Path]::IsPathRooted($Path)) { return $false }
    $p = [IO.Path]::GetFullPath($Path)
    foreach ($root in $Roots) {
        $r = [IO.Path]::GetFullPath($root).TrimEnd('\')
        if ($p.StartsWith($r+'\',[StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}
function Assert-PlainTree([string]$Path) {
    # Refuse junctions/symlinks in the target OR any existing ancestor.
    $p = [IO.Path]::GetFullPath($Path)
    while ($p) {
        if (Test-Path -LiteralPath $p) {
            $i = Get-Item -LiteralPath $p -Force
            if ($i.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse point refused: $p" }
        }
        $parent = Split-Path -Parent $p
        if ($parent -eq $p) { break }; $p = $parent
    }
    if (Test-Path -LiteralPath $Path) {
        foreach ($i in Get-ChildItem -LiteralPath $Path -Force -Recurse) {
            if ($i.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse point refused: $($i.FullName)" }
        }
    }
}
function Get-TreeProof([string]$Path) {
    Assert-PlainTree $Path
    if (!(Test-Path -LiteralPath $Path)) { return @() }
    $base = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    return @(Get-ChildItem -LiteralPath $Path -Force -Recurse -File | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($base.Length)+'|'+(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    })
}
function Get-RuleDecision($Rule,$App,$Port,[string[]]$InstallRoots) {
    $program = [Environment]::ExpandEnvironmentVariables([string]$App.Program).Trim('"')
    $display = [string]$Rule.DisplayName
    # Installer-managed rules: exact names AND exact expected connection ports only.
    $managed = $display -ceq 'BingeMote' -or $display -ceq 'BingeMote Helper'
    $ports = @($Port.LocalPort)
    $proto = [string]$Port.Protocol
    $expectedPort = ($ports.Count -eq 1) -and ((($proto -in @('TCP','6')) -and $ports[0] -eq '8488') -or (($proto -in @('UDP','17')) -and $ports[0] -eq '8489'))
    if ($managed -and $program -eq 'Any' -and $expectedPort -and [string]$Rule.Direction -eq 'Inbound' -and [string]$Rule.Action -eq 'Allow') { return 'remove' }
    # App-bound rules: path is in a known BingeMote install folder, or an exact
    # historic executable filename with matching Windows app-rule display name.
    $leaf = [IO.Path]::GetFileName($program)
    $knownLeaf = $leaf -in @('BingeMote.exe','BingeMoteHelper.exe','BingeMoteHelper-v1.0.1.exe')
    $exactDisplay = $display -in @('BingeMote','BingeMote Helper','bingemote.exe','bingemotehelper.exe','bingemotehelper-v1.0.1.exe')
    if ($knownLeaf -and $exactDisplay -and [IO.Path]::IsPathRooted($program)) {
        if (In-Root $program $InstallRoots) { return 'remove' }
        if (!(Test-Path -LiteralPath $program)) { return 'keep-ambiguous' }
        # Do not trust just the name of an existing file outside the install roots.
        $v = (Get-Item -LiteralPath $program).VersionInfo
        if ($v.ProductName -in @('BingeMote','BingeMoteHelper','BingeMote Helper') -and $v.OriginalFilename -in @('BingeMote.exe','BingeMoteHelper.exe')) { return 'remove' }
    }
    return 'keep-ambiguous'
}
function Known-UrlAcl([string]$Descriptor) {
    # Entire descriptor, one GX allow ACE only. No owner/group/flags/extra ACEs.
    return $Descriptor.Trim() -cmatch '^D:\(A;;GX;;;(?:WD|IU|S-1-1-0|S-1-5-4)\)$'
}
function Reset-VlcWebText([string]$Text) {
    # Comment out live host/password assignments so VLC loads its installed defaults.
    # Preserve other extra interfaces and every unrelated preference/comment.
    $lines=[regex]::Split($Text,'(?<=\n)')
    $result=foreach($line in $lines) {
        if ($line -match '^\s*(http-host|http-password)=') { '#'+$line }
        elseif ($line -match '^\s*extraintf=([^\r\n]*)([\r\n]*)$') {
            $parts=@($Matches[1] -split '[,: ]+' | Where-Object { $_ -and $_ -notin @('http','luahttp') })
            if ($parts.Count) { 'extraintf='+($parts -join ':')+$Matches[2] } else { '#'+$line }
        } else { $line }
    }
    return ($result -join '')
}
function Native-Checked([string]$Exe,[string[]]$Arguments) {
    & $Exe @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$Exe failed, exit $LASTEXITCODE" }
}
function Invoke-BingeMoteReset {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if (!$ExpectedSid -or $sid -ne $ExpectedSid) { throw 'Different Windows account at elevation. Cancelled to protect the wrong profile. Run from the account that uses BingeMote.' }
    if ((New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Open the reset normally, NOT as administrator. User-file operations refuse elevated execution.' }
    $installRoots = @((Join-Path $env:LOCALAPPDATA 'Programs\BingeMote'),(Join-Path $env:LOCALAPPDATA 'Programs\BingeMoteHelper'))
    $dataRoots = @((Join-Path $env:LOCALAPPDATA 'BingeMote'),(Join-Path $env:LOCALAPPDATA 'BingeMoteHelper'),(Join-Path $env:APPDATA 'BingeMote'))
    $roots = @($installRoots+$dataRoots | Select-Object -Unique)
    foreach ($r in $roots) { Assert-PlainTree $r }
    if (@(Get-Process -Name vlc -ErrorAction SilentlyContinue).Count) { throw 'Close VLC first, then run the reset again. No changes made.' }
    $hadInstall = @($installRoots | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'BingeMote.exe') }).Count -gt 0
    $backupBase = Join-Path $env:LOCALAPPDATA 'BingeMote-Recovery'
    Assert-PlainTree $backupBase
    New-Item -ItemType Directory -Path $backupBase -Force | Out-Null
    Native-Checked icacls.exe @($backupBase,'/inheritance:r','/grant:r',('*'+$sid+':(OI)(CI)F'),'*S-1-5-18:(OI)(CI)F','*S-1-5-32-544:(OI)(CI)F')
    $backup = Join-Path $backupBase ((Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path $backup | Out-Null
    Start-Transcript -Path (Join-Path $backup 'reset-log.txt') | Out-Null
    $issues = New-Object 'System.Collections.Generic.List[string]'
    try {
        Write-Host 'BingeMote reset: removes installs/pairing/settings and resets VLC web-remote values only.'
        Write-Host "Local recovery backup: $backup"
        # Complete inventory before stopping anything or removing state.
        $userUninstall=@()
        $rr='HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall'
        if(Test-Path $rr){foreach($k in Get-ChildItem $rr){
            $v=Get-ItemProperty -LiteralPath $k.PSPath
            if($v.PSObject.Properties['DisplayName'] -and [string]$v.DisplayName -match '^BingeMote(?: Helper)?(?: [0-9][0-9A-Za-z. -]*)?$'){
                if(!$v.PSObject.Properties['InstallLocation'] -or !@($installRoots | Where-Object {Same-Path $_ ([string]$v.InstallLocation)}).Count){throw 'Unknown user uninstall path.'}
                $userUninstall+=$k;Native-Checked reg.exe @('export',$k.Name,(Join-Path $backup ('user-uninstall-'+$userUninstall.Count+'.reg')),'/y')
            }
        }}
        $runPlan=@()
        foreach ($rk in @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Run')) {
            if (!(Test-Path $rk)) { continue }
            $v=Get-ItemProperty -LiteralPath $rk
            foreach ($n in @('BingeMote','BingeMoteHelper')) {
                if ($v.PSObject.Properties[$n]) {
                    $value=[string]$v.$n
                    $m=[regex]::Match($value,'^"([^"]+)"(?:\s.*)?$')
                    if (!$m.Success -or !(In-Root $m.Groups[1].Value $installRoots)) { throw 'Unexpected BingeMote startup target; no reset performed.' }
                    $runPlan += [pscustomobject]@{Key=$rk;Name=$n;Value=$value}
                }
            }
        }
        $runPlan | Export-Clixml -LiteralPath (Join-Path $backup 'startup-before.xml')
        $links=@()
        $shell=New-Object -ComObject WScript.Shell
        foreach ($dir in @([Environment]::GetFolderPath('Desktop'),[Environment]::GetFolderPath('Programs'),[Environment]::GetFolderPath('Startup'))) {
            if (!$dir -or !(Test-Path -LiteralPath $dir)) { continue }
            foreach ($n in @('BingeMote.lnk','BingeMote Helper.lnk')) {
                $l=Join-Path $dir $n
                if (Test-Path -LiteralPath $l) {
                    Assert-PlainTree $l
                    if (!(In-Root $shell.CreateShortcut($l).TargetPath $installRoots)) { throw 'Unexpected BingeMote shortcut target; no reset performed.' }
                    $links += $l
                }
            }
        }
        $links | Export-Clixml -LiteralPath (Join-Path $backup 'shortcut-paths.xml')
        $vlcPath=Join-Path $env:APPDATA 'vlc\vlcrc'
        $vlcBytes=$null;$vlcNew=$null
        if (Test-Path -LiteralPath $vlcPath) {
            Assert-PlainTree $vlcPath
            $vlcBytes=[IO.File]::ReadAllBytes($vlcPath)
            # VLC 3.x preferences use UTF-8. Invalid encoding is not rewritten.
            $utf8=New-Object Text.UTF8Encoding($false,$true)
            $vlcText=$utf8.GetString($vlcBytes)
            $vlcNew=Reset-VlcWebText $vlcText
            [IO.File]::WriteAllBytes((Join-Path $backup 'vlcrc-before'),$vlcBytes)
            if ((Get-FileHash -LiteralPath $vlcPath).Hash -ne (Get-FileHash -LiteralPath (Join-Path $backup 'vlcrc-before')).Hash) { throw 'VLC backup verification failed.' }
        }
        # Stop only identified executables. No taskkill by name or service wildcard.

        foreach ($p in @(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('BingeMote.exe','BingeMoteHelper.exe') })) {
            if (!$p.ExecutablePath -or !(In-Root $p.ExecutablePath $installRoots)) { throw 'Running BingeMote-named process outside known install paths; reset stopped.' }
            Stop-Process -Id $p.ProcessId -Force
        }
        # Back up every directory and verify the bytes before any destructive removal.
        $manifest=@();$index=0
        foreach ($r in $roots) {
            if (!(Test-Path -LiteralPath $r)) { continue }
            Assert-PlainTree $r
            $dest=Join-Path $backup ('folder-'+(++$index))
            Copy-Item -LiteralPath $r -Destination $dest -Recurse -Force
            $before=@(Get-TreeProof $r);$after=@(Get-TreeProof $dest)
            if (($before -join "`n") -cne ($after -join "`n")) { throw 'Backup verification failed; reset stopped before deletion.' }
            $manifest += [pscustomobject]@{Original=$r;Backup=$dest;Proof=$before}
        }
        $manifest | Export-Clixml -LiteralPath (Join-Path $backup 'folder-manifest.xml')
        $i=0;foreach ($l in $links) { Copy-Item -LiteralPath $l -Destination (Join-Path $backup ('shortcut-'+(++$i)+'.lnk')) }
        # All backups complete. Mutations below are restricted to the captured plan.
        foreach($k in $userUninstall){Remove-Item -LiteralPath $k.PSPath -Recurse -Force}
        foreach ($r in $runPlan) { Remove-ItemProperty -LiteralPath $r.Key -Name $r.Name }

        foreach ($l in $links) { Remove-Item -LiteralPath $l -Force }
        foreach ($m in $manifest) {
            Assert-PlainTree $m.Original
            if ((@(Get-TreeProof $m.Original) -join "`n") -cne (@($m.Proof) -join "`n")) { throw 'App files changed after backup; remaining deletion stopped.' }
            # Rename the original root into owner/admin-only recovery storage.
            # Never recursively delete user-writable trees while elevated.
            $quarantine=Join-Path $backup ('quarantine-'+[guid]::NewGuid().ToString('N'))
            [IO.Directory]::Move($m.Original,$quarantine)
            [pscustomobject]@{Original=$m.Original;Quarantine=$quarantine} | Export-Clixml -LiteralPath ($quarantine+'.xml')
            if (Test-Path -LiteralPath $m.Original) { throw 'A folder could not be removed.' }
        }
        if ($null -ne $vlcBytes) {
            if (@(Get-Process -Name vlc -ErrorAction SilentlyContinue).Count) { throw 'VLC started during reset; its config was not changed.' }
            if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($vlcPath)) -cne [Convert]::ToBase64String($vlcBytes)) { throw 'VLC config changed during reset; it was not overwritten.' }
            Assert-PlainTree $vlcPath
            $vlcTemp=Join-Path $backup 'vlcrc-reset-new'
            [IO.File]::WriteAllText($vlcTemp,$vlcNew,(New-Object Text.UTF8Encoding($false)))
            # Replace a file entry, not an in-place write through a swapped symlink.
            [IO.File]::Replace($vlcTemp,$vlcPath,(Join-Path $backup 'vlcrc-at-replace'),$false)
            Write-Host 'VLC HTTP interface/host/password reset to installed defaults; other interfaces/preferences kept.'
        }
        if ($issues.Count) {
            Write-Host 'PARTIAL RESET. Preserved or unresolved items:' -ForegroundColor Yellow
            $issues | ForEach-Object { Write-Host $_ }
            $issues | Set-Content -LiteralPath (Join-Path $backup 'PARTIAL.txt')
            return 2
        }
        Write-Host 'USER PHASE COMPLETE: profile files, pairing/settings and VLC web remote reset. Machine phase still required.' -ForegroundColor Green
        return 0
    } catch {
        Write-Host ('RESET STOPPED / MAY BE PARTIAL: '+$_.Exception.Message) -ForegroundColor Red
        Write-Host 'Do not assume a fresh state. Keep the recovery backup and send the visible error, not config files.'
        $_.Exception.Message | Set-Content -LiteralPath (Join-Path $backup 'STOPPED.txt')
        return 1
    } finally {
        Write-Host "Keep recovery folder local: $backup"
        Stop-Transcript | Out-Null
    }
}
function Invoke-MachineReset {
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if (!$ExpectedSid -or $sid -ne $ExpectedSid) { throw 'Different account at elevation. Machine phase refused.' }
    if (!(New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Machine phase requires elevation.' }
    # This worker never opens, backs up, modifies or deletes ANY user-profile file.
    $profileKey='HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\'+$sid
    $profile=[Environment]::ExpandEnvironmentVariables([string](Get-ItemProperty -LiteralPath $profileKey).ProfileImagePath)
    $pf=[Environment]::GetFolderPath('ProgramFiles')
    $pfx=[Environment]::GetFolderPath('ProgramFilesX86')
    $roots=@((Join-Path $profile 'AppData\Local\Programs\BingeMote'),(Join-Path $profile 'AppData\Local\Programs\BingeMoteHelper'),(Join-Path $pf 'BingeMote'))
    if ($pfx) { $roots+=Join-Path $pfx 'BingeMote' }
    # GUID backup directory is created directly under the protected Program Files parent.
    # No user-chosen backup path is accepted by the elevated worker.
    $backup=Join-Path $pf ('BingeMote-Reset-Recovery-'+[guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($backup) | Out-Null
    $issues=New-Object 'System.Collections.Generic.List[string]'
    Start-Transcript -LiteralPath (Join-Path $backup 'machine-log.txt') | Out-Null
    try {
        Native-Checked icacls.exe @($backup,'/inheritance:r','/grant:r',('*'+$sid+':(OI)(CI)RX'),'*S-1-5-18:(OI)(CI)F','*S-1-5-32-544:(OI)(CI)F')
        $tasks=@(Get-ScheduledTask | Where-Object { $_.TaskPath -eq '\' -and $_.TaskName -in @('BingeMote Watchdog','BingeMote Input Broker') })
        foreach($t in $tasks) {
            if (!@($t.Actions).Count) { throw 'Known task has no verifiable action.' }
            foreach($a in $t.Actions) {
                $exe=([string]$a.Execute).Trim('"')
                if (!(In-Root $exe $roots) -or [IO.Path]::GetFileName($exe) -ne 'BingeMote.exe') { throw 'Known task has unrecognized action.' }
            }
            Export-ScheduledTask -TaskName $t.TaskName -TaskPath '\' | Set-Content -LiteralPath (Join-Path $backup ($t.TaskName+'.xml')) -Encoding Unicode
        }
        if (@(Get-CimInstance Win32_Service | Where-Object { $_.Name -match 'BingeMote' -or $_.DisplayName -match 'BingeMote' }).Count) { throw 'Unexpected BingeMote service, machine reset refused.' }
        $rulePlan=@()
        foreach($r in @(Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.DisplayName -match 'BingeMote' })) {
            $a=$r | Get-NetFirewallApplicationFilter;$p=$r | Get-NetFirewallPortFilter
            # Do not inspect an executable or call Test-Path on profile paths while elevated.
            $program=[Environment]::ExpandEnvironmentVariables([string]$a.Program).Trim('"')
            $decision='keep-ambiguous'
            $leaf=[IO.Path]::GetFileName($program)
            if ($program -eq 'Any') { $decision=Get-RuleDecision $r $a $p @() }
            elseif ($leaf -in @('BingeMote.exe','BingeMoteHelper.exe','BingeMoteHelper-v1.0.1.exe') -and (In-Root $program $roots) -and $r.DisplayName -in @('BingeMote','BingeMote Helper','bingemote.exe','bingemotehelper.exe','bingemotehelper-v1.0.1.exe')) { $decision='remove' }
            $rulePlan += [pscustomobject]@{ID=$r.Name;Program=$program;Decision=$decision}
            if($decision -ne 'remove') { $issues.Add('Preserved ambiguous firewall rule: '+$r.Name) }
        }
        $rulePlan | Export-Clixml -LiteralPath (Join-Path $backup 'firewall-plan.xml')
        Native-Checked netsh.exe @('advfirewall','export',(Join-Path $backup 'firewall-before.wfw'))
        $url=& netsh.exe http show urlacl 2>&1
        if($LASTEXITCODE -ne 0){throw 'URLACL inventory failed.'}
        $url | Set-Content -LiteralPath (Join-Path $backup 'urlacl-before.txt')
        $urlPresent=[bool](($url -join "`n") -match '(?m)http://\+:8488/\s*$')
        $knownAcl=$false
        if($urlPresent) {
            $detail=& netsh.exe http show urlacl url=http://+:8488/ 2>&1
            if($LASTEXITCODE -ne 0){throw 'Exact URLACL inventory failed.'}
            $d=@([regex]::Matches(($detail -join "`n"),'(?m)^\s*SDDL\s*:\s*([^\r\n]+)\s*$') | ForEach-Object {$_.Groups[1].Value.Trim()})
            $knownAcl=($d.Count -eq 1 -and (Known-UrlAcl $d[0]))
        }
        $uninstall=@()
        foreach($rr in @('HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall')) {
            if(!(Test-Path $rr)){continue}
            foreach($k in Get-ChildItem $rr) {
                $v=Get-ItemProperty -LiteralPath $k.PSPath
                if($v.PSObject.Properties['DisplayName'] -and [string]$v.DisplayName -match '^BingeMote(?: Helper)?(?: [0-9][0-9A-Za-z. -]*)?$') {
                    if(!$v.PSObject.Properties['InstallLocation'] -or !@($roots | Where-Object { Same-Path $_ ([string]$v.InstallLocation) }).Count){throw 'Unknown BingeMote machine uninstall path.'}
                    $uninstall+=$k
                    Native-Checked reg.exe @('export',$k.Name,(Join-Path $backup ('uninstall-'+$uninstall.Count+'.reg')),'/y')
                }
            }
        }
        # Never recursively operate on ProgramFiles/Data app trees from this worker.
        # Current user has already removed profile files with their own token.
        foreach($r in @((Join-Path $pf 'BingeMote'),(Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'BingeMote'))) {
            if(Test-Path -LiteralPath $r) { $issues.Add('Protected machine app/data folder remains; manual review needed: '+$r) }
        }
        foreach($t in $tasks){Stop-ScheduledTask -TaskName $t.TaskName -TaskPath '\';Unregister-ScheduledTask -TaskName $t.TaskName -TaskPath '\' -Confirm:$false}
        foreach($r in $rulePlan | Where-Object {$_.Decision -eq 'remove'}){Remove-NetFirewallRule -PolicyStore PersistentStore -Name $r.ID}
        if($urlPresent) {
            $listener=@(Get-NetTCPConnection -State Listen | Where-Object {$_.LocalPort -eq 8488})
            if($knownAcl -and !$listener.Count -and ($uninstall.Count -gt 0 -or $tasks.Count -gt 0 -or @($rulePlan | Where-Object {$_.Decision -eq 'remove'}).Count -gt 0)){Native-Checked netsh.exe @('http','delete','urlacl','url=http://+:8488/')}
            else{$issues.Add('URL reservation preserved: ownership or remaining listener unresolved.')}
        }
        foreach($k in $uninstall){Remove-Item -LiteralPath $k.PSPath -Recurse -Force}
        if($issues.Count){Write-Host 'PARTIAL MACHINE RESET' -ForegroundColor Yellow;$issues | ForEach-Object {Write-Host $_};$issues | Set-Content -LiteralPath (Join-Path $backup 'PARTIAL.txt');return 2}
        Write-Host 'MACHINE PHASE COMPLETE' -ForegroundColor Green
        return 0
    } catch {Write-Host ('MACHINE RESET STOPPED / MAY BE PARTIAL: '+$_.Exception.Message) -ForegroundColor Red;return 1}
    finally {Write-Host ('Machine recovery backup: '+$backup);Stop-Transcript | Out-Null}
}
if ($MyInvocation.InvocationName -ne '.') {
    $code=1
    try {
        if($MachineOnly){$code=Invoke-MachineReset}
        else {
            if(!$ExpectedSid){$ExpectedSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value}
            $code=Invoke-BingeMoteReset
            if($code -eq 0) {
                $argLine=(Get-ElevationArguments $PSCommandPath $ExpectedSid)+' -MachineOnly'
                $child=Start-Process powershell.exe -Verb RunAs -ArgumentList $argLine -PassThru -Wait
                $code=$child.ExitCode
                if($code -eq 0){Write-Host 'BINGEMOTE RESET COMPLETE. Install the current PC app to set up again.' -ForegroundColor Green}
                else{Write-Host 'MACHINE PHASE INCOMPLETE. Do not assume a cold setup.' -ForegroundColor Yellow}
            }
        }
    } catch {Write-Host $_.Exception.Message -ForegroundColor Red;$code=1}
    Read-Host 'Press Enter to close' | Out-Null
    exit $code
}
