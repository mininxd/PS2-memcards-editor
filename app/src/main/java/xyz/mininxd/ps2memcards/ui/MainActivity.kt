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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mininxd.ps2memcards.core.ExportFilenameFormat
import xyz.mininxd.ps2memcards.core.MemcardFormatter
import xyz.mininxd.ps2memcards.core.Ps2Save
import xyz.mininxd.ps2memcards.core.RecentCard
import xyz.mininxd.ps2memcards.ui.components.AppHeader
import xyz.mininxd.ps2memcards.ui.components.CardStatsDialog
import xyz.mininxd.ps2memcards.ui.components.CreateCardDialog
import xyz.mininxd.ps2memcards.ui.components.EditTimestampsDialog
import xyz.mininxd.ps2memcards.ui.components.FormatCardDialog
import xyz.mininxd.ps2memcards.ui.components.HexViewerDialog
import xyz.mininxd.ps2memcards.ui.components.SaveDetailModal
import xyz.mininxd.ps2memcards.ui.components.SettingsDialog
import xyz.mininxd.ps2memcards.ui.screens.EmptyStateScreen
import xyz.mininxd.ps2memcards.ui.screens.MainScreen
import xyz.mininxd.ps2memcards.ui.theme.PS2MemcardTheme

private data class PendingCreateCard(
    val name: String,
    val sizeInMB: Int,
    val useEcc: Boolean,
    val isFormatted: Boolean
)

class MainActivity : ComponentActivity() {

    private val viewModel: MemcardViewModel by viewModels()

    private var pendingExportPsuBytes: ByteArray? = null
    private var pendingExportMaxBytes: ByteArray? = null
    private var pendingExportCbsBytes: ByteArray? = null
    private var pendingExportXpsBytes: ByteArray? = null
    private var pendingExportZipBytes: ByteArray? = null
    private var pendingCreateCard: PendingCreateCard? = null
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
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
            loadCardFromUri(it)
        }
    }

    private val selectSaveDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            pendingCreateCard = null
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

        val createParams = pendingCreateCard
        if (createParams != null) {
            pendingCreateCard = null
            createAndSaveCard(uri, createParams.name, createParams.sizeInMB, createParams.useEcc, createParams.isFormatted)
            return@registerForActivityResult
        }

        saveLoadedCardToDirectory(uri, dirName)
    }

    private fun createAndSaveCard(dirUri: Uri, name: String, sizeInMB: Int, useEcc: Boolean, formatted: Boolean) {
        lifecycleScope.launch {
            viewModel.setLoading("Creating $name (${sizeInMB}MB)...")
            withContext(Dispatchers.IO) {
                try {
                    val tree = DocumentFile.fromTreeUri(this@MainActivity, dirUri)
                    val targetFile = tree?.findFile(name) ?: tree?.createFile("application/octet-stream", name)
                    if (targetFile == null) {
                        withContext(Dispatchers.Main) {
                            showToast("Could not create file in selected directory", isLong = true)
                            viewModel.clearLoading()
                        }
                        return@withContext
                    }

                    val bytes = if (formatted) {
                        MemcardFormatter.format(sizeInMB, useEcc)
                    } else {
                        MemcardFormatter.createUnformatted(sizeInMB, useEcc)
                    }

                    val out = try {
                        contentResolver.openOutputStream(targetFile.uri, "wt")
                    } catch (_: Exception) {
                        contentResolver.openOutputStream(targetFile.uri, "w")
                    } ?: throw java.io.IOException("Could not open output stream for writing")

                    out.use { stream ->
                        stream.write(bytes)
                    }

                    withContext(Dispatchers.Main) {
                        viewModel.loadCardFromBytes(name, bytes, targetFile.uri)
                        viewModel.addRecentCard(
                            this@MainActivity,
                            RecentCard(
                                uriString = targetFile.uri.toString(),
                                fileName = name,
                                sizeBytes = bytes.size.toLong(),
                                saveCount = 0,
                                lastOpened = System.currentTimeMillis()
                            )
                        )
                        showToast("Created and saved $name successfully!")
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        showToast("Failed to create card: ${t.message ?: "Out of memory"}", isLong = true)
                        viewModel.setError("Failed to create card: ${t.message ?: "Out of memory"}")
                    }
                }
            }
        }
    }

    private fun saveLoadedCardToDirectory(dirUri: Uri, dirName: String) {
        val loaded = viewModel.uiState.value as? CardUiState.Loaded ?: return
        val rawData = loaded.memcard.getRawDataDirect()
        val cardName = loaded.cardName
        lifecycleScope.launch {
            viewModel.setLoading("Saving $cardName...")
            withContext(Dispatchers.IO) {
                try {
                    val docDir = DocumentFile.fromTreeUri(this@MainActivity, dirUri)
                    val targetFile = docDir?.findFile(cardName) ?: docDir?.createFile("application/octet-stream", cardName)
                    if (targetFile != null) {
                        val out = try {
                            contentResolver.openOutputStream(targetFile.uri, "wt")
                        } catch (_: Exception) {
                            contentResolver.openOutputStream(targetFile.uri, "w")
                        } ?: throw java.io.IOException("Could not open output stream for writing")

                        out.use { stream ->
                            stream.write(rawData)
                        }
                        withContext(Dispatchers.Main) {
                            viewModel.markCardSaved(targetFile.uri, cardName, this@MainActivity)
                            showToast("Saved $cardName to $dirName successfully!")
                            val action = pendingActionAfterSave
                            pendingActionAfterSave = null
                            action?.invoke()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            showToast("Could not create $cardName in $dirName", isLong = true)
                            pendingActionAfterSave = null
                            viewModel.clearLoading()
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        showToast("Failed to save card: ${t.message ?: "Error"}", isLong = true)
                        pendingActionAfterSave = null
                        viewModel.clearLoading()
                    }
                }
            }
        }
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

    private val exportCbsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let { targetUri ->
            pendingExportCbsBytes?.let { bytes ->
                try {
                    contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(bytes)
                    }
                    showToast("CodeBreaker (.cbs) save exported successfully!")
                } catch (e: Exception) {
                    showToast("Export failed: ${e.message}", isLong = true)
                }
            }
        }
        pendingExportCbsBytes = null
    }

    private val exportXpsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let { targetUri ->
            pendingExportXpsBytes?.let { bytes ->
                try {
                    contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(bytes)
                    }
                    showToast("SharkPort / X-Port (.xps) save exported successfully!")
                } catch (e: Exception) {
                    showToast("Export failed: ${e.message}", isLong = true)
                }
            }
        }
        pendingExportXpsBytes = null
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

        viewModel.initSettings(this)

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
                val updateStatus by viewModel.updateStatus.collectAsState()
                val recentCards by viewModel.recentCards.collectAsState()
                val exportFilenameFormat by viewModel.exportFilenameFormat.collectAsState()
                val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()

                LaunchedEffect(snackbarMessage) {
                    snackbarMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearSnackbar()
                    }
                }

                val currentCardName = (uiState as? CardUiState.Loaded)?.cardName
                val isInMemoryOnly = (uiState as? CardUiState.Loaded)?.cardUri == null
                var showUnsavedChangesDialog by remember { mutableStateOf(false) }
                var editingTimestampsSave by remember { mutableStateOf<Ps2Save?>(null) }

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
                            onOpenSettings = { viewModel.setShowSettingsDialog(true) }
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
                                    recentCards = recentCards,
                                    onOpenRecentCard = { recent ->
                                        loadCardFromUri(Uri.parse(recent.uriString))
                                    },
                                    onRemoveRecentCard = { uriStr ->
                                        viewModel.removeRecentCard(this@MainActivity, uriStr)
                                    },
                                    onClearRecentCards = {
                                        viewModel.clearRecentCards(this@MainActivity)
                                    },
                                    onOpenSettings = { viewModel.setShowSettingsDialog(true) },
                                    updateStatus = updateStatus,
                                    onCheckUpdate = {
                                        val version = try {
                                            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.3.1"
                                        } catch (e: Exception) {
                                            "1.3.1"
                                        }
                                        viewModel.checkUpdate(version)
                                    }
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
                                val onSaveClick = remember(viewModel) { { save: Ps2Save -> viewModel.selectSave(save) } }
                                val onExportPsu = remember { { save: Ps2Save -> triggerExportPsu(save) } }
                                val onExportZip = remember { { save: Ps2Save -> triggerExportZip(save) } }
                                val onDeleteSave = remember(viewModel) { { save: Ps2Save -> viewModel.deleteSave(save.directoryName) } }
                                val onSearchChange = remember(viewModel) { { q: String -> viewModel.setSearchQuery(q) } }
                                val onFilterChange = remember(viewModel) { { f: FilterType -> viewModel.setFilterType(f) } }
                                val onSortChange = remember(viewModel) { { s: SortBy -> viewModel.setSortBy(s) } }
                                val onImportPsu = remember { { importSaveLauncher.launch(arrayOf("*/*")) } }
                                val onSaveCard = remember { { triggerSaveCurrentCard() } }
                                val onFormatCard = remember(viewModel) { { viewModel.setShowFormatDialog(true) } }

                                MainScreen(
                                    saves = state.saves,
                                    stats = state.stats,
                                    searchQuery = searchQuery,
                                    onSearchChange = onSearchChange,
                                    filterType = filterType,
                                    onFilterChange = onFilterChange,
                                    sortBy = sortBy,
                                    onSortChange = onSortChange,
                                    onSaveClick = onSaveClick,
                                    onExportPsu = onExportPsu,
                                    onExportZip = onExportZip,
                                    onDeleteSave = onDeleteSave,
                                    onImportPsu = onImportPsu,
                                    hasUnsavedChanges = hasUnsavedChanges,
                                    isInMemoryOnly = isInMemoryOnly,
                                    onSaveCard = onSaveCard,
                                    onFormatCard = onFormatCard
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
                        onExportCbs = {
                            viewModel.selectSave(null)
                            triggerExportCbs(save)
                        },
                        onExportXps = {
                            viewModel.selectSave(null)
                            triggerExportXps(save)
                        },
                        onExportZip = {
                            viewModel.selectSave(null)
                            triggerExportZip(save)
                        },
                        onDelete = {
                            viewModel.selectSave(null)
                            viewModel.deleteSave(save.directoryName)
                        },
                        onToggleProtection = { isProtected ->
                            viewModel.setSaveProtection(save.directoryName, isProtected)
                        },
                        onEditTimestamps = {
                            editingTimestampsSave = save
                        },
                        onInspectFileHex = { file ->
                            val data = file.data ?: (uiState as? CardUiState.Loaded)?.memcard?.getSaveFileBytes(save.directoryName, file.name) ?: ByteArray(0)
                            viewModel.openHexViewer(file.name, data)
                        }
                    )
                }

                editingTimestampsSave?.let { saveToEdit ->
                    EditTimestampsDialog(
                        saveTitle = saveToEdit.displayTitle,
                        initialCreated = saveToEdit.dirEntry.created,
                        initialModified = saveToEdit.dirEntry.modified,
                        onDismiss = { editingTimestampsSave = null },
                        onSave = { newCreated, newModified ->
                            editingTimestampsSave = null
                            viewModel.updateSaveTimestamps(saveToEdit.directoryName, newCreated, newModified)
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
                            val customDirUri = viewModel.customDirectoryUri.value
                            if (customDirUri != null) {
                                createAndSaveCard(customDirUri, name, size, ecc, formatted)
                            } else {
                                pendingCreateCard = PendingCreateCard(name, size, ecc, formatted)
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

                if (showSettingsDialog) {
                    SettingsDialog(
                        currentFormat = exportFilenameFormat,
                        onFormatSelected = { viewModel.setExportFilenameFormat(this@MainActivity, it) },
                        hasRecentCards = recentCards.isNotEmpty(),
                        onClearRecentCards = { viewModel.clearRecentCards(this@MainActivity) },
                        onDismiss = { viewModel.setShowSettingsDialog(false) },
                        versionName = try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.3.1"
                        } catch (_: Exception) {
                            "1.3.1"
                        }
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

        // If card already has a file URI, save directly to it
        if (loaded.cardUri != null) {
            val cardUri = loaded.cardUri
            val cardName = loaded.cardName
            val rawData = loaded.memcard.getRawDataDirect()
            lifecycleScope.launch {
                viewModel.setLoading("Saving $cardName...")
                withContext(Dispatchers.IO) {
                    try {
                        val out = try {
                            contentResolver.openOutputStream(cardUri, "wt")
                        } catch (_: Exception) {
                            contentResolver.openOutputStream(cardUri, "w")
                        } ?: throw java.io.IOException("Could not open output stream for writing")

                        out.use { stream ->
                            stream.write(rawData)
                        }
                        withContext(Dispatchers.Main) {
                            viewModel.markCardSaved(cardUri, cardName, this@MainActivity)
                            showToast("Saved $cardName successfully!")
                            val action = pendingActionAfterSave
                            pendingActionAfterSave = null
                            action?.invoke()
                        }
                    } catch (t: Throwable) {
                        withContext(Dispatchers.Main) {
                            // If writing to original URI failed (e.g. permission lost), fall back to folder picker
                            viewModel.clearLoading()
                            triggerSaveCardAs()
                        }
                    }
                }
            }
            return
        }

        // If custom directory is set, save to that directory
        val customDirUri = viewModel.customDirectoryUri.value
        if (customDirUri != null) {
            saveLoadedCardToDirectory(customDirUri, viewModel.customDirectoryName.value ?: "Custom Directory")
            return
        }

        // Otherwise prompt folder picker
        triggerSaveCardAs()
    }

    private fun triggerSaveCardAs() {
        viewModel.clearLoading()
        if (viewModel.uiState.value is CardUiState.Loaded) {
            selectSaveDirectoryLauncher.launch(null)
        }
    }

    private fun loadCardFromUri(uri: Uri) {
        val fileName = queryFileName(uri) ?: "MemoryCard.ps2"
        viewModel.loadCardFromUri(contentResolver, uri, fileName, this)
    }

    private fun triggerExportPsu(save: Ps2Save) {
        val bytes = viewModel.exportPsu(save.directoryName)
        if (bytes != null) {
            pendingExportPsuBytes = bytes
            val filename = ExportFilenameFormat.generateFilename(save, "psu", viewModel.exportFilenameFormat.value)
            exportPsuLauncher.launch(filename)
        } else {
            showToast("Failed to export PSU")
        }
    }

    private fun triggerExportMax(save: Ps2Save) {
        val bytes = viewModel.exportMax(save.directoryName)
        if (bytes != null) {
            pendingExportMaxBytes = bytes
            val filename = ExportFilenameFormat.generateFilename(save, "max", viewModel.exportFilenameFormat.value)
            exportMaxLauncher.launch(filename)
        } else {
            showToast("Failed to export Action Replay MAX save")
        }
    }

    private fun triggerExportCbs(save: Ps2Save) {
        val bytes = viewModel.exportCbs(save.directoryName)
        if (bytes != null) {
            pendingExportCbsBytes = bytes
            val filename = ExportFilenameFormat.generateFilename(save, "cbs", viewModel.exportFilenameFormat.value)
            exportCbsLauncher.launch(filename)
        } else {
            showToast("Failed to export CodeBreaker save")
        }
    }

    private fun triggerExportXps(save: Ps2Save) {
        val bytes = viewModel.exportXps(save.directoryName)
        if (bytes != null) {
            pendingExportXpsBytes = bytes
            val filename = ExportFilenameFormat.generateFilename(save, "xps", viewModel.exportFilenameFormat.value)
            exportXpsLauncher.launch(filename)
        } else {
            showToast("Failed to export SharkPort / X-Port save")
        }
    }

    private fun triggerExportZip(save: Ps2Save) {
        val bytes = viewModel.exportZip(save.directoryName)
        if (bytes != null) {
            pendingExportZipBytes = bytes
            val filename = ExportFilenameFormat.generateFilename(save, "zip", viewModel.exportFilenameFormat.value)
            exportZipLauncher.launch(filename)
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
