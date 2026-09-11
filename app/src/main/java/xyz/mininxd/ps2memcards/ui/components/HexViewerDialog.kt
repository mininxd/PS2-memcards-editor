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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val rowCount = (data.size + 15) / 16
    var selectedRowIndex by remember { mutableIntStateOf(-1) }
    var selectedByteOffset by remember { mutableIntStateOf(-1) }

    var showJumpBar by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    val monoStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )

    fun performJump(targetOffset: Int) {
        val clamped = targetOffset.coerceIn(0, maxOf(0, data.size - 1))
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
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${String.format(Locale.US, "%,d", data.size)} bytes (0x${data.size.toString(16).uppercase()})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
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
                                    val dump = buildHexDumpText(data, maxRows = 2048)
                                    clipboardManager.setText(AnnotatedString(dump))
                                    Toast.makeText(context, "Hex dump copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Raw Hex String") },
                                onClick = {
                                    menuExpanded = false
                                    val hexStr = buildRawHexString(data, maxBytes = 32768)
                                    clipboardManager.setText(AnnotatedString(hexStr))
                                    Toast.makeText(context, "Raw hex string copied", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Printable ASCII") },
                                onClick = {
                                    menuExpanded = false
                                    val asciiStr = buildPrintableAscii(data, maxBytes = 65536)
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
                                placeholder = { Text("Offset (e.g. 0x100 or 256)", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
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
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Go", style = MaterialTheme.typography.labelMedium)
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(
                                onClick = { performJump(0) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Jump to Top", modifier = Modifier.size(18.dp))
                            }

                            IconButton(
                                onClick = { performJump(maxOf(0, data.size - 1)) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Jump to End", modifier = Modifier.size(18.dp))
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
                                val isSelected = rowIndex == selectedRowIndex

                                val offsetColor = MaterialTheme.colorScheme.primary
                                val normalHexColor = MaterialTheme.colorScheme.onSurface
                                val zeroHexColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                val ffHexColor = MaterialTheme.colorScheme.tertiary
                                val asciiColor = MaterialTheme.colorScheme.secondary
                                val dotColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

                                val rowAnnotatedString = remember(rowIndex, isSelected) {
                                    buildAnnotatedString {
                                        // 1. Offset
                                        append(String.format(Locale.US, "%08X:  ", offset))

                                        // 2. 16 Hex bytes
                                        for (i in 0 until 16) {
                                            val byteIndex = offset + i
                                            if (byteIndex < data.size) {
                                                val b = data[byteIndex].toInt() and 0xFF
                                                val byteColor = when {
                                                    b == 0 -> zeroHexColor
                                                    b == 0xFF -> ffHexColor
                                                    b in 32..126 -> asciiColor
                                                    else -> normalHexColor
                                                }
                                                pushStyle(SpanStyle(color = byteColor, fontWeight = if (b != 0) FontWeight.Medium else FontWeight.Normal))
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
                                            if (byteIndex < data.size) {
                                                val b = data[byteIndex].toInt() and 0xFF
                                                if (b in 32..126) {
                                                    pushStyle(SpanStyle(color = normalHexColor, fontWeight = FontWeight.Normal))
                                                    append(b.toChar())
                                                    pop()
                                                } else {
                                                    pushStyle(SpanStyle(color = dotColor))
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Bottom Data Inspector / Status Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (selectedByteOffset in data.indices) {
                        val b = data[selectedByteOffset].toInt() and 0xFF
                        val int8 = data[selectedByteOffset].toInt()
                        val uint16 = if (selectedByteOffset + 1 < data.size) {
                            (b) or ((data[selectedByteOffset + 1].toInt() and 0xFF) shl 8)
                        } else null
                        val uint32 = if (selectedByteOffset + 3 < data.size) {
                            (b.toLong()) or
                            ((data[selectedByteOffset + 1].toLong() and 0xFF) shl 8) or
                            ((data[selectedByteOffset + 2].toLong() and 0xFF) shl 16) or
                            ((data[selectedByteOffset + 3].toLong() and 0xFF) shl 24)
                        } else null

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            // Row 1: Offset info & row count / dismiss
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
                                    Text(
                                        text = "${rowCount} lines",
                                        style = monoStyle.copy(color = MaterialTheme.colorScheme.outline),
                                        maxLines = 1,
                                        softWrap = false
                                    )
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

                            // Row 2: Value inspector (horizontally scrollable, never wraps characters)
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
                                text = "Tap any row to inspect byte values",
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
