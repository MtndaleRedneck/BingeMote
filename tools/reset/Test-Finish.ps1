$ErrorActionPreference='Stop'
. "$PSScriptRoot\BingeMote-Finish.ps1"
Write-Host ('FINISH-SHA256 '+(Get-FileHash "$PSScriptRoot\BingeMote-Finish.ps1").Hash)
$ExpectedSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$profile=[Environment]::ExpandEnvironmentVariables([string](Get-ItemProperty ('HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\'+$ExpectedSid)).ProfileImagePath)
$specs=@([pscustomobject]@{Guid=[guid]::NewGuid().ToString();Protocol='TCP'},[pscustomobject]@{Guid=[guid]::NewGuid().ToString();Protocol='UDP'})
$ids=@();$decoy='BMFinishDecoy-'+[guid]::NewGuid().ToString('N')
try{
 foreach($s in $specs){$id=$s.Protocol+' Query User{'+$s.Guid+'}'+(Join-Path $profile 'downloads\bingemotehelper-v1.0.1.exe').ToLowerInvariant();$ids+=$id;New-NetFirewallRule -Name $id -DisplayName 'bingemotehelper-v1.0.1.exe' -Program (Join-Path $profile 'downloads\bingemotehelper-v1.0.1.exe') -Protocol $s.Protocol -Direction Inbound -Action Allow|Out-Null}
 New-NetFirewallRule -Name $decoy -DisplayName 'bingemotehelper-v1.0.1.exe' -Program (Join-Path $profile 'downloads\bingemotehelper-v1.0.1.exe') -Direction Inbound -Action Allow|Out-Null
 $r=Get-NetFirewallRule -Name $ids[0];$app=$r|Get-NetFirewallApplicationFilter;$port=$r|Get-NetFirewallPortFilter
 $bad=[pscustomobject]@{Program='C:\unrelated.exe'};$refused=$false;try{Assert-Target $r $bad $port $specs[0] $profile}catch{$refused=$true};if(!$refused){throw 'Wrong program accepted'}
 $badSpec=[pscustomobject]@{Guid=$specs[0].Guid;Protocol='UDP'};$refused=$false;try{Assert-Target $r $app $port $badSpec $profile}catch{$refused=$true};if(!$refused){throw 'Wrong protocol accepted'}
 Write-Host 'PASS target path/protocol mismatch refused'
 if((Invoke-Finish ([pscustomobject]@{Rules=$specs})) -ne 0){throw 'Native finisher failed'}
 foreach($id in $ids){if(Get-NetFirewallRule -Name $id -ErrorAction SilentlyContinue){throw 'Target remains'}}
 if(!(Get-NetFirewallRule -Name $decoy)){throw 'Same-name/path unrelated ID removed'}
 Write-Host 'PASS only exact two IDs removed; same-name/path decoy preserved'
 if((Invoke-Finish ([pscustomobject]@{Rules=$specs})) -ne 0){throw 'Already absent rerun failed'}
 Write-Host 'PASS already-absent idempotence and local backup export'
}finally{foreach($id in @($ids)+@($decoy)){Remove-NetFirewallRule -Name $id -ErrorAction SilentlyContinue}}
