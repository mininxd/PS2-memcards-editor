package com.armsx2.memcards.core

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance, standalone PS2 Memory Card Parser, Reader, and Editor.
 * Supports both RAW (512 bytes/page) and ECC (528 bytes/page) memory card images.
 */
class Ps2Memcard private constructor(
    private var rawData: ByteArray,
    val hasEcc: Boolean,
    var superBlock: Ps2SuperBlock
) {

    val clusterSize: Int get() = superBlock.clusterSize
    val totalCapacityBytes: Long get() = superBlock.totalCapacityBytes
    val totalCapacityMb: Double get() = superBlock.totalCapacityMb
    val allocOffset: Long get() = superBlock.allocOffset
    val totalClusters: Long get() = superBlock.clustersPerCard

    /**
     * Reads a single 512-byte page data from the card.
     */
    fun readPage(pageIndex: Int): ByteArray {
        val pageData = ByteArray(512)
        if (pageIndex < 0) return pageData

        val pageSize = if (hasEcc) 528L else 512L
        val offset = pageIndex.toLong() * pageSize

        if (offset >= 0 && offset + 512L <= rawData.size) {
            System.arraycopy(rawData, offset.toInt(), pageData, 0, 512)
        }
        return pageData
    }

    /**
     * Writes a single 512-byte page to the card (automatically updates ECC if needed).
     */
    fun writePage(pageIndex: Int, pageData: ByteArray) {
        if (pageIndex < 0) return

        if (hasEcc) {
            val offset = pageIndex.toLong() * 528L
            if (offset >= 0 && offset + 528L <= rawData.size) {
                val len = minOf(pageData.size, 512)
                System.arraycopy(pageData, 0, rawData, offset.toInt(), len)
                val spare = Ps2Ecc.generateSpareArea(pageData)
                System.arraycopy(spare, 0, rawData, (offset + 512L).toInt(), 16)
            }
        } else {
            val offset = pageIndex.toLong() * 512L
            if (offset >= 0 && offset + 512L <= rawData.size) {
                val len = minOf(pageData.size, 512)
                System.arraycopy(pageData, 0, rawData, offset.toInt(), len)
            }
        }
    }

    /**
     * Reads an entire cluster (typically 1024 bytes = 2 pages).
     */
    fun readCluster(clusterIndex: Long): ByteArray {
        val out = ByteArray(clusterSize)
        if (clusterIndex < 0 || clusterIndex >= totalClusters || clusterIndex == 0xFFFFFFFFL) {
            return out
        }

        val startPage = clusterIndex * superBlock.pagesPerCluster
        val totalPages = totalClusters * superBlock.pagesPerCluster
        var outOffset = 0

        for (p in 0 until superBlock.pagesPerCluster) {
            val pageIdx = startPage + p
            if (pageIdx < 0 || pageIdx >= totalPages) break
            val page = readPage(pageIdx.toInt())
            val toCopy = minOf(page.size, clusterSize - outOffset)
            System.arraycopy(page, 0, out, outOffset, toCopy)
            outOffset += toCopy
        }
        return out
    }

    /**
     * Writes an entire cluster (updating ECC per page).
     */
    fun writeCluster(clusterIndex: Long, clusterData: ByteArray) {
        if (clusterIndex < 0 || clusterIndex >= totalClusters || clusterIndex == 0xFFFFFFFFL) {
            return
        }

        val startPage = clusterIndex * superBlock.pagesPerCluster
        val totalPages = totalClusters * superBlock.pagesPerCluster
        val pageLen = superBlock.pageLen

        for (p in 0 until superBlock.pagesPerCluster) {
            val pageIdx = startPage + p
            if (pageIdx < 0 || pageIdx >= totalPages) break
            val pageData = ByteArray(pageLen)
            val srcOffset = p * pageLen
            if (srcOffset < clusterData.size) {
                val len = minOf(pageLen, clusterData.size - srcOffset)
                System.arraycopy(clusterData, srcOffset, pageData, 0, len)
            }
            writePage(pageIdx.toInt(), pageData)
        }
    }

    /**
     * Reads a FAT entry for a given allocatable cluster index.
     */
    fun getFatEntry(clusterIndex: Long): Long {
        if (clusterIndex < 0 || clusterIndex >= superBlock.allocatableClusters || clusterIndex == 0xFFFFFFFFL) {
            return 0L
        }

        val fatOffset = (clusterIndex % 256).toInt()
        val indirectIndex = (clusterIndex / 256).toInt()
        val indirectOffset = indirectIndex % 256
        val dblIndirectIndex = indirectIndex / 256

        if (dblIndirectIndex < 0 || dblIndirectIndex >= superBlock.ifcList.size) return 0L
        val ifcCluster = superBlock.ifcList[dblIndirectIndex].toLong() and 0xFFFFFFFFL
        if (ifcCluster <= 0L || ifcCluster >= totalClusters || ifcCluster == 0xFFFFFFFFL) return 0L

        // Read indirect FAT cluster
        val indirectData = readCluster(ifcCluster)
        val indirectBuf = ByteBuffer.wrap(indirectData).order(ByteOrder.LITTLE_ENDIAN)
        val fatClusterPos = indirectOffset * 4
        if (fatClusterPos + 4 > indirectData.size) return 0L

        indirectBuf.position(fatClusterPos)
        val fatCluster = indirectBuf.int.toLong() and 0xFFFFFFFFL
        if (fatCluster <= 0L || fatCluster >= totalClusters || fatCluster == 0xFFFFFFFFL) return 0L

        // Read FAT cluster
        val fatData = readCluster(fatCluster)
        val fatBuf = ByteBuffer.wrap(fatData).order(ByteOrder.LITTLE_ENDIAN)
        val entryPos = fatOffset * 4
        if (entryPos + 4 > fatData.size) return 0L

        fatBuf.position(entryPos)
        return fatBuf.int.toLong() and 0xFFFFFFFFL
    }

    /**
     * Writes a FAT entry for a given allocatable cluster index.
     */
    fun setFatEntry(clusterIndex: Long, value: Long) {
        if (clusterIndex < 0 || clusterIndex >= superBlock.allocatableClusters || clusterIndex == 0xFFFFFFFFL) {
            return
        }

        val fatOffset = (clusterIndex % 256).toInt()
        val indirectIndex = (clusterIndex / 256).toInt()
        val indirectOffset = indirectIndex % 256
        val dblIndirectIndex = indirectIndex / 256

        if (dblIndirectIndex < 0 || dblIndirectIndex >= superBlock.ifcList.size) return
        val ifcCluster = superBlock.ifcList[dblIndirectIndex].toLong() and 0xFFFFFFFFL
        if (ifcCluster <= 0L || ifcCluster >= totalClusters || ifcCluster == 0xFFFFFFFFL) return

        val indirectData = readCluster(ifcCluster)
        val indirectBuf = ByteBuffer.wrap(indirectData).order(ByteOrder.LITTLE_ENDIAN)
        val fatClusterPos = indirectOffset * 4
        if (fatClusterPos + 4 > indirectData.size) return

        indirectBuf.position(fatClusterPos)
        val fatCluster = indirectBuf.int.toLong() and 0xFFFFFFFFL
        if (fatCluster <= 0L || fatCluster >= totalClusters || fatCluster == 0xFFFFFFFFL) return

        val fatData = readCluster(fatCluster)
        val fatBuf = ByteBuffer.wrap(fatData).order(ByteOrder.LITTLE_ENDIAN)
        val entryPos = fatOffset * 4
        if (entryPos + 4 > fatData.size) return

        fatBuf.position(entryPos)
        fatBuf.putInt(value.toInt())
        writeCluster(fatCluster, fatData)
    }

    /**
     * Follows the cluster chain starting from `startCluster` (relative to allocOffset)
     * and reads all bytes.
     */
    fun readClusterChain(startCluster: Long, expectedLength: Long = -1): ByteArray {
        val maxAllocatable = superBlock.allocatableClusters
        if (startCluster == 0xFFFFFFFFL || startCluster < 0 || startCluster >= maxAllocatable) return ByteArray(0)

        val out = ByteArrayOutputStream()
        var curCluster = startCluster
        val visited = mutableSetOf<Long>()

        while (curCluster != 0xFFFFFFFFL && curCluster >= 0 && curCluster < maxAllocatable && !visited.contains(curCluster)) {
            visited.add(curCluster)
            val physicalCluster = allocOffset + curCluster
            if (physicalCluster < 0 || physicalCluster >= totalClusters) break

            val clusterBytes = readCluster(physicalCluster)
            out.write(clusterBytes)

            val fatEntry = getFatEntry(curCluster)
            if ((fatEntry and 0x80000000L) == 0L) {
                // Free cluster encountered unexpectedly
                break
            }

            if (fatEntry == 0xFFFFFFFFL) {
                // EOF
                break
            }

            curCluster = fatEntry and 0x7FFFFFFFL
        }

        val fullData = out.toByteArray()
        return if (expectedLength in 0 until fullData.size.toLong()) {
            val result = ByteArray(expectedLength.toInt())
            System.arraycopy(fullData, 0, result, 0, expectedLength.toInt())
            result
        } else {
            fullData
        }
    }

    /**
     * Lists all game saves stored on this memory card.
     */
    fun listSaves(): List<Ps2Save> {
        val saves = mutableListOf<Ps2Save>()

        // Root directory starts at cluster rootdirCluster (relative to allocOffset)
        val rootDirData = readClusterChain(superBlock.rootdirCluster)
        if (rootDirData.size < Ps2DirectoryEntry.ENTRY_SIZE) return emptyList()

        val rootDot = Ps2DirectoryEntry.parse(rootDirData, 0) ?: return emptyList()
        val rootEntriesCount = rootDot.length.toInt()
        val maxEntries = minOf(rootEntriesCount, rootDirData.size / Ps2DirectoryEntry.ENTRY_SIZE)

        for (i in 0 until maxEntries) {
            val offset = i * Ps2DirectoryEntry.ENTRY_SIZE
            val entry = Ps2DirectoryEntry.parse(rootDirData, offset) ?: continue

            if (!entry.isExists || !entry.isDirectory) continue
            if (entry.name == "." || entry.name == "..") continue

            // This is a save folder (e.g. "BASLUS-21445")
            val save = readSaveFromEntry(entry)
            if (save != null) {
                saves.add(save)
            }
        }

        return saves
    }

    private fun readSaveFromEntry(dirEntry: Ps2DirectoryEntry): Ps2Save? {
        val folderData = readClusterChain(dirEntry.cluster)
        if (folderData.size < Ps2DirectoryEntry.ENTRY_SIZE) return null

        val dotEntry = Ps2DirectoryEntry.parse(folderData, 0) ?: return null
        val entriesCount = dotEntry.length.toInt()
        val maxEntries = minOf(entriesCount, folderData.size / Ps2DirectoryEntry.ENTRY_SIZE)

        val files = mutableListOf<Ps2SaveFile>()
        var iconSys: Ps2IconSys? = null
        var iconBitmap: Bitmap? = null
        var totalBytes = 0L

        for (i in 0 until maxEntries) {
            val offset = i * Ps2DirectoryEntry.ENTRY_SIZE
            val fileEntry = Ps2DirectoryEntry.parse(folderData, offset) ?: continue

            if (!fileEntry.isExists || fileEntry.name == "." || fileEntry.name == "..") continue

            if (fileEntry.isFile) {
                val fileData = readClusterChain(fileEntry.cluster, fileEntry.length)
                totalBytes += fileEntry.length

                if (fileEntry.name.equals("icon.sys", ignoreCase = true)) {
                    iconSys = Ps2IconSys.parse(fileData)
                }

                files.add(
                    Ps2SaveFile(
                        name = fileEntry.name,
                        sizeInBytes = fileEntry.length,
                        modifiedDate = fileEntry.modified.toFormattedString(),
                        dirEntry = fileEntry,
                        data = fileData
                    )
                )
            }
        }

        // Try decoding icon texture from icon files
        val targetIconName = iconSys?.iconFile?.ifBlank { null }
        val iconFile = files.firstOrNull {
            if (targetIconName != null) it.name.equals(targetIconName, ignoreCase = true)
            else it.name.endsWith(".icn", ignoreCase = true) || it.name.endsWith(".ico", ignoreCase = true)
        } ?: files.firstOrNull { it.name.endsWith(".icn", ignoreCase = true) }

        if (iconFile?.data != null) {
            iconBitmap = Ps2IconDecoder.decodeTexture(iconFile.data)
        }

        val gameTitle = iconSys?.title?.ifBlank { null } ?: dirEntry.name
        val subtitle = iconSys?.subtitle ?: ""

        return Ps2Save(
            directoryName = dirEntry.name,
            title = gameTitle,
            subtitle = subtitle,
            sizeInBytes = totalBytes,
            createdDate = dirEntry.created.toFormattedString(),
            modifiedDate = dirEntry.modified.toFormattedString(),
            isProtected = dirEntry.isProtected,
            isHidden = dirEntry.isHidden,
            isPocketStation = dirEntry.isPocketStation,
            isPsx = dirEntry.isPsx,
            dirEntry = dirEntry,
            files = files,
            iconSys = iconSys,
            iconBitmap = iconBitmap
        )
    }

    /**
     * Reads a specific file data inside a save folder.
     */
    fun getSaveFileBytes(saveName: String, fileName: String): ByteArray? {
        val rootDirData = readClusterChain(superBlock.rootdirCluster)
        val maxEntries = rootDirData.size / Ps2DirectoryEntry.ENTRY_SIZE

        for (i in 0 until maxEntries) {
            val offset = i * Ps2DirectoryEntry.ENTRY_SIZE
            val entry = Ps2DirectoryEntry.parse(rootDirData, offset) ?: continue
            if (entry.isExists && entry.isDirectory && entry.name == saveName) {
                val folderData = readClusterChain(entry.cluster)
                val folderEntries = folderData.size / Ps2DirectoryEntry.ENTRY_SIZE
                for (j in 0 until folderEntries) {
                    val fOffset = j * Ps2DirectoryEntry.ENTRY_SIZE
                    val fEntry = Ps2DirectoryEntry.parse(folderData, fOffset) ?: continue
                    if (fEntry.isExists && fEntry.isFile && fEntry.name == fileName) {
                        return readClusterChain(fEntry.cluster, fEntry.length)
                    }
                }
            }
        }
        return null
    }

    /**
     * Deletes a save folder and frees all its clusters.
     */
    fun deleteSave(saveName: String): Boolean {
        val rootDirData = readClusterChain(superBlock.rootdirCluster)
        val maxEntries = rootDirData.size / Ps2DirectoryEntry.ENTRY_SIZE

        for (i in 0 until maxEntries) {
            val offset = i * Ps2DirectoryEntry.ENTRY_SIZE
            val entry = Ps2DirectoryEntry.parse(rootDirData, offset) ?: continue
            if (entry.isExists && entry.isDirectory && entry.name == saveName) {
                // Free files in save folder
                val folderData = readClusterChain(entry.cluster)
                val folderEntries = folderData.size / Ps2DirectoryEntry.ENTRY_SIZE
                for (j in 0 until folderEntries) {
                    val fOffset = j * Ps2DirectoryEntry.ENTRY_SIZE
                    val fEntry = Ps2DirectoryEntry.parse(folderData, fOffset) ?: continue
                    if (fEntry.isExists && fEntry.isFile && fEntry.cluster != 0xFFFFFFFFL) {
                        freeClusterChain(fEntry.cluster)
                    }
                }

                // Free save folder itself
                freeClusterChain(entry.cluster)

                // Mark root entry as deleted
                val deletedEntry = entry.copy(mode = entry.mode and Ps2DirectoryEntry.DF_EXISTS.inv())
                writeDirectoryEntryInCluster(superBlock.rootdirCluster, i, deletedEntry)
                return true
            }
        }
        return false
    }

    /**
     * Imports a .psu save archive onto this memory card.
     */
    fun importPsu(psuData: ByteArray): Boolean {
        val unpacked = PsuHandler.unpackPsu(psuData) ?: return false
        val saveName = unpacked.dirEntry.name.ifBlank { "IMPORT" }

        // If a save with the same name exists, delete it first
        deleteSave(saveName)

        val filesMap = unpacked.files
        val now = Ps2Timestamp.now()

        // 1. Allocate clusters and write all files
        val fileEntries = mutableListOf<Ps2DirectoryEntry>()
        for ((fName, fBytes) in filesMap) {
            val fCluster = if (fBytes.isNotEmpty()) {
                writeNewClusterChain(fBytes)
            } else {
                0xFFFFFFFFL
            }

            val fEntry = Ps2DirectoryEntry(
                mode = Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or
                        Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or Ps2DirectoryEntry.DF_0400,
                length = fBytes.size.toLong(),
                created = now,
                cluster = fCluster,
                dirEntry = 0,
                modified = now,
                attr = 0,
                name = fName
            )
            fileEntries.add(fEntry)
        }

        // 2. Build folder directory entries
        val folderEntriesCount = fileEntries.size + 2 // . and ..
        val folderBytes = ByteArray(folderEntriesCount * Ps2DirectoryEntry.ENTRY_SIZE)

        // "." entry
        val dot = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                    Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or
                    Ps2DirectoryEntry.DF_EXECUTE or Ps2DirectoryEntry.DF_0400,
            length = folderEntriesCount.toLong(),
            created = now,
            cluster = 0, // In '.' entry this is parent cluster
            dirEntry = 0,
            modified = now,
            attr = 0,
            name = "."
        )
        System.arraycopy(dot.toByteArray(), 0, folderBytes, 0, Ps2DirectoryEntry.ENTRY_SIZE)

        // ".." entry
        val dotDot = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                    Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or
                    Ps2DirectoryEntry.DF_EXECUTE,
            length = 0,
            created = now,
            cluster = 0,
            dirEntry = 0,
            modified = now,
            attr = 0,
            name = ".."
        )
        System.arraycopy(dotDot.toByteArray(), 0, folderBytes, Ps2DirectoryEntry.ENTRY_SIZE, Ps2DirectoryEntry.ENTRY_SIZE)

        // File entries
        for (i in fileEntries.indices) {
            val bytes = fileEntries[i].toByteArray()
            System.arraycopy(bytes, 0, folderBytes, (i + 2) * Ps2DirectoryEntry.ENTRY_SIZE, Ps2DirectoryEntry.ENTRY_SIZE)
        }

        // Write folder cluster chain
        val folderCluster = writeNewClusterChain(folderBytes)

        // 3. Add save folder entry into root directory
        val saveDirEntry = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                    Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or
                    Ps2DirectoryEntry.DF_EXECUTE or Ps2DirectoryEntry.DF_0400,
            length = folderEntriesCount.toLong(),
            created = now,
            cluster = folderCluster,
            dirEntry = 0,
            modified = now,
            attr = 0,
            name = saveName
        )

        return addEntryToRootDirectory(saveDirEntry)
    }

    /**
     * Exports a save folder as a .psu byte array.
     */
    fun exportSaveAsPsu(saveName: String): ByteArray? {
        val save = listSaves().firstOrNull { it.directoryName == saveName } ?: return null
        val filesMap = mutableMapOf<String, ByteArray>()
        for (f in save.files) {
            val data = f.data ?: getSaveFileBytes(saveName, f.name) ?: ByteArray(0)
            filesMap[f.name] = data
        }
        return PsuHandler.packPsu(saveName, save.dirEntry, filesMap)
    }

    private fun addEntryToRootDirectory(newEntry: Ps2DirectoryEntry): Boolean {
        val rootDirData = readClusterChain(superBlock.rootdirCluster)
        val rootDot = Ps2DirectoryEntry.parse(rootDirData, 0) ?: return false
        val currentEntriesCount = rootDot.length.toInt()
        val maxEntries = rootDirData.size / Ps2DirectoryEntry.ENTRY_SIZE

        // Find empty/deleted slot or append
        var targetIndex = -1
        for (i in 2 until maxEntries) {
            val entry = Ps2DirectoryEntry.parse(rootDirData, i * Ps2DirectoryEntry.ENTRY_SIZE)
            if (entry == null || !entry.isExists) {
                targetIndex = i
                break
            }
        }

        if (targetIndex != -1) {
            writeDirectoryEntryInCluster(superBlock.rootdirCluster, targetIndex, newEntry)
            val updatedDot = rootDot.copy(length = maxOf(currentEntriesCount, targetIndex + 1).toLong())
            writeDirectoryEntryInCluster(superBlock.rootdirCluster, 0, updatedDot)
            return true
        }

        return false
    }

    private fun writeDirectoryEntryInCluster(dirStartCluster: Long, entryIndex: Int, entry: Ps2DirectoryEntry) {
        val maxAllocatable = superBlock.allocatableClusters
        if (entryIndex < 0 || dirStartCluster < 0 || dirStartCluster >= maxAllocatable) return

        val offsetInBytes = entryIndex.toLong() * Ps2DirectoryEntry.ENTRY_SIZE
        val clusterOffset = (offsetInBytes / clusterSize).toInt()
        val offsetWithinCluster = (offsetInBytes % clusterSize).toInt()

        // Walk to clusterOffset
        var curCluster = dirStartCluster
        for (i in 0 until clusterOffset) {
            val fat = getFatEntry(curCluster)
            if (fat == 0xFFFFFFFFL || (fat and 0x80000000L) == 0L) return
            curCluster = fat and 0x7FFFFFFFL
            if (curCluster < 0 || curCluster >= maxAllocatable) return
        }

        val physicalCluster = allocOffset + curCluster
        if (physicalCluster < 0 || physicalCluster >= totalClusters) return

        val clusterData = readCluster(physicalCluster)
        val entryBytes = entry.toByteArray()
        if (offsetWithinCluster + entryBytes.size <= clusterData.size) {
            System.arraycopy(entryBytes, 0, clusterData, offsetWithinCluster, entryBytes.size)
            writeCluster(physicalCluster, clusterData)
        }
    }

    private fun writeNewClusterChain(data: ByteArray): Long {
        val clustersNeeded = maxOf(1, (data.size + clusterSize - 1) / clusterSize)
        val allocatedClusters = mutableListOf<Long>()

        // Search for free clusters
        var searchCluster = 1L
        val maxAllocatable = superBlock.allocatableClusters

        while (allocatedClusters.size < clustersNeeded && searchCluster < maxAllocatable) {
            val fat = getFatEntry(searchCluster)
            if ((fat and 0x80000000L) == 0L) {
                allocatedClusters.add(searchCluster)
            }
            searchCluster++
        }

        if (allocatedClusters.size < clustersNeeded) {
            return 0xFFFFFFFFL // Out of space
        }

        // Link clusters in FAT and write payloads
        for (i in 0 until clustersNeeded) {
            val c = allocatedClusters[i]
            val nextC = if (i == clustersNeeded - 1) 0xFFFFFFFFL else (0x80000000L or allocatedClusters[i + 1])
            setFatEntry(c, nextC)

            val chunk = ByteArray(clusterSize)
            val srcPos = i * clusterSize
            if (srcPos < data.size) {
                val len = minOf(clusterSize, data.size - srcPos)
                System.arraycopy(data, srcPos, chunk, 0, len)
            }
            writeCluster(allocOffset + c, chunk)
        }

        return allocatedClusters[0]
    }

    private fun freeClusterChain(startCluster: Long) {
        val maxAllocatable = superBlock.allocatableClusters
        if (startCluster < 0 || startCluster >= maxAllocatable || startCluster == 0xFFFFFFFFL) return

        var cur = startCluster
        val visited = mutableSetOf<Long>()
        while (cur != 0xFFFFFFFFL && cur >= 0 && cur < maxAllocatable && !visited.contains(cur)) {
            visited.add(cur)
            val fat = getFatEntry(cur)
            setFatEntry(cur, 0L) // Free in FAT
            if ((fat and 0x80000000L) == 0L || fat == 0xFFFFFFFFL) break
            cur = fat and 0x7FFFFFFFL
        }
    }

    /**
     * Calculates card statistics: free clusters, used clusters, free space.
     */
    fun getStats(): CardStats {
        var allocated = 0L
        var free = 0L
        val maxAllocatable = superBlock.allocatableClusters

        for (c in 0 until maxAllocatable) {
            val fat = getFatEntry(c)
            if ((fat and 0x80000000L) != 0L) {
                allocated++
            } else {
                free++
            }
        }

        val totalSpace = totalCapacityBytes
        val freeSpace = free * clusterSize
        val usedSpace = maxOf(0L, totalSpace - freeSpace)

        return CardStats(
            totalClusters = totalClusters,
            allocatableClusters = maxAllocatable,
            allocatedClusters = allocated,
            freeClusters = free,
            totalSpaceBytes = totalSpace,
            usedSpaceBytes = usedSpace,
            freeSpaceBytes = freeSpace,
            badBlocksCount = superBlock.badBlockList.count { it != -1 },
            hasEcc = hasEcc,
            pageSize = if (hasEcc) 528 else 512,
            clusterSize = clusterSize
        )
    }

    /**
     * Exports the raw memory card byte array.
     */
    fun toByteArray(): ByteArray {
        return rawData.copyOf()
    }

    /**
     * Converts ECC status of card and returns new byte array.
     */
    fun convertEcc(targetHasEcc: Boolean): ByteArray {
        return if (targetHasEcc && !hasEcc) {
            Ps2Ecc.convertRawToEcc(rawData)
        } else if (!targetHasEcc && hasEcc) {
            Ps2Ecc.convertEccToRaw(rawData)
        } else {
            rawData.copyOf()
        }
    }

    companion object {
        fun open(data: ByteArray): Ps2Memcard? {
            if (data.size < Ps2SuperBlock.SUPERBLOCK_SIZE) return null

            // First, attempt to parse the SuperBlock directly from offset 0
            // (Page 0 data is identical in RAW and ECC images because the 16-byte ECC
            // spare area comes after the 512-byte page data, while the SuperBlock is 340 bytes)
            var sb = Ps2SuperBlock.parse(data)
            var hasEcc: Boolean? = null

            if (sb != null) {
                val totalPages = sb.clustersPerCard * sb.pagesPerCluster
                val expectedEccSize = totalPages * 528L
                val expectedRawSize = totalPages * 512L

                hasEcc = when {
                    totalPages > 0 && data.size.toLong() == expectedEccSize -> true
                    totalPages > 0 && data.size.toLong() == expectedRawSize -> false
                    data.size % 528 == 0 && data.size % 512 != 0 -> true
                    data.size % 512 == 0 && data.size % 528 != 0 -> false
                    data.size % 528 == 0 -> true
                    else -> (sb.cardFlags and 0x01) != 0 || (sb.cardFlags and 0x02) != 0
                }
            } else if (data.size % 528 == 0) {
                // If direct parse failed but size is divisible by 528, try converting ECC to raw
                try {
                    val raw = Ps2Ecc.convertEccToRaw(data)
                    sb = Ps2SuperBlock.parse(raw)
                    if (sb != null) {
                        hasEcc = true
                    }
                } catch (_: Exception) {
                }
            }

            if (sb == null || hasEcc == null) {
                return null
            }

            return Ps2Memcard(data, hasEcc = hasEcc, superBlock = sb)
        }
    }
}

/**
 * Detailed memory card statistics.
 */
data class CardStats(
    val totalClusters: Long,
    val allocatableClusters: Long,
    val allocatedClusters: Long,
    val freeClusters: Long,
    val totalSpaceBytes: Long,
    val usedSpaceBytes: Long,
    val freeSpaceBytes: Long,
    val badBlocksCount: Int,
    val hasEcc: Boolean,
    val pageSize: Int,
    val clusterSize: Int
) {
    val totalSpaceKb: Long get() = totalSpaceBytes / 1024
    val usedSpaceKb: Long get() = usedSpaceBytes / 1024
    val freeSpaceKb: Long get() = freeSpaceBytes / 1024
    val usedPercent: Float get() = if (totalSpaceBytes > 0) (usedSpaceBytes.toFloat() / totalSpaceBytes.toFloat()) else 0f
}
