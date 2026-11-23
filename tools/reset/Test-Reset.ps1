$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
. "$PSScriptRoot\BingeMote-Reset.ps1"
$script:passed=0
function Assert($Condition,[string]$Message) { if (!$Condition) { throw "FAIL: $Message" }; $script:passed++; Write-Host "PASS $Message" }
$roots=@((Join-Path $env:LOCALAPPDATA 'Programs\BingeMote'),(Join-Path $env:ProgramFiles 'BingeMote'))
$r=[pscustomobject]@{DisplayName='BingeMote';Direction='Inbound';Action='Allow'}
$a=[pscustomobject]@{Program='Any'};$p=[pscustomobject]@{Protocol='TCP';LocalPort=@('8488')}
Assert ((Get-RuleDecision $r $a $p $roots) -eq 'remove') 'current exact TCP rule'
$p.Protocol='UDP';$p.LocalPort=@('8489');Assert ((Get-RuleDecision $r $a $p $roots) -eq 'remove') 'current exact UDP rule'
$p.LocalPort=@('12345');Assert ((Get-RuleDecision $r $a $p $roots) -eq 'keep-ambiguous') 'same display name wrong port preserved'
$r.DisplayName='BingeMote Unrelated';$p.LocalPort=@('8488');$p.Protocol='TCP';Assert ((Get-RuleDecision $r $a $p $roots) -eq 'keep-ambiguous') 'substring collision preserved'
$r.DisplayName='bingemote.exe';$a.Program=Join-Path $roots[0] 'BingeMote.exe';Assert ((Get-RuleDecision $r $a $p $roots) -eq 'remove') 'current app path rule'
$a.Program=Join-Path $env:TEMP 'missing-history\BingeMoteHelper-v1.0.1.exe';$r.DisplayName='bingemotehelper-v1.0.1.exe';Assert ((Get-RuleDecision $r $a $p $roots) -eq 'remove') 'missing legacy app path rule'
$a.Program="$env:SystemRoot\System32\notepad.exe";$r.DisplayName='bingemote.exe';Assert ((Get-RuleDecision $r $a $p $roots) -eq 'keep-ambiguous') 'unrelated program with copied display name preserved'
$a.Program='Any';Assert ((Get-RuleDecision $r $a $p $roots) -eq 'keep-ambiguous') 'broad rule with app-like name preserved'
Assert (!(In-Root ($roots[0]+'-other\BingeMote.exe') $roots)) 'prefix sibling not owned'
Assert (In-Root (Join-Path $roots[0] 'BingeMote.exe') $roots) 'owned path recognized'
$fixture=Join-Path $env:TEMP ('reset-test-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $fixture | Out-Null
Set-Content (Join-Path $fixture 'data.txt') 'fixture'
$proof=@(Get-TreeProof $fixture);Assert ($proof.Count -eq 1) 'backup proof includes file'
New-Item -ItemType Directory (Join-Path $fixture 'target') | Out-Null
cmd.exe /c mklink /J "$fixture\junction" "$fixture\target"
$refused=$false;try { Assert-PlainTree $fixture } catch { $refused=$true };Assert $refused 'junction refused'
cmd.exe /c rmdir "$fixture\junction";Remove-Item $fixture -Recurse -Force
# Native non-mutating firewall inventory API pipeline, then duplicate identity fixture.
$native=@(Get-NetFirewallRule -PolicyStore PersistentStore | Select-Object -First 2)
foreach ($item in $native) { $null=$item | Get-NetFirewallApplicationFilter; $null=$item | Get-NetFirewallPortFilter }
Assert ($native.Count -gt 0) 'native NetSecurity pipeline works'
$prefix='BMResetFixture-'+[guid]::NewGuid().ToString('N')
$ids=@(($prefix+'-a'),($prefix+'-b'),($prefix+'-decoy'))
try {
 New-NetFirewallRule -Name $ids[0] -DisplayName BingeMote -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8488 | Out-Null
 New-NetFirewallRule -Name $ids[1] -DisplayName BingeMote -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8488 | Out-Null
 New-NetFirewallRule -Name $ids[2] -DisplayName 'BingeMote Unrelated' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 54321 | Out-Null
 $found=@();foreach($id in $ids) {$fr=Get-NetFirewallRule -PolicyStore PersistentStore -Name $id;$fa=$fr|Get-NetFirewallApplicationFilter;$fp=$fr|Get-NetFirewallPortFilter;Write-Host ('FIXTURE '+$fr.Name+' app='+$fa.Program+' port='+$fp.LocalPort+' proto='+$fp.Protocol+' decision='+(Get-RuleDecision $fr $fa $fp $roots));if ((Get-RuleDecision $fr $fa $fp $roots) -eq 'remove') {$found+=$fr.Name}}
 Assert ($found.Count -eq 2 -and $found -contains $ids[0] -and $found -contains $ids[1]) 'duplicate display names keep unique identities and decoy'
} finally { foreach($id in $ids) { Remove-NetFirewallRule -Name $id -ErrorAction SilentlyContinue } }
# Different-admin guard is tested without doing any mutation.
$ExpectedSid='S-1-5-0';$blocked=$false;try { Invoke-BingeMoteReset } catch { $blocked=$true };Assert $blocked 'different-user elevation refused'
$text="#http-password=comment`r`nextraintf=rc:http:luahttp`r`nhttp-host=0.0.0.0`r`nhttp-password=fixture`r`nvolume=123`r`n"
$reset=Reset-VlcWebText $text
Assert ($reset.Contains('extraintf=rc')) 'VLC unrelated rc interface retained'
Assert ($reset.Contains('volume=123')) 'VLC unrelated preference retained'
Assert (!$reset.Contains("`nhttp-host=")) 'VLC host uses installed default'
Assert (!$reset.Contains("`nhttp-password=")) 'VLC password uses installed default'
Assert ((Reset-VlcWebText 'extraintf=http') -eq '#extraintf=http') 'VLC only-HTTP interface uses installed default'
Assert ((Reset-VlcWebText $reset) -ceq $reset) 'VLC reset idempotent'
Write-Host "FUNCTION TESTS PASSED: $script:passed"
