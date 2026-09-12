package xyz.mininxd.ps2memcards.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.mininxd.ps2memcards.core.Ps2Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
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
    var modYear by remember { mutableIntStateOf(initialModified.year) }
    var modMonth by remember { mutableIntStateOf(initialModified.month) }
    var modDay by remember { mutableIntStateOf(initialModified.day) }
    var modHour by remember { mutableIntStateOf(initialModified.hour) }
    var modMinute by remember { mutableIntStateOf(initialModified.minute) }
    var modSecond by remember { mutableIntStateOf(initialModified.second) }

    // Created date fields
    var creYear by remember { mutableIntStateOf(initialCreated.year) }
    var creMonth by remember { mutableIntStateOf(initialCreated.month) }
    var creDay by remember { mutableIntStateOf(initialCreated.day) }
    var creHour by remember { mutableIntStateOf(initialCreated.hour) }
    var creMinute by remember { mutableIntStateOf(initialCreated.minute) }
    var creSecond by remember { mutableIntStateOf(initialCreated.second) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val activeYear = if (selectedTab == 0) modYear else creYear
    val activeMonth = if (selectedTab == 0) modMonth else creMonth
    val activeDay = if (selectedTab == 0) modDay else creDay
    val activeHour = if (selectedTab == 0) modHour else creHour
    val activeMinute = if (selectedTab == 0) modMinute else creMinute
    val activeSecond = if (selectedTab == 0) modSecond else creSecond

    val activeTimestamp = remember(activeYear, activeMonth, activeDay, activeHour, activeMinute, activeSecond) {
        Ps2Timestamp.fromValues(activeYear, activeMonth, activeDay, activeHour, activeMinute, activeSecond)
    }

    fun addDays(days: Int) {
        val cal = Calendar.getInstance().apply {
            clear()
            set(activeYear, activeMonth - 1, activeDay)
            add(Calendar.DAY_OF_MONTH, days)
        }
        val newY = cal.get(Calendar.YEAR).coerceIn(1990, 2040)
        val newM = cal.get(Calendar.MONTH) + 1
        val newD = cal.get(Calendar.DAY_OF_MONTH)
        if (selectedTab == 0) {
            modYear = newY; modMonth = newM; modDay = newD
        } else {
            creYear = newY; creMonth = newM; creDay = newD
        }
    }

    // Material 3 Date Picker Dialog
    if (showDatePicker) {
        val initialUtcMillis = remember(activeYear, activeMonth, activeDay) {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(Calendar.YEAR, activeYear)
                set(Calendar.MONTH, activeMonth - 1)
                set(Calendar.DAY_OF_MONTH, activeDay)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialUtcMillis,
            yearRange = 1990..2040
        )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis ?: initialUtcMillis
                        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = millis
                        }
                        val newY = cal.get(Calendar.YEAR)
                        val newM = cal.get(Calendar.MONTH) + 1
                        val newD = cal.get(Calendar.DAY_OF_MONTH)
                        if (selectedTab == 0) {
                            modYear = newY; modMonth = newM; modDay = newD
                        } else {
                            creYear = newY; creMonth = newM; creDay = newD
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Material 3 Time Picker Dialog
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = activeHour,
            initialMinute = activeMinute,
            is24Hour = true
        )
        var pickerSecond by remember { mutableIntStateOf(activeSecond) }

        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Select Time (24h)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = timePickerState)

                    Spacer(modifier = Modifier.height(14.dp))

                    // Seconds Fine-Tuning
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Seconds",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "00-59",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { pickerSecond = 0 },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text(":00", style = MaterialTheme.typography.labelSmall)
                                }

                                FilledTonalIconButton(
                                    onClick = { pickerSecond = if (pickerSecond > 0) pickerSecond - 1 else 59 },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "-1s", modifier = Modifier.size(16.dp))
                                }

                                Text(
                                    text = String.format(Locale.US, "%02d", pickerSecond),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )

                                FilledTonalIconButton(
                                    onClick = { pickerSecond = (pickerSecond + 1) % 60 },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "+1s", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedTab == 0) {
                            modHour = timePickerState.hour
                            modMinute = timePickerState.minute
                            modSecond = pickerSecond
                        } else {
                            creHour = timePickerState.hour
                            creMinute = timePickerState.minute
                            creSecond = pickerSecond
                        }
                        showTimePicker = false
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Confirm", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.EditCalendar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
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
                        text = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text(
                                    "Last Modified",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    String.format(Locale.US, "%04d-%02d-%02d", modYear, modMonth, modDay),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text(
                                    "Created Date",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    String.format(Locale.US, "%04d-%02d-%02d", creYear, creMonth, creDay),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Hero Timestamp Preview Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (selectedTab == 0) "MODIFIED PREVIEW" else "CREATED PREVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = "PS2 (UTC+9)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = activeTimestamp.toFormattedString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Date Picker Tile
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showDatePicker = true },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.CalendarMonth,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Date",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = formatFriendlyDate(activeYear, activeMonth, activeDay),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                FilledTonalButton(
                                    onClick = { showDatePicker = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.Today, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Pick", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Time Picker Tile
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showTimePicker = true },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.AccessTime,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Time (24h)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = formatFriendlyTime(activeHour, activeMinute, activeSecond),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                FilledTonalButton(
                                    onClick = { showTimePicker = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Pick", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Actions Header
                Text(
                    text = "Quick Presets",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                )

                // Quick Presets Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            val now = Ps2Timestamp.now()
                            if (selectedTab == 0) {
                                modYear = now.year; modMonth = now.month; modDay = now.day
                                modHour = now.hour; modMinute = now.minute; modSecond = now.second
                            } else {
                                creYear = now.year; creMonth = now.month; creDay = now.day
                                creHour = now.hour; creMinute = now.minute; creSecond = now.second
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) },
                        label = { Text("Current Time", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp)
                    )

                    AssistChip(
                        onClick = {
                            if (selectedTab == 0) {
                                modYear = creYear; modMonth = creMonth; modDay = creDay
                                modHour = creHour; modMinute = creMinute; modSecond = creSecond
                            } else {
                                creYear = modYear; creMonth = modMonth; creDay = modDay
                                creHour = modHour; creMinute = modMinute; creSecond = modSecond
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp)) },
                        label = { Text(if (selectedTab == 0) "Copy Created" else "Copy Modified", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp)
                    )

                    AssistChip(
                        onClick = {
                            val now = Ps2Timestamp.now()
                            modYear = now.year; modMonth = now.month; modDay = now.day
                            modHour = now.hour; modMinute = now.minute; modSecond = now.second
                            creYear = now.year; creMonth = now.month; creDay = now.day
                            creHour = now.hour; creMinute = now.minute; creSecond = now.second
                        },
                        leadingIcon = { Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Set Both to Now", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp)
                    )

                    AssistChip(
                        onClick = { addDays(-1) },
                        leadingIcon = { Icon(Icons.Default.Remove, null, modifier = Modifier.size(14.dp)) },
                        label = { Text("-1 Day", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp)
                    )

                    AssistChip(
                        onClick = { addDays(1) },
                        leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp)) },
                        label = { Text("+1 Day", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp)
                    )

                    AssistChip(
                        onClick = {
                            if (selectedTab == 0) {
                                modYear = 2000; modMonth = 3; modDay = 4
                                modHour = 12; modMinute = 0; modSecond = 0
                            } else {
                                creYear = 2000; creMonth = 3; creDay = 4
                                creHour = 12; creMinute = 0; creSecond = 0
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.SportsEsports, null, modifier = Modifier.size(16.dp)) },
                        label = { Text("PS2 Launch (2000)", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalCreated = Ps2Timestamp.fromValues(creYear, creMonth, creDay, creHour, creMinute, creSecond)
                    val finalModified = Ps2Timestamp.fromValues(modYear, modMonth, modDay, modHour, modMinute, modSecond)
                    onSave(finalCreated, finalModified)
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
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

private fun formatFriendlyDate(year: Int, month: Int, day: Int): String {
    val cal = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, maxOf(0, month - 1))
        set(Calendar.DAY_OF_MONTH, maxOf(1, day))
    }
    val dateFormat = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
    return dateFormat.format(cal.time)
}

private fun formatFriendlyTime(hour: Int, minute: Int, second: Int): String {
    return String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second)
}
