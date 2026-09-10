package xyz.mininxd.ps2memcards.ui

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
import xyz.mininxd.ps2memcards.core.MemcardFormatter
import xyz.mininxd.ps2memcards.core.Ps2Save
import xyz.mininxd.ps2memcards.ui.components.AppHeader
import xyz.mininxd.ps2memcards.ui.components.CardStatsDialog
import xyz.mininxd.ps2memcards.ui.components.CreateCardDialog
import xyz.mininxd.ps2memcards.ui.components.FormatCardDialog
import xyz.mininxd.ps2memcards.ui.components.HexViewerDialog
import xyz.mininxd.ps2memcards.ui.components.SaveDetailModal
import xyz.mininxd.ps2memcards.ui.screens.EmptyStateScreen
import xyz.mininxd.ps2memcards.ui.screens.MainScreen
import xyz.mininxd.ps2memcards.ui.theme.PS2MemcardTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MemcardViewModel by viewModels()

    private var pendingExportPsuBytes: ByteArray? = null
    private var pendingExportMaxBytes: ByteArray? = null
    private var pendingExportZipBytes: ByteArray? = null
    private var pendingSaveCardBytes: ByteArray? = null
    private var pendingSaveCardName: String? = null
    private var pendingActionAfterSave: (() -> Unit)? = null

    private val snackbarHostState = SnackbarHostState()

    /**
     * Shows a Toast message only when no bottom UI is currently popped up (such as a
     * ModalBottomSheet or an active Snackbar).
     */
    private fun showToast(message: String, isLong: Boolean = false) {
        val isBottomUiPopped = (viewModel.selectedSave.value != null) ||
                               (snackbarHostState.currentSnackbarData != null)
        if (isBottomUiPopped) {
            return
        }
        Toast.makeText(this, message, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

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
                    showToast("Saved $cardName to $dirName successfully!")
                    val action = pendingActionAfterSave
                    pendingActionAfterSave = null
                    action?.invoke()
                } else {
                    showToast("Could not create $cardName in $dirName", isLong = true)
                    pendingActionAfterSave = null
                }
            } catch (e: Exception) {
                showToast("Failed to save card: ${e.message}", isLong = true)
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
                showToast("Failed to read save file: ${e.message}", isLong = true)
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
                    showToast("PSU save exported successfully!")
                } catch (e: Exception) {
                    showToast("Export failed: ${e.message}", isLong = true)
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
                    showToast("Action Replay MAX save exported successfully!")
                } catch (e: Exception) {
                    showToast("Export failed: ${e.message}", isLong = true)
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
                    showToast("ZIP archive exported successfully!")
                } catch (e: Exception) {
                    showToast("Export failed: ${e.message}", isLong = true)
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
                val hexViewerData by viewModel.hexViewerData.collectAsState()
                val snackbarMessage by viewModel.snackbarMessage.collectAsState()
                val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
                val customDirectoryName by viewModel.customDirectoryName.collectAsState()

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
                            onShowStats = { viewModel.setShowStatsDialog(true) }
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
                                        showToast("Created and saved $name in custom directory!")
                                        savedInDir = true
                                    }
                                } catch (e: Exception) {
                                    showToast("Could not save to custom directory: ${e.message}", isLong = true)
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
                showToast("Saved ${loaded.cardName} successfully!")
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
                    showToast("Saved ${loaded.cardName} to custom directory!")
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
        viewModel.loadCardFromUri(contentResolver, uri, fileName)
    }

    private fun triggerExportPsu(save: Ps2Save) {
        val bytes = viewModel.exportPsu(save.directoryName)
        if (bytes != null) {
            pendingExportPsuBytes = bytes
            exportPsuLauncher.launch("${save.directoryName}.psu")
        } else {
            showToast("Failed to export PSU")
        }
    }

    private fun triggerExportMax(save: Ps2Save) {
        val bytes = viewModel.exportMax(save.directoryName)
        if (bytes != null) {
            pendingExportMaxBytes = bytes
            exportMaxLauncher.launch("${save.directoryName}.max")
        } else {
            showToast("Failed to export Action Replay MAX save")
        }
    }

    private fun triggerExportZip(save: Ps2Save) {
        val bytes = viewModel.exportZip(save.directoryName)
        if (bytes != null) {
            pendingExportZipBytes = bytes
            exportZipLauncher.launch("${save.directoryName}.zip")
        } else {
            showToast("Failed to export ZIP")
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
