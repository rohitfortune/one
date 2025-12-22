package com.rohit.one.auth

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCustomCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCustomCredentialOption
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * CredentialAuthStore: uses AndroidX Credential Manager (CustomCredential) to persist tokens.
 * Stores a small JSON blob with access_token, refresh_token, expires_at under Bundle key "payload".
 */
object CredentialAuthStore {
    private const val TAG = "CredentialAuthStore"
    private const val CRED_TYPE = "com.rohit.one.GENERIC_CREDENTIAL"
    private const val PAYLOAD_KEY = "payload"

    suspend fun saveTokens(context: Context, accessToken: String, refreshToken: String?, expiresAt: Long): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val manager = CredentialManager.create(context)
            val payloadJson = JSONObject().apply {
                put("access_token", accessToken)
                put("refresh_token", refreshToken ?: JSONObject.NULL)
                put("expires_at", expiresAt)
            }.toString()

            // Put the JSON bytes into a Bundle under PAYLOAD_KEY
            val credentialData = Bundle().apply {
                putByteArray(PAYLOAD_KEY, payloadJson.toByteArray(Charsets.UTF_8))
            }

            val candidateQueryData = Bundle()
            val displayInfo = CreateCredentialRequest.DisplayInfo("One")

            val createReq = CreateCustomCredentialRequest(
                CRED_TYPE,
                credentialData,
                candidateQueryData,
                /* isSystemProviderRequired = */ false,
                displayInfo,
            )

            // createCredential requires the context as the first parameter
            manager.createCredential(context, createReq)
            Log.d(TAG, "Saved tokens to Credential Manager")
            // Clear explicit sign-out marker when we successfully save a token
            try {
                val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
                prefs.edit().remove("user_signed_out")
            } catch (_: Exception) {}
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save tokens to Credential Manager: ${e.message}")
            // Fallback: persist securely using AuthTokenStore (EncryptedSharedPreferences)
            try {
                AuthTokenStore.saveTokens(context, accessToken, refreshToken, expiresAt)
                Log.w(TAG, "Saved tokens to AuthTokenStore fallback due to CredentialManager failure")
                try {
                    val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
                    prefs.edit().remove("user_signed_out")
                } catch (_: Exception) {}
                return@withContext true
            } catch (e2: Exception) {
                Log.e(TAG, "AuthTokenStore fallback failed: ${e2.message}")
            }
            false
        }
    }

    suspend fun getAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        // If the user explicitly signed out, do not return any stored token (prevent silent sign-in)
        try {
            val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("user_signed_out", false)) {
                Log.d(TAG, "User recently signed out; skipping stored credential return")
                return@withContext null
            }
        } catch (_: Exception) {}
        try {
            val manager = CredentialManager.create(context)

            // Build a GetCredentialRequest asking for our custom credential type
            val requestData = Bundle()
            val option = GetCustomCredentialOption(
                CRED_TYPE,
                requestData,
                Bundle(),
                /* isSystemProviderRequired = */ false,
            )
            val getReq = GetCredentialRequest.Builder().addCredentialOption(option).build()

            val response: GetCredentialResponse = manager.getCredential(context, getReq)
            val cred = response.credential
            val dataBundle = cred.data

            // Read payload (bytes) or string
            val payloadBytes = when {
                dataBundle.containsKey(PAYLOAD_KEY) -> dataBundle.getByteArray(PAYLOAD_KEY)
                dataBundle.containsKey(PAYLOAD_KEY + "_str") -> dataBundle.getString(PAYLOAD_KEY + "_str")?.toByteArray(Charsets.UTF_8)
                else -> null
            }

            val json = payloadBytes?.let { String(it, Charsets.UTF_8) }
            if (!json.isNullOrBlank()) {
                val obj = JSONObject(json)
                val token = if (obj.has("access_token") && !obj.isNull("access_token")) obj.getString("access_token") else null
                if (!token.isNullOrBlank()) {
                    Log.d(TAG, "Restored token from Credential Manager")
                    // Ensure we do not return token if user explicitly signed out (double-check)
                    try {
                        val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
                        if (prefs.getBoolean("user_signed_out", false)) {
                            Log.d(TAG, "User signed out flag present after reading credential; ignoring token")
                            return@withContext null
                        }
                    } catch (_: Exception) {}
                    return@withContext token
                }
            }
        } catch (e: GetCredentialException) {
            Log.d(TAG, "No credential found in Credential Manager: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Credential Manager get failed: ${e.message}")
        }

        // Fallback: read from secure EncryptedSharedPreferences (AuthTokenStore)
        try {
            val token = AuthTokenStore.getAccessToken(context)
            // If user explicitly signed out, ignore fallback token too
            try {
                val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("user_signed_out", false)) {
                    Log.d(TAG, "User signed out; ignoring AuthTokenStore fallback token")
                    return@withContext null
                }
            } catch (_: Exception) {}
            if (!token.isNullOrBlank()) {
                Log.i(TAG, "Using token from AuthTokenStore fallback")
                return@withContext token
            }
        } catch (e: Exception) {
            Log.w(TAG, "AuthTokenStore read failed: ${e.message}")
        }

        // NOTE: Legacy debug/test token fallback was intentionally removed.
        // Returning a test token (prefixed with TEST_) caused backup attempts to use invalid credentials
        // and produce hard-to-debug 401 errors. If you need to inject tokens for local development,
        // write them to the debug_stored_token.txt file (created by fetchAccessToken) and handle them
        // explicitly in a diagnostic flow. For normal app operation we will not return debug tokens.

        // Previously returned null here when no token found

         return@withContext null
    }

    /**
     * Return stored access token and optional expiry time (millis) when available.
     * This parses the same credential payload used by saveTokens(...) and returns Pair(token, expiresAtMillis?)
     */
    suspend fun getTokenInfo(context: Context): Pair<String?, Long?> = withContext(Dispatchers.IO) {
        try {
            val manager = CredentialManager.create(context)
            val requestData = Bundle()
            val option = GetCustomCredentialOption(
                CRED_TYPE,
                requestData,
                Bundle(),
                /* isSystemProviderRequired = */ false,
            )
            val getReq = GetCredentialRequest.Builder().addCredentialOption(option).build()

            val response: GetCredentialResponse = manager.getCredential(context, getReq)
            val cred = response.credential
            val dataBundle = cred.data

            val payloadBytes = when {
                dataBundle.containsKey(PAYLOAD_KEY) -> dataBundle.getByteArray(PAYLOAD_KEY)
                dataBundle.containsKey(PAYLOAD_KEY + "_str") -> dataBundle.getString(PAYLOAD_KEY + "_str")?.toByteArray(Charsets.UTF_8)
                else -> null
            }

            val json = payloadBytes?.let { String(it, Charsets.UTF_8) }
            if (!json.isNullOrBlank()) {
                val obj = JSONObject(json)
                val token = if (obj.has("access_token") && !obj.isNull("access_token")) obj.getString("access_token") else null
                val expiresAt = if (obj.has("expires_at") && !obj.isNull("expires_at")) obj.getLong("expires_at") else null
                return@withContext Pair(token, expiresAt)
            }
        } catch (e: GetCredentialException) {
            Log.d(TAG, "No credential found in Credential Manager (getTokenInfo): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Credential Manager get (getTokenInfo) failed: ${e.message}")
        }

        // Fallback to AuthTokenStore if present
        try {
            val token = AuthTokenStore.getAccessToken(context)
            val userExpires = AuthTokenStore.getExpiresAt(context)
            return@withContext Pair(token, userExpires)
        } catch (e: Exception) {
            Log.w(TAG, "AuthTokenStore.getTokenInfo fallback failed: ${e.message}")
        }

        return@withContext Pair(null, null)
    }

    suspend fun clear(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Overwrite our credential with an empty JSON payload
        return@withContext try {
            val manager = CredentialManager.create(context)
            val payloadJson = JSONObject().toString()
            val credentialData = Bundle().apply {
                putByteArray(PAYLOAD_KEY, payloadJson.toByteArray(Charsets.UTF_8))
            }
            val createReq = CreateCustomCredentialRequest(
                CRED_TYPE,
                credentialData,
                Bundle(),
                /* isSystemProviderRequired = */ false,
                CreateCredentialRequest.DisplayInfo("One"),
            )
            manager.createCredential(context, createReq)
            Log.d(TAG, "Cleared tokens in Credential Manager (overwritten)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear Credential Manager: ${e.message}")
            // fallback to clear AuthTokenStore
            try {
                AuthTokenStore.clear(context)
                Log.w(TAG, "Cleared AuthTokenStore fallback due to CredentialManager failure")
                return@withContext true
            } catch (e2: Exception) {
                Log.e(TAG, "AuthTokenStore clear failed: ${e2.message}")
            }
            false
        }
    }

    // New overload that accepts an optional userId to store with the payload
    suspend fun saveTokens(context: Context, accessToken: String, refreshToken: String?, expiresAt: Long, userId: String?): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val manager = CredentialManager.create(context)
            val payloadJson = JSONObject().apply {
                put("access_token", accessToken)
                put("refresh_token", refreshToken ?: JSONObject.NULL)
                put("expires_at", expiresAt)
                if (!userId.isNullOrBlank()) put("user_id", userId)
            }.toString()

            // Put the JSON bytes into a Bundle under PAYLOAD_KEY
            val credentialData = Bundle().apply {
                putByteArray(PAYLOAD_KEY, payloadJson.toByteArray(Charsets.UTF_8))
            }

            val candidateQueryData = Bundle()
            val displayInfo = CreateCredentialRequest.DisplayInfo("One")

            val createReq = CreateCustomCredentialRequest(
                CRED_TYPE,
                credentialData,
                candidateQueryData,
                /* isSystemProviderRequired = */ false,
                displayInfo,
            )

            manager.createCredential(context, createReq)
            Log.d(TAG, "Saved tokens to Credential Manager (with userId)")

            // Clear explicit sign-out marker when we successfully save a token with userId
            try {
                val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
                prefs.edit().remove("user_signed_out")
            } catch (_: Exception) {}

            // Best-effort: also persist into AuthTokenStore so EncryptedSharedPreferences and
            // Credential Manager remain in sync for the stored user_id/display name.
            try {
                AuthTokenStore.saveTokens(context, accessToken, refreshToken, expiresAt, userId)
                Log.d(TAG, "Also synced tokens+userId to AuthTokenStore")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync to AuthTokenStore after CredentialManager success: ${e.message}")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save tokens to Credential Manager: ${e.message}")
            // Fallback to secure AuthTokenStore
            try {
                AuthTokenStore.saveTokens(context, accessToken, refreshToken, expiresAt, userId)
                Log.w(TAG, "Saved tokens to AuthTokenStore fallback due to CredentialManager failure (with userId)")
                try {
                    val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
                    prefs.edit().remove("user_signed_out")
                } catch (_: Exception) {}
                return@withContext true
            } catch (e2: Exception) {
                Log.e(TAG, "AuthTokenStore fallback failed: ${e2.message}")
            }
            false
        }
    }

    /**
     * Restore the optional `user_id` / display name stored in the credential payload.
     * Returns null when no user_id is stored or on error.
     */
    suspend fun getUserId(context: Context): String? = withContext(Dispatchers.IO) {
         // If user explicitly signed out, don't restore user id
         try {
             val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
             if (prefs.getBoolean("user_signed_out", false)) {
                 Log.d(TAG, "User has signed out; skipping userId restore")
                 return@withContext null
             }
         } catch (_: Exception) {}
          try {
              val manager = CredentialManager.create(context)

             val requestData = Bundle()
             val option = GetCustomCredentialOption(
                 CRED_TYPE,
                 requestData,
                 Bundle(),
                 /* isSystemProviderRequired = */ false,
             )
             val getReq = GetCredentialRequest.Builder().addCredentialOption(option).build()

             val response: GetCredentialResponse = manager.getCredential(context, getReq)
             val cred = response.credential
             val dataBundle = cred.data

             val payloadBytes = when {
                 dataBundle.containsKey(PAYLOAD_KEY) -> dataBundle.getByteArray(PAYLOAD_KEY)
                 dataBundle.containsKey(PAYLOAD_KEY + "_str") -> dataBundle.getString(PAYLOAD_KEY + "_str")?.toByteArray(Charsets.UTF_8)
                 else -> null
             }

             val json = payloadBytes?.let { String(it, Charsets.UTF_8) }
             if (!json.isNullOrBlank()) {
                 val obj = JSONObject(json)
                 if (obj.has("user_id") && !obj.isNull("user_id")) {
                     val userId = obj.getString("user_id")
                     if (!userId.isNullOrBlank()) {
                         Log.d(TAG, "Restored user_id from Credential Manager: $userId")
                         return@withContext userId
                     }
                 }
             }
         } catch (e: GetCredentialException) {
             Log.d(TAG, "No credential found in Credential Manager when reading user_id: ${e.message}")
         } catch (e: Exception) {
             Log.w(TAG, "Credential Manager get (user_id) failed: ${e.message}")
         }

        // Fallback: try to read from AuthTokenStore extras (EncryptedSharedPreferences)
        try {
            val t = AuthTokenStore.getUserId(context)
            if (!t.isNullOrBlank()) {
                Log.d(TAG, "Restored user_id from AuthTokenStore fallback: $t")
                return@withContext t
            }
        } catch (e: Exception) {
            Log.w(TAG, "AuthTokenStore.getUserId fallback failed: ${e.message}")
        }

        return@withContext null
    }
}
