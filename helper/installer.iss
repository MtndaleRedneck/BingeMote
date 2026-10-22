; BingeMote for Windows installer (Inno Setup, free). Asks for admin once, during install,
; so it can add the firewall rule itself and the app never needs a surprise admin prompt.
; Builds the BingeMote-PC-<version>.exe installer download from the published single-file exe.
#ifndef MyAppVersion
  #define MyAppVersion "1.2.0"
#endif

[Setup]
AppName=BingeMote
AppVersion={#MyAppVersion}
AppVerName=BingeMote {#MyAppVersion}
DefaultDirName={localappdata}\Programs\BingeMote
DefaultGroupName=BingeMote
PrivilegesRequired=admin
OutputBaseFilename=BingeMote-PC-{#MyAppVersion}
SetupIconFile=Assets\bingemote.ico
UninstallDisplayIcon={app}\BingeMote.exe
CloseApplications=yes
RestartApplications=no
WizardStyle=modern

[Files]
Source: "out\BingeMote.exe"; DestDir: "{app}"; Flags: ignoreversion

[Registry]
; The installer owns the autostart entry so uninstall removes it; the app rewrites the same value.
Root: HKCU; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "BingeMote"; ValueData: """{app}\BingeMote.exe"" --autostart"; Flags: uninsdeletevalue
; Drop the 1.x autostart entry name and the 1.x exe and shortcuts on upgrade.
Root: HKCU; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "BingeMoteHelper"; Flags: deletevalue

[Icons]
Name: "{userprograms}\BingeMote"; Filename: "{app}\BingeMote.exe"; IconFilename: "{app}\BingeMote.exe"
Name: "{userdesktop}\BingeMote"; Filename: "{app}\BingeMote.exe"; IconFilename: "{app}\BingeMote.exe"

[InstallDelete]
Type: files; Name: "{app}\BingeMoteHelper.exe"
Type: files; Name: "{userprograms}\BingeMote Helper.lnk"
Type: files; Name: "{userdesktop}\BingeMote Helper.lnk"

[Run]
; The installer is already elevated, so it owns the firewall rule and the listen
; permission: no surprise admin prompt on first run. Old 1.x rule name removed too.
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""BingeMote Helper"""; Flags: runhidden waituntilterminated
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""BingeMote"""; Flags: runhidden waituntilterminated
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""BingeMote"" dir=in action=allow protocol=TCP localport=8488 profile=private"; Flags: runhidden waituntilterminated
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""BingeMote"" dir=in action=allow protocol=UDP localport=8489 profile=private"; Flags: runhidden waituntilterminated
Filename: "netsh"; Parameters: "http delete urlacl url=http://+:8488/"; Flags: runhidden waituntilterminated
Filename: "netsh"; Parameters: "http add urlacl url=http://+:8488/ sddl=D:(A;;GX;;;WD)"; Flags: runhidden waituntilterminated
Filename: "{app}\BingeMote.exe"; Description: "Open BingeMote"; Flags: nowait postinstall skipifsilent
; After a silent update, reopen the app for him as the signed-in user (not elevated).
Filename: "{app}\BingeMote.exe"; Flags: nowait skipifnotsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /TN ""BingeMote Watchdog"" /F"; Flags: runhidden waituntilterminated skipifdoesntexist; RunOnceId: "delwatchdog"
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""BingeMote"""; Flags: runhidden waituntilterminated; RunOnceId: "delrule"
Filename: "netsh"; Parameters: "http delete urlacl url=http://+:8488/"; Flags: runhidden waituntilterminated; RunOnceId: "delacl"
