package xyz.mininxd.ps2memcards.core

import java.io.File

/**
 * Handles PCSX2 Folder-based Memory Cards and file <-> folder conversions.
 * Fully compatible with PCSX2 folder memory card format, including `_pcsx2_superblock`
 * and `_pcsx2_index` metadata files.
 */
object FolderMemcardHandler {

    const val SUPERBLOCK_FILENAME = "_pcsx2_superblock"
    const val INDEX_FILENAME = "_pcsx2_index"
    const val BLOCK_SIZE = 8192

    data class ParsedPcsx2Index(
        val rootCreated: Ps2Timestamp? = null,
        val rootModified: Ps2Timestamp? = null,
        val fileOrder: Map<String, Int> = emptyMap(),
        val fileCreated: Map<String, Ps2Timestamp> = emptyMap(),
        val fileModified: Map<String, Ps2Timestamp> = emptyMap()
    )

    /**
     * Checks whether a directory is a valid PCSX2 folder memory card.
     */
    fun isFolderMemcard(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val sbFile = File(dir, SUPERBLOCK_FILENAME)
        if (!sbFile.exists() || !sbFile.isFile) return false
        val bytes = try {
            sbFile.readBytes()
        } catch (_: Throwable) {
            return false
        }
        return isSuperblockBytes(bytes)
    }

    /**
     * Checks whether a file is a valid PCSX2 `_pcsx2_superblock` file.
     */
    fun isSuperblockFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (file.name == SUPERBLOCK_FILENAME || file.name.endsWith(SUPERBLOCK_FILENAME)) {
            val bytes = try { file.readBytes() } catch (_: Throwable) { return false }
            return isSuperblockBytes(bytes)
        }
        if (file.length() in 340L..65536L) {
            val bytes = try { file.readBytes() } catch (_: Throwable) { return false }
            return isSuperblockBytes(bytes)
        }
        return false
    }

    /**
     * Checks whether raw byte array represents a PCSX2 superblock block.
     */
    fun isSuperblockBytes(data: ByteArray): Boolean {
        if (data.size < 28) return false
        val magic = String(data.copyOfRange(0, 28), Charsets.US_ASCII)
        if (!magic.startsWith("Sony PS2 Memory Card Format")) return false
        if (data.size >= Ps2SuperBlock.SUPERBLOCK_SIZE) {
            return Ps2SuperBlock.parse(data, 0) != null
        }
        return true
    }

    /**
     * Parses a `_pcsx2_index` file if present.
     */
    fun parseIndexFile(indexFile: File): ParsedPcsx2Index? {
        if (!indexFile.exists() || !indexFile.isFile) return null
        return try {
            val text = indexFile.readText(Charsets.UTF_8)
            parseIndexContent(text)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parses the YAML/JSON hybrid format used by PCSX2 `_pcsx2_index`.
     * Example: {$ROOT: {timeCreated: 1789222698,timeModified: 1789222700},gh.icn: {order: 1,...}}
     */
    fun parseIndexContent(content: String): ParsedPcsx2Index {
        var rootCreated: Ps2Timestamp? = null
        var rootModified: Ps2Timestamp? = null
        val fileOrder = mutableMapOf<String, Int>()
        val fileCreated = mutableMapOf<String, Ps2Timestamp>()
        val fileModified = mutableMapOf<String, Ps2Timestamp>()

        val entryRegex = Regex("""([\$a-zA-Z0-9_.\-]+)\s*:\s*\{([^}]+)\}""")
        for (match in entryRegex.findAll(content)) {
            val key = match.groupValues[1].trim()
            val body = match.groupValues[2]

            val tcMatch = Regex("""timeCreated\s*:\s*(\d+)""").find(body)
            val tmMatch = Regex("""timeModified\s*:\s*(\d+)""").find(body)
            val orderMatch = Regex("""order\s*:\s*(\d+)""").find(body)

            val tc = tcMatch?.groupValues?.get(1)?.toLongOrNull()?.let { Ps2Timestamp.fromEpochSeconds(it) }
            val tm = tmMatch?.groupValues?.get(1)?.toLongOrNull()?.let { Ps2Timestamp.fromEpochSeconds(it) }
            val ord = orderMatch?.groupValues?.get(1)?.toIntOrNull()

            if (key == "\$ROOT") {
                rootCreated = tc
                rootModified = tm
            } else {
                if (ord != null) fileOrder[key] = ord
                if (tc != null) fileCreated[key] = tc
                if (tm != null) fileModified[key] = tm
            }
        }
        return ParsedPcsx2Index(rootCreated, rootModified, fileOrder, fileCreated, fileModified)
    }

    /**
     * Loads a PCSX2 folder memory card into a functional [Ps2Memcard] instance.
     */
    fun loadFolderMemcard(folderDir: File): Ps2Memcard? {
        if (!folderDir.exists() || !folderDir.isDirectory) return null
        val sbFile = File(folderDir, SUPERBLOCK_FILENAME)
        if (!sbFile.exists() || !sbFile.isFile) return null

        val sbBytes = try {
            sbFile.readBytes()
        } catch (_: Throwable) {
            return null
        }
        val sb = Ps2SuperBlock.parse(sbBytes, 0) ?: return null

        val totalBytes = sb.clustersPerCard * sb.pageLen * sb.pagesPerCluster
        val sizeInMB = maxOf(8, (totalBytes / (1024 * 1024)).toInt())
        val useEcc = (sb.cardFlags and 0x01) != 0

        val formattedData = MemcardFormatter.format(sizeInMB, useEcc)
        val card = Ps2Memcard.open(formattedData) ?: return null
        card.superBlock = sb

        val subDirs = folderDir.listFiles { f ->
            f.isDirectory && !f.name.startsWith(".") && !f.name.startsWith("_")
        } ?: emptyArray()

        subDirs.sortBy { it.name }

        for (saveDir in subDirs) {
            importSaveFolder(card, saveDir)
        }

        return card
    }

    /**
     * Imports a single save directory (e.g. BASLUS-21447) onto a memory card.
     */
    fun importSaveFolder(memcard: Ps2Memcard, saveDir: File): Boolean {
        if (!saveDir.exists() || !saveDir.isDirectory) return false
        val saveName = saveDir.name
        val saveFiles = saveDir.listFiles { f ->
            f.isFile && f.name != INDEX_FILENAME && !f.name.startsWith("_pcsx2_deleted_")
        } ?: return false

        val indexFile = File(saveDir, INDEX_FILENAME)
        val indexData = if (indexFile.exists()) parseIndexFile(indexFile) else null

        val rootCreated = indexData?.rootCreated
            ?: Ps2Timestamp.fromEpochSeconds(saveDir.lastModified() / 1000L)
        val rootModified = indexData?.rootModified
            ?: Ps2Timestamp.fromEpochSeconds(saveDir.lastModified() / 1000L)

        val entries = mutableListOf<PsuHandler.PsuEntry>()

        val sortedFiles = saveFiles.sortedWith { f1, f2 ->
            val o1 = indexData?.fileOrder?.get(f1.name) ?: Int.MAX_VALUE
            val o2 = indexData?.fileOrder?.get(f2.name) ?: Int.MAX_VALUE
            if (o1 != o2) o1.compareTo(o2) else f1.name.compareTo(f2.name)
        }

        for (f in sortedFiles) {
            val fileData = try {
                f.readBytes()
            } catch (_: Throwable) {
                continue
            }
            val fCreated = indexData?.fileCreated?.get(f.name)
                ?: Ps2Timestamp.fromEpochSeconds(f.lastModified() / 1000L)
            val fModified = indexData?.fileModified?.get(f.name)
                ?: Ps2Timestamp.fromEpochSeconds(f.lastModified() / 1000L)

            entries.add(
                PsuHandler.PsuEntry(
                    dirEntry = Ps2DirectoryEntry(
                        mode = Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or
                                Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
                        length = fileData.size.toLong(),
                        created = fCreated,
                        cluster = 0,
                        dirEntry = 0,
                        modified = fModified,
                        attr = 0,
                        name = f.name
                    ),
                    data = fileData
                )
            )
        }

        val unpacked = PsuHandler.UnpackedPsu(
            dirEntry = Ps2DirectoryEntry(
                mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                        Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
                length = (entries.size + 2).toLong(),
                created = rootCreated,
                cluster = 0,
                dirEntry = 0,
                modified = rootModified,
                attr = 0,
                name = saveName
            ),
            entries = entries
        )

        return memcard.importUnpackedSave(unpacked)
    }

    /**
     * Converts a File Memory Card (.ps2) to a directory structure.
     * Writes `_pcsx2_superblock`, save directories, files, and `_pcsx2_index`.
     */
    fun convertFileToFolder(memcard: Ps2Memcard, targetDir: File): Boolean {
        if (!targetDir.exists() && !targetDir.mkdirs()) return false

        // 1. Write _pcsx2_superblock (PCSX2 standard 8192 bytes = 1 block of 16 pages)
        val sbFile = File(targetDir, SUPERBLOCK_FILENAME)
        val sbBytes = ByteArray(BLOCK_SIZE)
        val rawSb = memcard.superBlock.toByteArray()
        System.arraycopy(rawSb, 0, sbBytes, 0, minOf(rawSb.size, BLOCK_SIZE))
        sbFile.writeBytes(sbBytes)

        // 2. Export each save directory and its files
        val saves = memcard.listSaves()
        val currentSaveNames = saves.map { it.directoryName }.toSet()

        // Clean up any save folders in targetDir that were deleted
        val existingDirs = targetDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in existingDirs) {
            if (!currentSaveNames.contains(dir.name) && !dir.name.startsWith(".")) {
                dir.deleteRecursively()
            }
        }

        for (save in saves) {
            val saveFolder = File(targetDir, save.directoryName)
            if (!saveFolder.exists()) {
                saveFolder.mkdirs()
            }

            // Write save files
            for (file in save.files) {
                val data = file.data ?: memcard.getSaveFileBytes(save.directoryName, file.name) ?: continue
                val outFile = File(saveFolder, file.name)
                outFile.writeBytes(data)
            }

            // Write _pcsx2_index
            val indexSb = java.lang.StringBuilder()
            indexSb.append("{\$ROOT: {timeCreated: ${save.dirEntry.created.toEpochSeconds()},timeModified: ${save.dirEntry.modified.toEpochSeconds()}}")
            var order = 1
            for (file in save.files) {
                indexSb.append(",${file.name}: {order: ${order++},timeCreated: ${file.dirEntry.created.toEpochSeconds()},timeModified: ${file.dirEntry.modified.toEpochSeconds()}}")
            }
            indexSb.append("}\n")

            val indexFile = File(saveFolder, INDEX_FILENAME)
            indexFile.writeText(indexSb.toString(), Charsets.UTF_8)
        }
        return true
    }

    /**
     * Converts a Folder Memory Card to a formatted .ps2 raw/ECC memory card byte array.
     */
    fun convertFolderToFile(folderDir: File): ByteArray? {
        val card = loadFolderMemcard(folderDir) ?: return null
        return card.toByteArray()
    }
}
