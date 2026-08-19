package com.codex.quota.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Secure per-account storage for the OAuth tokens. Each account lives in its own
 * EncryptedSharedPreferences file (same Keystore master key, different file), so
 * accounts are fully isolated. Never log these values, never store them in DataStore,
 * never show them in the UI.
 */
class CodexAuthStore(context: Context) {

    private val appContext = context.applicationContext
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private fun prefs(accountId: String): SharedPreferences = EncryptedSharedPreferences.create(
        "codex_auth_$accountId",
        masterKeyAlias,
        appContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isLoggedIn(accountId: String): Boolean = prefs(accountId).contains(KEY_ACCESS)

    fun tokens(accountId: String): CodexTokens? {
        val access = prefs(accountId).getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs(accountId).getString(KEY_REFRESH, null) ?: return null
        return CodexTokens(
            access,
            refresh,
            prefs(accountId).getString(KEY_ACCOUNT, null),
            prefs(accountId).getString(KEY_EMAIL, null)
        )
    }

    fun save(accountId: String, tokens: CodexTokens) {
        val editor = prefs(accountId).edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
        if (tokens.accountId != null) editor.putString(KEY_ACCOUNT, tokens.accountId)
        if (tokens.email != null) editor.putString(KEY_EMAIL, tokens.email)
        editor.apply()
    }

    fun clear(accountId: String) = prefs(accountId).edit().clear().apply()

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCOUNT = "account_id"
        const val KEY_EMAIL = "email"
    }
}
