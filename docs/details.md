# BingeMote - technical details

## Architecture

```
Android app  --(VLC HTTP, port 8080, Basic auth)-->  VLC on the PC
Android app  --(BingeMote PC HTTP, port 8488, token)-->  BingeMote PC (mouse/keyboard)
Android app  --(UDP broadcast, port 8489)          -->  BingeMote PC discovery
```

Both HTTP APIs are plain HTTP on the LAN. VLC's interface is HTTP-only; BingeMote PC follows
suit. Pairing tokens and typed input are visible to a device able to observe LAN traffic.
Use only on a trusted private network, and never forward these ports to the internet.

## The Android app

Kotlin + Jetpack Compose, single module, minSdk 31.

- `Vlc.kt` - VLC's built-in HTTP interface client. Polls `status.json` once a second while
  open; commands are the standard `command=...&val=...` requests. Volume uses VLC's raw
  scale (256 = 100%). The volume buttons control Windows system volume and mute through BingeMote PC; VLC volume is the fallback when it is unreachable, capped at 100%.
- `HelperClient.kt` - the BingeMote PC HTTP API plus UDP broadcast discovery
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

.NET 8, Windows Forms, single self-contained exe (installed as "BingeMote.exe"; the code
still calls itself BingeMoteHelper internally). The "BingeMote PC" label lives only on the
release downloads, to tell them apart from the phone app.

- HTTP on 8488: `/ping`, `/pair`, `/mouse/move`, `/mouse/button`, `/mouse/scroll`,
  `/mouse/zoom`, `/key`, `/type`. Every request except `/pair` needs
  `X-BingeMote-Token`. Only private addresses (loopback, RFC1918, link-local) are served.
- Pairing: the PC window shows a 6-digit code. `/pair` trades it (single use, 10-minute
  expiry, 5 strikes locks until restart) for a random 256-bit token. Each phone gets its
  own token, so several phones can stay paired at once (capped at 8); the tokens live in
  `%LOCALAPPDATA%\BingeMoteHelper\config.json` (per-user directory), and a 1.x
  single-token config migrates on first launch.
- Input playback is `SendInput`, serialized behind one lock so two phones tapping at the
  same instant cannot interleave a gesture: relative mouse moves, button down/up, wheel
  for scroll, Ctrl+wheel for zoom, unicode key events for typed text.
- First run elevates once (UAC) to add the firewall rule (Private profile only, TCP 8488
  and UDP 8489) and the HTTP listen permission, verifies both with `netsh`, and writes a
  marker file so it never asks again. Saying no leaves it running but unreachable
  until it offers again on next launch. The 1.x rule ("BingeMote Helper") is removed in
  favor of "BingeMote".
- Autostart is an `HKCU\...\Run` entry ("BingeMote"; the 1.x "BingeMoteHelper" entry is
  removed); it lives in the tray.

## CI

`.github/workflows/build.yml` runs the unit tests, builds the signed release APK from the
`KEYSTORE_B64` / `KEYSTORE_PASSWORD` repository secrets, and publishes BingeMote PC.
Android and PC release separately: `android-vX.Y.Z` (latest) ships
`BingeMote-Android-X.Y.Z.apk`, `pc-vX.Y.Z` (never latest) ships `BingeMote-PC-X.Y.Z.exe`
and `BingeMote-PC-Setup-X.Y.Z.exe`. Publishing is a manual workflow dispatch with
`publish=true` and a target, after review signs off on the exact commit.
