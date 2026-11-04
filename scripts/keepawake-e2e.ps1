# Keep-awake end-to-end on a REAL Windows machine (CI runner) with REAL VLC:
# the hold must follow the actual condition - video loaded (playing OR paused) and
# VLC not minimized - not the forced /ci/hold hook. Asserts both the helper's own
# log lines and the OS-level view (powercfg /requests SYSTEM section).
# Requires: helper exe at helper/out/BingeMote.exe built with PAIR_CODE_HOOK, and
# VLC installed. Starts its own helper + VLC and cleans both up.
$ErrorActionPreference = 'Stop'
$exe = "helper/out/BingeMote.exe"
if (-not (Test-Path $exe)) { throw "helper exe missing at $exe" }
$vlcExe = 'C:\Program Files\VideoLAN\VLC\vlc.exe'
if (-not (Test-Path $vlcExe)) { throw "VLC missing" }

function Assert($cond, $msg) { if (-not $cond) { throw "ASSERT FAILED: $msg" } ; Write-Host "ok: $msg" }
function Invoke-Helper($path, $body, $token) {
    $headers = @{}
    if ($token) { $headers['X-BingeMote-Token'] = $token }
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:8488$path" -Method Post -Body $body -Headers $headers -UseBasicParsing -TimeoutSec 10
        return @{ code = $r.StatusCode; body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) { return @{ code = [int]$resp.StatusCode; body = '' } }
        throw
    }
}
function Invoke-HelperGet($path, $token) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:8488$path" -Headers @{ 'X-BingeMote-Token' = $token } -UseBasicParsing -TimeoutSec 10
        return @{ code = $r.StatusCode; body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) { return @{ code = [int]$resp.StatusCode; body = '' } }
        throw
    }
}
function VlcCmd($pw, $query) {
    & curl.exe -s -o NUL -w '%{http_code}' -u ":$pw" "http://127.0.0.1:8080/requests/status.json?$query"
}
function SystemSection([string]$req) {
    $m = [regex]::Match($req, '(?ms)^SYSTEM:\s*\r?\n(.*?)(?=^[A-Z][A-Z ]+:|\z)')
    if ($m.Success) { $m.Groups[1].Value } else { '' }
}
$logPath = Join-Path $env:LOCALAPPDATA 'BingeMote/helper.log'
function LogMark() {
    if (Test-Path $logPath) { return (Get-Content $logPath).Count } else { return 0 }
}
function Wait-Hold($want, $label, $since) {
    # The keep-awake sync ticks every 10s and the VLC poller runs on its own interval;
    # give a transition up to 45s, then read BOTH the helper log and powercfg. Only log
    # lines AFTER $since count: the helper logs an initial OFF at startup, and a stale
    # line must never satisfy a later stage.
    $word = if ($want) { 'ON' } else { 'OFF' }
    $ok = $false
    foreach ($i in 1..45) {
        Start-Sleep -Seconds 1
        if (Test-Path $logPath) {
            $fresh = Get-Content $logPath -ErrorAction SilentlyContinue | Select-Object -Skip $since
            if ($fresh | Select-String "Keep-awake hold $word \(") { $ok = $true; break }
        }
    }
    Assert $ok "helper log reports the hold $word ($label)"
    Start-Sleep -Seconds 2
    $req = (powercfg /requests) -join "`n"
    if ($want) {
        Assert ((SystemSection $req) -match 'BingeMote') "OS shows the helper's SYSTEM power request ($label)"
    } else {
        Assert ((SystemSection $req) -notmatch 'BingeMote') "OS shows NO helper SYSTEM power request ($label)"
    }
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class Win32W {
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
'@

# --- a small real VIDEO to play (the helper only plays video extensions) ---
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { choco install ffmpeg --no-progress -y }

Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process vlc -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
$env:BINGEMOTE_PAIR_CODE = '555555'
$p = Start-Process $exe -PassThru
try {
    $up = $false
    foreach ($i in 1..40) {
        try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8488); $c.Close(); $up = $true; break }
        catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $up) { throw "helper did not start listening on 8488" }
    $r = Invoke-Helper '/pair' '{"code":"555555"}' $null
    Assert ($r.code -eq 200) "paired for the keep-awake test"
    $paired = $r.body | ConvertFrom-Json
    $token = $paired.token
    $pw = "keepawake-pw"

    # Movies folder, then hand the password over exactly like a phone's setup does,
    # so the helper's VLC poller can read status.
    $movies = Join-Path $env:TEMP 'bingemote-ka-movies'
    New-Item -ItemType Directory -Force $movies | Out-Null
    Get-ChildItem $movies | Remove-Item -Force
    $folderJson = '{"moviesFolder":"' + ($movies -replace '\\','/') + '"}'
    $r = Invoke-Helper '/config' $folderJson $token
    Assert ($r.code -eq 200) "movies folder set"
    $r = Invoke-HelperGet '/config' $token
    Assert (($r.body | ConvertFrom-Json).keepAwake -eq $true) "keep-awake defaults on in /config"
    $r = Invoke-Helper '/vlc/setup' ('{"password":"' + $pw + '"}') $token
    Assert ($r.code -eq 200) "VLC password handed over"

    # Play through the HELPER, exactly like a phone tap: /movies/play launches VLC AND
    # hands the poller the HTTP password (ResumeTracker.NoteLaunch) - a hand-launched
    # VLC leaves the poller blind and the hold can never turn on (v2's defect).
    & ffmpeg -y -loglevel error -f lavfi -i testsrc=duration=180:size=640x360:rate=15 -pix_fmt yuv420p (Join-Path $movies 'clip.mp4')
    $r = Invoke-Helper '/movies/play' '{"path":"clip.mp4","fullscreen":false}' $token
    Assert ($r.code -eq 200) "helper launches VLC (the real movie flow)"
    $playing = $false
    foreach ($i in 1..30) {
        Start-Sleep -Seconds 1
        $s = & curl.exe -s -u ":$pw" "http://127.0.0.1:8080/requests/status.json" 2>$null
        if ($s -match '"state":"playing"') { $playing = $true; break }
    }
    Assert $playing "VLC is really playing the clip over its HTTP interface"

    # 1) playing + window visible: hold ON (helper log + OS power request).
    $mark = LogMark
    Wait-Hold $true "playing, VLC visible" $mark

    # 2) paused but still loaded + visible: the hold must STAY on (his paused-laptop fix).
    $code = VlcCmd $pw 'command=pl_pause'
    Assert ([int]$code -eq 200) "pl_pause accepted"
    Start-Sleep -Seconds 12
    $req = (powercfg /requests) -join "`n"
    Assert ((SystemSection $req) -match 'BingeMote') "hold SURVIVES the pause (loaded + visible)"

    # 3) minimized while paused: the hold must DROP (minimized VLC sleeps normally).
    $vlcProc = Get-Process vlc -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    Assert ($vlcProc -ne $null) "VLC has a real main window to minimize (Qt interface up)"
    $mark = LogMark
    [Win32W]::ShowWindow($vlcProc.MainWindowHandle, 6) | Out-Null  # SW_MINIMIZE
    Wait-Hold $false "VLC minimized" $mark

    # 4) restored while still paused+loaded: the hold must RETURN.
    $mark = LogMark
    [Win32W]::ShowWindow($vlcProc.MainWindowHandle, 9) | Out-Null  # SW_RESTORE
    Wait-Hold $true "VLC restored" $mark

    # 5) stopped (still open): the hold must DROP.
    $code = VlcCmd $pw 'command=pl_stop'
    Assert ([int]$code -eq 200) "pl_stop accepted"
    $mark = LogMark
    Wait-Hold $false "VLC stopped" $mark

    Write-Host "KEEPAWAKE-E2E-PASS playing/paused/minimized/restored/stopped all followed the rule"
} finally {
    if ($vlc -and -not $vlc.HasExited) { Stop-Process -Id $vlc.Id -Force -ErrorAction SilentlyContinue }
    Get-Process vlc -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    Get-Process BingeMote -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}
