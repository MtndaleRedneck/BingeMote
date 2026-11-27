using System.Diagnostics;
using System.Net;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace BingeMoteHelper;

// PRIVATE SOURCE, NOT COMPILED, NOT RUN. No Windows runner, no .NET toolchain and no elevated session existed when this was written.
// Every Windows-facing member is an unverified draft until it has run on a real machine. See HelperTlsTests.cs for the unrun list.

internal sealed record TlsIdentityMarker(string Thumbprint, string SpkiSha256Hex, string AppId, int Port, string CreatedUtc);

/// <summary>Public metadata about the identity. Absent (no file) is distinct from Corrupt (a file exists but cannot be trusted).</summary>
public enum MarkerState { Absent, Present, Corrupt }

/// <summary>Present: the public certificate is in the store with a key reference. Whether the key is USABLE is only checked by the elevated side.</summary>
public enum CertState { Absent, Present, KeyMissing, AccessError }
internal enum BindingState { Absent, ExactExpected, StaleOwned, Foreign, Unknown }
internal enum TlsAction { Ready, BindMissing, GenerateFresh, UpdateStale, FailClosed }

internal sealed record TlsObservation(
    MarkerState Marker,
    TlsIdentityMarker? M,
    CertState Cert,
    bool CertMatchesMarker,
    bool OrphanOurCertWithoutMarker,
    BindingState V4,
    BindingState V6,
    bool SpecificIpBindingOnPort,
    bool RenewalDue,
    bool ExplicitSetup,
    bool Ipv6Capable = true,
    string Diag = "");

/// <summary>PROPOSAL, default strict (both families required). If ever approved, set true: with no IPv6 stack, a missing [::] binding is not required. It never applies to a present-but-wrong binding. http.sys behavior with IPv6 disabled is UNVERIFIED.</summary>
internal static class TlsPolicy
{
    internal const bool V6OptionalWhenStackAbsent = false;
    internal static bool V6Required(bool ipv6Capable) => ipv6Capable || !V6OptionalWhenStackAbsent;
}

/// <summary>Notes carry cases the planner deliberately does not decide (unverified precedence), so they stay visible.</summary>
internal sealed record TlsPlan(TlsAction Action, string Reason, bool BindV4 = false, bool BindV6 = false, bool RenewDue = false, string Notes = "", bool UpdateV4 = false, bool UpdateV6 = false);

internal static class TlsLifetime
{
    internal static readonly TimeSpan Validity = TimeSpan.FromDays(2 * 365);
    internal static readonly TimeSpan RenewWindow = TimeSpan.FromDays(60);
    internal static bool IsRenewalDue(DateTimeOffset notAfter, DateTimeOffset now) => notAfter - now <= RenewWindow;
}

/// <summary>
/// Ownership and lifecycle rules as a pure function. Anything ambiguous fails closed: no regeneration, no deletion,
/// no plaintext fallback. A corrupt or unreadable marker is NOT "no marker": it never leads to a new identity.
/// </summary>
internal static class TlsPlanner
{
    internal const string UnverifiedNote = "Not verified: http.sys precedence for SNI and central-store bindings (not enumerated) and for specific-IP bindings.";

    public static TlsPlan Plan(TlsObservation o)
    {
        if (o.Marker == MarkerState.Corrupt) return Fail("the identity marker is unreadable or corrupt; the identity is not regenerated");
        if (o.V4 == BindingState.Foreign || o.V6 == BindingState.Foreign) return Fail("a binding on the port is not provably this install's" + (o.Diag.Length > 0 ? ": " + o.Diag : ""));
        if (o.V4 == BindingState.Unknown || o.V6 == BindingState.Unknown) return Fail("the binding state could not be read");
        if (o.Cert == CertState.AccessError) return Fail("the certificate store or key could not be read");

        var v6req = TlsPolicy.V6Required(o.Ipv6Capable);
        if (!v6req && o.V6 == BindingState.Absent) o = o with { V6 = BindingState.ExactExpected };   // not required under the (default-off) proposal; a present wrong binding is still Foreign above
        var notes = UnverifiedNote + (v6req ? "" : " IPv6 stack absent: [::] binding not required (proposal policy).") + (o.SpecificIpBindingOnPort ? " A specific-IP binding exists on the port." : "");

        if (o.Marker == MarkerState.Absent)
        {
            if (o.OrphanOurCertWithoutMarker) return Fail("a certificate that looks like ours exists but its marker does not");
            if (o.V4 != BindingState.Absent || o.V6 != BindingState.Absent) return Fail("a binding exists but this install has no identity marker for it");
            if (!o.ExplicitSetup) return Fail("secure setup has not been run");
            if (o.SpecificIpBindingOnPort) return Fail("a specific-IP binding exists on the port; coexistence is not verified", notes: notes);
            return new TlsPlan(TlsAction.GenerateFresh, "first setup", true, v6req, Notes: notes);
        }

        switch (o.Cert)
        {
            case CertState.KeyMissing: return Fail("the certificate is present but its private key is not");
            case CertState.Absent:
                if (!o.ExplicitSetup) return Fail("the expected certificate is absent; run setup again");
                if (o.SpecificIpBindingOnPort) return Fail("a specific-IP binding exists on the port; coexistence is not verified", notes: notes);
                // Prior marker, certificate absent. Families whose binding is absent are added; families still bound to the prior thumbprint (ours by marker) are UPDATED to the new thumbprint. A new key changes the pinned key: the phone must re-pair, this is the explicit regeneration path.
                return new TlsPlan(TlsAction.GenerateFresh, "expected certificate is absent", o.V4 == BindingState.Absent, o.V6 == BindingState.Absent && v6req, Notes: notes,
                    UpdateV4: o.V4 != BindingState.Absent, UpdateV6: o.V6 != BindingState.Absent && v6req);
        }
        if (!o.CertMatchesMarker) return Fail("the certificate in the store does not match the identity marker");

        bool stale4 = o.V4 == BindingState.StaleOwned, stale6 = o.V6 == BindingState.StaleOwned;
        bool needV4 = o.V4 == BindingState.Absent, needV6 = o.V6 == BindingState.Absent;
        if (stale4 || stale6)
        {
            if (!o.ExplicitSetup) return Fail("a binding still points at an earlier certificate of this install; run setup again");
            if (o.SpecificIpBindingOnPort) return Fail("coexistence on the port is not verified", notes: notes);
            return new TlsPlan(TlsAction.UpdateStale, "binding points at an earlier same-key certificate of this install", needV4, needV6, o.RenewalDue, notes + " StaleV4=" + stale4 + " StaleV6=" + stale6);
        }
        if (!needV4 && !needV6) return new TlsPlan(TlsAction.Ready, "exact bindings present", RenewDue: o.RenewalDue, Notes: notes);
        if (!o.ExplicitSetup) return Fail("a binding is missing; run setup again");
        if (o.SpecificIpBindingOnPort) return Fail("a specific-IP binding exists on the port; coexistence is not verified", notes: notes);
        return new TlsPlan(TlsAction.BindMissing, "binding missing", needV4, needV6 && v6req, o.RenewalDue, notes);
    }

    private static TlsPlan Fail(string why, string notes = "") => new(TlsAction.FailClosed, why, Notes: notes);
}

/// <summary>What to do with the old http:// reservation when the https:// one is created. Planning only; nothing calls it with side effects yet.</summary>
internal static class UrlAclMigrationPlanner
{
    internal enum Action { None, DeleteOldHttpReservation, LeaveForeign }
    internal static Action PlanOld(FirewallSetup.AclOwnership oldHttp) => oldHttp switch
    {
        FirewallSetup.AclOwnership.Ours or FirewallSetup.AclOwnership.Legacy => Action.DeleteOldHttpReservation,
        FirewallSetup.AclOwnership.Foreign => Action.LeaveForeign,
        _ => Action.None,
    };
}

/// <summary>Files written only by the elevated side: atomic replace, then a DACL of SYSTEM and Administrators full control, Users read-only.</summary>
internal static class ProtectedFile
{
    internal static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "BingeMote");

    /// <summary>True only when the directory exists, is owned by SYSTEM, Administrators or TrustedInstaller, and no non-admin principal can write or change it. A foreign directory is NEVER repaired here.</summary>
    internal static bool DirTrusted(string dir)
    {
        try
        {
            var sec = new DirectoryInfo(dir).GetAccessControl();
            var owner = sec.GetOwner(typeof(SecurityIdentifier)) as SecurityIdentifier;
            var ok = new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid };
            bool ownerOk = owner != null && (ok.Any(w => owner.IsWellKnown(w)) || owner.Value.StartsWith("S-1-5-80-"));   // S-1-5-80-* = service SIDs (TrustedInstaller)
            if (!ownerOk) return false;
            var writeish = FileSystemRights.WriteData | FileSystemRights.AppendData | FileSystemRights.WriteAttributes | FileSystemRights.WriteExtendedAttributes | FileSystemRights.Delete | FileSystemRights.DeleteSubdirectoriesAndFiles | FileSystemRights.ChangePermissions | FileSystemRights.TakeOwnership;
            foreach (FileSystemAccessRule r in sec.GetAccessRules(true, true, typeof(SecurityIdentifier)))
            {
                if (r.AccessControlType != AccessControlType.Allow || (r.FileSystemRights & writeish) == 0) continue;
                var s = (SecurityIdentifier)r.IdentityReference;
                if (!(s.IsWellKnown(WellKnownSidType.LocalSystemSid) || s.IsWellKnown(WellKnownSidType.BuiltinAdministratorsSid) || s.Value.StartsWith("S-1-5-80-") || s.Value == "S-1-3-0")) return false;   // CREATOR OWNER (S-1-3-0) is inheritance-only
            }
            return true;
        }
        catch { return false; }
    }

    internal static void WriteAtomic(string path, string text)
    {
        var dir = Path.GetDirectoryName(path)!;
        if (!Directory.Exists(dir))
        {
            var ds = new DirectorySecurity();
            ds.SetAccessRuleProtection(true, false);
            foreach (var (sid, rights) in new[] { (WellKnownSidType.LocalSystemSid, FileSystemRights.FullControl), (WellKnownSidType.BuiltinAdministratorsSid, FileSystemRights.FullControl), (WellKnownSidType.BuiltinUsersSid, FileSystemRights.ReadAndExecute) })
                ds.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null), rights, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            new DirectoryInfo(dir).Create(ds);
        }
        if (!DirTrusted(dir)) throw new InvalidOperationException("the data directory is not trusted (owner or ACL); it was not changed");
        var tmp = path + ".new";
        File.WriteAllText(tmp, text);
        var sec = new FileSecurity();
        sec.SetAccessRuleProtection(true, false);
        sec.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), FileSystemRights.FullControl, AccessControlType.Allow));
        sec.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), FileSystemRights.FullControl, AccessControlType.Allow));
        sec.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null), FileSystemRights.Read, AccessControlType.Allow));
        new FileInfo(tmp).SetAccessControl(sec);
        File.Move(tmp, path, true);
    }
}

internal static class TlsMarkerStore
{
    internal static string FilePath => Path.Combine(ProtectedFile.Dir, "tls-identity.json");

    internal static (MarkerState state, TlsIdentityMarker? marker) Read(string? path = null)
    {
        var p = path ?? FilePath;
        try
        {
            if (!File.Exists(p)) return (MarkerState.Absent, null);
            if (path == null && !ProtectedFile.DirTrusted(ProtectedFile.Dir)) return (MarkerState.Corrupt, null);
            var m = JsonSerializer.Deserialize<TlsIdentityMarker>(File.ReadAllText(p));
            if (m != null && Regex.IsMatch(m.Thumbprint ?? "", "^[0-9A-Fa-f]{40}$") && Regex.IsMatch(m.SpkiSha256Hex ?? "", "^[0-9a-f]{64}$")
                && Guid.TryParse(m.AppId, out _) && m.Port is > 0 and <= 65535) return (MarkerState.Present, m);
            return (MarkerState.Corrupt, null);
        }
        catch { return (MarkerState.Corrupt, null); }   // exists but unreadable: never read as "absent"
    }

    internal static void Write(TlsIdentityMarker m, string? path = null) => ProtectedFile.WriteAtomic(path ?? FilePath, JsonSerializer.Serialize(m));
}

/// <summary>Durable record of what an in-flight setup or renewal has changed, written BEFORE each mutation.</summary>
internal sealed record TlsJournal(string Kind, string? NewThumb, string? OldThumb, bool CertAdded, bool Bound4, bool Bound6, bool Updated4, bool Updated6, string StartedUtc);

internal static class TlsJournalStore
{
    internal static string FilePath => Path.Combine(ProtectedFile.Dir, "tls-journal.json");

    internal static (MarkerState state, TlsJournal? journal) Read(string? path = null)
    {
        var p = path ?? FilePath;
        try
        {
            if (!File.Exists(p)) return (MarkerState.Absent, null);
            if (path == null && !ProtectedFile.DirTrusted(ProtectedFile.Dir)) return (MarkerState.Corrupt, null);
            var j = JsonSerializer.Deserialize<TlsJournal>(File.ReadAllText(p));
            return j != null && (j.Kind is "Fresh" or "Bind" or "Renew") ? (MarkerState.Present, j) : (MarkerState.Corrupt, null);
        }
        catch { return (MarkerState.Corrupt, null); }
    }

    internal static void Write(TlsJournal j, string? path = null) => ProtectedFile.WriteAtomic(path ?? FilePath, JsonSerializer.Serialize(j));
    internal static void Delete(string? path = null) { try { File.Delete(path ?? FilePath); } catch { } }
}

/// <summary>
/// Structured http.sys reads (HttpQueryServiceConfiguration). No text parsing. UNRUN.
/// Layouts verified against Microsoft Learn (win32/api/http: HTTP_SERVICE_CONFIG_SSL_QUERY, _SSL_KEY, _SSL_SET, _SSL_PARAM,
/// _SSL_SNI_QUERY/_KEY/_SET, _SSL_CCS_QUERY/_KEY/_SET, HTTP_SERVICE_CONFIG_ID, _QUERY_TYPE, HttpQueryServiceConfiguration;
/// win32/api/ws2def SOCKADDR_STORAGE: ss_family, then an __int64 aligned member, 128 bytes) read in this session.
/// The SDK header itself was not available, so field OFFSETS are DERIVED from those declarations with natural MSVC alignment
/// (pointer-size alignment; SOCKADDR_STORAGE 8-aligned). That derivation is UNVERIFIED on a real machine: LayoutOk() and the
/// tests pin the numbers, and every read fails closed (Error, never Absent) if the pinned sizes do not hold.
/// Buffers are manual unmanaged memory (no marshalled structs); output pointers point INSIDE the output buffer, so records are
/// decoded before it is freed.
/// </summary>
internal static class HttpSysSsl
{
    internal enum Kind { Absent, Found, Error }
    internal sealed record Result(Kind Kind, string Thumbprint = "", string AppId = "", string Store = "", string Why = "");
    internal sealed record Entry(string Family, IPAddress? Address, int Port, string Host, string Thumbprint, string AppId, string Store);

    // HTTP_SERVICE_CONFIG_ID: HttpServiceConfigSSLCertInfo=1, HttpServiceConfigSslSniCertInfo=5, HttpServiceConfigSslCcsCertInfo=6 (enum order verified).
    internal const int IdSsl = 1, IdSni = 5, IdCcs = 6;
    // HTTP_SERVICE_CONFIG_QUERY_TYPE: Exact=0, Next=1.
    private const int QueryExact = 0, QueryNext = 1;
    private const uint InitConfig = 0x2;   // HTTP_INITIALIZE_CONFIG, same value FirewallSetup.QueryUrlAcl already uses
    private const uint ErrFileNotFound = 2, ErrInsufficient = 122, ErrMoreData = 234, ErrNoMoreItems = 259;

    [StructLayout(LayoutKind.Sequential)] private struct HttpApiVersion { public ushort Major; public ushort Minor; }
    [DllImport("httpapi.dll", ExactSpelling = true)] private static extern uint HttpInitialize(HttpApiVersion version, uint flags, IntPtr reserved);
    [DllImport("httpapi.dll", ExactSpelling = true)] private static extern uint HttpTerminate(uint flags, IntPtr reserved);
    [DllImport("httpapi.dll", ExactSpelling = true)]
    private static extern uint HttpQueryServiceConfiguration(IntPtr serviceHandle, int configId, IntPtr input, int inputLength, IntPtr output, int outputLength, out int returnLength, IntPtr overlapped);

    private static int P => IntPtr.Size;
    private const int StorageSize = 128, StorageAlign = 8;
    private static int Align(int v, int a) => (v + a - 1) / a * a;

    // Derived offsets (see header). SSL_QUERY { int QueryDesc; PSOCKADDR; DWORD token }.
    internal static int SslQueryToken => 2 * P;
    internal static int SslQuerySize => Align(2 * P + 4, P);
    // SNI/CCS query { int QueryDesc; SOCKADDR_STORAGE; [PWSTR Host]; DWORD token }.
    internal static int StorageOffset => Align(4, StorageAlign);                       // 8
    internal static int SniHostOffset => StorageOffset + StorageSize;                    // 136
    internal static int SniQueryToken => SniHostOffset + P;
    internal static int SniQuerySize => Align(SniQueryToken + 4, StorageAlign);
    internal static int CcsQueryToken => StorageOffset + StorageSize;
    internal static int CcsQuerySize => Align(CcsQueryToken + 4, StorageAlign);
    // SSL_PARAM { ULONG len; PVOID hash; GUID app; PWSTR store; ... } offsets from the start of ParamDesc.
    internal static int ParamHash => P;
    internal static int ParamApp => 2 * P;
    internal static int ParamStore => 2 * P + 16;
    // Output records: SSL_SET { PSOCKADDR; PARAM }, SNI_SET { STORAGE; PWSTR; PARAM }, CCS_SET { STORAGE; PARAM }.
    internal static int SslSetParam => Align(P, P);
    internal static int SniSetParam => Align(StorageSize + P, P);
    internal static int CcsSetParam => StorageSize;

    /// <summary>Pinned expectations for the supported architectures (x64 and x86). Anything else fails closed.</summary>
    internal static bool LayoutOk() =>
        (P == 8 && SslQuerySize == 24 && SniQuerySize == 152 && CcsQuerySize == 144 && SslSetParam == 8 && SniSetParam == 136 && CcsSetParam == 128 && ParamStore == 32) ||
        (P == 4 && SslQuerySize == 12 && SniQuerySize == 144 && CcsQuerySize == 144 && SslSetParam == 4 && SniSetParam == 132 && CcsSetParam == 128 && ParamStore == 24);

    private static IntPtr SockAddr(IPAddress ip, int port)
    {
        var v4 = ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork;
        var bytes = new byte[v4 ? 16 : 28];   // sockaddr_in / sockaddr_in6 sizes
        BitConverter.GetBytes(v4 ? (ushort)2 : (ushort)23).CopyTo(bytes, 0);   // AF_INET / AF_INET6
        bytes[2] = (byte)(port >> 8); bytes[3] = (byte)(port & 0xff);           // network byte order
        ip.GetAddressBytes().CopyTo(bytes, v4 ? 4 : 8);                         // sin_addr @4; sin6_addr @8 (after sin6_flowinfo)
        var p = Marshal.AllocHGlobal(bytes.Length); Marshal.Copy(bytes, 0, p, bytes.Length); return p;
    }

    private static Entry? DecodeParam(string family, IPAddress? addr, int port, string host, IntPtr param)
    {
        var len = (uint)Marshal.ReadInt32(param);
        var hashPtr = Marshal.ReadIntPtr(param, ParamHash);
        if (hashPtr == IntPtr.Zero || len == 0 || len > 64) return null;
        var hash = new byte[len]; Marshal.Copy(hashPtr, hash, 0, hash.Length);
        var g = new byte[16]; Marshal.Copy(param + ParamApp, g, 0, 16);
        var sp = Marshal.ReadIntPtr(param, ParamStore);
        return new Entry(family, addr, port, host, Convert.ToHexString(hash), new Guid(g).ToString(), sp == IntPtr.Zero ? "" : Marshal.PtrToStringUni(sp) ?? "");
    }

    private static (IPAddress? addr, int port) DecodeSockaddr(IntPtr sa)
    {
        var fam = (ushort)Marshal.ReadInt16(sa);
        var port = (Marshal.ReadByte(sa, 2) << 8) | Marshal.ReadByte(sa, 3);
        if (fam == 2) { var r = new byte[4]; Marshal.Copy(sa + 4, r, 0, 4); return (new IPAddress(r), port); }
        if (fam == 23) { var r = new byte[16]; Marshal.Copy(sa + 8, r, 0, 16); return (new IPAddress(r), port); }
        return (null, port);
    }

    private static Entry? DecodeSsl(IntPtr buf)
    {
        var sa = Marshal.ReadIntPtr(buf);
        if (sa == IntPtr.Zero) return null;
        var (a, port) = DecodeSockaddr(sa);
        return a == null ? null : DecodeParam("ip", a, port, "", buf + SslSetParam);
    }
    private static Entry? DecodeSni(IntPtr buf)
    {
        var (a, port) = DecodeSockaddr(buf);
        var h = Marshal.ReadIntPtr(buf, StorageSize);
        return DecodeParam("sni", a, port, h == IntPtr.Zero ? "" : Marshal.PtrToStringUni(h) ?? "", buf + SniSetParam);
    }
    private static Entry? DecodeCcs(IntPtr buf)
    {
        var (a, port) = DecodeSockaddr(buf);
        return DecodeParam("ccs", a, port, "", buf + CcsSetParam);
    }

    private static uint Call(int id, IntPtr input, int inputLen, Func<IntPtr, Entry?> decode, out Entry? entry)
    {
        entry = null;
        var size = 1024;
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var buf = Marshal.AllocHGlobal(size);
            try
            {
                var rc = HttpQueryServiceConfiguration(IntPtr.Zero, id, input, inputLen, buf, size, out var needed, IntPtr.Zero);
                if (rc == ErrInsufficient || rc == ErrMoreData) { if (needed > size) { size = needed; continue; } return 1; }
                if (rc != 0) return rc;
                entry = decode(buf);
                return entry == null ? 1u : 0u;   // 1 = undecodable, treated as error by callers
            }
            finally { Marshal.FreeHGlobal(buf); }
        }
        return 1;
    }

    /// <summary>Only ERROR_FILE_NOT_FOUND means absent. Every other failure is Error, never "absent".</summary>
    internal static Result QueryExact_(IPAddress ip, int port)
    {
        if (!LayoutOk()) return new Result(Kind.Error, Why: "unsupported layout");
        if (HttpInitialize(new HttpApiVersion { Major = 1, Minor = 0 }, InitConfig, IntPtr.Zero) != 0) return new Result(Kind.Error, Why: "http.sys init failed");
        var sa = SockAddr(ip, port);
        var q = Marshal.AllocHGlobal(SslQuerySize);
        try
        {
            for (var i = 0; i < SslQuerySize; i++) Marshal.WriteByte(q, i, 0);
            Marshal.WriteInt32(q, 0, QueryExact); Marshal.WriteIntPtr(q, P, sa);
            var rc = Call(IdSsl, q, SslQuerySize, DecodeSsl, out var e);
            if (rc == ErrFileNotFound) return new Result(Kind.Absent);
            if (rc != 0 || e == null) return new Result(Kind.Error, Why: "win32 " + rc);
            return new Result(Kind.Found, e.Thumbprint, e.AppId, e.Store);
        }
        finally { Marshal.FreeHGlobal(q); Marshal.FreeHGlobal(sa); HttpTerminate(InitConfig, IntPtr.Zero); }
    }

    /// <summary>All IP:port, SNI and CCS records, in one list. A null list means some enumeration could not be completed (never "none").</summary>
    internal static List<Entry>? EnumerateAll()
    {
        if (!LayoutOk()) return null;
        if (HttpInitialize(new HttpApiVersion { Major = 1, Minor = 0 }, InitConfig, IntPtr.Zero) != 0) return null;
        try
        {
            var list = new List<Entry>();
            foreach (var (id, size, tokenOff, decode) in new (int, int, int, Func<IntPtr, Entry?>)[]
                { (IdSsl, SslQuerySize, SslQueryToken, DecodeSsl), (IdSni, SniQuerySize, SniQueryToken, DecodeSni), (IdCcs, CcsQuerySize, CcsQueryToken, DecodeCcs) })
            {
                var done = false;
                for (var token = 0; token < 4096 && !done; token++)
                {
                    var q = Marshal.AllocHGlobal(size);
                    try
                    {
                        for (var i = 0; i < size; i++) Marshal.WriteByte(q, i, 0);
                        Marshal.WriteInt32(q, 0, QueryNext); Marshal.WriteInt32(q, tokenOff, token);
                        var rc = Call(id, q, size, decode, out var e);
                        if (rc == ErrNoMoreItems) { done = true; break; }
                        if (rc != 0 || e == null) return null;
                        list.Add(e);
                    }
                    finally { Marshal.FreeHGlobal(q); }
                }
                if (!done) return null;
            }
            return list;
        }
        finally { HttpTerminate(InitConfig, IntPtr.Zero); }
    }
}

/// <summary>Read-only look at the machine. No mutation here. Public metadata and the privileged key check are separate.</summary>
internal static class TlsInspector
{
    internal const string StoreLabel = "MY";
    internal static readonly Guid AppId = new("bd54aef6-13d9-4c17-8e6f-e985b8026083");   // this app's own id, not a test placeholder
    internal const string FriendlyName = "BingeMote LAN identity";

    /// <summary>privilegedKeyCheck=false (runtime): public certificate plus a key REFERENCE only. The runtime may be unable to use a SYSTEM/Admin-only key by design.</summary>
    internal static (CertState state, X509Certificate2? cert) FindByThumbprint(string thumbprint, bool privilegedKeyCheck)
    {
        try
        {
            using var st = new X509Store(System.Security.Cryptography.X509Certificates.StoreName.My, StoreLocation.LocalMachine);
            st.Open(OpenFlags.ReadOnly | OpenFlags.OpenExistingOnly);
            var found = st.Certificates.Find(X509FindType.FindByThumbprint, thumbprint, false);
            if (found.Count == 0) return (CertState.Absent, null);
            var c = found[0];
            if (!c.HasPrivateKey) return (CertState.KeyMissing, c);
            if (privilegedKeyCheck)
            {
                try { using var k = c.GetECDsaPrivateKey(); if (k == null) return (CertState.KeyMissing, c); k.SignData(new byte[] { 1 }, HashAlgorithmName.SHA256); }
                catch (CryptographicException) { return (CertState.AccessError, c); }   // an access or provider error is never "missing"
            }
            return (CertState.Present, c);
        }
        catch { return (CertState.AccessError, null); }
    }

    internal static bool HasOrphanOurCert()
    {
        try
        {
            using var st = new X509Store(System.Security.Cryptography.X509Certificates.StoreName.My, StoreLocation.LocalMachine);
            st.Open(OpenFlags.ReadOnly | OpenFlags.OpenExistingOnly);
            foreach (var c in st.Certificates) if (c.FriendlyName == FriendlyName) return true;
            return false;
        }
        catch { return true; }   // cannot tell: treat as present so setup fails closed
    }

    internal static string SpkiHex(X509Certificate2 c) => Convert.ToHexString(SHA256.HashData(c.PublicKey.ExportSubjectPublicKeyInfo())).ToLowerInvariant();

    internal static BindingState FromResult(HttpSysSsl.Result r, TlsIdentityMarker? marker)
    {
        if (r.Kind == HttpSysSsl.Kind.Error) return BindingState.Unknown;
        if (r.Kind == HttpSysSsl.Kind.Absent) return BindingState.Absent;
        bool ours = marker != null && r.Thumbprint.Equals(marker.Thumbprint, StringComparison.OrdinalIgnoreCase)
                    && r.AppId.Equals(marker.AppId, StringComparison.OrdinalIgnoreCase)
                    && r.Store.Equals(StoreLabel, StringComparison.OrdinalIgnoreCase);
        return ours ? BindingState.ExactExpected : BindingState.Foreign;
    }

    /// <summary>StaleOwned only when ALL hold: our app id, store MY, and the bound thumbprint is a certificate in this machine's store whose public key equals the marker's key (an earlier same-key reissue). Anything else stays Foreign.</summary>
    internal static BindingState FromResultOrStale(HttpSysSsl.Result r, TlsIdentityMarker? marker)
    {
        var s = FromResult(r, marker);
        if (s != BindingState.Foreign || marker == null || r.Kind != HttpSysSsl.Kind.Found) return s;
        if (!r.AppId.Equals(marker.AppId, StringComparison.OrdinalIgnoreCase) || !r.Store.Equals(StoreLabel, StringComparison.OrdinalIgnoreCase)) return s;
        var (cs, c) = FindByThumbprint(r.Thumbprint, false);
        return cs == CertState.Present || cs == CertState.KeyMissing ? (c != null && SpkiHex(c) == marker.SpkiSha256Hex ? BindingState.StaleOwned : s) : s;
    }

    internal static TlsObservation Observe(int port, bool elevated, bool explicitSetup)
    {
        var (ms, marker) = TlsMarkerStore.Read();
        var (cert, c) = marker != null ? FindByThumbprint(marker.Thumbprint, elevated) : (CertState.Absent, null);
        bool matches = c != null && marker != null && SpkiHex(c) == marker.SpkiSha256Hex;
        var r4 = HttpSysSsl.QueryExact_(IPAddress.Any, port); var r6 = HttpSysSsl.QueryExact_(IPAddress.IPv6Any, port);
        var v4 = FromResultOrStale(r4, marker); var v6 = FromResultOrStale(r6, marker);
        var diag = Diagnose("IPv4", r4, v4, marker) + Diagnose("IPv6", r6, v6, marker);
        var all = elevated ? HttpSysSsl.EnumerateAll() : null;   // runtime does not enumerate (admin need unverified)
        // Any SNI or CCS record, or a non-wildcard IP record on this port, is reported as an UNVERIFIED coexistence. Actual precedence is not evaluated.
        bool specific = all != null && all.Any(e => e.Family != "ip" ? (e.Family == "ccs" || e.Port == port)
            : e.Port == port && e.Address != null && !e.Address.Equals(IPAddress.Any) && !e.Address.Equals(IPAddress.IPv6Any));
        if (elevated && all == null) { v4 = v4 == BindingState.ExactExpected ? v4 : BindingState.Unknown; v6 = v6 == BindingState.ExactExpected ? v6 : BindingState.Unknown; }
        bool orphan = ms == MarkerState.Absent && HasOrphanOurCert();
        bool due = c != null && TlsLifetime.IsRenewalDue(new DateTimeOffset(c.NotAfter), DateTimeOffset.UtcNow);
        return new TlsObservation(ms, marker, cert, matches, orphan, v4, v6, specific, due, explicitSetup, System.Net.Sockets.Socket.OSSupportsIPv6, diag);
    }

    /// <summary>Precise, non-secret reason a binding is not treated as ours. Never leads to deletion: an app-id match alone is not ownership, and a binding naming a certificate that is gone stays untouched.</summary>
    internal static string Diagnose(string fam, HttpSysSsl.Result r, BindingState s, TlsIdentityMarker? m)
    {
        if (s != BindingState.Foreign || r.Kind != HttpSysSsl.Kind.Found) return "";
        var appMatch = m != null && r.AppId.Equals(m.AppId, StringComparison.OrdinalIgnoreCase);
        return fam + " binding names thumbprint " + r.Thumbprint + " in store '" + r.Store + "'" + (appMatch ? "; its app id matches this install but the certificate is not a same-key certificate in the machine store, so it is left untouched. " : "; app id differs from this install, left untouched. ");
    }
}

internal sealed record RemovalPlan(bool DeleteV4, bool DeleteV6, string Remains);

/// <summary>Uninstall policy: remove only OWNED TRANSPORT bindings that a fresh read shows are exactly ours. The certificate, persisted key and identity marker are RETAINED: http.sys reference absence does not prove exclusive ownership, and no removal policy for identity material is approved.</summary>
internal static class RemovalPlanner
{
    internal static RemovalPlan Decide(MarkerState ms, BindingState v4, BindingState v6, CertState cert, bool certMatchesMarker, bool otherRecordsReferenceCert, bool journalPresent)
    {
        if (journalPresent) return new(false, false, "an interrupted setup journal exists; nothing was removed");
        if (ms != MarkerState.Present) return new(false, false, ms == MarkerState.Corrupt ? "the identity marker is unreadable; nothing was removed" : "no identity marker; nothing of ours is provable");
        var remains = new List<string> { "certificate, persisted key container, earlier renewal certificates and the identity marker are retained" };
        if (v4 is BindingState.Foreign or BindingState.StaleOwned or BindingState.Unknown) remains.Add("IPv4 binding left (" + v4 + ")");
        if (v6 is BindingState.Foreign or BindingState.StaleOwned or BindingState.Unknown) remains.Add("IPv6 binding left (" + v6 + ")");
        return new(v4 == BindingState.ExactExpected, v6 == BindingState.ExactExpected, string.Join("; ", remains));
    }
}

public enum RecoveryDecision { DeleteJournal, RollBack, FailClosed }

/// <summary>Pure recovery decision from ACTUAL observed state (never from a journal phase). hadPrior = a prior identity existed before the operation (journal OldThumb recorded). A different marker is never assumed ours: it fails closed.</summary>
internal static class RecoveryPlanner
{
    internal static RecoveryDecision Decide(string kind, MarkerState ms, bool markerPresent, bool newThumbKnown, bool markerNamesNew, bool markerNamesOld, bool bothExactNew, bool hadPrior = false)
    {
        if (ms == MarkerState.Corrupt) return RecoveryDecision.FailClosed;
        if (!newThumbKnown) return RecoveryDecision.RollBack;   // crashed before the thumbprint was recorded; RollBack itself fails closed if any mutation flag is set
        if (kind != "Bind" && markerNamesNew) return bothExactNew ? RecoveryDecision.DeleteJournal : RecoveryDecision.FailClosed;   // committed
        if (kind == "Bind" && markerNamesNew && bothExactNew) return RecoveryDecision.DeleteJournal;                                   // completed
        if (kind == "Renew" && markerPresent && !markerNamesOld) return RecoveryDecision.FailClosed;
        if (kind == "Fresh")
        {
            if (hadPrior) return markerPresent && markerNamesOld ? RecoveryDecision.RollBack : RecoveryDecision.FailClosed;   // prior marker must still be exactly the prior one
            return markerPresent ? RecoveryDecision.FailClosed : RecoveryDecision.RollBack;
        }
        return RecoveryDecision.RollBack;
    }
}

/// <summary>
/// Elevated-only setup and renewal. The unelevated runtime never calls these. UNRUN.
/// Commit model (single commit point per operation = the atomic marker replace; recovery decides from ACTUAL state, never from journal phase):
///   Fresh : journal(NewThumb) -> add cert -> reopen+sign+SPKI check -> bind v4/v6 (journal flag BEFORE each) -> verify exact -> WRITE MARKER (commit) -> delete journal.
///   Bind  : journal(NewThumb = current marker cert) -> add missing / update stale (flag BEFORE each) -> verify exact -> delete journal. Marker never changes.
///   Renew : journal(NewThumb, OldThumb) -> add cert -> reopen+sign+SPKI check -> update v4/v6 (flag BEFORE each) -> verify exact NEW -> WRITE MARKER (commit) -> delete journal.
/// Recovery after any crash: marker names NewThumb and both bindings are exactly NEW => the operation committed, delete the journal.
/// Marker still names the prior identity (or none) => roll back ONLY bindings a fresh structured read proves are ours and equal to NewThumb;
/// the new certificate is kept for Renew, removed for Fresh only when unbound and carrying our friendly name. Anything else: fail closed, keep the journal.
/// </summary>
internal static class TlsSetup
{
    // Exit codes: 0 ok; 5 planner refused or setup failed (rolled back where provable); 6 not elevated; 7 busy; 9 an earlier interrupted run is not provably recoverable.
    internal static int Run(int port, bool explicitSetup)
    {
        if (!new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator)) return 6;
        using var mutex = MutexAcl.Create(false, @"Global\BingeMoteTlsSetup", out _, MakeMutexSecurity());
        bool got;
        try { got = mutex.WaitOne(TimeSpan.FromSeconds(30)); } catch (AbandonedMutexException) { got = true; }   // prior holder died: we own it, the journal tells what it left
        if (!got) return 7;
        try
        {
            var (js, journal) = TlsJournalStore.Read();
            if (js == MarkerState.Corrupt)
            {
                Program.Log("tls setup: the operation journal (" + TlsJournalStore.FilePath + ") or its folder is unreadable or untrusted; nothing was changed and nothing will be until it is examined. No identity is regenerated.");
                return 9;
            }
            if (js == MarkerState.Present && !Recover(port, journal!)) { Program.Log("tls setup: an earlier interrupted operation could not be proven recoverable; the journal was kept. State: marker " + TlsMarkerStore.Read().state + "."); return 9; }

            var obs = TlsInspector.Observe(port, elevated: true, explicitSetup);
            var plan = TlsPlanner.Plan(obs);
            Program.Log("tls setup plan: " + plan.Action + " (" + plan.Reason + ") marker=" + obs.Marker + " cert=" + obs.Cert + " v4=" + obs.V4 + " v6=" + obs.V6 + " " + plan.Notes);
            if (plan.Action == TlsAction.FailClosed) return 5;
            if (plan.Action == TlsAction.Ready) return plan.RenewDue && explicitSetup ? Renew(port, obs.M!) : 0;
            return Install(port, plan, obs.M);
        }
        finally { try { mutex.ReleaseMutex(); } catch { } }
    }

    private static string Now => DateTime.UtcNow.ToString("O");
    private static bool BothExact(int port, TlsIdentityMarker m) =>
        TlsInspector.FromResult(HttpSysSsl.QueryExact_(IPAddress.Any, port), m) == BindingState.ExactExpected &&
        (!TlsPolicy.V6Required(System.Net.Sockets.Socket.OSSupportsIPv6) ||
         TlsInspector.FromResult(HttpSysSsl.QueryExact_(IPAddress.IPv6Any, port), m) == BindingState.ExactExpected);

    private static void AddToStore(X509Certificate2 c)
    {
        using var st = new X509Store(System.Security.Cryptography.X509Certificates.StoreName.My, StoreLocation.LocalMachine);
        st.Open(OpenFlags.ReadWrite); st.Add(c);
    }

    /// <summary>Reopens the certificate FROM THE STORE and proves, before any binding: key present, a real signature works, SPKI equals expected. No assumed key association.</summary>
    private static void VerifyStored(string thumb, string spki)
    {
        var (s, c) = TlsInspector.FindByThumbprint(thumb, privilegedKeyCheck: true);
        if (s != CertState.Present || c == null) throw new InvalidOperationException("stored certificate not usable: " + s);
        if (TlsInspector.SpkiHex(c) != spki) throw new InvalidOperationException("stored certificate key differs from expected");
    }

    private static int Install(int port, TlsPlan plan, TlsIdentityMarker? existing)
    {
        var fresh = plan.Action == TlsAction.GenerateFresh;
        var j = new TlsJournal(fresh ? "Fresh" : "Bind", fresh ? null : existing?.Thumbprint, fresh ? existing?.Thumbprint : null, false, false, false, false, false, Now);
        TlsIdentityMarker? marker = existing;
        try
        {
            if (fresh)
            {
                using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
                using var cert = NewCert(key, persistedKey: false);
                j = j with { NewThumb = cert.Thumbprint };
                TlsJournalStore.Write(j);                         // journal first, then the mutation
                j = j with { CertAdded = true }; TlsJournalStore.Write(j);   // intent recorded BEFORE the add; recovery tolerates an absent resource and only deletes after exact checks
                AddToStore(cert);
                marker = new TlsIdentityMarker(cert.Thumbprint, TlsInspector.SpkiHex(cert), TlsInspector.AppId.ToString(), port, Now);
                VerifyStored(marker.Thumbprint, marker.SpkiSha256Hex);
            }
            if (marker == null) return 5;
            if (plan.Action == TlsAction.UpdateStale)
            {
                foreach (var (ip, addr, isV4) in new[] { (IPAddress.Any, "0.0.0.0", true), (IPAddress.IPv6Any, "[::]", false) })
                {
                    var r = HttpSysSsl.QueryExact_(ip, port);
                    if (r.Kind != HttpSysSsl.Kind.Found || TlsInspector.FromResultOrStale(r, marker) != BindingState.StaleOwned) continue;
                    if (j.OldThumb != null && !j.OldThumb.Equals(r.Thumbprint, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("two different stale thumbprints");
                    j = j with { OldThumb = r.Thumbprint, Updated4 = j.Updated4 || isV4, Updated6 = j.Updated6 || !isV4 };
                    TlsJournalStore.Write(j);
                    Netsh($"http update sslcert ipport={addr}:{port} certhash={marker.Thumbprint} appid={{{marker.AppId}}} certstorename=MY");
                }
            }
            if (plan.UpdateV4) { j = j with { Updated4 = true }; TlsJournalStore.Write(j); Netsh($"http update sslcert ipport=0.0.0.0:{port} certhash={marker.Thumbprint} appid={{{marker.AppId}}} certstorename=MY"); }
            if (plan.UpdateV6) { j = j with { Updated6 = true }; TlsJournalStore.Write(j); Netsh($"http update sslcert ipport=[::]:{port} certhash={marker.Thumbprint} appid={{{marker.AppId}}} certstorename=MY"); }
            if (plan.BindV4) { j = j with { Bound4 = true }; TlsJournalStore.Write(j); Netsh($"http add sslcert ipport=0.0.0.0:{port} certhash={marker.Thumbprint} appid={{{marker.AppId}}} certstorename=MY"); }
            if (plan.BindV6) { j = j with { Bound6 = true }; TlsJournalStore.Write(j); Netsh($"http add sslcert ipport=[::]:{port} certhash={marker.Thumbprint} appid={{{marker.AppId}}} certstorename=MY"); }
            // "Already exists" or a zero exit is not success: a structured read must show the exact expected binding in both families.
            if (!BothExact(port, marker)) throw new InvalidOperationException("bindings did not verify");
            if (fresh) TlsMarkerStore.Write(marker);              // commit point
            TlsJournalStore.Delete();
            return 0;
        }
        catch (Exception ex)
        {
            Program.Log("tls setup failed (" + ex.GetType().Name + "); restoring the prior state where it can be proven.");
            if (fresh && j.NewThumb != null && TlsMarkerStore.Read().marker?.Thumbprint == j.NewThumb) { /* committed before the failure: leave */ }
            else if (RollBack(port, j, existing)) TlsJournalStore.Delete();
            return 5;
        }
    }

    /// <summary>Same-key reissue. The new certificate is built over the SAME key object, stored, then REOPENED FROM THE STORE and verified (key present, real signature, SPKI unchanged) before any binding changes. The old certificate stays in the store.</summary>
    private static int Renew(int port, TlsIdentityMarker marker)
    {
        var (state, old) = TlsInspector.FindByThumbprint(marker.Thumbprint, privilegedKeyCheck: true);
        if (state != CertState.Present || old == null) return 5;
        var j = new TlsJournal("Renew", null, marker.Thumbprint, false, false, false, false, false, Now);
        try
        {
            using var key = old.GetECDsaPrivateKey() ?? throw new InvalidOperationException("no key");
            using var fresh = NewCert(key, persistedKey: true);                        // stays alive through the add
            if (TlsInspector.SpkiHex(fresh) != marker.SpkiSha256Hex) throw new InvalidOperationException("renewal would change the pinned key");
            j = j with { NewThumb = fresh.Thumbprint }; TlsJournalStore.Write(j);
            j = j with { CertAdded = true }; TlsJournalStore.Write(j);   // intent first
            AddToStore(fresh);
            VerifyStored(fresh.Thumbprint, marker.SpkiSha256Hex);                      // no assumed key association: prove it
            var next = marker with { Thumbprint = fresh.Thumbprint, CreatedUtc = Now };
            j = j with { Updated4 = true }; TlsJournalStore.Write(j);
            Netsh($"http update sslcert ipport=0.0.0.0:{port} certhash={fresh.Thumbprint} appid={{{marker.AppId}}} certstorename=MY");
            j = j with { Updated6 = true }; TlsJournalStore.Write(j);
            Netsh($"http update sslcert ipport=[::]:{port} certhash={fresh.Thumbprint} appid={{{marker.AppId}}} certstorename=MY");
            if (!BothExact(port, next)) throw new InvalidOperationException("renewed bindings did not verify");
            TlsMarkerStore.Write(next);                                                // commit point; old certificate retained
            TlsJournalStore.Delete();
            return 0;
        }
        catch (Exception ex)
        {
            Program.Log("tls renewal failed (" + ex.GetType().Name + "); restoring the prior bindings where provable.");
            var m = TlsMarkerStore.Read().marker;
            if (m != null && j.NewThumb != null && m.Thumbprint == j.NewThumb) { /* committed before the failure */ }
            else if (RollBack(port, j, marker)) TlsJournalStore.Delete();
            return 5;
        }
    }

    private static X509Certificate2 NewCert(ECDsa key, bool persistedKey)
    {
        var req = new CertificateRequest("CN=BingeMote", key, HashAlgorithmName.SHA256);
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
        var ss = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.Add(TlsLifetime.Validity));
        if (persistedKey) { ss.FriendlyName = TlsInspector.FriendlyName; return ss; }   // caller disposes; store association is verified after the add, not assumed
        using (ss)
        {
            var c = new X509Certificate2(ss.Export(X509ContentType.Pfx), (string?)null, X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.MachineKeySet);   // exportability of the in-memory key is UNVERIFIED
            c.FriendlyName = TlsInspector.FriendlyName;
            return c;
        }
    }

    /// <summary>Interrupted earlier run. Decides from ACTUAL state (marker + structured binding reads), see the class comment.</summary>
    private static bool Recover(int port, TlsJournal j)
    {
        var (ms, marker) = TlsMarkerStore.Read();
        var isNew = marker != null && j.NewThumb != null && marker.Thumbprint.Equals(j.NewThumb, StringComparison.OrdinalIgnoreCase);
        var isOld = marker != null && j.OldThumb != null && marker.Thumbprint.Equals(j.OldThumb, StringComparison.OrdinalIgnoreCase);
        var exact = isNew && BothExact(port, marker!);
        switch (RecoveryPlanner.Decide(j.Kind, ms, marker != null, j.NewThumb != null, isNew, isOld, exact, j.OldThumb != null))
        {
            case RecoveryDecision.DeleteJournal: TlsJournalStore.Delete(); return true;
            case RecoveryDecision.RollBack:
                if (!RollBack(port, j, marker)) return false;
                TlsJournalStore.Delete(); return true;
            default: return false;
        }
    }

    /// <summary>Undo only what the journal flagged AND a fresh read proves is exactly ours and still NewThumb. Foreign or changed bindings are preserved (and make this return true only if nothing of ours remains unproven).</summary>
    private static bool RollBack(int port, TlsJournal j, TlsIdentityMarker? marker)
    {
        try
        {
            if (j.NewThumb == null) return !(j.Bound4 || j.Bound6 || j.Updated4 || j.Updated6 || j.CertAdded);
            var appId = marker?.AppId ?? TlsInspector.AppId.ToString();
            foreach (var (bound, updated, ip, addr) in new[] { (j.Bound4, j.Updated4, IPAddress.Any, "0.0.0.0"), (j.Bound6, j.Updated6, IPAddress.IPv6Any, "[::]") })
            {
                if (!bound && !updated) continue;
                var r = HttpSysSsl.QueryExact_(ip, port);
                if (r.Kind == HttpSysSsl.Kind.Error) return false;
                if (r.Kind == HttpSysSsl.Kind.Absent) continue;
                var ours = r.Thumbprint.Equals(j.NewThumb, StringComparison.OrdinalIgnoreCase) && r.AppId.Equals(appId, StringComparison.OrdinalIgnoreCase) && r.Store.Equals(TlsInspector.StoreLabel, StringComparison.OrdinalIgnoreCase);
                if (!ours) continue;   // changed by someone else (or already restored): preserve it
                if (updated && j.OldThumb != null) Netsh($"http update sslcert ipport={addr}:{port} certhash={j.OldThumb} appid={{{appId}}} certstorename=MY");
                else if (bound) Netsh($"http delete sslcert ipport={addr}:{port}");
                else return false;   // updated with no recorded prior thumbprint: cannot restore, fail closed
            }
            if (j.Kind == "Fresh" && j.CertAdded)
            {
                var all = HttpSysSsl.EnumerateAll();
                if (all == null) return false;
                if (all.Any(e => e.Thumbprint.Equals(j.NewThumb, StringComparison.OrdinalIgnoreCase))) return false;   // still referenced: keep it, keep the journal
                using var st = new X509Store(System.Security.Cryptography.X509Certificates.StoreName.My, StoreLocation.LocalMachine);
                st.Open(OpenFlags.ReadWrite);
                foreach (var c in st.Certificates.Find(X509FindType.FindByThumbprint, j.NewThumb, false))
                    if (c.FriendlyName == TlsInspector.FriendlyName) st.Remove(c);   // key container stays (conservative retention); friendly name only narrows, it is not ownership proof
            }
            return true;
        }
        catch (Exception ex) { Program.Log("tls rollback incomplete: " + ex.GetType().Name); return false; }
    }

    /// <summary>Elevated uninstall cleanup: owned transport bindings only. Certificate, key, renewal certificates and the identity marker are retained by policy.</summary>
    internal static int Remove(int port)
    {
        if (!new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator)) return 6;
        using var mutex = MutexAcl.Create(false, @"Global\BingeMoteTlsSetup", out _, MakeMutexSecurity());
        bool got;
        try { got = mutex.WaitOne(TimeSpan.FromSeconds(30)); } catch (AbandonedMutexException) { got = true; }
        if (!got) return 7;
        try
        {
            var obs = TlsInspector.Observe(port, elevated: true, explicitSetup: false);
            var all = HttpSysSsl.EnumerateAll();
            if (all == null) { Program.Log("tls removal: bindings could not be enumerated; nothing was removed."); return 0; }
            var m = obs.M;
            bool others = m != null && all.Any(e => e.Thumbprint.Equals(m.Thumbprint, StringComparison.OrdinalIgnoreCase) && !(e.Family == "ip" && e.Port == port && e.Address != null && (e.Address.Equals(IPAddress.Any) || e.Address.Equals(IPAddress.IPv6Any))));
            var plan = RemovalPlanner.Decide(obs.Marker, obs.V4, obs.V6, obs.Cert, obs.CertMatchesMarker, others, TlsJournalStore.Read().state != MarkerState.Absent);
            Program.Log("tls removal: " + plan.Remains);
            if (m == null) return 0;
            if (plan.DeleteV4) Netsh($"http delete sslcert ipport=0.0.0.0:{port}");
            if (plan.DeleteV6) Netsh($"http delete sslcert ipport=[::]:{port}");
            return 0;
        }
        catch (Exception ex) { Program.Log("tls removal stopped (" + ex.GetType().Name + "); remaining state is unchanged beyond the logged steps."); return 0; }
        finally { try { mutex.ReleaseMutex(); } catch { } }
    }

    private static MutexSecurity MakeMutexSecurity()
    {
        var s = new MutexSecurity();
        s.AddAccessRule(new MutexAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), MutexRights.FullControl, AccessControlType.Allow));
        s.AddAccessRule(new MutexAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), MutexRights.FullControl, AccessControlType.Allow));
        return s;
    }

    private static int Netsh(string args)
    {
        var psi = new ProcessStartInfo("netsh", args) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false };
        using var p = Process.Start(psi)!; p.StandardOutput.ReadToEnd(); p.StandardError.ReadToEnd(); p.WaitForExit();
        if (p.ExitCode != 0) throw new InvalidOperationException("netsh exit " + p.ExitCode);   // callers still re-read structured state before trusting any step
        return p.ExitCode;
    }
}

/// <summary>Runtime (unelevated) view: public metadata, the public certificate, a key reference and structured binding reads. It never signs, creates, imports, deletes or rebinds.</summary>
internal static class HelperTls
{
    internal static bool RuntimeReady(int port, out string reason)
    {
        var plan = TlsPlanner.Plan(TlsInspector.Observe(port, elevated: false, explicitSetup: false));
        reason = plan.Reason;
        return plan.Action == TlsAction.Ready;
    }
    /// <summary>After the listener is started: a real TLS handshake on loopback (IPv4 and IPv6) must present a certificate whose SPKI equals the marker's. Public data only; no private-key access, no HTTP request. A mismatch means the caller must stop the listener. UNRUN; ::1 reachability on a given machine is unverified, so a v6 connect failure is reported, not hidden.</summary>
    internal static bool VerifyListener(int port, out string reason)
    {
        var (ms, marker) = TlsMarkerStore.Read();
        reason = "";
        if (ms != MarkerState.Present || marker == null) { reason = "identity marker not usable"; return false; }
        foreach (var ip in TlsPolicy.V6Required(System.Net.Sockets.Socket.OSSupportsIPv6) ? new[] { IPAddress.Loopback, IPAddress.IPv6Loopback } : new[] { IPAddress.Loopback })
        {
            try
            {
                using var tcp = new System.Net.Sockets.TcpClient(ip.AddressFamily);
                if (!tcp.ConnectAsync(ip, port).Wait(3000)) { reason = "connect timeout " + ip; return false; }
                string seen = "";
                using var ssl = new System.Net.Security.SslStream(tcp.GetStream(), false, (_, cert, _, _) =>
                { if (cert != null) seen = Convert.ToHexString(SHA256.HashData(new X509Certificate2(cert).PublicKey.ExportSubjectPublicKeyInfo())).ToLowerInvariant(); return true; });
                if (!ssl.AuthenticateAsClientAsync("localhost").Wait(5000)) { reason = "handshake timeout " + ip; return false; }
                if (seen != marker.SpkiSha256Hex) { reason = "listener presented a different key on " + ip; return false; }
            }
            catch (Exception ex) { reason = "handshake failed on " + ip + ": " + ex.GetType().Name; return false; }
        }
        return true;
    }
}
