# BingeMote-only reset. Windows PowerShell 5.1. No network access.
[CmdletBinding()]
param([string]$ExpectedSid)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

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
        if (!(Test-Path -LiteralPath $program)) { return 'remove' }
        # Do not trust just the name of an existing file outside the install roots.
        $v = (Get-Item -LiteralPath $program).VersionInfo
        if ($v.ProductName -in @('BingeMote','BingeMoteHelper','BingeMote Helper') -and $v.OriginalFilename -in @('BingeMote.exe','BingeMoteHelper.exe')) { return 'remove' }
    }
    return 'keep-ambiguous'
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
    if (!(New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Administrator permission is required. Use the CMD launcher.' }
    $installRoots = @((Join-Path $env:LOCALAPPDATA 'Programs\BingeMote'),(Join-Path $env:ProgramFiles 'BingeMote'))
    if (${env:ProgramFiles(x86)}) { $installRoots += Join-Path ${env:ProgramFiles(x86)} 'BingeMote' }
    $installRoots += Join-Path $env:LOCALAPPDATA 'Programs\BingeMoteHelper'
    $dataRoots = @((Join-Path $env:LOCALAPPDATA 'BingeMote'),(Join-Path $env:LOCALAPPDATA 'BingeMoteHelper'),(Join-Path $env:APPDATA 'BingeMote'),(Join-Path $env:ProgramData 'BingeMote'))
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
        $tasks = @(Get-ScheduledTask | Where-Object { $_.TaskPath -eq '\' -and $_.TaskName -in @('BingeMote Watchdog','BingeMote Input Broker') })
        foreach ($t in $tasks) {
            foreach ($a in $t.Actions) {
                if (!(In-Root ([string]$a.Execute).Trim('"') $installRoots) -or [IO.Path]::GetFileName(([string]$a.Execute).Trim('"')) -ne 'BingeMote.exe') { throw 'Known task name has an unexpected action; no reset performed.' }
            }
            if (@($t.Actions).Count -eq 0) { throw 'Empty task action; no reset performed.' }
            Export-ScheduledTask -TaskName $t.TaskName -TaskPath '\' | Set-Content -LiteralPath (Join-Path $backup ($t.TaskName+'.xml')) -Encoding Unicode
        }
        # No BingeMote version in reviewed history installs a Windows service.
        # Refuse an unexpected BingeMote service instead of deleting by substring.
        if (@(Get-CimInstance Win32_Service | Where-Object { $_.Name -match 'BingeMote' -or $_.DisplayName -match 'BingeMote' }).Count) { throw 'Unexpected BingeMote-named Windows service. No reset performed; inspection needed.' }
        $rulePlan = @()
        foreach ($r in @(Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.DisplayName -match 'BingeMote' })) {
            $r | Export-Clixml -LiteralPath (Join-Path $backup ('rule-'+[guid]::NewGuid().ToString('N')+'.xml'))
            $a = $r | Get-NetFirewallApplicationFilter
            $p = $r | Get-NetFirewallPortFilter
            $decision = Get-RuleDecision $r $a $p $installRoots
            $rulePlan += [pscustomobject]@{ID=$r.Name;DisplayName=$r.DisplayName;Program=$a.Program;Protocol=$p.Protocol;LocalPort=$p.LocalPort;Decision=$decision}
            if ($decision -ne 'remove') { $issues.Add('Preserved ambiguous firewall rule: '+$r.Name) }
        }
        $rulePlan | Export-Clixml -LiteralPath (Join-Path $backup 'firewall-plan.xml')
        Native-Checked netsh.exe @('advfirewall','export',(Join-Path $backup 'firewall-before.wfw'))
        $url = & netsh.exe http show urlacl 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'URL reservation inventory failed.' }
        $url | Set-Content -LiteralPath (Join-Path $backup 'urlacl-before.txt')
        $urlBlock=[regex]::Match(($url -join "`n"),'(?ms)^\s*Reserved URL\s*:\s*http://\+:8488/\s*$(.*?)(?=^\s*Reserved URL\s*:|\z)').Value
        $urlPresent = [bool]$urlBlock
        $knownAcl = ($urlBlock -match 'D:\(A;;GX;;;(?:WD|S-1-5-4)\)')
        $regRoots = @('HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall','HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall')
        $uninstall = @()
        foreach ($rr in $regRoots) {
            if (!(Test-Path $rr)) { continue }
            foreach ($k in Get-ChildItem $rr) {
                $v=Get-ItemProperty -LiteralPath $k.PSPath
                if ($v.PSObject.Properties['DisplayName'] -and [string]$v.DisplayName -match '^BingeMote(?: Helper)?(?: [0-9][0-9A-Za-z. -]*)?$') {
                    if (!$v.PSObject.Properties['InstallLocation'] -or !@($installRoots | Where-Object { Same-Path $_ ([string]$v.InstallLocation) }).Count) { throw 'BingeMote uninstall entry has unknown install path; no reset performed.' }
                    $uninstall += $k
                    Native-Checked reg.exe @('export',$k.Name,(Join-Path $backup ('uninstall-'+$uninstall.Count+'.reg')),'/y')
                }
            }
        }
        $runPlan=@()
        foreach ($rk in @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Run','HKLM:\Software\Microsoft\Windows\CurrentVersion\Run')) {
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
        foreach ($dir in @([Environment]::GetFolderPath('Desktop'),[Environment]::GetFolderPath('Programs'),[Environment]::GetFolderPath('CommonDesktopDirectory'),[Environment]::GetFolderPath('CommonPrograms'),[Environment]::GetFolderPath('Startup'))) {
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
        foreach ($t in $tasks) { Stop-ScheduledTask -TaskName $t.TaskName -TaskPath '\' }
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
        foreach ($t in $tasks) { Unregister-ScheduledTask -TaskName $t.TaskName -TaskPath '\' -Confirm:$false }
        foreach ($r in $rulePlan | Where-Object { $_.Decision -eq 'remove' }) { Remove-NetFirewallRule -PolicyStore PersistentStore -Name $r.ID }
        if ($urlPresent) {
            $listener=@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq 8488 })
            if ($hadInstall -and $knownAcl -and !$listener.Count) { Native-Checked netsh.exe @('http','delete','urlacl','url=http://+:8488/') }
            else { $issues.Add('Preserved URL reservation: ownership/listener could not be established after stopping BingeMote.') }
        }
        foreach ($r in $runPlan) { Remove-ItemProperty -LiteralPath $r.Key -Name $r.Name }
        foreach ($k in $uninstall) { Remove-Item -LiteralPath $k.PSPath -Recurse -Force }
        foreach ($l in $links) { Remove-Item -LiteralPath $l -Force }
        foreach ($m in $manifest) {
            Assert-PlainTree $m.Original
            if ((@(Get-TreeProof $m.Original) -join "`n") -cne (@($m.Proof) -join "`n")) { throw 'App files changed after backup; remaining deletion stopped.' }
            Remove-Item -LiteralPath $m.Original -Recurse -Force
            if (Test-Path -LiteralPath $m.Original) { throw 'A folder could not be removed.' }
        }
        if ($null -ne $vlcBytes) {
            if (@(Get-Process -Name vlc -ErrorAction SilentlyContinue).Count) { throw 'VLC started during reset; its config was not changed.' }
            if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($vlcPath)) -cne [Convert]::ToBase64String($vlcBytes)) { throw 'VLC config changed during reset; it was not overwritten.' }
            [IO.File]::WriteAllText($vlcPath,$vlcNew,(New-Object Text.UTF8Encoding($false)))
            Write-Host 'VLC HTTP interface/host/password reset to installed defaults; other interfaces/preferences kept.'
        }
        if ($issues.Count) {
            Write-Host 'PARTIAL RESET. Preserved or unresolved items:' -ForegroundColor Yellow
            $issues | ForEach-Object { Write-Host $_ }
            $issues | Set-Content -LiteralPath (Join-Path $backup 'PARTIAL.txt')
            return 2
        }
        Write-Host 'BingeMote reset complete. Install the current BingeMote PC app to set up again.' -ForegroundColor Green
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
if ($MyInvocation.InvocationName -ne '.') {
    if (!$ExpectedSid) {
        try {
            $ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            $argLine='-NoProfile -ExecutionPolicy Bypass -File "'+$PSCommandPath+'" -ExpectedSid '+$ownerSid
            $child=Start-Process -FilePath powershell.exe -Verb RunAs -ArgumentList $argLine -PassThru -Wait
            exit $child.ExitCode
        } catch { Write-Host 'Administrator approval cancelled or elevation failed. Nothing was reset.' -ForegroundColor Red; Read-Host 'Press Enter to close' | Out-Null; exit 1 }
    }
    try { $code=Invoke-BingeMoteReset } catch { Write-Host $_.Exception.Message -ForegroundColor Red; $code=1 }
    Read-Host 'Press Enter to close' | Out-Null
    exit $code
}
