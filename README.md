# BingeMote

The couch remote for movie night. BingeMote controls VLC on your Windows PC from your
Android phone: play, pause, seek, volume, subtitles, a folder browser for your movies, and
a full mouse-and-keyboard mode for everything else.

Two parts:

- **BingeMote** - the Android app (`BingeMote-vX.Y.Z.apk` in [Releases](../../releases))
- **BingeMote Helper** - the small Windows program the mouse mode talks to
  (`BingeMoteHelper-vX.Y.Z.exe` in [Releases](../../releases))

Both live on your home network only. No accounts, no cloud, nothing leaves the house.

## Setup

1. **Install the app.** Download the APK on your phone and open it. Android asks you to
   allow installing from your browser or files app - that is normal for an app installed
   this way.
2. **Install the helper on your PC.** Download `BingeMoteHelper.exe` and run it.
   - Windows SmartScreen may warn about an unrecognized app: click **More info**, then
     **Run anyway**.
   - Windows then asks once for administrator permission (UAC). The helper uses it only to
     add a firewall rule limited to Private networks, so your phone can reach it. It never
     asks again.
   - The helper sits in the system tray, starts with Windows, and shows a 6-digit pairing
     code.
3. **Turn on VLC's web remote.** In VLC: Tools - Preferences, set "Show settings" to
   **All**, then Interface - Main interfaces - tick **Web**. Open Main interfaces - Lua and
   set a password under Lua HTTP. Save and restart VLC. If Windows asks about the
   firewall, allow Private networks.
4. **Pair.** Open BingeMote and follow the setup: it finds your PC on its own, you enter
   the helper's 6-digit code, then your VLC password. Done.

## Using it

- **Remote** - the big play/pause tile, hold the seek bar a second to unlock it, tap the
  volume % to mute, hold the skip buttons to repeat them. Your phone's volume buttons
  control VLC's volume.
- **Movies** - browse your movies folder on the PC; highlight with the arrows (or the
  volume buttons) and OK plays it. Change the folder in Settings.
- **Mouse** - the screen is a touchpad: tap = left click, two-finger tap = right click,
  two-finger drag = scroll, pinch = zoom, long-press then move = drag, tap to let go.
  The keyboard button types straight onto the PC.

## Updating

The app checks for a new release each time it opens and offers it to you. Nothing
downloads or installs on its own; Android confirms the install like the first time.

## Security notes

- The phone and PC pair once with a 6-digit code; after that a random 256-bit token
  authorizes every request, and the helper only answers private (home network) addresses.
- Pairing codes are single-use, expire after 10 minutes, and lock after 5 wrong tries.
- The VLC password and the pairing token are stored in Android's encrypted preferences.
- The helper's firewall rule allows Private networks only - it is not reachable from the
  public internet.

See [docs/details.md](docs/details.md) for the technical details of both sides.

## Building

Android app: `./gradlew assembleRelease` (release signing needs `KEYSTORE_FILE`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS=bingemote`).

Helper: `dotnet publish helper/BingeMoteHelper -c Release -r win-x64 --self-contained -p:PublishSingleFile=true`.

GitHub Actions builds both on every push and publishes a release when the version number
increases to 1.0.0 or above.
