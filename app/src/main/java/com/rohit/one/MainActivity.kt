@file:Suppress("DEPRECATION", "unused", "UNUSED_PARAMETER")
package com.rohit.one

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
// ActivityResultContracts intentionally not imported here (unused in this file)

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.MutableState
import android.util.Log
import android.widget.Toast
import com.rohit.one.auth.IdentityAuthProvider
import com.rohit.one.auth.CredentialAuthStore
import com.rohit.one.data.Note

import com.rohit.one.data.NoteRepository
import com.rohit.one.data.PasswordRepository
import com.rohit.one.data.CreditCardRepository
import com.rohit.one.data.BackupRepository
import com.rohit.one.ui.BackupScreen

import com.rohit.one.ui.AddEditPasswordScreen
import com.rohit.one.ui.AddEditCardScreen
import com.rohit.one.ui.VaultsScreen
import com.rohit.one.ui.FilesScreen
import com.rohit.one.ui.NoteScreen
import com.rohit.one.ui.theme.OneTheme
import com.rohit.one.viewmodel.NotesViewModel
import com.rohit.one.viewmodel.VaultsViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import androidx.biometric.BiometricPrompt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavBackStackEntry
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.setValue
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import com.rohit.one.data.NoteDatabase

@Suppress("DEPRECATION")
enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    NOTES("Notes", Icons.Filled.Note),
    PASSWORDS("Vault", Icons.Filled.Password),
    FILES("Files", Icons.Filled.Folder),
    SETTINGS("Settings", Icons.Filled.Settings),
}

class MainActivity : FragmentActivity() {

    // Coroutine scope for launching UI-driven coroutines
    private val mainScope = MainScope()

    // Guard to avoid re-entering interactive sign-in/consent flows repeatedly
    @Volatile
    private var signInFlowLaunched = false

    // Hoisted Compose-backed signed-in account state (shared between Activity and Compose)
    private val signedInAccountState: MutableState<String?> by lazy { mutableStateOf(null) }

    private lateinit var notesViewModel: NotesViewModel
    private lateinit var vaultsViewModel: VaultsViewModel

    private val isAppLocked = mutableStateOf(false)

    override fun onPause() {
        super.onPause()
        // Record the time when the app goes into the background
        if (!isAppLocked.value) {
            lastBackgroundTimestamp = System.currentTimeMillis()
        }
    }

    override fun onResume() {
        super.onResume()
        if (com.rohit.one.data.SettingsStore.isAppLockEnabled(this)) {
            // If the app is currently unlocked but we are resuming, we should re-lock?
            // Standard behavior usually locks on cold start or after timeout.
            // For simple "App Lock", locking on every Resume (background -> foreground) is safest.
            // Check if we are already locked to avoid double-prompting (though onResume is called once per foregrounding)
            if (!isAppLocked.value) {
                 // Check if 1 minute (60000 ms) has passed since we went to background
                 // Use static timestamp (memory-only) so it resets on process death
                 val lastBg = lastBackgroundTimestamp
                 val currentTime = System.currentTimeMillis()
                 if (lastBg != 0L && (currentTime - lastBg) > 60_000) {
                     isAppLocked.value = true
                     authenticateUser()
                 }
                 // Reset timestamp so we don't accidentally lock while using the app
                 lastBackgroundTimestamp = 0
            }
        }
    }

    // ActivityResult launcher for GoogleSignIn (interactive chooser)
    private val googleSignInLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (data == null) {
            Log.w("OneApp", "GoogleSignInLauncher: result data is null")
            Toast.makeText(this, "Sign-in canceled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        try {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = try { task.getResult(com.google.android.gms.common.api.ApiException::class.java) } catch (e: Exception) { null }
            if (account != null) {
                Log.i("OneApp", "GoogleSignIn succeeded (launcher): ${account.email}")
                Toast.makeText(this, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
                // Update hoisted Compose state so UI updates immediately (no SharedPreferences)
                signedInAccountState.value = account.email

                // Try to obtain an on-device Drive access token first. Only if it's missing do we start
                // the additional consent/sign-in flow. This prevents repeated interactive sign-in loops
                // where the fallback GoogleSignIn interactive flow reinvokes the same launcher.
                mainScope.launch {
                    try {
                        val token = try { IdentityAuthProvider.getAccessToken(applicationContext, account.email) } catch (e: Exception) {
                            // If this is a UserRecoverableAuthException from Google Play services, try to surface
                            // the consent intent to the user. Use reflection to avoid a hard dependency.
                            try {
                                if (e.javaClass.name == "com.google.android.gms.auth.UserRecoverableAuthException") {
                                    Log.w("OneApp", "getAccessToken requires user consent (reflection): ${e.message}")
                                    try {
                                        val getIntent = e.javaClass.getMethod("getIntent")
                                        val intent = getIntent.invoke(e) as? Intent
                                        if (intent != null) {
                                            // Launch consent UI; do not call startSignIn() directly here.
                                            startActivity(intent)
                                        } else {
                                            Toast.makeText(this@MainActivity, "Please grant consent in settings and retry", Toast.LENGTH_LONG).show()
                                        }
                                    } catch (ex: Exception) {
                                        Log.e("OneApp", "Failed to launch UserRecoverableAuthException intent (reflection): ${ex.message}", ex)
                                        Toast.makeText(this@MainActivity, "Please grant consent in settings and retry", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }
                            } catch (_: Exception) {}
                            throw e
                        }

                        if (!token.isNullOrBlank()) {
                            Log.i("OneApp", "Obtained access token after sign-in (length=${token.length})")
                            Toast.makeText(this@MainActivity, "Drive access token obtained", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.i("OneApp", "No access token returned after sign-in; launching consent flow once")
                            // Delegate to startSignIn() which will set the guard. Do not set the flag here.
                            try {
                                startSignIn()
                            } catch (ex: Exception) {
                                Log.w("OneApp", "Failed to start additional Drive consent flow after GoogleSignIn: ${ex.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("OneApp", "Failed to fetch access token after sign-in: ${e.message}")
                        Toast.makeText(this@MainActivity, "Failed to obtain Drive token: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        // Ensure we allow future sign-in attempts if the flow did not attach to the normal
                        // sign-in result handlers (some reflection code paths may not produce an intent).
                        signInFlowLaunched = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OneApp", "GoogleSignInLauncher handling failed: ${e.message}", e)
            Toast.makeText(this, "Sign-in failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    // ActivityResult launcher for IntentSender (Identity PendingIntent)
    private val signInLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val data = result.data
        try {
            if (data == null) {
                Log.w("OneApp", "Sign-in result intent was null")
                Toast.makeText(this, "Sign-in canceled or no data returned", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            // Log intent and extras for debugging
            try {
                Log.d("OneApp", "Sign-in Intent: $data")
                val extras = data.extras
                if (extras != null) {
                    val keys = extras.keySet()
                    for (k in keys) {
                        try {
                            val v = extras.get(k)
                            Log.d("OneApp", "Intent extra: $k -> $v")
                        } catch (e: Exception) {
                            Log.d("OneApp", "Intent extra: $k -> <error reading>")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("OneApp", "Failed to dump intent extras: ${e.message}")
            }

            var handled = false

            // Try SignInCredential path (One-Tap / SignInClient)
            try {
                val signInClient = com.google.android.gms.auth.api.identity.Identity.getSignInClient(this)
                val credential = signInClient.getSignInCredentialFromIntent(data)
                val name = credential.id
                val idToken = try { credential.googleIdToken } catch (_: Throwable) { null }
                val password = try { credential.password } catch (_: Throwable) { null }
                Log.i("OneApp", "SignInCredential - id=$name idToken=${idToken?.substring(0, kotlin.math.min(idToken.length, 40))} password=${if (password != null) "***" else null}")
                Toast.makeText(this, "Signed in as $name", Toast.LENGTH_SHORT).show()
                handled = true
                // Persist token if available (password could be token)
                mainScope.launch {
                    try {
                        val tokenToSave = password ?: idToken
                        if (!tokenToSave.isNullOrBlank()) {
                            val saved = CredentialAuthStore.saveTokens(applicationContext, tokenToSave, null, System.currentTimeMillis() + 3600_000, name)
                            Log.i("OneApp", "Saved token from SignInCredential: $saved")
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.d("OneApp", "Not a SignInCredential result: ${e.message}")
            }

            // Try AuthorizationResponse via reflection to extract auth code / access token
            try {
                val authRespClass = try { Class.forName("com.google.android.gms.auth.api.identity.AuthorizationResponse") } catch (_: Exception) { null }
                if (authRespClass != null) {
                    val fromIntent = try { authRespClass.getMethod("fromIntent", Intent::class.java) } catch (_: Exception) { null }
                    val authResp = if (fromIntent != null) try { fromIntent.invoke(null, data) } catch (_: Exception) { null } else null
                    if (authResp != null) {
                        // Try common getters: getAuthorizationCode(), getAccessToken(), getIdToken()
                        val code = try { authRespClass.getMethod("getAuthorizationCode").invoke(authResp) as? String } catch (_: Exception) { null }
                        val accessToken = try { authRespClass.getMethod("getAccessToken").invoke(authResp) as? String } catch (_: Exception) { null }
                        val idToken = try { authRespClass.getMethod("getIdToken").invoke(authResp) as? String } catch (_: Exception) { null }
                        Log.i("OneApp", "AuthorizationResponse parsed reflectively: code=${code?.take(40)} accessToken=${accessToken?.take(40)} idToken=${idToken?.take(40)}")
                        if (!accessToken.isNullOrBlank()) {
                            // Store token in Credential Manager (no user id available here)
                            mainScope.launch {
                                val saved = CredentialAuthStore.saveTokens(applicationContext, accessToken, null, System.currentTimeMillis() + 3600_000, null)
                                Log.i("OneApp", "Saved access token to CredentialAuthStore: $saved")
                            }
                            handled = true
                        } else if (!code.isNullOrBlank()) {
                            // We received an auth code. Save it temporarily or perform PKCE exchange if OAUTH_CLIENT_ID present.
                            Log.i("OneApp", "Received auth code; PKCE exchange required (code length=${code.length})")
                            // Store auth code as a placeholder token (short-lived) so backup UI can use it after exchange
                            mainScope.launch {
                                CredentialAuthStore.saveTokens(applicationContext, code, null, System.currentTimeMillis() + 600_000)
                            }
                            handled = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("OneApp", "AuthorizationResponse reflection parse failed: ${e.message}")
            }

            if (!handled) {
                Log.d("OneApp", "Sign-in result not fully handled; user likely signed in (credential path handled).")
            }

        } catch (e: Exception) {
            val msg = e.message ?: "Unknown sign-in error"
            Toast.makeText(this, "Sign-in failed: $msg", Toast.LENGTH_LONG).show()
            Log.e("OneApp", "Sign-in failed or canceled: $msg", e)
        }
    }

    companion object {
        // kept for potential legacy code paths
        // Memory-only timestamp to track background time. Resets on process death.
        var lastBackgroundTimestamp: Long = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        val database = NoteDatabase.getDatabase(this)
        val repository = NoteRepository(database.noteDao())
        notesViewModel = ViewModelProvider(this, NotesViewModel.provideFactory(repository))[NotesViewModel::class.java]

        val passwordRepository = PasswordRepository(database.passwordDao(), applicationContext)
        val cardRepository = CreditCardRepository(database.creditCardDao(), applicationContext)
        vaultsViewModel = ViewModelProvider(this, VaultsViewModel.provideFactory(passwordRepository, cardRepository))[VaultsViewModel::class.java]

        // Hoisted signed-in state starts null; we no longer persist it to SharedPreferences here
        // Try to restore display name / user id from CredentialAuthStore so UI shows last signed-in user
        try {
            mainScope.launch {
                try {
                    val restored = CredentialAuthStore.getUserId(applicationContext)
                    if (!restored.isNullOrBlank()) {
                        signedInAccountState.value = restored
                        Log.d("OneApp", "Restored signed-in username from CredentialAuthStore: $restored")
                    }
                } catch (e: Exception) {
                    Log.w("OneApp", "Failed to restore user id from CredentialAuthStore: ${e.message}")
                }
            }
        } catch (_: Exception) {}

        if (com.rohit.one.data.SettingsStore.isAppLockEnabled(this)) {
            val lastBg = lastBackgroundTimestamp
            val now = System.currentTimeMillis()
            // If recently backgrounded (< 1 min, and process still alive), keep unlocked. Otherwise lock.
            // Note: if process was killed, lastBg is 0 (default), so we lock.
            if (lastBg != 0L && (now - lastBg) < 60_000) {
                isAppLocked.value = false
            } else {
                isAppLocked.value = true
            }
        }

        setContent {
            val locked by remember { isAppLocked }
            OneTheme {
                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                    // Always compose the app so its state (NavHost) is preserved
                    OneApp(
                        notesViewModel = notesViewModel,
                        vaultsViewModel = vaultsViewModel,
                        onSignIn = { startSignIn() },
                        signedInAccountState = signedInAccountState
                    )
                    
                    // Overlay the lock screen if locked
                    if (locked) {
                        LockScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(1f) // Ensure it sits on top
                                .background(MaterialTheme.colorScheme.background), // Opaque background
                            onUnlock = { authenticateUser() }
                        )
                    }
                }
            }
        }

        // Silent credential sign-in at startup has been disabled per user preference.
        // Interactive sign-in is initiated only when the user taps the Sign in button.
        Log.d("OneApp", "Silent credential sign-in disabled — interactive sign-in only on user action")
    }

    // Start interactive sign-in using AuthorizationClient (Google Identity Services)
    private fun startSignIn() {
        Log.d("OneApp","startSignIn() called — attempting AuthorizationClient then fallback")
        // Direct interactive sign-in using AuthorizationClient (preferred)
        try {
            // Build AuthorizationRequest with Drive appdata scope
            val authReqBuilder = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            try {
                val scope = com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.appdata")
                val scopeFull = com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive")
                // AuthorizationRequest.Builder may expose addScope on some versions; call it reflectively to stay binary compatible
                try {
                    val addScopeMethod = authReqBuilder.javaClass.methods.firstOrNull { it.name == "addScope" && it.parameterTypes.size == 1 }
                    addScopeMethod?.invoke(authReqBuilder, scope)
                    addScopeMethod?.invoke(authReqBuilder, scopeFull)
                    addScopeMethod?.invoke(authReqBuilder, com.google.android.gms.common.api.Scope("email"))
                } catch (_: Throwable) {
                    // If addScope not available or reflection fails, ignore — some older versions may require different API
                }
            } catch (_: Exception) {}

            val authReq = try { authReqBuilder.build() } catch (_: Exception) { null }

            if (authReq != null) {
                try {
                    // Use reflection to locate an authorize(...) method that returns a Task.
                    var task: com.google.android.gms.tasks.Task<*>? = null
                    try {
                        val authClientClass = Class.forName("com.google.android.gms.auth.api.identity.AuthorizationClient")
                        try {
                            val m = authClientClass.getMethod("authorize", FragmentActivity::class.java, authReq.javaClass)
                            val t = m.invoke(null, this, authReq)
                            if (t is com.google.android.gms.tasks.Task<*>) task = t
                        } catch (_: Exception) {}

                        if (task == null) {
                            try {
                                val m = authClientClass.getMethod("authorize", Context::class.java, authReq.javaClass)
                                val t = m.invoke(null, this, authReq)
                                if (t is com.google.android.gms.tasks.Task<*>) task = t
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}

                    // If static methods not found, try instance authorize via Identity.getSignInClient or similar
                    if (task == null) {
                        try {
                            val identityCls = Class.forName("com.google.android.gms.auth.api.identity.Identity")
                            val getClientM = identityCls.methods.firstOrNull { it.name.startsWith("get") && it.parameterTypes.size == 1 }
                            if (getClientM != null) {
                                val client = getClientM.invoke(null, this)
                                if (client != null) {
                                    val authorizeM = client.javaClass.methods.firstOrNull { it.name == "authorize" && it.parameterTypes.size == 1 }
                                    if (authorizeM != null) {
                                        val t = authorizeM.invoke(client, authReq)
                                        if (t is com.google.android.gms.tasks.Task<*>) task = t
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (task != null) {
                        task.addOnSuccessListener { result ->
                            try {
                                val authResult = result as? com.google.android.gms.auth.api.identity.AuthorizationResult
                                var accessToken: String? = null
                                if (authResult != null) accessToken = try { authResult.accessToken } catch (_: Exception) { null }
                                if (accessToken.isNullOrBlank()) {
                                    try {
                                        val getAccess = result?.javaClass?.getMethod("getAccessToken")
                                        if (getAccess != null) accessToken = getAccess.invoke(result) as? String
                                    } catch (_: Exception) {}
                                }

                                if (!accessToken.isNullOrBlank()) {
                                    // Fetch user info to get the email (needed for persistence and refresh)
                                    val token = accessToken
                                    mainScope.launch {
                                        val email = withContext(Dispatchers.IO) {
                                            try {
                                                val url = java.net.URL("https://www.googleapis.com/oauth2/v3/userinfo")
                                                val conn = url.openConnection() as java.net.HttpURLConnection
                                                conn.requestMethod = "GET"
                                                conn.setRequestProperty("Authorization", "Bearer $token")
                                                conn.connectTimeout = 10000
                                                conn.readTimeout = 10000
                                                if (conn.responseCode == 200) {
                                                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                                                    val obj = org.json.JSONObject(json)
                                                    if (obj.has("email")) obj.getString("email") else null
                                                } else {
                                                    Log.w("OneApp", "UserInfo fetch failed: ${conn.responseCode} ${conn.responseMessage}")
                                                    null
                                                }
                                            } catch (e: Exception) {
                                                Log.w("OneApp", "UserInfo fetch error: ${e.message}")
                                                null
                                            }
                                        }

                                        val userId = email ?: "GoogleUser" // Fallback if fetch fails, though email is critical for refresh
                                        val saved = CredentialAuthStore.saveTokens(applicationContext, token, null, System.currentTimeMillis() + 3600_000, userId)
                                        Log.i("OneApp", "Saved access token from AuthorizationClient: $saved (user=$userId)")
                                        
                                        // Update hoisted Compose state directly
                                        signedInAccountState.value = userId
                                    }
                                } else {
                                    Log.i("OneApp", "Authorization succeeded but no access token present; result=$result")
                                    try {
                                        val getCode = result?.javaClass?.getMethod("getAuthorizationCode")
                                        val code = if (getCode != null) try { getCode.invoke(result) as? String } catch (_: Exception) { null } else null
                                        if (!code.isNullOrBlank()) {
                                            mainScope.launch { CredentialAuthStore.saveTokens(applicationContext, code, null, System.currentTimeMillis() + 600_000) }
                                        }
                                    } catch (_: Exception) {}
                                }
                            } catch (e: Exception) {
                                Log.e("OneApp", "Auth success handler failed: ${e.message}", e)
                            }
                        }
                        task.addOnFailureListener { exc ->
                            Log.e("OneApp", "AuthorizationClient authorize failed: ${exc.message}", exc)
                            trySignInClientFallback()
                        }

                        // AuthorizationClient launched; return
                        return
                    }

                } catch (e: Exception) {
                    Log.w("OneApp", "Reflection AuthorizationClient attempt failed, will fallback: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.w("OneApp", "startSignIn AuthorizationClient block failed: ${e.message}")
        }

        // fallback to BeginSignIn one-tap / SignInClient
        trySignInClientFallback()
    }

    private fun trySignInClientFallback() {
        try {
            Log.d("OneApp","trySignInClientFallback: starting GoogleSignInClient interactive flow")
            Log.d("OneApp","trySignInClientFallback: building GoogleSignInOptions")
            val gsoBuilder = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
            ).requestEmail()
            try {
                gsoBuilder.requestScopes(
                    com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.appdata"),
                    com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive")
                )
                Log.d("OneApp","trySignInClientFallback: requested Drive appdata and full Drive scopes")
            } catch (ex: Exception) {
                Log.w("OneApp","trySignInClientFallback: failed to add scope: ${ex.message}")
            }
            val gso = try {
                gsoBuilder.build().also { Log.d("OneApp","trySignInClientFallback: GSO built") }
            } catch (ex: Exception) {
                Log.e("OneApp","trySignInClientFallback: GSO build failed: ${ex.message}", ex)
                throw ex
            }
            val signInClient = try {
                com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso).also { Log.d("OneApp","trySignInClientFallback: obtained GoogleSignInClient") }
            } catch (ex: Exception) {
                Log.e("OneApp","trySignInClientFallback: getClient failed: ${ex.message}", ex)
                throw ex
            }
            val signInIntent = try {
                signInClient.signInIntent.also { Log.d("OneApp","trySignInClientFallback: signInIntent prepared") }
            } catch (ex: Exception) {
                Log.e("OneApp","trySignInClientFallback: signInIntent creation failed: ${ex.message}", ex)
                throw ex
            }
            try {
                Log.d("OneApp","trySignInClientFallback: launching GoogleSignInLauncher")
                googleSignInLauncher.launch(signInIntent)
                Log.d("OneApp","trySignInClientFallback: launched sign-in intent")
            } catch (ex: Exception) {
                Log.e("OneApp","trySignInClientFallback: launcher.launch failed: ${ex.message}", ex)
                throw ex
            }
             return
         } catch (e: Exception) {
            Log.w("OneApp","trySignInClientFallback: GoogleSignInClient flow failed: ${e.message}")
         }

         Log.e("OneApp","trySignInClientFallback: all fallback attempts failed — unable to start sign-in flow")
         Toast.makeText(this, "Unable to start sign-in flow", Toast.LENGTH_LONG).show()
     }

     private fun authenticateUser() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAppLocked.value = false
                    Toast.makeText(applicationContext, "Unlocked", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Users can cancel the prompt (e.g. back button). In that case, we stay locked.
                    // If errors like "too many attempts", we also stay locked.
                    // We can show a toast or just let the UI remain the Lock Screen.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                         Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock One")
            .setSubtitle("Authenticate to access your data")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
            
        biometricPrompt.authenticate(promptInfo)
     }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any coroutines started from the activity scope to avoid leaks
        try { mainScope.cancel() } catch (_: Exception) {}
    }
}

suspend fun fetchAccessToken(context: Context, accountName: String?): String? = withContext(Dispatchers.IO) {
    // Read token from CredentialAuthStore and provide extra debug information.
    val token = try {
        CredentialAuthStore.getAccessToken(context)
    } catch (e: Exception) {
        Log.w("OneApp.Debug", "CredentialAuthStore.getAccessToken failed: ${e.message}")
        null
    }

    // Truncate for safe logging
    val preview = token?.let { if (it.length > 120) it.take(120) + "..." else it } ?: "<null>"
    Log.d("OneApp.Debug", "fetchAccessToken -> rawTokenPreview=$preview")

    if (token != null) {
        val type = when {
            token.startsWith("TEST_") -> "DEBUG_INJECTED_TOKEN"
            token.count { it == '.' } == 2 -> "ID_TOKEN_JWT"
            token.startsWith("4/") -> "SERVER_AUTH_CODE"
            token.contains('@') -> "ACCOUNT_ID_OR_EMAIL"
            token.length in 20..400 -> "POSSIBLE_ACCESS_OR_REFRESH_TOKEN"
            else -> "UNKNOWN"
        }
        Log.i("OneApp.Debug", "Stored credential classification=$type length=${token.length}")

        // Write to an app-private debug file for inspection (useful during development only)
        try {
            val f = File(context.filesDir, "debug_stored_token.txt")
            f.writeText(token)
            Log.i("OneApp.Debug", "Wrote stored token to ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w("OneApp.Debug", "Failed to write debug token file: ${e.message}")
        }
    }

    return@withContext token
}

@Composable
fun OneApp(
    notesViewModel: NotesViewModel = viewModel(),
    vaultsViewModel: VaultsViewModel = viewModel(),
    onSignIn: () -> Unit,
    signedInAccountState: MutableState<String?>
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Backup repository instance
    val backupRepository = remember { BackupRepository(context.applicationContext) }

    // scope for launching sign-out coroutine
    val scope = rememberCoroutineScope()
    val onSignOutAction: () -> Unit = {
        scope.launch {
            try {
                val res = IdentityAuthProvider.signOut(context)
                if (res.isSuccess) {
                    // update hoisted state (no SharedPreferences)
                    signedInAccountState.value = null
                } else {
                    Log.w("OneApp", "signOut result failure: ${res.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e("OneApp", "signOut failed: ${e.message}", e)
            }
        }
    }

    // access token provider to be passed to BackupScreen
    // Use IdentityAuthProvider.getAccessToken so callers trigger on-device exchange (and may receive
    // a UserRecoverableAuthException) instead of only reading stored tokens. This lets BackupScreen
    // handle consent/intent resolution and obtain a usable Drive token after interactive sign-in.
    val accessTokenProvider: suspend () -> String? = {
        try {
            IdentityAuthProvider.getAccessToken(context.applicationContext, signedInAccountState.value)
        } catch (e: Exception) {
            // Propagate exceptions to caller (BackupScreen handles UserRecoverableAuthException via reflection)
            throw e
        }
    }

    NavHost(navController = navController, startDestination = "main") {
        composable(route = "main") {
            MainScreen(
                onNavigateToAddNote = { navController.navigate("addEditNote/-1") },
                onNavigateToEditNote = { noteId -> navController.navigate("addEditNote/$noteId") },
                onNavigateToAddPassword = { navController.navigate("addEditPassword/-1") },
                onNavigateToEditPassword = { pwId -> navController.navigate("addEditPassword/$pwId") },
                onNavigateToAddCard = { navController.navigate("addEditCard/-1") },
                onNavigateToEditCard = { cardId -> navController.navigate("addEditCard/$cardId") },
                notesViewModel = notesViewModel,
                vaultsViewModel = vaultsViewModel,
                backupRepository = backupRepository,
                accessTokenProvider = accessTokenProvider,
                onSignIn = onSignIn,
                signedInAccount = signedInAccountState.value,
                onSignOut = onSignOutAction
             )
         }
        composable(
            route = "addEditNote/{noteId}",
            arguments = listOf(navArgument("noteId") { type = NavType.IntType })
        ) { backStackEntry ->
            AddEditKeepNoteRoute(
                navController = navController,
                notesViewModel = notesViewModel,
                backStackEntry = backStackEntry
            )
        }
        composable(
            route = "addEditPassword/{pwId}",
            arguments = listOf(navArgument("pwId") { type = NavType.IntType })
        ) { backStackEntry ->
            AddEditPasswordRoute(
                navController = navController,
                vaultsViewModel = vaultsViewModel,
                backStackEntry = backStackEntry
            )
        }
        composable(
            route = "addEditCard/{cardId}",
            arguments = listOf(navArgument("cardId") { type = NavType.IntType })
        ) { backStackEntry ->
            AddEditCardRoute(
                navController = navController,
                vaultsViewModel = vaultsViewModel,
                backStackEntry = backStackEntry
            )
        }
    }
}

@Composable
fun AddEditPasswordRoute(
    navController: NavController,
    vaultsViewModel: VaultsViewModel,
    backStackEntry: NavBackStackEntry
) {
    val pwId = backStackEntry.arguments?.getInt("pwId")
    val pwToEdit = vaultsViewModel.passwords.collectAsState().value.find { it.id == pwId }
    val context = LocalContext.current

    // requestRawPassword: caller will trigger biometric auth and then receive the secret via callback
    val requestRawPassword: (((String?) -> Unit) -> Unit)? = pwToEdit?.let { pw ->
        { onResult ->
            // pw is non-null here (let scope). Proceed to prompt for biometric auth and return secret via onResult.
            val executor = ContextCompat.getMainExecutor(context)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authenticate")
                .setSubtitle("Authenticate to reveal password")
                .setNegativeButtonText("Cancel")
                .build()
            val biometricPrompt = BiometricPrompt(context as FragmentActivity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onResult(vaultsViewModel.getRawPassword(pw.uuid))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        onResult(null)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        onResult(null)
                    }
                })
            biometricPrompt.authenticate(promptInfo)
        }
    }

    AddEditPasswordScreen(
        password = pwToEdit,
        onSave = { title, username, rawPassword ->
            if (pwToEdit == null) {
                vaultsViewModel.addPassword(title, username, rawPassword)
            } else {
                vaultsViewModel.updatePassword(pwToEdit.copy(title = title, username = username),
                    rawPassword.ifBlank { null })
            }
            navController.popBackStack()
        },
        onDelete = { pw ->
            vaultsViewModel.deletePassword(pw)
            navController.popBackStack()
        },
        onNavigateUp = { navController.popBackStack() },
        requestRawPassword = requestRawPassword
    )
}

@Composable
fun AddEditCardRoute(
    navController: NavController,
    vaultsViewModel: VaultsViewModel,
    backStackEntry: NavBackStackEntry
) {
    val cardId = backStackEntry.arguments?.getInt("cardId")
    val cardToEdit = vaultsViewModel.cards.collectAsState().value.find { it.id == cardId }
    val context = LocalContext.current

    val requestFullNumber: (((String?) -> Unit) -> Unit)? = cardToEdit?.let { card ->
        { onResult ->
            val executor = ContextCompat.getMainExecutor(context)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authenticate")
                .setSubtitle("Authenticate to reveal card number")
                .setNegativeButtonText("Cancel")
                .build()
            val biometricPrompt = BiometricPrompt(context as FragmentActivity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onResult(vaultsViewModel.getFullNumber(card.uuid))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        onResult(null)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        onResult(null)
                    }
                })
            biometricPrompt.authenticate(promptInfo)
        }
    }

    AddEditCardScreen(
        card = cardToEdit,
        onSave = { name, fullNumber, nickname, expiry, securityCode ->
            if (cardToEdit == null) {
                // Persist nickname via brand, and store expiry/securityCode on the new card.
                vaultsViewModel.addCard(name, fullNumber, nickname, expiry, securityCode)
            } else {
                vaultsViewModel.updateCard(
                    cardToEdit.copy(
                        cardholderName = name,
                        brand = nickname,
                        expiry = expiry,
                        securityCode = securityCode
                    ),
                    fullNumber.ifBlank { null }
                )
            }
            navController.popBackStack()
        },
        onDelete = { c ->
            vaultsViewModel.deleteCard(c)
            navController.popBackStack()
        },
        onNavigateUp = { navController.popBackStack() },
        onRequestFullNumber = requestFullNumber
    )
}

@Composable
fun MainScreen(
    onNavigateToAddNote: () -> Unit,
    onNavigateToEditNote: (Int) -> Unit,
    onNavigateToAddPassword: () -> Unit,
    onNavigateToEditPassword: (Int) -> Unit,
    onNavigateToAddCard: () -> Unit,
    onNavigateToEditCard: (Int) -> Unit,
    notesViewModel: NotesViewModel,
    vaultsViewModel: VaultsViewModel,
    backupRepository: BackupRepository,
    accessTokenProvider: suspend () -> String?,
    onSignIn: () -> Unit,
    signedInAccount: String? = null,
    onSignOut: (() -> Unit)? = null,
) {
    // Specify type parameter explicitly to help inference
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.NOTES) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            // Use a full-size column and let each screen apply innerPadding as needed. This prevents VaultsScreen from getting an extra top inset.
            Column(modifier = Modifier.fillMaxSize()) {
                 when (currentDestination) {
                    AppDestinations.NOTES -> NotesScreen(
                        modifier = Modifier.padding(innerPadding),
                        notesViewModel = notesViewModel,
                        onAddNoteClicked = onNavigateToAddNote,
                        onNoteClicked = onNavigateToEditNote
                    )
                    AppDestinations.PASSWORDS -> VaultsScreen(
                        modifier = Modifier.fillMaxSize(),
                        vaultsViewModel = vaultsViewModel,
                        onAddPassword = onNavigateToAddPassword,
                        onAddCard = onNavigateToAddCard,
                        onPasswordClick = { pw -> onNavigateToEditPassword(pw.id) },
                        onCardClick = { card -> onNavigateToEditCard(card.id) },
                        signedInUsername = signedInAccount,
                        onSignOut = if (onSignOut != null) { { onSignOut() } } else null
                    )
                    AppDestinations.FILES -> FilesScreen(
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                        onSignIn = onSignIn,
                        signedInAccount = signedInAccount
                    )
                    AppDestinations.SETTINGS -> BackupScreen(
                        modifier = Modifier.padding(innerPadding),
                        backupRepository = backupRepository,
                        accessTokenProvider = accessTokenProvider,
                        onSignIn = onSignIn,
                        notesViewModel = notesViewModel,
                        vaultsViewModel = vaultsViewModel,
                        signedInUsername = signedInAccount,
                        onSignOut = onSignOut
                    )
                }
            }
        }
    }
}

@Composable
fun AddEditKeepNoteRoute(
    navController: NavController,
    notesViewModel: NotesViewModel,
    backStackEntry: NavBackStackEntry
) {
    val noteId = backStackEntry.arguments?.getInt("noteId")
    val noteToEdit = notesViewModel.notes.collectAsState().value.find { it.id == noteId }

    NoteScreen(
        note = noteToEdit,
        onSave = { note ->
            if (noteToEdit == null) {
                notesViewModel.addNote(note)
            } else {
                notesViewModel.updateNote(note.copy(id = noteToEdit.id))
            }
            navController.popBackStack()
        },
        onDelete = { note ->
            notesViewModel.deleteNote(note)
            navController.popBackStack()
        },
        onNavigateUp = { navController.popBackStack() }
    )
}


@Composable
fun NotesScreen(
    modifier: Modifier = Modifier,
    notesViewModel: NotesViewModel,
    onAddNoteClicked: () -> Unit,
    onNoteClicked: (Int) -> Unit
) {
    val notes by notesViewModel.notes.collectAsState()

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNoteClicked) {
                Icon(Icons.Filled.Add, contentDescription = "Add note")
            }
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp), // Removed vertical padding from modifier
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notes) { note ->
                NoteItem(note, onClick = { onNoteClicked(note.id) })
            }
        }
    }
}

@Composable
fun NoteItem(note: Note, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = note.title, style = MaterialTheme.typography.titleMedium)
            val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
            val dateString = sdf.format(java.util.Date(note.lastModified))
            Text(text = "Last updated: $dateString", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun LockScreen(
    modifier: Modifier = Modifier,
    onUnlock: () -> Unit
) {
    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Locked",
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "One is locked",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onUnlock) {
                Text("Unlock")
            }
        }
    }
}
