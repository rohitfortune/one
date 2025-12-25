@file:Suppress("UNUSED_VALUE", "DEPRECATION")
@file:OptIn(ExperimentalMaterial3Api::class)
package com.rohit.one.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rohit.one.data.BackupRepository
import com.rohit.one.data.BackupPayload
import com.rohit.one.viewmodel.NotesViewModel
import com.rohit.one.viewmodel.VaultsViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import com.rohit.one.auth.IdentityAuthProvider
import com.rohit.one.auth.CredentialAuthStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import java.util.UUID
import com.rohit.one.data.Note
import com.rohit.one.data.AttachmentExport

private enum class PassphraseAction { NONE, BACKUP, RESTORE }
private enum class OperationType { NONE, BACKUP, RESTORE }

// Helper: fetch latest backup createdTime ISO string and format to localized display string. Returns null if none.
private suspend fun getFormattedLatestBackupTime(backupRepository: BackupRepository, accessTokenProvider: suspend () -> String?): String? {
    val iso = try { backupRepository.getLatestBackupCreatedTime(accessTokenProvider) } catch (_: Exception) { null }
    if (iso == null) return null
    return try {
        val inst = Instant.parse(iso)
        val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
        fmt.format(inst)
    } catch (_: Exception) {
        iso
    }
}

private fun restoreAttachments(context: android.content.Context, exports: List<AttachmentExport>): List<Note.Attachment> {
    val attachmentsDir = java.io.File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }
    
    // Log what we are trying to restore
    val survey = exports.map { "uri=${it.uri} hasBase64=${it.base64Content != null} len=${it.base64Content?.length ?: 0}" }
    android.util.Log.d("BackupScreen", "Restoring attachments: $survey")

    return exports.map { export ->
        val uri = if (export.base64Content != null) {
            try {
                val bytes = Base64.decode(export.base64Content, Base64.DEFAULT)
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(export.mimeType) ?: "bin"
                val filename = "restored_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext"
                val file = java.io.File(attachmentsDir, filename)
                FileOutputStream(file).use { it.write(bytes) }
                file.absolutePath
            } catch (e: Exception) {
                android.util.Log.e("BackupScreen", "Failed to restore attachment", e)
                export.uri
            }
        } else {
            export.uri
        }
        Note.Attachment(uri, export.displayName, export.mimeType)
    }
}

@Composable
fun BackupScreen(
    modifier: Modifier = Modifier,
    backupRepository: BackupRepository,
    accessTokenProvider: suspend () -> String?,
    onSignIn: () -> Unit,
    notesViewModel: NotesViewModel,
    vaultsViewModel: VaultsViewModel,
    signedInUsername: String? = null,
    onSignOut: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val notes by notesViewModel.notes.collectAsState()
    val passwords by vaultsViewModel.passwords.collectAsState()
    val cards by vaultsViewModel.cards.collectAsState()
    val context = LocalContext.current
    // A UI scope tied to the Activity lifecycle (survives composable disposal). Fall back to composable scope.
    val uiScope = (context as? ComponentActivity)?.lifecycleScope ?: rememberCoroutineScope()

    val showPassphraseDialog = remember { mutableStateOf(false) }
    val passphrase = remember { mutableStateOf("") }
    var usePassphrase by remember { mutableStateOf(true) }
    val passphraseAction = remember { mutableStateOf(PassphraseAction.NONE) }
    // Progress state for passphrase dialog operations (show spinner and disable buttons)
    val passphraseInProgress = remember { mutableStateOf(false) }
    // Avoid repeatedly launching sign-in flows from automatic checks which can cause loops.
    val signInLaunched = remember { mutableStateOf(false) }
    // Signed-in state derived from provided username; when false, backup/restore should be disabled
    val isSignedIn = !signedInUsername.isNullOrBlank()

    val moshi = remember { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }
    val payloadAdapter = remember { moshi.adapter(BackupPayload::class.java) }

    val consentLauncher = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { _ ->
        // Consent UI returned — clear the launched flag and notify the user so they can retry the action.
        signInLaunched.value = false
        scope.launch { snackbarHostState.showSnackbar("Consent flow completed — please retry the action") }
    }

    // Track last backup time (formatted) and helper value - placed at composable scope so dialog can access it
    val lastBackupTime = remember { mutableStateOf<String?>(null) }
    // Track which operation (if any) is running so spinner can appear beside the active button
    val operationInProgress = remember { mutableStateOf(OperationType.NONE) }

    // Refresh last backup time when signed-in state changes
    LaunchedEffect(isSignedIn) {
        if (isSignedIn) {
            try { lastBackupTime.value = getFormattedLatestBackupTime(backupRepository, accessTokenProvider) } catch (_: Exception) {}
        } else {
            lastBackupTime.value = null
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        val layoutDir = LocalLayoutDirection.current
        // Apply scaffold insets for start/end/bottom but force top to 0 to remove empty top space.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = innerPadding.calculateStartPadding(layoutDir) + 16.dp,
                    end = innerPadding.calculateEndPadding(layoutDir) + 16.dp,
                    top = 24.dp, // Add margin from top
                    bottom = innerPadding.calculateBottomPadding() + 16.dp
                )
        ) {
            // Only show the interactive Sign-in button when no user is currently signed in.
            if (signedInUsername.isNullOrBlank()) {
                Button(onClick = { onSignIn() }) { Text("Sign in to Google") }
            }

            // Signed-in header row: username left, compact logout control right (matches "Use passphrase backup" layout)
            if (!signedInUsername.isNullOrBlank()) {
                Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = signedInUsername)
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                val res = IdentityAuthProvider.signOut(context)
                                if (res.isSuccess) {
                                    snackbarHostState.showSnackbar("Signed out")
                                } else {
                                    snackbarHostState.showSnackbar("Sign-out failed")
                                }
                                try { onSignOut?.invoke() } catch (_: Exception) {}
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Sign-out error: ${e.message}")
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Logout, contentDescription = "Sign out")
                    }
                }
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text("Use passphrase backup")
                Spacer(modifier = Modifier.weight(1f))
                Switch(checked = usePassphrase, onCheckedChange = { usePassphrase = it })
            }

            // Backup
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    if (usePassphrase) {
                        passphraseAction.value = PassphraseAction.BACKUP
                        showPassphraseDialog.value = true
                        return@Button
                    }

                    scope.launch {
                        operationInProgress.value = OperationType.BACKUP
                        try {
                            val token = try { accessTokenProvider() } catch (e: Exception) {
                                // Handle UserRecoverableAuthException without referencing its type at compile-time.
                                try {
                                    val getIntent = e.javaClass.getMethod("getIntent")
                                    val intent = getIntent.invoke(e) as? android.content.Intent
                                    if (intent != null) {
                                        // mark launched so we don't re-trigger repeatedly
                                        signInLaunched.value = true
                                        consentLauncher.launch(intent)
                                        snackbarHostState.showSnackbar("Please grant Drive permission and retry")
                                        return@launch
                                    }
                                } catch (_: Exception) {
                                    // not a UserRecoverableAuthException or reflection failed; rethrow
                                }
                                throw e
                            }
                            if (token == null) {
                                // We won't automatically open the sign-in flow to avoid loops.
                                // Ask the user to explicitly tap the existing "Sign in to Google" button.
                                snackbarHostState.showSnackbar("No Drive token available — please sign in using the 'Sign in to Google' button and grant Drive access")
                                return@launch
                            }

                            // token is available here (polled earlier if needed)
                            val finalToken = token
                            // Quick heuristic: Google OAuth access tokens typically start with "ya29." and are long.
                            val looksLikeAccessToken = try { finalToken.startsWith("ya29.") || finalToken.length >= 60 } catch (_: Exception) { false }
                            if (!looksLikeAccessToken) {
                                val preview = if (finalToken.isNotEmpty()) try { finalToken.take(6) + "..." + finalToken.takeLast(6) } catch (_: Exception) { "<hidden>" } else "<hidden>"
                                uiScope.launch { snackbarHostState.showSnackbar("Stored credential doesn't look like an access token. Preview: $preview — please sign in and grant Drive access via the Sign in button") }
                                return@launch
                            }
                            // token looks like a real access token — clear the launched flag so future checks can re-trigger sign-in as needed
                            signInLaunched.value = false

                            try {
                                val ok = backupRepository.performDeviceBackup(
                                    notes.toList(),
                                    passwords.toList(),
                                    cards.toList(),
                                    fetchRawPassword = { uuid -> withContext(Dispatchers.IO) { vaultsViewModel.getRawPassword(uuid) } },
                                    fetchFullCardNumber = { uuid -> withContext(Dispatchers.IO) { vaultsViewModel.getFullNumber(uuid) } },
                                    accessTokenProvider = accessTokenProvider
                                )
                                if (ok) {
                                    snackbarHostState.showSnackbar("Backup uploaded")
                                    // Refresh visible last backup time
                                    scope.launch { try { lastBackupTime.value = getFormattedLatestBackupTime(backupRepository, accessTokenProvider) } catch (_: Exception) {} }
                                } else {
                                    snackbarHostState.showSnackbar("Backup upload failed")
                                }
                            } catch (e: Exception) {
                                // If Drive returned 401/UNAUTHENTICATED, clear saved token and prompt interactive sign-in
                                val msg = e.message ?: "Backup failed"
                                if (msg.contains("code=401") || msg.contains("UNAUTHENTICATED") || msg.contains("Invalid Credentials")) {
                                    try { CredentialAuthStore.clear(context) } catch (_: Exception) {}
                                    snackbarHostState.showSnackbar("Authentication failed — cleared saved credentials. Please sign in to Google and grant Drive access.")
                                    onSignIn()
                                } else {
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Backup failed: ${e.message}")
                        } finally {
                            operationInProgress.value = OperationType.NONE
                        }
                    }
                }, enabled = isSignedIn) { Text("Backup now") }

                // Show a small inline progress indicator while backup is running; otherwise show last backup time
                if (operationInProgress.value == OperationType.BACKUP) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (isSignedIn) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = lastBackupTime.value ?: "No backups", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Restore (button + inline spinner)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    if (usePassphrase) {
                        passphraseAction.value = PassphraseAction.RESTORE
                        showPassphraseDialog.value = true
                        return@Button
                    }

                    scope.launch {
                        operationInProgress.value = OperationType.RESTORE
                        try {
                            val token = try { accessTokenProvider() } catch (e: Exception) {
                                // Handle UserRecoverableAuthException without referencing its type at compile-time.
                                try {
                                    val getIntent = e.javaClass.getMethod("getIntent")
                                    val intent = getIntent.invoke(e) as? android.content.Intent
                                    if (intent != null) {
                                        // mark launched so we don't re-trigger repeatedly
                                        signInLaunched.value = true
                                        consentLauncher.launch(intent)
                                        snackbarHostState.showSnackbar("Please grant Drive permission and retry")
                                        return@launch
                                    }
                                } catch (_: Exception) {
                                    // not a UserRecoverableAuthException or reflection failed; rethrow
                                }
                                throw e
                            }
                            if (token == null) {
                                // We won't automatically open the sign-in flow to avoid loops.
                                // Ask the user to explicitly tap the existing "Sign in to Google" button.
                                snackbarHostState.showSnackbar("No Drive token available — please sign in using the 'Sign in to Google' button and grant Drive access")
                                return@launch
                            }

                            // token is available here (polled earlier if needed)
                            val finalToken = token
                            // Quick heuristic: Google OAuth access tokens typically start with "ya29." and are long.
                            val looksLikeAccessToken = try { finalToken.startsWith("ya29.") || finalToken.length >= 60 } catch (_: Exception) { false }
                            if (!looksLikeAccessToken) {
                                val preview = if (finalToken.isNotEmpty()) try { finalToken.take(6) + "..." + finalToken.takeLast(6) } catch (_: Exception) { "<hidden>" } else "<hidden>"
                                uiScope.launch { snackbarHostState.showSnackbar("Stored credential doesn't look like an access token. Preview: $preview — please sign in and grant Drive access via the Sign in button") }
                                return@launch
                            }
                            signInLaunched.value = false

                            try {
                                val json = backupRepository.performDeviceRestore(accessTokenProvider)
                                if (json == null) {
                                    snackbarHostState.showSnackbar("No backup found or restore failed")
                                    return@launch
                                }
                                val parsed = payloadAdapter.fromJson(json) ?: run {
                                    snackbarHostState.showSnackbar("Invalid backup format")
                                    return@launch
                                }

                                // restore notes including inline attachments and paths
                                parsed.notes.forEach { n ->
                                    val restoredAttachments = restoreAttachments(context, n.attachments)
                                    notesViewModel.restoreNoteFromBackup(n.title, n.content, restoredAttachments, n.paths)
                                }
                                parsed.passwords.forEach { p ->
                                    vaultsViewModel.restorePasswordFromBackup(
                                        p.uuid,
                                        p.title,
                                        p.username,
                                        p.rawPassword,
                                        p.createdAt
                                    )
                                }
                                parsed.cards.forEach { c ->
                                    vaultsViewModel.restoreCardFromBackup(
                                        c.uuid,
                                        c.cardholderName,
                                        c.fullNumber,
                                        c.brand,
                                        c.expiry,
                                        c.securityCode,
                                        c.createdAt
                                    )
                                }
                                // attachments are included in notes, no separate matching needed
                                snackbarHostState.showSnackbar("Restore completed: notes=${parsed.notes.size}, passwords=${parsed.passwords.size}, cards=${parsed.cards.size}")
                                // After a successful restore, refresh last backup timestamp display
                                scope.launch { try { lastBackupTime.value = getFormattedLatestBackupTime(backupRepository, accessTokenProvider) } catch (_: Exception) {} }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(e.message ?: "Restore failed")
                            }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Restore failed: ${e.message}")
                        } finally {
                            uiScope.launch { operationInProgress.value = OperationType.NONE }
                        }
                    }
                }, enabled = isSignedIn) { Text("Restore latest backup") }

                // Show spinner inline beside Restore button when restore is running
                if (operationInProgress.value == OperationType.RESTORE) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            // Removed 'Clear saved credentials' debug button per request

            // NOTE: The debug 'Inject test credential' UI was removed to prevent accidentally
            // using test tokens in development which previously caused confusing 401 errors.
            // If you need to inject tokens for local development, use a developer-only
            // diagnostic flow that explicitly documents how and where tokens are stored.
            // Debug export UI removed. For development token inspection use `adb exec-out run-as com.rohit.one cat files/debug_stored_token.txt` if needed.
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showPassphraseDialog.value) {
        AlertDialog(
            onDismissRequest = { if (!passphraseInProgress.value) showPassphraseDialog.value = false },
            title = { Text("Enter passphrase") },
            text = {
                Column {
                    OutlinedTextField(value = passphrase.value, onValueChange = { passphrase.value = it }, label = { Text("Passphrase") }, enabled = !passphraseInProgress.value)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Passphrase is required to encrypt/decrypt portable backups")
                }
            },
            confirmButton = {
                if (passphraseInProgress.value) {
                    // Keep a small progress indicator in place of the OK button while work is running
                    Box(modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    TextButton(onClick = {
                        // Capture values and close dialog immediately; show progress beside the active button
                        val passChars = passphrase.value.toCharArray()
                        val action = passphraseAction.value
                        // dismiss dialog before starting work, set which operation is running
                        showPassphraseDialog.value = false
                        passphrase.value = ""
                        operationInProgress.value = if (action == PassphraseAction.BACKUP) OperationType.BACKUP else OperationType.RESTORE

                        // choose activity lifecycleScope if available so operation continues across navigation
                        val activityScope = (context as? ComponentActivity)?.lifecycleScope
                        val launchScope = activityScope ?: scope
                        launchScope.launch(Dispatchers.IO) {
                            try {
                                when (action) {
                                    PassphraseAction.BACKUP -> {
                                        try {
                                            val token = try { accessTokenProvider() } catch (e: Exception) {
                                                try {
                                                    val getIntent = e.javaClass.getMethod("getIntent")
                                                    val intent = getIntent.invoke(e) as? android.content.Intent
                                                    if (intent != null) {
                                                        // ensure we notify UI on main thread using Activity lifecycle scope so UI updates run even if composable is destroyed
                                                        uiScope.launch { signInLaunched.value = true; consentLauncher.launch(intent); snackbarHostState.showSnackbar("Please grant Drive permission and retry") }
                                                        return@launch
                                                    }
                                                } catch (_: Exception) {}
                                                throw e
                                            }
                                            if (token == null) {
                                                uiScope.launch { snackbarHostState.showSnackbar("No Drive token available — please sign in using the 'Sign in to Google' button and grant Drive access") }
                                                return@launch
                                            }
                                            uiScope.launch { snackbarHostState.showSnackbar("Uploading backup...") }
                                            val ok = backupRepository.performPassphraseBackup(
                                                notes.toList(),
                                                passwords.toList(),
                                                cards.toList(),
                                                fetchRawPassword = { uuid -> withContext(Dispatchers.IO) { vaultsViewModel.getRawPassword(uuid) } },
                                                fetchFullCardNumber = { uuid -> withContext(Dispatchers.IO) { vaultsViewModel.getFullNumber(uuid) } },
                                                passphrase = passChars,
                                                accessTokenProvider = accessTokenProvider
                                            )
                                            if (ok) {
                                                uiScope.launch { snackbarHostState.showSnackbar("Passphrase backup uploaded"); lastBackupTime.value = getFormattedLatestBackupTime(backupRepository, accessTokenProvider) }
                                            } else {
                                                uiScope.launch { snackbarHostState.showSnackbar("Backup upload failed") }
                                            }
                                        } catch (e: Exception) {
                                            uiScope.launch { snackbarHostState.showSnackbar("Backup failed: ${e.message}") }
                                        }
                                    }
                                    PassphraseAction.RESTORE -> {
                                        try {
                                            val token = try { accessTokenProvider() } catch (e: Exception) {
                                                try {
                                                    val getIntent = e.javaClass.getMethod("getIntent")
                                                    val intent = getIntent.invoke(e) as? android.content.Intent
                                                    if (intent != null) {
                                                        // ensure we notify UI on main thread using Activity lifecycle scope so UI updates run even if composable is destroyed
                                                        uiScope.launch { signInLaunched.value = true; consentLauncher.launch(intent); snackbarHostState.showSnackbar("Please grant Drive permission and retry") }
                                                        return@launch
                                                    }
                                                } catch (_: Exception) {}
                                                throw e
                                            }
                                            if (token == null) {
                                                uiScope.launch { snackbarHostState.showSnackbar("No Drive token available — please sign in using the 'Sign in to Google' button and grant Drive access") }
                                                return@launch
                                            }
                                            uiScope.launch { snackbarHostState.showSnackbar("Restoring backup...") }
                                            val json = backupRepository.performPassphraseRestore(accessTokenProvider, passChars)
                                            if (json == null) {
                                                uiScope.launch { snackbarHostState.showSnackbar("No backup found or decrypt failed") }
                                                return@launch
                                            }
                                            val parsed = try { payloadAdapter.fromJson(json) } catch (_: Exception) { null }
                                            if (parsed == null) {
                                                uiScope.launch { snackbarHostState.showSnackbar("Invalid backup format") }
                                                return@launch
                                            }
                                            uiScope.launch {
                                                parsed.notes.forEach { n ->
                                                    val restoredAttachments = restoreAttachments(context, n.attachments)
                                                    notesViewModel.restoreNoteFromBackup(n.title, n.content, restoredAttachments, n.paths)
                                                }
                                                parsed.passwords.forEach { p ->
                                                    vaultsViewModel.restorePasswordFromBackup(
                                                        p.uuid,
                                                        p.title,
                                                        p.username,
                                                        p.rawPassword,
                                                        p.createdAt
                                                    )
                                                }
                                                parsed.cards.forEach { c ->
                                                    vaultsViewModel.restoreCardFromBackup(
                                                        c.uuid,
                                                        c.cardholderName,
                                                        c.fullNumber,
                                                        c.brand,
                                                        c.expiry,
                                                        c.securityCode,
                                                        c.createdAt
                                                    )
                                                }
                                                snackbarHostState.showSnackbar("Restore completed: notes=${parsed.notes.size}, passwords=${parsed.passwords.size}, cards=${parsed.cards.size}")
                                                try { lastBackupTime.value = getFormattedLatestBackupTime(backupRepository, accessTokenProvider) } catch (_: Exception) {}
                                            }
                                        } catch (e: Exception) {
                                            uiScope.launch { snackbarHostState.showSnackbar("Restore failed: ${e.message}") }
                                        }
                                    }
                                    else -> {}
                                }
                            } finally {
                                // wipe passphrase from memory and reset state on main thread
                                scope.launch {
                                    for (i in passChars.indices) passChars[i] = '\u0000'
                                    passphraseAction.value = PassphraseAction.NONE
                                    operationInProgress.value = OperationType.NONE
                                }
                            }
                        }
                    }) { Text("OK") }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!passphraseInProgress.value) showPassphraseDialog.value = false }, enabled = !passphraseInProgress.value) { Text("Cancel") }
            }
        )
    }
}
