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
; Neutralized blind watchdog-task delete. Provenance-verified deletion (one Exec action,
; Command == {app}\BingeMote.exe, Arguments exactly --ensure-running, Principal == the
; uninstalling account) runs in Code at usUninstall; this inert entry supersedes the
; blind "schtasks /Delete" older chains recorded under the same RunOnceId, so a foreign
; same-name task can never be deleted by a merged uninstall log. RunOnceId "delwatchdog"
; (exact, lowercase) is reserved for this inert entry.
Filename: "{cmd}"; Parameters: "/c exit 0"; Flags: runhidden waituntilterminated; RunOnceId: "delwatchdog"
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
//
// Locked-uninstall repair (bounded, current product only):
//  - ssInstall: best-effort updating marker in the ORIGINAL user's data dir (mirrors
//    Program.PickDataDir; a hint only, never assumed invariant), provenance-verified
//    watchdog-task delete (the actual relaunch suppression), then held-handle
//    verified helper stop. A helper that cannot be verified as stopped ABORTS the
//    install loudly while rollback is still safe.
//  - usUninstall, in order: delete the watchdog task only after provenance checks
//    (exactly one Exec action, Command == {app}\BingeMote.exe, Arguments exactly
//    "--ensure-running", Principal UserId == the uninstalling account's SID); then the
//    same verified-handle helper stop; then the existing --uninstall-tls Exec. Mid-
//    uninstall there is no rollback, so a surviving helper proceeds with a loud log
//    line instead of a stall; file residue is Inno-reported.
//  - The [UninstallRun] delwatchdog entry is neutralized to an inert supersession
//    entry (same pattern as delacl): a blind by-name delete would bypass the
//    provenance refusal and could delete a foreign account's same-name task.
//  - Verification fails CLOSED: an unverifiable candidate process (OpenProcess or
//    image-path read failure) counts as a surviving helper, and the task XML is
//    validated with a real parser requiring exactly one Principals/Principal/UserId
//    and exactly one Actions/Exec - any other structure is refused.
//  - ExecAsOriginalUser is install-time only (Inno raises NoUninstallFuncError in the
//    uninstaller), so the uninstall-side delete runs elevated but ONLY after the
//    principal/path/arguments checks above - it can never touch a different account's
//    same-name task, and a cross-account uninstall leaves the task with a loud log.

const
  BM_PROCESS_TERMINATE = $0001;
  BM_PROCESS_QUERY_LIMITED = $1000;
  BM_SYNCHRONIZE = $00100000;
  BM_EVENT_MODIFY_STATE = $0002;
  BM_WAIT_OBJECT_0 = 0;
  BM_TH32CS_SNAPPROCESS = 2;
  BM_INVALID_HANDLE_VALUE = $FFFFFFFF;

type
  TBMProcessEntry32 = record
    dwSize: DWORD;
    cntUsage: DWORD;
    th32ProcessID: DWORD;
    th32DefaultHeapID: DWORD;
    th32ModuleID: DWORD;
    cntThreads: DWORD;
    th32ParentProcessID: DWORD;
    pcPriClassBase: LongInt;
    dwFlags: DWORD;
    szExeFile: array[0..259] of Char;
  end;

function BMOpenProcess(dwDesiredAccess: DWORD; bInheritHandle: BOOL; dwProcessId: DWORD): THandle;
  external 'OpenProcess@kernel32.dll stdcall';
function BMQueryFullProcessImageName(hProcess: THandle; dwFlags: DWORD; lpExeName: String; var lpdwSize: DWORD): BOOL;
  external 'QueryFullProcessImageNameW@kernel32.dll stdcall';
function BMCloseHandle(hObject: THandle): BOOL;
  external 'CloseHandle@kernel32.dll stdcall';
function BMWaitForSingleObject(hHandle: THandle; dwMilliseconds: DWORD): DWORD;
  external 'WaitForSingleObject@kernel32.dll stdcall';
function BMTerminateProcess(hProcess: THandle; uExitCode: DWORD): BOOL;
  external 'TerminateProcess@kernel32.dll stdcall';
function BMOpenEvent(dwDesiredAccess: DWORD; bInheritHandle: BOOL; lpName: String): THandle;
  external 'OpenEventW@kernel32.dll stdcall';
function BMSetEvent(hEvent: THandle): BOOL;
  external 'SetEvent@kernel32.dll stdcall';
function BMCreateToolhelp32Snapshot(dwFlags: DWORD; th32ProcessID: DWORD): THandle;
  external 'CreateToolhelp32Snapshot@kernel32.dll stdcall';
function BMProcess32First(hSnapshot: THandle; var lppe: TBMProcessEntry32): BOOL;
  external 'Process32FirstW@kernel32.dll stdcall';
function BMProcess32Next(hSnapshot: THandle; var lppe: TBMProcessEntry32): BOOL;
  external 'Process32NextW@kernel32.dll stdcall';

function BMExeName(const pe: TBMProcessEntry32): String;
var
  i: Integer;
begin
  Result := '';
  for i := 0 to 259 do
  begin
    if pe.szExeFile[i] = #0 then Break;
    Result := Result + pe.szExeFile[i];
  end;
end;

// Stops every running copy whose image path, verified on the HELD handle, equals
// AppExe. Quit event first (in-session only), terminate as fallback, bounded waits,
// PID-reuse-safe because the handle is never released in between. Returns True when
// no verified copy survives.
function BMStopHelperVerified(const AppExe: String; const SignalQuit: Boolean): Boolean;
var
  snap, hProc, hEvt: THandle;
  pe: TBMProcessEntry32;
  ok: BOOL;
  img: String;
  imgSize: DWORD;
  stopped: Boolean;
begin
  Result := True;
  snap := BMCreateToolhelp32Snapshot(BM_TH32CS_SNAPPROCESS, 0);
  if (snap = 0) or (snap = BM_INVALID_HANDLE_VALUE) then
  begin
    Log('helper-stop: process snapshot failed - treating as unverifiable, no stop attempted');
    Result := False;
    exit;
  end;
  pe.dwSize := SizeOf(TBMProcessEntry32);
  ok := BMProcess32First(snap, pe);
  while ok do
  begin
    if CompareText(BMExeName(pe), 'BingeMote.exe') = 0 then
    begin
      hProc := BMOpenProcess(BM_PROCESS_TERMINATE or BM_PROCESS_QUERY_LIMITED or BM_SYNCHRONIZE, False, pe.th32ProcessID);
      if hProc = 0 then
      begin
        Log(Format('helper-stop: OpenProcess failed for pid %d - unverifiable, treated as a surviving helper', [pe.th32ProcessID]));
        Result := False; // fail closed: an unverifiable candidate blocks install, logs mid-uninstall
      end
      else
      begin
        imgSize := 260;
        SetLength(img, 260);
        if BMQueryFullProcessImageName(hProc, 0, img, imgSize) then
        begin
          SetLength(img, imgSize);
          if CompareText(img, AppExe) = 0 then
          begin
            // Verified on the held handle: this process IS {app}\BingeMote.exe.
            stopped := BMWaitForSingleObject(hProc, 0) = BM_WAIT_OBJECT_0;
            if (not stopped) and SignalQuit then
            begin
              hEvt := BMOpenEvent(BM_EVENT_MODIFY_STATE, False, 'Local\BingeMoteHelper.Quit');
              if hEvt <> 0 then
              begin
                BMSetEvent(hEvt);
                stopped := BMWaitForSingleObject(hProc, 5000) = BM_WAIT_OBJECT_0;
                BMCloseHandle(hEvt);
              end;
            end;
            if not stopped then
            begin
              BMTerminateProcess(hProc, 1);
              stopped := BMWaitForSingleObject(hProc, 5000) = BM_WAIT_OBJECT_0;
            end;
            if stopped then
              Log(Format('helper-stop: verified %s (pid %d) stopped', [AppExe, pe.th32ProcessID]))
            else
            begin
              Log(Format('helper-stop: verified %s (pid %d) SURVIVED quit signal and terminate', [AppExe, pe.th32ProcessID]));
              Result := False;
            end;
          end
          else
            Log(Format('helper-stop: pid %d image "%s" is not %s - foreign, left alone', [pe.th32ProcessID, img, AppExe]));
        end
        else
        begin
          Log(Format('helper-stop: image path unreadable for pid %d - unverifiable, treated as a surviving helper', [pe.th32ProcessID]));
          Result := False; // fail closed, same rule
        end;
        BMCloseHandle(hProc);
      end;
    end;
    ok := BMProcess32Next(snap, pe);
  end;
  BMCloseHandle(snap);
end;

// Writes the 3-minute self-expiring "updating" marker in the ORIGINAL user's data dir.
// Mirrors Program.PickDataDir exactly: the new folder once it exists, else the 1.x
// folder while it still exists, else the new folder. Best-effort only: the stop paths
// never depend on it and a normal helper start clears it.
procedure BMWriteUpdatingMarker;
var
  rc: Integer;
  cmd: String;
begin
  // No delayed expansion: a "!" in %LOCALAPPDATA% would corrupt !var! expansion, so
  // each branch uses the literal expanded path inline. Mirrors Program.PickDataDir:
  // the new folder once it exists, else the 1.x folder while it still exists, else
  // the new folder (created here).
  cmd := '/c (if exist "%LOCALAPPDATA%\BingeMote" (> "%LOCALAPPDATA%\BingeMote\updating" echo updating) '
    + 'else (if exist "%LOCALAPPDATA%\BingeMoteHelper" (> "%LOCALAPPDATA%\BingeMoteHelper\updating" echo updating) '
    + 'else (mkdir "%LOCALAPPDATA%\BingeMote" & > "%LOCALAPPDATA%\BingeMote\updating" echo updating)))';
  if ExecAsOriginalUser(ExpandConstant('{sys}\cmd.exe'), cmd, '', SW_HIDE, ewWaitUntilTerminated, rc) and (rc = 0) then
    Log('update-suppression marker written in the original user''s data dir')
  else
    Log(Format('update-suppression marker write failed (rc %d) - continuing; stop paths do not depend on it', [rc]));
end;

// Validates the watchdog task XML with a real parser (MSXML 6, present on every
// supported Windows): exactly one Principals/Principal/UserId node, exactly one
// Actions/Exec node, Command == AppExe, Arguments exactly "--ensure-running",
// principal UserId == Sid. local-name() matching is namespace-safe. Fails CLOSED
// on any parse error, missing/extra structure, or parser unavailability.
function BMVerifyTaskXml(const Xml, AppExe, Sid: String; out Why: String): Boolean;
var
  doc, principals, execs, cmd, args: OleVariant;
  cmdText, argsText, uidText: String;
begin
  Result := False;
  try
    doc := CreateOleObject('Msxml2.DOMDocument.6.0');
    doc.async := False;
    doc.validateOnParse := False;
    doc.resolveExternals := False;
    if not doc.loadXML(Xml) then
    begin
      Why := 'task XML rejected by parser';
      exit;
    end;
    principals := doc.selectNodes('//*[local-name()="Principals"]/*[local-name()="Principal"]/*[local-name()="UserId"]');
    if principals.length <> 1 then
    begin
      Why := Format('expected exactly one Principals/Principal/UserId, found %d', [principals.length]);
      exit;
    end;
    execs := doc.selectNodes('//*[local-name()="Actions"]/*[local-name()="Exec"]');
    if execs.length <> 1 then
    begin
      Why := Format('expected exactly one Actions/Exec, found %d', [execs.length]);
      exit;
    end;
    cmd := execs.item(0).selectSingleNode('./*[local-name()="Command"]');
    args := execs.item(0).selectSingleNode('./*[local-name()="Arguments"]');
    if VarIsNull(cmd) or VarIsNull(args) then
    begin
      Why := 'Exec is missing Command or Arguments';
      exit;
    end;
    cmdText := String(cmd.text);
    argsText := String(args.text);
    uidText := String(principals.item(0).text);
  except
    Why := 'XML parser unavailable or raised - refusing';
    exit;
  end;
  if CompareText(cmdText, AppExe) <> 0 then
    Why := Format('Command "%s" is not %s', [cmdText, AppExe])
  else if argsText <> '--ensure-running' then
    Why := Format('Arguments "%s" are not --ensure-running', [argsText])
  else if CompareText(uidText, Sid) <> 0 then
    Why := Format('Principal UserId "%s" is not the uninstalling/installing account %s', [uidText, Sid])
  else
    Result := True;
end;

function BMReadUtf16File(const FileName: String): String;
var
  a: AnsiString;
  i, n: Integer;
begin
  Result := '';
  if not LoadStringFromFile(FileName, a) then exit;
  i := 1;
  n := Length(a);
  if (n >= 2) and (Ord(a[1]) = $FF) and (Ord(a[2]) = $FE) then i := 3;
  while i + 1 <= n do
  begin
    Result := Result + Chr(Ord(a[i]) or (Ord(a[i + 1]) shl 8));
    i := i + 2;
  end;
end;

// SID of the account running this uninstaller (whoami is ASCII-safe in CSV form).
function BMCurrentUserSid(const TmpFile: String): String;
var
  rc, p1, p2: Integer;
  a: AnsiString;
  s: String;
begin
  Result := '';
  if Exec(ExpandConstant('{sys}\cmd.exe'), '/c whoami /user /fo csv /nh > "' + TmpFile + '"', '', SW_HIDE, ewWaitUntilTerminated, rc)
     and (rc = 0) and LoadStringFromFile(TmpFile, a) then
  begin
    s := String(a);
    p1 := Pos('","', s);
    if p1 > 0 then
    begin
      p2 := p1 + 3;
      while p2 <= Length(s) do
      begin
        if s[p2] = '"' then Break;
        p2 := p2 + 1;
      end;
      if p2 <= Length(s) then Result := Copy(s, p1 + 3, p2 - p1 - 3);
    end;
  end;
  DeleteFile(TmpFile);
end;

// Deletes the watchdog task only when it provably is the helper's own task for THIS
// install path and THIS account. Anything else is foreign state: left, logged loudly.
procedure BMDeleteWatchdogVerified(const AppExe: String);
var
  rc: Integer;
  tmpXml, xml, sid, why: String;
begin
  sid := BMCurrentUserSid(ExpandConstant('{tmp}\bingemote-whoami.txt'));
  tmpXml := ExpandConstant('{tmp}\bingemote-task-query.xml');
  if (not Exec(ExpandConstant('{sys}\cmd.exe'), '/c schtasks /Query /TN "BingeMote Watchdog" /XML > "' + tmpXml + '"', '', SW_HIDE, ewWaitUntilTerminated, rc))
     or (rc <> 0) then
  begin
    Log('watchdog-delete: query failed or task absent - nothing to delete');
    exit;
  end;
  xml := BMReadUtf16File(tmpXml);
  DeleteFile(tmpXml);
  if xml = '' then
  begin
    Log('watchdog-delete: task XML unreadable - task left alone');
    exit;
  end;
  if sid = '' then
  begin
    Log('watchdog-delete: uninstalling account SID unresolved - task left alone');
    exit;
  end;
  why := '';
  if not BMVerifyTaskXml(xml, ExpandConstant('{app}\BingeMote.exe'), sid, why) then
  begin
    Log('watchdog-delete: REFUSED - ' + why + ' - task left alone');
    exit;
  end;
  if Exec(ExpandConstant('{sys}\schtasks.exe'), '/Delete /TN "BingeMote Watchdog" /F', '', SW_HIDE, ewWaitUntilTerminated, rc) and (rc = 0) then
    Log('watchdog-delete: provenance-verified task deleted')
  else
    Log(Format('watchdog-delete: verified delete failed (rc %d) - task left; no blind backstop will remove it', [rc]));
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    // Pre-file, rollback-safe point: the marker is only a best-effort hint; the actual
    // relaunch suppression is the provenance-verified task delete below (the helper
    // re-registers the watchdog on its next normal start). A foreign/unverifiable
    // task is left, and the verified stop still aborts a surviving helper.
    BMWriteUpdatingMarker;
    BMDeleteWatchdogVerified(ExpandConstant('{app}\BingeMote.exe'));
    if not BMStopHelperVerified(ExpandConstant('{app}\BingeMote.exe'), True) then
    begin
      Log('install aborted: a verified helper copy could not be stopped pre-file');
      SuppressibleMsgBox('BingeMote is still running and could not be closed. Please quit it (right-click the tray icon, choose Quit) and run this installer again.',
        mbCriticalError, MB_OK, IDOK);
      Abort;
    end;
  end
  else if CurStep = ssPostInstall then
    // Best-effort retouch: keep the suppression hint fresh through a long install.
    BMWriteUpdatingMarker;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Code: Integer;
begin
  if CurUninstallStep = usUninstall then
  begin
    // Mid-uninstall there is no rollback: provenance-delete the relauncher first,
    // stop the helper on verified handles, proceed with a loud log either way.
    BMDeleteWatchdogVerified(ExpandConstant('{app}\BingeMote.exe'));
    if not BMStopHelperVerified(ExpandConstant('{app}\BingeMote.exe'), True) then
      Log('helper-stop: a verified helper survived - proceeding; file residue will be reported by the uninstaller');
    if FileExists(ExpandConstant('{app}\BingeMote.exe')) then
      Exec(ExpandConstant('{app}\BingeMote.exe'), '--uninstall-tls', '', SW_HIDE, ewWaitUntilTerminated, Code);
  end;
end;
