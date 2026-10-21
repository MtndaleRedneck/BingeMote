# BingeMote

<img src="docs/logo.png" width="128" alt="BingeMote logo">

A couch remote for VLC on your Windows PC, with a movie browser and a mouse-and-keyboard mode.

## Install

1. Download BingeMote Android (the APK) and BingeMote PC (the Windows installer) from the project's GitHub Releases page.
2. Run BingeMote PC on your PC. If SmartScreen warns, choose More info, then Run anyway.
3. Complete the one-time Windows permission prompt for the Private-network firewall rules and HTTP listener.
4. Open the Android app and follow setup: find the PC, enter its pairing code, then configure VLC's web interface.

Android 12+ and Windows x64. Keep both devices on the same trusted private network.

## Controls

- Play/pause, fullscreen, subtitles, volume and adjustable skip buttons.
- Hold the seek bar to unlock seeking. Tap the volume percentage to mute.
- Movies: arrows select, OK opens; phone volume keys move the highlight.
- Mouse: touchpad, click buttons, scroll strip, pinch zoom and keyboard.
- The app offers updates when it opens. Android confirms each installation.

## Network security

BingeMote PC requires pairing and accepts private-network addresses only. Several phones can pair with the same PC - each phone pairs once with its own code. Traffic is unencrypted HTTP, including typed text and the token. Use only on a trusted network. Do not forward these ports to the internet.

Uninstalling BingeMote PC removes the program and its start-with-Windows entry. The one-time firewall rule and HTTP-listener permission stay behind (removing them needs administrator rights; they are inert without the program). To remove them, run as administrator: `netsh advfirewall firewall delete rule name="BingeMote PC"` and `netsh http delete urlacl url=http://+:8488/`.

More setup, build and protocol details are in [docs/details.md](docs/details.md).
