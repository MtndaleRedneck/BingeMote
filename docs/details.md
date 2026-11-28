# BingeMote - technical details

Runs on Android 12 or newer and 64-bit Windows 10/11.

## Architecture

```
Android app  --(VLC HTTP, VLC's web port (default 8080), Basic auth)-->  VLC on the PC
Android app  --(BingeMote PC HTTPS, port 8490, token)-->  BingeMote PC (mouse/keyboard)
Android app  --(UDP broadcast, port 8489)          -->  BingeMote PC discovery
```

VLC's own web interface is plain HTTP (its password is sent with each request), so its traffic
is visible to a device able to observe LAN traffic. The BingeMote PC connection is HTTPS. Use only
on a trusted private network, and never forward these ports to the internet.

## The Android app

Kotlin + Jetpack Compose, single module, minSdk 31.

- `Vlc.kt` - VLC's built-in HTTP interface client. Polls `status.json` once a second while
  open; commands are the standard `command=...&val=...` requests. Volume uses VLC's raw
  scale (256 = 100%). The volume buttons control Windows system volume and mute through BingeMote PC; VLC volume is the fallback when it is unreachable, capped at 100%.
- `HelperClient.kt` - the BingeMote PC API plus UDP broadcast discovery
  (`BINGEMOTE_DISCOVER_V1` / `BINGEMOTE_HERE_V1`).
- `RemoteState.kt` - holds the latest VLC status, fan-out of commands, mute bookkeeping
  (tap-to-mute restores the previous volume on unmute), and reconnect + rediscovery.
- `Store.kt` - settings in SharedPreferences; the VLC password and pairing token in
  EncryptedSharedPreferences.
- `Updater.kt` - once per open, asks the GitHub releases API for the latest release,
  compares versions, downloads the APK over HTTPS with a size cap and optional SHA-256
  check, verifies package name, version bump and the signing certificate, and hands the
  file to Android's installer. Silent on any network failure; no auto-install.
- Screens: `MainScreen` (remote), `MoviesScreen` (folder browser + D-pad), `MouseScreen`
  (touchpad gesture machine), `SettingsScreen`, `SetupScreen` (first-run pairing).

### VLC quirks worth knowing

- Relative seek: `val=+5s` (the `+` must be URL-encoded as `%2B`) or `-5s`.
- Subtitle selection: `command=subtitle_track&val=N` where N is the "Stream N" number from
  status.json; `-1` turns subtitles off. VLC does not reliably report the active track, so
  the app remembers the last pick per title.
- `browse.json?dir=` lists any folder VLC can read; playing a file is
  `command=in_play&input=<uri>`.

## BingeMote PC (Windows)

## BingeMote PC (Windows)

.NET 10, Windows Forms, single self-contained exe (installed as "BingeMote.exe"; the code
still calls itself BingeMoteHelper internally). The "BingeMote PC" label lives only on the
release downloads, to tell them apart from the phone app.

- HTTPS on 8490: `/ping`, `/pair`, `/mouse/move`, `/mouse/button`, `/mouse/scroll`,
  `/mouse/zoom`, `/key`, `/type`. Every request except `/pair` needs
  `X-BingeMote-Token`. Only private addresses (loopback, RFC1918, link-local) are served.
- Pairing: the PC window shows a 4-digit code. `/pair` trades it (single use, 10-minute
  expiry, 5 strikes locks until restart) for a random 256-bit token. Each phone gets its
  own token, so several phones can stay paired at once (capped at 8); the tokens live in
  `%LOCALAPPDATA%\BingeMoteHelper\config.json` (per-user directory), and a 1.x
  single-token config migrates on first launch.
- Input playback is `SendInput`, serialized behind one lock so two phones tapping at the
  same instant cannot interleave a gesture: relative mouse moves, button down/up, wheel
  for scroll, Ctrl+wheel for zoom, unicode key events for typed text.
- Firewall: setup (one UAC prompt, run by the installer or by the exe on first run) adds new,
  uniquely named inbound allow rules with `New-NetFirewallRule`: TCP 8490 and UDP 8489 for
  Private networks (any device) and for Public networks (this PC's own subnet only,
  `LocalSubnet4`). When VLC is installed it also adds a rule for `vlc.exe` on Private networks
  and a rule for `vlc.exe` + TCP + VLC's web port (vlcrc `http-port`, default 8080) on Public
  networks, subnet only. BingeMote never changes your Windows network type (Private/Public).
  Setup never deletes or rewrites a rule it did not create, including rules from older
  versions (an older "BingeMote VLC" rule is left as it is). Every rule it creates is
  recorded in a journal, and uninstall removes only those, and only if each still matches
  what was recorded. Nothing re-runs setup between installs, so if you install VLC or change VLC's web
  port or location after setup, run the BingeMote installer again: a missing VLC rule is added, and a
  VLC rule BingeMote created and recorded is updated in place, but only if it is still exactly what
  was recorded (a rule that changed since is left alone and logged). If any VLC rule is not in
  place afterwards, a warning says so at the end of setup or at the next normal launch. Both
  devices must be on the same Wi-Fi, and some guest and hotel networks block device-to-device
  traffic ("client isolation"); BingeMote cannot change that.
- Autostart is an `HKCU\...\Run` entry ("BingeMote"; the 1.x "BingeMoteHelper" entry is
  removed); it lives in the tray.

## CI

`.github/workflows/build.yml` runs the unit tests, builds the signed release APK from the
`KEYSTORE_B64` / `KEYSTORE_PASSWORD` repository secrets, and publishes BingeMote PC.
Android and PC release separately: `android-vX.Y.Z` (latest) ships
`BingeMote-Android-X.Y.Z.apk`, `pc-vX.Y.Z` (never latest) ships the
`BingeMote-PC-X.Y.Z.exe` installer. Publishing is a manual workflow dispatch with
`publish=true` and a target, after review signs off on the exact commit.

## Controls

- Play/pause, fullscreen, subtitles, volume and adjustable skip buttons.
- Hold the seek bar to unlock seeking. Tap the volume percentage to mute.
- Movies: arrows select, OK opens; phone volume keys move the highlight.
- Mouse: touchpad, click buttons, scroll strip, pinch zoom and keyboard.
- The app offers updates when it opens. Android confirms each installation.

## Network security

BingeMote requires pairing and accepts private-network addresses only. Several phones can pair with the same PC - each phone pairs once with its own code. The BingeMote PC connection is HTTPS and the token travels inside it. VLC's own web interface is plain HTTP with its password. Use only on a trusted network. Do not forward these ports to the internet.

Uninstalling BingeMote removes the program, its start-with-Windows entry, and the firewall rules, listen permission and watchdog task that setup recorded in its journal, each only when it still matches what was recorded. Anything that no longer matches, or that setup did not create (rules or startup entries from older versions), is left in place and noted in the helper log. The plain exe has no uninstaller of its own: if you ran it without the installer, the rules, listen permission and task that setup created stay until the installer's uninstaller runs the same journal-based cleanup.
