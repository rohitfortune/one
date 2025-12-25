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
fun OneDriveFilesScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "OneDrive", style = MaterialTheme.typography.headlineSmall)

        Text(
            text = "To browse OneDrive you need to integrate Microsoft Graph and authenticate via OAuth.\n" +
                    "This screen is a placeholder for OneDrive integration.\n\nSee: https://learn.microsoft.com/graph/onedrive/",
            modifier = Modifier.padding(top = 12.dp)
        )

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(text = "Back")
        }
    }
}

