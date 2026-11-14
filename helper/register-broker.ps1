# BingeMote Input Broker registration (runs once from the elevated installer).
# Creates/replaces a Task Scheduler task that starts "BingeMote.exe --input-broker"
# as SYSTEM in the interactive session at every logon. The broker only plays input
# ops the per-user helper sends over a local named pipe. Because SYSTEM is the
# highest integrity level, UIPI can no longer discard phone input while an elevated
# window (e.g. Realtek's SwUSB dialog) holds the foreground - the same immunity
# Unified Remote gets from its signed uvhid.sys driver, without a driver.
param([string]$ExePath)
$ErrorActionPreference = 'Stop'
$exe = if ($ExePath) { $ExePath } else { Join-Path $PSScriptRoot 'BingeMote.exe' }

# rev14: the task runs this exe as SYSTEM, so its directory must be admin-only.
# The installer already ACLs {app} (Program Files defaults agree); re-assert here so
# upgrades and manual runs of this script cannot leave a user-writable SYSTEM target.
$dir = Split-Path $exe -Parent
& icacls $dir /inheritance:r /grant 'SYSTEM:(OI)(CI)F' /grant 'Administrators:(OI)(CI)F' /grant 'Users:(OI)(CI)RX' | Out-Null
& icacls $exe /inheritance:r /grant 'SYSTEM:F' /grant 'Administrators:F' /grant 'Users:RX' | Out-Null
& icacls $dir
& icacls $exe

$action = New-ScheduledTaskAction -Execute $exe -Argument '--input-broker'
$trigger = New-ScheduledTaskTrigger -AtLogOn
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType Interactive -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName 'BingeMote Input Broker' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
