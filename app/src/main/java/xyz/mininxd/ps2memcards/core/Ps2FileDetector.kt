package xyz.mininxd.ps2memcards.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * PS2 file and savegame format classification.
 */
enum class Ps2FileType {
    PS2_MEMCARD_IMAGE,       // Standard raw / ECC .ps2, .mcd, .raw, .mc2, .vmc memory card image
    PS2_FOLDER_MEMCARD,      // PCSX2 Folder memory card (via _pcsx2_superblock or directory)
    SAVEGAME_PSU,            // EMS / mymc .psu save archive
    SAVEGAME_MAX,            // Datel Action Replay MAX (.max)
    SAVEGAME_CBS,            // Pelican CodeBreaker (.cbs)
    SAVEGAME_XPS,            // Datel SharkPort / X-Port (.xps)
    SAVEGAME_FOLDER,         // A save directory containing save files (e.g. icon.sys)
    INVALID                  // Not a recognized PS2 file or savegame
}

/**
 * Detailed result of file detection.
 */
data class DetectionResult(
    val fileType: Ps2FileType,
    val resolvedFile: File? = null,
    val folderDir: File? = null,
    val description: String = "",
    val isValid: Boolean = fileType != Ps2FileType.INVALID
) {
    val isMemcard: Boolean
        get() = fileType == Ps2FileType.PS2_MEMCARD_IMAGE || fileType == Ps2FileType.PS2_FOLDER_MEMCARD

    val isSavegame: Boolean
        get() = fileType == Ps2FileType.SAVEGAME_PSU ||
                fileType == Ps2FileType.SAVEGAME_MAX ||
                fileType == Ps2FileType.SAVEGAME_CBS ||
                fileType == Ps2FileType.SAVEGAME_XPS ||
                fileType == Ps2FileType.SAVEGAME_FOLDER
}

/**
 * High-accuracy format detector for PS2 memory cards, folder cards, and savegame archives.
 */
object Ps2FileDetector {

    /**
     * Detects file type from raw byte array and optional filename.
     */
    fun detect(data: ByteArray, fileName: String? = null): Ps2FileType {
        val nameLower = fileName?.lowercase() ?: ""

        // 1. Check for PCSX2 Folder Memory Card superblock
        if (isSuperblockData(data, nameLower)) {
            return Ps2FileType.PS2_FOLDER_MEMCARD
        }

        // 2. Check for standard PS2 memory card image (.ps2, .raw, etc.)
        if (isMemcardImageData(data, nameLower)) {
            return Ps2FileType.PS2_MEMCARD_IMAGE
        }

        // 3. Check for savegame formats
        if (MaxHandler.isMax(data)) {
            return Ps2FileType.SAVEGAME_MAX
        }
        if (CbsHandler.isCbs(data)) {
            return Ps2FileType.SAVEGAME_CBS
        }
        if (XpsHandler.isXps(data)) {
            return Ps2FileType.SAVEGAME_XPS
        }
        if (isPsuData(data)) {
            return Ps2FileType.SAVEGAME_PSU
        }

        return Ps2FileType.INVALID
    }

    /**
     * Detects file type from a [File] on disk (file or directory).
     */
    fun detect(file: File): DetectionResult {
        if (!file.exists()) {
            return DetectionResult(Ps2FileType.INVALID, description = "File does not exist")
        }

        if (file.isDirectory) {
            // Check if folder contains _pcsx2_superblock
            val sbFile = File(file, FolderMemcardHandler.SUPERBLOCK_FILENAME)
            if (sbFile.exists()) {
                val sbBytes = try { sbFile.readBytes() } catch (_: Throwable) { ByteArray(0) }
                if (isSuperblockData(sbBytes, FolderMemcardHandler.SUPERBLOCK_FILENAME)) {
                    return DetectionResult(
                        fileType = Ps2FileType.PS2_FOLDER_MEMCARD,
                        resolvedFile = sbFile,
                        folderDir = file,
                        description = "PCSX2 Folder Memory Card (${file.name})"
                    )
                }
            }

            // Check if it's a save directory (contains icon.sys or save files)
            if (File(file, "icon.sys").exists()) {
                return DetectionResult(
                    fileType = Ps2FileType.SAVEGAME_FOLDER,
                    resolvedFile = file,
                    folderDir = file,
                    description = "PS2 Savegame Folder (${file.name})"
                )
            }

            return DetectionResult(
                fileType = Ps2FileType.INVALID,
                description = "Directory is not a valid PS2 folder memory card or save folder"
            )
        }

        val name = file.name
        if (name == FolderMemcardHandler.SUPERBLOCK_FILENAME || name.endsWith(FolderMemcardHandler.SUPERBLOCK_FILENAME)) {
            val parent = file.parentFile
            val bytes = try { file.readBytes() } catch (_: Throwable) { ByteArray(0) }
            if (isSuperblockData(bytes, name)) {
                return DetectionResult(
                    fileType = Ps2FileType.PS2_FOLDER_MEMCARD,
                    resolvedFile = file,
                    folderDir = parent,
                    description = "PCSX2 Folder Memory Card (${parent?.name ?: "Folder"})"
                )
            }
        }

        val bytes = try {
            file.readBytes()
        } catch (_: Throwable) {
            return DetectionResult(Ps2FileType.INVALID, description = "Could not read file")
        }

        val type = detect(bytes, name)
        val desc = when (type) {
            Ps2FileType.PS2_MEMCARD_IMAGE -> "PS2 Memory Card Image (${file.name})"
            Ps2FileType.PS2_FOLDER_MEMCARD -> "PCSX2 Folder Memory Card (${file.parentFile?.name ?: file.name})"
            Ps2FileType.SAVEGAME_PSU -> "PSU Savegame Archive (${file.name})"
            Ps2FileType.SAVEGAME_MAX -> "Action Replay MAX Savegame (${file.name})"
            Ps2FileType.SAVEGAME_CBS -> "CodeBreaker Savegame (${file.name})"
            Ps2FileType.SAVEGAME_XPS -> "SharkPort / X-Port Savegame (${file.name})"
            Ps2FileType.SAVEGAME_FOLDER -> "PS2 Savegame Folder (${file.name})"
            Ps2FileType.INVALID -> "Not a valid PS2 memory card or savegame file"
        }

        return DetectionResult(
            fileType = type,
            resolvedFile = file,
            folderDir = if (type == Ps2FileType.PS2_FOLDER_MEMCARD) file.parentFile else null,
            description = desc
        )
    }

    /**
     * Detects file type from a content/file URI and content resolver.
     */
    fun detect(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String? = null,
        context: Context? = null
    ): DetectionResult {
        // Try resolving to java.io.File first for direct filesystem access
        val resolved = if (context != null) {
            StoragePermissionHelper.resolveFileFromUri(context, uri)
        } else if (uri.scheme == "file") {
            uri.path?.let { File(it) }
        } else null

        if (resolved != null && resolved.exists()) {
            return detect(resolved)
        }

        // If not directly accessible via java.io.File, read from stream
        val effectiveName = fileName ?: uri.lastPathSegment ?: "unknown"
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Throwable) { null }

        if (bytes == null || bytes.isEmpty()) {
            return DetectionResult(Ps2FileType.INVALID, description = "Could not read file from storage")
        }

        val type = detect(bytes, effectiveName)
        val desc = when (type) {
            Ps2FileType.PS2_MEMCARD_IMAGE -> "PS2 Memory Card Image ($effectiveName)"
            Ps2FileType.PS2_FOLDER_MEMCARD -> "PCSX2 Folder Memory Card ($effectiveName)"
            Ps2FileType.SAVEGAME_PSU -> "PSU Savegame Archive ($effectiveName)"
            Ps2FileType.SAVEGAME_MAX -> "Action Replay MAX Savegame ($effectiveName)"
            Ps2FileType.SAVEGAME_CBS -> "CodeBreaker Savegame ($effectiveName)"
            Ps2FileType.SAVEGAME_XPS -> "SharkPort / X-Port Savegame ($effectiveName)"
            Ps2FileType.SAVEGAME_FOLDER -> "PS2 Savegame Folder ($effectiveName)"
            Ps2FileType.INVALID -> "Not a valid PS2 memory card or savegame file"
        }

        return DetectionResult(
            fileType = type,
            resolvedFile = null,
            folderDir = null,
            description = desc
        )
    }

    /**
     * Checks if byte array and optional filename match a PCSX2 folder memory card superblock.
     */
    fun isSuperblockData(data: ByteArray, nameLower: String = ""): Boolean {
        val sbName = FolderMemcardHandler.SUPERBLOCK_FILENAME.lowercase()
        if (!nameLower.contains(sbName) && nameLower != sbName) {
            return false
        }
        if (data.size < 28) return false
        val magic = String(data.copyOfRange(0, minOf(28, data.size)), Charsets.US_ASCII)
        if (magic.startsWith("Sony PS2 Memory Card Format")) return true
        if (data.size in 340..65536) {
            val sb = Ps2SuperBlock.parse(data, 0)
            if (sb != null && sb.isFormatted()) return true
        }
        return false
    }

    /**
     * Checks if byte array represents a standard full-size PS2 memory card image.
     */
    fun isMemcardImageData(data: ByteArray, nameLower: String = ""): Boolean {
        if (data.size < 1024 * 1024) return false

        val sb = Ps2SuperBlock.parse(data, 0)
        if (sb != null && sb.isFormatted()) return true

        val magicBytes = Ps2SuperBlock.MAGIC_STRING.toByteArray(Charsets.US_ASCII)
        val maxSearch = minOf(data.size - Ps2SuperBlock.SUPERBLOCK_SIZE, 8192)
        for (offset in 1 until maxSearch) {
            var match = true
            for (m in magicBytes.indices) {
                if (data[offset + m] != magicBytes[m]) {
                    match = false
                    break
                }
            }
            if (match && Ps2SuperBlock.parse(data, offset) != null) {
                return true
            }
        }

        // Unformatted memory card (PCSX2 / ARMSX2 standard erase state filled with 0xFF)
        val size = data.size
        val isEccCandidate = (size % 528 == 0) && (size >= 8 * 1024 * 528 * 2)
        val isRawCandidate = (size % 512 == 0) && (size >= 8 * 1024 * 512 * 2)
        if (isEccCandidate || isRawCandidate) {
            var ffCount = 0
            val samples = 64
            val step = maxOf(1, size / samples)
            for (i in 0 until samples) {
                val idx = i * step
                if (idx < size && data[idx] == 0xFF.toByte()) {
                    ffCount++
                }
            }
            if (ffCount >= (samples * 3 / 4)) return true
        }

        return false
    }

    /**
     * Checks if byte array represents a valid .psu save archive.
     */
    fun isPsuData(data: ByteArray): Boolean {
        if (data.size < Ps2DirectoryEntry.ENTRY_SIZE) return false
        val entry = Ps2DirectoryEntry.parse(data, 0) ?: return false
        if (!entry.isDirectory) return false
        if (entry.name.isBlank() || entry.name == "." || entry.name == "..") return false
        if (entry.length < 2) return false
        val magic = String(data.copyOfRange(0, minOf(data.size, 28)), Charsets.US_ASCII)
        if (magic.startsWith("Sony PS2 Memory Card Format")) return false
        return true
    }
}
