package com.rohit.one.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DriveFilesScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Google Drive", style = MaterialTheme.typography.headlineSmall)

        Text(
            text = "To browse Google Drive you need to integrate Google Drive REST or the Drive Picker.\n" +
                    "This screen is a placeholder.\n\nSee: https://developers.google.com/drive/api/v3/about-sdk",
            modifier = Modifier.padding(top = 12.dp)
        )

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(text = "Back")
        }
    }
}

