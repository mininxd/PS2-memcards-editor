package com.ps2.memcard.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    cardName: String?,
    onOpenCard: () -> Unit,
    onCreateCard: () -> Unit,
    onFormatCard: () -> Unit,
    onShowStats: () -> Unit,
    onShowConvert: () -> Unit,
    onCreateDemoCard: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = "PS2 Memcard Editor",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (cardName != null) {
                    Text(
                        text = cardName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        actions = {
            IconButton(onClick = onOpenCard) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Open Card",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onCreateCard) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Card",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More Options"
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                if (cardName != null) {
                    DropdownMenuItem(
                        text = { Text("Card Diagnostics") },
                        leadingIcon = { Icon(Icons.Default.Analytics, null) },
                        onClick = {
                            menuExpanded = false
                            onShowStats()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Convert ECC Format") },
                        leadingIcon = { Icon(Icons.Default.Transform, null) },
                        onClick = {
                            menuExpanded = false
                            onShowConvert()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Format Card", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onFormatCard()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Load Demo Card") },
                    leadingIcon = { Icon(Icons.Default.VideogameAsset, null) },
                    onClick = {
                        menuExpanded = false
                        onCreateDemoCard()
                    }
                )
            }
        }
    )
}
