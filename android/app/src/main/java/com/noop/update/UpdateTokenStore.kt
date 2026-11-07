package com.noop.update

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * At-rest-encrypted storage for the GitHub token that lets the phone read releases from the PRIVATE
 * repository. Same construction as [com.noop.ai.AiKeyStore]: values are encrypted under a key held
 * in the Android Keystore, hardware-backed where available.
 *
 * The token should be a FINE-GRAINED personal access token scoped to `ronboger/noop` alone with
 * `Contents: Read-only`. It is optional — with no token the updater falls back to the public check
 * and the rest of the app is unaffected.
 */
object UpdateTokenStore {

    private const val FILE_NAME = "noop_update_secure_prefs"
    private const val KEY_TOKEN = "github_token"

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The stored token, or null when unset or blank. Never throws — a Keystore failure resolves to
     *  null so the app degrades to the public check rather than crashing. */
    fun token(ctx: Context): String? = runCatching {
        prefs(ctx).getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun hasToken(ctx: Context): Boolean = token(ctx) != null

    fun save(ctx: Context, token: String) {
        runCatching { prefs(ctx).edit().putString(KEY_TOKEN, token.trim()).apply() }
    }

    fun clear(ctx: Context) {
        runCatching { prefs(ctx).edit().remove(KEY_TOKEN).apply() }
    }
}
