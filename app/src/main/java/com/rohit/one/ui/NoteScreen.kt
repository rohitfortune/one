package com.rohit.one.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohit.one.data.Note
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import org.json.JSONArray
import org.json.JSONObject

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
    val blocks: List<NoteBlock>
)

// Very small history stack for undo/redo on the new model
private class BlockHistory(private val limit: Int = 20) {
    private val stack = mutableListOf<NoteEditorState>()
    private var index = -1

    fun push(state: NoteEditorState) {
        if (stack.size > limit) {
            stack.removeAt(0)
            if (index > 0) index--
        }
        while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
        stack.add(state.copy(blocks = state.blocks.map { it.copyBlock() }))
        index = stack.lastIndex
    }

    fun undo(): NoteEditorState? {
        if (index > 0) {
            index--
            return stack[index].deepCopy()
        }
        return null
    }

    fun redo(): NoteEditorState? {
        if (index < stack.lastIndex) {
            index++
            return stack[index].deepCopy()
        }
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
                blocks = initialBlocks
            )
        )
    }

    // Index of the block that should grab focus on the next recomposition (-1 = none)
    var focusedBlockIndex by remember { mutableIntStateOf(-1) }

    val history = remember { BlockHistory().apply { push(editorState) } }

    fun pushHistory() {
        history.push(editorState)
    }

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
            attachments = note?.attachments ?: emptyList(),
            paths = drawingState.strokes,
            lastModified = System.currentTimeMillis()
        )
        onSave(noteToSave)
        Toast.makeText(context, "Note Saved", Toast.LENGTH_SHORT).show()
    }

    var editMode by remember { mutableStateOf(EditMode.TEXT) }
    var isEraserActive by remember { mutableStateOf(false) }

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pen icon is the single toggle between TEXT and DRAW modes
                    IconButton(onClick = {
                        if (isEraserActive) return@IconButton // Don't allow mode switch while eraser is active
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
                    }) {
                        Icon(
                            Icons.Filled.Colorize,
                            contentDescription = "Toggle Draw Mode",
                            tint = if (editMode == EditMode.DRAW && !isEraserActive) Color.Black else Color.Gray
                        )
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
                    // Select mode remains disabled placeholder
                    IconButton(onClick = { /* Select mode can be added later */ }, enabled = false) {
                        Icon(Icons.Filled.TouchApp, contentDescription = "Select Mode", tint = Color.LightGray)
                    }
                    IconButton(onClick = {
                        // Undo last drawing stroke if any, otherwise fall back to text undo
                        if (drawingState.strokes.isNotEmpty()) {
                            drawingState = drawingState.copy(
                                strokes = drawingState.strokes.dropLast(1),
                                currentStroke = null
                            )
                        } else {
                            history.undo()?.let { editorState = it }
                        }
                    }, enabled = history.canUndo() || drawingState.strokes.isNotEmpty()) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (history.canUndo() || drawingState.strokes.isNotEmpty()) Color.Black else Color.Gray
                        )
                    }
                    IconButton(onClick = {
                        history.redo()?.let { editorState = it }
                    }, enabled = history.canRedo()) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (history.canRedo()) Color.Black else Color.Gray
                        )
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
                        focusedBlockIndex = insertPos
                        pushHistory()
                    }
                },
                onBulletList = {
                    if (editMode == EditMode.TEXT) {
                        val blocks = editorState.blocks.toMutableList()
                        val insertPos = if (focusedBlockIndex in blocks.indices) focusedBlockIndex + 1 else blocks.size
                        blocks.add(insertPos, NoteBlock.BulletItem(text = ""))
                        editorState = editorState.copy(blocks = blocks)
                        focusedBlockIndex = insertPos
                        pushHistory()
                    }
                },
                onNumberedList = {
                    if (editMode == EditMode.TEXT) {
                        val blocks = editorState.blocks.toMutableList()
                        val insertPos = if (focusedBlockIndex in blocks.indices) focusedBlockIndex + 1 else blocks.size
                        blocks.add(insertPos, NoteBlock.NumberedItem(index = 1, text = ""))
                        editorState = editorState.copy(blocks = blocks)
                        focusedBlockIndex = insertPos
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(editorState.blocks, key = { index, _ -> index }) { index, block ->
                    val isLastBlock = index == editorState.blocks.lastIndex
                    val thisShouldFocus = index == focusedBlockIndex
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
                                    }
                                }
                            },
                            onBackspaceAtStart = { isEmptyNow: Boolean ->
                                val blocks = editorState.blocks.toMutableList()
                                Log.d(
                                    "NoteScreen",
                                    "Paragraph onBackspaceAtStart: index=$index isEmptyNow=$isEmptyNow sizeBefore=${blocks.size}"
                                )

                                // New behavior: backspace at start only ever removes this paragraph
                                // (when empty) and never deletes the block above.
                                if (isEmptyNow && blocks.size > 1 && index in blocks.indices) {
                                    Log.d(
                                        "NoteScreen",
                                        "Paragraph backspace: removing empty paragraph at index=$index"
                                    )
                                    blocks.removeAt(index)
                                    editorState = editorState.copy(blocks = blocks)
                                    pushHistory()
                                    blockSelection.remove(index)
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
                                val current = blocks.getOrNull(index) as? NoteBlock.ChecklistItem
                                if (current == null) return@ChecklistBlock
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
                                if (current == null) return@ChecklistBlock

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
                                val current = blocks.getOrNull(index) as? NoteBlock.BulletItem
                                if (current == null) return@BulletBlock
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
                                val current = blocks.getOrNull(index) as? NoteBlock.BulletItem
                                if (current == null) return@BulletBlock
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
                                            "Numbered onTextChange index=$index guarded old='${current.text}' new='$newText'"
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
                                if (current == null) return@NumberedBlock
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
                                if (current == null) return@NumberedBlock
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

            // Always render drawing overlay so strokes stay visible in both modes,
            // but only capture pointer events when actually drawing.
            DrawingOverlay(
                modifier = Modifier.fillMaxSize(),
                drawingState = drawingState,
                isInputEnabled = (drawingState.isDrawing && !isEraserActive) || isEraserActive,
                isEraserActive = isEraserActive,
                onUpdateDrawingState = { transform -> drawingState = transform(drawingState) }
            )
        }
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
    onBackspaceAtStart: (isEmptyNow: Boolean) -> Unit,
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
            value = newValue
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
                    val logical = oldText
                    val isEmptyNow = logical.isBlank()
                    onEnter(isEmptyNow)
                    value = TextFieldValue(logical, TextRange(logical.length))
                    onTextChange(logical)
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
                    val logical = oldText
                    val isEmptyNow = logical.isBlank()
                    onEnter(isEmptyNow)
                    value = TextFieldValue(logical, TextRange(logical.length))
                    onTextChange(logical)
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
                    val logical = oldText
                    val isEmptyNow = logical.isBlank()
                    Log.d("NoteScreen", "NumberedBlock detected ENTER logical='$logical' isEmptyNow=$isEmptyNow")
                    onEnter(isEmptyNow)
                    value = TextFieldValue(logical, TextRange(logical.length))
                    onTextChange(logical)
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
    onUpdateDrawingState: ((DrawingState) -> DrawingState) -> Unit
) {
    val inputModifier = if (isInputEnabled) {
        if (isEraserActive) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastPos = down.position
                    var erased = false
                    onUpdateDrawingState { state -> state }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer == null || !pointer.pressed) break
                        val pos = pointer.position
                        // Erase logic: remove points close to the drag path
                        onUpdateDrawingState { state ->
                            val newStrokes = state.strokes.flatMap { stroke ->
                                val filtered = stroke.points.filterNot { pt ->
                                    val dx = pt.x - pos.x
                                    val dy = pt.y - pos.y
                                    Math.hypot(dx.toDouble(), dy.toDouble()) < 16.0 // 16px threshold (was 32.0)
                                }
                                if (filtered.size >= 2) listOf(stroke.copy(points = filtered)) else emptyList()
                            }
                            state.copy(strokes = newStrokes, currentStroke = null)
                        }
                        lastPos = pos
                        pointer.consume()
                    }
                }
            }
        } else {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Start a new stroke with the initial down position
                    var currentStroke = Note.Path(
                        points = listOf(Note.Point(down.position.x, down.position.y))
                    )
                    onUpdateDrawingState { state ->
                        state.copy(currentStroke = currentStroke)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer == null || !pointer.pressed) {
                            break
                        }
                        val pos = pointer.position
                        // Append new point by creating a new list (Note.Path.points is immutable)
                        currentStroke = currentStroke.copy(
                            points = currentStroke.points + Note.Point(pos.x, pos.y)
                        )
                        onUpdateDrawingState { state ->
                            state.copy(currentStroke = currentStroke)
                        }
                        pointer.consume()
                    }
                    val finishedStroke = currentStroke
                    if (finishedStroke.points.size > 1) {
                        onUpdateDrawingState { state ->
                            state.copy(
                                strokes = state.strokes + finishedStroke,
                                currentStroke = null
                            )
                        }
                    } else {
                        onUpdateDrawingState { state ->
                            state.copy(currentStroke = null)
                        }
                    }
                }
            }
        }
    } else {
        Modifier // no pointer input; overlay is visual-only
    }
    Canvas(modifier = modifier.then(inputModifier)) {
        // Draw all persisted strokes
        val strokeWidth = 4.dp.toPx()
        fun Note.Path.toPath(): Path {
            val p = Path()
            val pts = points
            if (pts.isEmpty()) return p
            p.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                p.lineTo(pts[i].x, pts[i].y)
            }
            return p
        }
        drawingState.strokes.forEach { stroke ->
            drawPath(
                path = stroke.toPath(),
                color = Color.Black,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
            )
        }
        drawingState.currentStroke?.let { stroke ->
            drawPath(
                path = stroke.toPath(),
                color = Color.Black,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
            )
        }
    }
}

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
                val num = line.substring(0, dotIndex).toIntOrNull() ?: 1
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
