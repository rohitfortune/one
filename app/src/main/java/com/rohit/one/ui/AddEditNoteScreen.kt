@file:Suppress("DEPRECATION")
package com.rohit.one.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rohit.one.data.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AddEditNoteScreen(
    note: Note?,
    onSave: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf(note?.title ?: "") }
    var content by rememberSaveable { mutableStateOf(note?.content ?: "") }
    // Inline attachments list managed in screen state; persisted on save/update
    var attachments by rememberSaveable { mutableStateOf(note?.attachments ?: emptyList()) }

    // File picker launcher: ACTION_OPEN_DOCUMENT
    val pickDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            var displayName: String? = null
            var mimeType: String? = null
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && c.moveToFirst()) displayName = c.getString(nameIdx)
                }
                mimeType = context.contentResolver.getType(uri)
            } catch (_: Exception) {}
            val att = Note.Attachment(uri = uri.toString(), displayName = displayName, mimeType = mimeType)
            attachments = attachments + att
        }
    }

    fun openAttachment(att: Note.Attachment) {
        try {
            val uri = Uri.parse(att.uri)
            val type = att.mimeType ?: try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Grant URI permission explicitly to the resolved activity if possible
            try {
                val res = context.packageManager.resolveActivity(intent, 0)
                if (res != null && res.activityInfo?.packageName != null) {
                    context.grantUriPermission(res.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: Exception) {}
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app available to open this attachment", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(context, "Missing permission for this file. Re-attach to regain access.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open attachment: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    suspend fun decodeThumbnail(uri: Uri, targetSize: Int = 96): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // First decode bounds
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
            }
            val (w, h) = try {
                val opts2 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) }
                opts2.outWidth to opts2.outHeight
            } catch (_: Exception) { 0 to 0 }
            val sample = if (w > 0 && h > 0) {
                var s = 1
                var cw = w
                var ch = h
                while (cw / 2 >= targetSize && ch / 2 >= targetSize) { s *= 2; cw /= 2; ch /= 2 }
                s
            } else 1
            val opts3 = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return@withContext context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts3) }
        } catch (_: Exception) { null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("Content") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))

        // Attachments section
        Text(text = "Attachments", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        attachments.forEachIndexed { index, att ->
            val isImage = (att.mimeType?.startsWith("image/") == true)
            val uri = remember(att.uri) { Uri.parse(att.uri) }
            val bmp by produceState<Bitmap?>(initialValue = null, key1 = att.uri) {
                if (isImage) value = decodeThumbnail(uri)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { openAttachment(att) }
                ) {
                    if (isImage) {
                        val thumbModifier = Modifier.size(40.dp)
                        when {
                            bmp == null -> {
                                // Placeholder while loading (clickable via parent Row)
                                Box(thumbModifier, contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                            else -> {
                                Image(
                                    bitmap = bmp!!.asImageBitmap(),
                                    contentDescription = att.displayName ?: "Image attachment thumbnail",
                                    modifier = thumbModifier
                                )
                            }
                        }
                    } else {
                        // MIME-specific icon selection (clickable via parent Row)
                        val icon = when {
                            att.mimeType?.contains("pdf", ignoreCase = true) == true -> Icons.Filled.Description
                            att.mimeType?.startsWith("text/") == true -> Icons.Filled.Description
                            att.mimeType?.startsWith("video/") == true -> Icons.Filled.Description
                            att.mimeType?.startsWith("audio/") == true -> Icons.Filled.Description
                            else -> Icons.Filled.Description
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "Attachment",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = att.displayName ?: att.uri,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Thumbnail/icon + text area is now the single tap target
                    TextButton(onClick = {
                        attachments = attachments.toMutableList().also { it.removeAt(index) }
                    }) { Text("Remove") }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { pickDocumentLauncher.launch(arrayOf("*/*")) }) { Text("Attach file") }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val toSave = Note(id = note?.id ?: 0, title = title, content = content, attachments = attachments)
                onSave(toSave)
            }) { Text("Save") }

            if (note != null) {
                Button(onClick = { onDelete(note) }) { Text("Delete") }
            }

            TextButton(onClick = onNavigateUp) { Text("Cancel") }
        }
    }
}


