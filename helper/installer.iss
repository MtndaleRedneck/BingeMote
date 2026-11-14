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
; rev14 (Instinct 2 publish blocker): the SYSTEM input-broker task runs this exe, so it
; cannot live in a user-writable per-user dir - a normal user could swap it and own
; SYSTEM at the next task launch. Install per-machine under Program Files (install is
; already elevated for the firewall rule and the task) with an explicit ACL:
; SYSTEM/Administrators full, authenticated users read+execute only. Program Files
; defaults to that already; the explicit ACL keeps it true on nonstandard machines.
DefaultDirName={commonpf}\BingeMote
DefaultGroupName=BingeMote
PrivilegesRequired=admin
; 64-bit install mode so {commonpf} is C:\Program Files (not the x86 view the
; 32-bit installer would otherwise target). The exe is x64 self-contained.
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputBaseFilename=BingeMote-PC-{#MyAppVersion}
SetupIconFile=Assets\bingemote.ico
UninstallDisplayIcon={app}\BingeMote.exe
CloseApplications=yes
RestartApplications=no
WizardStyle=modern

[Dirs]
; The exe the SYSTEM task launches and everything beside it: admins/SYSTEM write,
; everyone else read+execute. (The single-file exe is the whole payload - no loose
; DLLs or runtimeconfig a user could swap.)
Name: "{app}"; Permissions: admins-full system-full authusers-readexec

[Files]
Source: "out\BingeMote.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "register-broker.ps1"; DestDir: "{app}"; Flags: ignoreversion

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
; rev11: register the SYSTEM input broker task (mouse freeze fix). The installer is
; already elevated, so the task is created without any extra prompt; the broker
; itself starts at the next sign-in.
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\register-broker.ps1"""; Flags: runhidden waituntilterminated
Filename: "{app}\BingeMote.exe"; Description: "Open BingeMote"; Flags: nowait postinstall skipifsilent
; After a silent update, reopen the app for him as the signed-in user (not elevated).
Filename: "{app}\BingeMote.exe"; Flags: nowait skipifnotsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /TN ""BingeMote Watchdog"" /F"; Flags: runhidden waituntilterminated skipifdoesntexist; RunOnceId: "delwatchdog"
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /TN ""BingeMote Input Broker"" /F"; Flags: runhidden waituntilterminated skipifdoesntexist; RunOnceId: "delbroker"
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""BingeMote"""; Flags: runhidden waituntilterminated; RunOnceId: "delrule"
Filename: "netsh"; Parameters: "http delete urlacl url=http://+:8488/"; Flags: runhidden waituntilterminated; RunOnceId: "delacl"
