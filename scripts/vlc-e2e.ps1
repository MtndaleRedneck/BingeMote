# VLC control tests against real VLC on Windows, using the exact request shapes
# the Android app sends (see app/src/main/java/com/bingemote/Vlc.kt).
$ErrorActionPreference = 'Stop'
$pass = 'test123'
$base = 'http://127.0.0.1:8080/requests'

function Assert($cond, $msg) { if (-not $cond) { throw "ASSERT FAILED: $msg" } ; Write-Host "ok: $msg" }
function Vlc($url) {
    # Returns @{ code; body } like the app's get(): Basic auth, empty username.
    $out = Join-Path $env:TEMP ('vlc-' + [Guid]::NewGuid().ToString('N') + '.json')
    $code = & curl.exe -s -o $out -w '%{http_code}' -u ":$pass" $url
    $body = if (Test-Path $out) { Get-Content $out -Raw } else { '' }
    return @{ code = [int]$code; body = $body }
}

# --- a small real media file to play: 20s 8kHz sine WAV ---
$wav = Join-Path $env:TEMP 'bingemote-test.wav'
$rate = 8000; $seconds = 20; $freq = 440
$bw = New-Object IO.BinaryWriter([IO.File]::Create($wav))
$n = $rate * $seconds; $dataSize = $n * 2
$bw.Write([Text.Encoding]::ASCII.GetBytes('RIFF'))
$bw.Write([int](36 + $dataSize))
$bw.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
$bw.Write([int]16); $bw.Write([int16]1); $bw.Write([int16]1)
$bw.Write([int]$rate); $bw.Write([int]($rate * 2)); $bw.Write([int16]2); $bw.Write([int16]16)
$bw.Write([Text.Encoding]::ASCII.GetBytes('data'))
$bw.Write([int]$dataSize)
$block = New-Object byte[] 8192
$written = 0
for ($i = 0; $i -lt $n; $i++) {
    $v = [int16]([Math]::Sin(2 * [Math]::PI * $freq * $i / $rate) * 12000)
    $block[($i * 2) % 8192] = $v -band 0xff
    $block[($i * 2 + 1) % 8192] = ($v -shr 8) -band 0xff
    if (($i * 2 + 2) % 8192 -eq 0) { $bw.Write($block); $written += 8192 }
}
if ($written -lt $dataSize) { $bw.Write($block, 0, $dataSize - $written) }
$bw.Close()
Assert ((Get-Item $wav).Length -eq 44 + $dataSize) "test wav written"

# --- launch VLC with its HTTP interface, loopback only, dummy audio ---
Write-Host "== installing VLC =="
choco install vlc --no-progress -y | Out-Null
$vlcExe = 'C:\Program Files\VideoLAN\VLC\vlc.exe'
Assert (Test-Path $vlcExe) "vlc installed"
& $vlcExe --version
$proc = Start-Process $vlcExe -PassThru -ArgumentList '-I','dummy','--aout=afile','--audiofile-file=NUL','--audiofile-format=s16l','--extraintf','http','--http-host','127.0.0.1','--http-port','8080','--http-password',$pass
try {
    $up = $false
    foreach ($i in 1..40) {
        $r = Vlc "$base/status.json"
        if ($r.code -eq 200 -or $r.code -eq 401) { $up = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Assert $up "VLC HTTP interface is up"

    $r = Vlc "$base/status.json"
    Assert ($r.code -eq 200) "status.json with password is 200"
    $bad = & curl.exe -s -o NUL -w '%{http_code}' -u ':wrongpass' "$base/status.json"
    Assert ([int]$bad -eq 401) "status.json with wrong password is 401"

    # play the wav via in_play, exactly as the app does (uri percent-encoded)
    $uri = 'file:///' + ($wav -replace '\\','/')
    $enc = [uri]::EscapeDataString($uri)
    $r = Vlc "$base/status.json?command=in_play&input=$enc"
    Assert ($r.code -eq 200) "in_play accepted"

    $playing = $false
    foreach ($i in 1..20) {
        $s = (Vlc "$base/status.json").body | ConvertFrom-Json
        if ($s.state -eq 'playing') { $playing = $true; break }
        Start-Sleep -Milliseconds 500
    }
    Assert $playing "state is playing"
    Assert ([int]$s.length -gt 0) "length reported: $($s.length)"

    # pause toggle both ways
    Vlc "$base/status.json?command=pl_pause" | Out-Null
    Start-Sleep -Milliseconds 800
    $s = (Vlc "$base/status.json").body | ConvertFrom-Json
    Assert ($s.state -eq 'paused') "pl_pause pauses"
    Vlc "$base/status.json?command=pl_pause" | Out-Null
    Start-Sleep -Milliseconds 800
    $s = (Vlc "$base/status.json").body | ConvertFrom-Json
    Assert ($s.state -eq 'playing') "pl_pause resumes"

    # absolute seek, then relative +5s encoded as %2B5s like the app sends
    Vlc "$base/status.json?command=seek&val=2" | Out-Null
    Start-Sleep -Milliseconds 800
    $s = (Vlc "$base/status.json").body | ConvertFrom-Json
    $t0 = [int]$s.time
    Assert ($t0 -ge 1 -and $t0 -le 4) "absolute seek to 2s (got ${t0}s)"
    Vlc "$base/status.json?command=seek&val=%2B5s" | Out-Null
    Start-Sleep -Milliseconds 800
    $s = (Vlc "$base/status.json").body | ConvertFrom-Json
    $t1 = [int]$s.time
    Assert ($t1 -ge $t0 + 4 -and $t1 -le $t0 + 8) "relative +5s seek (${t0}s -> ${t1}s)"

    # volume in VLC raw units (256 = 100%). The command shape must be accepted;
    # whether the mixer holds the value depends on the audio output having a real
    # device, which a headless runner lacks - that part is reported, not asserted.
    $r = Vlc "$base/status.json?command=volume&val=128"
    Assert ($r.code -eq 200) "volume command accepted"
    Start-Sleep -Milliseconds 800
    $s = (Vlc "$base/status.json").body | ConvertFrom-Json
    if ([int]$s.volume -eq 128) {
        Write-Host "ok: volume set to 128 and held"
    } else {
        Write-Host "::warning::volume command accepted but status reports $($s.volume) - no real audio device on this runner, so the mixer effect of the volume command is UNVERIFIED here. Raw-unit math is covered by unit tests; verify once on a PC with speakers."
    }

    # subtitle off is a valid command shape even with no subs present
    $r = Vlc "$base/status.json?command=subtitle_track&val=-1"
    Assert ($r.code -eq 200) "subtitle_track off accepted"

    function Head($b) { return $b.Substring(0, [Math]::Min(300, $b.Length)) }

    # browse, the shapes the app actually sends:
    # 1) empty dir on first open (VLC lists drive letters)
    $r0 = Vlc "$base/browse.json?dir="
    Write-Host "browse dir= (drives) status $($r0.code): $(Head $r0.body)"
    Assert ($r0.code -eq 200) "browse.json empty dir is 200"
    Assert ($r0.body -match 'element') "browse.json empty dir lists drive letters"
    # 2) the plain Windows path VLC returns when you open a folder
    $r2 = Vlc "$base/browse.json?dir=$([uri]::EscapeDataString('C:\'))"
    Write-Host "browse dir=C:\ status $($r2.code): $(Head $r2.body)"
    Assert ($r2.code -eq 200) "browse.json plain path C:\ is 200"
    Assert ($r2.body -match 'element') "browse.json C:\ lists elements"
    # 3) a file:/// URI, if the user pastes one into Settings: observe and report.
    $r = Vlc "$base/browse.json?dir=$([uri]::EscapeDataString('file:///C:/'))"
    Write-Host "browse dir=file:///C:/ status $($r.code): $(Head $r.body)"
    Assert ($r.code -eq 200) "browse.json file:///C:/ is 200"
    if ($r.body -match 'element') { Write-Host "ok: file:/// browse lists elements" }
    else { Write-Host "::warning::file:///C:/ browse returned no elements - app users should enter a plain path like C:\Videos in Settings." }

    Write-Host "VLC E2E PASSED"
} finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}
