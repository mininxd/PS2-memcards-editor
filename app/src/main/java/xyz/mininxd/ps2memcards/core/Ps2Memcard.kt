package xyz.mininxd.ps2memcards.core

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
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

    val isFormatted: Boolean get() = superBlock.isFormatted()
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
     * Returns direct reference to underlying rawData without allocating a copy.
     */
    fun getRawDataDirect(): ByteArray = rawData

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
                Ps2Ecc.writeSpareArea(rawData, offset.toInt(), rawData, (offset + 512L).toInt())
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

    var fatTable: IntArray = IntArray(0)
        private set

    /**
     * Preloads the FAT table into memory following myMCpp's readFatFromCard implementation.
     */
    fun loadFatFromCard() {
        if (!isFormatted) {
            fatTable = IntArray(0)
            return
        }
        val totalAllocatable = maxOf(superBlock.allocatableClusters, superBlock.allocEnd).toInt()
        if (totalAllocatable <= 0) return

        val fat = IntArray(totalAllocatable) { 0x7FFFFFFF }
        val epc = maxOf(1, clusterSize / 4)
        var fatEntry = 0

        for (dblOffset in 0 until 32) {
            if (fatEntry >= fat.size) break
            if (dblOffset >= superBlock.ifcList.size) break
            val indirectCluster = superBlock.ifcList[dblOffset].toLong() and 0xFFFFFFFFL
            if (indirectCluster == 0L || indirectCluster == 0xFFFFFFFFL || indirectCluster >= totalClusters) {
                continue
            }

            val indirectData = readCluster(indirectCluster)
            if (indirectData.isEmpty()) continue

            val indirectBuf = ByteBuffer.wrap(indirectData).order(ByteOrder.LITTLE_ENDIAN)

            for (indirectOffset in 0 until epc) {
                if (fatEntry >= fat.size) break
                val pos = indirectOffset * 4
                if (pos + 4 > indirectData.size) break

                val rawFatCluster = indirectBuf.getInt(pos).toLong() and 0xFFFFFFFFL
                val fatCluster = rawFatCluster and 0x7FFFFFFFL
                if (fatCluster == 0L || rawFatCluster == 0xFFFFFFFFL || fatCluster >= totalClusters) {
                    continue
                }

                val fatClusterData = readCluster(fatCluster)
                if (fatClusterData.isEmpty()) continue

                val fatClusterBuf = ByteBuffer.wrap(fatClusterData).order(ByteOrder.LITTLE_ENDIAN)
                val entriesInCluster = minOf(epc, fatClusterData.size / 4)

                for (j in 0 until entriesInCluster) {
                    if (fatEntry >= fat.size) break
                    fat[fatEntry] = fatClusterBuf.getInt(j * 4)
                    fatEntry++
                }
            }
        }
        fatTable = fat
    }

    /**
     * Writes the cached FAT table back to the memory card's physical FAT clusters.
     * Aligned with myMCpp's writeFatToCard implementation.
     */
    fun writeFatToCard(): Boolean {
        if (fatTable.isEmpty()) return false
        val epc = maxOf(1, clusterSize / 4)
        var fatEntryIdx = 0

        for (dblOffset in 0 until 32) {
            if (fatEntryIdx >= fatTable.size) break
            if (dblOffset >= superBlock.ifcList.size) break
            val indirectCluster = superBlock.ifcList[dblOffset].toLong() and 0xFFFFFFFFL
            if (indirectCluster == 0L || indirectCluster == 0xFFFFFFFFL || indirectCluster >= totalClusters) {
                continue
            }

            val indirectData = readCluster(indirectCluster)
            if (indirectData.isEmpty()) return false
            val indirectBuf = ByteBuffer.wrap(indirectData).order(ByteOrder.LITTLE_ENDIAN)

            for (indirectOffset in 0 until epc) {
                if (fatEntryIdx >= fatTable.size) break
                val pos = indirectOffset * 4
                if (pos + 4 > indirectData.size) break

                val rawFatCluster = indirectBuf.getInt(pos).toLong() and 0xFFFFFFFFL
                val fatClusterPhys = rawFatCluster and 0x7FFFFFFFL
                if (fatClusterPhys == 0L || rawFatCluster == 0xFFFFFFFFL || fatClusterPhys >= totalClusters) {
                    continue
                }

                val fatData = ByteArray(clusterSize)
                val fatBuf = ByteBuffer.wrap(fatData).order(ByteOrder.LITTLE_ENDIAN)
                val entriesInCluster = minOf(epc, fatData.size / 4)
                for (k in 0 until entriesInCluster) {
                    fatBuf.putInt(k * 4, 0x7FFFFFFF)
                }

                for (k in 0 until entriesInCluster) {
                    if (fatEntryIdx >= fatTable.size) break
                    fatBuf.putInt(k * 4, fatTable[fatEntryIdx])
                    fatEntryIdx++
                }

                writeCluster(fatClusterPhys, fatData)
            }
        }
        return true
    }

    /**
     * Reads a FAT entry for a given allocatable cluster index.
     * Uses cached FAT table if available, with on-demand fallback.
     */
    fun getFatEntry(clusterIndex: Long): Long {
        if (clusterIndex < 0 || clusterIndex == 0xFFFFFFFFL) return 0L
        if (clusterIndex < fatTable.size) {
            return fatTable[clusterIndex.toInt()].toLong() and 0xFFFFFFFFL
        }
        return readFatEntryDirect(clusterIndex)
    }

    private fun readFatEntryDirect(clusterIndex: Long): Long {
        if (clusterIndex < 0 || clusterIndex >= superBlock.allocatableClusters || clusterIndex == 0xFFFFFFFFL) {
            return 0L
        }

        val fatPerCluster = maxOf(1L, (clusterSize / 4).toLong())
        val fatOffset = (clusterIndex % fatPerCluster).toInt()
        val indirectIndex = clusterIndex / fatPerCluster
        val indirectOffset = (indirectIndex % fatPerCluster).toInt()
        val dblIndirectIndex = (indirectIndex / fatPerCluster).toInt()

        if (dblIndirectIndex < 0 || dblIndirectIndex >= superBlock.ifcList.size) return 0L
        val ifcCluster = superBlock.ifcList[dblIndirectIndex].toLong() and 0x7FFFFFFFL
        if (ifcCluster <= 0L || ifcCluster >= totalClusters) return 0L

        val indirectData = readCluster(ifcCluster)
        val indirectBuf = ByteBuffer.wrap(indirectData).order(ByteOrder.LITTLE_ENDIAN)
        val fatClusterPos = indirectOffset * 4
        if (fatClusterPos + 4 > indirectData.size) return 0L

        val rawFatCluster = indirectBuf.getInt(fatClusterPos).toLong() and 0xFFFFFFFFL
        val fatCluster = rawFatCluster and 0x7FFFFFFFL
        if (fatCluster <= 0L || fatCluster >= totalClusters || rawFatCluster == 0xFFFFFFFFL) return 0L

        val fatData = readCluster(fatCluster)
        val fatBuf = ByteBuffer.wrap(fatData).order(ByteOrder.LITTLE_ENDIAN)
        val entryPos = fatOffset * 4
        if (entryPos + 4 > fatData.size) return 0L

        return fatBuf.getInt(entryPos).toLong() and 0xFFFFFFFFL
    }

    /**
     * Writes a FAT entry for a given allocatable cluster index.
     */
    fun setFatEntry(clusterIndex: Long, value: Long) {
        if (clusterIndex < 0 || clusterIndex >= superBlock.allocatableClusters || clusterIndex == 0xFFFFFFFFL) {
            return
        }
        if (clusterIndex < fatTable.size) {
            fatTable[clusterIndex.toInt()] = value.toInt()
        }
        writeFatEntryDirect(clusterIndex, value)
    }

    private fun writeFatEntryDirect(clusterIndex: Long, value: Long) {
        val fatPerCluster = maxOf(1L, (clusterSize / 4).toLong())
        val fatOffset = (clusterIndex % fatPerCluster).toInt()
        val indirectIndex = clusterIndex / fatPerCluster
        val indirectOffset = (indirectIndex % fatPerCluster).toInt()
        val dblIndirectIndex = (indirectIndex / fatPerCluster).toInt()

        if (dblIndirectIndex < 0 || dblIndirectIndex >= superBlock.ifcList.size) return
        val ifcCluster = superBlock.ifcList[dblIndirectIndex].toLong() and 0x7FFFFFFFL
        if (ifcCluster <= 0L || ifcCluster >= totalClusters) return

        val indirectData = readCluster(ifcCluster)
        val indirectBuf = ByteBuffer.wrap(indirectData).order(ByteOrder.LITTLE_ENDIAN)
        val fatClusterPos = indirectOffset * 4
        if (fatClusterPos + 4 > indirectData.size) return

        val rawFatCluster = indirectBuf.getInt(fatClusterPos).toLong() and 0xFFFFFFFFL
        val fatCluster = rawFatCluster and 0x7FFFFFFFL
        if (fatCluster <= 0L || fatCluster >= totalClusters || rawFatCluster == 0xFFFFFFFFL) return

        val fatData = readCluster(fatCluster)
        val fatBuf = ByteBuffer.wrap(fatData).order(ByteOrder.LITTLE_ENDIAN)
        val entryPos = fatOffset * 4
        if (entryPos + 4 > fatData.size) return

        fatBuf.putInt(entryPos, value.toInt())
        writeCluster(fatCluster, fatData)
    }

    /**
     * Reads directory entries from a directory starting at `dirCluster` (relative to allocOffset).
     * Follows the FAT cluster chain matching myMCpp's readDirents implementation.
     */
    fun readDirents(dirCluster: Long): List<Ps2DirectoryEntry> {
        val entries = mutableListOf<Ps2DirectoryEntry>()
        val maxAllocatable = superBlock.allocatableClusters
        if (dirCluster < 0 || dirCluster >= maxAllocatable || dirCluster == 0xFFFFFFFFL) {
            return entries
        }

        var currentCluster = dirCluster
        var iteration = 0
        val visited = mutableSetOf<Long>()

        while (currentCluster != 0xFFFFFFFFL && currentCluster in 0 until maxAllocatable && !visited.contains(currentCluster)) {
            visited.add(currentCluster)
            iteration++
            if (iteration > 1000) break

            val diskCluster = allocOffset + currentCluster
            if (diskCluster >= totalClusters) break

            val clusterData = readCluster(diskCluster)
            if (clusterData.isEmpty()) break

            val entriesPerCluster = maxOf(1, clusterSize / Ps2DirectoryEntry.ENTRY_SIZE)
            for (i in 0 until entriesPerCluster) {
                val offset = i * Ps2DirectoryEntry.ENTRY_SIZE
                if (offset + Ps2DirectoryEntry.ENTRY_SIZE > clusterData.size) break

                val entry = Ps2DirectoryEntry.parse(clusterData, offset) ?: continue
                if (!entry.isExists) continue

                entries.add(entry)
            }

            val rawNext = getFatEntry(currentCluster)
            val next = rawNext and 0x7FFFFFFFL
            if (next == 0x7FFFFFFFL || rawNext == 0xFFFFFFFFL || next >= maxAllocatable) {
                break
            }
            currentCluster = next
        }

        return entries
    }

    /**
     * Reads all directory slots from a directory cluster (alias for readDirents).
     */
    fun readAllDirents(dirCluster: Long): List<Ps2DirectoryEntry> = readDirents(dirCluster)

    /**
     * Writes directory entries to a directory starting at `dirCluster` (relative to allocOffset).
     * Follows or expands the FAT cluster chain matching myMCpp's writeDirents implementation.
     */
    fun writeDirents(dirCluster: Long, entries: List<Ps2DirectoryEntry>): Boolean {
        val maxAllocatable = superBlock.allocatableClusters
        if (dirCluster < 0 || dirCluster >= maxAllocatable || dirCluster == 0xFFFFFFFFL) return false
        if (entries.isEmpty()) return true

        val entriesPerCluster = maxOf(1, clusterSize / Ps2DirectoryEntry.ENTRY_SIZE)
        var currentCluster = dirCluster
        var entryIdx = 0
        var iteration = 0

        while (entryIdx < entries.size) {
            iteration++
            if (iteration > 1000) return false

            val clusterData = ByteArray(clusterSize)
            for (i in 0 until entriesPerCluster) {
                if (entryIdx >= entries.size) break
                val packed = entries[entryIdx].toByteArray()
                System.arraycopy(packed, 0, clusterData, i * Ps2DirectoryEntry.ENTRY_SIZE, Ps2DirectoryEntry.ENTRY_SIZE)
                entryIdx++
            }

            val diskCluster = allocOffset + currentCluster
            if (diskCluster >= totalClusters) return false
            writeCluster(diskCluster, clusterData)

            if (entryIdx < entries.size) {
                val rawNext = getFatEntry(currentCluster)
                val next = rawNext and 0x7FFFFFFFL
                if (next >= maxAllocatable || next == 0x7FFFFFFFL || rawNext == 0xFFFFFFFFL) {
                    val newCluster = allocateCluster()
                    if (newCluster == 0xFFFFFFFFL) return false
                    setFatEntry(currentCluster, newCluster or 0x80000000L)
                    setFatEntry(newCluster, 0xFFFFFFFFL)
                    currentCluster = newCluster
                } else {
                    currentCluster = next
                }
            } else {
                break
            }
        }

        // Terminate FAT chain at currentCluster and free any remaining old clusters in chain
        val rawNext = getFatEntry(currentCluster)
        val next = rawNext and 0x7FFFFFFFL
        if (next != 0x7FFFFFFFL && rawNext != 0xFFFFFFFFL && next < maxAllocatable) {
            setFatEntry(currentCluster, 0xFFFFFFFFL)
            freeClusterChain(next)
        }

        return true
    }

    /**
     * Synchronizes a directory's length to its entry in the parent directory,
     * matching myMCpp's syncParentDirectoryEntryLength.
     */
    fun syncParentDirectoryEntryLength(childDirCluster: Long): Boolean {
        val child = readDirents(childDirCluster)
        if (child.isEmpty() || child[0].name != ".") return true
        val newLen = child[0].length
        val ancestor = child[0].cluster
        val slot = child[0].dirEntry.toInt()

        val ancestorEntries = readDirents(ancestor).toMutableList()
        var updated = false
        if (slot in ancestorEntries.indices && ancestorEntries[slot].isDirectory && ancestorEntries[slot].cluster == childDirCluster) {
            ancestorEntries[slot] = ancestorEntries[slot].copy(length = newLen)
            updated = true
        } else {
            for (i in ancestorEntries.indices) {
                val e = ancestorEntries[i]
                if (e.isDirectory && e.isExists && e.cluster == childDirCluster) {
                    ancestorEntries[i] = e.copy(length = newLen)
                    updated = true
                    break
                }
            }
        }
        if (updated) {
            val ok = writeDirents(ancestor, ancestorEntries)
            if (ok) writeFatToCard()
            return ok
        }
        return true
    }

    /**
     * Reads file data following the FAT chain starting at `startCluster` (relative to allocOffset).
     * Matches myMCpp's readFile implementation.
     */
    fun readFile(startCluster: Long, length: Long): ByteArray {
        if (length <= 0 || startCluster == 0xFFFFFFFFL || startCluster < 0) return ByteArray(0)
        val maxAllocatable = superBlock.allocatableClusters
        if (startCluster >= maxAllocatable) return ByteArray(0)

        val out = ByteArray(length.toInt())
        var currentCluster = startCluster
        var bytesRead = 0
        val visited = mutableSetOf<Long>()

        while (bytesRead < length && currentCluster != 0xFFFFFFFFL && currentCluster in 0 until maxAllocatable && !visited.contains(currentCluster)) {
            visited.add(currentCluster)
            val diskCluster = allocOffset + currentCluster
            if (diskCluster >= totalClusters) break

            val clusterData = readCluster(diskCluster)
            if (clusterData.isEmpty()) break

            val remaining = (length - bytesRead).toInt()
            val toRead = minOf(remaining, clusterSize)
            System.arraycopy(clusterData, 0, out, bytesRead, toRead)
            bytesRead += toRead

            val rawNext = getFatEntry(currentCluster)
            val next = rawNext and 0x7FFFFFFFL
            if (next == 0x7FFFFFFFL || rawNext == 0xFFFFFFFFL || next >= maxAllocatable) {
                break
            }
            currentCluster = next
        }

        return if (bytesRead == out.size) out else out.copyOf(bytesRead)
    }

    /**
     * Follows the cluster chain starting from `startCluster` (relative to allocOffset)
     * and reads all bytes.
     */
    fun readClusterChain(startCluster: Long, expectedLength: Long = -1): ByteArray {
        if (expectedLength >= 0) {
            return readFile(startCluster, expectedLength)
        }
        val maxAllocatable = superBlock.allocatableClusters
        if (startCluster == 0xFFFFFFFFL || startCluster < 0 || startCluster >= maxAllocatable) return ByteArray(0)

        val out = ByteArrayOutputStream()
        var curCluster = startCluster
        val visited = mutableSetOf<Long>()

        while (curCluster != 0xFFFFFFFFL && curCluster in 0 until maxAllocatable && !visited.contains(curCluster)) {
            visited.add(curCluster)
            val physicalCluster = allocOffset + curCluster
            if (physicalCluster >= totalClusters) break

            val clusterBytes = readCluster(physicalCluster)
            out.write(clusterBytes)

            val fatEntry = getFatEntry(curCluster)
            val nextCluster = fatEntry and 0x7FFFFFFFL
            if (nextCluster == 0x7FFFFFFFL || fatEntry == 0xFFFFFFFFL || nextCluster >= maxAllocatable) {
                break
            }
            curCluster = nextCluster
        }

        return out.toByteArray()
    }

    private var cachedSaves: List<Ps2Save>? = null

    fun invalidateSavesCache() {
        cachedSaves = null
    }

    /**
     * Lists all game saves stored on this memory card.
     * Caches decoded saves list in memory; invalidates on write/delete/import.
     */
    fun listSaves(forceRefresh: Boolean = false): List<Ps2Save> {
        if (!isFormatted) return emptyList()
        if (!forceRefresh && cachedSaves != null) {
            return cachedSaves!!
        }
        val saves = mutableListOf<Ps2Save>()
        val seenNames = mutableSetOf<String>()

        val rootCluster = if (superBlock.rootdirCluster >= allocOffset) {
            superBlock.rootdirCluster - allocOffset
        } else {
            superBlock.rootdirCluster
        }
        val rootEntries = readDirents(rootCluster)
        for (entry in rootEntries) {
            val name = entry.name.trim().trimEnd('\u0000')
            if (name.isBlank() || name == "." || name == ".." || seenNames.contains(name)) continue
            if (!entry.isExists) continue

            try {
                val save = buildSaveFromEntryLightweight(entry)
                if (save != null) {
                    saves.add(save)
                    seenNames.add(name)
                }
            } catch (_: Throwable) {
            }
        }

        cachedSaves = saves
        return saves
    }

    private fun buildSaveFromEntryLightweight(dirEntry: Ps2DirectoryEntry): Ps2Save? {
        val saveName = dirEntry.name.trim().trimEnd('\u0000')
        if (saveName.isBlank() || saveName == "." || saveName == "..") return null
        if (!dirEntry.isExists) return null

        // PS1 save file (single file in root directory)
        if (dirEntry.isPsx || (!dirEntry.isDirectory && dirEntry.length > 0 && dirEntry.cluster != 0xFFFFFFFFL)) {
            val subEntriesTest = if (dirEntry.cluster in 0 until superBlock.allocatableClusters) {
                readDirents(dirEntry.cluster)
            } else {
                emptyList()
            }
            val hasSubFiles = subEntriesTest.any { it.name != "." && it.name != ".." && it.isExists }

            if (!hasSubFiles) {
                val files = listOf(
                    Ps2SaveFile(
                        name = saveName,
                        sizeInBytes = dirEntry.length,
                        modifiedDate = dirEntry.modified.toFormattedString(),
                        dirEntry = dirEntry,
                        data = null
                    )
                )

                val cacheKey = "ps1:$saveName:${dirEntry.length}"
                var ps1Title: String? = null
                val iconBitmap = Ps2IconDecoder.getCached(cacheKey) ?: run {
                    val headerData = try {
                        readFile(dirEntry.cluster, minOf(dirEntry.length, 512L))
                    } catch (_: Throwable) {
                        null
                    }
                    if (headerData != null && headerData.isNotEmpty()) {
                        ps1Title = Ps2IconDecoder.extractPs1Title(headerData)
                        Ps2IconDecoder.decodePs1Icon(headerData, cacheKey = cacheKey)
                    } else null
                }

                val finalTitle = ps1Title ?: saveName

                return Ps2Save(
                    directoryName = saveName,
                    title = finalTitle,
                    subtitle = if (dirEntry.isPsx) "PlayStation Save" else "Save File",
                    sizeInBytes = dirEntry.length,
                    createdDate = dirEntry.created.toFormattedString(),
                    modifiedDate = dirEntry.modified.toFormattedString(),
                    isProtected = dirEntry.isProtected,
                    isHidden = dirEntry.isHidden,
                    isPocketStation = dirEntry.isPocketStation,
                    isPsx = dirEntry.isPsx,
                    dirEntry = dirEntry,
                    files = files,
                    iconSys = null,
                    iconBitmap = iconBitmap
                )
            }
        }

        // PS2 Save Directory
        val subEntries = if (dirEntry.cluster in 0 until superBlock.allocatableClusters) {
            readDirents(dirEntry.cluster)
        } else {
            emptyList()
        }

        val files = mutableListOf<Ps2SaveFile>()
        var iconSys: Ps2IconSys? = null
        var totalBytes = 0L

        for (fileEntry in subEntries) {
            val fName = fileEntry.name.trim().trimEnd('\u0000')
            if (!fileEntry.isExists || fName.isBlank() || fName == "." || fName == "..") continue

            if (!fileEntry.isDirectory) {
                totalBytes += fileEntry.length

                // If icon.sys, extract game title and subtitle
                if (fName.equals("icon.sys", ignoreCase = true)) {
                    val iconSysData = readFile(fileEntry.cluster, minOf(fileEntry.length.coerceAtLeast(964L), 2048L))
                    if (iconSysData.isNotEmpty()) {
                        iconSys = try {
                            Ps2IconSys.parse(iconSysData)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }

                files.add(
                    Ps2SaveFile(
                        name = fName,
                        sizeInBytes = fileEntry.length,
                        modifiedDate = fileEntry.modified.toFormattedString(),
                        dirEntry = fileEntry,
                        data = null
                    )
                )
            }
        }

        val gameTitle = iconSys?.title?.trim()?.ifBlank { null } ?: saveName
        val subtitle = iconSys?.subtitle?.trim() ?: ""

        // Decode save icon (3D model rasterized into 2D static image) into Bitmap if icon file exists
        val iconFileName = iconSys?.iconFile?.trim()?.ifBlank { null }
            ?: subEntries.firstOrNull { it.isExists && (it.name.endsWith(".icn", ignoreCase = true) || it.name.endsWith(".ico", ignoreCase = true)) }?.name
        val iconBitmap = if (iconFileName != null) {
            val iconEntry = subEntries.firstOrNull { it.isExists && it.name.equals(iconFileName, ignoreCase = true) }
            if (iconEntry != null && iconEntry.cluster != 0xFFFFFFFFL && iconEntry.length > 0) {
                val cacheKey = "ps2:$saveName:${iconEntry.name}:${iconEntry.length}"
                Ps2IconDecoder.getCached(cacheKey) ?: run {
                    val iconData = readFile(iconEntry.cluster, iconEntry.length)
                    if (iconData.isNotEmpty()) {
                        try {
                            Ps2IconDecoder.decodePs2Icon(iconData, iconSys = iconSys, cacheKey = cacheKey)
                        } catch (_: Throwable) {
                            null
                        }
                    } else null
                }
            } else null
        } else null

        return Ps2Save(
            directoryName = saveName,
            title = gameTitle,
            subtitle = subtitle,
            sizeInBytes = if (totalBytes > 0) totalBytes else dirEntry.length,
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
        val rootCluster = if (superBlock.rootdirCluster >= allocOffset) {
            superBlock.rootdirCluster - allocOffset
        } else {
            superBlock.rootdirCluster
        }
        val rootEntries = readDirents(rootCluster)
        val saveEntry = rootEntries.firstOrNull { it.name.trim().trimEnd('\u0000') == saveName }
            ?: return null

        if (!saveEntry.isDirectory) {
            return if (saveEntry.name.trim().trimEnd('\u0000') == fileName) {
                val bytes = readFile(saveEntry.cluster, saveEntry.length)
                if (bytes.isNotEmpty()) bytes else null
            } else {
                null
            }
        }

        val subEntries = readDirents(saveEntry.cluster)
        val fEntry = subEntries.firstOrNull { it.name.trim().trimEnd('\u0000').equals(fileName, ignoreCase = true) }
            ?: return null

        return readFile(fEntry.cluster, fEntry.length)
    }

    /**
     * Finds an unallocated cluster on the memory card.
     */
    fun allocateCluster(): Long {
        for (i in 1 until fatTable.size) {
            val fat = fatTable[i].toLong() and 0xFFFFFFFFL
            if ((fat and 0x80000000L) == 0L && fat == 0x7FFFFFFFL) {
                setFatEntry(i.toLong(), 0xFFFFFFFFL)
                return i.toLong()
            }
        }
        return 0xFFFFFFFFL
    }

    /**
     * Allocates a sequence of clusters and chains them in the FAT.
     */
    fun allocateClusters(count: Int): List<Long> {
        val clusters = mutableListOf<Long>()
        for (i in 0 until count) {
            val cluster = allocateCluster()
            if (cluster == 0xFFFFFFFFL) {
                for (c in clusters) {
                    setFatEntry(c, 0x7FFFFFFFL)
                }
                return emptyList()
            }
            clusters.add(cluster)
            if (i > 0) {
                setFatEntry(clusters[i - 1], cluster or 0x80000000L)
            }
        }
        if (clusters.isNotEmpty()) {
            setFatEntry(clusters.last(), 0xFFFFFFFFL)
        }
        return clusters
    }

    /**
     * Frees a FAT cluster chain starting from `startCluster`.
     */
    fun freeClusterChain(startCluster: Long) {
        val maxAllocatable = superBlock.allocatableClusters
        if (startCluster < 0 || startCluster >= maxAllocatable || startCluster == 0xFFFFFFFFL) return

        var cur = startCluster
        val visited = mutableSetOf<Long>()
        while (cur != 0xFFFFFFFFL && cur >= 0 && cur < maxAllocatable && !visited.contains(cur)) {
            visited.add(cur)
            val raw = getFatEntry(cur)
            setFatEntry(cur, 0x7FFFFFFFL) // Unallocated in PS2 FAT is 0x7FFFFFFF
            if (raw == 0xFFFFFFFFL || (raw and 0x7FFFFFFFL) == 0x7FFFFFFFL || (raw and 0x7FFFFFFFL) >= maxAllocatable) {
                break
            }
            cur = raw and 0x7FFFFFFFL
        }
    }

    /**
     * Creates a directory on the card, following myMCpp's makeDir.
     * Accurately sets '.' and '..' entries, dirEntry slot index, cluster pointers,
     * and DF_0400 flags expected by the PS2 BIOS browser.
     */
    fun makeDir(dirName: String): Long {
        if (!isFormatted) return 0xFFFFFFFFL
        val rootCluster = if (superBlock.rootdirCluster >= allocOffset) {
            superBlock.rootdirCluster - allocOffset
        } else {
            superBlock.rootdirCluster
        }

        val parentEntries = readDirents(rootCluster).toMutableList()
        val existing = parentEntries.firstOrNull { it.isExists && it.name == dirName }
        if (existing != null) {
            return existing.cluster
        }

        val dirCluster = allocateCluster()
        if (dirCluster == 0xFFFFFFFFL) return 0xFFFFFFFFL

        val slotForNewDir = parentEntries.size
        val now = Ps2Timestamp.now()

        val newDirEntries = mutableListOf<Ps2DirectoryEntry>()
        val dotEntry = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
            length = 2,
            created = now,
            cluster = rootCluster,
            dirEntry = slotForNewDir.toLong(),
            modified = now,
            attr = 0,
            name = "."
        )
        newDirEntries.add(dotEntry)

        val dotDotEntry = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
            length = 0,
            created = now,
            cluster = 0,
            dirEntry = 0,
            modified = now,
            attr = 0,
            name = ".."
        )
        newDirEntries.add(dotDotEntry)

        if (!writeDirents(dirCluster, newDirEntries)) {
            setFatEntry(dirCluster, 0x7FFFFFFFL)
            return 0xFFFFFFFFL
        }

        val newDirEntry = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
            length = 2,
            created = now,
            cluster = dirCluster,
            dirEntry = 0,
            modified = now,
            attr = 0,
            name = dirName
        )

        parentEntries.add(newDirEntry)
        if (parentEntries.isNotEmpty() && parentEntries[0].isDirectory) {
            parentEntries[0] = parentEntries[0].copy(length = parentEntries.size.toLong())
        }

        if (!writeDirents(rootCluster, parentEntries)) {
            return 0xFFFFFFFFL
        }

        writeFatToCard()
        return dirCluster
    }

    /**
     * Writes a file into a directory matching myMCpp's writeFile.
     */
    fun writeFile(dirCluster: Long, fileName: String, data: ByteArray, entryTemplate: Ps2DirectoryEntry? = null): Boolean {
        if (!isFormatted) return false
        val clustersNeeded = if (data.isNotEmpty()) (data.size + clusterSize - 1) / clusterSize else 0
        val fileClusters = if (clustersNeeded > 0) {
            allocateClusters(clustersNeeded)
        } else {
            emptyList()
        }
        if (clustersNeeded > 0 && fileClusters.isEmpty()) {
            return false
        }

        var offset = 0
        for (cluster in fileClusters) {
            val remaining = data.size - offset
            val toWrite = minOf(clusterSize, remaining)
            val chunk = ByteArray(clusterSize)
            if (toWrite > 0) {
                System.arraycopy(data, offset, chunk, 0, toWrite)
            }
            writeCluster(allocOffset + cluster, chunk)
            offset += toWrite
        }

        val parentEntries = readDirents(dirCluster).toMutableList()
        val existingSlot = parentEntries.indexOfFirst { it.name == fileName }

        val now = Ps2Timestamp.now()
        val templateMode = entryTemplate?.mode ?: (Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400)
        // Ensure standard PS2 file attributes: DF_FILE | DF_EXISTS | DF_RWX | DF_0400, clear DF_PROTECTED so BIOS has full permit to delete/rewrite
        val fileMode = (templateMode and Ps2DirectoryEntry.DF_PROTECTED.inv()) or
                (Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400)

        val fileEntry = Ps2DirectoryEntry(
            mode = fileMode,
            length = data.size.toLong(),
            created = entryTemplate?.created ?: now,
            cluster = if (fileClusters.isEmpty()) 0xFFFFFFFFL else fileClusters[0],
            dirEntry = 0,
            modified = if (existingSlot != -1) now else (entryTemplate?.modified ?: now),
            attr = entryTemplate?.attr ?: 0L,
            name = fileName
        )

        if (existingSlot != -1) {
            val oldCluster = parentEntries[existingSlot].cluster
            if (oldCluster != 0xFFFFFFFFL) {
                freeClusterChain(oldCluster)
            }
            parentEntries[existingSlot] = fileEntry
        } else {
            parentEntries.add(fileEntry)
        }

        if (parentEntries.isNotEmpty() && parentEntries[0].name == ".") {
            parentEntries[0] = parentEntries[0].copy(length = parentEntries.size.toLong())
        }

        if (!writeDirents(dirCluster, parentEntries)) return false
        syncParentDirectoryEntryLength(dirCluster)
        writeFatToCard()
        return true
    }

    /**
     * Deletes a save folder and frees all its clusters.
     */
    fun deleteSave(saveName: String): Boolean {
        if (!isFormatted) return false
        val rootCluster = if (superBlock.rootdirCluster >= allocOffset) {
            superBlock.rootdirCluster - allocOffset
        } else {
            superBlock.rootdirCluster
        }

        val rootEntries = readDirents(rootCluster).toMutableList()
        val targetIdx = rootEntries.indexOfFirst { it.name.trim().trimEnd('\u0000') == saveName }
        if (targetIdx == -1) return false
        val entry = rootEntries[targetIdx]

        if (entry.isDirectory) {
            val subEntries = readDirents(entry.cluster)
            for (fEntry in subEntries) {
                if (fEntry.name != "." && fEntry.name != ".." && fEntry.cluster != 0xFFFFFFFFL) {
                    freeClusterChain(fEntry.cluster)
                }
            }
            freeClusterChain(entry.cluster)
        } else {
            if (entry.cluster != 0xFFFFFFFFL) {
                freeClusterChain(entry.cluster)
            }
        }

        rootEntries.removeAt(targetIdx)
        if (rootEntries.isNotEmpty() && rootEntries[0].isDirectory) {
            rootEntries[0] = rootEntries[0].copy(length = rootEntries.size.toLong())
        }

        // Update dirEntry backlink for all remaining subdirectories whose slot index changed
        for (i in rootEntries.indices) {
            val e = rootEntries[i]
            if (e.isDirectory && e.name != "." && e.name != "..") {
                val sub = readDirents(e.cluster).toMutableList()
                if (sub.isNotEmpty() && sub[0].name == ".") {
                    sub[0] = sub[0].copy(dirEntry = i.toLong())
                    writeDirents(e.cluster, sub)
                }
            }
        }

        writeDirents(rootCluster, rootEntries)
        writeFatToCard()
        invalidateSavesCache()
        Ps2IconDecoder.invalidate(saveName)
        return true
    }

    /**
     * Imports a save file archive (.psu or .max) onto this memory card.
     */
    fun importSave(saveData: ByteArray): Boolean {
        if (!isFormatted) return false
        val unpacked = when {
            MaxHandler.isMax(saveData) -> MaxHandler.unpackMax(saveData)
            CbsHandler.isCbs(saveData) -> CbsHandler.unpackCbs(saveData)
            XpsHandler.isXps(saveData) -> XpsHandler.unpackXps(saveData)
            else -> PsuHandler.unpackPsu(saveData)
        } ?: return false
        return importUnpackedSave(unpacked)
    }

    /**
     * Imports a .psu save archive onto this memory card.
     */
    fun importPsu(psuData: ByteArray): Boolean {
        return importSave(psuData)
    }

    /**
     * Imports an Action Replay MAX (.max) save archive onto this memory card.
     */
    fun importMax(maxData: ByteArray): Boolean {
        val unpacked = MaxHandler.unpackMax(maxData) ?: return false
        return importUnpackedSave(unpacked)
    }

    /**
     * Imports a CodeBreaker (.cbs) save archive onto this memory card.
     */
    fun importCbs(cbsData: ByteArray): Boolean {
        val unpacked = CbsHandler.unpackCbs(cbsData) ?: return false
        return importUnpackedSave(unpacked)
    }

    /**
     * Imports a SharkPort / X-Port (.xps) save archive onto this memory card.
     */
    fun importXps(xpsData: ByteArray): Boolean {
        val unpacked = XpsHandler.unpackXps(xpsData) ?: return false
        return importUnpackedSave(unpacked)
    }

    private fun importUnpackedSave(unpacked: PsuHandler.UnpackedPsu): Boolean {
        val saveName = unpacked.dirEntry.name.trim().trimEnd('\u0000').ifBlank { "IMPORT" }

        // If a save with the same name exists, delete it first
        deleteSave(saveName)

        val rootCluster = if (superBlock.rootdirCluster >= allocOffset) {
            superBlock.rootdirCluster - allocOffset
        } else {
            superBlock.rootdirCluster
        }

        // 1. Create directory on card
        val dirCluster = makeDir(saveName)
        if (dirCluster == 0xFFFFFFFFL) {
            return false
        }

        // 2. Write all files
        for (file in unpacked.entries) {
            val fName = file.dirEntry.name.trim().trimEnd('\u0000')
            if (fName.isBlank() || fName == "." || fName == "..") continue
            val ok = writeFile(dirCluster, fName, file.data, file.dirEntry)
            if (!ok) {
                return false
            }
        }

        // 3. Update the save directory's root directory entry with the original mode, timestamps, and attr
        val rootEntries = readDirents(rootCluster).toMutableList()
        val saveEntryIdx = rootEntries.indexOfFirst { it.isDirectory && (it.name == saveName || it.cluster == dirCluster) }
        if (saveEntryIdx != -1) {
            val currentEntry = rootEntries[saveEntryIdx]
            val origMode = unpacked.dirEntry.mode
            // Always ensure DF_DIRECTORY | DF_EXISTS | DF_RWX | DF_0400, clear DF_PROTECTED
            val newMode = (origMode and Ps2DirectoryEntry.DF_PROTECTED.inv()) or
                    (Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400)
            rootEntries[saveEntryIdx] = currentEntry.copy(
                cluster = dirCluster,
                mode = newMode,
                created = unpacked.dirEntry.created,
                modified = unpacked.dirEntry.modified,
                attr = unpacked.dirEntry.attr
            )
            writeDirents(rootCluster, rootEntries)
        }

        // 4. Ensure root directory length
        val finalRoot = readDirents(rootCluster).toMutableList()
        if (finalRoot.isNotEmpty() && finalRoot[0].isDirectory) {
            finalRoot[0] = finalRoot[0].copy(length = finalRoot.size.toLong())
            writeDirents(rootCluster, finalRoot)
        }

        // 5. Flush updated FAT to memory card image
        writeFatToCard()
        invalidateSavesCache()

        return true
    }

    /**
     * Exports a save folder as a .psu byte array.
     */
    fun exportSaveAsPsu(saveName: String): ByteArray? {
        if (!isFormatted) return null
        val save = listSaves().firstOrNull { it.directoryName == saveName } ?: return null
        val filesMap = mutableMapOf<String, ByteArray>()
        for (f in save.files) {
            val data = f.data ?: getSaveFileBytes(saveName, f.name) ?: ByteArray(0)
            filesMap[f.name] = data
        }
        return PsuHandler.packPsu(saveName, save.dirEntry, filesMap)
    }

    /**
     * Exports a save folder as an Action Replay MAX (.max) byte array.
     */
    fun exportSaveAsMax(saveName: String): ByteArray? {
        if (!isFormatted) return null
        val save = listSaves().firstOrNull { it.directoryName == saveName } ?: return null
        val filesMap = mutableMapOf<String, ByteArray>()
        for (f in save.files) {
            val data = f.data ?: getSaveFileBytes(saveName, f.name) ?: ByteArray(0)
            filesMap[f.name] = data
        }
        val title = save.title.ifBlank { saveName }
        return MaxHandler.packMax(saveName, title, filesMap)
    }

    /**
     * Exports a save folder as a CodeBreaker (.cbs) byte array.
     */
    fun exportSaveAsCbs(saveName: String): ByteArray? {
        if (!isFormatted) return null
        val save = listSaves().firstOrNull { it.directoryName == saveName } ?: return null
        val filesMap = mutableMapOf<String, ByteArray>()
        for (f in save.files) {
            val data = f.data ?: getSaveFileBytes(saveName, f.name) ?: ByteArray(0)
            filesMap[f.name] = data
        }
        val title = save.title.ifBlank { saveName }
        return CbsHandler.packCbs(saveName, save.dirEntry, filesMap, title)
    }

    /**
     * Exports a save folder as a SharkPort / X-Port (.xps) byte array.
     */
    fun exportSaveAsXps(saveName: String): ByteArray? {
        if (!isFormatted) return null
        val save = listSaves().firstOrNull { it.directoryName == saveName } ?: return null
        val filesMap = mutableMapOf<String, ByteArray>()
        for (f in save.files) {
            val data = f.data ?: getSaveFileBytes(saveName, f.name) ?: ByteArray(0)
            filesMap[f.name] = data
        }
        return XpsHandler.packXps(saveName, save.dirEntry, filesMap)
    }

    /**
     * Updates an individual file's contents inside a save folder (e.g. for Hex Hacking).
     */
    fun updateSaveFile(saveName: String, fileName: String, newData: ByteArray): Boolean {
        if (!isFormatted) return false
        val rootCluster = if (superBlock.rootdirCluster >= allocOffset) {
            superBlock.rootdirCluster - allocOffset
        } else {
            superBlock.rootdirCluster
        }
        val rootEntries = readDirents(rootCluster)
        val saveEntry = rootEntries.firstOrNull { it.name.trim().trimEnd('\u0000') == saveName } ?: return false
        val subEntries = readDirents(saveEntry.cluster)
        val template = subEntries.firstOrNull { it.name.trim().trimEnd('\u0000').equals(fileName, ignoreCase = true) }
        val ok = writeFile(saveEntry.cluster, fileName, newData, template)
        if (ok) {
            invalidateSavesCache()
        }
        return ok
    }

    /**
     * Calculates card statistics: free clusters, used clusters, free space.
     */
    fun getStats(): CardStats {
        if (!isFormatted) {
            return CardStats(
                totalClusters = totalClusters,
                allocatableClusters = 0,
                allocatedClusters = 0,
                freeClusters = 0,
                totalSpaceBytes = totalCapacityBytes,
                usedSpaceBytes = 0,
                freeSpaceBytes = totalCapacityBytes,
                badBlocksCount = 0,
                hasEcc = hasEcc,
                pageSize = if (hasEcc) 528 else 512,
                clusterSize = clusterSize,
                isFormatted = false
            )
        }
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

            var cardData = data
            var sb = Ps2SuperBlock.parse(cardData, 0)

            if (sb == null) {
                val magicBytes = Ps2SuperBlock.MAGIC_STRING.toByteArray(Charsets.US_ASCII)
                val maxSearch = minOf(data.size - Ps2SuperBlock.SUPERBLOCK_SIZE, 8192)
                var foundOffset = -1
                for (offset in 1 until maxSearch) {
                    var match = true
                    for (m in magicBytes.indices) {
                        if (data[offset + m] != magicBytes[m]) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        foundOffset = offset
                        break
                    }
                }
                if (foundOffset > 0) {
                    sb = Ps2SuperBlock.parse(data, foundOffset)
                    if (sb != null) {
                        cardData = data.copyOfRange(foundOffset, data.size)
                    }
                }
            }

            var hasEcc: Boolean? = null

            if (sb != null) {
                val totalPages = sb.clustersPerCard * sb.pagesPerCluster
                val expectedEccSize = totalPages * 528L
                val expectedRawSize = totalPages * 512L

                hasEcc = when {
                    totalPages > 0 && cardData.size.toLong() == expectedEccSize -> true
                    totalPages > 0 && cardData.size.toLong() == expectedRawSize -> false
                    cardData.size % 528 == 0 && cardData.size % 512 != 0 -> true
                    cardData.size % 512 == 0 && cardData.size % 528 != 0 -> false
                    else -> {
                        if (cardData.size >= 528) {
                            val computed = Ps2Ecc.generateSpareArea(cardData, 0)
                            var matches = true
                            for (b in 0 until 12) {
                                if (cardData[512 + b] != computed[b]) {
                                    matches = false
                                    break
                                }
                            }
                            matches
                        } else {
                            (sb.cardFlags and 0x01) != 0
                        }
                    }
                }
            }

            if (sb == null || hasEcc == null) {
                // Detect unformatted memory card images (e.g. PCSX2 / ARMSX2 standard erase state filled with 0xFF)
                val size = cardData.size
                val isEccCandidate = (size % 528 == 0) && (size >= 8 * 1024 * 528 * 2)
                val isRawCandidate = (size % 512 == 0) && (size >= 8 * 1024 * 512 * 2)

                if (isEccCandidate || isRawCandidate) {
                    val detectedEcc = isEccCandidate
                    val pageSize = if (detectedEcc) 528 else 512
                    val totalPages = size / pageSize
                    val totalClusters = (totalPages / 2).toLong()
                    val unformattedSb = Ps2SuperBlock.createUnformatted(totalClusters, detectedEcc)
                    return Ps2Memcard(cardData, hasEcc = detectedEcc, superBlock = unformattedSb)
                }

                return null
            }

            val card = Ps2Memcard(cardData, hasEcc = hasEcc, superBlock = sb)
            card.loadFatFromCard()
            return card
        }
    }
}

/**
 * Detailed memory card statistics.
 */
@Immutable
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
    val clusterSize: Int,
    val isFormatted: Boolean = true
) {
    val totalSpaceKb: Long get() = totalSpaceBytes / 1024
    val usedSpaceKb: Long get() = usedSpaceBytes / 1024
    val freeSpaceKb: Long get() = freeSpaceBytes / 1024
    val usedPercent: Float get() = if (totalSpaceBytes > 0) (usedSpaceBytes.toFloat() / totalSpaceBytes.toFloat()) else 0f
}
