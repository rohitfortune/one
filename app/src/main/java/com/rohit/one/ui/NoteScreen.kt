package com.rohit.one.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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

// --- New structured model for the editor ---

private sealed class NoteBlock {
    data class Paragraph(var text: String) : NoteBlock()
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
    TEXT, DRAW, ERASE, SELECT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    note: Note?,
    onSave: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current

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

    fun saveNote() {
        val markdown = blocksToMarkdown(editorState.blocks)
        val noteToSave = Note(
            id = note?.id ?: 0,
            title = editorState.title,
            content = markdown,
            attachments = note?.attachments ?: emptyList(),
            paths = note?.paths ?: emptyList(),
            lastModified = System.currentTimeMillis()
        )
        onSave(noteToSave)
        Toast.makeText(context, "Note Saved", Toast.LENGTH_SHORT).show()
    }

    var editMode by remember { mutableStateOf(EditMode.TEXT) }

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
                                editorState = editorState.copy(title = it)
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
                    IconButton(onClick = { editMode = EditMode.TEXT }) {
                        Icon(
                            Icons.Filled.Keyboard,
                            contentDescription = "Text Mode",
                            tint = if (editMode == EditMode.TEXT) Color.Black else Color.Gray
                        )
                    }
                    // Drawing related icons are visually present but disabled in this refactor
                    IconButton(onClick = { /* no-op in new model */ }, enabled = false) {
                        Icon(Icons.Filled.Colorize, contentDescription = "Draw Mode", tint = Color.LightGray)
                    }
                    IconButton(onClick = { /* no-op in new model */ }, enabled = false) {
                        Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Erase Mode", tint = Color.LightGray)
                    }
                    IconButton(onClick = { /* no-op in new model */ }, enabled = false) {
                        Icon(Icons.Filled.TouchApp, contentDescription = "Select Mode", tint = Color.LightGray)
                    }
                    IconButton(onClick = {
                        history.undo()?.let { editorState = it }
                    }, enabled = history.canUndo()) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (history.canUndo()) Color.Black else Color.Gray
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
                    val blocks = editorState.blocks.toMutableList()
                    val insertPos = if (focusedBlockIndex in blocks.indices) focusedBlockIndex + 1 else blocks.size
                    blocks.add(insertPos, NoteBlock.ChecklistItem(text = "", checked = false))
                    editorState = editorState.copy(blocks = blocks)
                    focusedBlockIndex = insertPos
                    pushHistory()
                },
                onBulletList = {
                    val blocks = editorState.blocks.toMutableList()
                    val insertPos = if (focusedBlockIndex in blocks.indices) focusedBlockIndex + 1 else blocks.size
                    blocks.add(insertPos, NoteBlock.BulletItem(text = ""))
                    editorState = editorState.copy(blocks = blocks)
                    focusedBlockIndex = insertPos
                    pushHistory()
                },
                onNumberedList = {
                    val blocks = editorState.blocks.toMutableList()
                    val insertPos = if (focusedBlockIndex in blocks.indices) focusedBlockIndex + 1 else blocks.size
                    blocks.add(insertPos, NoteBlock.NumberedItem(index = 1, text = ""))
                    editorState = editorState.copy(blocks = blocks)
                    focusedBlockIndex = insertPos
                    pushHistory()
                },
                onBold = {
                    // Simple: append ** marker, real styling model would be richer
                    val blocks = editorState.blocks.toMutableList()
                    if (blocks.isNotEmpty()) {
                        val last = blocks.last()
                        if (last is NoteBlock.Paragraph) {
                            last.text += " **bold**"
                            editorState = editorState.copy(blocks = blocks)
                            pushHistory()
                        }
                    }
                },
                onItalic = {
                    val blocks = editorState.blocks.toMutableList()
                    if (blocks.isNotEmpty()) {
                        val last = blocks.last()
                        if (last is NoteBlock.Paragraph) {
                            last.text += " _italic_"
                            editorState = editorState.copy(blocks = blocks)
                            pushHistory()
                        }
                    }
                },
                onUnderline = {
                    val blocks = editorState.blocks.toMutableList()
                    if (blocks.isNotEmpty()) {
                        val last = blocks.last()
                        if (last is NoteBlock.Paragraph) {
                            last.text += " __underline__"
                            editorState = editorState.copy(blocks = blocks)
                            pushHistory()
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        // Main content: list of blocks
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.White),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(editorState.blocks, key = { index, _ -> index }) { index, block ->
                val isLastBlock = index == editorState.blocks.lastIndex
                val thisShouldFocus = index == focusedBlockIndex
                when (block) {
                    is NoteBlock.Paragraph -> ParagraphBlock(
                        text = block.text,
                        onTextChange = { newText ->
                            val blocks = editorState.blocks.toMutableList()
                            val current = blocks.getOrNull(index)
                            if (current is NoteBlock.Paragraph) {
                                blocks[index] = current.copy(text = newText)
                                editorState = editorState.copy(blocks = blocks)
                            }
                        },
                        onBackspaceAtStart = { isEmptyNow ->
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
                            }
                        },
                        onFocused = { focusedBlockIndex = index }
                    )

                    is NoteBlock.ChecklistItem -> ChecklistBlock(
                        item = block,
                        autoFocus = thisShouldFocus || (isLastBlock && block.text.isEmpty()),
                        onCheckedChange = { checked ->
                            val blocks = editorState.blocks.toMutableList()
                            val current = blocks.getOrNull(index)
                            if (current is NoteBlock.ChecklistItem) {
                                blocks[index] = current.copy(checked = checked)
                                editorState = editorState.copy(blocks = blocks)
                                pushHistory()
                            }
                        },
                        onTextChange = { newText ->
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
                        onEnter = { wasEmpty ->
                            val blocks = editorState.blocks.toMutableList()
                            val current = blocks.getOrNull(index) as? NoteBlock.ChecklistItem
                                ?: return@ChecklistBlock
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
                        autoFocus = thisShouldFocus || (isLastBlock && block.text.isEmpty()),
                        onTextChange = { newText ->
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
                        onEnter = { wasEmpty ->
                            val blocks = editorState.blocks.toMutableList()
                            val current = blocks.getOrNull(index) as? NoteBlock.BulletItem
                                ?: return@BulletBlock
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
                                ?: return@BulletBlock
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
                        autoFocus = thisShouldFocus || (isLastBlock && block.text.isEmpty()),
                        onTextChange = { newText ->
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
                        onEnter = { wasEmpty ->
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
}

@Composable
private fun ParagraphBlock(
    text: String,
    onTextChange: (String) -> Unit,
    onBackspaceAtStart: (isEmptyNow: Boolean) -> Unit,
    onFocused: () -> Unit
) {
    var value by remember(text) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    BasicTextField(
        value = value,
        onValueChange = { newValue ->
            val oldText = value.text
            val oldSelection = value.selection
            val newText = newValue.text
            val newSelection = newValue.selection

            val classicBackspace =
                oldSelection.start == 0 &&
                    oldSelection.end == 0 &&
                    newText.length == oldText.length - 1
            val clearToEmptyAtStart =
                newText.isEmpty() &&
                    oldText.isNotEmpty() &&
                    newSelection.start == 0 && newSelection.end == 0
            val emptyBackspaceNoOp =
                oldText.isEmpty() &&
                    newText.isEmpty() &&
                    oldSelection.start == 0 && oldSelection.end == 0 &&
                    newSelection.start == 0 && newSelection.end == 0

            val backspaceAtStart = classicBackspace || clearToEmptyAtStart || emptyBackspaceNoOp

            if (backspaceAtStart) {
                onBackspaceAtStart(newText.isEmpty())
            }

            value = newValue
            if (newText != text) {
                onTextChange(newText)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() },
        textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default),
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

private fun parseMarkdownToBlocks(markdown: String): List<NoteBlock> {
    if (markdown.isBlank()) return listOf(NoteBlock.Paragraph(""))
    val lines = markdown.lines()
    val blocks = mutableListOf<NoteBlock>()
    for (line in lines) {
        when {
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
            is NoteBlock.Paragraph -> block.text
            is NoteBlock.ChecklistItem -> (if (block.checked) "[x] " else "[ ] ") + block.text
            is NoteBlock.BulletItem -> "• " + block.text
            is NoteBlock.NumberedItem -> "${block.index}. " + block.text
        }
    }
}
