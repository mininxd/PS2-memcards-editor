package com.armsx2.memcards.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
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
    filterType: FilterType,
    onFilterChange: (FilterType) -> Unit,
    sortBy: SortBy,
    onSortChange: (SortBy) -> Unit,
    onSaveClick: (Ps2Save) -> Unit,
    onExportPsu: (Ps2Save) -> Unit,
    onExportZip: (Ps2Save) -> Unit,
    onDeleteSave: (Ps2Save) -> Unit,
    onImportPsu: () -> Unit,
    hasUnsavedChanges: Boolean = false,
    isInMemoryOnly: Boolean = false,
    onSaveCard: () -> Unit = {},
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImportPsu,
                icon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                text = { Text("Import Save") },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (hasUnsavedChanges || isInMemoryOnly) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
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

            // Storage Bar
            StorageBar(
                stats = stats,
                saveCount = saves.size
            )

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search saves by title or code...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = (filterType == FilterType.ALL),
                    onClick = { onFilterChange(FilterType.ALL) },
                    label = { Text("All (${saves.size})") }
                )
                FilterChip(
                    selected = (filterType == FilterType.PS2_ONLY),
                    onClick = { onFilterChange(FilterType.PS2_ONLY) },
                    label = { Text("PS2") }
                )
                FilterChip(
                    selected = (filterType == FilterType.PROTECTED),
                    onClick = { onFilterChange(FilterType.PROTECTED) },
                    label = { Text("Protected") }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Save List
            if (filteredSaves.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No saves matching '$searchQuery'" else "No saves in this memory card",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
