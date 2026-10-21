## v1.1.0

Two downloads: **BingeMoteHelper-Setup** (recommended) installs it with a Start menu entry and an optional desktop shortcut; the plain **BingeMoteHelper.exe** runs without installing.

- The window is now a small settings app: status, the movies folder with the usual Windows folder picker, pairing controls, and Quit - and it follows Windows dark/light mode
- Opening the helper while it is already running brings its window up instead of doing nothing
- Updating takes over from the running copy on its own. From 1.0.0 or 1.0.1: quit the old copy by hand first (right-click its tray icon, Quit)
- New "Reset pairing" in the tray menu: forgets the phone and shows a fresh code
- Keeps a small log file (%LocalAppData%\BingeMoteHelper\helper.log) to help with setup problems

## v1.0.1

- The helper checks for updates too and tells you from the tray when a new version is out. Nothing installs without your OK.

## First release

- Listens for the paired phone and plays back mouse, scroll, zoom, keys and typing
- Windows system volume and mute
- One-time pairing code, Private-networks-only firewall rule, starts with Windows in the tray
