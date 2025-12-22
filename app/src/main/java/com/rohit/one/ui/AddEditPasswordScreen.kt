@file:Suppress("DEPRECATION")
package com.rohit.one.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rohit.one.data.Password
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditPasswordScreen(
    password: Password?,
    onSave: (String, String, String) -> Unit,
    onDelete: (Password) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    // requestRawPassword is async: caller performs any auth and invokes the onResult callback with the secret
    requestRawPassword: (((String?) -> Unit) -> Unit)? = null
) {
    var title by remember { mutableStateOf(password?.title ?: "") }
    var username by remember { mutableStateOf(password?.username ?: "") }
    var rawPassword by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // If editing an existing password and requestRawPassword is provided, we load it only when user requests reveal
    // LaunchedEffect is no longer used for automatic load; reveal is driven by the button callback

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = if (password == null) "Add Password" else "Edit Password") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (password != null) {
                        IconButton(onClick = { onDelete(password) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onSave(title, username, rawPassword) }) {
                Icon(Icons.Filled.Done, contentDescription = "Save password")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            TextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Domain") },
                modifier = Modifier.padding(bottom = 8.dp)
            )
            TextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.padding(bottom = 8.dp)
            )
            TextField(
                value = rawPassword,
                onValueChange = { rawPassword = it },
                label = { Text("Password") },
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                placeholder = { if (password != null && rawPassword.isEmpty()) Text("(hidden, reveal to load)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            Row {
                TextButton(onClick = {
                    if (password != null && requestRawPassword != null) {
                        if (!revealed) {
                            // request the secret via callback (which should perform biometric auth)
                            requestRawPassword.invoke { result ->
                                if (result != null) {
                                    rawPassword = result
                                    revealed = true
                                    // auto-hide after 30s
                                    coroutineScope.launch {
                                        delay(30_000)
                                        revealed = false
                                    }
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Authentication failed")
                                    }
                                }
                            }
                        } else {
                            // hide
                            revealed = false
                        }
                    } else {
                        // for new password entries or when no request callback is available, just toggle visibility
                        revealed = !revealed
                    }
                }) {
                    Text(if (revealed) "Hide password" else "Reveal password")
                }

                if (revealed && rawPassword.isNotEmpty()) {
                    IconButton(onClick = {
                        // copy to system clipboard
                        val clip = ClipData.newPlainText("password", rawPassword)
                        clipboardManager.setPrimaryClip(clip)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Copied to clipboard")
                            // clear clipboard after 10s
                            delay(10_000)
                            // clear by setting empty clip
                            clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                }
            }
        }
    }
}
