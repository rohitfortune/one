package com.rohit.one.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class DocEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val size: Long,
    val lastModified: Long
)

private enum class ViewMode { List, Grid }
private enum class SortOrder { Name, Date, Size }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true 
            }
        )
    }
    
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    hasPermission = Environment.isExternalStorageManager()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!hasPermission) {
        PermissionRequestScreen()
        return
    }

    val rootDir = Environment.getExternalStorageDirectory()
    
    // UI State
    val stack = remember { mutableStateListOf<File>(rootDir) }
    var viewMode by remember { mutableStateOf(ViewMode.List) }
    var sortOrder by remember { mutableStateOf(SortOrder.Name) }
    var showSortMenu by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val entries = remember { mutableStateListOf<DocEntry>() }
    
    // Dialog States
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog by remember { mutableStateOf<File?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) } // To force refresh

    // Back Handler
    BackHandler(enabled = stack.size > 1) {
        stack.removeAt(stack.lastIndex)
    }

    val currentDir = stack.last()
    
    LaunchedEffect(currentDir, sortOrder, refreshTrigger) {
        isLoading = true
        val result = withContext(Dispatchers.IO) {
            val fileList = currentDir.listFiles()?.toList() ?: emptyList()
            val docEntries = fileList.map { file ->
                val isDir = file.isDirectory
                val name = file.name
                val extension = file.extension.lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                val size = if (isDir) 0L else file.length()
                val date = file.lastModified()
                DocEntry(file, name, isDir, mime, size, date)
            }
            
             when (sortOrder) {
                SortOrder.Name -> docEntries.sortedWith(compareByDescending<DocEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                SortOrder.Date -> docEntries.sortedWith(compareByDescending<DocEntry> { it.isDirectory }.thenByDescending { it.lastModified })
                SortOrder.Size -> docEntries.sortedWith(compareByDescending<DocEntry> { it.isDirectory }.thenByDescending { it.size })
            }
        }
        entries.clear()
        entries.addAll(result)
        isLoading = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = if (stack.size == 1) "Local Storage" else currentDir.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (stack.size > 1) {
                            Text(text = currentDir.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines=1, overflow=TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }) {
                        Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateFolderDialog = true }) {
                        Icon(imageVector = Icons.Rounded.CreateNewFolder, contentDescription = "New Folder")
                    }
                    IconButton(onClick = { viewMode = if (viewMode == ViewMode.List) ViewMode.Grid else ViewMode.List }) {
                        Icon(
                            imageVector = if (viewMode == ViewMode.List) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                            contentDescription = "Toggle View"
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(imageVector = Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortOrder.values().forEach { order ->
                                DropdownMenuItem(
                                    text = { Text("Sort by ${order.name}") },
                                    onClick = {
                                        sortOrder = order
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
             BreadcrumbBar(stack) { index ->
                 while (stack.size > index + 1) {
                     stack.removeAt(stack.lastIndex)
                 }
             }
             
             LazyVerticalGrid(
                 columns = if (viewMode == ViewMode.Grid) GridCells.Adaptive(minSize = 100.dp) else GridCells.Fixed(1),
                 contentPadding = PaddingValues(bottom = 80.dp, start = 16.dp, end = 16.dp, top = 16.dp),
                 verticalArrangement = Arrangement.spacedBy(8.dp),
                 horizontalArrangement = Arrangement.spacedBy(8.dp),
                 modifier = Modifier.fillMaxSize()
             ) {
                 items(entries) { entry ->
                     FileItem(
                         entry = entry,
                         viewMode = viewMode,
                         onClick = {
                             if (entry.isDirectory) {
                                 stack.add(entry.file)
                             } else {
                                 openFile(context, entry.file, entry.mimeType)
                             }
                         },
                         onRename = { showRenameDialog = entry.file },
                         onDelete = { showDeleteDialog = entry.file },
                         onShare = { shareFile(context, entry.file, entry.mimeType) }
                     )
                 }
             }
        }
    }
    
    // Dialogs
    if (showCreateFolderDialog) {
        InputDialog(
            title = "New Folder",
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { name ->
                val newDir = File(currentDir, name)
                if (newDir.mkdir()) {
                    refreshTrigger++
                } else {
                    Toast.makeText(context, "Failed to create folder", Toast.LENGTH_SHORT).show()
                }
                showCreateFolderDialog = false
            }
        )
    }

    showRenameDialog?.let { file ->
        InputDialog(
            title = "Rename",
            initialValue = file.name,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName ->
                val newFile = File(file.parentFile, newName)
                if (file.renameTo(newFile)) {
                    refreshTrigger++
                } else {
                    Toast.makeText(context, "Failed to rename", Toast.LENGTH_SHORT).show()
                }
                showRenameDialog = null
            }
        )
    }

    showDeleteDialog?.let { file ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete ${file.name}?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    if (file.deleteRecursively()) {
                         refreshTrigger++
                    } else {
                         Toast.makeText(context, "Failed to delete", Toast.LENGTH_SHORT).show()
                    }
                    showDeleteDialog = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun InputDialog(
    title: String,
    initialValue: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PermissionRequestScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("Access Required", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "To browse your local drive, please grant 'All files access' permission.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:" + context.packageName)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            }
        }) {
            Text("Grant Permission")
        }
    }
}

@Composable
private fun BreadcrumbBar(stack: List<File>, onBreadcrumbClick: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(stack.size) { index ->
            val item = stack[index]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (index == 0) "Internal Storage" else item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index == stack.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = index != stack.lastIndex) { onBreadcrumbClick(index) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                if (index < stack.size - 1) {
                    Text("/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    entry: DocEntry,
    viewMode: ViewMode,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    
    val formattedDate = remember(entry.lastModified) {
        if (entry.lastModified > 0) {
            SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(entry.lastModified))
        } else {
            ""
        }
    }
    val formattedSize = remember(entry.size) {
        Formatter.formatFileSize(context, entry.size)
    }

    val itemContent = @Composable {
        if (viewMode == ViewMode.List) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FileThumbnail(entry = entry, size = 48.dp)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (entry.isDirectory) formattedDate else "$formattedSize • $formattedDate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(imageVector = Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    FileContextMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        onRename = onRename,
                        onDelete = onDelete,
                        onShare = onShare
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    FileThumbnail(entry = entry, size = 64.dp)
                    Box(modifier = Modifier.padding(top = 40.dp)) {
                         // Grid view menu is tricky, typically long press or a small button.
                         // For now, let's keep it simple or omitting generic menu in GridView for brevity?
                         // Better to just support LongPress for context menu generally, but fitting in "More" icon is standard.
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    if (viewMode == ViewMode.List) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
        ) {
            itemContent()
        }
    } else {
         Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Box {
                itemContent()
                // Overlay menu for Grid
                if (showMenu) {
                    // Position is tricky in Grid without cursor offset.
                    // We'll just show it at top start of card or similar? 
                    // Or actually utilize the DropdownMenu properly.
                    // Simplified: Just use a centered dialog or bottom sheet for grid context menu?
                    // Let's stick to DropdownMenu anchored to a placeholder.
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        FileContextMenu(
                            expanded = showMenu,
                            onDismiss = { showMenu = false },
                            onRename = onRename,
                            onDelete = onDelete,
                            onShare = onShare
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
            onClick = {
                onDismiss()
                onRename()
            }
        )
        DropdownMenuItem(
            text = { Text("Share") },
            leadingIcon = { Icon(Icons.Rounded.Share, null) },
            onClick = {
                onDismiss()
                onShare()
            }
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
            onClick = {
                onDismiss()
                onDelete()
            },
            colors = androidx.compose.material3.MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error
            )
        )
    }
}

@Composable
private fun FileThumbnail(entry: DocEntry, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    
    if (entry.isDirectory) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = "Folder",
            modifier = Modifier.size(size),
            tint = MaterialTheme.colorScheme.primary
        )
    } else {
        val isImage = entry.mimeType.startsWith("image/")
        if (isImage) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(entry.file)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = "File",
                modifier = Modifier.size(size),
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

private fun openFile(context: Context, file: File, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Failed to open file", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, file: File, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            setType(mimeType)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share file"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share this file", Toast.LENGTH_SHORT).show()
    }
}
