package com.armsx2.memcards.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.armsx2.memcards.core.CardStats
import com.armsx2.memcards.core.Ps2Save
import com.armsx2.memcards.ui.FilterType
import com.armsx2.memcards.ui.SortBy
import com.armsx2.memcards.ui.components.SaveCard
import com.armsx2.memcards.ui.components.StorageBar

@Composable
fun MainScreen(
    saves: List<Ps2Save>,
    stats: CardStats,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filterType: FilterType = FilterType.ALL,
    onFilterChange: (FilterType) -> Unit = {},
    sortBy: SortBy = SortBy.NAME_ASC,
    onSortChange: (SortBy) -> Unit = {},
    onSaveClick: (Ps2Save) -> Unit,
    onExportPsu: (Ps2Save) -> Unit,
    onExportZip: (Ps2Save) -> Unit,
    onDeleteSave: (Ps2Save) -> Unit,
    onImportPsu: () -> Unit,
    hasUnsavedChanges: Boolean = false,
    isInMemoryOnly: Boolean = false,
    onSaveCard: () -> Unit = {},
    onFormatCard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val filteredSaves = saves.filter { save ->
        val query = searchQuery.trim().lowercase()
        val matchesQuery = query.isEmpty() ||
                save.displayTitle.lowercase().contains(query) ||
                save.directoryName.lowercase().contains(query) ||
                save.subtitle.lowercase().contains(query)

        val matchesFilter = when (filterType) {
            FilterType.ALL -> true
            FilterType.PS2_ONLY -> !save.isPsx
            FilterType.PS1_ONLY -> save.isPsx
            FilterType.PROTECTED -> save.isProtected
        }

        matchesQuery && matchesFilter
    }.let { list ->
        when (sortBy) {
            SortBy.NAME_ASC -> list.sortedBy { it.displayTitle.lowercase() }
            SortBy.DATE_DESC -> list.sortedByDescending { it.modifiedDate }
            SortBy.SIZE_DESC -> list.sortedByDescending { it.sizeInBytes }
        }
    }

    val listState = rememberLazyListState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (!stats.isFormatted) {
                ExtendedFloatingActionButton(
                    onClick = onFormatCard,
                    expanded = true,
                    icon = { Icon(Icons.Default.Save, contentDescription = null) },
                    text = { Text("Format Card") },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = onImportPsu,
                    expanded = true,
                    icon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                    text = { Text("Import Save") },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Top Info: Unsaved changes banner & Card Storage stats
            if (hasUnsavedChanges || isInMemoryOnly) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isInMemoryOnly) "Unsaved Card (Stored in memory)" else "Unsaved changes on card",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Button(
                            onClick = onSaveCard,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Unformatted Card Warning Banner
            if (!stats.isFormatted) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Unformatted Memory Card",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Card is in clean flash-erased state (0xFF). Format now to use in this app, or format inside PS2 BIOS.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onFormatCard,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Format", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Storage Bar
            StorageBar(
                stats = stats,
                saveCount = saves.size
            )

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search saves by title or code...", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )

            // Save List
            if (filteredSaves.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (!stats.isFormatted) {
                            "Card is unformatted.\nFormat it to start storing saves."
                        } else if (searchQuery.isNotEmpty()) {
                            "No saves matching '$searchQuery'"
                        } else {
                            "No saves in this memory card"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(filteredSaves, key = { it.directoryName }) { save ->
                        SaveCard(
                            save = save,
                            onClick = { onSaveClick(save) },
                            onExportPsu = { onExportPsu(save) },
                            onExportZip = { onExportZip(save) },
                            onDelete = { onDeleteSave(save) }
                        )
                    }
                }
            }
        }
    }
}
