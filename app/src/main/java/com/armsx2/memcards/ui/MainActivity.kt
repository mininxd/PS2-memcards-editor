package com.armsx2.memcards.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.armsx2.memcards.core.MemcardFormatter
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
    private var pendingExportMaxBytes: ByteArray? = null
    private var pendingExportZipBytes: ByteArray? = null
    private var pendingSaveCardBytes: ByteArray? = null
    private var pendingSaveCardName: String? = null
    private var pendingActionAfterSave: (() -> Unit)? = null

    private val openCardLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { loadCardFromUri(it) }
    }

    private val selectSaveDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            pendingSaveCardBytes = null
            pendingSaveCardName = null
            pendingActionAfterSave = null
            return@registerForActivityResult
        }

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val docDir = DocumentFile.fromTreeUri(this, uri)
        val dirName = docDir?.name ?: uri.lastPathSegment ?: "Selected Folder"
        val prefs = getSharedPreferences("memcard_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("custom_dir_uri", uri.toString())
            .putString("custom_dir_name", dirName)
            .apply()
        viewModel.setCustomDirectory(uri, dirName)

        val bytes = pendingSaveCardBytes ?: viewModel.getRawCardData()
        val cardName = pendingSaveCardName ?: (viewModel.uiState.value as? CardUiState.Loaded)?.cardName ?: "mcd001.ps2"

        if (bytes != null) {
            try {
                val targetFile = docDir?.findFile(cardName) ?: docDir?.createFile("application/octet-stream", cardName)
                if (targetFile != null) {
                    contentResolver.openOutputStream(targetFile.uri, "wt")?.use { out ->
                        out.write(bytes)
                    }
                    val currentState = viewModel.uiState.value
                    if (currentState is CardUiState.Loaded) {
                        viewModel.markCardSaved(targetFile.uri, cardName)
                    } else {
                        viewModel.loadCardFromBytes(cardName, bytes, targetFile.uri)
                    }
                    Toast.makeText(this, "Saved $cardName to $dirName successfully!", Toast.LENGTH_SHORT).show()
                    val action = pendingActionAfterSave
                    pendingActionAfterSave = null
                    action?.invoke()
                } else {
                    Toast.makeText(this, "Could not create $cardName in $dirName", Toast.LENGTH_LONG).show()
                    pendingActionAfterSave = null
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to save card: ${e.message}", Toast.LENGTH_LONG).show()
                pendingActionAfterSave = null
            }
        }
        pendingSaveCardBytes = null
        pendingSaveCardName = null
    }

    private val importSaveLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openInputStream(it)?.use { stream ->
                    val bytes = stream.readBytes()
                    viewModel.importSave(bytes)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to read save file: ${e.message}", Toast.LENGTH_LONG).show()
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

    private val exportMaxLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let { targetUri ->
            pendingExportMaxBytes?.let { bytes ->
                try {
                    contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(bytes)
                    }
                    Toast.makeText(this, "Action Replay MAX save exported successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingExportMaxBytes = null
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

        // Load persisted custom directory
        val prefs = getSharedPreferences("memcard_prefs", Context.MODE_PRIVATE)
        val savedDirUri = prefs.getString("custom_dir_uri", null)
        val savedDirName = prefs.getString("custom_dir_name", null)
        if (savedDirUri != null) {
            viewModel.setCustomDirectory(Uri.parse(savedDirUri), savedDirName)
        }

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
                val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
                val customDirectoryName by viewModel.customDirectoryName.collectAsState()

                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(snackbarMessage) {
                    snackbarMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearSnackbar()
                    }
                }

                val currentCardName = (uiState as? CardUiState.Loaded)?.cardName
                val isInMemoryOnly = (uiState as? CardUiState.Loaded)?.cardUri == null
                var showUnsavedChangesDialog by remember { mutableStateOf(false) }

                BackHandler(enabled = (uiState is CardUiState.Loaded) && selectedSave == null) {
                    if (hasUnsavedChanges || isInMemoryOnly) {
                        showUnsavedChangesDialog = true
                    } else {
                        viewModel.closeCard()
                    }
                }

                if (showUnsavedChangesDialog) {
                    AlertDialog(
                        onDismissRequest = { showUnsavedChangesDialog = false },
                        shape = RoundedCornerShape(20.dp),
                        title = {
                            Text(
                                text = "Save Changes?",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Text(
                                text = "You have unsaved changes on this memory card. Do you want to save before closing?",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showUnsavedChangesDialog = false
                                    pendingActionAfterSave = { viewModel.closeCard() }
                                    triggerSaveCurrentCard()
                                }
                            ) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            Row {
                                TextButton(
                                    onClick = {
                                        showUnsavedChangesDialog = false
                                        viewModel.closeCard()
                                    }
                                ) {
                                    Text("Discard")
                                }
                                TextButton(
                                    onClick = { showUnsavedChangesDialog = false }
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    )
                }

                Scaffold(
                    topBar = {
                        AppHeader(
                            cardName = currentCardName,
                            hasUnsavedChanges = hasUnsavedChanges,
                            isInMemoryOnly = isInMemoryOnly,
                            onOpenCard = { openCardLauncher.launch(arrayOf("*/*")) },
                            onCreateCard = { viewModel.setShowCreateDialog(true) },
                            onSaveCard = { triggerSaveCurrentCard() },
                            onSaveCardAs = { triggerSaveCardAs() },
                            onFormatCard = { viewModel.setShowFormatDialog(true) },
                            onShowStats = { viewModel.setShowStatsDialog(true) },
                            onShowConvert = { viewModel.setShowConvertDialog(true) }
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
                                    onCreateCard = { viewModel.setShowCreateDialog(true) }
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
                                    onImportPsu = { importSaveLauncher.launch(arrayOf("*/*")) },
                                    hasUnsavedChanges = hasUnsavedChanges,
                                    isInMemoryOnly = isInMemoryOnly,
                                    onSaveCard = { triggerSaveCurrentCard() },
                                    onFormatCard = { viewModel.setShowFormatDialog(true) }
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
                        onExportMax = {
                            viewModel.selectSave(null)
                            triggerExportMax(save)
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
                        customDirectoryName = customDirectoryName,
                        onSelectCustomDirectory = { selectSaveDirectoryLauncher.launch(null) },
                        onDismiss = { viewModel.setShowCreateDialog(false) },
                        onSaveCard = { name, size, ecc, formatted ->
                            viewModel.setShowCreateDialog(false)
                            val bytes = if (formatted) {
                                MemcardFormatter.format(size, ecc)
                            } else {
                                MemcardFormatter.createUnformatted(size, ecc)
                            }
                            val customDirUri = viewModel.customDirectoryUri.value
                            var savedInDir = false
                            if (customDirUri != null) {
                                try {
                                    val tree = DocumentFile.fromTreeUri(this, customDirUri)
                                    val targetFile = tree?.findFile(name) ?: tree?.createFile("application/octet-stream", name)
                                    if (targetFile != null) {
                                        contentResolver.openOutputStream(targetFile.uri, "wt")?.use { out ->
                                            out.write(bytes)
                                        }
                                        viewModel.loadCardFromBytes(name, bytes, targetFile.uri)
                                        Toast.makeText(this, "Created and saved $name in custom directory!", Toast.LENGTH_SHORT).show()
                                        savedInDir = true
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(this, "Could not save to custom directory: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            if (!savedInDir) {
                                pendingSaveCardBytes = bytes
                                pendingSaveCardName = name
                                selectSaveDirectoryLauncher.launch(null)
                            }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            loadCardFromUri(uri)
        }
    }

    private fun triggerSaveCurrentCard() {
        val loaded = viewModel.uiState.value as? CardUiState.Loaded ?: return
        val bytes = viewModel.getRawCardData() ?: return

        // If card already has a file URI, save directly to it
        if (loaded.cardUri != null) {
            try {
                contentResolver.openOutputStream(loaded.cardUri, "wt")?.use { out ->
                    out.write(bytes)
                }
                viewModel.markCardSaved(loaded.cardUri, loaded.cardName)
                Toast.makeText(this, "Saved ${loaded.cardName} successfully!", Toast.LENGTH_SHORT).show()
                val action = pendingActionAfterSave
                pendingActionAfterSave = null
                action?.invoke()
                return
            } catch (_: Exception) {
                // If writing to original URI failed (e.g. permission lost), fall back to folder picker
            }
        }

        // If custom directory is set, save to that directory
        val customDirUri = viewModel.customDirectoryUri.value
        if (customDirUri != null) {
            try {
                val tree = DocumentFile.fromTreeUri(this, customDirUri)
                val targetFile = tree?.findFile(loaded.cardName) ?: tree?.createFile("application/octet-stream", loaded.cardName)
                if (targetFile != null) {
                    contentResolver.openOutputStream(targetFile.uri, "wt")?.use { out ->
                        out.write(bytes)
                    }
                    viewModel.markCardSaved(targetFile.uri, loaded.cardName)
                    Toast.makeText(this, "Saved ${loaded.cardName} to custom directory!", Toast.LENGTH_SHORT).show()
                    val action = pendingActionAfterSave
                    pendingActionAfterSave = null
                    action?.invoke()
                    return
                }
            } catch (_: Exception) {}
        }

        // Otherwise prompt folder picker
        triggerSaveCardAs()
    }

    private fun triggerSaveCardAs() {
        val loaded = viewModel.uiState.value as? CardUiState.Loaded ?: return
        val bytes = viewModel.getRawCardData() ?: return
        pendingSaveCardBytes = bytes
        pendingSaveCardName = loaded.cardName
        selectSaveDirectoryLauncher.launch(null)
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

    private fun triggerExportMax(save: Ps2Save) {
        val bytes = viewModel.exportMax(save.directoryName)
        if (bytes != null) {
            pendingExportMaxBytes = bytes
            exportMaxLauncher.launch("${save.directoryName}.max")
        } else {
            Toast.makeText(this, "Failed to export Action Replay MAX save", Toast.LENGTH_SHORT).show()
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
