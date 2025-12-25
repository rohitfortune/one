package com.rohit.one.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.rohit.one.auth.IdentityAuthProvider
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedTime: Long,
    val thumbnailLink: String?
) {
    val isDirectory: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}

private enum class DriveViewMode { List, Grid }
private enum class DriveSortOrder { Name, Date } // Size often not available for folders

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveFilesScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    signedInAccount: String?
) {
    val context = LocalContext.current
    
    if (signedInAccount == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Google Drive", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Sign in to access your files", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onSignIn) {
                Text("Sign In with Google")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack) {
                Text("Back")
            }
        }
        return
    }

    // State
    var accessToken by remember { mutableStateOf<String?>(null) }
    var folderStack by remember { mutableStateOf(listOf(DriveFile("root", "My Drive", "application/vnd.google-apps.folder", 0, 0, null))) }
    var fileList by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(DriveViewMode.List) }
    var sortOrder by remember { mutableStateOf(DriveSortOrder.Name) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Fetch Token
    LaunchedEffect(signedInAccount) {
        isLoading = true
        try {
            accessToken = IdentityAuthProvider.getAccessToken(context, signedInAccount)
            if (accessToken == null) {
                error = "Failed to get access token"
            }
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = accessToken == null // Stop loading only if we failed, otherwise fetch files will start
        }
    }

    // Fetch Files
    val currentFolder = folderStack.last()
    LaunchedEffect(accessToken, currentFolder, sortOrder) {
        if (accessToken == null) return@LaunchedEffect
        isLoading = true
        error = null
        try {
            val files = listDriveFiles(accessToken!!, currentFolder.id, sortOrder)
            fileList = files
        } catch (e: Exception) {
            error = "Error loading files: " + e.message
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = currentFolder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = signedInAccount, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (folderStack.size > 1) folderStack = folderStack.dropLast(1) else onBack() }) {
                        Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewMode = if (viewMode == DriveViewMode.List) DriveViewMode.Grid else DriveViewMode.List }) {
                        Icon(
                            imageVector = if (viewMode == DriveViewMode.List) Icons.Rounded.GridView else Icons.Rounded.ViewList,
                            contentDescription = "Toggle View"
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(imageVector = Icons.Rounded.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            DriveSortOrder.values().forEach { order ->
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (folderStack.size > 1) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(folderStack.size) { index ->
                        val item = folderStack[index]
                         Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (index == folderStack.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(enabled = index != folderStack.lastIndex) { 
                                        folderStack = folderStack.take(index + 1) 
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                            if (index < folderStack.size - 1) {
                                Text("/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = error ?: "Unknown error", color = MaterialTheme.colorScheme.error)
                }
            } else {
                 LazyVerticalGrid(
                     columns = if (viewMode == DriveViewMode.Grid) GridCells.Adaptive(minSize = 100.dp) else GridCells.Fixed(1),
                     contentPadding = PaddingValues(16.dp),
                     verticalArrangement = Arrangement.spacedBy(8.dp),
                     horizontalArrangement = Arrangement.spacedBy(8.dp),
                     modifier = Modifier.fillMaxSize()
                 ) {
                     items(fileList) { file ->
                        DriveFileItem(
                            file = file,
                            viewMode = viewMode,
                            accessToken = accessToken,
                            onClick = {
                                if (file.isDirectory) {
                                    folderStack = folderStack + file
                                } else {
                                    // TODO: Open file handling (intent or download)
                                }
                            }
                        )
                     }
                 }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveFileItem(
    file: DriveFile,
    viewMode: DriveViewMode,
    accessToken: String?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val formattedDate = remember(file.modifiedTime) {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(file.modifiedTime))
    }
    
    val itemContent = @Composable {
        if (viewMode == DriveViewMode.List) {
             Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DriveThumbnail(file, 48.dp, accessToken)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                       text = formattedDate, // Size is tricky for Drive files, requires more logic/API
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
             Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                DriveThumbnail(file, 64.dp, accessToken)
                Spacer(modifier = Modifier.height(8.dp))
                 Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    if (viewMode == DriveViewMode.List) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick)
        ) {
            itemContent()
        }
    } else {
         Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f) // file card ratio
                .combinedClickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
              itemContent()
        }
    }
}

@Composable
private fun DriveThumbnail(file: DriveFile, size: androidx.compose.ui.unit.Dp, accessToken: String?) {
     val context = LocalContext.current
     if (file.isDirectory) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = "Folder",
            modifier = Modifier.size(size),
            tint = MaterialTheme.colorScheme.primary
        )
    } else {
         val isImage = file.mimeType.startsWith("image/")
         if (isImage && !file.thumbnailLink.isNullOrBlank() && accessToken != null) {
              // Drive thumbnails usually don't need auth headers if 'thumbnailLink' is public/short-lived?
              // Actually, thumbnailLink often works without auth for a short time, but secure way is headers if "alt=media" on fileId.
              // But 'thumbnailLink' field in API is public-ish. Let's try direct first. 
              // If we used 'webContentLink' or downloaded content, we'd need auth.
              AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file.thumbnailLink)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp))
            )
         } else {
             Icon(
                imageVector = Icons.Rounded.InsertDriveFile,
                contentDescription = "File",
                modifier = Modifier.size(size),
                tint = MaterialTheme.colorScheme.secondary
            )
         }
    }
}

// Drive API Logic
private suspend fun listDriveFiles(accessToken: String, folderId: String, sortOrder: DriveSortOrder): List<DriveFile> = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    
    val orderBy = when(sortOrder) {
        DriveSortOrder.Name -> "folder,name"
        DriveSortOrder.Date -> "folder,modifiedTime desc"
    }
    
    val query = "'$folderId' in parents and trashed = false"
    // Request thumbnailLink for images
    val fields = "files(id, name, mimeType, size, modifiedTime, thumbnailLink)"
    
    val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&orderBy=${java.net.URLEncoder.encode(orderBy, "UTF-8")}&fields=${java.net.URLEncoder.encode(fields, "UTF-8")}&pageSize=1000"
    
     val request = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $accessToken")
        .get()
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw Exception("Drive Error: ${response.code}")
        
        val body = response.body?.string() ?: return@withContext emptyList()
        
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val adapter: JsonAdapter<Map<String, Any>> = moshi.adapter(type)
        val map = adapter.fromJson(body)
        
        val filesRaw = map?.get("files") as? List<Map<String, Any>> ?: emptyList()
        
        filesRaw.map { raw ->
            val id = raw["id"] as? String ?: ""
            val name = raw["name"] as? String ?: ""
            val mimeType = raw["mimeType"] as? String ?: ""
            val sizeStr = raw["size"] as? String
            val size = sizeStr?.toLongOrNull() ?: 0L
            val modTimeStr = raw["modifiedTime"] as? String
            // modifiedTime is ISO8601 (e.g. 2023-10-25T...) - Need to parse if we want Long
            // Simple approach: just use current time if fill, or parse properly. 
            // For now, let's just store 0 or try parse.
            val modifiedTime = try {
                 // Instant.parse(modTimeStr).toEpochMilli() // Requires API 26
                 // SimpleDateFormat for ISO
                 SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parse(modTimeStr ?: "")?.time ?: 0L
            } catch (e: Exception) { 0L }
            
            val thumb = raw["thumbnailLink"] as? String
            
            DriveFile(id, name, mimeType, size, modifiedTime, thumb)
        }
    }
}
