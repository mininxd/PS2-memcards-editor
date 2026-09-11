package xyz.mininxd.ps2memcards.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.mininxd.ps2memcards.core.Ps2Timestamp

@Composable
fun EditTimestampsDialog(
    saveTitle: String,
    initialCreated: Ps2Timestamp,
    initialModified: Ps2Timestamp,
    onDismiss: () -> Unit,
    onSave: (created: Ps2Timestamp, modified: Ps2Timestamp) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Modified, 1 = Created

    // Modified date fields
    var modYear by remember { mutableStateOf(initialModified.year.toString()) }
    var modMonth by remember { mutableStateOf(initialModified.month.toString().padStart(2, '0')) }
    var modDay by remember { mutableStateOf(initialModified.day.toString().padStart(2, '0')) }
    var modHour by remember { mutableStateOf(initialModified.hour.toString().padStart(2, '0')) }
    var modMin by remember { mutableStateOf(initialModified.minute.toString().padStart(2, '0')) }
    var modSec by remember { mutableStateOf(initialModified.second.toString().padStart(2, '0')) }

    // Created date fields
    var creYear by remember { mutableStateOf(initialCreated.year.toString()) }
    var creMonth by remember { mutableStateOf(initialCreated.month.toString().padStart(2, '0')) }
    var creDay by remember { mutableStateOf(initialCreated.day.toString().padStart(2, '0')) }
    var creHour by remember { mutableStateOf(initialCreated.hour.toString().padStart(2, '0')) }
    var creMin by remember { mutableStateOf(initialCreated.minute.toString().padStart(2, '0')) }
    var creSec by remember { mutableStateOf(initialCreated.second.toString().padStart(2, '0')) }

    fun buildTimestamp(yearStr: String, monthStr: String, dayStr: String, hourStr: String, minStr: String, secStr: String): Ps2Timestamp {
        val y = yearStr.toIntOrNull() ?: 2000
        val mo = monthStr.toIntOrNull()?.coerceIn(1, 12) ?: 1
        val d = dayStr.toIntOrNull()?.coerceIn(1, 31) ?: 1
        val h = hourStr.toIntOrNull()?.coerceIn(0, 23) ?: 0
        val m = minStr.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val s = secStr.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return Ps2Timestamp.fromValues(y, mo, d, h, m, s)
    }

    val currentMod = buildTimestamp(modYear, modMonth, modDay, modHour, modMin, modSec)
    val currentCre = buildTimestamp(creYear, creMonth, creDay, creHour, creMin, creSec)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Edit Timestamps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (saveTitle.isNotBlank()) {
                        Text(
                            text = saveTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Last Modified", style = MaterialTheme.typography.labelMedium) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Created Date", style = MaterialTheme.typography.labelMedium) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val activeTimestamp = if (selectedTab == 0) currentMod else currentCre

                // Preview Banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (selectedTab == 0) "Modified Preview" else "Created Preview",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = activeTimestamp.toFormattedString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Date Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Date",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(36.dp)
                    )
                    CompactNumberField(
                        value = if (selectedTab == 0) modYear else creYear,
                        onValueChange = { if (selectedTab == 0) modYear = it else creYear = it },
                        placeholder = "YYYY",
                        maxDigits = 4,
                        modifier = Modifier.weight(1.3f)
                    )
                    Text(
                        text = "-",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline
                    )
                    CompactNumberField(
                        value = if (selectedTab == 0) modMonth else creMonth,
                        onValueChange = { if (selectedTab == 0) modMonth = it else creMonth = it },
                        placeholder = "MM",
                        maxDigits = 2,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "-",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline
                    )
                    CompactNumberField(
                        value = if (selectedTab == 0) modDay else creDay,
                        onValueChange = { if (selectedTab == 0) modDay = it else creDay = it },
                        placeholder = "DD",
                        maxDigits = 2,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Time Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Time",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(36.dp)
                    )
                    CompactNumberField(
                        value = if (selectedTab == 0) modHour else creHour,
                        onValueChange = { if (selectedTab == 0) modHour = it else creHour = it },
                        placeholder = "HH",
                        maxDigits = 2,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline
                    )
                    CompactNumberField(
                        value = if (selectedTab == 0) modMin else creMin,
                        onValueChange = { if (selectedTab == 0) modMin = it else creMin = it },
                        placeholder = "MM",
                        maxDigits = 2,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline
                    )
                    CompactNumberField(
                        value = if (selectedTab == 0) modSec else creSec,
                        onValueChange = { if (selectedTab == 0) modSec = it else creSec = it },
                        placeholder = "SS",
                        maxDigits = 2,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val now = Ps2Timestamp.now()
                            if (selectedTab == 0) {
                                modYear = now.year.toString()
                                modMonth = now.month.toString().padStart(2, '0')
                                modDay = now.day.toString().padStart(2, '0')
                                modHour = now.hour.toString().padStart(2, '0')
                                modMin = now.minute.toString().padStart(2, '0')
                                modSec = now.second.toString().padStart(2, '0')
                            } else {
                                creYear = now.year.toString()
                                creMonth = now.month.toString().padStart(2, '0')
                                creDay = now.day.toString().padStart(2, '0')
                                creHour = now.hour.toString().padStart(2, '0')
                                creMin = now.minute.toString().padStart(2, '0')
                                creSec = now.second.toString().padStart(2, '0')
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Schedule, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Current Time", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            if (selectedTab == 0) {
                                modYear = creYear
                                modMonth = creMonth
                                modDay = creDay
                                modHour = creHour
                                modMin = creMin
                                modSec = creSec
                            } else {
                                creYear = modYear
                                creMonth = modMonth
                                creDay = modDay
                                creHour = modHour
                                creMin = modMin
                                creSec = modSec
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Sync, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (selectedTab == 0) "Copy Created" else "Copy Modified", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalCreated = buildTimestamp(creYear, creMonth, creDay, creHour, creMin, creSec)
                    val finalModified = buildTimestamp(modYear, modMonth, modDay, modHour, modMin, modSec)
                    onSave(finalCreated, finalModified)
                }
            ) {
                Text("Save Timestamps", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

@Composable
private fun CompactNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    maxDigits: Int,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() }.take(maxDigits)
            onValueChange(filtered)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
                innerTextField()
            }
        }
    )
}
