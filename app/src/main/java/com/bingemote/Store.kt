package com.bingemote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * App settings in plain preferences; secrets (VLC password, helper token) in
 * Keystore-backed encrypted preferences, same pattern as Listen Later's CredStore.
 */
/** The primary constructor doubles as a test seam: settings code runs over an
 *  in-memory config, so a saved state can be exercised on the JVM without a Context. */
class Store internal constructor(
    private val prefs: android.content.SharedPreferences,
    private val secrets: android.content.SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        openSecrets(context),
    )

    init {
        // Secure-only transport reads the loaded record from this store; unpaired, legacy, interrupted or unreadable yields null and nothing is sent.
        HelperClient.pairingSource = { (helperPairing() as? PairedCredential.State.Secure)?.credential }
        // 1.9 removals: the seek bar is always on top and reconnect is always on.
        // Drop the retired keys so no stale setting lingers on existing installs.
        prefs.edit().remove("seekOnTop").remove("autoReconnect").remove("cancelStyle").apply()
    }

    /** True when the Keystore-backed store could not open this launch (teardown C11).
     *  NOTHING is ever auto-deleted: the encrypted file and the master key stay exactly
     *  as they are, the app keeps working on an in-memory stand-in, and recovery is a
     *  plain error + re-pair, by hand, never a wipe. */
    private val storeOpenFailed: Boolean = secrets is InMemoryPrefs
    /** Failed to open this launch, or a secret read failed since (the box then refuses writes). */
    val secureStoreBroken: Boolean get() = storeOpenFailed || secretBox.isBroken

    /** Fail-closed gate for every secret write: while the store is broken a write
     *  THROWS instead of landing in memory and vanishing at restart (no fake-persisted
     *  pairings). Reads still return "" so the app honestly shows unpaired. */
    private val secretBox = FailClosedSecrets(
        reader = { k -> secrets.getString(k, null) },
        writer = { k, v -> val ed = secrets.edit(); if (v == null) ed.remove(k) else ed.putString(k, v); ed.apply() },
        broken = storeOpenFailed,
    )

    /** Blocks until pending plain and secret writes are on disk; false when either commit is refused.
     *  Pairing uses it so "saved" means durable, not just queued. */
    fun commitPairing(): Boolean = try { prefs.edit().commit() && !secretBox.isBroken && secrets.edit().commit() } catch (e: Exception) { false }

    var pcName: String
        get() = prefs.getString("pcName", "") ?: ""
        set(v) = synchronized(pairingLock) { try { prefs.edit().putString("pcName", v).apply() } finally { bumpPairingGenerationLocked() } }

    /** Last known helper/VLC address. Rediscovered over UDP when it changes. */
    var host: String
        get() = (helperPairing() as? PairedCredential.State.Secure)?.credential?.host ?: (prefs.getString("host", "") ?: "")
        set(v) = synchronized(pairingLock) { try { prefs.edit().putString("host", v).apply() } finally { bumpPairingGenerationLocked() } }

    var vlcPort: Int
        get() = prefs.getInt("vlcPort", 8080)
        set(v) = synchronized(pairingLock) { try { prefs.edit().putInt("vlcPort", v).apply() } finally { bumpPairingGenerationLocked() } }

    var helperPort: Int
        get() = (helperPairing() as? PairedCredential.State.Secure)?.credential?.port ?: prefs.getInt("helperPort", 8488)
        set(v) = synchronized(pairingLock) { try { prefs.edit().putInt("helperPort", v).apply() } finally { bumpPairingGenerationLocked() } }

    /// Touchpad gain: 1.0 = raw finger movement. Range 0.5-2.0 in 0.1 steps (the steppers enforce it).
    var mouseSpeed: Float
        get() = prefs.getFloat("mouseSpeed", 1.0f)
        set(v) = prefs.edit().putFloat("mouseSpeed", v).apply()

    var moviesPath: String
        get() = normalizeWinPath(prefs.getString("moviesPath", "") ?: "")
        set(v) = prefs.edit().putString("moviesPath", normalizeWinPath(v)).apply()

    /** Conditional moviesPath write: lands ONLY while the pairing generation is still the one
     *  the caller captured with its fetch, atomically under [pairingLock] - a re-pair or forget
     *  in between skips the write whole, so an old PC's folder value never lands under a new
     *  pairing. Returns true when the write landed. */
    fun setMoviesPathIfCurrent(v: String, gen: Long): Boolean = synchronized(pairingLock) {
        if (pairingGeneration != gen) false else { moviesPath = v; true }
    }

    var skipMainSec: Int
        // A stored value that is no longer offered falls back to the default.
        get() = prefs.getInt("skipMainSec", 10).takeIf { it in MAIN_SKIP_OPTIONS } ?: 10
        set(v) = prefs.edit().putInt("skipMainSec", v).apply()

    var skipSecondarySec: Int
        get() = prefs.getInt("skipSecondarySec", 30).takeIf { it in SECONDARY_SKIP_OPTIONS } ?: 30
        set(v) = prefs.edit().putInt("skipSecondarySec", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    /** Keep the PC awake while a video is loaded in VLC (screen may still turn off;
     * minimized VLC sleeps normally). Default ON for new installs; a saved choice is kept.
     * The PC behavior itself ships with the reviewed helper change; helpers that don't
     * know the setting ignore it. */
    var keepAwake: Boolean
        get() = prefs.getBoolean("keepAwake", true)
        set(v) = prefs.edit().putBoolean("keepAwake", v).apply()

    var showVolumeOnPc: Boolean
        get() = prefs.getBoolean("showVolumeOnPc", true)
        set(v) = prefs.edit().putBoolean("showVolumeOnPc", v).apply()

    /** Play/pause/skip + position on the lock screen while a movie plays. Off by default. */
    var lockScreenControls: Boolean
        get() = prefs.getBoolean("lockScreenControls", false)
        set(v) = prefs.edit().putBoolean("lockScreenControls", v).apply()

    // CI screenshot state (debug builds only): MediaControlsService seeds a demo session.
    var demoControls: Boolean
        get() = prefs.getBoolean("demoControls", false)
        set(v) = prefs.edit().putBoolean("demoControls", v).apply()

    /** Press flash on the home screen: 0 = skip rings only, 1 = every button. */
    var buttonFlash: Int
        get() = prefs.getInt("buttonFlash", 1).coerceIn(0, 1)
        set(v) = prefs.edit().putInt("buttonFlash", v).apply()

    var setupDone: Boolean
        get() = prefs.getBoolean("setupDone", false)
        set(v) = prefs.edit().putBoolean("setupDone", v).apply()

    /** A version the user chose to skip; not offered again. */
    var skippedUpdate: String?
        get() = prefs.getString("skipped_update", null)
        set(v) = prefs.edit().putString("skipped_update", v).apply()

    var vlcPassword: String
        get() = secretBox.read("vlcPassword")
        set(v) = synchronized(pairingLock) { try { secretBox.write("vlcPassword", v) } finally { bumpPairingGenerationLocked() } }

    /** The in-flight adoption request id, kept until the PC acks so a crash mid-fetch
     *  replays the same id instead of burning the one-shot grant. */
    var vlcAdoptRequestId: String?
        get() = secretBox.read("vlcAdoptRequestId").ifEmpty { null }
        set(v) = secretBox.write("vlcAdoptRequestId", v)

    /** The secure record's token when one is loaded; otherwise the raw old value, which the secure transport never sends. */
    var helperToken: String
        get() = (helperPairing() as? PairedCredential.State.Secure)?.credential?.token ?: secretBox.read("helperToken")
        set(v) = synchronized(pairingLock) { try { secretBox.write("helperToken", v) } finally { bumpPairingGenerationLocked() } }

    /** Secure pairing persistence (credential record + write-ahead marker), same secret store and flush as the token. */
    internal val securePairStore: SecurePairStore = object : SecurePairStore {
        // Each write is a synchronous commit() that must report success; a refused commit throws, so the save logic sees it.
        private fun syncWrite(k: String, v: String) {
            if (secretBox.isBroken) throw SecureStoreUnavailable()
            val ed = secrets.edit(); if (v.isEmpty()) ed.remove(k) else ed.putString(k, v)
            if (!ed.commit()) throw IllegalStateException("secret commit refused")
        }
        override var credential: String get() = secretBox.read("helperCredential"); set(v) = syncWrite("helperCredential", v)
        override var intent: String get() = secretBox.read("helperIntent"); set(v) = syncWrite("helperIntent", v)
        override var vlcPassword: String get() = this@Store.vlcPassword; set(v) { this@Store.vlcPassword = v }
        override var pcName: String get() = this@Store.pcName; set(v) { this@Store.pcName = v }
        override var legacyToken: String get() = secretBox.read("helperToken"); set(v) = secretBox.write("helperToken", v)
        override fun flush(): Boolean = commitPairing()
        override fun healthy(): Boolean = !secureStoreBroken
    }
    /** Usable only when Secure. Legacy-only, interrupted, corrupt or unreadable storage is never usable. */
    internal fun helperPairing(): PairedCredential.State = SecurePairSave.load(securePairStore)

    /** One coherent direct-VLC target: the host, port and password read inside the same lock
     *  every writer of them takes, so a re-pair, forget, rediscovery or heal can never land
     *  between the host and the password of one send. The secure record's host wins; the plain
     *  prefs host is the legacy fallback (preserved). A BLANK password stays a valid member
     *  (legacy manual-setup VLC with an empty password keeps working exactly as today). The
     *  store health check runs AFTER the reads: a poison mid-read returns null - an honest
     *  unavailable, never a partial tuple. Null also when no host exists. A snapshot, not a
     *  cache: nothing is retained between calls. */
    fun vlcTarget(): VlcTarget? = synchronized(pairingLock) {
        val p = helperPairing()                                        // ONE pairing load
        val c = (p as? PairedCredential.State.Secure)?.credential
        val h = c?.host ?: (prefs.getString("host", "") ?: "")         // record host else legacy plain fallback
        val port = prefs.getInt("vlcPort", 8080)
        val pw = secretBox.read("vlcPassword")
        if (secretBox.isBroken) null                                   // poison mid-read: honest unavailable
        else if (h.isBlank()) null
        else VlcTarget(h, port, pw, c != null)
    }

    /** The helper endpoint AND the VLC credentials in the same locked section (see
     *  [HelperVlcSnapshot]). Same fallback and health-check rules as [vlcTarget]; null on a
     *  poisoned store or a blank host. */
    fun helperVlcSnapshot(): HelperVlcSnapshot? = synchronized(pairingLock) {
        val p = helperPairing()                                        // ONE pairing load
        val c = (p as? PairedCredential.State.Secure)?.credential
        val h = c?.host ?: (prefs.getString("host", "") ?: "")
        val hp = c?.port ?: prefs.getInt("helperPort", 8488)
        val tok = c?.token ?: secretBox.read("helperToken")            // record token else legacy raw fallback
        val vp = prefs.getInt("vlcPort", 8080)
        val pw = secretBox.read("vlcPassword")
        if (secretBox.isBroken) null
        else if (h.isBlank()) null
        else HelperVlcSnapshot(h, hp, tok, vp, pw, c != null)
    }

    /** One locked capture: the helper/VLC snapshot AND the pairing generation of the SAME
     *  record, so a caller fetching with the snapshot keys its later conditional write on the
     *  generation that was current WHEN THE SNAPSHOT WAS TAKEN - never a generation read after
     *  the network fetch, which a mid-fetch re-pair would make match the new pairing again. */
    fun helperVlcSnapshotWithGeneration(): Pair<HelperVlcSnapshot?, Long> = synchronized(pairingLock) {
        helperVlcSnapshot() to pairingGeneration
    }

    /** ONE locked capture of the pairing status (helper token present? VLC password
     *  present?) AND the pairing generation of the SAME state: two separate getter reads
     *  could mix one pairing's token with another's password. Uses the SAME property
     *  getters with their existing fallback/blank/broken semantics - only the atomicity
     *  and the generation are added. */
    fun pairingStatusWithGeneration(): Triple<Boolean, Boolean, Long> = synchronized(pairingLock) {
        Triple(helperToken.isNotBlank(), vlcPassword.isNotBlank(), pairingGeneration)
    }

    /** Capture a [PairingSequence] for one command sequence; null on a poisoned store or a
     *  blank host, the same conditions (and the same honest-unavailable outcome) as
     *  [helperVlcSnapshot]. */
    fun pairingSequence(): PairingSequence? = synchronized(pairingLock) {
        val snap = helperVlcSnapshot()
        if (snap == null) null else PairingSequence(this, snap, pairingGeneration)
    }

    /** The pairing mutation generation: advanced under [pairingLock] by EVERY writer of the
     *  pairing record or the helper/VLC target fields (the locked setters above and every
     *  SecurePairSave transaction, whatever its outcome - a failed save can still have landed
     *  writes). A plain volatile read: storage-free and lock-free, safe on Main. A tap captures
     *  it and the IO action re-checks after its locked snapshot: a change means the snapshot may
     *  belong to a different pairing than the tap, so the action drops instead of retargeting. */
    val pairingGeneration: Long get() = pairingMutationGeneration

    /** Everything RemoteState's poll needs from the pairing store, from ONE locked read (see
     *  [pairingLock]): the same fallback rules as the individual getters (the secure record wins,
     *  legacy plain/secret fallbacks preserved) with NO usability gating - blank values stay the
     *  caller's decision, exactly as the separate getters behaved. Pair it with
     *  [pairingGeneration]: capture the generation before the IO read and re-check it on Main
     *  before publishing anything derived from the snapshot. */
    internal fun pollSnapshot(): PollSnapshot = synchronized(pairingLock) {
        val c = (helperPairing() as? PairedCredential.State.Secure)?.credential
        PollSnapshot(
            host = c?.host ?: (prefs.getString("host", "") ?: ""),
            helperPort = c?.port ?: prefs.getInt("helperPort", 8488),
            helperToken = c?.token ?: secretBox.read("helperToken"),
            vlcPort = prefs.getInt("vlcPort", 8080),
            vlcPassword = secretBox.read("vlcPassword"),
            pcId = c?.pcId,
            secure = c != null,
            // The WHOLE record identity from the same locked section: post-heal publishes
            // compare against it, so a same-token endpoint move fails the compare too.
            encode = c?.encode(),
            // AFTER the reads, like vlcTarget/helperVlcSnapshot: a poison mid-read marks the
            // snapshot broken instead of letting partially-"" values pass for healthy.
            broken = secretBox.isBroken,
        )
    }


    /** Notification permission was already requested once (teardown C1: asked once at
     *  the first phone stream on Android 13+, never nagged again). */
    var notifPermissionAsked: Boolean
        get() = prefs.getBoolean("notifPermissionAsked", false)
        set(v) = prefs.edit().putBoolean("notifPermissionAsked", v).apply()

    /** Assigned Android movies folder (persisted SAF tree URI) for the Movies screen's Phone origin. */
    var moviesPhoneTreeUri: String
        get() = prefs.getString("moviesPhoneTreeUri", "") ?: ""
        set(v) = prefs.edit().putString("moviesPhoneTreeUri", v).apply()

    val isConfigured: Boolean
        get() = setupDone && helperPairing() is PairedCredential.State.Secure && host.isNotBlank() && vlcPassword.isNotBlank()

    /** An old plain-HTTP pairing, an interrupted save or an unreadable store: shown as "pair again", never as paired. */
    val pairingNeedsRepair: Boolean
        get() = setupDone && helperPairing().let { it is PairedCredential.State.NeedsRepair || it is PairedCredential.State.Blocked }
}

private fun openSecrets(context: Context): android.content.SharedPreferences = try {
    EncryptedSharedPreferences.create(
        context,
        "secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
} catch (e: Exception) {
    InMemoryPrefs()
}

/** Read/write stand-in while the real encrypted store can't open: values live only
 *  for this process and are NEVER written to disk (no plaintext secrets, no deletes). */
private class InMemoryPrefs : android.content.SharedPreferences {
    private val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    private inner class Editor : android.content.SharedPreferences.Editor {
        private val writes = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clear = false
        override fun putString(key: String?, value: String?) = apply { key?.let { writes[it] = value; removals.remove(it) } }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { key?.let { writes[it] = values } }
        override fun putInt(key: String?, value: Int) = apply { key?.let { writes[it] = value } }
        override fun putLong(key: String?, value: Long) = apply { key?.let { writes[it] = value } }
        override fun putFloat(key: String?, value: Float) = apply { key?.let { writes[it] = value } }
        override fun putBoolean(key: String?, value: Boolean) = apply { key?.let { writes[it] = value } }
        override fun remove(key: String?) = apply { key?.let { removals.add(it); writes.remove(it) } }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { applyWrites(); return true }
        override fun apply() { applyWrites() }
        private fun applyWrites() {
            if (clear) map.clear()
            for (r in removals) map.remove(r)
            for ((k, v) in writes) map[k] = v
        }
    }
}


/** One process-wide lock for pairing state: every writer of the pairing record or the VLC
 *  target fields takes it, and coherent readers (Store.vlcTarget) assemble their snapshot
 *  inside it - a re-pair, forget, rediscovery or heal lands before or after a read, never
 *  between the host and the password. Leaf lock: nothing under it acquires another lock. */
internal val pairingLock = Any()

/** The counter behind [Store.pairingGeneration]. Volatile: reads never touch storage or the lock. */
@Volatile private var pairingMutationGeneration = 0L

/** Advance the pairing mutation generation. The caller MUST hold [pairingLock]: the bump is part
 *  of the same atomic section as the writes it marks, so a reader holding the lock that still
 *  sees the old generation is guaranteed none of this mutation's writes have landed. */
internal fun bumpPairingGenerationLocked() { pairingMutationGeneration++ }

/** A coherent direct-VLC target: host, port and password from ONE locked read. [secure] is
 *  true when the host came from the secure pairing record, false for the legacy plain fallback. */
data class VlcTarget(val host: String, val port: Int, val password: String, val secure: Boolean)

/** A coherent helper endpoint AND VLC credentials snapshot from ONE locked read: a call that
 *  carries both (the helper route that starts a movie with VLC params) can never mix one
 *  pairing's identity with another pairing's VLC password. The WIP16 token gate then refuses
 *  the send if a re-pair landed between this capture and the request. */
data class HelperVlcSnapshot(val host: String, val helperPort: Int, val helperToken: String, val vlcPort: Int, val vlcPassword: String, val secure: Boolean)

/** One command sequence's ownership of the pairing it captured: [snapshot] and the generation
 *  come from ONE locked section (never a post-fetch read), every network call in the sequence
 *  uses the snapshot's fields, and [isCurrent] turning false drops the rest of the sequence
 *  instead of redirecting it onto the new pairing. */
class PairingSequence internal constructor(
    private val store: Store,
    /** The one target every call in the sequence uses. */
    val snapshot: HelperVlcSnapshot,
    private val gen: Long,
) {
    /** True while the pairing is still the captured one; false = drop the rest of the sequence. */
    fun isCurrent(): Boolean = store.pairingGeneration == gen
}

/** One locked read of everything RemoteState's poll needs from the pairing store (see [Store.pollSnapshot]). */
internal data class PollSnapshot(val host: String, val helperPort: Int, val helperToken: String, val vlcPort: Int, val vlcPassword: String, val pcId: String?, val secure: Boolean, val broken: Boolean, val encode: String?)

/** VLC sometimes hands back mixed separators ("Desktop\/Movies"); store and show one clean form. Keeps a leading "\\" (network share) intact. */
private fun normalizeWinPath(p: String): String =
    // Lambda replacement on purpose: a "\" string replacement is an invalid escape and throws on a match.
    p.trim().replace('/', '\\').replace(Regex("(?<=.)\\\\{2,}")) { "\\" }
