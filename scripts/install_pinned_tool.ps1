# Installs a CI tool from a pinned official download, verified by sha256 BEFORE it is executed or extracted.
# Replaces `choco install vlc|ffmpeg` (the package feed returned 503/504 and no run could proceed).
# Same versions the choco feed supplied in the last green run 45666576153: vlc v3.0.24 and ffmpeg v9.1.0
# (its log also printed 'ffmpeg version 9.1-essentials_build-www.gyan.dev'). Pins:
#  - VLC installer sha256: from the publisher's checksum file
#    https://get.videolan.org/vlc/3.0.24/win64/vlc-3.0.24-win64.exe.sha256 (the installer itself is fetched from download.videolan.org: get.videolan.org answers a PowerShell user agent with an HTML page)
#  - ffmpeg 9.1 essentials build: sha256 digest reported by the GitHub release API for
#    https://github.com/GyanD/codexffmpeg/releases/tag/9.1. Same version as the choco package (9.1.0);
#    NOT asserted byte-identical to what choco installed.
# Failure is distinct: "TOOL NOT RUN: <tool> acquisition failed ..." and ZEPHYR_<TOOL>_STATUS=notrun is exported
# to later steps so dependent steps skip instead of running and failing for a missing tool.
# Budget note: -TimeoutSec is Invoke-WebRequest's request timeout, not a documented total-transfer limit; the real hard bound is
# the workflow step timeout-minutes (12 for both tools), the ~260 s per tool figure is NOT guaranteed.
# There is deliberately no unverified fallback (no choco) after a download or hash failure.
param([Parameter(Mandatory)][ValidateSet('vlc','ffmpeg')][string]$Tool)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$tmp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$pins = @{
  vlc    = @{ Url = 'https://download.videolan.org/vlc/3.0.24/win64/vlc-3.0.24-win64.exe'
              Sha = 'd711e1e1fe52052748c39080c7dce63f6b7e4c315efedf1774a3f1957b782ff3'; File = 'vlc-3.0.24-win64.exe' }
  ffmpeg = @{ Url = 'https://github.com/GyanD/codexffmpeg/releases/download/9.1/ffmpeg-9.1-essentials_build.zip'
              Sha = 'b9bbbb3acece000cbeec994f856649354e52c773807841e78908374b24d658fd'; File = 'ffmpeg-9.1-essentials_build.zip' }
}
$pin = $pins[$Tool]
$vlcExe = 'C:\Program Files\VideoLAN\VLC\vlc.exe'

function Set-Status([string]$v) {
  if ($env:GITHUB_ENV) { "ZEPHYR_$($Tool.ToUpper())_STATUS=$v" | Out-File -FilePath $env:GITHUB_ENV -Append -Encoding utf8 }
  Set-Item "env:ZEPHYR_$($Tool.ToUpper())_STATUS" $v
}
$script:seen = ''
function Test-Installed {
  # Existing tool counts only if its VERSION is the pinned one, not on mere presence.
  if ($Tool -eq 'vlc') {
    if (-not (Test-Path $vlcExe)) { return $false }
    $script:seen = "vlc.exe ProductVersion '$((Get-Item $vlcExe).VersionInfo.ProductVersion)'"
    Write-Host "found $script:seen"
    return ((Get-Item $vlcExe).VersionInfo.ProductVersion -like '3.0.24*')
  }
  $ff = (Get-Command ffmpeg.exe -ErrorAction SilentlyContinue).Source
  if (-not $ff) { return $false }
  $line = (& $ff -version 2>&1 | Select-Object -First 1) | Out-String
  $script:seen = "$ff first line '$($line.Trim())'"
  Write-Host "found $script:seen"
  return ($line -match '^ffmpeg version 9\.1(\D|$)')
}
function Fail([string]$why) {
  Set-Status 'notrun'
  throw "TOOL NOT RUN: $Tool acquisition failed ($why). Tests that need $Tool are skipped, not failed."
}

if (Test-Installed) { Write-Host "$Tool already present at the pinned version; skipping download"; Set-Status 'ok'; return }

$dest = Join-Path $tmp $pin.File
$ok = $false; $last = ''
$clock = [Diagnostics.Stopwatch]::StartNew()
foreach ($attempt in 1..3) {
  # Bounded budget: no new attempt after 150 s, so one tool costs at most ~260 s and cannot starve later steps.
  if ($attempt -gt 1 -and $clock.Elapsed.TotalSeconds -ge 150) { Write-Host "download budget used ($([int]$clock.Elapsed.TotalSeconds) s); no further attempts"; break }
  try {
    if (Test-Path $dest) { Remove-Item $dest -Force }
    Write-Host "download attempt $attempt/3: $($pin.Url)"
    Invoke-WebRequest -Uri $pin.Url -OutFile $dest -UseBasicParsing -TimeoutSec 120
    $got = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
    if ($got -ne $pin.Sha) { throw "sha256 mismatch: expected $($pin.Sha) got $got" }
    $ok = $true; break
  } catch {
    $last = $_.Exception.Message
    Write-Host "attempt $attempt failed: $last"
    if (Test-Path $dest) { Remove-Item $dest -Force }
    if ($attempt -lt 3) { $wait = if ($attempt -eq 1) { 20 } else { 60 }; Start-Sleep -Seconds $wait }
  }
}
if (-not $ok) { Fail "3 download attempts, last error: $last" }
Write-Host "sha256 verified: $($pin.Sha)"

if ($Tool -eq 'vlc') {
  $p = Start-Process -FilePath $dest -ArgumentList '/S' -Wait -PassThru
  if ($p.ExitCode -ne 0) { Fail "installer exit code $($p.ExitCode)" }
} else {
  $dir = Join-Path $tmp 'ffmpeg-9.1'
  if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
  Expand-Archive -Path $dest -DestinationPath $dir -Force
  $exe = Get-ChildItem $dir -Recurse -Filter ffmpeg.exe | Where-Object { $_.DirectoryName -like '*\bin' } | Select-Object -First 1
  if (-not $exe) { Fail 'ffmpeg.exe not found in the verified archive' }
  $env:PATH = "$($exe.DirectoryName);$env:PATH"
  if ($env:GITHUB_PATH) { $exe.DirectoryName | Out-File -FilePath $env:GITHUB_PATH -Append -Encoding utf8 }
}
if (-not (Test-Installed)) { Fail "installed but the expected path/version check failed; saw: $(if ($script:seen) { $script:seen } else { 'nothing at the expected path/PATH' })" }
Set-Status 'ok'
Write-Host "$Tool ready"
