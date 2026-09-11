package xyz.mininxd.ps2memcards.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    var modMonth by remember { mutableStateOf(initialModified.month.toString()) }
    var modDay by remember { mutableStateOf(initialModified.day.toString()) }
    var modHour by remember { mutableStateOf(initialModified.hour.toString()) }
    var modMin by remember { mutableStateOf(initialModified.minute.toString()) }
    var modSec by remember { mutableStateOf(initialModified.second.toString()) }

    // Created date fields
    var creYear by remember { mutableStateOf(initialCreated.year.toString()) }
    var creMonth by remember { mutableStateOf(initialCreated.month.toString()) }
    var creDay by remember { mutableStateOf(initialCreated.day.toString()) }
    var creHour by remember { mutableStateOf(initialCreated.hour.toString()) }
    var creMin by remember { mutableStateOf(initialCreated.minute.toString()) }
    var creSec by remember { mutableStateOf(initialCreated.second.toString()) }

    fun buildTimestamp(yearStr: String, monthStr: String, dayStr: String, hourStr: String, minStr: String, secStr: String): Ps2Timestamp {
        val y = yearStr.toIntOrNull() ?: 2000
        val mo = monthStr.toIntOrNull() ?: 1
        val d = dayStr.toIntOrNull() ?: 1
        val h = hourStr.toIntOrNull() ?: 0
        val m = minStr.toIntOrNull() ?: 0
        val s = secStr.toIntOrNull() ?: 0
        return Ps2Timestamp.fromValues(y, mo, d, h, m, s)
    }

    val currentMod = buildTimestamp(modYear, modMonth, modDay, modHour, modMin, modSec)
    val currentCre = buildTimestamp(creYear, creMonth, creDay, creHour, creMin, creSec)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Edit Timestamps",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = saveTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Last Modified") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Created Date") }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val activeTimestamp = if (selectedTab == 0) currentMod else currentCre

                // Preview Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (selectedTab == 0) "Modified Preview:" else "Created Preview:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = activeTimestamp.toFormattedString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Date Fields (Year, Month, Day)
                Text(
                    text = "Date (YYYY - MM - DD)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = if (selectedTab == 0) modYear else creYear,
                        onValueChange = {
                            val filtered = it.filter { ch -> ch.isDigit() }.take(4)
                            if (selectedTab == 0) modYear = filtered else creYear = filtered
                        },
                        label = { Text("Year") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = if (selectedTab == 0) modMonth else creMonth,
                        onValueChange = {
                            val filtered = it.filter { ch -> ch.isDigit() }.take(2)
                            if (selectedTab == 0) modMonth = filtered else creMonth = filtered
                        },
                        label = { Text("Month") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = if (selectedTab == 0) modDay else creDay,
                        onValueChange = {
                            val filtered = it.filter { ch -> ch.isDigit() }.take(2)
                            if (selectedTab == 0) modDay = filtered else creDay = filtered
                        },
                        label = { Text("Day") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Time Fields (Hour, Minute, Second)
                Text(
                    text = "Time (24-Hour: HH : MM : SS)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = if (selectedTab == 0) modHour else creHour,
                        onValueChange = {
                            val filtered = it.filter { ch -> ch.isDigit() }.take(2)
                            if (selectedTab == 0) modHour = filtered else creHour = filtered
                        },
                        label = { Text("Hour") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = if (selectedTab == 0) modMin else creMin,
                        onValueChange = {
                            val filtered = it.filter { ch -> ch.isDigit() }.take(2)
                            if (selectedTab == 0) modMin = filtered else creMin = filtered
                        },
                        label = { Text("Min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = if (selectedTab == 0) modSec else creSec,
                        onValueChange = {
                            val filtered = it.filter { ch -> ch.isDigit() }.take(2)
                            if (selectedTab == 0) modSec = filtered else creSec = filtered
                        },
                        label = { Text("Sec") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quick Presets
                Text(
                    text = "Quick Presets",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {
                            val now = Ps2Timestamp.now()
                            if (selectedTab == 0) {
                                modYear = now.year.toString()
                                modMonth = now.month.toString()
                                modDay = now.day.toString()
                                modHour = now.hour.toString()
                                modMin = now.minute.toString()
                                modSec = now.second.toString()
                            } else {
                                creYear = now.year.toString()
                                creMonth = now.month.toString()
                                creDay = now.day.toString()
                                creHour = now.hour.toString()
                                creMin = now.minute.toString()
                                creSec = now.second.toString()
                            }
                        },
                        label = { Text("Current Time (Now)") },
                        leadingIcon = {
                            Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp))
                        },
                        shape = RoundedCornerShape(8.dp)
                    )

                    AssistChip(
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
                        label = {
                            Text(if (selectedTab == 0) "Copy from Created" else "Copy from Modified")
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp))
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
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
                Text("Save Timestamps", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
