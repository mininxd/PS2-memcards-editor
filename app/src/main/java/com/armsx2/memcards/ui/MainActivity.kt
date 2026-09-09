package com.armsx2.memcards.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.armsx2.memcards.core.Ps2Save
import com.armsx2.memcards.ui.components.AppHeader
import com.armsx2.memcards.ui.components.CardStatsDialog
import com.armsx2.memcards.ui.components.ConvertCardDialog
import com.armsx2.memcards.ui.components.CreateCardDialog
import com.armsx2.memcards.ui.components.FormatCardDialog
import com.armsx2.memcards.ui.components.HexViewerDialog
import com.armsx2.memcards.ui.components.SaveDetailModal
import com.armsx2.memcards.ui.screens.EmptyStateScreen
import com.armsx2.memcards.ui.screens.MainScreen
import com.armsx2.memcards.ui.theme.PS2MemcardTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MemcardViewModel by viewModels()

    private var pendingExportPsuBytes: ByteArray? = null
    private var pendingExportZipBytes: ByteArray? = null

    private val openCardLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { loadCardFromUri(it) }
    }

    private val importPsuLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openInputStream(it)?.use { stream ->
                    val bytes = stream.readBytes()
                    viewModel.importPsu(bytes)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to read PSU file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val exportPsuLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let { targetUri ->
            pendingExportPsuBytes?.let { bytes ->
                try {
                    contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(bytes)
                    }
                    Toast.makeText(this, "PSU save exported successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingExportPsuBytes = null
    }

    private val exportZipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        uri?.let { targetUri ->
            pendingExportZipBytes?.let { bytes ->
                try {
                    contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(bytes)
                    }
                    Toast.makeText(this, "ZIP archive exported successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingExportZipBytes = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle opening file from external intent
        intent?.data?.let { uri ->
            loadCardFromUri(uri)
        }

        setContent {
            PS2MemcardTheme {
                val uiState by viewModel.uiState.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val filterType by viewModel.filterType.collectAsState()
                val sortBy by viewModel.sortBy.collectAsState()
                val selectedSave by viewModel.selectedSave.collectAsState()
                val showCreateDialog by viewModel.showCreateDialog.collectAsState()
                val showFormatDialog by viewModel.showFormatDialog.collectAsState()
                val showStatsDialog by viewModel.showStatsDialog.collectAsState()
                val showConvertDialog by viewModel.showConvertDialog.collectAsState()
                val hexViewerData by viewModel.hexViewerData.collectAsState()
                val snackbarMessage by viewModel.snackbarMessage.collectAsState()

                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(snackbarMessage) {
                    snackbarMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearSnackbar()
                    }
                }

                val currentCardName = (uiState as? CardUiState.Loaded)?.cardName

                Scaffold(
                    topBar = {
                        AppHeader(
                            cardName = currentCardName,
                            onOpenCard = { openCardLauncher.launch(arrayOf("*/*")) },
                            onCreateCard = { viewModel.setShowCreateDialog(true) },
                            onFormatCard = { viewModel.setShowFormatDialog(true) },
                            onShowStats = { viewModel.setShowStatsDialog(true) },
                            onShowConvert = { viewModel.setShowConvertDialog(true) },
                            onCreateDemoCard = { viewModel.createDemoCard() }
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (val state = uiState) {
                            is CardUiState.Empty -> {
                                EmptyStateScreen(
                                    onOpenCard = { openCardLauncher.launch(arrayOf("*/*")) },
                                    onCreateCard = { viewModel.setShowCreateDialog(true) },
                                    onCreateDemoCard = { viewModel.createDemoCard() }
                                )
                            }
                            is CardUiState.Loading -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            is CardUiState.Loaded -> {
                                MainScreen(
                                    saves = state.saves,
                                    stats = state.stats,
                                    searchQuery = searchQuery,
                                    onSearchChange = { viewModel.setSearchQuery(it) },
                                    filterType = filterType,
                                    onFilterChange = { viewModel.setFilterType(it) },
                                    sortBy = sortBy,
                                    onSortChange = { viewModel.setSortBy(it) },
                                    onSaveClick = { viewModel.selectSave(it) },
                                    onExportPsu = { triggerExportPsu(it) },
                                    onExportZip = { triggerExportZip(it) },
                                    onDeleteSave = { viewModel.deleteSave(it.directoryName) },
                                    onImportPsu = { importPsuLauncher.launch(arrayOf("*/*")) }
                                )
                            }
                            is CardUiState.Error -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(onClick = { openCardLauncher.launch(arrayOf("*/*")) }) {
                                            Text("Open Another Card")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Modals & Dialogs
                selectedSave?.let { save ->
                    SaveDetailModal(
                        save = save,
                        onDismiss = { viewModel.selectSave(null) },
                        onExportPsu = {
                            viewModel.selectSave(null)
                            triggerExportPsu(save)
                        },
                        onExportZip = {
                            viewModel.selectSave(null)
                            triggerExportZip(save)
                        },
                        onDelete = {
                            viewModel.selectSave(null)
                            viewModel.deleteSave(save.directoryName)
                        },
                        onInspectFileHex = { file ->
                            val data = file.data ?: (uiState as? CardUiState.Loaded)?.memcard?.getSaveFileBytes(save.directoryName, file.name) ?: ByteArray(0)
                            viewModel.openHexViewer(file.name, data)
                        }
                    )
                }

                if (showCreateDialog) {
                    CreateCardDialog(
                        onDismiss = { viewModel.setShowCreateDialog(false) },
                        onCreate = { name, size, ecc ->
                            viewModel.setShowCreateDialog(false)
                            viewModel.createNewCard(name, size, ecc)
                        }
                    )
                }

                if (showFormatDialog) {
                    FormatCardDialog(
                        cardName = currentCardName ?: "Memory Card",
                        onDismiss = { viewModel.setShowFormatDialog(false) },
                        onConfirmFormat = {
                            viewModel.setShowFormatDialog(false)
                            viewModel.formatCurrentCard()
                        }
                    )
                }

                if (showStatsDialog) {
                    (uiState as? CardUiState.Loaded)?.let { loaded ->
                        CardStatsDialog(
                            superBlock = loaded.memcard.superBlock,
                            stats = loaded.stats,
                            onDismiss = { viewModel.setShowStatsDialog(false) }
                        )
                    }
                }

                if (showConvertDialog) {
                    (uiState as? CardUiState.Loaded)?.let { loaded ->
                        ConvertCardDialog(
                            currentHasEcc = loaded.memcard.hasEcc,
                            onDismiss = { viewModel.setShowConvertDialog(false) },
                            onConfirmConvert = { targetHasEcc ->
                                viewModel.setShowConvertDialog(false)
                                viewModel.convertEcc(targetHasEcc)
                            }
                        )
                    }
                }

                hexViewerData?.let { (title, data) ->
                    HexViewerDialog(
                        title = title,
                        data = data,
                        onDismiss = { viewModel.closeHexViewer() }
                    )
                }
            }
        }
    }

    private fun loadCardFromUri(uri: Uri) {
        val fileName = queryFileName(uri) ?: "MemoryCard.ps2"
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                viewModel.loadCardFromBytes(fileName, bytes, uri)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read card: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerExportPsu(save: Ps2Save) {
        val bytes = viewModel.exportPsu(save.directoryName)
        if (bytes != null) {
            pendingExportPsuBytes = bytes
            exportPsuLauncher.launch("${save.directoryName}.psu")
        } else {
            Toast.makeText(this, "Failed to export PSU", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerExportZip(save: Ps2Save) {
        val bytes = viewModel.exportZip(save.directoryName)
        if (bytes != null) {
            pendingExportZipBytes = bytes
            exportZipLauncher.launch("${save.directoryName}.zip")
        } else {
            Toast.makeText(this, "Failed to export ZIP", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        return cursor.getString(index)
                    }
                }
            }
        }
        return uri.lastPathSegment
    }
}
