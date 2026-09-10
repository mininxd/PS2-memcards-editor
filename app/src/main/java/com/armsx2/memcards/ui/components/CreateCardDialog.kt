package com.armsx2.memcards.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateCardDialog(
    customDirectoryName: String?,
    onSelectCustomDirectory: () -> Unit,
    onDismiss: () -> Unit,
    onSaveCard: (name: String, sizeInMB: Int, useEcc: Boolean, isFormatted: Boolean) -> Unit
) {
    var cardName by remember { mutableStateOf("mcd001.ps2") }
    var selectedSize by remember { mutableIntStateOf(8) }
    var isPreformatted by remember { mutableStateOf(false) } // Default: standard unformatted (matching PCSX2/armsx2)
    var useEcc by remember { mutableStateOf(true) }

    val sizes = listOf(8, 16, 32, 64, 128)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Create New Memory Card",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = cardName,
                    onValueChange = { cardName = it },
                    label = { Text("Card Name / Filename") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Capacity:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    sizes.forEach { size ->
                        FilterChip(
                            selected = (selectedSize == size),
                            onClick = { selectedSize = size },
                            label = { Text("${size}MB") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Card Creation Mode:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = !isPreformatted,
                        onClick = { isPreformatted = false },
                        label = { Text("Unformatted (PCSX2/ARMSX2)") }
                    )
                    FilterChip(
                        selected = isPreformatted,
                        onClick = { isPreformatted = true },
                        label = { Text("Pre-formatted") }
                    )
                }

                Text(
                    text = if (!isPreformatted) {
                        "Standard erase state (0xFF). Format inside PS2 BIOS or game (recommended for emulator compatibility)."
                    } else {
                        "Initializes PS2 filesystem now so saves can be managed immediately in this app."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newEcc = !useEcc
                            useEcc = newEcc
                            if (!newEcc && cardName.endsWith(".ps2", ignoreCase = true)) {
                                cardName = cardName.removeSuffix(".ps2") + ".bin"
                            } else if (newEcc && (cardName.endsWith(".bin", ignoreCase = true) || cardName.endsWith(".mc2", ignoreCase = true))) {
                                val base = cardName.substringBeforeLast(".")
                                cardName = "$base.ps2"
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Include ECC (Recommended for .ps2)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (useEcc) "528 B/page (Standard PCSX2 & ARMSX2 .ps2)" else "512 B/page (RAW .bin / .mc2 format)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = useEcc,
                        onCheckedChange = { checked ->
                            useEcc = checked
                            if (!checked && cardName.endsWith(".ps2", ignoreCase = true)) {
                                cardName = cardName.removeSuffix(".ps2") + ".bin"
                            } else if (checked && (cardName.endsWith(".bin", ignoreCase = true) || cardName.endsWith(".mc2", ignoreCase = true))) {
                                val base = cardName.substringBeforeLast(".")
                                cardName = "$base.ps2"
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Custom Directory Selection
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Destination Directory",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = customDirectoryName ?: "No folder set (prompts on save)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (customDirectoryName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = onSelectCustomDirectory,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (customDirectoryName == null) "Select" else "Change",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (cardName.isNotBlank()) {
                        onSaveCard(cardName.trim(), selectedSize, useEcc, isPreformatted)
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Card")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
