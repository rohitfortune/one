@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.rohit.one.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rohit.one.data.BackupRepository
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(
    activity: Activity,
    backupRepository: BackupRepository,
    accessTokenProvider: suspend () -> String?,
    onSignIn: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Backup / Restore") }) }
    ) { inner ->
        Column(modifier = Modifier.padding(inner).padding(16.dp).fillMaxSize()) {
            Button(onClick = { onSignIn() }) {
                Text("Sign in to Google")
            }

            Button(onClick = {
                scope.launch {
                    val token = accessTokenProvider()
                    if (token == null) return@launch
                    // gather data from app (notes, passwords, cards) - placeholder
                    val payload = "{\"notes\":[], \"passwords\":[], \"cards\":[]}"
                    val enc = backupRepository.createEncryptedBackup(payload)
                    val ok = backupRepository.uploadBackupToDrive(enc, token)
                    // show result - left as an exercise to implement Snackbar
                }
            }) {
                Text("Backup now")
            }

            Button(onClick = {
                scope.launch {
                    val token = accessTokenProvider()
                    if (token == null) return@launch
                    val enc = backupRepository.downloadLatestBackupFromDrive(token)
                    val json = enc?.let { backupRepository.decryptBackup(it) }
                    // parse JSON and restore - left as exercise
                }
            }) {
                Text("Restore latest backup")
            }
        }
    }
}
