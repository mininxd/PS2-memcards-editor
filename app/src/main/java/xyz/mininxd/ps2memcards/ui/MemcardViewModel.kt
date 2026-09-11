package xyz.mininxd.ps2memcards.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xyz.mininxd.ps2memcards.core.CardStats
import xyz.mininxd.ps2memcards.core.ExportFilenameFormat
import xyz.mininxd.ps2memcards.core.MemcardFormatter
import xyz.mininxd.ps2memcards.core.Ps2DirectoryEntry
import xyz.mininxd.ps2memcards.core.Ps2Memcard
import xyz.mininxd.ps2memcards.core.Ps2Save
import xyz.mininxd.ps2memcards.core.Ps2Timestamp
import xyz.mininxd.ps2memcards.core.PsuHandler
import xyz.mininxd.ps2memcards.core.RecentCard
import xyz.mininxd.ps2memcards.core.RecentCardsManager
import xyz.mininxd.ps2memcards.core.UpdateChecker
import xyz.mininxd.ps2memcards.core.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed interface CardUiState {
    data object Empty : CardUiState
    data class Loading(val message: String = "Loading memory card...") : CardUiState
    data class Loaded(
        val cardName: String,
        val cardUri: Uri?,
        val memcard: Ps2Memcard,
        val saves: List<Ps2Save>,
        val stats: CardStats
    ) : CardUiState
    data class Error(val message: String) : CardUiState
}

enum class FilterType {
    ALL,
    PS2_ONLY,
    PS1_ONLY,
    PROTECTED
}

enum class SortBy {
    NAME_ASC,
    DATE_DESC,
    SIZE_DESC
}

class MemcardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<CardUiState>(CardUiState.Empty)
    val uiState: StateFlow<CardUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterType = MutableStateFlow(FilterType.ALL)
    val filterType: StateFlow<FilterType> = _filterType.asStateFlow()

    private val _sortBy = MutableStateFlow(SortBy.NAME_ASC)
    val sortBy: StateFlow<SortBy> = _sortBy.asStateFlow()

    private val _selectedSave = MutableStateFlow<Ps2Save?>(null)
    val selectedSave: StateFlow<Ps2Save?> = _selectedSave.asStateFlow()

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    private val _showFormatDialog = MutableStateFlow(false)
    val showFormatDialog: StateFlow<Boolean> = _showFormatDialog.asStateFlow()

    private val _showStatsDialog = MutableStateFlow(false)
    val showStatsDialog: StateFlow<Boolean> = _showStatsDialog.asStateFlow()

    private val _hexViewerData = MutableStateFlow<Pair<String, ByteArray>?>(null)
    val hexViewerData: StateFlow<Pair<String, ByteArray>?> = _hexViewerData.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val maxUndoHistory = 10
    private val undoStack = ArrayDeque<ByteArray>()
    private val redoStack = ArrayDeque<ByteArray>()
    private var savedCardCrc: Long? = null
    private val historyLock = Any()
    private val historyMutex = Mutex()

    private val _customDirectoryUri = MutableStateFlow<Uri?>(null)
    val customDirectoryUri: StateFlow<Uri?> = _customDirectoryUri.asStateFlow()

    private val _customDirectoryName = MutableStateFlow<String?>(null)
    val customDirectoryName: StateFlow<String?> = _customDirectoryName.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    private val _recentCards = MutableStateFlow<List<RecentCard>>(emptyList())
    val recentCards: StateFlow<List<RecentCard>> = _recentCards.asStateFlow()

    private val _exportFilenameFormat = MutableStateFlow(ExportFilenameFormat.GAME_NAME_AND_PRODUCT_ID)
    val exportFilenameFormat: StateFlow<ExportFilenameFormat> = _exportFilenameFormat.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun initSettings(context: Context) {
        _recentCards.value = RecentCardsManager.getRecentCards(context)
        _exportFilenameFormat.value = ExportFilenameFormat.getSavedFormat(context)
    }

    fun addRecentCard(context: Context, recent: RecentCard) {
        RecentCardsManager.addRecentCard(context, recent)
        _recentCards.value = RecentCardsManager.getRecentCards(context)
    }

    fun removeRecentCard(context: Context, uriString: String) {
        RecentCardsManager.removeRecentCard(context, uriString)
        _recentCards.value = RecentCardsManager.getRecentCards(context)
    }

    fun clearRecentCards(context: Context) {
        RecentCardsManager.clearAll(context)
        _recentCards.value = emptyList()
    }

    fun setExportFilenameFormat(context: Context, format: ExportFilenameFormat) {
        _exportFilenameFormat.value = format
        ExportFilenameFormat.saveFormat(context, format)
    }

    fun setShowSettingsDialog(show: Boolean) {
        _showSettingsDialog.value = show
    }

    fun checkUpdate(currentVersion: String) {
        viewModelScope.launch {
            _updateStatus.value = UpdateStatus.Checking
            val result = UpdateChecker.checkUpdate(currentVersion)
            _updateStatus.value = result
        }
    }

    private var currentLoadedCard: CardUiState.Loaded? = null

    private fun setLoadedState(loaded: CardUiState.Loaded) {
        currentLoadedCard = loaded
        _uiState.value = loaded
    }

    fun setCustomDirectory(uri: Uri?, name: String?) {
        _customDirectoryUri.value = uri
        _customDirectoryName.value = name
    }

    fun markCardSaved(savedUri: Uri? = null, savedName: String? = null, context: Context? = null) {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return
        savedCardCrc = calculateCrc(current.memcard.getRawDataDirect())
        clearUndoRedoHistory()
        _hasUnsavedChanges.value = false
        val updated = current.copy(
            cardName = savedName ?: current.cardName,
            cardUri = savedUri ?: current.cardUri
        )
        setLoadedState(updated)
        if (context != null && savedUri != null) {
            val name = savedName ?: updated.cardName
            addRecentCard(
                context,
                RecentCard(
                    uriString = savedUri.toString(),
                    fileName = name,
                    sizeBytes = updated.memcard.getRawDataDirect().size.toLong(),
                    saveCount = updated.saves.size,
                    lastOpened = System.currentTimeMillis()
                )
            )
        }
    }

    private fun calculateCrc(bytes: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(bytes)
        return crc.value
    }

    private fun pushUndoSnapshot(snapshot: ByteArray) {
        synchronized(historyLock) {
            if (undoStack.size >= maxUndoHistory) {
                undoStack.removeFirst()
            }
            undoStack.addLast(snapshot)
            redoStack.clear()
            _canUndo.value = true
            _canRedo.value = false
        }
    }

    private fun clearUndoRedoHistory() {
        synchronized(historyLock) {
            undoStack.clear()
            redoStack.clear()
            _canUndo.value = false
            _canRedo.value = false
        }
    }

    fun undo() {
        if (!_canUndo.value) return
        viewModelScope.launch {
            historyMutex.withLock {
                val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return@withLock
                val snapshotToRestore: ByteArray = synchronized(historyLock) {
                    if (undoStack.isEmpty()) return@withLock
                    val snap = undoStack.removeLast()
                    val currentBytes = current.memcard.getRawDataDirect().copyOf()
                    if (redoStack.size >= maxUndoHistory) {
                        redoStack.removeFirst()
                    }
                    redoStack.addLast(currentBytes)
                    _canUndo.value = undoStack.isNotEmpty()
                    _canRedo.value = true
                    snap
                }

                withContext(Dispatchers.Default) {
                    val card = Ps2Memcard.open(snapshotToRestore) ?: return@withContext
                    val saves = card.listSaves()
                    val stats = card.getStats()
                    val isAtSavedBaseline = savedCardCrc != null && calculateCrc(snapshotToRestore) == savedCardCrc
                    _hasUnsavedChanges.value = if (current.cardUri == null) true else !isAtSavedBaseline
                    val updated = current.copy(
                        memcard = card,
                        saves = saves,
                        stats = stats
                    )
                    setLoadedState(updated)
                    val selName = _selectedSave.value?.directoryName
                    _selectedSave.value = if (selName != null) saves.firstOrNull { it.directoryName == selName } else null
                    _snackbarMessage.value = "Undid modification"
                }
            }
        }
    }

    fun redo() {
        if (!_canRedo.value) return
        viewModelScope.launch {
            historyMutex.withLock {
                val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return@withLock
                val snapshotToRestore: ByteArray = synchronized(historyLock) {
                    if (redoStack.isEmpty()) return@withLock
                    val snap = redoStack.removeLast()
                    val currentBytes = current.memcard.getRawDataDirect().copyOf()
                    if (undoStack.size >= maxUndoHistory) {
                        undoStack.removeFirst()
                    }
                    undoStack.addLast(currentBytes)
                    _canUndo.value = true
                    _canRedo.value = redoStack.isNotEmpty()
                    snap
                }

                withContext(Dispatchers.Default) {
                    val card = Ps2Memcard.open(snapshotToRestore) ?: return@withContext
                    val saves = card.listSaves()
                    val stats = card.getStats()
                    val isAtSavedBaseline = savedCardCrc != null && calculateCrc(snapshotToRestore) == savedCardCrc
                    _hasUnsavedChanges.value = if (current.cardUri == null) true else !isAtSavedBaseline
                    val updated = current.copy(
                        memcard = card,
                        saves = saves,
                        stats = stats
                    )
                    setLoadedState(updated)
                    val selName = _selectedSave.value?.directoryName
                    _selectedSave.value = if (selName != null) saves.firstOrNull { it.directoryName == selName } else null
                    _snackbarMessage.value = "Redid modification"
                }
            }
        }
    }

    fun setLoading(message: String) {
        _uiState.value = CardUiState.Loading(message)
    }

    fun setError(message: String) {
        _uiState.value = CardUiState.Error(message)
    }

    fun clearLoading() {
        if (_uiState.value is CardUiState.Loading) {
            _uiState.value = currentLoadedCard ?: CardUiState.Empty
        }
    }

    fun getRawCardData(): ByteArray? {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return null
        return current.memcard.getRawDataDirect()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilterType(filter: FilterType) {
        _filterType.value = filter
    }

    fun setSortBy(sort: SortBy) {
        _sortBy.value = sort
    }

    fun selectSave(save: Ps2Save?) {
        _selectedSave.value = save
    }

    fun setShowCreateDialog(show: Boolean) {
        _showCreateDialog.value = show
    }

    fun setShowFormatDialog(show: Boolean) {
        _showFormatDialog.value = show
    }

    fun setShowStatsDialog(show: Boolean) {
        _showStatsDialog.value = show
    }

    fun openHexViewer(title: String, data: ByteArray) {
        _hexViewerData.value = Pair(title, data)
    }

    fun closeHexViewer() {
        _hexViewerData.value = null
    }

    fun setSaveProtection(saveName: String, isProtected: Boolean) {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val snapshotBefore = current.memcard.getRawDataDirect().copyOf()
                val ok = current.memcard.setSaveProtection(saveName, isProtected)
                if (ok) {
                    pushUndoSnapshot(snapshotBefore)
                    val saves = current.memcard.listSaves()
                    val stats = current.memcard.getStats()
                    _hasUnsavedChanges.value = true
                    val updatedSave = saves.firstOrNull { it.directoryName == saveName }
                    setLoadedState(current.copy(saves = saves, stats = stats))
                    if (_selectedSave.value?.directoryName == saveName) {
                        _selectedSave.value = updatedSave
                    }
                    _snackbarMessage.value = if (isProtected) {
                        "Marked $saveName as copy-protected"
                    } else {
                        "Removed copy-protection from $saveName"
                    }
                }
            }
        }
    }

    fun updateSaveTimestamps(saveName: String, created: Ps2Timestamp, modified: Ps2Timestamp) {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val snapshotBefore = current.memcard.getRawDataDirect().copyOf()
                val ok = current.memcard.updateSaveTimestamps(saveName, created, modified)
                if (ok) {
                    pushUndoSnapshot(snapshotBefore)
                    val saves = current.memcard.listSaves()
                    val stats = current.memcard.getStats()
                    _hasUnsavedChanges.value = true
                    val updatedSave = saves.firstOrNull { it.directoryName == saveName }
                    setLoadedState(current.copy(saves = saves, stats = stats))
                    if (_selectedSave.value?.directoryName == saveName) {
                        _selectedSave.value = updatedSave
                    }
                    _snackbarMessage.value = "Updated timestamps for $saveName"
                }
            }
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun reloadCard(contentResolver: android.content.ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _isRefreshing.value = true
            withContext(Dispatchers.IO) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        _snackbarMessage.value = "Could not reload: file unavailable"
                        return@withContext
                    }
                    val card = Ps2Memcard.open(bytes)
                    if (card != null) {
                        val saves = card.listSaves()
                        val stats = card.getStats()
                        savedCardCrc = calculateCrc(bytes)
                        clearUndoRedoHistory()
                        _hasUnsavedChanges.value = false
                        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard
                        val fileName = current?.cardName ?: "MemoryCard.ps2"
                        val loaded = CardUiState.Loaded(
                            cardName = fileName,
                            cardUri = uri,
                            memcard = card,
                            saves = saves,
                            stats = stats
                        )
                        setLoadedState(loaded)
                        _snackbarMessage.value = "Reloaded $fileName (${saves.size} saves)"
                    } else {
                        _snackbarMessage.value = "Invalid PS2 Memory Card image format."
                    }
                } catch (t: Throwable) {
                    _snackbarMessage.value = "Failed to reload: ${t.message ?: "Error"}"
                } finally {
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun loadCardFromUri(
        contentResolver: android.content.ContentResolver,
        uri: Uri,
        fileName: String,
        context: Context? = null
    ) {
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Reading $fileName...")
            withContext(Dispatchers.IO) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        _uiState.value = CardUiState.Error("Could not read file from storage.")
                        return@withContext
                    }
                    val card = Ps2Memcard.open(bytes)
                    if (card != null) {
                        val saves = card.listSaves()
                        val stats = card.getStats()
                        savedCardCrc = calculateCrc(bytes)
                        clearUndoRedoHistory()
                        _hasUnsavedChanges.value = false
                        val loaded = CardUiState.Loaded(
                            cardName = fileName,
                            cardUri = uri,
                            memcard = card,
                            saves = saves,
                            stats = stats
                        )
                        setLoadedState(loaded)
                        if (context != null) {
                            addRecentCard(
                                context,
                                RecentCard(
                                    uriString = uri.toString(),
                                    fileName = fileName,
                                    sizeBytes = bytes.size.toLong(),
                                    saveCount = saves.size,
                                    lastOpened = System.currentTimeMillis()
                                )
                            )
                        }
                        _snackbarMessage.value = "Loaded $fileName (${saves.size} saves)"
                    } else {
                        _uiState.value = CardUiState.Error("Invalid PS2 Memory Card image format.")
                    }
                } catch (t: Throwable) {
                    _uiState.value = CardUiState.Error("Failed to open card: ${t.message ?: "Out of memory"}")
                }
            }
        }
    }

    fun loadCardFromBytes(name: String, bytes: ByteArray, uri: Uri? = null) {
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Reading $name...")
            withContext(Dispatchers.Default) {
                try {
                    val card = Ps2Memcard.open(bytes)
                    if (card != null) {
                        val saves = card.listSaves()
                        val stats = card.getStats()
                        savedCardCrc = calculateCrc(bytes)
                        clearUndoRedoHistory()
                        _hasUnsavedChanges.value = false
                        val loaded = CardUiState.Loaded(
                            cardName = name,
                            cardUri = uri,
                            memcard = card,
                            saves = saves,
                            stats = stats
                        )
                        setLoadedState(loaded)
                        _snackbarMessage.value = "Loaded $name (${saves.size} saves)"
                    } else {
                        _uiState.value = CardUiState.Error("Invalid PS2 Memory Card image format.")
                    }
                } catch (t: Throwable) {
                    _uiState.value = CardUiState.Error("Failed to open card: ${t.message ?: "Out of memory"}")
                }
            }
        }
    }

    fun createNewCard(name: String, sizeInMB: Int, useEcc: Boolean, formatted: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Creating $name (${sizeInMB}MB)...")
            withContext(Dispatchers.Default) {
                try {
                    val bytes = if (formatted) {
                        MemcardFormatter.format(sizeInMB, useEcc)
                    } else {
                        MemcardFormatter.createUnformatted(sizeInMB, useEcc)
                    }
                    val card = Ps2Memcard.open(bytes)
                    if (card != null) {
                        val saves = card.listSaves()
                        val stats = card.getStats()
                        savedCardCrc = null
                        clearUndoRedoHistory()
                        _hasUnsavedChanges.value = true
                        val loaded = CardUiState.Loaded(
                            cardName = name,
                            cardUri = null,
                            memcard = card,
                            saves = saves,
                            stats = stats
                        )
                        setLoadedState(loaded)
                        _snackbarMessage.value = "Created $name successfully!"
                    } else {
                        _uiState.value = CardUiState.Error("Failed to initialize memory card.")
                    }
                } catch (t: Throwable) {
                    _uiState.value = CardUiState.Error("Creation error: ${t.message ?: "Out of memory"}")
                }
            }
        }
    }

    fun formatCurrentCard() {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Formatting card...")
            withContext(Dispatchers.Default) {
                try {
                    val snapshotBefore = current.memcard.getRawDataDirect().copyOf()
                    val sizeInMB = current.memcard.totalCapacityMb.toInt()
                    val bytes = MemcardFormatter.format(sizeInMB, current.memcard.hasEcc)
                    val card = Ps2Memcard.open(bytes)
                    if (card != null) {
                        pushUndoSnapshot(snapshotBefore)
                        _hasUnsavedChanges.value = true
                        val loaded = CardUiState.Loaded(
                            cardName = current.cardName,
                            cardUri = current.cardUri,
                            memcard = card,
                            saves = emptyList(),
                            stats = card.getStats()
                        )
                        setLoadedState(loaded)
                        _snackbarMessage.value = "Memory card formatted successfully."
                    }
                } catch (t: Throwable) {
                    _uiState.value = CardUiState.Error("Format error: ${t.message ?: "Out of memory"}")
                }
            }
        }
    }

    fun deleteSave(saveName: String) {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    val snapshotBefore = current.memcard.getRawDataDirect().copyOf()
                    val success = current.memcard.deleteSave(saveName)
                    if (success) {
                        pushUndoSnapshot(snapshotBefore)
                        val saves = current.memcard.listSaves()
                        val stats = current.memcard.getStats()
                        _selectedSave.value = null
                        _hasUnsavedChanges.value = true
                        val loaded = current.copy(saves = saves, stats = stats)
                        setLoadedState(loaded)
                        _snackbarMessage.value = "Deleted save $saveName"
                    } else {
                        _snackbarMessage.value = "Failed to delete save $saveName"
                    }
                } catch (e: Exception) {
                    _snackbarMessage.value = "Delete error: ${e.message}"
                }
            }
        }
    }

    fun importSave(saveBytes: ByteArray) {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return
        if (!current.stats.isFormatted) {
            _snackbarMessage.value = "Card must be formatted before importing saves."
            return
        }
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Importing Savegame...")
            withContext(Dispatchers.Default) {
                try {
                    val snapshotBefore = current.memcard.getRawDataDirect().copyOf()
                    val success = current.memcard.importSave(saveBytes)
                    if (success) {
                        pushUndoSnapshot(snapshotBefore)
                        val saves = current.memcard.listSaves()
                        val stats = current.memcard.getStats()
                        _hasUnsavedChanges.value = true
                        val loaded = current.copy(saves = saves, stats = stats)
                        setLoadedState(loaded)
                        _snackbarMessage.value = "Imported save successfully!"
                    } else {
                        setLoadedState(current)
                        _snackbarMessage.value = "Failed to import save (insufficient space or invalid format)."
                    }
                } catch (e: Exception) {
                    setLoadedState(current)
                    _snackbarMessage.value = "Import error: ${e.message}"
                }
            }
        }
    }

    fun importPsu(psuBytes: ByteArray) = importSave(psuBytes)

    fun exportPsu(saveName: String): ByteArray? {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return null
        return current.memcard.exportSaveAsPsu(saveName)
    }

    fun exportMax(saveName: String): ByteArray? {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return null
        return current.memcard.exportSaveAsMax(saveName)
    }

    fun exportCbs(saveName: String): ByteArray? {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return null
        return current.memcard.exportSaveAsCbs(saveName)
    }

    fun exportXps(saveName: String): ByteArray? {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return null
        return current.memcard.exportSaveAsXps(saveName)
    }

    fun exportZip(saveName: String): ByteArray? {
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return null
        val save = current.saves.firstOrNull { it.directoryName == saveName } ?: return null

        val bos = ByteArrayOutputStream()
        val zos = ZipOutputStream(bos)

        for (f in save.files) {
            val data = f.data ?: current.memcard.getSaveFileBytes(saveName, f.name) ?: continue
            val entry = ZipEntry("${save.directoryName}/${f.name}")
            zos.putNextEntry(entry)
            zos.write(data)
            zos.closeEntry()
        }
        zos.close()
        return bos.toByteArray()
    }



    fun closeCard() {
        currentLoadedCard = null
        _uiState.value = CardUiState.Empty
        savedCardCrc = null
        clearUndoRedoHistory()
        _hasUnsavedChanges.value = false
        _selectedSave.value = null
        _searchQuery.value = ""
    }
}
