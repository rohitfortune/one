package com.rohit.one.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FloatingActionButton
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
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
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // State for Upload Menu
    var showUploadMenu by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Helper to upload a uri
    fun uploadUri(uri: android.net.Uri) {
         val contentResolver = context.contentResolver
         val type = contentResolver.getType(uri) ?: "application/octet-stream"
         val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name ?: "upload_${System.currentTimeMillis()}"
            
         kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
             try {
                 val inputStream = contentResolver.openInputStream(uri)
                 val tempFile = File(context.cacheDir, name)
                 val outputStream = FileOutputStream(tempFile)
                 inputStream?.copyTo(outputStream)
                 inputStream?.close()
                 outputStream.close()
                 
                 uploadFileToOneDrive(accessToken!!, folderStack.last().id, tempFile, type)
                 withContext(Dispatchers.Main) {
                     Toast.makeText(context, "Upload complete", Toast.LENGTH_SHORT).show()
                     refreshTrigger++
                 }
             } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                 }
             }
         }
    }

    // Launchers
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadUri(it) }
    }
    
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadUri(it) }
    }
    
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempCameraUri != null) {
            uploadUri(tempCameraUri!!)
        }
    }

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
    LaunchedEffect(accessToken, currentFolder, sortOrder, refreshTrigger) {
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
                    Box {
                        IconButton(onClick = { showUploadMenu = true }) {
                            Icon(imageVector = Icons.Rounded.Add, contentDescription = "Upload File")
                        }
                        DropdownMenu(expanded = showUploadMenu, onDismissRequest = { showUploadMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Gallery") },
                                onClick = { 
                                    showUploadMenu = false
                                    galleryLauncher.launch("image/*") 
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Files") },
                                onClick = { 
                                    showUploadMenu = false
                                    fileLauncher.launch("*/*") 
                                }
                            )
                             DropdownMenuItem(
                                text = { Text("Camera") },
                                onClick = { 
                                    showUploadMenu = false
                                    val tempFile = File.createTempFile("camera_", ".jpg", context.cacheDir)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
                                    tempCameraUri = uri
                                    cameraLauncher.launch(uri)
                                }
                            )
                        }
                    }
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
                     IconButton(onClick = {
                        kotlinx.coroutines.MainScope().launch(Dispatchers.Main) {
                            if (MsGraphAuthProvider.signOut()) {
                                isSignedIn = false
                                accessToken = null
                                accountName = null
                            }
                        }
                    }) {
                        Text("LO", style = MaterialTheme.typography.labelSmall)
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
                                    openOneDriveFile(context, accessToken!!, file)
                                }
                            },
                             onDownload = { downloadOneDriveFile(context, accessToken!!, file) },
                             onShare = { shareOneDriveFile(context, accessToken!!, file) }
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
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
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
                Box {
                    if (!file.isDirectory) {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                onClick = { showMenu = false; onDownload() }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                onClick = { showMenu = false; onShare() }
                            )
                        }
                    }
                }
            }
        } else {
             Box {
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
                if (!file.isDirectory) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                onClick = { showMenu = false; onDownload() }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                onClick = { showMenu = false; onShare() }
                            )
                        }
                    }
                }
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



private fun openOneDriveFile(context: Context, accessToken: String, file: OneDriveFile) {
    Toast.makeText(context, "Opening...", Toast.LENGTH_SHORT).show()
    kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
        val cachedFile = downloadOneDriveFileToCache(context, accessToken, file)
        withContext(Dispatchers.Main) {
            if (cachedFile != null) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cachedFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, file.mimeType ?: "application/octet-stream")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Failed to download", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun downloadOneDriveFile(context: Context, accessToken: String, file: OneDriveFile) {
     Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
     kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
         try {
             val client = OkHttpClient()
             val request = Request.Builder().url("https://graph.microsoft.com/v1.0/me/drive/items/${file.id}/content").addHeader("Authorization", "Bearer $accessToken").build()
             val response = client.newCall(request).execute()
             if (response.isSuccessful) {
                 val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                 val destFile = java.io.File(downloadsDir, file.name)
                 val fos = java.io.FileOutputStream(destFile)
                 response.body?.byteStream()?.copyTo(fos)
                 fos.close()
                 withContext(Dispatchers.Main) { Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
             } else {
                 withContext(Dispatchers.Main) { Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show() }
             }
         } catch (e: Exception) {
             withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
         }
     }
}

private fun shareOneDriveFile(context: Context, accessToken: String, file: OneDriveFile) {
    Toast.makeText(context, "Preparing share...", Toast.LENGTH_SHORT).show()
    kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
        val cachedFile = downloadOneDriveFileToCache(context, accessToken, file)
        withContext(Dispatchers.Main) {
            if (cachedFile != null) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cachedFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    setType(file.mimeType ?: "application/octet-stream")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share file"))
            } else {
                Toast.makeText(context, "Failed to share", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun downloadOneDriveFileToCache(context: Context, accessToken: String, file: OneDriveFile): java.io.File? {
     try {
         val client = OkHttpClient()
         val request = Request.Builder().url("https://graph.microsoft.com/v1.0/me/drive/items/${file.id}/content").addHeader("Authorization", "Bearer $accessToken").build()
         val response = client.newCall(request).execute()
         if (response.isSuccessful) {
             val destFile = java.io.File(context.cacheDir, file.name)
             val fos = java.io.FileOutputStream(destFile)
             response.body?.byteStream()?.copyTo(fos)
             fos.close()
             return destFile
         }
     } catch (e: Exception) {
         e.printStackTrace()
     }
     return null
}

private suspend fun uploadFileToOneDrive(accessToken: String, folderId: String, file: java.io.File, mimeType: String) = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    
    // For small files (< 4MB), use PUT /me/drive/items/{parent-id}:/{filename}:/content
    val endpoint = if (folderId == "root") {
        "https://graph.microsoft.com/v1.0/me/drive/root:/${file.name}:/content"
    } else {
        "https://graph.microsoft.com/v1.0/me/drive/items/$folderId:/${file.name}:/content"
    }
    
    val requestBody = file.asRequestBody(mimeType.toMediaTypeOrNull())
    
    val request = Request.Builder()
        .url(endpoint)
        .addHeader("Authorization", "Bearer $accessToken")
        .put(requestBody)
        .build()
        
    val response = client.newCall(request).execute()
    if (!response.isSuccessful) {
        val body = response.body?.string()
        throw Exception("Upload failed: ${response.code} $body")
    }
}
