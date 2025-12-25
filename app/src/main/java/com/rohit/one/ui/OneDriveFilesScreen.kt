package com.rohit.one.ui

import android.app.Activity
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
import com.rohit.one.auth.MsGraphAuthProvider
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class OneDriveFile(
    val id: String,
    val name: String,
    val mimeType: String?, // Graph doesn't always give MIME, check 'folder' or 'file' property
    val size: Long,
    val modifiedTime: Long,
    val thumbnailLink: String?
) {
    val isDirectory: Boolean get() = mimeType == "folder" || mimeType == "application/vnd.google-apps.folder" // Standardize check
}

private enum class OneDriveViewMode { List, Grid }
private enum class OneDriveSortOrder { Name, Date }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneDriveFilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // State
    var isSignedIn by remember { mutableStateOf(false) }
    var accountName by remember { mutableStateOf<String?>(null) }
    var accessToken by remember { mutableStateOf<String?>(null) }
    var authInitialized by remember { mutableStateOf(false) }

    var folderStack by remember { mutableStateOf(listOf(OneDriveFile("root", "OneDrive", "folder", 0, 0, null))) }
    var fileList by remember { mutableStateOf<List<OneDriveFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    
    var viewMode by remember { mutableStateOf(OneDriveViewMode.List) }
    var sortOrder by remember { mutableStateOf(OneDriveSortOrder.Name) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Init Auth
    LaunchedEffect(Unit) {
        if (MsGraphAuthProvider.init(context)) {
            val account = MsGraphAuthProvider.getCurrentAccount()
            if (account != null) {
                isSignedIn = true
                accountName = account.username
                // Silently get token
                val result = MsGraphAuthProvider.acquireTokenSilent()
                accessToken = result?.accessToken
            }
            authInitialized = true
        } else {
            error = "Failed to initialize MSAL"
            authInitialized = true
        }
    }

    // Fetch Files
    val currentFolder = folderStack.last()
    LaunchedEffect(accessToken, currentFolder, sortOrder) {
        if (accessToken == null) return@LaunchedEffect
        isLoading = true
        error = null
        try {
            val files = listOneDriveFiles(accessToken!!, currentFolder.id, sortOrder)
            fileList = files
        } catch (e: Exception) {
            error = "Error loading files: " + e.message
        } finally {
            isLoading = false
        }
    }

    if (!authInitialized) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!isSignedIn) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Microsoft OneDrive", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Sign in to access your files", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(onClick = {
                activity?.let {
                    kotlinx.coroutines.MainScope().launch(Dispatchers.Main) { 
                        // Using MainScope just to launch, ideally should use rememberCoroutineScope 
                        // but logic inside clickable usually fine. 
                        // Actually, let's use a side-effect or similar.
                        // For simplicity in onClick:
                        val res = MsGraphAuthProvider.signIn(it)
                        if (res != null) {
                            isSignedIn = true
                            accountName = res.account.username
                            accessToken = res.accessToken
                        } else {
                            // error handled in logs
                        }
                    }
                }
            }) {
                Text("Sign In with Microsoft")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack) {
                Text("Back")
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                "Note: You must configure a valid Client Key in gradle/manifest for this to work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = currentFolder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = accountName ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (folderStack.size > 1) folderStack = folderStack.dropLast(1) else onBack() }) {
                        Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewMode = if (viewMode == OneDriveViewMode.List) OneDriveViewMode.Grid else OneDriveViewMode.List }) {
                        Icon(
                            imageVector = if (viewMode == OneDriveViewMode.List) Icons.Rounded.GridView else Icons.Rounded.ViewList,
                            contentDescription = "Toggle View"
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(imageVector = Icons.Rounded.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            OneDriveSortOrder.values().forEach { order ->
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
                    // Sign out option
                     IconButton(onClick = {
                        kotlinx.coroutines.MainScope().launch(Dispatchers.Main) {
                            if (MsGraphAuthProvider.signOut()) {
                                isSignedIn = false
                                accessToken = null
                                accountName = null
                            }
                        }
                    }) {
                        // Using a different icon or overflow menu would be better, but quick SignOut button:
                        // Icon(Icons.Rounded.ExitToApp, "Sign Out") // Icon might not exist in this set
                        Text("LO", style = MaterialTheme.typography.labelSmall) // Placeholder
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
                     columns = if (viewMode == OneDriveViewMode.Grid) GridCells.Adaptive(minSize = 100.dp) else GridCells.Fixed(1),
                     contentPadding = PaddingValues(16.dp),
                     verticalArrangement = Arrangement.spacedBy(8.dp),
                     horizontalArrangement = Arrangement.spacedBy(8.dp),
                     modifier = Modifier.fillMaxSize()
                 ) {
                     items(fileList) { file ->
                        OneDriveFileItem(
                            file = file,
                            viewMode = viewMode,
                            accessToken = accessToken,
                            onClick = {
                                if (file.isDirectory) {
                                    folderStack = folderStack + file
                                } else {
                                    // TODO: Open handling
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
private fun OneDriveFileItem(
    file: OneDriveFile,
    viewMode: OneDriveViewMode,
    accessToken: String?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val formattedDate = remember(file.modifiedTime) {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(file.modifiedTime))
    }
    
    val itemContent = @Composable {
        if (viewMode == OneDriveViewMode.List) {
             Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OneDriveThumbnail(file, 48.dp, accessToken)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                       text = formattedDate,
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
                OneDriveThumbnail(file, 64.dp, accessToken)
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

    if (viewMode == OneDriveViewMode.List) {
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
                .aspectRatio(0.8f)
                .combinedClickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
              itemContent()
        }
    }
}

@Composable
private fun OneDriveThumbnail(file: OneDriveFile, size: androidx.compose.ui.unit.Dp, accessToken: String?) {
     val context = LocalContext.current
     if (file.isDirectory) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = "Folder",
            modifier = Modifier.size(size),
            tint = MaterialTheme.colorScheme.primary
        )
    } else {
         val isImage = file.mimeType?.startsWith("image/") == true
         if (isImage && !file.thumbnailLink.isNullOrBlank()) {
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

// Graph API Logic
private suspend fun listOneDriveFiles(accessToken: String, folderId: String, sortOrder: OneDriveSortOrder): List<OneDriveFile> = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    
    val orderBy = when(sortOrder) {
        OneDriveSortOrder.Name -> "name"
        OneDriveSortOrder.Date -> "lastModifiedDateTime desc"
    }

    // Graph API endpoint
    // If root, use /me/drive/root/children
    // If other, use /me/drive/items/{item-id}/children
    val endpoint = if (folderId == "root") {
        "https://graph.microsoft.com/v1.0/me/drive/root/children"
    } else {
        "https://graph.microsoft.com/v1.0/me/drive/items/$folderId/children"
    }
    
    // Graph uses $select, $orderby, $top. Query params must be properly encoded/placed.
    // NOTE: Graph query params are standard $ prefixed.
    // But OkHttp usually doesn't encoding raw URLs well if mixed? 
    // Let's construct manually.
    
    val url = "$endpoint?\$select=id,name,folder,package,file,size,lastModifiedDateTime&\$orderby=$orderBy&\$top=1000&\$expand=thumbnails"
    
     val request = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $accessToken")
        .get()
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw Exception("Graph Error: ${response.code} ${response.message}")
        
        val body = response.body?.string() ?: return@withContext emptyList()
        // Log.d("OneDrive", "Response: $body") 
        
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val adapter: JsonAdapter<Map<String, Any>> = moshi.adapter(type)
        val map = adapter.fromJson(body)
        
        val value = map?.get("value") as? List<Map<String, Any>> ?: emptyList()
        
        value.map { raw ->
            val id = raw["id"] as? String ?: ""
            val name = raw["name"] as? String ?: ""
            val sizeStr = raw["size"] // could be Double or Long in JSON
            val size = (sizeStr as? Number)?.toLong() ?: 0L
            
            // Check for folder
            val folderMap = raw["folder"] as? Map<*, *>
            val isFolder = folderMap != null
            val mimeType = if (isFolder) "folder" else (raw["file"] as? Map<*, *>)?.get("mimeType") as? String
            
            val modTimeStr = raw["lastModifiedDateTime"] as? String
            // ISO 8601
            val modifiedTime = try {
                 SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(modTimeStr ?: "")?.time ?: 0L
            } catch (e: Exception) { 0L }
            
            // Thumbnails
            // "thumbnails": [ { "medium": { "url": "..." } } ]
            val thumbs = raw["thumbnails"] as? List<Map<String, Any>>
            val medium = (thumbs?.firstOrNull()?.get("medium") as? Map<String, Any>)
            val thumbUrl = medium?.get("url") as? String
            
            OneDriveFile(id, name, mimeType, size, modifiedTime, thumbUrl)
        }
    }
}

// Needed to run coroutine in onClick
