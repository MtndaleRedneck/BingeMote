## v1.5.1

- The phone can browse this PC's folders to pick the Movies folder - no VLC needed
- Pairing reads better at larger text sizes: bordered name box, note never clips
- Remove buttons fit their text - no more clipped labels
- Feedback & bugs: buttons fit their text, and the message box scrollbar goes dark
- Updates line keeps the version clear of the label at larger text sizes

## v1.5.0

- New squircle program icon, and Windows picks it up right after an update - no sign-out needed
- Updates install themselves now: one click, one Windows permission prompt, and BingeMote reopens on its own
- Update results get their own line in the window, and the window reads better at larger text sizes
- Closing the window just tucks it into the tray - no more balloon tip
- Updating from 1.4.0? This one still shows the setup screens - every update after is one click
- An update puts the desktop shortcut back if you removed it

## v1.4.0

- The Public Wi-Fi warning now sits in the window until fixed, re-checks every 30 seconds, and a home network is never buried by Not now
- Pairing is clearer: a first-run pointer to the phone app, tighter buttons, plainer code wording
- Updates: one click downloads the update and opens it - Windows still asks for permission
- Sharper program icon at the largest icon sizes

## v1.3.1

- New-look window: smaller, rounded, follows Windows dark mode
- Feedback & bugs: send a note straight from the window
- Updates section with a Check for updates button
- The movies folder is set only from the phone app's Settings
- New program icon

## v1.3.0

- The window lists your paired phones by name: remove one or all, and pair a new phone by typing its name and tapping Pair
- The tray menu is now just Show and Quit - pairing lives in the window
- If Windows treats your Wi-Fi as "Public" (which blocks the phone), a popup offers to mark it Private in one click, open Settings, or not ask again for that Wi-Fi
- Already-paired phones carry over and show as "Phone 1", "Phone 2" and so on

## v1.2.0

**BingeMote-PC** (the installer) installs it with a Start menu entry and an optional desktop shortcut. Once installed, the program is just called **BingeMote** - window, tray, Start menu, everything.

- Setup stops springing an admin prompt on first run: the installer asks for Windows permission once, during install, and adds the firewall rule itself. Uninstalling removes the rule too
- New name: the old "BingeMote Helper" is now just **BingeMote** - window, tray, Start menu and installer. Install over the top of 1.x; it replaces the old copy and removes the old autostart entry, shortcuts and firewall rule.
- Friendlier setup messages, and the Wi-Fi help now says plainly what to click when Windows calls your home network Public
- When BingeMote starts VLC for you, VLC's remote-control port listens only on the address your phone used - not on every network
- Pair more than one phone: each phone pairs with its own code, and pairing can be reset to forget every phone (in 1.2.0 these lived in the tray menu; from 1.3.0 they are in the window)
- The status window refreshes every second while it is open - a pairing code shows the moment it exists - and sleeps while tucked in the tray
- Movies without VLC running: BingeMote lists the folder and starts the movie in VLC itself

- The window is now a small settings app: status, the movies folder with the usual Windows folder picker, pairing controls, and Quit - and it follows Windows dark/light mode
- Opening the helper while it is already running brings its window up instead of doing nothing
- Updating takes over from the running copy on its own. From 1.0.0 or 1.0.1: quit the old copy by hand first (right-click its tray icon, Quit)
- Pairing can be reset: forgets the phone and shows a fresh code
- Keeps a small log file (%LocalAppData%\BingeMoteHelper\helper.log) to help with setup problems

## v1.0.1

- The helper checks for updates too and tells you from the tray when a new version is out. Nothing installs without your OK.

## First release

- Listens for the paired phone and plays back mouse, scroll, zoom, keys and typing
- Windows system volume and mute
- One-time pairing code, Private-networks-only firewall rule, starts with Windows in the tray
