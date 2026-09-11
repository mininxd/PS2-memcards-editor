package xyz.mininxd.ps2memcards.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    cardName: String?,
    hasUnsavedChanges: Boolean = false,
    isInMemoryOnly: Boolean = false,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onOpenCard: () -> Unit,
    onCreateCard: () -> Unit,
    onSaveCard: () -> Unit,
    onSaveCardAs: () -> Unit,
    onFormatCard: () -> Unit,
    onShowStats: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = {
            if (cardName != null) {
                Text(
                    text = if (hasUnsavedChanges || isInMemoryOnly) "$cardName *" else cardName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = "PS2 Memcard Editor",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        actions = {
            if (cardName != null && (canUndo || canRedo)) {
                IconButton(
                    onClick = onUndo,
                    enabled = canUndo
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = if (canUndo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }

                IconButton(
                    onClick = onRedo,
                    enabled = canRedo
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo",
                        tint = if (canRedo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            } else {
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
            }

            if (cardName != null) {
                if (hasUnsavedChanges || isInMemoryOnly) {
                    FilledTonalIconButton(
                        onClick = onSaveCard,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Save Card"
                        )
                    }
                } else {
                    IconButton(onClick = onSaveCard) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Save Card",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (cardName != null) {
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
                    DropdownMenuItem(
                        text = { Text("Open Card") },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                        onClick = {
                            menuExpanded = false
                            onOpenCard()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("New Card") },
                        leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = {
                            menuExpanded = false
                            onCreateCard()
                        }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text("Save Card") },
                        leadingIcon = { Icon(Icons.Default.Save, null) },
                        onClick = {
                            menuExpanded = false
                            onSaveCard()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Save Card As...") },
                        leadingIcon = { Icon(Icons.Default.SaveAs, null) },
                        onClick = {
                            menuExpanded = false
                            onSaveCardAs()
                        }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text("Card Diagnostics") },
                        leadingIcon = { Icon(Icons.Default.Analytics, null) },
                        onClick = {
                            menuExpanded = false
                            onShowStats()
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
                    if (onOpenSettings != null) {
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = {
                                menuExpanded = false
                                onOpenSettings()
                            }
                        )
                    }
                }
            }
        }
    )
}
