# OSD desktop e2e (CI validation, never shipped): proves on a real Windows desktop
# what the bitmap shots cannot - (1) the pop-up composites over FULLSCREEN VLC,
# (2) clicks pass through it, (3) a read-only audio endpoint inventory for the
# mute-API question. Fails the step on any assert. Writes artifacts at repo root.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing, System.Windows.Forms

function Save-Screen($path) {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = [System.Drawing.Bitmap]::new($b.Width, $b.Height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen(0, 0, 0, 0, $bmp.Size)
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    return $b
}
function Count-Orange($path, $rect) {
    $bmp = [System.Drawing.Bitmap]::FromFile($path)
    $n = 0
    for ($y = $rect.Top; $y -lt $rect.Bottom; $y++) {
        for ($x = $rect.Left; $x -lt $rect.Right; $x++) {
            $p = $bmp.GetPixel($x, $y)
            if ($p.R -gt 200 -and $p.G -gt 90 -and $p.G -lt 170 -and $p.B -lt 60) { $n++ }
        }
    }
    $bmp.Dispose()
    return $n
}
function Count-DistinctColors($path, $rect) {
    $bmp = [System.Drawing.Bitmap]::FromFile($path)
    $set = @{}
    for ($y = $rect.Top; $y -lt $rect.Bottom; $y += 3) {
        for ($x = $rect.Left; $x -lt $rect.Right; $x += 3) {
            $set[$bmp.GetPixel($x, $y).ToArgb()] = $true
        }
    }
    $bmp.Dispose()
    return $set.Count
}

# --- 0. Read-only audio endpoint inventory (for the mute question; no changes) ---
$inv = @()
$inv += "Audiosrv: $((Get-Service Audiosrv).Status)"
$inv += "Win32_SoundDevice:"; Get-CimInstance Win32_SoundDevice | ForEach-Object { $inv += "  $($_.Name) [$($_.Status)]" }
$render = Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render' -ErrorAction SilentlyContinue
$inv += "MMDevices Render endpoints (registry): $($render.Count)"
$inv | Tee-Object -FilePath audio-inventory.txt

# --- 1. Preconditions: 96 DPI layout assumption (probe + panel math) ---
$screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$g0 = [System.Drawing.Graphics]::FromHwnd([IntPtr]::Zero)
$dpi = [int]$g0.DpiX
$g0.Dispose()
"Screen: $($screen.Width)x$($screen.Height), DPI: $dpi" | Tee-Object -FilePath audio-inventory.txt -Append
if ($dpi -ne 96) { throw "Runner DPI is $dpi, probe/panel math assumes 96" }

# --- 2. Build the hook exe (compiles the #if WINDOW_SHOT_HOOK code) + the probe ---
dotnet publish helper/BingeMoteHelper/BingeMoteHelper.csproj -c Release -r win-x64 --self-contained -p:PublishSingleFile=true "-p:DefineConstants=TRACE%3BWINDOW_SHOT_HOOK" -o helper/out-hook | Out-Null
$helper = (Get-Item helper/out-hook/BingeMote.exe).FullName
dotnet build scripts/osd-probe/osd-probe.csproj -c Release -o probe-out | Out-Null
$probe = (Get-Item probe-out/osd-probe.exe).FullName

# --- 3. Fullscreen VLC over a generated video (ffmpeg + vlc installed by workflow) ---
ffmpeg -y -f lavfi -i testsrc=duration=120:size=1280x720:rate=30 -pix_fmt yuv420p e2e-video.mp4 | Out-Null
$vlc = Start-Process -PassThru -FilePath 'C:\Program Files\VideoLAN\VLC\vlc.exe' -ArgumentList '--fullscreen','--loop','--no-video-title-show','e2e-video.mp4'
Start-Sleep -Seconds 8  # let VLC reach fullscreen

# --- 4. Normal pop-up over fullscreen VLC ---
$env:BINGEMOTE_OSD_SHOW_SECONDS = '12'; Remove-Item Env:BINGEMOTE_OSD_SHOW_MUTED -ErrorAction SilentlyContinue
$osd = Start-Process -PassThru -FilePath $helper -ArgumentList '--osd-show'
Start-Sleep -Seconds 4
Save-Screen 'osd-live.png' | Out-Null
Wait-Process -Id $osd.Id -Timeout 15 -ErrorAction SilentlyContinue
$panel = [System.Drawing.Rectangle]::new(($screen.X + ($screen.Width - 300) / 2), ($screen.Bottom - 48 - 64), 300, 64)
$orange = Count-Orange 'osd-live.png' $panel
"orange px in panel (normal): $orange" | Tee-Object -FilePath audio-inventory.txt -Append
if ($orange -lt 200) { throw "Normal OSD not composited over fullscreen VLC (orange=$orange)" }
$band = [System.Drawing.Rectangle]::new(100, 100, 400, 300)
$colors = Count-DistinctColors 'osd-live.png' $band
"distinct colors in video band: $colors" | Tee-Object -FilePath audio-inventory.txt -Append
if ($colors -lt 50) { throw "Video band not visibly playing (colors=$colors)" }

# --- 5. Muted pop-up over fullscreen VLC: grey, no orange ---
$env:BINGEMOTE_OSD_SHOW_MUTED = '1'
$osd = Start-Process -PassThru -FilePath $helper -ArgumentList '--osd-show'
Start-Sleep -Seconds 4
Save-Screen 'osd-live-muted.png' | Out-Null
Wait-Process -Id $osd.Id -Timeout 15 -ErrorAction SilentlyContinue
Remove-Item Env:BINGEMOTE_OSD_SHOW_MUTED
$orangeM = Count-Orange 'osd-live-muted.png' $panel
"orange px in panel (muted): $orangeM" | Tee-Object -FilePath audio-inventory.txt -Append
if ($orangeM -ge 5) { throw "Muted OSD still shows orange ($orangeM)" }
Stop-Process -Id $vlc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# --- 6. Click-through leg (no VLC): probe under the panel must receive the click ---
Remove-Item osd-probe.log -ErrorAction SilentlyContinue
$env:OSD_PROBE_LOG = (Join-Path (Get-Location) 'osd-probe.log')
$pr = Start-Process -PassThru -FilePath $probe
Start-Sleep -Seconds 1
$env:BINGEMOTE_OSD_SHOW_SECONDS = '8'
$osd = Start-Process -PassThru -FilePath $helper -ArgumentList '--osd-show'
Wait-Process -Id $pr.Id -Timeout 20 -ErrorAction SilentlyContinue
Wait-Process -Id $osd.Id -Timeout 15 -ErrorAction SilentlyContinue
$plog = Get-Content osd-probe.log -Raw
"probe log: $($plog -replace "`n", ' ')" | Tee-Object -FilePath audio-inventory.txt -Append
if ($plog -notmatch 'SENT') { throw "Probe never sent its click" }
if ($plog -notmatch 'CLICK') { throw "Click did NOT pass through the pop-up to the probe" }

"OSD DESKTOP E2E: PASS" | Tee-Object -FilePath audio-inventory.txt -Append
