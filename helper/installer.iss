; BingeMote PC installer (Inno Setup, free). Per-user install: no admin needed.
; Builds BingeMote-PC-Setup-<version>.exe from the published single-file exe.
#ifndef MyAppVersion
  #define MyAppVersion "1.2.0"
#endif

[Setup]
AppName=BingeMote PC
AppVersion={#MyAppVersion}
AppVerName=BingeMote PC {#MyAppVersion}
DefaultDirName={localappdata}\Programs\BingeMote
DefaultGroupName=BingeMote
PrivilegesRequired=lowest
OutputBaseFilename=BingeMote-PC-Setup-{#MyAppVersion}
SetupIconFile=Assets\bingemote.ico
UninstallDisplayIcon={app}\BingeMote PC.exe
CloseApplications=yes
RestartApplications=no
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Add a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "out\BingeMote PC.exe"; DestDir: "{app}"; Flags: ignoreversion

[Registry]
; The installer owns the autostart entry so uninstall removes it; the app rewrites the same value.
Root: HKCU; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "BingeMotePC"; ValueData: """{app}\BingeMote PC.exe"""; Flags: uninsdeletevalue
; Drop the 1.x autostart entry name and the 1.x exe and shortcuts on upgrade.
Root: HKCU; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "BingeMoteHelper"; Flags: deletevalue

[Icons]
Name: "{userprograms}\BingeMote PC"; Filename: "{app}\BingeMote PC.exe"
Name: "{userdesktop}\BingeMote PC"; Filename: "{app}\BingeMote PC.exe"; Tasks: desktopicon

[InstallDelete]
Type: files; Name: "{app}\BingeMoteHelper.exe"
Type: files; Name: "{userprograms}\BingeMote Helper.lnk"
Type: files; Name: "{userdesktop}\BingeMote Helper.lnk"

[Run]
Filename: "{app}\BingeMote PC.exe"; Description: "Open BingeMote PC"; Flags: nowait postinstall skipifsilent
