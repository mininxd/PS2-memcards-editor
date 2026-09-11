package xyz.mininxd.ps2memcards.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
import java.util.Locale

@Composable
fun HexViewerDialog(
    title: String,
    data: ByteArray,
    saveDirectoryName: String? = null,
    onSaveFile: ((ByteArray) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Editable working bytes buffer
    var workingBytes by remember(data) { mutableStateOf(data.clone()) }
    val modifiedMap = remember(data) { mutableStateMapOf<Int, Byte>() }
    var editRevision by remember { mutableIntStateOf(0) }

    val rowCount = (workingBytes.size + 15) / 16
    var selectedRowIndex by remember { mutableIntStateOf(-1) }
    var selectedByteOffset by remember { mutableIntStateOf(-1) }

    var showJumpBar by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showEditModal by remember { mutableStateOf(false) }

    val monoStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )

    fun performJump(targetOffset: Int) {
        val clamped = targetOffset.coerceIn(0, maxOf(0, workingBytes.size - 1))
        val targetRow = clamped / 16
        selectedRowIndex = targetRow
        selectedByteOffset = clamped
        coroutineScope.launch {
            listState.animateScrollToItem(targetRow)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
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
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

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
                            if (modifiedMap.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        text = "${modifiedMap.size} edited",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${String.format(Locale.US, "%,d", workingBytes.size)} bytes (0x${workingBytes.size.toString(16).uppercase()})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Undo / Revert Edits Button
                    if (modifiedMap.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                workingBytes = data.clone()
                                modifiedMap.clear()
                                editRevision++
                                Toast.makeText(context, "Edits reverted", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Revert Edits",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }

                        // Save Modified Bytes Button
                        if (onSaveFile != null) {
                            FilledTonalIconButton(
                                onClick = {
                                    val savedData = workingBytes.clone()
                                    onSaveFile(savedData)
                                    modifiedMap.clear()
                                    Toast.makeText(context, "Hacked changes saved to memory card!", Toast.LENGTH_SHORT).show()
                                },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = "Save Changes"
                                )
                            }
                        }
                    }

                    // Jump Button
                    IconButton(onClick = { showJumpBar = !showJumpBar }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Jump to Offset",
                            tint = if (showJumpBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Copy Menu Button
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Hex Dump") },
                                onClick = {
                                    menuExpanded = false
                                    val dump = buildHexDumpText(workingBytes, maxRows = 2048)
                                    clipboardManager.setText(AnnotatedString(dump))
                                    Toast.makeText(context, "Hex dump copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Raw Hex String") },
                                onClick = {
                                    menuExpanded = false
                                    val hexStr = buildRawHexString(workingBytes, maxBytes = 32768)
                                    clipboardManager.setText(AnnotatedString(hexStr))
                                    Toast.makeText(context, "Raw hex string copied", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Printable ASCII") },
                                onClick = {
                                    menuExpanded = false
                                    val asciiStr = buildPrintableAscii(workingBytes, maxBytes = 65536)
                                    clipboardManager.setText(AnnotatedString(asciiStr))
                                    Toast.makeText(context, "ASCII text copied", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }

                    // Close Button
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = jumpInput,
                                onValueChange = { jumpInput = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                placeholder = {
                                    Text(
                                        "Jump to offset (e.g. 0x100 or 256)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 12.sp
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                textStyle = monoStyle,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    val target = parseOffset(jumpInput)
                                    if (target != null) performJump(target)
                                })
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    val target = parseOffset(jumpInput)
                                    if (target != null) {
                                        performJump(target)
                                    } else {
                                        Toast.makeText(context, "Invalid offset", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(46.dp)
                            ) {
                                Text("Go", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // Preset Jump Quick Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = { performJump(0) },
                        label = { Text("Top (0x00)", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Jump to Top", modifier = Modifier.size(14.dp))
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    )
                    if (workingBytes.size > 256) {
                        AssistChip(
                            onClick = { performJump(256) },
                            label = { Text("0x0100", style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp),
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        )
                    }
                    if (workingBytes.size > 1024) {
                        AssistChip(
                            onClick = { performJump(1024) },
                            label = { Text("0x0400", style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp),
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        )
                    }
                    if (workingBytes.size > 4096) {
                        AssistChip(
                            onClick = { performJump(4096) },
                            label = { Text("0x1000", style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp),
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        )
                    }
                    AssistChip(
                        onClick = { performJump(maxOf(0, workingBytes.size - 16)) },
                        label = { Text("End", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Jump to End", modifier = Modifier.size(14.dp))
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // Scrollable Hex Viewer Body
                val horizontalScrollState = rememberScrollState()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .horizontalScroll(horizontalScrollState)
                ) {
                    Column {
                        // Sticky Header
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
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
                                val isSelected = rowIndex == selectedRowIndex

                                val normalHexColor = MaterialTheme.colorScheme.onSurface
                                val zeroHexColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                val ffHexColor = MaterialTheme.colorScheme.tertiary
                                val asciiColor = MaterialTheme.colorScheme.secondary
                                val dotColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                val hackedColor = Color(0xFFFF9800) // Amber for modified bytes

                                val rowAnnotatedString = remember(rowIndex, isSelected, editRevision) {
                                    buildAnnotatedString {
                                        // 1. Offset
                                        append(String.format(Locale.US, "%08X:  ", offset))

                                        // 2. 16 Hex bytes
                                        for (i in 0 until 16) {
                                            val byteIndex = offset + i
                                            if (byteIndex < workingBytes.size) {
                                                val b = workingBytes[byteIndex].toInt() and 0xFF
                                                val isHacked = modifiedMap.containsKey(byteIndex)
                                                val byteColor = when {
                                                    isHacked -> hackedColor
                                                    b == 0 -> zeroHexColor
                                                    b == 0xFF -> ffHexColor
                                                    b in 32..126 -> asciiColor
                                                    else -> normalHexColor
                                                }
                                                pushStyle(
                                                    SpanStyle(
                                                        color = byteColor,
                                                        fontWeight = if (isHacked) FontWeight.ExtraBold else if (b != 0) FontWeight.Medium else FontWeight.Normal,
                                                        background = if (isHacked) Color(0x33FF9800) else Color.Transparent
                                                    )
                                                )
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
                                            if (byteIndex < workingBytes.size) {
                                                val b = workingBytes[byteIndex].toInt() and 0xFF
                                                val isHacked = modifiedMap.containsKey(byteIndex)
                                                if (b in 32..126) {
                                                    pushStyle(
                                                        SpanStyle(
                                                            color = if (isHacked) hackedColor else normalHexColor,
                                                            fontWeight = if (isHacked) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    )
                                                    append(b.toChar())
                                                    pop()
                                                } else {
                                                    pushStyle(SpanStyle(color = if (isHacked) hackedColor else dotColor))
                                                    append('.')
                                                    pop()
                                                }
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
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .clickable {
                                            if (selectedRowIndex == rowIndex) {
                                                selectedRowIndex = -1
                                                selectedByteOffset = -1
                                            } else {
                                                selectedRowIndex = rowIndex
                                                selectedByteOffset = offset
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = rowAnnotatedString,
                                        style = monoStyle
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // Bottom Inspector / Hack Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (selectedByteOffset in 0 until workingBytes.size) {
                        val b = workingBytes[selectedByteOffset].toInt() and 0xFF
                        val uint16 = if (selectedByteOffset + 1 < workingBytes.size) {
                            (b) or ((workingBytes[selectedByteOffset + 1].toInt() and 0xFF) shl 8)
                        } else null
                        val uint32 = if (selectedByteOffset + 3 < workingBytes.size) {
                            (b.toLong()) or
                                    ((workingBytes[selectedByteOffset + 1].toLong() and 0xFF) shl 8) or
                                    ((workingBytes[selectedByteOffset + 2].toLong() and 0xFF) shl 16) or
                                    ((workingBytes[selectedByteOffset + 3].toLong() and 0xFF) shl 24)
                        } else null

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            // Row 1: Offset info & Hack / Edit button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "Offset: 0x%08X", selectedByteOffset),
                                        style = monoStyle.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = "(${selectedByteOffset})",
                                        style = monoStyle.copy(color = MaterialTheme.colorScheme.outline),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = { showEditModal = true },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Hack / Edit", style = MaterialTheme.typography.labelSmall)
                                    }

                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear Selection",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                selectedRowIndex = -1
                                                selectedByteOffset = -1
                                            }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Row 2: Value inspector
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(Locale.US, "Hex: 0x%02X (%d)", b, b),
                                    style = monoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
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
                                val asciiChar = if (b in 32..126) "'${b.toChar()}'" else "'.'"
                                Text(
                                    text = "Char: $asciiChar",
                                    style = monoStyle.copy(color = MaterialTheme.colorScheme.secondary),
                                    maxLines = 1,
                                    softWrap = false
                                )
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
                                text = "Tap any row to inspect & hack byte values",
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

    // Modal Dialog to Hack/Edit Byte Values
    if (showEditModal && selectedByteOffset in 0 until workingBytes.size) {
        val currentVal = workingBytes[selectedByteOffset].toInt() and 0xFF
        var inputHex by remember { mutableStateOf(String.format(Locale.US, "%02X", currentVal)) }
        var inputDec by remember { mutableStateOf(currentVal.toString()) }
        var editMode by remember { mutableStateOf("HEX") } // HEX, DEC, U16, U32

        AlertDialog(
            onDismissRequest = { showEditModal = false },
            title = {
                Text(
                    text = String.format(Locale.US, "Hack Byte at 0x%08X", selectedByteOffset),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Mode Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("HEX", "DEC", "U16", "U32").forEach { mode ->
                            val selected = editMode == mode
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        editMode = mode
                                        when (mode) {
                                            "HEX" -> inputHex = String.format(Locale.US, "%02X", workingBytes[selectedByteOffset].toInt() and 0xFF)
                                            "DEC" -> inputDec = (workingBytes[selectedByteOffset].toInt() and 0xFF).toString()
                                            "U16" -> {
                                                if (selectedByteOffset + 1 < workingBytes.size) {
                                                    val u16 = (workingBytes[selectedByteOffset].toInt() and 0xFF) or
                                                            ((workingBytes[selectedByteOffset + 1].toInt() and 0xFF) shl 8)
                                                    inputDec = u16.toString()
                                                }
                                            }
                                            "U32" -> {
                                                if (selectedByteOffset + 3 < workingBytes.size) {
                                                    val u32 = (workingBytes[selectedByteOffset].toLong() and 0xFF) or
                                                            ((workingBytes[selectedByteOffset + 1].toLong() and 0xFF) shl 8) or
                                                            ((workingBytes[selectedByteOffset + 2].toLong() and 0xFF) shl 16) or
                                                            ((workingBytes[selectedByteOffset + 3].toLong() and 0xFF) shl 24)
                                                    inputDec = u32.toString()
                                                }
                                            }
                                        }
                                    }
                            ) {
                                Text(
                                    text = mode,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (editMode == "HEX") {
                        OutlinedTextField(
                            value = inputHex,
                            onValueChange = { if (it.length <= 2) inputHex = it.uppercase() },
                            label = { Text("Hex Byte (00 - FF)") },
                            singleLine = true,
                            textStyle = monoStyle.copy(fontSize = 16.sp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = inputDec,
                            onValueChange = { inputDec = it.filter { ch -> ch.isDigit() } },
                            label = {
                                Text(
                                    when (editMode) {
                                        "DEC" -> "Decimal (0 - 255)"
                                        "U16" -> "16-bit Integer LE (0 - 65,535)"
                                        else -> "32-bit Integer LE (0 - 4,294,967,295)"
                                    }
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = monoStyle.copy(fontSize = 16.sp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Hacking Presets
                    Text(
                        text = "Quick Presets",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                inputHex = "00"
                                inputDec = "0"
                            },
                            label = { Text("0x00") },
                            shape = RoundedCornerShape(8.dp)
                        )
                        AssistChip(
                            onClick = {
                                inputHex = "FF"
                                inputDec = "255"
                            },
                            label = { Text("0xFF") },
                            shape = RoundedCornerShape(8.dp)
                        )
                        AssistChip(
                            onClick = {
                                inputDec = "9999"
                                inputHex = "0F"
                            },
                            label = { Text("9999") },
                            shape = RoundedCornerShape(8.dp)
                        )
                        AssistChip(
                            onClick = {
                                inputDec = "999999"
                            },
                            label = { Text("999999") },
                            shape = RoundedCornerShape(8.dp)
                        )
                        AssistChip(
                            onClick = {
                                val cur = inputDec.toLongOrNull() ?: 0L
                                inputDec = (cur + 1).toString()
                                inputHex = String.format(Locale.US, "%02X", ((inputHex.toIntOrNull(16) ?: 0) + 1) and 0xFF)
                            },
                            label = { Text("+1") },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            when (editMode) {
                                "HEX" -> {
                                    val byteVal = inputHex.toInt(16) and 0xFF
                                    workingBytes[selectedByteOffset] = byteVal.toByte()
                                    modifiedMap[selectedByteOffset] = byteVal.toByte()
                                }
                                "DEC" -> {
                                    val decVal = inputDec.toInt().coerceIn(0, 255)
                                    workingBytes[selectedByteOffset] = decVal.toByte()
                                    modifiedMap[selectedByteOffset] = decVal.toByte()
                                }
                                "U16" -> {
                                    val u16 = inputDec.toLong().coerceIn(0L, 65535L)
                                    val b0 = (u16 and 0xFF).toByte()
                                    val b1 = ((u16 ushr 8) and 0xFF).toByte()
                                    workingBytes[selectedByteOffset] = b0
                                    modifiedMap[selectedByteOffset] = b0
                                    if (selectedByteOffset + 1 < workingBytes.size) {
                                        workingBytes[selectedByteOffset + 1] = b1
                                        modifiedMap[selectedByteOffset + 1] = b1
                                    }
                                }
                                "U32" -> {
                                    val u32 = inputDec.toLong().coerceIn(0L, 4294967295L)
                                    for (step in 0..3) {
                                        if (selectedByteOffset + step < workingBytes.size) {
                                            val b = ((u32 ushr (step * 8)) and 0xFF).toByte()
                                            workingBytes[selectedByteOffset + step] = b
                                            modifiedMap[selectedByteOffset + step] = b
                                        }
                                    }
                                }
                            }
                            editRevision++
                            showEditModal = false
                            Toast.makeText(context, "Hacked value applied!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Invalid value: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Apply Hack")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditModal = false }) {
                    Text("Cancel")
                }
            }
        )
    }
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
