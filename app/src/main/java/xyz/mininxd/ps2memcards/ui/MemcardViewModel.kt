package xyz.mininxd.ps2memcards.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xyz.mininxd.ps2memcards.core.CardStats
import xyz.mininxd.ps2memcards.core.MemcardFormatter
import xyz.mininxd.ps2memcards.core.Ps2DirectoryEntry
import xyz.mininxd.ps2memcards.core.Ps2Memcard
import xyz.mininxd.ps2memcards.core.Ps2Save
import xyz.mininxd.ps2memcards.core.Ps2Timestamp
import xyz.mininxd.ps2memcards.core.PsuHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val _customDirectoryUri = MutableStateFlow<Uri?>(null)
    val customDirectoryUri: StateFlow<Uri?> = _customDirectoryUri.asStateFlow()

    private val _customDirectoryName = MutableStateFlow<String?>(null)
    val customDirectoryName: StateFlow<String?> = _customDirectoryName.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var currentLoadedCard: CardUiState.Loaded? = null

    private fun setLoadedState(loaded: CardUiState.Loaded) {
        currentLoadedCard = loaded
        _uiState.value = loaded
    }

    fun setCustomDirectory(uri: Uri?, name: String?) {
        _customDirectoryUri.value = uri
        _customDirectoryName.value = name
    }

    fun markCardSaved(savedUri: Uri? = null, savedName: String? = null) {
        _hasUnsavedChanges.value = false
        val current = (_uiState.value as? CardUiState.Loaded) ?: currentLoadedCard ?: return
        val updated = current.copy(
            cardName = savedName ?: current.cardName,
            cardUri = savedUri ?: current.cardUri
        )
        setLoadedState(updated)
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

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun loadCardFromUri(contentResolver: android.content.ContentResolver, uri: Uri, fileName: String) {
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
                        _hasUnsavedChanges.value = false
                        val loaded = CardUiState.Loaded(
                            cardName = fileName,
                            cardUri = uri,
                            memcard = card,
                            saves = saves,
                            stats = stats
                        )
                        setLoadedState(loaded)
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
                    val sizeInMB = current.memcard.totalCapacityMb.toInt()
                    val bytes = MemcardFormatter.format(sizeInMB, current.memcard.hasEcc)
                    val card = Ps2Memcard.open(bytes)
                    if (card != null) {
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
                    val success = current.memcard.deleteSave(saveName)
                    if (success) {
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
            _uiState.value = CardUiState.Loading("Importing save (.psu / .max)...")
            withContext(Dispatchers.Default) {
                try {
                    val success = current.memcard.importSave(saveBytes)
                    if (success) {
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
        _hasUnsavedChanges.value = false
        _selectedSave.value = null
        _searchQuery.value = ""
    }
}
