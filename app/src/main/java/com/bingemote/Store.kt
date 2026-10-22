package com.bingemote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * App settings in plain preferences; secrets (VLC password, helper token) in
 * Keystore-backed encrypted preferences, same pattern as Listen Later's CredStore.
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val secrets = EncryptedSharedPreferences.create(
        context,
        "secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var pcName: String
        get() = prefs.getString("pcName", "") ?: ""
        set(v) = prefs.edit().putString("pcName", v).apply()

    /** Last known helper/VLC address. Rediscovered over UDP when it changes. */
    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v).apply()

    var vlcPort: Int
        get() = prefs.getInt("vlcPort", 8080)
        set(v) = prefs.edit().putInt("vlcPort", v).apply()

    var helperPort: Int
        get() = prefs.getInt("helperPort", 8488)
        set(v) = prefs.edit().putInt("helperPort", v).apply()

    /// Touchpad gain: 1.0 = raw finger movement. Range 0.5-2.0 in 0.1 steps (the steppers enforce it).
    var mouseSpeed: Float
        get() = prefs.getFloat("mouseSpeed", 1.0f)
        set(v) = prefs.edit().putFloat("mouseSpeed", v).apply()

    var moviesPath: String
        get() = normalizeWinPath(prefs.getString("moviesPath", "") ?: "")
        set(v) = prefs.edit().putString("moviesPath", normalizeWinPath(v)).apply()

    var skipMainSec: Int
        // A stored value that is no longer offered falls back to the default.
        get() = prefs.getInt("skipMainSec", 5).takeIf { it in MAIN_SKIP_OPTIONS } ?: 5
        set(v) = prefs.edit().putInt("skipMainSec", v).apply()

    var skipSecondarySec: Int
        get() = prefs.getInt("skipSecondarySec", 30).takeIf { it in SECONDARY_SKIP_OPTIONS } ?: 30
        set(v) = prefs.edit().putInt("skipSecondarySec", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    /** Press flash on the home screen: 0 = skip rings only, 1 = every button. */
    var buttonFlash: Int
        get() = prefs.getInt("buttonFlash", 1).coerceIn(0, 1)
        set(v) = prefs.edit().putInt("buttonFlash", v).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean("autoReconnect", true)
        set(v) = prefs.edit().putBoolean("autoReconnect", v).apply()

    var setupDone: Boolean
        get() = prefs.getBoolean("setupDone", false)
        set(v) = prefs.edit().putBoolean("setupDone", v).apply()

    /** A version the user chose to skip; not offered again. */
    var skippedUpdate: String?
        get() = prefs.getString("skipped_update", null)
        set(v) = prefs.edit().putString("skipped_update", v).apply()

    var vlcPassword: String
        get() = secrets.getString("vlcPassword", "") ?: ""
        set(v) = secrets.edit().putString("vlcPassword", v).apply()

    var helperToken: String
        get() = secrets.getString("helperToken", "") ?: ""
        set(v) = secrets.edit().putString("helperToken", v).apply()

    val isConfigured: Boolean
        get() = setupDone && host.isNotBlank() && vlcPassword.isNotBlank()
}

/** VLC sometimes hands back mixed separators ("Desktop\/Movies"); store and show one clean form. Keeps a leading "\\" (network share) intact. */
private fun normalizeWinPath(p: String): String =
    // Lambda replacement on purpose: a "\" string replacement is an invalid escape and throws on a match.
    p.trim().replace('/', '\\').replace(Regex("(?<=.)\\\\{2,}")) { "\\" }
