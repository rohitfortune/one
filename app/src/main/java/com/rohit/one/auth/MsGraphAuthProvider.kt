@file:Suppress("DEPRECATION")
package com.rohit.one.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import com.rohit.one.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object MsGraphAuthProvider {
    private const val TAG = "MsGraphAuthProvider"
    private var mSingleAccountApp: ISingleAccountPublicClientApplication? = null

    // Scopes for OneDrive
    private val SCOPES = arrayOf("Files.ReadWrite", "User.Read")

    suspend fun init(context: Context): Boolean = suspendCancellableCoroutine { cont ->
        if (mSingleAccountApp != null) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }

        // PRE-CHECK: Manually validate the config file to prevent MSAL background thread crashes
        try {
            val resources = context.resources
            val inputStream = resources.openRawResource(R.raw.auth_config_single_account)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            
            if (jsonString.contains(": \"onedrive client id\"") || jsonString.contains(": \"redirect_uri\"")) {
                 Log.e(TAG, "Invalid placeholders detected in auth_config. Aborting MSAL init to prevent crash.")
                 cont.resume(false)
                 return@suspendCancellableCoroutine
            }
             if (jsonString.contains("\"client_id\"") && jsonString.contains(": \"\"")) {
                 Log.e(TAG, "Empty client_id detected. Aborting.")
                 cont.resume(false)
                 return@suspendCancellableCoroutine
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read auth config: ${e.message}")
            // Continue and let MSAL try (or fail), but at least we tried to protect
        }

        try {
            PublicClientApplication.createSingleAccountPublicClientApplication(
                context.applicationContext,
                R.raw.auth_config_single_account,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        mSingleAccountApp = application
                        Log.d(TAG, "MSAL initialized successfully")
                        cont.resume(true)
                    }

                    override fun onError(exception: MsalException) {
                        Log.e(TAG, "MSAL initialization failed: ${exception.message}")
                        cont.resume(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "MSAL initialization threw exception: ${e.message}")
            cont.resume(false)
        }
    }

    suspend fun signIn(activity: Activity): IAuthenticationResult? = suspendCancellableCoroutine { cont ->
        if (mSingleAccountApp == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        mSingleAccountApp?.signIn(
            activity,
            "",
            SCOPES,
            object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                     Log.d(TAG, "Sign-in success: ${authenticationResult.account.username}")
                     cont.resume(authenticationResult)
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Sign-in error: ${exception.message}")
                    cont.resume(null)
                }

                override fun onCancel() {
                    Log.d(TAG, "Sign-in cancelled")
                    cont.resume(null)
                }
            }
        )
    }
    
    suspend fun signOut(): Boolean = suspendCancellableCoroutine { cont ->
        if (mSingleAccountApp == null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        
        mSingleAccountApp?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                Log.d(TAG, "Signed out successfully")
                cont.resume(true)
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Sign out error: ${exception.message}")
                cont.resume(false)
            }
        })
    }

    suspend fun acquireTokenSilent(): IAuthenticationResult? = suspendCancellableCoroutine { cont ->
        if (mSingleAccountApp == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val authority = mSingleAccountApp?.configuration?.defaultAuthority?.authorityURL?.toString() ?: ""
        
        mSingleAccountApp?.acquireTokenSilentAsync(
            SCOPES,
            authority,
            object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(TAG, "Silent token success")
                    cont.resume(authenticationResult)
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Silent token error: ${exception.message}")
                    cont.resume(null)
                }

                override fun onCancel() {
                    cont.resume(null)
                }
            }
        )
    }

    suspend fun getCurrentAccount(): com.microsoft.identity.client.IAccount? = suspendCancellableCoroutine { cont ->
         if (mSingleAccountApp == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        
        mSingleAccountApp?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: com.microsoft.identity.client.IAccount?) {
                cont.resume(activeAccount)
            }

            override fun onAccountChanged(priorAccount: com.microsoft.identity.client.IAccount?, currentAccount: com.microsoft.identity.client.IAccount?) {
                 // Not needed for a one-shot get
            }

            override fun onError(exception: MsalException) {
                cont.resume(null)
            }
        })
    }
}
