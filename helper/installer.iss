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
; Firewall rules, the HTTPS listen permission and the TLS identity are all created by the
; product's own --install-firewall step below (create-only, new names, port 8490). This
; installer no longer deletes or adds any firewall rule or http:// reservation: the
; pre-1.10 "BingeMote" rules and the http://+:8488/ reservation are left exactly as found.
; One-time secure setup (TLS identity, port bindings, HTTPS listen permission): the same
; elevated product step the first-run prompt would launch, so install asks for admin once
; and the app never needs a surprise prompt. Idempotent - same-path upgrades never run the
; old uninstaller (their log entries merge into the next real uninstall), so this simply
; re-verifies Ready or rebinds the existing identity.
Filename: "{app}\BingeMote.exe"; Parameters: "--install-firewall"; Flags: runhidden waituntilterminated
; Relaunch task for the installing user only (limited token, admin-only write), new name, create-only.
; Skips with a log line when the installing account is not the signed-in user.
Filename: "{app}\BingeMote.exe"; Parameters: "--install-watchdog"; Flags: runhidden waituntilterminated
Filename: "{app}\BingeMote.exe"; Description: "Open BingeMote"; Flags: nowait postinstall skipifsilent
; After a silent update, reopen the app for him as the signed-in user (not elevated).
Filename: "{app}\BingeMote.exe"; Flags: nowait skipifnotsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\cmd.exe"; Parameters: "/c exit 0"; Flags: runhidden waituntilterminated; RunOnceId: "delwatchdog"
; Inno runs only the NEWEST entry per RunOnceId at uninstall, so these same-id inert entries
; shadow the blind deletes that 1.9.x installs left in the merged uninstall log. Nothing is
; deleted by name any more, including the legacy fixed-name "BingeMote Watchdog" task, which is
; never read, changed or removed by this installer (its fate is an open product decision).
Filename: "{sys}\cmd.exe"; Parameters: "/c exit 0"; Flags: runhidden waituntilterminated; RunOnceId: "delrule"
Filename: "{sys}\cmd.exe"; Parameters: "/c exit 0"; Flags: runhidden waituntilterminated; RunOnceId: "delacl"

[Code]
// TLS transport cleanup runs as the newest install's own compiled logic, while the exe
// still exists. A merged-log [UninstallRun] entry would instead be inherited from every
// appended install and fire unordered against the newest state.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Code: Integer;
begin
  if CurUninstallStep = usUninstall then
    if FileExists(ExpandConstant('{app}\BingeMote.exe')) then
      Exec(ExpandConstant('{app}\BingeMote.exe'), '--uninstall-tls', '', SW_HIDE, ewWaitUntilTerminated, Code);
end;
