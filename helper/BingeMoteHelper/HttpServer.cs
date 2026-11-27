using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Net;
using System.Text;
using System.Text.Json;

namespace BingeMoteHelper;

/// <summary>
/// LAN HTTPS API for the paired phone. Every endpoint except /pair needs the
/// pairing token in X-BingeMote-Token, and only private (home network) addresses are served.
/// </summary>
internal sealed class HttpServer : IDisposable
{
    private readonly HttpListener _listener = new();
    private Thread? _thread;
    private volatile bool _running;

    // Test-only port override: lets the Windows runtime tests stand up an isolated
    // identity/listener on their own port so teardown can never touch product state.
    internal static int PortOverrideForTests;

    public void Start()
    {
        var port = PortOverrideForTests != 0 ? PortOverrideForTests : Program.HttpPort;
        // HTTPS only. If the identity, key or either binding is not exactly as setup left it, nothing listens (no plaintext fallback).
        if (!HelperTls.RuntimeReady(port, out var tlsWhy))
        {
            Program.Log("secure listener not started: " + tlsWhy);
            throw new InvalidOperationException("Secure setup is not complete: " + tlsWhy);
        }
        _listener.Prefixes.Add($"https://+:{port}/");
        _listener.Start();
        if (!HelperTls.VerifyListener(port, out var hsWhy))
        {
            try { _listener.Stop(); } catch { }
            Program.Log("secure listener stopped: " + hsWhy);
            throw new InvalidOperationException("Secure listener check failed: " + hsWhy);
        }
        _running = true;
        _thread = new Thread(Loop) { IsBackground = true };
        _thread.Start();
    }

    private void Loop()
    {
        while (_running)
        {
            HttpListenerContext ctx;
            try { ctx = _listener.GetContext(); }
            catch { break; }
            Task.Run(async () => await Handle(ctx));
        }
    }

    private async Task Handle(HttpListenerContext ctx)
    {
        try
        {
            var req = ctx.Request;
            var res = ctx.Response;

            // A damaged pairing store is fail-closed for every route, before auth: 503 (never
            // 401/403, which would tell the phone its token is dead), generic body, nothing spent.
            if (Program.StoreBroken)
            {
                Reply(res, 503, "{\"error\":\"store broken\"}");
                return;
            }

            if (!IsPrivateAddress(req.RemoteEndPoint.Address))
            {
                Reply(res, 403, "{\"error\":\"not a private address\"}");
                return;
            }

            var path = req.Url?.AbsolutePath?.TrimEnd('/') ?? "";
            switch (path)
            {
                case "/ping":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    Reply(res, 200, "{\"ok\":true}");
                    return;

                case "/pair":
                    HandlePair(req, res);
                    return;

#if KEEPAWAKE_HOOK
                // CI-only (never in shipped builds, same gate as the pairing hook): force
                // the keep-awake hold on/off so the e2e can watch it in powercfg /requests.
                // Runs on a listener pool thread on purpose - that exercises the
                // pool-to-UI-thread marshal the hold depends on.
                case "/ci/hold":
                {
                    var on = JsonDocument.Parse(ReadBody(req)).RootElement.GetProperty("on").GetBoolean();
                    PowerWake.SyncToVlc(on);
                    Reply(res, 200, "{\"ok\":true}");
                    return;
                }
#endif

#if BLOCK_NOTICE_HOOK
                // CI-only (never in shipped builds): the input-blocked notice state, so
                // the e2e can assert the title, state transitions and balloon throttle.
                case "/ci/blockstate":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    Reply(res, 200, "{\"blocked\":" + (HelperWindow.BlockNoticeProbe.Blocked ? "true" : "false")
                        + ",\"balloons\":" + HelperWindow.BlockNoticeProbe.Balloons
                        + ",\"title\":" + JsonSerializer.Serialize(HelperWindow.BlockNoticeProbe.Title) + "}");
                    return;
#endif

                // The live mouse connection (item 5): one always-open WebSocket for
                // move/scroll/zoom/button frames, fire-and-forget. Same port, same token
                // check; an old phone simply never opens it and stays on HTTP.
                case "/ws":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    if (!req.IsWebSocketRequest) { Reply(res, 400, "{\"error\":\"websocket expected\"}"); return; }
                    await HandleWs(ctx);
                    return;

                // Any button on the phone: bring the screen back if its idle timer shut it
                // off. The playback buttons talk straight to VLC, so the phone pings this.
                case "/wake-display":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    PowerWake.WakeDisplay();
                    Reply(res, 200, "{\"ok\":true}");
                    return;

                // The phone's gate for VLC-direct buttons: dark (or not yet known) means
                // the first tap only wakes - it must not send the tap's real command.
                case "/screen-state":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    Reply(res, 200, "{\"dark\":" + (PowerWake.DisplayDarkOrUnknown ? "true" : "false") + "}");
                    return;

                case "/config":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    HandleConfig(req, res);
                    return;

                // The phone's Finish step hands over the VLC web-remote password (T36):
                // write it into vlcrc so a hand-opened VLC has the remote too.
                case "/vlc/setup":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    {
                        var pw = JsonDocument.Parse(ReadBody(req)).RootElement.GetProperty("password").GetString() ?? "";
                        // E5 made the vlcrc reads strict: an unreadable file is a 503, never a
                        // bare 500 (state stays safe - E5.6 leaves it untouched, E5.7 stages).
                        // I2-2: expected IO/access failures from the write itself are the same
                        // retryable 503.
                        try { Reply(res, 200, VlcSetup.WriteVlcrc(pw)); }
                        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { Reply(res, 503, "{\"ok\":false}"); }
                    }
                    return;

                // The phone's automatic setup check asks the PC what is actually configured:
                // a network error must never become a green Done on the phone by itself.
                case "/vlc/setup-status":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    // E6: configured=true only from a STRICT-verified full setup state; an
                    // unreadable vlcrc is a 503 (never a bare 500, never a false configured).
                    // DELTA-H: BOTH fields come from ONE coherent read (ConfigSaveLock -> Gate);
                    // pending means a LIVE obligation (never a superseded or already-applied
                    // record). Same wire fields as before.
                    try
                    {
                        var s = VlcSetup.StatusSnapshot();
                        Reply(res, 200, JsonSerializer.Serialize(new { configured = s.configured, pending = s.pending }));
                    }
                    catch (VlcSetup.VlcrcUnreadableException)
                    {
                        Reply(res, 503, "{\"ok\":false}");
                    }
                    return;

                // The one-shot adoption fetch: a paired phone that lost its copy of the VLC
                // password (or never finished setup) may fetch it exactly once, by request ID.
                case "/vlc/adopt":
                    {
                        // F9: body parse and EVERY network reply stay OUTSIDE ConfigSaveLock -
                        // a slow or stalled client must never stall every config save behind it.
                        string requestId; bool ack;
                        using (var doc = JsonDocument.Parse(ReadBody(req)))
                        {
                            var ar = doc.RootElement;
                            requestId = ar.TryGetProperty("requestId", out var ri) ? ri.GetString() ?? "" : "";
                            ack = ar.TryGetProperty("ack", out var ak) && ak.GetBoolean();
                        }
                        // E3: the token->phone lookup and the serve/ack run inside the ONE save
                        // transaction (ConfigSaveLock -> PhonesLock), so a removal - itself a
                        // ConfigSaveLock transaction - serializes against them: remove-then-adopt
                        // misses the fresh lookup; adopt-then-remove legitimately pre-dates the
                        // revoke. Fresh membership is checked on EVERY adopt call. The Result is
                        // only CAPTURED here - mapped and replied after the lock is released.
                        AdoptGrant.Result? adoptResult = null; string? adoptName = null;
                        lock (Program.ConfigSaveLock)
                        {
                            var token = req.Headers["X-BingeMote-Token"];
                            Program.PairedPhone? phone = null;
                            if (token != null)
                                lock (Program.PhonesLock) { phone = Program.Phones.FirstOrDefault(p => ConstantTimeEq(p.Token, token)); }
                            if (phone != null)
                            {
                                adoptName = phone.Name;
                                // NAMED UNRESOLVED LIMITATION: the serve reads/writes vlcrc
                                // (local disk IO) inside ConfigSaveLock - responsiveness under a
                                // stalled disk is not established (observed; not accepted, not
                                // waived, not permitted by anyone).
                                // I2-2: expected local IO/access failures map to Failed -> a
                                // retryable 503, never a bare 500.
                                try
                                {
                                    adoptResult = ack
                                        ? AdoptGrant.Ack(phone, requestId, Program.SaveConfig)
                                        : AdoptGrant.Serve(phone, VlcSetup.ReadOrCreatePassword, requestId, DateTime.UtcNow, Program.SaveConfig);
                                }
                                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                                {
                                    adoptResult = new AdoptGrant.Result { Failed = true };
                                }
                            }
                        }
                        ReplyAdoptResult(res, ack, adoptName, adoptResult);
                    }
                    return;

                case "/subtitles/media":
                    if (!Authorized(req)) { Reply(res,401,"{}"); return; }
                    if (req.HttpMethod != "GET") { Reply(res,405,"{}"); return; }
                    Reply(res,200,await Subtitles.Media(int.TryParse(VlcSetup.ReadVlcrcValue("http-port"),out var mediaPort) && mediaPort is >0 and <=65535 ? mediaPort :8080,VlcSetup.EffectivePassword() ?? ""));
                    return;
                case "/subtitles/list":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    if (req.HttpMethod != "GET") { Reply(res, 405, "{}"); return; }
                    Reply(res, 200, await Subtitles.List(Program.MoviesFolder ?? "", int.TryParse(VlcSetup.ReadVlcrcValue("http-port"), out var listPort) && listPort is > 0 and <= 65535 ? listPort : 8080, VlcSetup.EffectivePassword() ?? ""));
                    return;
                case "/subtitles/select":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    if (req.HttpMethod != "POST") { Reply(res, 405, "{}"); return; }
                    {
                        if (PowerWake.ShouldSwallowDiscrete()) { Reply(res, 200, "{\"woke\":true}"); return; }
                        using var doc = JsonDocument.Parse(ReadBody(req));
                        var answer = await Subtitles.Select(int.TryParse(VlcSetup.ReadVlcrcValue("http-port"), out var selectPort) && selectPort is > 0 and <= 65535 ? selectPort : 8080,
                            VlcSetup.EffectivePassword() ?? "", doc.RootElement.GetProperty("trackId").GetInt32(), Program.MoviesFolder ?? "", doc.RootElement.GetProperty("mediaId").GetString());
                        Reply(res, answer.status, answer.body);
                    }
                    return;
                case "/subtitles/load":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    if (req.HttpMethod != "POST") { Reply(res, 405, "{}"); return; }
                    {
                        if (PowerWake.ShouldSwallowDiscrete()) { Reply(res, 200, "{\"woke\":true}"); return; }
                        using var doc = JsonDocument.Parse(ReadBody(req));
                        var data = doc.RootElement;
                        var answer = await Subtitles.Load(Program.MoviesFolder ?? "", int.TryParse(VlcSetup.ReadVlcrcValue("http-port"), out var loadPort) && loadPort is > 0 and <= 65535 ? loadPort : 8080, VlcSetup.EffectivePassword() ?? "",
                            data.GetProperty("mediaId").GetString() ?? "", data.GetProperty("id").GetString() ?? "");
                        Reply(res, answer.status, answer.body);
                    }
                    return;

                case "/movies/list":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    {
                        var rel = req.QueryString["path"] ?? "";
                        var answer = Movies.List(Program.MoviesFolder ?? "", rel);
                        Reply(res, answer.Contains("\"not found\"") || answer.Contains("\"no movies folder\"") ? 404 : 200, answer);
                    }
                    return;

                case "/browse":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    {
                        var answer = Movies.Browse(req.QueryString["path"] ?? "");
                        Reply(res, answer.Contains("\"not found\"") ? 404 : 200, answer);
                    }
                    return;

                case "/movies/play":
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    {
                        // Tapping a movie is a button press: dark screen means this tap only
                        // wakes, the movie does not launch blind. woke:true tells the phone
                        // the tap was consumed (no resume question, no navigation).
                        if (PowerWake.ShouldSwallowDiscrete()) { Reply(res, 200, "{\"ok\":true,\"woke\":true}"); return; }
                        using var doc = JsonDocument.Parse(ReadBody(req));
                        var rr = doc.RootElement;
                        var answer = Movies.Play(
                            Program.MoviesFolder ?? "",
                            rr.GetProperty("path").GetString() ?? "",
                            rr.TryGetProperty("httpPort", out var hp) ? hp.GetInt32() : 8080,
                            VlcSetup.EffectivePassword()
                                ?? (rr.TryGetProperty("httpPassword", out var pw) ? pw.GetString() ?? "" : ""),
                            rr.TryGetProperty("fullscreen", out var fs) && fs.GetBoolean(),
                            req.LocalEndPoint.Address,
                            rr.TryGetProperty("resume", out var rs) ? rs.GetBoolean() : (bool?)null);
                        Reply(res, answer.Contains("\"error\"") ? 400 : 200, answer);
                    }
                    return;

                default:
                    if (!Authorized(req)) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
                    RouteCommand(path, ReadBody(req), res);
                    return;
            }
        }
        catch
        {
            try { Reply(ctx.Response, 500, "{\"error\":\"internal\"}"); } catch { }
        }
    }

    private static bool Authorized(HttpListenerRequest req)
    {
        var token = req.Headers["X-BingeMote-Token"];
        if (token == null) return false;
        lock (Program.PhonesLock) { return Program.Phones.Any(p => ConstantTimeEq(token, p.Token)); }
    }

    private static Program.PairedPhone? FindPhone(HttpListenerRequest req)
    {
        var token = req.Headers["X-BingeMote-Token"];
        if (token == null) return null;
        lock (Program.PhonesLock) { return Program.Phones.FirstOrDefault(p => ConstantTimeEq(token, p.Token)); }
    }

    /// <summary>POST /vlc/adopt: {requestId} fetches the PC's VLC password once per paired
    /// phone; {requestId, ack:true} confirms the phone stored it and spends the grant.
    /// F9: response mapping ONLY - this runs after the adopt save transaction's lock is
    /// released; no Reply may be written under ConfigSaveLock.</summary>
    private static void ReplyAdoptResult(HttpListenerResponse res, bool ack, string? phoneName, AdoptGrant.Result? r)
    {
        if (r == null) { Reply(res, 401, "{\"error\":\"bad token\"}"); return; }
        if (r.Failed) { Reply(res, 503, "{\"ok\":false}"); return; } // persist failed; markers restored (E4)
        if (ack)
        {
            if (r.Acked)
            {
                Program.Log("Phone \"" + phoneName + "\" stored the VLC password; its one-time fetch is spent.");
                Reply(res, 200, "{\"ok\":true}");
            }
            else Reply(res, 409, "{\"error\":\"nothing to ack\"}");
            return;
        }
        if (r.Served)
        {
            if (!r.Replayed)
                Program.Log("Phone \"" + phoneName + "\" fetched the VLC password (one-time; value never logged).");
            Reply(res, 200, JsonSerializer.Serialize(new { password = r.Password }));
        }
        else if (r.Conflict)
            Reply(res, 409, "{\"error\":\"adopt in flight\",\"detail\":\"A fetch is already underway. Retry with the same request id.\"}");
        else if (r.AlreadySpent)
            Reply(res, 410, "{\"error\":\"adopt spent\",\"detail\":\"This phone already fetched its VLC password. Re-pair or set the password by hand.\"}");
        else
            Reply(res, 400, "{\"error\":\"bad request\"}");
    }

    internal static readonly object PairLock = new();

    /// <summary>Closes the Pair session when its code is older than PairSessionLifetime. Same lock as /pair.
    /// Returns true when a code was closed. A closed session needs the owner to press Pair again.</summary>
    internal static bool ExpirePairCodeIfStale(DateTime nowUtc)
    {
        lock (PairLock)
        {
            if (Program.PairCode == null) return false;
            if (nowUtc - Program.PairCodeIssuedUtc < Program.PairSessionLifetime) return false;
            Program.PairCode = null;
            Program.PendingPhoneName = null;
            Program.Log("Pairing session closed after 10 minutes.");
            return true;
        }
    }
    private void HandlePair(HttpListenerRequest req, HttpListenerResponse res)
    {
        // Read the request body BEFORE taking the lock: a slow or stalled client must never
        // hold PairLock, or the window's Cancel/Start (same lock) would freeze behind it.
        var body = ReadBody(req);
        lock (PairLock) { HandlePairLocked(body, res); }
    }
    private void HandlePairLocked(string body, HttpListenerResponse res)
    {
        if (Program.PairFailures >= Program.MaxPairFailures)
        {
            Program.Log("Pairing locked - too many wrong codes.");
            Reply(res, 429, "{\"error\":\"locked\",\"detail\":\"Too many wrong codes. Press Pair again in the BingeMote window on the PC.\"}");
            return;
        }
        ExpirePairCodeIfStale(DateTime.UtcNow);
        // Snapshot the pairing state ONCE under the lock: the UI thread also writes it
        // (Start/Cancel/expiry), and a null landing between the check and the compare
        // would turn into an exception instead of an answer.
        var pairCode = Program.PairCode;
        // No code on screen: guesses here must not count toward the lock, or anyone on the
        // Wi-Fi could lock pairing while he is not even pairing.
        if (pairCode == null)
        {
            Reply(res, 403, "{\"error\":\"bad code\"}");
            return;
        }
        var code = JsonDocument.Parse(body).RootElement.GetProperty("code").GetString() ?? "";
        // One-shot, rate-limited, and closes after the 10-minute Pair session.
        var valid = ConstantTimeEq(code, pairCode);
        if (!valid)
        {
            Program.PairFailures++;
            Program.Log("Wrong pairing code entered (" + Program.PairFailures + " of " + Program.MaxPairFailures + ").");
            Reply(res, 403, "{\"error\":\"bad code\"}");
            return;
        }
        if (Program.PhonesCount >= Program.MaxPhones)
        {
            Program.Log("Pairing refused - already " + Program.MaxPhones + " phones paired.");
            Reply(res, 409, "{\"error\":\"too many phones\",\"detail\":\"This PC already has " + Program.MaxPhones + " phones paired. Remove one in the BingeMote window on the PC to make room.\"}");
            return;
        }
        // S10: decide the VLC password BEFORE anything is saved or spent (E1). An unreadable
        // vlcrc refuses the whole pair with a retryable 503 - never a fresh password planned
        // over an unreadable (possibly configured) one.
        VlcSetup.PasswordPlan plan;
        try { plan = VlcSetup.PlanPassword(); }
        catch (VlcSetup.VlcrcUnreadableException)
        {
            Reply(res, 503, "{\"error\":\"vlcrc unreadable\",\"detail\":\"VLC's settings file could not be read. Nothing was saved - try the same code again in a moment.\"}");
            return;
        }
        var token = Program.NewToken();
        // The name typed in the window when Pair was tapped; a code from elsewhere pairs as "Phone N".
        string? phoneName = Program.PendingPhoneName;
        Program.PendingPhoneName = null;
        // Transactional: mutation and persist inside the one save lock. A failed save rolls the
        // in-memory add back and the code is NOT spent - he can try the same code again; the
        // typed name goes back too.
        if (!TrySavePair(token, ref phoneName, plan.IsFreshCandidate ? plan.Value : null, out var livePendingInstead))
        {
            Program.PendingPhoneName = phoneName;
            Reply(res, 503, "{\"error\":\"save failed\",\"detail\":\"The pairing worked but could not be saved on the PC. Try the same code again in a moment.\"}");
            return;
        }
        // Persisted: only now is the code spent.
        Program.PairCode = null;
        Program.PairFailures = 0;
        Program.Log("Phone paired as \"" + phoneName + "\" (" + Program.PhonesCount + " total).");
        // Automatic-first VLC setup (S10): the phone is durable now, so the token MUST reach
        // it - truthfulness comes from the fields. For a fresh candidate the pending record is
        // already durable (staged in the SAME save as the phone, F12/D1); the apply completes
        // it via the E1 decision. F12/D2: vlcPending is true whenever the served value is not
        // verified in effect - an unverified password NEVER reads as "ready".
        // I2-3: when an intervening live pending was preserved, the pair serves THAT value.
        Reply(res, 200, FinishPairReply(token, livePendingInstead ?? plan.Value));
        Program.PhonesChanged?.Invoke();
    }

    /// <summary>S10/I2 route recovery: the phone is durable and the code spent, so the token
    /// MUST reach the phone - truthfulness comes from the fields. The apply runs AFTER that
    /// commit, so its expected IO/access failures (including the I2 N2 explicit failure when a
    /// proven-stale plan has nothing verified to serve) are caught HERE, never escaping as a
    /// bare dispatch 500 that would strand the durable phone and the spent code. The VLC value
    /// rides the reply when verified in effect, or when the apply CONVERGES on a durably owed
    /// live pending (the retry timer completes it); the failure catch below carries NO
    /// credential (conservative disposition: raw fields cannot prove owed vs stale/uncertain),
    /// only the token with vlcPending true. NAMED RESIDUAL (F14 edge): if the emergency
    /// re-stage's persist itself fails, the reply can still carry an owed-but-not-durable
    /// value - pre-existing; the phone's 401/adopt heal converges. vlcPending is true whenever
    /// the value is not verified in effect or a live obligation stands (S10 wire contract), so
    /// the phone finishes setup through its status/adopt checks instead of reading a false
    /// Done.</summary>
    internal static string FinishPairReply(string token, string plannedOrLivePending)
    {
        string? value; bool pending;
        try
        {
            var applied = VlcSetup.ApplyPlannedPassword(plannedOrLivePending);
            value = applied.value;
            pending = !applied.verifiedInEffect || VlcSetup.Pending;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Program.Log("VLC setup could not be verified after the pair was saved - the phone keeps its pairing; an owed password completes via the retry/adopt path.");
            // Conservative disposition (I2 narrow closure): raw fields alone cannot prove a
            // pending record is owed rather than stale/uncertain/superseded, so NO credential
            // rides this failure reply - the durable token always does (the phone never loses
            // its pairing), vlcPending is true unconditionally (the S10 contract: the value
            // was not verified in effect), and the phone finishes via setup-status/adopt
            // while any live owed record completes on the retry timer.
            value = null;
            pending = true;
        }
        return JsonSerializer.Serialize(new
        {
            token = token,
            name = Program.MachineLabel(),
            vlcPassword = value,
            vlcPending = pending,
        });
    }

    /// <summary>F12/D1: the pair save transaction - the phone record and, for a FRESH password
    /// candidate, the pending vlcrc stage persist as ONE save (order ConfigSaveLock ->
    /// PhonesLock). A failed persist rolls BOTH back (phone removed, prior pending snapshot
    /// restored) and returns false: the pair code stays unspent and the route answers 503.
    /// Serve-branch plans stage nothing (current already in effect; branch 5 already durable).</summary>
    internal static bool TrySavePair(string token, ref string? phoneName, string? freshCandidate, out string? livePendingInstead)
    {
        livePendingInstead = null;
        lock (Program.ConfigSaveLock)
        {
            lock (Program.PhonesLock)
            {
                if (string.IsNullOrWhiteSpace(phoneName)) phoneName = "Phone " + (Program.Phones.Count + 1);
                Program.Phones.Add(new Program.PairedPhone { Token = token, Name = phoneName!.Trim() });
            }
            // E2: a superseded pending record is cleared in the SAME save as the phone
            // record - never restaged, never served again.
            var priorPending = VlcSetup.ClearSupersededPendingWithinSaveLock();
            // F12/D1 + I2-3: a fresh candidate is staged in the SAME save - a crash between
            // commit and apply can never leave a durable phone with its password nowhere. But
            // the plan predates this lock: if a DIFFERENT live pending was staged in between
            // (e.g. a /vlc/setup deferral), it is owed and durable - never overwritten; the
            // pair serves that value instead.
            if (freshCandidate != null && !VlcSetup.TryStageFreshCandidateWithinSaveLock(freshCandidate))
            {
                // F15: the owed pending stands; the never-staged candidate is DROPPED so no
                // later caller can apply it. What the phone is served (the owed value) is
                // already durable - D1 crash-safety holds for the value actually served.
                VlcSetup.DropCandidateWithinSaveLock(freshCandidate);
                livePendingInstead = VlcSetup.PendingValueWithinSaveLock;
            }
            try { Program.SaveConfig(); return true; }
            catch (Exception ex)
            {
                VlcSetup.RestorePendingSnapshotWithinSaveLock(priorPending.prior, priorPending.priorSup, priorPending.priorOver, priorPending.priorOverUncertain);
                lock (Program.PhonesLock) { Program.Phones.RemoveAll(p => p.Token == token); }
                Program.Log("Pairing could not be saved: " + ex.Message);
                return false;
            }
        }
    }

    /// <summary>GET returns the shared settings; POST writes them: movies folder, keep-awake, volume pop-up.</summary>
    private static void HandleConfig(HttpListenerRequest req, HttpListenerResponse res)
    {
        if (req.HttpMethod == "GET")
        {
            Reply(res, 200, JsonSerializer.Serialize(new { moviesFolder = Program.MoviesFolder ?? "", keepAwake = Program.KeepAwakeEnabled, volumeOsd = Program.VolumeOsdEnabled }));
            return;
        }
        var root = JsonDocument.Parse(ReadBody(req)).RootElement;
        string? newFolder = root.TryGetProperty("moviesFolder", out var mf) ? (mf.GetString() ?? "").Trim() : null;
        bool? newKeepAwake = root.TryGetProperty("keepAwake", out var ka) && (ka.ValueKind == JsonValueKind.True || ka.ValueKind == JsonValueKind.False) ? ka.GetBoolean() : null;
        bool? newVolumeOsd = root.TryGetProperty("volumeOsd", out var vo) && (vo.ValueKind == JsonValueKind.True || vo.ValueKind == JsonValueKind.False) ? vo.GetBoolean() : null;
        if (!ApplyConfigChange(newFolder, newKeepAwake, newVolumeOsd)) { Reply(res, 503, "{\"ok\":false,\"detail\":\"The PC could not save the change.\"}"); return; }
        Reply(res, 200, "{\"ok\":true}");
    }

    /// <summary>S8: change + persist as one unit. Side effects (sleep rules, pop-up) and log
    /// lines run only after the change is on disk; a refused save rolls the settings back and
    /// returns false, so the phone never shows a state the PC will lose at restart.</summary>
    internal static bool ApplyConfigChange(string? newFolder, bool? newKeepAwake, bool? newVolumeOsd)
    {
        string? priorFolder = null; bool priorKeep = true, priorOsd = true;
        var saved = Program.MutateAndPersist(
            () =>
            {
                priorFolder = Program.MoviesFolder; priorKeep = Program.KeepAwakeEnabled; priorOsd = Program.VolumeOsdEnabled;
                if (newFolder != null) Program.MoviesFolder = newFolder;
                if (newKeepAwake is bool k) Program.KeepAwakeEnabled = k;
                if (newVolumeOsd is bool v) Program.VolumeOsdEnabled = v;
            },
            () => { Program.MoviesFolder = priorFolder; Program.KeepAwakeEnabled = priorKeep; Program.VolumeOsdEnabled = priorOsd; },
            "the settings from the phone");
        if (!saved) return false;
        if (newFolder != null)
            Program.Log("Movies folder set from the phone: " + (newFolder.Length == 0 ? "(cleared)" : newFolder));
        if (newKeepAwake is bool k2)
        {
            // The keep-awake switch: off means the laptop follows its normal sleep rules again.
            if (!k2) PowerWake.SyncToVlc(false);
            Program.Log("Keep-awake while a video is loaded in VLC: " + (k2 ? "on" : "off") + ".");
        }
        if (newVolumeOsd is bool v2)
        {
            // The "Show volume on PC screen" switch: off hides the pop-up immediately.
            if (!v2) VolumeOsd.HideNow();
            Program.Log("Volume pop-up on the PC screen: " + (v2 ? "on" : "off") + ".");
        }
        return true;
    }

    private static void RouteCommand(string path, string body, HttpListenerResponse res)
    {
        // First press while the screen is dark only wakes it - the tap's action is
        // swallowed whole, never half-applied. Continuous touchpad/scroll input is
        // swallowed to the end of the gesture. Reads (volume/get) carry no action and pass.
        var swallowed = path switch
        {
            "/volume/get" => false,
            "/mouse/move" or "/mouse/scroll" or "/mouse/zoom" => PowerWake.ShouldSwallowContinuous(),
            _ => PowerWake.ShouldSwallowDiscrete(),
        };
        if (swallowed)
        {
            Reply(res, 200, "{\"ok\":true,\"woke\":true}");
            return;
        }
        using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body);
        var r = doc.RootElement;
        switch (path)
        {
            case "/mouse/move":
                MoveDiag.RecordMove(viaLiveSocket: false);
                InputSender.Move((float)r.GetProperty("dx").GetDouble(), (float)r.GetProperty("dy").GetDouble());
                break;
            case "/mouse/button":
                InputSender.Button(
                    r.GetProperty("button").GetString() ?? "left",
                    r.GetProperty("down").GetBoolean());
                break;
            case "/mouse/scroll":
                InputSender.Scroll((float)r.GetProperty("dy").GetDouble());
                break;
            case "/mouse/zoom":
                InputSender.Zoom((float)r.GetProperty("delta").GetDouble());
                break;
            case "/volume/get":
            {
                try
                {
                    var (volume, mute) = VolumeControl.Get();
                    Reply(res, 200, JsonSerializer.Serialize(new { volume, mute }));
                }
                catch (VolumeControl.NoAudioDeviceException ex) { NoAudio(res, ex); }
                return;
            }
            case "/volume/set":
            {
                var vol = Math.Clamp(r.GetProperty("volume").GetInt32(), 0, 100);
                try
                {
                    VolumeControl.Set(vol);
                    var (v, m) = VolumeControl.Get();
                    VolumeOsd.Show(v, m);
                }
                catch (VolumeControl.NoAudioDeviceException ex) { NoAudio(res, ex); return; }
                break;
            }
            case "/volume/mute":
                try
                {
                    VolumeControl.SetMute(r.GetProperty("mute").GetBoolean());
                    var (v, m) = VolumeControl.Get();
                    VolumeOsd.Show(v, m);
                }
                catch (VolumeControl.NoAudioDeviceException ex) { NoAudio(res, ex); return; }
                break;
            case "/key":
                InputSender.NamedKey(r.GetProperty("key").GetString() ?? "");
                break;
            case "/type":
                InputSender.TypeText(r.GetProperty("text").GetString() ?? "");
                break;
            default:
                Reply(res, 404, "{\"error\":\"unknown\"}");
                return;
        }
        Reply(res, 200, "{\"ok\":true}");
    }

    /// <summary>Serve one live mouse connection until it drops. Each text frame is a small
    /// JSON command applied as it arrives (frames are ordered, so a click sent after moves
    /// lands after them). No replies: fire-and-forget.</summary>
    private static async Task HandleWs(HttpListenerContext ctx)
    {
        WebSocket? ws = null;
        // This connection's pressed mouse buttons: whatever it still holds when it ends
        // gets released, so a phone that dies mid-drag can't leave the PC's mouse down.
        var pressed = new HashSet<string>();
        // Any frame counts as life (the phone sends a heartbeat every 2 s while open).
        // ~8 s of silence means the phone is gone without a close frame: let go.
        var lastFrameAt = DateTime.UtcNow;
        var lastSeq = -1L;
        using var watchdog = new System.Threading.Timer(_ =>
        {
            if (DateTime.UtcNow - lastFrameAt > TimeSpan.FromSeconds(8))
            {
                Program.Log("Live mouse connection silent for 8s - releasing buttons and closing.");
                try { ws?.Abort(); } catch { }
            }
        }, null, 4000, 4000);
        var token = ctx.Request.Headers["X-BingeMote-Token"] ?? "";
        try
        {
            var wsCtx = await ctx.AcceptWebSocketAsync(null);
            ws = wsCtx.WebSocket;
            Program.Log("Live mouse connection opened.");
            var buf = new byte[4096];
            var text = new StringBuilder();
            while (ws.State == WebSocketState.Open)
            {
                var r = await ws.ReceiveAsync(new ArraySegment<byte>(buf), CancellationToken.None);
                if (r.MessageType == WebSocketMessageType.Close)
                {
                    try { await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "", CancellationToken.None); } catch { }
                    break;
                }
                if (r.MessageType != WebSocketMessageType.Text) continue;
                text.Append(Encoding.UTF8.GetString(buf, 0, r.Count));
                if (!r.EndOfMessage) continue;
                lastFrameAt = DateTime.UtcNow;
                // Removing a phone must stop its input right away, not when it happens to
                // disconnect: the token check only ran at the handshake.
                if (!StillPaired(token))
                {
                    Program.Log("Live mouse connection closed - the phone was removed.");
                    break;
                }
                DispatchWs(text.ToString(), pressed, ref lastSeq);
                text.Clear();
            }
        }
        catch { /* a dropped connection is normal - Wi-Fi blips, the phone sleeps */ }
        finally
        {
            foreach (var b in pressed)
            {
                try { InputSender.Button(b, false); } catch { }
                Program.Log("Released a held " + b + " mouse button from the dropped live connection.");
            }
            try { ws?.Dispose(); } catch { }
            MoveDiag.FlushIfIdle();
            Program.Log("Live mouse connection closed.");
        }
    }

    private static bool StillPaired(string token)
    {
        lock (Program.PhonesLock) { return Program.Phones.Any(p => ConstantTimeEq(token, p.Token)); }
    }

    /// <summary>Apply one frame from the live connection. Anything malformed costs nothing.
    /// Frame numbers are counted: a gap means a frame went missing between phone and PC.</summary>
    private static void DispatchWs(string text, HashSet<string> pressed, ref long lastSeq)
    {
        try
        {
            using var doc = JsonDocument.Parse(text);
            var r = doc.RootElement;
            var cmd = r.GetProperty("cmd").GetString();
            if (cmd == "heartbeat") return; // liveness only, applied above
            if (r.TryGetProperty("seq", out var sq))
            {
                var n = sq.GetInt64();
                if (lastSeq >= 0 && n != lastSeq + 1)
                    Program.Log($"Live connection frame gap: expected {lastSeq + 1}, got {n} ({n - lastSeq - 1} lost).");
                lastSeq = n;
            }
            // First touch while the screen is dark only wakes it: the frame is consumed
            // whole (no half-gestures, no leaked clicks) and nothing is injected.
            if (cmd == "button" ? PowerWake.ShouldSwallowDiscrete() : PowerWake.ShouldSwallowContinuous()) return;
            switch (cmd)
            {
                case "move": MoveDiag.RecordMove(viaLiveSocket: true); InputSender.Move((float)r.GetProperty("dx").GetDouble(), (float)r.GetProperty("dy").GetDouble()); break;
                case "button":
                    var b = r.GetProperty("button").GetString() ?? "left";
                    var down = r.GetProperty("down").GetBoolean();
                    if (down) pressed.Add(b); else pressed.Remove(b);
                    InputSender.Button(b, down);
                    break;
                case "scroll": InputSender.Scroll((float)r.GetProperty("dy").GetDouble()); break;
                case "zoom": InputSender.Zoom((float)r.GetProperty("delta").GetDouble()); break;
            }
        }
        catch { }
    }

    /// <summary>503 with a reason the phone can show, instead of a bare "internal" error.</summary>
    private static void NoAudio(HttpListenerResponse res, VolumeControl.NoAudioDeviceException ex)
    {
        Reply(res, 503, JsonSerializer.Serialize(new { error = "no audio device", detail = ex.Message }));
    }

    private static string ReadBody(HttpListenerRequest req)
    {
        if (req.ContentLength64 > 64 * 1024) throw new InvalidDataException("Request too large");
        return ReadBoundedBody(req.InputStream).GetAwaiter().GetResult();
    }

    internal static async Task<string> ReadBoundedBody(Stream input) {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        using var output = new MemoryStream();
        var buffer = new byte[4096];
        while (true) {
            var n = await input.ReadAsync(buffer.AsMemory(), timeout.Token).AsTask().WaitAsync(timeout.Token);
            if (n == 0) break;
            if (output.Length + n > 64 * 1024) throw new InvalidDataException("Request too large");
            output.Write(buffer, 0, n);
        }
        return Encoding.UTF8.GetString(output.ToArray());
    }

    private static void Reply(HttpListenerResponse res, int code, string json)
    {
        var bytes = Encoding.UTF8.GetBytes(json);
        res.StatusCode = code;
        res.ContentType = "application/json";
        res.ContentLength64 = bytes.Length;
        res.OutputStream.Write(bytes, 0, bytes.Length);
        res.OutputStream.Close();
    }

    /// <summary>Loopback or RFC1918 / link-local: a home network, not the internet.</summary>
    internal static bool IsPrivateAddress(IPAddress ip)
    {
        if (IPAddress.IsLoopback(ip)) return true;
        var b = ip.GetAddressBytes();
        if (b.Length != 4) return false;
        return b[0] == 10
               || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
               || (b[0] == 192 && b[1] == 168)
               || (b[0] == 169 && b[1] == 254);
    }

    private static bool ConstantTimeEq(string a, string b)
    {
        var ab = Encoding.UTF8.GetBytes(a);
        var bb = Encoding.UTF8.GetBytes(b);
        return CryptographicOperations.FixedTimeEquals(
            ab.Length == bb.Length ? ab : new byte[bb.Length], bb) && a.Length == b.Length;
    }

    public void Dispose()
    {
        _running = false;
        try { _listener.Stop(); } catch { }
    }
}

/// <summary>Answers UDP discovery broadcasts so the phone can find this PC by itself.</summary>
internal sealed class DiscoveryResponder : IDisposable
{
    private UdpClient? _udp;
    private Thread? _thread;
    private volatile bool _running;

    public void Start()
    {
        _udp = new UdpClient(Program.DiscoveryPort) { EnableBroadcast = true };
        _running = true;
        _thread = new Thread(Loop) { IsBackground = true };
        _thread.Start();
    }

    private void Loop()
    {
        var any = new IPEndPoint(IPAddress.Any, 0);
        while (_running)
        {
            try
            {
                var data = _udp!.Receive(ref any);
                if (!HttpServer.IsPrivateAddress(any.Address)) continue;
                if (Encoding.UTF8.GetString(data).Trim() != Program.DiscoverMagic) continue;
                var payload = $"{Program.ReplyMagic}|{Program.MachineLabel()}|{Program.HttpPort}";
                var reply = Encoding.UTF8.GetBytes(payload);
                _udp.Send(reply, reply.Length, any);
            }
            catch
            {
                if (!_running) break;
                // A repeating network error (adapter flapping) must not spin the core:
                // wait a beat before listening again.
                try { Thread.Sleep(200); } catch { }
            }
        }
    }

    public void Dispose()
    {
        _running = false;
        try { _udp?.Close(); } catch { }
    }
}
