package com.rohit.one.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import android.content.SharedPreferences

object AuthTokenStore {
    private const val PREFS_NAME = "com.rohit.one.auth.tokens"
    private const val KEY_ALIAS = "com.rohit.one.auth.master_key"

    // Ensure an AES key exists in the AndroidKeyStore for our alias
    private fun ensureKeyExists() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    @Suppress("DEPRECATION") // No direct replacement for secure shared preferences as of Dec 2025
    private fun prefs(context: Context): SharedPreferences {
        ensureKeyExists()
        val masterKey = MasterKey.Builder(context, KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Backwards-compatible: userId is optional and defaults to null.
    fun saveTokens(context: Context, accessToken: String, refreshToken: String?, expiresAt: Long, userId: String? = null) {
        prefs(context).edit(commit = true) {
            putString("access_token", accessToken)
            putString("refresh_token", refreshToken)
            putLong("expires_at", expiresAt)
            if (!userId.isNullOrBlank()) putString("user_id", userId) else remove("user_id")
        }
    }

    fun getAccessToken(context: Context): String? = prefs(context).getString("access_token", null)
    fun getExpiresAt(context: Context): Long = prefs(context).getLong("expires_at", 0L)
    fun getUserId(context: Context): String? = prefs(context).getString("user_id", null)
    fun clear(context: Context) {
        prefs(context).edit(commit = true) { clear() }
    }
}
