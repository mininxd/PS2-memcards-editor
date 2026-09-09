package com.ps2.memcard.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ps2.memcard.core.CardStats
import com.ps2.memcard.core.MemcardFormatter
import com.ps2.memcard.core.Ps2DirectoryEntry
import com.ps2.memcard.core.Ps2Memcard
import com.ps2.memcard.core.Ps2Save
import com.ps2.memcard.core.Ps2Timestamp
import com.ps2.memcard.core.PsuHandler
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

    private val _showConvertDialog = MutableStateFlow(false)
    val showConvertDialog: StateFlow<Boolean> = _showConvertDialog.asStateFlow()

    private val _hexViewerData = MutableStateFlow<Pair<String, ByteArray>?>(null)
    val hexViewerData: StateFlow<Pair<String, ByteArray>?> = _hexViewerData.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

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

    fun setShowConvertDialog(show: Boolean) {
        _showConvertDialog.value = show
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

    fun loadCardFromBytes(name: String, bytes: ByteArray, uri: Uri? = null) {
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Reading $name...")
            withContext(Dispatchers.Default) {
                try {
                    val card = Ps2Memcard.open(bytes)
                    if (card != null) {
                        val saves = card.listSaves()
                        val stats = card.getStats()
                        _uiState.value = CardUiState.Loaded(
                            cardName = name,
                            cardUri = uri,
                            memcard = card,
                            saves = saves,
                            stats = stats
                        )
                        _snackbarMessage.value = "Loaded $name (${saves.size} saves)"
                    } else {
                        _uiState.value = CardUiState.Error("Invalid PS2 Memory Card image format.")
                    }
                } catch (e: Exception) {
                    _uiState.value = CardUiState.Error("Failed to open card: ${e.message}")
                }
            }
        }
    }

    fun createNewCard(name: String, sizeInMB: Int, useEcc: Boolean) {
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Creating $name (${sizeInMB}MB)...")
            withContext(Dispatchers.Default) {
                try {
                    val bytes = MemcardFormatter.format(sizeInMB, useEcc)
                    val card = Ps2Memcard.open(bytes)
                    if (card != null) {
                        val saves = card.listSaves()
                        val stats = card.getStats()
                        _uiState.value = CardUiState.Loaded(
                            cardName = name,
                            cardUri = null,
                            memcard = card,
                            saves = saves,
                            stats = stats
                        )
                        _snackbarMessage.value = "Created $name successfully!"
                    } else {
                        _uiState.value = CardUiState.Error("Failed to format memory card.")
                    }
                } catch (e: Exception) {
                    _uiState.value = CardUiState.Error("Creation error: ${e.message}")
                }
            }
        }
    }

    fun formatCurrentCard() {
        val current = _uiState.value as? CardUiState.Loaded ?: return
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Formatting card...")
            withContext(Dispatchers.Default) {
                try {
                    val sizeInMB = current.memcard.totalCapacityMb.toInt()
                    val bytes = MemcardFormatter.format(sizeInMB, current.memcard.hasEcc)
                    val card = Ps2Memcard.open(bytes)
                    if (card != null) {
                        _uiState.value = CardUiState.Loaded(
                            cardName = current.cardName,
                            cardUri = current.cardUri,
                            memcard = card,
                            saves = emptyList(),
                            stats = card.getStats()
                        )
                        _snackbarMessage.value = "Memory card formatted successfully."
                    }
                } catch (e: Exception) {
                    _uiState.value = CardUiState.Error("Format error: ${e.message}")
                }
            }
        }
    }

    fun deleteSave(saveName: String) {
        val current = _uiState.value as? CardUiState.Loaded ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    val success = current.memcard.deleteSave(saveName)
                    if (success) {
                        val saves = current.memcard.listSaves()
                        val stats = current.memcard.getStats()
                        _selectedSave.value = null
                        _uiState.value = current.copy(saves = saves, stats = stats)
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

    fun importPsu(psuBytes: ByteArray) {
        val current = _uiState.value as? CardUiState.Loaded ?: return
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Importing save...")
            withContext(Dispatchers.Default) {
                try {
                    val success = current.memcard.importPsu(psuBytes)
                    if (success) {
                        val saves = current.memcard.listSaves()
                        val stats = current.memcard.getStats()
                        _uiState.value = current.copy(saves = saves, stats = stats)
                        _snackbarMessage.value = "Imported save successfully!"
                    } else {
                        _uiState.value = current
                        _snackbarMessage.value = "Failed to import save (insufficient space or invalid format)."
                    }
                } catch (e: Exception) {
                    _uiState.value = current
                    _snackbarMessage.value = "Import error: ${e.message}"
                }
            }
        }
    }

    fun exportPsu(saveName: String): ByteArray? {
        val current = _uiState.value as? CardUiState.Loaded ?: return null
        return current.memcard.exportSaveAsPsu(saveName)
    }

    fun exportZip(saveName: String): ByteArray? {
        val current = _uiState.value as? CardUiState.Loaded ?: return null
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

    fun convertEcc(targetHasEcc: Boolean) {
        val current = _uiState.value as? CardUiState.Loaded ?: return
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Converting card ECC...")
            withContext(Dispatchers.Default) {
                try {
                    val newBytes = current.memcard.convertEcc(targetHasEcc)
                    val card = Ps2Memcard.open(newBytes)
                    if (card != null) {
                        val saves = card.listSaves()
                        val stats = card.getStats()
                        _uiState.value = CardUiState.Loaded(
                            cardName = current.cardName,
                            cardUri = current.cardUri,
                            memcard = card,
                            saves = saves,
                            stats = stats
                        )
                        _snackbarMessage.value = if (targetHasEcc) "Converted to ECC (528B/page)" else "Converted to RAW (512B/page)"
                    }
                } catch (e: Exception) {
                    _uiState.value = current
                    _snackbarMessage.value = "Conversion error: ${e.message}"
                }
            }
        }
    }

    fun createDemoCard() {
        viewModelScope.launch {
            _uiState.value = CardUiState.Loading("Creating Demo Memory Card...")
            withContext(Dispatchers.Default) {
                try {
                    val cardBytes = MemcardFormatter.format(8, true)
                    val card = Ps2Memcard.open(cardBytes)!!

                    // Add demo saves to showcase Material You UI
                    val demoSaves = listOf(
                        Triple("BASLUS-20268", "Kingdom Hearts II", "The World That Never Was"),
                        Triple("BASLUS-21445", "Final Fantasy X", "Besaid Island - Calm Lands"),
                        Triple("BASCUS-97198", "Gran Turismo 4", "100% Championship Complete"),
                        Triple("SLUS-20946", "Grand Theft Auto: San Andreas", "End of the Line - 100%"),
                        Triple("BESLES-51233", "Metal Gear Solid 3: Snake Eater", "Dremuchij South - Operation Snake Eater")
                    )

                    for ((code, title, sub) in demoSaves) {
                        val dummyIconSys = buildDemoIconSys(title, sub)
                        val dummyFiles = mapOf(
                            "icon.sys" to dummyIconSys,
                            "data01.bin" to ByteArray(4096) { 0x55.toByte() },
                            "saveinfo.dat" to "Demo Save Info for $title".toByteArray(Charsets.UTF_8)
                        )
                        val psu = PsuHandler.packPsu(
                            saveName = code,
                            dirEntry = Ps2DirectoryEntry(
                                mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                                        Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or
                                        Ps2DirectoryEntry.DF_EXECUTE or Ps2DirectoryEntry.DF_0400,
                                length = (dummyFiles.size + 2).toLong(),
                                created = Ps2Timestamp.now(),
                                cluster = 0,
                                dirEntry = 0,
                                modified = Ps2Timestamp.now(),
                                attr = 0,
                                name = code
                            ),
                            files = dummyFiles
                        )
                        card.importPsu(psu)
                    }

                    val saves = card.listSaves()
                    val stats = card.getStats()
                    _uiState.value = CardUiState.Loaded(
                        cardName = "Demo_Mcd001.ps2",
                        cardUri = null,
                        memcard = card,
                        saves = saves,
                        stats = stats
                    )
                    _snackbarMessage.value = "Demo Memory Card ready with 5 game saves!"
                } catch (e: Exception) {
                    _uiState.value = CardUiState.Error("Demo card error: ${e.message}")
                }
            }
        }
    }

    private fun buildDemoIconSys(title: String, subtitle: String): ByteArray {
        val bytes = ByteArray(0x1C0)
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("PS2D".toByteArray(Charsets.US_ASCII))
        buf.position(0x06)
        buf.putShort(title.length.toShort())
        buf.position(0x0C)
        buf.putInt(0xFF) // Transparency

        // Title at 0xC0
        buf.position(0xC0)
        val titleText = "$title\n$subtitle"
        val titleBytes = titleText.toByteArray(Charsets.UTF_8)
        buf.put(titleBytes, 0, minOf(titleBytes.size, 63))

        // Icon file name at 0x100
        buf.position(0x100)
        buf.put("icon.icn".toByteArray(Charsets.US_ASCII))

        return bytes
    }
}
