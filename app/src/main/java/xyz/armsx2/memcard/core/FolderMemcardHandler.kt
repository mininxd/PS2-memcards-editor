package xyz.armsx2.memcard.core

import java.io.File

/**
 * Handles PCSX2 Folder-based Memory Cards and file <-> folder conversions.
 */
object FolderMemcardHandler {

    /**
     * Converts a File Memory Card (.ps2) to a directory structure.
     */
    fun convertFileToFolder(memcard: Ps2Memcard, targetDir: File): Boolean {
        if (!targetDir.exists() && !targetDir.mkdirs()) return false

        // 1. Write _pcsx2_superblock
        val sbFile = File(targetDir, "_pcsx2_superblock")
        sbFile.writeBytes(memcard.superBlock.toByteArray())

        // 2. Export each save directory and its files
        val saves = memcard.listSaves()
        for (save in saves) {
            val saveFolder = File(targetDir, save.directoryName)
            if (!saveFolder.exists()) {
                saveFolder.mkdirs()
            }

            for (file in save.files) {
                val data = file.data ?: memcard.getSaveFileBytes(save.directoryName, file.name) ?: continue
                val outFile = File(saveFolder, file.name)
                outFile.writeBytes(data)
            }
        }
        return true
    }

    /**
     * Converts a Folder Memory Card to a formatted .ps2 raw/ECC memory card byte array.
     */
    fun convertFolderToFile(folderDir: File, sizeInMB: Int = 8, useEcc: Boolean = true): ByteArray? {
        if (!folderDir.exists() || !folderDir.isDirectory) return null

        val formattedData = MemcardFormatter.format(sizeInMB, useEcc)
        val card = Ps2Memcard.open(formattedData) ?: return null

        val subDirs = folderDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (saveDir in subDirs) {
            val saveFiles = saveDir.listFiles { f -> f.isFile } ?: continue
            val filesMap = mutableMapOf<String, ByteArray>()
            for (f in saveFiles) {
                filesMap[f.name] = f.readBytes()
            }

            val psuData = PsuHandler.packPsu(
                saveName = saveDir.name,
                dirEntry = Ps2DirectoryEntry(
                    mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                            Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or
                            Ps2DirectoryEntry.DF_EXECUTE or Ps2DirectoryEntry.DF_0400,
                    length = (filesMap.size + 2).toLong(),
                    created = Ps2Timestamp.now(),
                    cluster = 0,
                    dirEntry = 0,
                    modified = Ps2Timestamp.now(),
                    attr = 0,
                    name = saveDir.name
                ),
                files = filesMap
            )
            card.importPsu(psuData)
        }

        return card.toByteArray()
    }
}
