## v1.2.0

Two downloads: **BingeMote-PC-Setup** (recommended) installs it with a Start menu entry and an optional desktop shortcut; the plain **BingeMote-PC** exe runs without installing. Once installed, the program is just called **BingeMote** - window, tray, Start menu, everything.

- New name: the old "BingeMote Helper" is now just **BingeMote** - window, tray, Start menu and installer. Install over the top of 1.x; it replaces the old copy, removes the old autostart entry and the old firewall rule
- Pair more than one phone: each phone pairs with its own code (tray menu, "New pairing code"). "Reset pairing" now forgets every phone
- The status window refreshes every second while it is open - a pairing code shows the moment it exists - and sleeps while tucked in the tray
- Movies without VLC running: BingeMote lists the folder and starts the movie in VLC itself

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
