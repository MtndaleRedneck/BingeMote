# Narrow follow-up: remove only two owner-selected Windows firewall rule identities.
[CmdletBinding()]
param([string]$ExpectedSid,[switch]$Worker)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
function Assert-Target($Rule,$App,$Port,$Spec,[string]$Profile) {
    $path=Join-Path $Profile 'downloads\bingemotehelper-v1.0.1.exe'
    $name=$Spec.Protocol+' Query User{'+$Spec.Guid+'}'+$path.ToLowerInvariant()
    if (![string]::Equals([string]$Rule.Name,$name,[StringComparison]::OrdinalIgnoreCase)) {throw 'Exact selected rule identity mismatch.'}
    if (![string]::Equals([string]$App.Program,$path,[StringComparison]::OrdinalIgnoreCase)) {throw 'Selected rule program mismatch.'}
    if ([string]$Rule.DisplayName -ine 'bingemotehelper-v1.0.1.exe') {throw 'Selected rule display name mismatch.'}
    if ([string]$Rule.Direction -ne 'Inbound' -or [string]$Rule.Action -ne 'Allow') {throw 'Selected rule direction/action mismatch.'}
    $expected=if($Spec.Protocol -eq 'TCP'){@('TCP','6')}else{@('UDP','17')}
    if ([string]$Port.Protocol -notin $expected) {throw 'Selected rule protocol mismatch.'}
}
function Assert-Manifest($Manifest) {
    if ((@($Manifest.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'Rules') {throw 'Unexpected manifest schema; fixed Downloads helper path only.'}
    if (@($Manifest.Rules).Count -ne 2 -or (@($Manifest.Rules.Protocol | Sort-Object)-join ',') -cne 'TCP,UDP') {throw 'Expected exactly TCP and UDP targets.'}
    foreach($r in $Manifest.Rules) {
        if ((@($r.PSObject.Properties.Name | Sort-Object)-join ',') -cne 'Guid,Protocol') {throw 'Unexpected rule schema.'}
        if($r.Guid -notmatch '^[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}$'){throw 'Invalid rule GUID.'}
    }
}
function Invoke-Finish($Manifest) {
    Assert-Manifest $Manifest
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if (!$ExpectedSid -or $sid -ne $ExpectedSid){throw 'Different administrator account refused.'}
    if (!(New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Administrator permission required.'}
    $sys=[Environment]::SystemDirectory
    $env:PSModulePath=(Join-Path $sys 'WindowsPowerShell\v1.0\Modules')+';'+(Join-Path $PSHOME 'Modules')
    $env:PATH=$sys+';'+[Environment]::GetFolderPath('Windows')
    Import-Module (Join-Path $sys 'WindowsPowerShell\v1.0\Modules\NetSecurity\NetSecurity.psd1') -Force
    if (@($Manifest.Rules).Count -ne 2 -or @($Manifest.Rules.Protocol | Sort-Object -Unique).Count -ne 2){throw 'Expected exactly one TCP and one UDP target.'}
    $profile=[Environment]::ExpandEnvironmentVariables([string](Get-ItemProperty -LiteralPath ('HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\'+$sid)).ProfileImagePath)
    $plan=@()
    # No profile file reads, path existence tests, or file deletion, only identity strings.
    foreach($spec in $Manifest.Rules){
        if($spec.Protocol -notin @('TCP','UDP') -or $spec.Guid -notmatch '^[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}$'){throw 'Invalid exact target manifest.'}
        $id=$spec.Protocol+' Query User{'+$spec.Guid+'}'+(Join-Path $profile 'downloads\bingemotehelper-v1.0.1.exe').ToLowerInvariant()
        $all=@(Get-NetFirewallRule -PolicyStore PersistentStore -ErrorAction Stop)
        $r=@($all | Where-Object { [string]::Equals([string]$_.Name,$id,[StringComparison]::OrdinalIgnoreCase) })
        if(!$r.Count){Write-Host ('Selected '+$spec.Protocol+' rule already absent.');continue}
        if($r.Count -ne 1){throw 'Selected identity is not unique.'}
        $a=$r[0]|Get-NetFirewallApplicationFilter;$p=$r[0]|Get-NetFirewallPortFilter
        Assert-Target $r[0] $a $p $spec $profile
        $plan+=$r[0]
    }
    $backup=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) ('BingeMote-Firewall-Recovery-'+[guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($backup)|Out-Null
    & (Join-Path $sys 'icacls.exe') $backup /inheritance:r /grant:r ('*'+$sid+':(OI)(CI)RX') '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Host
    if($LASTEXITCODE -ne 0){throw 'Backup permissions failed.'}
    & (Join-Path $sys 'netsh.exe') advfirewall export (Join-Path $backup 'firewall-before.wfw') | Out-Host
    if($LASTEXITCODE -ne 0){throw 'Firewall backup failed. Nothing removed.'}
    $plan | Select-Object Name,DisplayName,Direction,Action,Profile | Export-Clixml -LiteralPath (Join-Path $backup 'selected-rules.xml')
    try {
    foreach($r in $plan){
        $now=Get-NetFirewallRule -PolicyStore PersistentStore -Name $r.Name
        $spec=@($Manifest.Rules | Where-Object {$now.Name.StartsWith($_.Protocol+' Query User{'+$_.Guid+'}',[StringComparison]::OrdinalIgnoreCase)})
        if($spec.Count -ne 1){throw 'Target changed; remaining removal stopped.'}
        Assert-Target $now ($now|Get-NetFirewallApplicationFilter) ($now|Get-NetFirewallPortFilter) $spec[0] $profile
        Remove-NetFirewallRule -PolicyStore PersistentStore -Name $now.Name
    }
    $remaining=@(Get-NetFirewallRule -PolicyStore PersistentStore)
    foreach($r in $plan){if(@($remaining|Where-Object {$_.Name -eq $r.Name}).Count){throw 'Selected rule remains.'}}
    } catch {
        Write-Host ('CLEANUP STOPPED: '+$_.Exception.Message) -ForegroundColor Red
        Write-Host ('Local firewall recovery: '+$backup)
        foreach($item in $plan) {
            try {
                $left=@(Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object {$_.Name -eq $item.Name})
                Write-Host ('Selected rule '+$item.Name+': '+$(if($left.Count){'REMAINS'}else{'ABSENT'}))
            } catch { Write-Host ('Selected rule '+$item.Name+': STATE UNVERIFIED') }
        }
        return 1
    }
    Write-Host ('Local firewall recovery: '+$backup)
    Write-Host 'TWO-RULE CLEANUP COMPLETE. No files, settings or other firewall rules were changed.' -ForegroundColor Green
    return 0
}
if($MyInvocation.InvocationName -ne '.'){
    $code=1
    try{
        if($Worker){$manifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'BingeMote-Finish-private.json') -Raw|ConvertFrom-Json;$code=Invoke-Finish $manifest}
        else{
            $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            $args='-NoProfile -ExecutionPolicy Bypass -File "'+$PSCommandPath+'" -Worker -ExpectedSid '+$sid
            $p=Start-Process (Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe') -Verb RunAs -ArgumentList $args -Wait -PassThru
            $code=$p.ExitCode
            if($code -ne 0){Write-Host 'CLEANUP INCOMPLETE. Send the visible error before reinstalling.' -ForegroundColor Yellow}
        }
    }catch{Write-Host ('CLEANUP STOPPED: '+$_.Exception.Message) -ForegroundColor Red;$code=1}
    Read-Host 'Press Enter to close'|Out-Null
    exit $code
}
