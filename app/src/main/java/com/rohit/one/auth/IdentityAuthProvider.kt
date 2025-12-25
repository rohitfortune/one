@file:Suppress("DEPRECATION")
package com.rohit.one.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.GetCustomCredentialOption
import androidx.credentials.exceptions.GetCredentialException
import org.json.JSONObject
import android.os.Bundle
import kotlinx.coroutines.withContext
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn // Used for sign-out/revoke only; migration path is Google Identity Services
import com.google.android.gms.auth.api.signin.GoogleSignInOptions // Used for sign-out/revoke only; migration path is Google Identity Services
import java.io.File

/**
 * IdentityAuthProvider: simplified provider using AuthorizationClient for sign-in and
 * SharedPreferences (via CredentialAuthStore) for token persistence.
 *
 * Note: Some imports and usages (UserRecoverableAuthException) are deprecated in Google Play Services.
 * If you migrate to AuthorizationClient, use the new exception types and flows.
 */
object IdentityAuthProvider {
    // Lifecycle-aware refresh scope: keep a SupervisorJob we can cancel on sign-out.
    // Use a var so we can cancel children and recreate the job after sign-out.
    private var refreshJob = SupervisorJob()
    private val refreshScope: CoroutineScope
        get() = CoroutineScope(refreshJob + Dispatchers.IO)

    /**
     * Silently attempting credential sign-in is not supported in the AuthorizationClient-only mode.
     * Return a failure to let callers invoke interactive sign-in when needed.
     * This function is called from MainActivity or UI.
     */
    fun attemptCredentialSignIn(activity: Activity, callback: (Result<Pair<String, String>>) -> Unit) {
        MainScope().launch {
            val manager = CredentialManager.create(activity)
            try {
                // Request both password and custom credential options in the same request. Some providers
                // will respond with the best match and avoid throwing 'no matching credential' errors.
                val passOption = GetPasswordOption()
                val customOption = GetCustomCredentialOption("com.rohit.one.GENERIC_CREDENTIAL", Bundle(), Bundle(), false)
                val req = GetCredentialRequest.Builder()
                    .addCredentialOption(passOption)
                    .addCredentialOption(customOption)
                    .build()

                val resp = manager.getCredential(activity, req)
                val cred = resp.credential

                // If it's a PasswordCredential (username/password saved), return that.
                val pw = cred as? PasswordCredential
                if (pw != null) {
                    Log.d("OneApp", "IdentityAuthProvider: got PasswordCredential id=${pw.id}")
                    callback(Result.success(Pair(pw.id, pw.password)))
                    return@launch
                }

                // Otherwise try to parse our custom credential payload.
                try {
                    val data = cred.data
                    val payloadBytes = when {
                        data.containsKey("payload") -> data.getByteArray("payload")
                        data.containsKey("payload_str") -> data.getString("payload_str")?.toByteArray(Charsets.UTF_8)
                        else -> null
                    }
                    val json = payloadBytes?.let { String(it, Charsets.UTF_8) }
                    if (!json.isNullOrBlank()) {
                        val obj = JSONObject(json)
                        val access = if (obj.has("access_token") && !obj.isNull("access_token")) obj.getString("access_token") else null
                        val id = if (obj.has("user_id") && !obj.isNull("user_id")) obj.getString("user_id") else null
                        if (!access.isNullOrBlank()) {
                            callback(Result.success(Pair(id ?: "", access)))
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.w("OneApp", "IdentityAuthProvider: failed to parse credential data: ${e.message}")
                }

                // Nothing usable in the credential response
                callback(Result.failure(Exception("No usable credential returned by CredentialManager")))
            } catch (e: GetCredentialException) {
                // Common: no matching credential available (error like 28433). Log full details for debugging
                Log.w("OneApp", "IdentityAuthProvider: GetCredentialException while retrieving credential: ${e.message}")
                Log.w("OneApp", "GetCredentialException stack: ", e)
                // Provide a clear failure to the caller so UI can start interactive sign-in
                callback(Result.failure(Exception("No saved credential found (CredentialManager). Interactive sign-in required. (${e.message})")))
            } catch (e: Exception) {
                Log.w("OneApp", "IdentityAuthProvider: unexpected exception during getCredential: ${e.message}", e)
                callback(Result.failure(e))
            }
        }
    }

    suspend fun getAccessToken(context: Context, accountName: String?): String? {
        // First check stored token and expiry (Credential Manager or fallback store)
        try {
            val (storedToken, expiresAt) = CredentialAuthStore.getTokenInfo(context)
            val now = System.currentTimeMillis()
            val refreshMarginMs = 60_000L // refresh if within 60s of expiry

            if (!storedToken.isNullOrEmpty()) {
                // If we have an expiry and it's still valid, return the stored token immediately
                if (expiresAt != null && now < (expiresAt - refreshMarginMs)) {
                    return storedToken
                }
                // If expiry not present, conservatively try to use it but attempt background refresh
                if (expiresAt == null) {
                    // Try to refresh in background but return current token now (best-effort)
                    // Trigger refresh asynchronously; callers that get 401 should trigger interactive sign-in.
                    // Launch a coroutine in an app-scoped supervised scope to refresh token without blocking callers.
                    refreshScope.launch {
                        try {
                            if (!accountName.isNullOrBlank()) {
                                val scope = "oauth2:https://www.googleapis.com/auth/drive https://www.googleapis.com/auth/drive.appdata"
                                // Deprecated: migrate to AuthorizationClient
                                val newToken = GoogleAuthUtil.getToken(context, accountName, scope)
                                if (newToken.isNotEmpty()) {
                                    try { CredentialAuthStore.saveTokens(context, newToken, null, System.currentTimeMillis() + 3600_000, accountName) } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {
                            // ignore background refresh failures
                        }
                    }
                    return storedToken
                }
                // If token expired (or near expiry), fall through to refresh path below
            }
        } catch (e: Exception) {
            Log.w("OneApp", "IdentityAuthProvider: failed to read token info: ${e.message}")
            // continue to attempt on-device exchange
        }

        // If we get here, either we had no stored token or it is expired / near expiry. Try on-device exchange.
        if (accountName.isNullOrBlank()) return null

        try {
            return withContext(Dispatchers.IO) {
                try {
                    val scope = "oauth2:https://www.googleapis.com/auth/drive https://www.googleapis.com/auth/drive.appdata"
                    // Deprecated: migrate to AuthorizationClient
                    val token = GoogleAuthUtil.getToken(context, accountName, scope)
                    if (token.isNotEmpty()) {
                        try {
                            CredentialAuthStore.saveTokens(context, token, null, System.currentTimeMillis() + 3600_000, accountName)
                        } catch (e: Exception) {
                            Log.w("OneApp", "IdentityAuthProvider: failed to save token to CredentialAuthStore: ${e.message}")
                        }
                        return@withContext token
                    }
                } catch (e: Exception) {
                    Log.w("OneApp", "IdentityAuthProvider: GoogleAuthUtil.getToken failed: ${e.message}")
                }
                null
            }
        } catch (e: Exception) {
            // propagate exception to caller so UI can launch the provided intent
            throw e
        }
    }

    /**
     * Sign out the current user: clears persisted last_signed_in_account and local token store.
     */
    suspend fun signOut(context: Context): Result<Unit> {
        return try {
            // Clear our persisted 'last_signed_in_account' using KTX edit extension
            try {
                val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
                prefs.edit().remove("last_signed_in_account").apply()
            } catch (_: Exception) {
                // log but continue
            }

            // Clear stored tokens in our Credential Manager and fallback store
            try {
                CredentialAuthStore.clear(context)
            } catch (e: Exception) {
                Log.w("OneApp", "CredentialAuthStore.clear failed during signOut: ${e.message}")
            }

            // Ensure EncryptedSharedPreferences fallback is also cleared
            try {
                AuthTokenStore.clear(context)
            } catch (e: Exception) {
                Log.w("OneApp", "AuthTokenStore.clear failed during signOut: ${e.message}")
            }

            // Remove any debug token file that may have been written during development
            try {
                val f = File(context.filesDir, "debug_stored_token.txt")
                if (f.exists()) {
                    f.delete()
                    Log.d("OneApp", "Removed debug_stored_token.txt during signOut")
                }
            } catch (e: Exception) {
                Log.w("OneApp", "Failed to delete debug token file: ${e.message}")
            }

            // Also sign out of GoogleSignIn (clears cached account chooser state) and revoke access
            // Deprecated: migrate to Google Identity Services
            try {
                try {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build()
                    val client = GoogleSignIn.getClient(context, gso)
                    client.signOut().addOnCompleteListener { Log.d("OneApp", "GoogleSignIn.signOut completed") }
                    client.revokeAccess().addOnCompleteListener { Log.d("OneApp", "GoogleSignIn.revokeAccess completed") }
                } catch (inner: Exception) {
                    Log.w("OneApp", "GoogleSignIn signOut/revoke failed: ${inner.message}")
                }
            } catch (e: Exception) {
                Log.w("OneApp", "GoogleSignIn actions failed during signOut: ${e.message}")
            }

            // Mark user as explicitly signed-out so credential reads are suppressed until new sign-in
            try {
                val prefs = context.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("user_signed_out", true).apply()
            } catch (e: Exception) {
                Log.w("OneApp", "Failed to set user_signed_out flag: ${e.message}")
            }

            // Cancel the current refresh job and create a new one for future refreshes
            // Use cancel() on the SupervisorJob (cancelChildren() is not available here).
            refreshJob.cancel()
            refreshJob = SupervisorJob()
            Log.d("OneApp", "IdentityAuthProvider: cancelled background refresh jobs on signOut")

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
