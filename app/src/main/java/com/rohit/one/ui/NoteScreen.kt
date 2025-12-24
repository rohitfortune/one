package com.rohit.one.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.net.toUri
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.FileProvider
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rohit.one.data.Note
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.unit.Dp

// --- New structured model for the editor ---

private enum class InlineStyle { Bold, Italic, Underline }

// Represents a formatting span
private data class StyleSpan(val start: Int, val end: Int, val style: InlineStyle)

private sealed class NoteBlock {
    data class Paragraph(
        var text: String,
        val spans: List<StyleSpan> = emptyList()
    ) : NoteBlock()
    data class ChecklistItem(var text: String, var checked: Boolean) : NoteBlock()
    data class BulletItem(var text: String) : NoteBlock()
    data class NumberedItem(var index: Int, var text: String) : NoteBlock()
}

private data class NoteEditorState(
    val title: String,
    val blocks: List<NoteBlock>,
    val strokes: List<Note.Path> = emptyList() // Add strokes to track drawing
)

// Very small history stack for undo/redo on the new model
private class BlockHistory(private val limit: Int = 20) {
    private val stack = mutableListOf<NoteEditorState>()
    private var index = -1

    val currentIndex: Int
        get() = index
    val stackLastIndex: Int
        get() = stack.lastIndex


    fun push(state: NoteEditorState) {
        // If not at the end, remove all states after the current index
        if (index < stack.lastIndex) {
            for (i in stack.lastIndex downTo index + 1) {
                stack.removeAt(i)
            }
        }
        if (stack.size >= limit) {
            stack.removeAt(0)
            if (index > 0) index--
        }
        stack.add(state.copy(blocks = state.blocks.map { it.copyBlock() }))
        index = stack.lastIndex
        Log.d("BlockHistory", "push: after size=${stack.size} index=$index stackLastIndex=${stack.lastIndex} blocks=${state.blocks.size}")
    }

    fun undo(): NoteEditorState? {
        Log.d("BlockHistory", "undo: before index=$index stackLastIndex=${stack.lastIndex}")
        if (index > 0) {
            index--
            val result = stack.getOrNull(index)?.deepCopy()
            Log.d("BlockHistory", "undo: after index=$index stackLastIndex=${stack.lastIndex} result=${result != null}")
            return result
        }
        Log.d("BlockHistory", "undo: at beginning of stack")
        return stack.getOrNull(index)?.deepCopy()
    }

    fun redo(): NoteEditorState? {
        Log.d("BlockHistory", "redo: before index=$index stackLastIndex=${stack.lastIndex}")
        if (index < stack.lastIndex) {
            index++
            val result = stack.getOrNull(index)?.deepCopy()
            Log.d("BlockHistory", "redo: after index=$index stackLastIndex=${stackLastIndex} result=${result != null}")
            return result
        }
        Log.d("BlockHistory", "redo: at end of stack")
        return null
    }

    fun canUndo() = index > 0
    fun canRedo() = index < stack.lastIndex
}

// Helper copy functions
private fun NoteBlock.copyBlock(): NoteBlock = when (this) {
    is NoteBlock.Paragraph -> copy()
    is NoteBlock.ChecklistItem -> copy()
    is NoteBlock.BulletItem -> copy()
    is NoteBlock.NumberedItem -> copy()
}

private fun NoteEditorState.deepCopy(): NoteEditorState =
    copy(blocks = blocks.map { it.copyBlock() })

// --- Legacy edit mode enum kept so toolbar icons stay the same, but drawing is removed in this refactor ---

private enum class EditMode {
    TEXT, DRAW
}

// Simple drawing state for freehand overlay
private data class DrawingState(
    val isDrawing: Boolean = false,
    val strokes: List<Note.Path> = emptyList(),
    val currentStroke: Note.Path? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    note: Note?,
    onSave: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Track selection per paragraph block (move this above all usages)
    val blockSelection = remember { mutableStateMapOf<Int, TextRange>() }

    // Parse existing markdown into simple blocks: lines starting with "[ ] " / "[x] " become checklist items.
    val initialText = note?.content ?: ""
    val initialBlocks = remember(initialText) { parseMarkdownToBlocks(initialText) }

    var editorState by remember {
        mutableStateOf(
            NoteEditorState(
                title = note?.title ?: "",
                blocks = initialBlocks,
                strokes = note?.paths ?: emptyList() // initialize with note's paths
            )
        )
    }

    // Attachments state (new) - keep track of attachments in the editor
    var attachments by remember { mutableStateOf(note?.attachments ?: emptyList()) }

    // Directory for attachments in internal storage
    val attachmentsDir = java.io.File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

    // Helper to safely delete an attachment file that we copied into internal storage
    fun deleteInternalAttachment(att: Note.Attachment) {
        try {
            val f = java.io.File(att.uri)
            if (f.exists() && f.absolutePath.startsWith(attachmentsDir.absolutePath)) {
                val ok = f.delete()
                Log.d("NoteScreen", "Deleted attachment file ${f.absolutePath}: $ok")
            } else {
                Log.d("NoteScreen", "Skipping delete for ${att.uri}, not inside attachmentsDir")
            }
        } catch (e: Exception) {
            Log.w("NoteScreen", "Failed to delete attachment file ${att.uri}: ${e.message}")
        }
    }

    // Activity Result launcher for picking multiple documents
    val coroutineScope = rememberCoroutineScope()
    var copyingInProgress by remember { mutableStateOf(false) }

    val pickLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val contentResolver = context.contentResolver
        // Use the attachmentsDir created above in the outer scope
        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()

        copyingInProgress = true
        coroutineScope.launch {
            val newAttachments = mutableListOf<Note.Attachment>()
            try {
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        try {
                            // Try to query display name
                            var displayName: String? = null
                            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) displayName = cursor.getString(0)
                            }

                            val mime = contentResolver.getType(uri)

                            // Copy the content to internal storage
                            val safeName = (displayName ?: uri.lastPathSegment ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                            val timestamp = System.currentTimeMillis()
                            val targetFile = java.io.File(attachmentsDir, "${timestamp}_$safeName")

                            contentResolver.openInputStream(uri)?.use { input ->
                                targetFile.outputStream().use { out ->
                                    input.copyTo(out)
                                }
                            } ?: throw java.io.IOException("Unable to open input stream for $uri")

                            val attach = Note.Attachment(uri = targetFile.absolutePath, displayName = displayName, mimeType = mime)
                            // Avoid duplicates by internal path
                            if (attachments.none { it.uri == attach.uri } && newAttachments.none { it.uri == attach.uri }) {
                                newAttachments.add(attach)
                            }
                        } catch (e: Exception) {
                            Log.w("NoteScreen", "Failed to add attachment for $uri: ${e.message}")
                        }
                    }
                }
            } finally {
                // Merge new attachments on main thread
                if (newAttachments.isNotEmpty()) attachments = attachments + newAttachments
                copyingInProgress = false
            }
        }
    }

    var focusedBlockIndex by remember { mutableIntStateOf(-1) }
    // We'll derive block positions from the LazyListState.visibleItemsInfo — it's more reliable
    val listState = rememberLazyListState()
    val blockHeights = remember { mutableStateMapOf<Int, Float>() }
    // Local view/density removed — we rely on LazyList offsets and per-item measurements

    // Smooth block top changes to avoid sudden jumps when lazy list recycles/scrolls.
    val smoothedBlockTops = remember { mutableStateMapOf<Int, Float>() }

    // Launch a coroutine that watches for visible items and animates their offsets into smoothedBlockTops.
    LaunchedEffect(listState, attachments, editorState.blocks) {
        while (true) {
            try {
                val visible = listState.layoutInfo.visibleItemsInfo
                Log.d("NoteScreen", "visibleItems: count=${visible.size} firstIndex=${visible.firstOrNull()?.index}")
                val visibleMap = mutableMapOf<Int, Pair<Float, Float>>()
                val blockStartIndex = if (attachments.isNotEmpty()) 1 else 0
                val totalBlocks = editorState.blocks.size
                for (info in visible) {
                    val blockIndex = info.index - blockStartIndex
                    if (blockIndex in 0 until totalBlocks) {
                        // info.offset is the item's top relative to the LazyColumn viewport.
                        // Our Canvas overlays the same area, so using info.offset directly
                        // maps correctly into the Canvas local Y coordinates.
                        visibleMap[blockIndex] = info.offset.toFloat() to info.size.toFloat()
                    }
                }
                for ((blockIdx, pair) in visibleMap) {
                    val (targetOffset, sizePx) = pair
                    blockHeights[blockIdx] = sizePx
                    // Write immediate top in local (container) coordinates so drawing uses same space.
                    smoothedBlockTops[blockIdx] = targetOffset
                    Log.d("NoteScreen", "smoothed top updated: idx=$blockIdx top=$targetOffset")
                }
             }
             catch (_: Exception) {
                 // ignore; layout info may not be ready yet
             }
             delay(40)
         }
     }

    val history = remember { BlockHistory().apply { push(editorState) } }

    var drawingState by remember(note?.paths) {
        mutableStateOf(
            DrawingState(
                isDrawing = false,
                strokes = note?.paths ?: emptyList(),
                currentStroke = null
            )
        )
    }

    fun saveNote() {
        val markdown = blocksToMarkdown(editorState.blocks)
        val noteToSave = Note(
            id = note?.id ?: 0,
            title = editorState.title,
            content = markdown,
            attachments = attachments,
            paths = drawingState.strokes,
            lastModified = System.currentTimeMillis()
        )
        onSave(noteToSave)
        Toast.makeText(context, "Note Saved", Toast.LENGTH_SHORT).show()
    }

    var editMode by remember { mutableStateOf(EditMode.TEXT) }
    var isEraserActive by remember { mutableStateOf(false) }

    // Added historyVersion state variable to force recomposition after undo/redo
    var historyVersion by remember { mutableIntStateOf(0) }

    fun pushHistory() {
        history.push(editorState.copy(strokes = drawingState.strokes))
        Log.d("BlockHistory", "pushHistory: index=${history.currentIndex} stackLastIndex=${history.stackLastIndex} canUndo=${history.canUndo()} canRedo=${history.canRedo()}")
        historyVersion++ // Force recomposition so undo button updates
    }

    var strokeColor by remember { mutableStateOf(Color.Black) }
    var strokeWidthDp by remember { mutableStateOf(4f) } // float dp
    var showStrokeSheet by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.White,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.White,
                        titleContentColor = Color.Black,
                        actionIconContentColor = Color.Black,
                        navigationIconContentColor = Color.Black
                    ),
                    title = {
                        TextField(
                            value = editorState.title,
                            onValueChange = {
                                if (editMode == EditMode.TEXT) {
                                    editorState = editorState.copy(title = it)
                                }
                            },
                            placeholder = { Text("Title", color = Color.Gray) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = Color.Black,
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                            ),
                            textStyle = MaterialTheme.typography.titleLarge,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        var menuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (note != null) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        menuExpanded = false
                                        // Delete copied internal files for attachments (if any) before deleting the note
                                        // 'note' is non-null in this branch; prefer its attachments when editor attachments are empty
                                        val toDelete = attachments.ifEmpty { note.attachments }
                                        toDelete.forEach { att -> deleteInternalAttachment(att) }
                                        onDelete(note)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Save") },
                                onClick = {
                                    menuExpanded = false
                                    saveNote()
                                }
                            )
                        }
                    }
                )

                // Secondary toolbar – reuse icons for text mode / undo / redo, plus list and checklist tools.
                key(historyVersion) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pen icon is the single toggle between TEXT and DRAW modes
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (isEraserActive) {
                                            isEraserActive = false
                                            editMode = EditMode.DRAW
                                            drawingState = drawingState.copy(isDrawing = true, currentStroke = null)
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                        } else {
                                            val newMode = if (editMode == EditMode.TEXT) EditMode.DRAW else EditMode.TEXT
                                            editMode = newMode
                                            drawingState = drawingState.copy(
                                                isDrawing = (newMode == EditMode.DRAW),
                                                currentStroke = null
                                            )
                                            if (newMode == EditMode.DRAW) {
                                                focusManager.clearFocus(force = true)
                                                keyboardController?.hide()
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        showStrokeSheet = true
                                    }
                                )
                        ) {
                            Icon(
                                Icons.Filled.Colorize,
                                contentDescription = "Toggle Draw Mode",
                                tint = if (editMode == EditMode.DRAW && !isEraserActive) Color.Black else Color.Gray
                            )
                        }

                        // Show the modal bottom sheet when requested
                        if (showStrokeSheet) {
                            ModalBottomSheet(onDismissRequest = { showStrokeSheet = false }) {
                                StrokeBottomSheet(
                                    initialColor = strokeColor,
                                    initialWidthDp = strokeWidthDp,
                                    onColorSelected = { c ->
                                        strokeColor = c
                                    },
                                    onWidthSelected = { w ->
                                        strokeWidthDp = w
                                    },
                                    onDismissRequest = { showStrokeSheet = false }
                                )
                            }
                        }

                        // Erase button: toggles eraser mode
                        IconButton(
                            onClick = {
                                isEraserActive = !isEraserActive
                                if (isEraserActive) {
                                    focusManager.clearFocus(force = true)
                                    editMode = EditMode.TEXT // Always leave draw mode when eraser is active
                                    drawingState = drawingState.copy(isDrawing = false, currentStroke = null)
                                }
                            },
                            enabled = true
                        ) {
                            Icon(
                                Icons.Filled.RemoveCircleOutline,
                                contentDescription = "Eraser Mode",
                                tint = if (isEraserActive) Color.Black else Color.LightGray
                            )
                        }

                        // Attach file button (new)
                        IconButton(onClick = { pickLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "Attach File", tint = Color.Black)
                        }

                        IconButton(onClick = {
                            // Always use BlockHistory for undo, update both editorState and drawingState
                            val result = history.undo()
                            Log.d("BlockHistory", "undo: index=${history.currentIndex} stackLastIndex=${history.stackLastIndex}")
                            result?.let {
                                editorState = it
                                drawingState = drawingState.copy(strokes = it.strokes)
                            }
                            Log.d("BlockHistory", "after undo: canUndo=${history.canUndo()} canRedo=${history.canRedo()} index=${history.currentIndex} stackLastIndex=${history.stackLastIndex}")
                            historyVersion++ // Force recomposition
                        }, enabled = history.canUndo()) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo",
                                tint = if (history.canUndo()) Color.Black else Color.Gray
                            )
                        }
                        IconButton(onClick = {
                            val result = history.redo()
                            result?.let {
                                editorState = it
                                drawingState = drawingState.copy(strokes = it.strokes)
                            }
                            Log.d("BlockHistory", "after redo: canUndo=${history.canUndo()} canRedo=${history.canRedo()} index=${history.currentIndex} stackLastIndex=${history.stackLastIndex}")
                            historyVersion++ // Force recomposition
                        }, enabled = history.canRedo().also { Log.d("BlockHistory", "redo button enabled: canRedo=${history.canRedo()} index=${history.currentIndex} stackLastIndex=${history.stackLastIndex}") }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                contentDescription = "Redo",
                                tint = if (history.canRedo()) Color.Black else Color.Gray
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.LightGray)
            }
        },
        bottomBar = {
            BottomFormattingBar(
                onChecklist = {
                    if (editMode == EditMode.TEXT) {
                        val blocks = editorState.blocks.toMutableList()
                        val insertPos = if (focusedBlockIndex in blocks.indices) focusedBlockIndex + 1 else blocks.size
                        blocks.add(insertPos, NoteBlock.ChecklistItem(text = "", checked = false))
                        editorState = editorState.copy(blocks = blocks)
                        pushHistory()
                    }
                },
                onBulletList = {
                    if (editMode == EditMode.TEXT) {
                        val blocks = editorState.blocks.toMutableList()
                        val insertPos = if (focusedBlockIndex in blocks.indices) focusedBlockIndex + 1 else blocks.size
                        blocks.add(insertPos, NoteBlock.BulletItem(text = ""))
                        editorState = editorState.copy(blocks = blocks)
                        pushHistory()
                    }
                },
                onNumberedList = {
                    if (editMode == EditMode.TEXT) {
                        val blocks = editorState.blocks.toMutableList()
                        val insertPos = if (focusedBlockIndex in blocks.indices) focusedBlockIndex + 1 else blocks.size
                        blocks.add(insertPos, NoteBlock.NumberedItem(index = 1, text = ""))
                        editorState = editorState.copy(blocks = blocks)
                        pushHistory()
                    }
                },
                onBold = {
                    if (editMode == EditMode.TEXT && focusedBlockIndex in editorState.blocks.indices) {
                        val blocks = editorState.blocks.toMutableList()
                        val block = blocks[focusedBlockIndex]
                        if (block is NoteBlock.Paragraph) {
                            val sel = blockSelection[focusedBlockIndex] ?: TextRange(block.text.length)
                            if (!sel.collapsed) {
                                val newSpans = toggleStyleSpan(block.spans, sel, InlineStyle.Bold)
                                blocks[focusedBlockIndex] = block.copy(spans = newSpans)
                                editorState = editorState.copy(blocks = blocks)
                                pushHistory()
                                blockSelection[focusedBlockIndex] = sel
                            }
                        }
                    }
                },
                onItalic = {
                    if (editMode == EditMode.TEXT && focusedBlockIndex in editorState.blocks.indices) {
                        val blocks = editorState.blocks.toMutableList()
                        val block = blocks[focusedBlockIndex]
                        if (block is NoteBlock.Paragraph) {
                            val sel = blockSelection[focusedBlockIndex] ?: TextRange(block.text.length)
                            if (!sel.collapsed) {
                                val newSpans = toggleStyleSpan(block.spans, sel, InlineStyle.Italic)
                                blocks[focusedBlockIndex] = block.copy(spans = newSpans)
                                editorState = editorState.copy(blocks = blocks)
                                pushHistory()
                                blockSelection[focusedBlockIndex] = sel
                            }
                        }
                    }
                },
                onUnderline = {
                    if (editMode == EditMode.TEXT && focusedBlockIndex in editorState.blocks.indices) {
                        val blocks = editorState.blocks.toMutableList()
                        val block = blocks[focusedBlockIndex]
                        if (block is NoteBlock.Paragraph) {
                            val sel = blockSelection[focusedBlockIndex] ?: TextRange(block.text.length)
                            if (!sel.collapsed) {
                                val newSpans = toggleStyleSpan(block.spans, sel, InlineStyle.Underline)
                                blocks[focusedBlockIndex] = block.copy(spans = newSpans)
                                editorState = editorState.copy(blocks = blocks)
                                pushHistory()
                                blockSelection[focusedBlockIndex] = sel
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.White)
        ) {
            // Main content: list of blocks
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Attachments header (new)
                if (attachments.isNotEmpty()) {
                    item {
                        AttachmentList(
                            attachments = attachments,
                            onRemove = { toRemove: Note.Attachment ->
                                // remove the physical file (if internal) and then update state
                                deleteInternalAttachment(toRemove)
                                attachments = attachments.filter { it.uri != toRemove.uri }
                            },
                            attachmentsDir = attachmentsDir
                        )
                    }
                }

                itemsIndexed(editorState.blocks, key = { index, _ -> index }) { index, block ->
                    val isLastBlock = index == editorState.blocks.lastIndex
                    val thisShouldFocus = index == focusedBlockIndex

                    // Measure each block's height persistently so offscreen anchors are exact.
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            // store height in pixels
                            blockHeights[index] = coords.size.height.toFloat()
                        }
                    ) {
                        when (block) {
                            is NoteBlock.Paragraph -> ParagraphBlock(
                                text = block.text,
                                spans = block.spans,
                                editMode = editMode,
                                selection = blockSelection[index] ?: TextRange(block.text.length),
                                onTextChange = { newText, newSpans, newSelection ->
                                    if (editMode == EditMode.TEXT) {
                                        val blocks = editorState.blocks.toMutableList()
                                        val current = blocks.getOrNull(index)
                                        if (current is NoteBlock.Paragraph) {
                                            blocks[index] = current.copy(text = newText, spans = newSpans)
                                            editorState = editorState.copy(blocks = blocks)
                                            blockSelection[index] = newSelection
                                            pushHistory() // Push paragraph changes to history for undo
                                        }
                                    }
                                },
                                onFocused = { focusedBlockIndex = index }
                            )

                            is NoteBlock.ChecklistItem -> ChecklistBlock(
                                item = block,
                                editMode = editMode,
                                autoFocus = thisShouldFocus || (isLastBlock && block.text.isEmpty()),
                                onCheckedChange = { checked: Boolean ->
                                    val blocks = editorState.blocks.toMutableList()
                                    val current = blocks.getOrNull(index)
                                    if (current is NoteBlock.ChecklistItem) {
                                        blocks[index] = current.copy(checked = checked)
                                        editorState = editorState.copy(blocks = blocks)
                                        pushHistory()
                                    }
                                },
                                onTextChange = { newText: String ->
                                    val blocks = editorState.blocks.toMutableList()
                                    val current = blocks.getOrNull(index)
                                    if (current is NoteBlock.ChecklistItem) {
                                        // Mirror numbered-list guard: only mutate when this block's text
                                        // truly changes, and ignore stale empty updates into non-empty text.
                                        if (current.text != newText) {
                                            Log.d(
                                                "NoteScreen",
                                                "Checklist onTextChange index=$index guarded old='${current.text}' new='$newText'"
                                            )
                                            if (!(newText.isEmpty() && current.text.isNotEmpty())) {
                                                blocks[index] = current.copy(text = newText)
                                            }
                                            editorState = editorState.copy(blocks = blocks)
                                            pushHistory()
                                        }
                                    }
                                },
                                onEnter = { wasEmpty: Boolean ->
                                    val blocks = editorState.blocks.toMutableList()
                                    if (blocks.getOrNull(index) !is NoteBlock.ChecklistItem) return@ChecklistBlock
                                    if (wasEmpty) {
                                        blocks[index] = NoteBlock.Paragraph("")
                                        focusedBlockIndex = index
                                    } else {
                                        val insertPos = index + 1
                                        blocks.add(insertPos, NoteBlock.ChecklistItem(text = "", checked = false))
                                        focusedBlockIndex = insertPos
                                    }
                                    editorState = editorState.copy(blocks = blocks)
                                    pushHistory()
                                },
                                onBackspaceAtStart = {
                                    val blocks = editorState.blocks.toMutableList()
                                    val current = blocks.getOrNull(index) as? NoteBlock.ChecklistItem
                                        ?: return@ChecklistBlock

                                    Log.d(
                                        "NoteScreen",
                                        "Checklist onBackspaceAtStart: index=$index text='${current.text}' sizeBefore=${blocks.size}"
                                    )

                                    // New behavior: backspace at start only deletes this checklist item
                                    // (when there is at least one other block) and never touches the one above.
                                    if (blocks.size > 1 && index in blocks.indices) {
                                        blocks.removeAt(index)
                                        editorState = editorState.copy(blocks = blocks)
                                        pushHistory()
                                    }
                                },
                                onFocused = { focusedBlockIndex = index }
                            )

                            is NoteBlock.BulletItem -> BulletBlock(
                                item = block,
                                editMode = editMode,
                                autoFocus = thisShouldFocus || (isLastBlock && block.text.isEmpty()),
                                onTextChange = { newText: String ->
                                    val blocks = editorState.blocks.toMutableList()
                                    val current = blocks.getOrNull(index)
                                    if (current is NoteBlock.BulletItem) {
                                        // Same guard pattern as numbered/checklist: ignore stale empty
                                        // updates into non-empty bullet text and only push real changes.
                                        if (current.text != newText) {
                                            Log.d(
                                                "NoteScreen",
                                                "Bullet onTextChange index=$index guarded old='${current.text}' new='$newText'"
                                            )
                                            if (!(newText.isEmpty() && current.text.isNotEmpty())) {
                                                blocks[index] = current.copy(text = newText)
                                            }
                                            editorState = editorState.copy(blocks = blocks)
                                            pushHistory()
                                        }
                                    }
                                },
                                onEnter = { wasEmpty: Boolean ->
                                    val blocks = editorState.blocks.toMutableList()
                                    if (blocks.getOrNull(index) !is NoteBlock.BulletItem) return@BulletBlock
                                    if (wasEmpty) {
                                        blocks[index] = NoteBlock.Paragraph("")
                                        focusedBlockIndex = index
                                    } else {
                                        val insertPos = index + 1
                                        blocks.add(insertPos, NoteBlock.BulletItem(text = ""))
                                        focusedBlockIndex = insertPos
                                    }
                                    editorState = editorState.copy(blocks = blocks)
                                    pushHistory()
                                },
                                onBackspaceAtStart = {
                                    val blocks = editorState.blocks.toMutableList()
                                    if (blocks.getOrNull(index) !is NoteBlock.BulletItem) return@BulletBlock
                                    if (blocks.size > 1 && index in blocks.indices) {
                                        blocks.removeAt(index)
                                        editorState = editorState.copy(blocks = blocks)
                                        pushHistory()
                                    }
                                },
                                onFocused = { focusedBlockIndex = index }
                            )

                            is NoteBlock.NumberedItem -> NumberedBlock(
                                item = block,
                                editMode = editMode,
                                autoFocus = thisShouldFocus || (isLastBlock && block.text.isEmpty()),
                                onTextChange = { newText: String ->
                                    val blocks = editorState.blocks.toMutableList()
                                    val current = blocks.getOrNull(index)
                                    if (current is NoteBlock.NumberedItem) {
                                        // Only update when the text actually changes for THIS block.
                                        // If callbacks arrive after this block was deleted/reindexed, the
                                        // text in the model won't match and we skip the mutation.
                                        if (current.text != newText) {
                                            Log.d(
                                                "NoteScreen",
                                                "Numbered onTextChange index=$index guarded old='${current.text}' new='$newText' lengthDelta=${newText.length - current.text.length}"
                                            )
                                            // Extra guard: ignore a stale empty update if the current block
                                            // already has non-empty text (e.g., the next item after deletion).
                                            if (!(newText.isEmpty() && current.text.isNotEmpty())) {
                                                blocks[index] = current.copy(text = newText)
                                            }
                                        }
                                        // Recalculate indices for each contiguous numbered list separately
                                        var runStart = 0
                                        while (runStart < blocks.size) {
                                            while (runStart < blocks.size && blocks[runStart] !is NoteBlock.NumberedItem) {
                                                runStart++
                                            }
                                            if (runStart >= blocks.size) break
                                            var runEnd = runStart
                                            while (runEnd < blocks.size && blocks[runEnd] is NoteBlock.NumberedItem) {
                                                runEnd++
                                            }
                                          var localIndex = 1
                                          for (i in runStart until runEnd) {
                                              val b = blocks[i] as NoteBlock.NumberedItem
                                              if (b.index != localIndex) {
                                                  blocks[i] = b.copy(index = localIndex)
                                              }
                                              localIndex++
                                          }
                                          runStart = runEnd
                                        }
                                        editorState = editorState.copy(blocks = blocks)
                                        pushHistory()
                                    }
                                },
                                onEnter = { wasEmpty: Boolean ->
                                    val blocks = editorState.blocks.toMutableList()
                                    val current = blocks.getOrNull(index) as? NoteBlock.NumberedItem
                                        ?: return@NumberedBlock
                                    Log.d("NoteScreen", "Numbered onEnter index=$index wasEmpty=$wasEmpty text='${current.text}' sizeBefore=${blocks.size}")
                                    if (wasEmpty) {
                                        blocks[index] = NoteBlock.Paragraph("")
                                        focusedBlockIndex = index
                                    } else {
                                        val insertPos = index + 1
                                        blocks.add(insertPos, NoteBlock.NumberedItem(index = 1, text = ""))
                                        focusedBlockIndex = insertPos
                                    }
                                    // Recalculate indices for each contiguous numbered list separately,
                                    // including the new item if added.
                                    var runStart = 0
                                    while (runStart < blocks.size) {
                                        while (runStart < blocks.size && blocks[runStart] !is NoteBlock.NumberedItem) {
                                            runStart++
                                        }
                                        if (runStart >= blocks.size) break
                                        var runEnd = runStart
                                        while (runEnd < blocks.size && blocks[runEnd] is NoteBlock.NumberedItem) {
                                            runEnd++
                                        }
                                        var localIndex = 1
                                        for (i in runStart until runEnd) {
                                            val b = blocks[i] as NoteBlock.NumberedItem
                                            blocks[i] = b.copy(index = localIndex++)
                                        }
                                        runStart = runEnd
                                    }
                                    Log.d("NoteScreen", "Numbered onEnter after reindex sizeAfter=${blocks.size}")
                                    editorState = editorState.copy(blocks = blocks)
                                    pushHistory()
                                },
                                onBackspaceAtStart = {
                                    val blocks = editorState.blocks.toMutableList()
                                    val current = blocks.getOrNull(index) as? NoteBlock.NumberedItem
                                        ?: return@NumberedBlock
                                    Log.d(
                                        "NoteScreen",
                                        "Numbered onBackspaceAtStart index=$index text='${current.text}' sizeBefore=${blocks.size}"
                                    )
                                    if (blocks.size > 1 && index in blocks.indices) {
                                        blocks.removeAt(index)
                                        // Recalculate indices for each contiguous numbered list after removal.
                                        var runStart = 0
                                        while (runStart < blocks.size) {
                                            while (runStart < blocks.size && blocks[runStart] !is NoteBlock.NumberedItem) {
                                                runStart++
                                            }
                                            if (runStart >= blocks.size) break
                                            var runEnd = runStart
                                            while (runEnd < blocks.size && blocks[runEnd] is NoteBlock.NumberedItem) {
                                                runEnd++
                                            }
                                            var localIndex = 1
                                            for (i in runStart until runEnd) {
                                                val b = blocks[i] as NoteBlock.NumberedItem
                                                blocks[i] = b.copy(index = localIndex++)
                                            }
                                            runStart = runEnd
                                        }
                                        Log.d("NoteScreen", "Numbered onBackspaceAtStart after remove sizeAfter=${blocks.size}")
                                        editorState = editorState.copy(blocks = blocks)
                                        pushHistory()
                                    }
                                },
                                onFocused = { focusedBlockIndex = index }
                            )
                        }
                    }
                }
            }

            // Always render drawing overlay so strokes stay visible in both modes,
            // but only capture pointer events when actually drawing.
            DrawingOverlay(
                  modifier = Modifier.fillMaxSize(),
                  drawingState = drawingState,
                  isInputEnabled = (drawingState.isDrawing && !isEraserActive) || isEraserActive,
                  isEraserActive = isEraserActive,
                  blockTops = smoothedBlockTops,
                  blockHeights = blockHeights,
                  listState = listState,
                  blockStartIndex = if (attachments.isNotEmpty()) 1 else 0,
                  strokeColor = strokeColor,                // named param
                  strokeWidthDp = strokeWidthDp.dp,
                  onUpdateDrawingState = { transform ->
                      val prevStrokes = drawingState.strokes
                      val prevCount = prevStrokes.size
                      drawingState = transform(drawingState)
                      val newStrokes = drawingState.strokes
                      val newCount = newStrokes.size
                      if (newCount > prevCount) pushHistory()
                      if (newCount < prevCount) pushHistory()
                  }
              )
        }
    }

    // Show a global copying progress dialog when copyingInProgress is true
    if (copyingInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Copying…") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Copying files to note...")
                }
            },
            confirmButton = {}
        )
    }
}

// Utility: merge and toggle style spans
private fun toggleStyleSpan(
    spans: List<StyleSpan>,
    selection: TextRange,
    style: InlineStyle
): List<StyleSpan> {
    if (selection.collapsed) return spans
    val (start, end) = selection.start to selection.end
    val newSpans = spans.toMutableList()
    // Remove any existing span of this style in the selection
    val overlapping = newSpans.filter { it.style == style && it.start < end && it.end > start }
    if (overlapping.isNotEmpty()) {
        newSpans.removeAll(overlapping)
        // Optionally, split spans if selection is in the middle
        overlapping.forEach { span ->
            if (span.start < start) newSpans.add(StyleSpan(span.start, start, style))
            if (span.end > end) newSpans.add(StyleSpan(end, span.end, style))
        }
    } else {
        newSpans.add(StyleSpan(start, end, style))
    }
    // Merge adjacent/overlapping spans of the same style
    return newSpans.sortedWith(compareBy({ it.style.ordinal }, { it.start }, { it.end })).fold(mutableListOf()) { acc, span ->
        if (acc.isNotEmpty() && acc.last().style == span.style && acc.last().end >= span.start) {
            val last = acc.removeAt(acc.lastIndex)
            acc.add(StyleSpan(last.start, maxOf(last.end, span.end), span.style))
        } else {
            acc.add(span)
        }
        acc
    }
}

// Visual transformation for styled text
private fun styledVisualTransformation(spans: List<StyleSpan>): VisualTransformation {
    return VisualTransformation { input ->
        val annotated = buildAnnotatedString {
            append(input.text)
            spans.forEach { span ->
                val style = when (span.style) {
                    InlineStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                    InlineStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                    InlineStyle.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
                }
                val safeStart = span.start.coerceIn(0, input.text.length)
                val safeEnd = span.end.coerceIn(0, input.text.length)
                if (safeStart < safeEnd) {
                    addStyle(style, safeStart, safeEnd)
                }
            }
        }
        TransformedText(annotated, OffsetMapping.Identity)
    }
}

@Composable
private fun ParagraphBlock(
    text: String,
    spans: List<StyleSpan>,
    editMode: EditMode,
    selection: TextRange,
    onTextChange: (String, List<StyleSpan>, TextRange) -> Unit,
    onFocused: () -> Unit
) {
    var value by remember(text, spans, selection) { mutableStateOf(TextFieldValue(text, selection)) }

    BasicTextField(
        value = value,
        onValueChange = { newValue ->
            if (editMode != EditMode.TEXT) return@BasicTextField
            val oldText = value.text
            val newText = newValue.text
            val newSelection = newValue.selection
            // Shift spans for insert/delete
            val delta = newText.length - oldText.length
            val newSpans = if (delta == 0) spans else spans.mapNotNull { span ->
                if (span.end <= newSelection.start && span.start <= span.end) {
                    // Before edit
                    span
                } else if (span.start >= newSelection.start - delta) {
                    // After edit
                    val shift = delta
                    val s = (span.start + shift).coerceAtLeast(0)
                    val e = (span.end + shift).coerceAtLeast(s)
                    if (s < e) span.copy(start = s, end = e) else null
                } else {
                    // Overlapping edit: drop
                    null
                }
            }
            if (newText != text || newSpans != spans || newSelection != selection) {
                onTextChange(newText, newSpans, newSelection)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() },
        textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default),
        visualTransformation = styledVisualTransformation(spans),
        decorationBox = { innerTextField ->
            Box {
                if (value.text.isEmpty()) {
                    Text("Note content…", color = Color.Gray)
                }
                innerTextField()
            }
        }
    )
}

@Composable
private fun ChecklistBlock(
    item: NoteBlock.ChecklistItem,
    editMode: EditMode,
    autoFocus: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
    onEnter: (wasEmpty: Boolean) -> Unit,
    onBackspaceAtStart: () -> Unit,
    onFocused: () -> Unit
) {
    var localChecked by remember(item) { mutableStateOf(item.checked) }
    LaunchedEffect(item.checked) {
        if (localChecked != item.checked) {
            localChecked = item.checked
        }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = localChecked,
            onCheckedChange = { newChecked ->
                localChecked = newChecked
                onCheckedChange(newChecked)
            }
        )
        Spacer(modifier = Modifier.width(8.dp))

        var value by remember(item) { mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length))) }

        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                if (editMode != EditMode.TEXT) return@BasicTextField

                val oldText = value.text
                val oldSelection = value.selection
                val newText = newValue.text

                val isEnter = newText.length == oldText.length + 1 && newText.endsWith("\n")
                if (isEnter) {
                    val isEmptyNow = oldText.isBlank()
                    onEnter(isEmptyNow)
                    value = TextFieldValue(oldText, TextRange(oldText.length))
                    onTextChange(oldText)
                    return@BasicTextField
                }

                val lengthDelta = newText.length - oldText.length
                // Treat any single-character deletion when the cursor was at position 0,
                // or clearing the last character to empty, as a backspace-at-start signal.
                val becameEmpty = oldText.isNotEmpty() && newText.isEmpty()
                val classicBackspaceAtStart =
                    lengthDelta == -1 && oldSelection.start == 0 && oldSelection.end == 0
                val emptyBackspaceNoOp =
                    oldText.isEmpty() && newText.isEmpty() &&
                        oldSelection.start == 0 && oldSelection.end == 0 &&
                        newValue.selection.start == 0 && newValue.selection.end == 0
                val backspaceAtStart = classicBackspaceAtStart || (becameEmpty && newValue.selection.start == 0) || emptyBackspaceNoOp

                if (backspaceAtStart) {
                    // If this item is already empty, delegate deletion of the row to the parent.
                    if (newText.isEmpty()) {
                        onBackspaceAtStart()
                        value = TextFieldValue("", TextRange(0))
                        onTextChange("")
                        return@BasicTextField
                    } else {
                        // Non-empty text case: allow parent to decide if it wants to merge/delete,
                        // but keep the local text update.
                        onBackspaceAtStart()
                    }
                }

                value = newValue
                if (newText != item.text) {
                    onTextChange(newText)
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocused() },
            textStyle = TextStyle(
                fontSize = 16.sp,
                color = Color.Black,
                textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None
            ),
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default)
        )
    }
}

@Composable
private fun BulletBlock(
    item: NoteBlock.BulletItem,
    editMode: EditMode,
    autoFocus: Boolean,
    onTextChange: (String) -> Unit,
    onEnter: (wasEmpty: Boolean) -> Unit,
    onBackspaceAtStart: () -> Unit,
    onFocused: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("•", modifier = Modifier.padding(end = 8.dp), fontSize = 18.sp)
        var value by remember(item) { mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length))) }
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                if (editMode != EditMode.TEXT) return@BasicTextField

                val oldText = value.text
                val oldSelection = value.selection
                val newText = newValue.text

                val isEnter = newText.length == oldText.length + 1 && newText.endsWith("\n")
                if (isEnter) {
                    val isEmptyNow = oldText.isBlank()
                    onEnter(isEmptyNow)
                    value = TextFieldValue(oldText, TextRange(oldText.length))
                    onTextChange(oldText)
                    return@BasicTextField
                }

                val lengthDelta = newText.length - oldText.length
                val becameEmpty = oldText.isNotEmpty() && newText.isEmpty()
                val classicBackspaceAtStart =
                    lengthDelta == -1 && oldSelection.start == 0 && oldSelection.end == 0
                val emptyBackspaceNoOp =
                    oldText.isEmpty() && newText.isEmpty() &&
                        oldSelection.start == 0 && oldSelection.end == 0 &&
                        newValue.selection.start == 0 && newValue.selection.end == 0
                val backspaceAtStart = classicBackspaceAtStart || (becameEmpty && newValue.selection.start == 0) || emptyBackspaceNoOp

                if (backspaceAtStart) {
                    if (newText.isEmpty()) {
                        onBackspaceAtStart()
                        value = TextFieldValue("", TextRange(0))
                        onTextChange("")
                        return@BasicTextField
                    }
                }

                value = newValue
                if (newText != item.text) onTextChange(newText)
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocused() },
            textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default)
        )
    }
}

@Composable
private fun NumberedBlock(
    item: NoteBlock.NumberedItem,
    editMode: EditMode,
    autoFocus: Boolean,
    onTextChange: (String) -> Unit,
    onEnter: (wasEmpty: Boolean) -> Unit,
    onBackspaceAtStart: () -> Unit,
    onFocused: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${item.index}.", modifier = Modifier.padding(end = 8.dp), fontSize = 16.sp)
        var value by remember(item) { mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length))) }
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                if (editMode != EditMode.TEXT) return@BasicTextField

                val oldText = value.text
                val newText = newValue.text

                Log.d(
                    "NoteScreen",
                    "NumberedBlock onValueChange oldText='$oldText' newText='$newText' lengthDelta=${newText.length - oldText.length}"
                )

                val isEnter = newText.length == oldText.length + 1 && newText.endsWith("\n")
                if (isEnter) {
                    val isEmptyNow = oldText.isBlank()
                    Log.d("NoteScreen", "NumberedBlock detected ENTER logical='$oldText' isEmptyNow=$isEmptyNow")
                    onEnter(isEmptyNow)
                    value = TextFieldValue(oldText, TextRange(oldText.length))
                    onTextChange(oldText)
                    return@BasicTextField
                }

                val becameBlank = oldText.isNotBlank() && newText.isBlank()
                Log.d("NoteScreen", "NumberedBlock becameBlank=$becameBlank")

                // If line had visible content and now is blank (only whitespace or empty), delete this numbered item.
                if (becameBlank) {
                    Log.d("NoteScreen", "NumberedBlock deleting item via onBackspaceAtStart")
                    onBackspaceAtStart()
                    value = TextFieldValue("", TextRange(0))
                    onTextChange("")
                    return@BasicTextField
                }

                // For numbered items we *do not* forward generic backspace-at-start anymore; just update the text.
                value = newValue
                if (newText != item.text) onTextChange(newText)
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocused() },
            textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default)
        )
    }
}

@Composable
private fun BottomFormattingBar(
    onChecklist: () -> Unit,
    onBulletList: () -> Unit,
    onNumberedList: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Make the bar stay above the system navigation bar and
            // move up together with the on‑screen keyboard (IME).
            .imePadding()
            .navigationBarsPadding()
            .background(Color(0xFFF5F1FF))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onChecklist) {
            Icon(Icons.Filled.Checklist, contentDescription = "Checklist", tint = Color.Black)
        }
        IconButton(onClick = onBulletList) {
            Icon(
                Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = "Bulleted List",
                tint = Color.Black
            )
        }
        IconButton(onClick = onNumberedList) {
            Icon(Icons.Filled.FormatListNumbered, contentDescription = "Numbered List", tint = Color.Black)
        }
        IconButton(onClick = onBold) {
            Icon(Icons.Filled.FormatBold, contentDescription = "Bold", tint = Color.Black)
        }
        IconButton(onClick = onItalic) {
            Icon(Icons.Filled.FormatItalic, contentDescription = "Italic", tint = Color.Black)
        }
        IconButton(onClick = onUnderline) {
            Icon(Icons.Filled.FormatUnderlined, contentDescription = "Underline", tint = Color.Black)
        }
    }
}

@Composable
private fun DrawingOverlay(
    modifier: Modifier = Modifier,
    drawingState: DrawingState,
    isInputEnabled: Boolean,
    isEraserActive: Boolean = false,
    blockTops: Map<Int, Float> = emptyMap(),
    blockHeights: Map<Int, Float> = emptyMap(),
    listState: androidx.compose.foundation.lazy.LazyListState,
    blockStartIndex: Int,
    strokeColor: Color,
    strokeWidthDp: Dp,
    onUpdateDrawingState: ((DrawingState) -> DrawingState) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val contentPaddingTopPx = with(density) { 16.dp.toPx() }
    val itemSpacingPx = with(density) { 8.dp.toPx() }

    // Helper: compute anchor top Y in the Canvas coordinate space for a given anchor block index.
     fun computeAnchorTop(anchorIdx: Int?): Float {
         if (anchorIdx == null) return 0f
         // Prefer visible item offset when available (most accurate for visible items).
         val visibleInfo = listState.layoutInfo.visibleItemsInfo.find { it.index - blockStartIndex == anchorIdx }
         // If we have a smoothed top (from the watcher coroutine) prefer it — it reduces micro-jumps.
         val smoothed = blockTops[anchorIdx]
         if (visibleInfo != null) {
             return smoothed ?: visibleInfo.offset.toFloat()
         }

         // Anchor is off-screen. Compute its top from cumulative per-item heights
         // and the LazyListState scroll offsets so the stroke continues to move
         // naturally with the content (and doesn't stick to the last visible offset).
         val visibleInfos = listState.layoutInfo.visibleItemsInfo
         val avgHeight = if (visibleInfos.isNotEmpty()) visibleInfos.map { it.size }.average().toFloat() else 64f

         val firstVisibleItemIndex = listState.firstVisibleItemIndex
         val firstVisibleBlockIndex = maxOf(0, firstVisibleItemIndex - blockStartIndex)
         val firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset

         // Sum heights of blocks before the anchor (use measured height if available).
         // Also add spacing and the top padding. This makes the off-screen calculation match
         // what LazyColumn reports for visible offsets.
         var cumBeforeAnchor = contentPaddingTopPx
         var cumBeforeFirst = contentPaddingTopPx
         for (i in 0 until anchorIdx) {
             cumBeforeAnchor += (blockHeights[i] ?: avgHeight) + itemSpacingPx
         }
         if (firstVisibleBlockIndex > 0) {
             for (i in 0 until firstVisibleBlockIndex) {
                 cumBeforeFirst += (blockHeights[i] ?: avgHeight) + itemSpacingPx
             }
         }

         val globalScrollY = cumBeforeFirst + firstVisibleScrollOffset
         return (cumBeforeAnchor - globalScrollY)
     }

    val inputModifier = if (isInputEnabled) {
        if (isEraserActive) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onUpdateDrawingState { state -> state }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer == null || !pointer.pressed) break
                        val pos = pointer.position
                        // compute visible blocks once per gesture iteration
                        val visibleBlockSet = listState.layoutInfo.visibleItemsInfo.map { it.index - blockStartIndex }.toSet()
                        // Erase logic: remove points close to the drag path
                        onUpdateDrawingState { state ->
                            val newStrokes = state.strokes.flatMap { stroke ->
                                // For anchored strokes, compute screen Y per point
                                val anchorIdx = stroke.anchorBlockIndex
                                // If anchor is off-screen skip eraser processing for this stroke (leave it intact)
                                if (anchorIdx != null && !visibleBlockSet.contains(anchorIdx)) {
                                    listOf(stroke)
                                } else {
                                    val anchorTop = computeAnchorTop(anchorIdx)
                                    val filtered = stroke.points.filterNot { pt ->
                                        val sx = pt.x
                                        val sy = if (anchorIdx != null) anchorTop + pt.y else pt.y
                                        val dx = sx - pos.x
                                        val dy = sy - pos.y
                                        hypot(dx.toDouble(), dy.toDouble()) < 16.0
                                    }
                                    if (filtered.size >= 2) listOf(stroke.copy(points = filtered)) else emptyList()
                                }
                            }
                            state.copy(strokes = newStrokes, currentStroke = null)
                        }
                        pointer.consume()
                    }
                }
            }
        } else {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var anchorIndex: Int? = null
                    // Prefer blockTops if available in the outer scope
                    val visibleBlockSet = listState.layoutInfo.visibleItemsInfo.map { it.index - blockStartIndex }.toSet()
                    for ((idx, top) in blockTops) {
                        val h = blockHeights[idx] ?: 0f
                        if (down.position.y >= top && down.position.y <= top + h) {
                            anchorIndex = idx
                            break
                        }
                    }
                    if (anchorIndex == null) {
                        // Look up visible items directly from listState and pick the nearest
                        // item to the touch point as the anchor. This gives an exact offset
                        // for initial strokes when the item is visible.
                        val visibleInfos = listState.layoutInfo.visibleItemsInfo
                        val nearestInfo = visibleInfos.minByOrNull { info ->
                            val itemCenter = info.offset + info.size / 2f
                            kotlin.math.abs(down.position.y - itemCenter)
                        }
                        if (nearestInfo != null) {
                            anchorIndex = nearestInfo.index - blockStartIndex
                        } else {
                            // No visible items (rare); leave anchorIndex null and fallback to raw positions
                            anchorIndex = null
                        }
                    }

                    // If the chosen anchorIndex is off-screen (shouldn't happen because we picked nearest visible), clear it
                    if (anchorIndex != null && !visibleBlockSet.contains(anchorIndex)) {
                        anchorIndex = null
                    }

                    // Compute a consistent anchorTop for both visible and off-screen anchors.
                    val computedTop = computeAnchorTop(anchorIndex)
                    val initialY = if (anchorIndex != null) down.position.y - computedTop else down.position.y
                    var currentStroke = Note.Path(
                        points = listOf(Note.Point(down.position.x, initialY)),
                        anchorBlockIndex = anchorIndex,
                        anchorLocalTop = if (anchorIndex != null) initialY else null
                    )
                    onUpdateDrawingState { state -> state.copy(currentStroke = currentStroke) }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer == null || !pointer.pressed) {
                           break
                        }
                        val pos = pointer.position
                        val currentAnchorTop = computeAnchorTop(anchorIndex)
                        val addY = if (anchorIndex != null) pos.y - currentAnchorTop else pos.y
                        val addPoint = Note.Point(pos.x, addY)
                        currentStroke = currentStroke.copy(points = currentStroke.points + addPoint)
                        onUpdateDrawingState { state -> state.copy(currentStroke = currentStroke) }
                        pointer.consume()
                    }
                    val finishedStroke = currentStroke
                    if (finishedStroke.points.size > 1) {
                       onUpdateDrawingState { state ->
                           state.copy(strokes = state.strokes + finishedStroke, currentStroke = null)
                       }
                    } else {
                       onUpdateDrawingState { state -> state.copy(currentStroke = null) }
                    }
                }
            }
        }
    } else {
        Modifier // no pointer input; overlay is visual-only
    }

    Canvas(modifier = modifier.then(inputModifier)) {
        // Draw all persisted strokes
        val strokeWidth = with(density) { strokeWidthDp.toPx() }
        val visibleBlockSetForDraw = listState.layoutInfo.visibleItemsInfo.map { it.index - blockStartIndex }.toSet()
        fun Note.Path.toPath(): Path {
             val p = Path()
             val pts = points
             if (pts.isEmpty()) return p
             val anchorIdx = anchorBlockIndex
             val anchored = anchorIdx != null

             // If anchored and anchor not currently visible, return empty path so nothing is drawn
             if (anchored && !visibleBlockSetForDraw.contains(anchorIdx)) return p

             val anchorTop = computeAnchorTop(anchorIdx)

             val firstY = if (anchored) anchorTop + pts[0].y else pts[0].y
             p.moveTo(pts[0].x, firstY)
             for (i in 1 until pts.size) {
                val pt = pts[i]
                val y = if (anchored) anchorTop + pt.y else pt.y
                p.lineTo(pt.x, y)
             }
             return p
         }
        drawingState.strokes.forEach { stroke ->
            drawPath(
                path = stroke.toPath(),
                color = strokeColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
            )
        }
        drawingState.currentStroke?.let { stroke ->
            drawPath(
                path = stroke.toPath(),
                color = strokeColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
            )
        }
    }


}

// Utility: parse and serialize simple markdown-like block format used by the editor
private fun parseMarkdownToBlocks(markdown: String): List<NoteBlock> {
    if (markdown.isBlank()) return listOf(NoteBlock.Paragraph(""))
    val lines = markdown.lines()
    val blocks = mutableListOf<NoteBlock>()
    for (line in lines) {
        if (line.startsWith("P|")) {
            val parts = line.split("|", limit = 3)
            val text = parts.getOrNull(1) ?: ""
            val spans = if (parts.size > 2) jsonToSpanList(parts[2]) else emptyList()
            blocks.add(NoteBlock.Paragraph(text, spans))
        } else when {
            line.startsWith("[ ] ") -> blocks.add(NoteBlock.ChecklistItem(text = line.removePrefix("[ ] "), checked = false))
            line.startsWith("[x] ") || line.startsWith("[X] ") ->
                blocks.add(NoteBlock.ChecklistItem(text = line.substring(4), checked = true))
            line.startsWith("• ") -> blocks.add(NoteBlock.BulletItem(text = line.removePrefix("• ")))
            Regex("^\\d+\\. ").containsMatchIn(line) -> {
                val dotIndex = line.indexOf('.')
                val num = line.take(dotIndex).toIntOrNull() ?: 1
                val text = line.substring(dotIndex + 2)
                blocks.add(NoteBlock.NumberedItem(index = num, text = text))
            }
            else -> blocks.add(NoteBlock.Paragraph(line))
        }
    }
    return blocks
}

private fun blocksToMarkdown(blocks: List<NoteBlock>): String {
    return blocks.joinToString("\n") { block ->
        when (block) {
            is NoteBlock.Paragraph -> {
                val spansJson = spanListToJson(block.spans)
                if (spansJson.isNotEmpty())
                    "P|${block.text}|$spansJson"
                else
                    block.text
            }
            is NoteBlock.ChecklistItem -> (if (block.checked) "[x] " else "[ ] ") + block.text
            is NoteBlock.BulletItem -> "• " + block.text
            is NoteBlock.NumberedItem -> "${block.index}. " + block.text
        }
    }
}

private fun spanListToJson(spans: List<StyleSpan>): String {
    if (spans.isEmpty()) return ""
    return JSONArray().apply {
        spans.forEach { span ->
            put(JSONObject().apply {
                put("start", span.start)
                put("end", span.end)
                put("style", span.style.name)
            })
        }
    }.toString()
}

private fun jsonToSpanList(json: String): List<StyleSpan> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val obj = arr.getJSONObject(i)
            StyleSpan(
                start = obj.getInt("start"),
                end = obj.getInt("end"),
                style = InlineStyle.valueOf(obj.getString("style"))
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

// AttachmentList: show small mime-aware preview (image thumbnail or mime icon) and a remove button
@Composable
private fun AttachmentList(
    attachments: List<Note.Attachment>,
    onRemove: (Note.Attachment) -> Unit,
    attachmentsDir: java.io.File
) {
    val thumbDp = 40.dp
    val ctx = LocalContext.current

    // Long-press state and save launcher
    var optionsFor by remember { mutableStateOf<Note.Attachment?>(null) }
    var pendingSave by remember { mutableStateOf<Note.Attachment?>(null) }
    val contentResolver = ctx.contentResolver
    val coroutineScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    val createDocumentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val toSave = pendingSave
        if (uri != null && toSave != null) {
            // Offload heavy I/O to IO dispatcher
            isSaving = true
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val inputStream = if (toSave.uri.startsWith("/")) {
                            java.io.File(toSave.uri).inputStream()
                        } else {
                            contentResolver.openInputStream(toSave.uri.toUri())
                        }
                        inputStream?.use { inp ->
                            contentResolver.openOutputStream(uri)?.use { out ->
                                inp.copyTo(out)
                            }
                        } ?: throw java.io.IOException("Unable to open input for ${toSave.uri}")
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.w("NoteScreen", "Failed to save attachment ${toSave.uri}: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    isSaving = false
                }
             }
         }
         pendingSave = null
     }

    Column {
        Text(text = "Attachments", color = Color.DarkGray)
        for (att in attachments) {
            val rowModifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)

            Row(
                modifier = rowModifier.combinedClickable(
                    onClick = {
                        // Open the attachment when tapped
                        try {
                            val intent = Intent(Intent.ACTION_VIEW)
                            val attFile = java.io.File(att.uri)
                            val isInternal = attFile.exists() && attFile.absolutePath.startsWith(attachmentsDir.absolutePath)
                            if (isInternal) {
                                val contentUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", attFile)
                                intent.setDataAndType(contentUri, att.mimeType ?: "*/*")
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } else {
                                // Try parsing as URI (content:// or http). If parsing fails, fall back to file path.
                                val maybeUri = try { att.uri.toUri() } catch (_: Exception) { null }
                                if (maybeUri != null) {
                                    intent.setDataAndType(maybeUri, att.mimeType ?: ctx.contentResolver.getType(maybeUri) ?: "*/*")
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } else {
                                    Toast.makeText(ctx, "Unable to open attachment", Toast.LENGTH_SHORT).show()
                                    return@combinedClickable
                                }
                            }
                            // Use Activity startActivity; add NEW_TASK when context isn't an Activity
                            val chooser = Intent.createChooser(intent, att.displayName ?: "Open attachment")
                            if (ctx !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(chooser)
                        } catch (e: Exception) {
                            Log.w("NoteScreen", "Failed to open attachment ${att.uri}: ${e.message}")
                            Toast.makeText(ctx, "Cannot open file", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLongClick = {
                        // Show options dialog for this attachment
                        optionsFor = att
                    }
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val mime = att.mimeType ?: run {
                     val ext = try { java.io.File(att.uri).extension.lowercase() } catch (_: Exception) { "" }
                     when (ext) {
                         "png", "jpg", "jpeg", "gif", "webp" -> "image/$ext"
                         "pdf" -> "application/pdf"
                         "mp3", "wav", "m4a" -> "audio/*"
                         "mp4", "mov", "mkv" -> "video/*"
                         else -> ""
                     }
                 }

                if (mime.startsWith("image/")) {
                    // Use Coil to load the image from the internal file path (file:// URI)
                    val fileUri = if (att.uri.startsWith("/")) "file://${att.uri}" else att.uri
                    AsyncImage(
                        model = fileUri,
                        contentDescription = att.displayName ?: "attachment",
                        modifier = Modifier
                            .size(thumbDp)
                            .padding(end = 8.dp)
                    )
                } else {
                    val icon = when {
                        mime == "application/pdf" || att.uri.endsWith(".pdf", true) -> Icons.Filled.PictureAsPdf
                        mime.startsWith("audio") || att.uri.endsWith(".mp3", true) -> Icons.Filled.Audiotrack
                        mime.startsWith("video") || att.uri.endsWith(".mp4", true) -> Icons.Filled.Movie
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    }
                    Icon(
                        icon,
                        contentDescription = "file",
                        modifier = Modifier
                            .size(thumbDp)
                            .padding(end = 8.dp)
                    )
                }

                val display = att.displayName ?: try { java.io.File(att.uri).name } catch (_: Exception) { att.uri }
                Text(text = display, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(att) }) {
                    Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Remove Attachment")
                }
            }

            // Long-press options dialog anchored per-attachment
            if (optionsFor == att) {
                AlertDialog(
                    onDismissRequest = { optionsFor = null },
                    title = { Text(text = att.displayName ?: "Attachment") },
                    text = { Text(text = "Choose an action") },
                    confirmButton = {
                        TextButton(onClick = {
                            // Share
                            try {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = att.mimeType ?: "*/*"
                                    val attFile = java.io.File(att.uri)
                                    if (attFile.exists() && attFile.absolutePath.startsWith(attachmentsDir.absolutePath)) {
                                        val contentUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", attFile)
                                        putExtra(Intent.EXTRA_STREAM, contentUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    } else {
                                        val maybeUri = try { att.uri.toUri() } catch (_: Exception) { null }
                                        if (maybeUri != null) {
                                            putExtra(Intent.EXTRA_STREAM, maybeUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    }
                                }
                                val chooser = Intent.createChooser(shareIntent, "Share ${att.displayName ?: "file"}")
                                if (ctx !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(chooser)
                            } catch (e: Exception) {
                                Log.w("NoteScreen", "Share failed: ${e.message}")
                                Toast.makeText(ctx, "Share failed", Toast.LENGTH_SHORT).show()
                            }
                            optionsFor = null
                        }) { Text("Share") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                // Save: launch CreateDocument to let user pick a location
                                pendingSave = att
                                val suggested = att.displayName ?: try { java.io.File(att.uri).name } catch (_: Exception) { "attachment" }
                                createDocumentLauncher.launch(suggested)
                                optionsFor = null
                            }) { Text("Save") }
                             TextButton(onClick = { optionsFor = null }) { Text("Cancel") }
                         }
                     }
                 )

                 // Show a modal progress dialog while saving this specific attachment
                if (isSaving && pendingSave?.uri == att.uri) {
                    AlertDialog(onDismissRequest = {}, title = { Text("Saving…") }, text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Saving file…")
                        }
                    }, confirmButton = {})
                }
            }
        }
    }
}




