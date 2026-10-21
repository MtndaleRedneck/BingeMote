; BingeMote Helper installer (Inno Setup, free). Per-user install: no admin needed.
; Builds BingeMoteHelper-Setup.exe from the published single-file exe.
#ifndef MyAppVersion
  #define MyAppVersion "1.0.2"
#endif

[Setup]
AppName=BingeMote Helper
AppVersion={#MyAppVersion}
AppVerName=BingeMote Helper {#MyAppVersion}
DefaultDirName={localappdata}\Programs\BingeMote
DefaultGroupName=BingeMote
PrivilegesRequired=lowest
OutputBaseFilename=BingeMoteHelper-Setup-{#MyAppVersion}
SetupIconFile=Assets\bingemote.ico
UninstallDisplayIcon={app}\BingeMoteHelper.exe
CloseApplications=yes
RestartApplications=no
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Add a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "out\BingeMoteHelper.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\BingeMote Helper"; Filename: "{app}\BingeMoteHelper.exe"
Name: "{userdesktop}\BingeMote Helper"; Filename: "{app}\BingeMoteHelper.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\BingeMoteHelper.exe"; Description: "Open BingeMote Helper"; Flags: nowait postinstall skipifsilent
