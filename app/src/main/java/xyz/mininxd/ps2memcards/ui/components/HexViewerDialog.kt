package xyz.mininxd.ps2memcards.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import xyz.mininxd.ps2memcards.ui.theme.Ps2AccentAmber
import java.util.Locale

private data class HexEditAction(
    val offset: Int,
    val oldBytes: ByteArray,
    val newBytes: ByteArray,
    val description: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HexEditAction) return false
        return offset == other.offset &&
                oldBytes.contentEquals(other.oldBytes) &&
                newBytes.contentEquals(other.newBytes) &&
                description == other.description
    }

    override fun hashCode(): Int {
        var result = offset
        result = 31 * result + oldBytes.contentHashCode()
        result = 31 * result + newBytes.contentHashCode()
        result = 31 * result + description.hashCode()
        return result
    }
}

@Composable
fun HexViewerDialog(
    title: String,
    data: ByteArray,
    onDismiss: () -> Unit,
    isReadOnly: Boolean = false,
    onSave: ((ByteArray) -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Internal editable data buffer
    val currentBytes = remember(data) { data.copyOf() }
    val initialData = remember(data) { data.copyOf() }
    var dataVersion by remember { mutableIntStateOf(0) }

    // Undo / Redo stacks
    val undoStack = remember { mutableStateListOf<HexEditAction>() }
    val redoStack = remember { mutableStateListOf<HexEditAction>() }
    val modifiedOffsets = remember { mutableStateListOf<Int>() }

    fun refreshModifiedOffsets() {
        modifiedOffsets.clear()
        for (i in currentBytes.indices) {
            if (i < initialData.size && currentBytes[i] != initialData[i]) {
                modifiedOffsets.add(i)
            }
        }
    }

    fun applySingleByteEdit(targetOffset: Int, newByte: Byte) {
        if (targetOffset !in currentBytes.indices) return
        val oldByte = currentBytes[targetOffset]
        if (oldByte == newByte) return

        currentBytes[targetOffset] = newByte
        undoStack.add(
            HexEditAction(
                offset = targetOffset,
                oldBytes = byteArrayOf(oldByte),
                newBytes = byteArrayOf(newByte),
                description = String.format(Locale.US, "Edit byte at 0x%08X", targetOffset)
            )
        )
        redoStack.clear()
        refreshModifiedOffsets()
        dataVersion++
    }

    fun applyRangeEdit(startOffset: Int, newBytes: ByteArray) {
        if (startOffset !in currentBytes.indices || newBytes.isEmpty()) return
        val writeLen = minOf(newBytes.size, currentBytes.size - startOffset)
        if (writeLen <= 0) return

        val oldBytes = currentBytes.copyOfRange(startOffset, startOffset + writeLen)
        val actualNew = newBytes.copyOfRange(0, writeLen)
        System.arraycopy(actualNew, 0, currentBytes, startOffset, writeLen)

        undoStack.add(
            HexEditAction(
                offset = startOffset,
                oldBytes = oldBytes,
                newBytes = actualNew,
                description = String.format(Locale.US, "Write %d bytes at 0x%08X", writeLen, startOffset)
            )
        )
        redoStack.clear()
        refreshModifiedOffsets()
        dataVersion++
    }

    fun performUndo() {
        val action = undoStack.removeLastOrNull() ?: return
        System.arraycopy(action.oldBytes, 0, currentBytes, action.offset, action.oldBytes.size)
        redoStack.add(action)
        refreshModifiedOffsets()
        dataVersion++
    }

    fun performRedo() {
        val action = redoStack.removeLastOrNull() ?: return
        System.arraycopy(action.newBytes, 0, currentBytes, action.offset, action.newBytes.size)
        undoStack.add(action)
        refreshModifiedOffsets()
        dataVersion++
    }

    fun performRevert() {
        System.arraycopy(initialData, 0, currentBytes, 0, minOf(initialData.size, currentBytes.size))
        undoStack.clear()
        redoStack.clear()
        modifiedOffsets.clear()
        dataVersion++
        Toast.makeText(context, "Reverted all modifications", Toast.LENGTH_SHORT).show()
    }

    val rowCount = (currentBytes.size + 15) / 16
    var selectedByteOffset by remember { mutableIntStateOf(-1) }

    var showJumpBar by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var isHexSearch by remember { mutableStateOf(false) }
    var searchMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var searchMatchLen by remember { mutableIntStateOf(0) }
    var currentSearchMatchIndex by remember { mutableIntStateOf(-1) }

    var showEditByteDialog by remember { mutableStateOf(false) }
    var showWriteDataDialog by remember { mutableStateOf(false) }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val monoStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )

    fun performJump(targetOffset: Int) {
        val clamped = targetOffset.coerceIn(0, maxOf(0, currentBytes.size - 1))
        val targetRow = clamped / 16
        selectedByteOffset = clamped
        coroutineScope.launch {
            listState.animateScrollToItem(targetRow)
        }
    }

    fun performSearch() {
        val (matches, len) = findMatches(currentBytes, searchInput, isHexSearch)
        searchMatches = matches
        searchMatchLen = len
        if (matches.isNotEmpty()) {
            currentSearchMatchIndex = 0
            performJump(matches[0])
            Toast.makeText(context, "Found ${matches.size} match(es)", Toast.LENGTH_SHORT).show()
        } else {
            currentSearchMatchIndex = -1
            Toast.makeText(context, "No matches found", Toast.LENGTH_SHORT).show()
        }
    }

    fun searchNext() {
        if (searchMatches.isEmpty()) return
        currentSearchMatchIndex = (currentSearchMatchIndex + 1) % searchMatches.size
        performJump(searchMatches[currentSearchMatchIndex])
    }

    fun searchPrev() {
        if (searchMatches.isEmpty()) return
        currentSearchMatchIndex = if (currentSearchMatchIndex <= 0) searchMatches.size - 1 else currentSearchMatchIndex - 1
        performJump(searchMatches[currentSearchMatchIndex])
    }

    val hasModifications = modifiedOffsets.isNotEmpty()

    fun handleRequestClose() {
        if (hasModifications) {
            showDiscardConfirmDialog = true
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { handleRequestClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Toolbar Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isReadOnly) Icons.Default.Code else Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (hasModifications) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "*",
                                    color = Ps2AccentAmber,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${String.format(Locale.US, "%,d", currentBytes.size)} B (0x${currentBytes.size.toString(16).uppercase()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (hasModifications) {
                                Text(
                                    text = "• ${modifiedOffsets.size} modified",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ps2AccentAmber,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Undo Button
                    if (!isReadOnly) {
                        IconButton(
                            onClick = { performUndo() },
                            enabled = undoStack.isNotEmpty(),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo",
                                tint = if (undoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Redo Button
                        IconButton(
                            onClick = { performRedo() },
                            enabled = redoStack.isNotEmpty(),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Redo,
                                contentDescription = "Redo",
                                tint = if (redoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Search Button
                    IconButton(
                        onClick = {
                            showSearchBar = !showSearchBar
                            if (showSearchBar) showJumpBar = false
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Find",
                            tint = if (showSearchBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Jump Button
                    IconButton(
                        onClick = {
                            showJumpBar = !showJumpBar
                            if (showJumpBar) showSearchBar = false
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Jump",
                            tint = if (showJumpBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Save Button
                    if (onSave != null && !isReadOnly) {
                        if (hasModifications) {
                            FilledTonalButton(
                                onClick = { onSave(currentBytes.copyOf()) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            IconButton(
                                onClick = { onSave(currentBytes.copyOf()) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = "Save",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Overflow Menu
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Hex Dump") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    menuExpanded = false
                                    val dump = buildHexDumpText(currentBytes, maxRows = 2048)
                                    clipboardManager.setText(AnnotatedString(dump))
                                    Toast.makeText(context, "Hex dump copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Raw Hex String") },
                                leadingIcon = { Icon(Icons.Default.Code, null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    menuExpanded = false
                                    val hexStr = buildRawHexString(currentBytes, maxBytes = 32768)
                                    clipboardManager.setText(AnnotatedString(hexStr))
                                    Toast.makeText(context, "Raw hex string copied", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Printable ASCII") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    menuExpanded = false
                                    val asciiStr = buildPrintableAscii(currentBytes, maxBytes = 65536)
                                    clipboardManager.setText(AnnotatedString(asciiStr))
                                    Toast.makeText(context, "ASCII text copied", Toast.LENGTH_SHORT).show()
                                }
                            )
                            if (hasModifications && !isReadOnly) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Revert All Edits", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        menuExpanded = false
                                        performRevert()
                                    }
                                )
                            }
                        }
                    }

                    // Close Button
                    IconButton(
                        onClick = { handleRequestClose() },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Collapsible Search Panel
                AnimatedVisibility(visible = showSearchBar) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = searchInput,
                                    onValueChange = {
                                        searchInput = it
                                        searchMatches = emptyList()
                                        currentSearchMatchIndex = -1
                                    },
                                    placeholder = {
                                        Text(
                                            if (isHexSearch) "Hex (e.g. 50 53 32)" else "Text (e.g. SONY)",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                FilterChip(
                                    selected = isHexSearch,
                                    onClick = { isHexSearch = !isHexSearch },
                                    label = { Text(if (isHexSearch) "HEX" else "TXT", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(34.dp)
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                Button(
                                    onClick = { performSearch() },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("Find", style = MaterialTheme.typography.labelSmall)
                                }

                                if (searchMatches.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { searchPrev() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Prev Match", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = { searchNext() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Next Match", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            if (searchMatches.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Match ${currentSearchMatchIndex + 1} of ${searchMatches.size} (at 0x${searchMatches[currentSearchMatchIndex].toString(16).uppercase()})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // Collapsible Jump to Offset Bar
                AnimatedVisibility(visible = showJumpBar) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = jumpInput,
                                onValueChange = { jumpInput = it },
                                placeholder = { Text("Offset (e.g. 0x100 or 256)", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    val offset = parseOffset(jumpInput)
                                    if (offset != null) {
                                        performJump(offset)
                                    } else {
                                        Toast.makeText(context, "Invalid offset", Toast.LENGTH_SHORT).show()
                                    }
                                }),
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    val offset = parseOffset(jumpInput)
                                    if (offset != null) {
                                        performJump(offset)
                                    } else {
                                        Toast.makeText(context, "Invalid offset", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Go", style = MaterialTheme.typography.labelMedium)
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(
                                onClick = { performJump(0) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Jump to Top", modifier = Modifier.size(16.dp))
                            }

                            IconButton(
                                onClick = { performJump(maxOf(0, currentBytes.size - 1)) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Jump to End", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Hex Canvas Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surface)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Column(modifier = Modifier.width(620.dp)) {
                        // Sticky Hex Header Bar
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Offset(h) ",
                                    style = monoStyle.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Text(
                                    text = "00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F",
                                    style = monoStyle.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                                Text(
                                    text = "  |Decoded text    |",
                                    style = monoStyle.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // Hex Data Rows
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 4.dp)
                        ) {
                            items(
                                count = rowCount,
                                key = { it }
                            ) { rowIndex ->
                                val offset = rowIndex * 16
                                val isRowActive = selectedByteOffset / 16 == rowIndex

                                val offsetColor = MaterialTheme.colorScheme.primary
                                val normalHexColor = MaterialTheme.colorScheme.onSurface
                                val zeroHexColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                val ffHexColor = MaterialTheme.colorScheme.tertiary
                                val asciiColor = MaterialTheme.colorScheme.secondary
                                val dotColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                val selectedBgColor = MaterialTheme.colorScheme.primary
                                val selectedTextColor = MaterialTheme.colorScheme.onPrimary
                                val modifiedBgColor = Ps2AccentAmber.copy(alpha = 0.35f)
                                val searchBgColor = MaterialTheme.colorScheme.tertiaryContainer
                                val searchTextColor = MaterialTheme.colorScheme.onTertiaryContainer

                                var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

                                val rowAnnotatedString = remember(rowIndex, selectedByteOffset, dataVersion, currentSearchMatchIndex) {
                                    buildAnnotatedString {
                                        // 1. Offset
                                        pushStyle(SpanStyle(color = offsetColor, fontWeight = if (isRowActive) FontWeight.Bold else FontWeight.Normal))
                                        append(String.format(Locale.US, "%08X:  ", offset))
                                        pop()

                                        // 2. 16 Hex bytes
                                        for (i in 0 until 16) {
                                            val byteIndex = offset + i
                                            if (byteIndex < currentBytes.size) {
                                                val b = currentBytes[byteIndex].toInt() and 0xFF
                                                val isByteSelected = byteIndex == selectedByteOffset
                                                val isModified = byteIndex in modifiedOffsets
                                                val isSearchMatch = searchMatches.isNotEmpty() &&
                                                        currentSearchMatchIndex in searchMatches.indices &&
                                                        byteIndex >= searchMatches[currentSearchMatchIndex] &&
                                                        byteIndex < searchMatches[currentSearchMatchIndex] + searchMatchLen

                                                when {
                                                    isByteSelected -> {
                                                        pushStyle(SpanStyle(background = selectedBgColor, color = selectedTextColor, fontWeight = FontWeight.Bold))
                                                    }
                                                    isSearchMatch -> {
                                                        pushStyle(SpanStyle(background = searchBgColor, color = searchTextColor, fontWeight = FontWeight.Bold))
                                                    }
                                                    isModified -> {
                                                        pushStyle(SpanStyle(background = modifiedBgColor, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold))
                                                    }
                                                    b == 0 -> {
                                                        pushStyle(SpanStyle(color = zeroHexColor, fontWeight = FontWeight.Normal))
                                                    }
                                                    b == 0xFF -> {
                                                        pushStyle(SpanStyle(color = ffHexColor, fontWeight = FontWeight.Medium))
                                                    }
                                                    b in 32..126 -> {
                                                        pushStyle(SpanStyle(color = asciiColor, fontWeight = FontWeight.Medium))
                                                    }
                                                    else -> {
                                                        pushStyle(SpanStyle(color = normalHexColor, fontWeight = FontWeight.Normal))
                                                    }
                                                }
                                                append(String.format(Locale.US, "%02X", b))
                                                pop()
                                                append(" ")
                                            } else {
                                                append("   ")
                                            }
                                            if (i == 7) append(" ")
                                        }

                                        // 3. ASCII column
                                        append(" |")
                                        for (i in 0 until 16) {
                                            val byteIndex = offset + i
                                            if (byteIndex < currentBytes.size) {
                                                val b = currentBytes[byteIndex].toInt() and 0xFF
                                                val isByteSelected = byteIndex == selectedByteOffset
                                                val isModified = byteIndex in modifiedOffsets
                                                val isSearchMatch = searchMatches.isNotEmpty() &&
                                                        currentSearchMatchIndex in searchMatches.indices &&
                                                        byteIndex >= searchMatches[currentSearchMatchIndex] &&
                                                        byteIndex < searchMatches[currentSearchMatchIndex] + searchMatchLen

                                                when {
                                                    isByteSelected -> {
                                                        pushStyle(SpanStyle(background = selectedBgColor, color = selectedTextColor, fontWeight = FontWeight.Bold))
                                                    }
                                                    isSearchMatch -> {
                                                        pushStyle(SpanStyle(background = searchBgColor, color = searchTextColor, fontWeight = FontWeight.Bold))
                                                    }
                                                    isModified -> {
                                                        pushStyle(SpanStyle(background = modifiedBgColor, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold))
                                                    }
                                                    b in 32..126 -> {
                                                        pushStyle(SpanStyle(color = normalHexColor, fontWeight = FontWeight.Normal))
                                                    }
                                                    else -> {
                                                        pushStyle(SpanStyle(color = dotColor, fontWeight = FontWeight.Normal))
                                                    }
                                                }
                                                if (b in 32..126) append(b.toChar()) else append('.')
                                                pop()
                                            } else {
                                                append(' ')
                                            }
                                        }
                                        append('|')
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isRowActive) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .pointerInput(rowIndex) {
                                            detectTapGestures { tapOffset ->
                                                val charIndex = textLayoutResult?.getOffsetForPosition(tapOffset) ?: 0
                                                val byteInRow = calculateByteIndexFromCharPosition(charIndex)
                                                val targetByteOffset = (offset + byteInRow).coerceIn(0, maxOf(0, currentBytes.size - 1))
                                                selectedByteOffset = targetByteOffset
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = rowAnnotatedString,
                                        style = monoStyle,
                                        onTextLayout = { textLayoutResult = it }
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Bottom Inspector & Editing Panel
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (selectedByteOffset in currentBytes.indices) {
                        val b = currentBytes[selectedByteOffset].toInt() and 0xFF
                        val charDisplay = if (b in 32..126) "'${b.toChar()}'" else "N/A"
                        val bin = String.format(Locale.US, "%8s", Integer.toBinaryString(b)).replace(' ', '0')
                        val uint16 = if (selectedByteOffset + 1 < currentBytes.size) {
                            (b) or ((currentBytes[selectedByteOffset + 1].toInt() and 0xFF) shl 8)
                        } else null
                        val uint32 = if (selectedByteOffset + 3 < currentBytes.size) {
                            (b.toLong()) or
                            ((currentBytes[selectedByteOffset + 1].toLong() and 0xFF) shl 8) or
                            ((currentBytes[selectedByteOffset + 2].toLong() and 0xFF) shl 16) or
                            ((currentBytes[selectedByteOffset + 3].toLong() and 0xFF) shl 24)
                        } else null

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            // Row 1: Offset info & Byte Navigator & 16-byte Quick Picker
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { if (selectedByteOffset > 0) performJump(selectedByteOffset - 1) },
                                    enabled = selectedByteOffset > 0,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev Byte", modifier = Modifier.size(16.dp))
                                }

                                Text(
                                    text = String.format(Locale.US, "0x%08X", selectedByteOffset),
                                    style = monoStyle.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                IconButton(
                                    onClick = { if (selectedByteOffset < currentBytes.size - 1) performJump(selectedByteOffset + 1) },
                                    enabled = selectedByteOffset < currentBytes.size - 1,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Byte", modifier = Modifier.size(16.dp))
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Quick 16-byte row strip
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val rowBase = (selectedByteOffset / 16) * 16
                                    for (i in 0 until 16) {
                                        val bOffset = rowBase + i
                                        if (bOffset < currentBytes.size) {
                                            val isCurrent = bOffset == selectedByteOffset
                                            val isMod = bOffset in modifiedOffsets
                                            val bVal = currentBytes[bOffset].toInt() and 0xFF
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = when {
                                                    isCurrent -> MaterialTheme.colorScheme.primary
                                                    isMod -> Ps2AccentAmber.copy(alpha = 0.35f)
                                                    else -> MaterialTheme.colorScheme.surface
                                                },
                                                border = BorderStroke(
                                                    0.5.dp,
                                                    if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                                ),
                                                modifier = Modifier
                                                    .clickable { selectedByteOffset = bOffset }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = String.format(Locale.US, "%02X", bVal),
                                                    style = monoStyle.copy(
                                                        fontSize = 9.sp,
                                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Selection",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { selectedByteOffset = -1 }
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            // Row 2: Value inspector (horizontally scrollable)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(Locale.US, "Hex: 0x%02X", b),
                                    style = monoStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "Dec: $b",
                                    style = monoStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "ASCII: $charDisplay",
                                    style = monoStyle.copy(color = MaterialTheme.colorScheme.secondary),
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "Bin: $bin",
                                    style = monoStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    maxLines = 1,
                                    softWrap = false
                                )
                                if (uint16 != null) {
                                    Text(
                                        text = String.format(Locale.US, "u16: %d", uint16),
                                        style = monoStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                if (uint32 != null) {
                                    Text(
                                        text = String.format(Locale.US, "u32: %d", uint32),
                                        style = monoStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            if (!isReadOnly) {
                                Spacer(modifier = Modifier.height(4.dp))

                                // Row 3: Editing action buttons
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { showEditByteDialog = true },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Edit Byte", style = MaterialTheme.typography.labelSmall)
                                    }

                                    OutlinedButton(
                                        onClick = { showWriteDataDialog = true },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Write Data", style = MaterialTheme.typography.labelSmall)
                                    }

                                    AssistChip(
                                        onClick = { applySingleByteEdit(selectedByteOffset, 0.toByte()) },
                                        label = { Text("Zero (00)", style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(30.dp)
                                    )

                                    AssistChip(
                                        onClick = { applySingleByteEdit(selectedByteOffset, 0xFF.toByte()) },
                                        label = { Text("Fill (FF)", style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(30.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isReadOnly) "Tap any byte or row to inspect values" else "Tap any byte or row to inspect & edit values",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )

                            Text(
                                text = "${rowCount} lines",
                                style = monoStyle.copy(color = MaterialTheme.colorScheme.outline),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Edit Byte Dialog
    if (showEditByteDialog && selectedByteOffset in currentBytes.indices) {
        EditByteDialog(
            initialByte = currentBytes[selectedByteOffset],
            offset = selectedByteOffset,
            maxOffset = currentBytes.size - 1,
            onDismiss = { showEditByteDialog = false },
            onApply = { newByte, advanceNext ->
                applySingleByteEdit(selectedByteOffset, newByte)
                if (advanceNext && selectedByteOffset < currentBytes.size - 1) {
                    selectedByteOffset++
                } else {
                    showEditByteDialog = false
                }
            }
        )
    }

    // Modal Write Data Dialog
    if (showWriteDataDialog && selectedByteOffset in currentBytes.indices) {
        WriteDataDialog(
            startOffset = selectedByteOffset,
            totalSize = currentBytes.size,
            onDismiss = { showWriteDataDialog = false },
            onWrite = { writeBytes ->
                applyRangeEdit(selectedByteOffset, writeBytes)
                showWriteDataDialog = false
            }
        )
    }

    // Modal Discard Confirmation Dialog
    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = { Text("Discard Unsaved Changes?") },
            text = { Text("You have modified ${modifiedOffsets.size} byte(s). If you leave now, these changes will be discarded.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmDialog = false
                        onDismiss()
                    }
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Button(onClick = { showDiscardConfirmDialog = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }
}

@Composable
private fun EditByteDialog(
    initialByte: Byte,
    offset: Int,
    maxOffset: Int,
    onDismiss: () -> Unit,
    onApply: (newByte: Byte, advanceNext: Boolean) -> Unit
) {
    var editMode by remember { mutableIntStateOf(0) } // 0: Hex, 1: ASCII, 2: Decimal
    val uByte = initialByte.toInt() and 0xFF
    var hexText by remember { mutableStateOf(String.format(Locale.US, "%02X", uByte)) }
    var asciiText by remember { mutableStateOf(if (uByte in 32..126) uByte.toChar().toString() else "") }
    var decText by remember { mutableStateOf(uByte.toString()) }

    fun currentParsedByte(): Byte? {
        return when (editMode) {
            0 -> hexText.trim().toIntOrNull(16)?.toByte()
            1 -> if (asciiText.isNotEmpty()) asciiText[0].code.toByte() else null
            2 -> decText.trim().toIntOrNull()?.let { if (it in 0..255) it.toByte() else null }
            else -> null
        }
    }

    val parsed = currentParsedByte()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = String.format(Locale.US, "Edit Byte at 0x%08X", offset),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mode selector chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = editMode == 0,
                        onClick = { editMode = 0 },
                        label = { Text("Hex", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = editMode == 1,
                        onClick = { editMode = 1 },
                        label = { Text("ASCII", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = editMode == 2,
                        onClick = { editMode = 2 },
                        label = { Text("Decimal", style = MaterialTheme.typography.labelSmall) }
                    )
                }

                when (editMode) {
                    0 -> {
                        OutlinedTextField(
                            value = hexText,
                            onValueChange = { input ->
                                val filtered = input.uppercase(Locale.US).filter { it in "0123456789ABCDEF" }.take(2)
                                hexText = filtered
                                val b = filtered.toIntOrNull(16)
                                if (b != null) {
                                    asciiText = if (b in 32..126) b.toChar().toString() else ""
                                    decText = b.toString()
                                }
                            },
                            label = { Text("Hex (00 - FF)") },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    1 -> {
                        OutlinedTextField(
                            value = asciiText,
                            onValueChange = { input ->
                                val filtered = input.take(1)
                                asciiText = filtered
                                if (filtered.isNotEmpty()) {
                                    val code = filtered[0].code and 0xFF
                                    hexText = String.format(Locale.US, "%02X", code)
                                    decText = code.toString()
                                }
                            },
                            label = { Text("ASCII Character") },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    2 -> {
                        OutlinedTextField(
                            value = decText,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() }.take(3)
                                decText = filtered
                                val d = filtered.toIntOrNull()
                                if (d != null && d in 0..255) {
                                    hexText = String.format(Locale.US, "%02X", d)
                                    asciiText = if (d in 32..126) d.toChar().toString() else ""
                                }
                            },
                            label = { Text("Decimal (0 - 255)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Comparison Preview
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val beforeChar = if (uByte in 32..126) "'${uByte.toChar()}'" else "."
                        Text(
                            text = String.format(Locale.US, "Original: 0x%02X (%d, %s)", uByte, uByte, beforeChar),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.outline
                        )
                        val afterByte = parsed?.toInt()?.and(0xFF)
                        val afterChar = if (afterByte != null && afterByte in 32..126) "'${afterByte.toChar()}'" else "."
                        Text(
                            text = if (afterByte != null) {
                                String.format(Locale.US, "New Value: 0x%02X (%d, %s)", afterByte, afterByte, afterChar)
                            } else "Invalid input",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            color = if (afterByte != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (offset < maxOffset) {
                    TextButton(
                        onClick = {
                            parsed?.let { onApply(it, true) }
                        },
                        enabled = parsed != null
                    ) {
                        Text("Apply & Next")
                    }
                }
                Button(
                    onClick = {
                        parsed?.let { onApply(it, false) }
                    },
                    enabled = parsed != null
                ) {
                    Text("Apply")
                }
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
private fun WriteDataDialog(
    startOffset: Int,
    totalSize: Int,
    onDismiss: () -> Unit,
    onWrite: (bytes: ByteArray) -> Unit
) {
    var isHexMode by remember { mutableStateOf(true) }
    var inputText by remember { mutableStateOf("") }

    val remainingBytes = maxOf(0, totalSize - startOffset)

    fun parseInput(): ByteArray? {
        val trimmed = inputText.trim()
        if (trimmed.isEmpty()) return null
        return if (isHexMode) {
            val clean = trimmed.replace(" ", "").replace("0x", "").replace(":", "").replace(",", "")
            if (clean.isEmpty() || clean.length % 2 != 0) return null
            try {
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (_: Exception) {
                null
            }
        } else {
            trimmed.toByteArray(Charsets.UTF_8)
        }
    }

    val parsedBytes = parseInput()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = String.format(Locale.US, "Write Data at 0x%08X", startOffset),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isHexMode,
                        onClick = { isHexMode = true },
                        label = { Text("Hex Bytes") }
                    )
                    FilterChip(
                        selected = !isHexMode,
                        onClick = { isHexMode = false },
                        label = { Text("ASCII / UTF-8") }
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = if (isHexMode) "e.g. 50 53 32 20 4D 43" else "e.g. My PS2 Save Data",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (parsedBytes != null && parsedBytes.isNotEmpty()) {
                            Text(
                                text = "Will write ${parsedBytes.size} byte(s)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val endOffset = minOf(totalSize, startOffset + parsedBytes.size) - 1
                            Text(
                                text = String.format(Locale.US, "Range: 0x%08X .. 0x%08X", startOffset, endOffset),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (parsedBytes.size > remainingBytes) {
                                Text(
                                    text = "Warning: Exceeds file end! Only $remainingBytes bytes will be written.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            Text(
                                text = if (isHexMode) "Enter hex bytes (e.g. 41 42 43)" else "Enter text string to write",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    parsedBytes?.let { onWrite(it) }
                },
                enabled = parsedBytes != null && parsedBytes.isNotEmpty() && remainingBytes > 0
            ) {
                Text("Write")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun calculateByteIndexFromCharPosition(charIndex: Int): Int {
    if (charIndex in 62..77) {
        return (charIndex - 62).coerceIn(0, 15)
    }
    if (charIndex in 11..35) {
        return ((charIndex - 11) / 3).coerceIn(0, 7)
    }
    if (charIndex in 36..59) {
        return (8 + (charIndex - 36) / 3).coerceIn(8, 15)
    }
    return if (charIndex < 11) 0 else 15
}

private fun findMatches(data: ByteArray, query: String, isHex: Boolean): Pair<List<Int>, Int> {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || data.isEmpty()) return Pair(emptyList(), 0)

    val targetBytes: ByteArray = if (isHex) {
        val clean = trimmed.replace(" ", "").replace("0x", "").replace(":", "")
        if (clean.isEmpty() || clean.length % 2 != 0) {
            val validLen = clean.length - (clean.length % 2)
            if (validLen == 0) return Pair(emptyList(), 0)
            val chunked = clean.substring(0, validLen)
            ByteArray(validLen / 2) { i ->
                chunked.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return Pair(emptyList(), 0)
            }
        } else {
            try {
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (_: Exception) {
                return Pair(emptyList(), 0)
            }
        }
    } else {
        trimmed.toByteArray(Charsets.UTF_8)
    }

    if (targetBytes.isEmpty() || targetBytes.size > data.size) return Pair(emptyList(), 0)

    val matches = mutableListOf<Int>()
    val maxSearch = minOf(data.size - targetBytes.size, 500_000)
    var i = 0
    while (i <= maxSearch && matches.size < 500) {
        var matched = true
        for (j in targetBytes.indices) {
            val dByte = data[i + j]
            val tByte = targetBytes[j]
            if (!isHex) {
                val dChar = dByte.toInt().toChar().lowercaseChar()
                val tChar = tByte.toInt().toChar().lowercaseChar()
                if (dChar != tChar) {
                    matched = false
                    break
                }
            } else {
                if (dByte != tByte) {
                    matched = false
                    break
                }
            }
        }
        if (matched) {
            matches.add(i)
            i += targetBytes.size
        } else {
            i++
        }
    }
    return Pair(matches, targetBytes.size)
}

private fun parseOffset(input: String): Int? {
    val trimmed = input.trim()
    return if (trimmed.startsWith("0x", ignoreCase = true)) {
        trimmed.substring(2).toIntOrNull(16)
    } else {
        trimmed.toIntOrNull() ?: trimmed.toIntOrNull(16)
    }
}

private fun buildHexDumpText(data: ByteArray, maxRows: Int = 2048): String {
    val sb = StringBuilder()
    sb.appendLine("Offset(h)  00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  |Decoded text    |")
    sb.appendLine("---------------------------------------------------------------------------------")
    val rows = minOf((data.size + 15) / 16, maxRows)
    for (r in 0 until rows) {
        val offset = r * 16
        sb.append(String.format(Locale.US, "%08X:  ", offset))
        for (i in 0 until 16) {
            val idx = offset + i
            if (idx < data.size) {
                sb.append(String.format(Locale.US, "%02X ", data[idx]))
            } else {
                sb.append("   ")
            }
            if (i == 7) sb.append(" ")
        }
        sb.append(" |")
        for (i in 0 until 16) {
            val idx = offset + i
            if (idx < data.size) {
                val b = data[idx].toInt() and 0xFF
                if (b in 32..126) sb.append(b.toChar()) else sb.append('.')
            } else {
                sb.append(' ')
            }
        }
        sb.appendLine("|")
    }
    return sb.toString()
}

private fun buildRawHexString(data: ByteArray, maxBytes: Int = 32768): String {
    val limit = minOf(data.size, maxBytes)
    val sb = StringBuilder(limit * 2)
    for (i in 0 until limit) {
        sb.append(String.format(Locale.US, "%02X", data[i]))
    }
    return sb.toString()
}

private fun buildPrintableAscii(data: ByteArray, maxBytes: Int = 65536): String {
    val limit = minOf(data.size, maxBytes)
    val sb = StringBuilder(limit)
    for (i in 0 until limit) {
        val b = data[i].toInt() and 0xFF
        if (b in 32..126) sb.append(b.toChar()) else sb.append('.')
    }
    return sb.toString()
}
