package com.rohit.one.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    onSignIn: () -> Unit,
    signedInAccount: String?
) {
    var selectedItem by rememberSaveable { mutableIntStateOf(0) }
    val items = listOf("Local", "Drive", "OneDrive")
    val icons = listOf(Icons.Rounded.Smartphone, Icons.Rounded.Cloud, Icons.Rounded.CloudQueue)

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = item) },
                        label = { Text(item) },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
            when (selectedItem) {
                0 -> LocalFilesScreen(onBack = { /* No-op at top level */ })
                1 -> DriveFilesScreen(
                    onBack = { /* No-op at top level */ },
                    onSignIn = onSignIn,
                    signedInAccount = signedInAccount
                )
                2 -> OneDriveFilesScreen(onBack = { /* No-op at top level */ })
            }
        }
    }
}
