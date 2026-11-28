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
; HTTP.sys URL reservation for interactively signed-in users (S-1-5-4) instead of
; Everyone. Partial hardening only: HTTP.sys will register the helper's URL for any
; interactive user and BingeMote no longer grants it to non-interactive identities, but
; another interactive user can still register the same URL, and raw TCP binding of the
; port is unaffected. INTERACTIVE, not the installing account's SID, because a standard
; user elevating with admin credentials still runs the helper as themselves.
Filename: "netsh"; Parameters: "http add urlacl url=http://+:8488/ sddl=D:(A;;GX;;;S-1-5-4)"; Flags: runhidden waituntilterminated
; One-time secure setup (TLS identity, port bindings, HTTPS listen permission): the same
; elevated product step the first-run prompt would launch, so install asks for admin once
; and the app never needs a surprise prompt. Idempotent - same-path upgrades never run the
; old uninstaller (their log entries merge into the next real uninstall), so this simply
; re-verifies Ready or rebinds the existing identity.
Filename: "{app}\BingeMote.exe"; Parameters: "--install-firewall"; Flags: runhidden waituntilterminated
Filename: "{app}\BingeMote.exe"; Description: "Open BingeMote"; Flags: nowait postinstall skipifsilent
; After a silent update, reopen the app for him as the signed-in user (not elevated).
Filename: "{app}\BingeMote.exe"; Flags: nowait skipifnotsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /TN ""BingeMote Watchdog"" /F"; Flags: runhidden waituntilterminated skipifdoesntexist; RunOnceId: "delwatchdog"
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""BingeMote"""; Flags: runhidden waituntilterminated; RunOnceId: "delrule"
; Neutralized inherited cleanup. Every installer since the delacl entry was introduced
; recorded a blind "netsh http delete urlacl" here under RunOnceId "delacl", and merged
; uninstall logs execute only the LATEST entry per case-sensitive RunOnceId - so this inert
; entry supersedes the blind delete on any chain whose latest install is this build or newer.
; HTTP removal at uninstall is owned by the helper's classified cleanup, never by a blind
; [UninstallRun] command. The RunOnceId value "delacl" (exact, lowercase) is reserved for
; this inert entry; scripts/iss-lint.ps1 fails the build if a destructive uninstall command
; or an un-ID'd [UninstallRun] entry ever returns.
Filename: "{cmd}"; Parameters: "/c exit 0"; Flags: runhidden waituntilterminated; RunOnceId: "delacl"

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
