package com.rohit.one.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    val current = remember { mutableStateOf<FilesRoute>(FilesRoute.Menu) }

    when (current.value) {
        FilesRoute.Menu -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Browse files", style = MaterialTheme.typography.headlineSmall)

                Button(
                    onClick = { current.value = FilesRoute.Local },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    Text(text = "Browse local files")
                }

                Button(
                    onClick = { current.value = FilesRoute.GoogleDrive },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(text = "Browse Google Drive")
                }

                Button(
                    onClick = { current.value = FilesRoute.OneDrive },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(text = "Browse OneDrive")
                }
            }
        }

        FilesRoute.Local -> LocalFilesScreen(onBack = { current.value = FilesRoute.Menu })
        FilesRoute.GoogleDrive -> DriveFilesScreen(onBack = { current.value = FilesRoute.Menu })
        FilesRoute.OneDrive -> OneDriveFilesScreen(onBack = { current.value = FilesRoute.Menu })
    }
}

sealed class FilesRoute {
    object Menu : FilesRoute()
    object Local : FilesRoute()
    object GoogleDrive : FilesRoute()
    object OneDrive : FilesRoute()
}
