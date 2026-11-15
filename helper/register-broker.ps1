# BingeMote Input Broker registration (runs once from the elevated installer).
# Creates/replaces a Task Scheduler task that starts "BingeMote.exe --broker-launcher"
# as SYSTEM in the interactive session at every logon. The launcher hops the serving
# broker (--input-broker) into the active console session and watches its lifetime; the
# broker only plays input ops the per-user helper sends over a local named pipe.
# Because SYSTEM is the highest integrity level, UIPI can no longer discard phone input
# while an elevated window (e.g. Realtek's SwUSB dialog) holds the foreground - the same
# immunity Unified Remote gets from its signed uvhid.sys driver, without a driver.
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

# rev15: safe legacy cleanup. Pre-rev14 builds installed per-user under
# %LOCALAPPDATA%\Programs\BingeMote and pointed that user's Run entry and the
# "BingeMote Watchdog" task at the user-writable copy. Remove the stale copy and
# the stale task so nothing keeps the old path. The app's first run from the
# protected copy re-registers the watchdog with its own (now protected) path,
# and the installer's HKCU Run entry already points there.
$legacyDir = Join-Path $env:LOCALAPPDATA 'Programs\BingeMote'
Get-Process BingeMote -ErrorAction SilentlyContinue |
  Where-Object { $_.Path -and $_.Path.StartsWith($legacyDir, [System.StringComparison]::OrdinalIgnoreCase) } |
  Stop-Process -Force -ErrorAction SilentlyContinue
if (Test-Path $legacyDir) {
  Remove-Item $legacyDir -Recurse -Force -ErrorAction SilentlyContinue
  if (Test-Path $legacyDir) { Write-Host "warning: legacy copy still present at $legacyDir" }
  else { Write-Host "ok: removed legacy per-user copy at $legacyDir" }
}
# try/catch: pwsh 7.4+ turns a nonzero native exit (task absent) into a terminating
# error under ErrorActionPreference=Stop, which must not abort broker registration.
try { & schtasks.exe /Delete /TN "BingeMote Watchdog" /F 2>$null | Out-Null } catch { }

$action = New-ScheduledTaskAction -Execute $exe -Argument '--broker-launcher'
$trigger = New-ScheduledTaskTrigger -AtLogOn
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType Interactive -RunLevel Highest
# rev16 (F10): restart a failed broker 3x at one-minute intervals. This pairs with the
# launcher's lifetime watch in Program.cs: the task instance now ends nonzero when the
# console-session broker dies, so these settings actually fire.
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName 'BingeMote Input Broker' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
# rev17: start the broker NOW. Through rev16 the only trigger was AtLogOn and nothing
# started the task at install time, while PrepareToInstall ends the running broker
# before an upgrade - so the installing session had no broker until the next sign-in
# and the tray app fell back to local SendInput, losing to elevated/secure desktops
# (Ezzy's midnight rev16 install, Nov 15 2026: input_routing=local, SetCursorPos
# failures until sign-out/in). AtLogOn stays as the across-reboot backstop.
# try/catch: a start failure must not fail the install; the launcher supervision and
# the AtLogOn trigger still bring the broker up.
try { Start-ScheduledTask -TaskName 'BingeMote Input Broker' }
catch { Write-Host "warning: broker task registered but immediate start failed: $($_.Exception.Message)" }
