package xyz.mininxd.ps2memcards.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Creates and formats PS2 memory card images (8MB, 16MB, 32MB, 64MB, 128MB).
 *
 * Supports two creation strategies:
 * 1. [createUnformatted]: Creates an unformatted card image filled with 0xFF (flash erased state).
 *    Byte-for-byte identical to the cards created by PCSX2 / ARMSX2 (FileMcd_CreateNewCard).
 *    When loaded in PS2 BIOS, the PS2 detects it as unformatted and can format it cleanly.
 * 2. [format]: Creates a pre-formatted PS2 memory card filesystem image with Superblock,
 *    IFC, FAT, and Root Directory, while ensuring all unallocated flash blocks, spare areas,
 *    and backup blocks remain in the clean erased flash state (0xFF).
 */
object MemcardFormatter {

    /**
     * Creates a standard unformatted memory card image filled entirely with 0xFF (flash erased state).
     *
     * @param sizeInMB Card capacity in megabytes (8, 16, 32, 64, or 128).
     * @param useEcc If true, uses 528 bytes per page (required for standard PCSX2 / ARMSX2 .ps2 files).
     *               If false, uses 512 bytes per page (RAW format for .bin / .mc2 files).
     */
    fun createUnformatted(sizeInMB: Int = 8, useEcc: Boolean = true): ByteArray {
        val validSize = when (sizeInMB) {
            16 -> 16
            32 -> 32
            64 -> 64
            128 -> 128
            else -> 8
        }
        val rawPageSize = if (useEcc) 528 else 512
        val totalPages = validSize * 2048 // 2048 pages per MB
        val totalBytes = totalPages * rawPageSize
        val data = ByteArray(totalBytes)
        java.util.Arrays.fill(data, 0xFF.toByte())
        return data
    }

    /**
     * Creates and formats a memory card image with a valid PS2 filesystem.
     * All unallocated sectors and spare areas are kept in the clean erased flash state (0xFF).
     */
    fun format(sizeInMB: Int = 8, useEcc: Boolean = true): ByteArray {
        val validSize = when (sizeInMB) {
            16 -> 16
            32 -> 32
            64 -> 64
            128 -> 128
            else -> 8
        }

        val pageLen = 512
        val pagesPerCluster = 2
        val clusterSize = pageLen * pagesPerCluster // 1024 bytes
        val pagesPerBlock = 16
        val clustersPerBlock = pagesPerBlock / pagesPerCluster // 8 clusters per block

        val totalClusters = (validSize * 1024 * 1024) / clusterSize
        val totalBlocks = totalClusters / clustersPerBlock

        val backupBlock1 = (totalBlocks - 1).toLong()
        val backupBlock2 = (totalBlocks - 2).toLong()

        // FAT calculations
        // 256 FAT entries (4 bytes each) fit in 1 cluster (1024 bytes)
        val epc = clusterSize / 4
        val allocatableClustersEst = totalClusters - (8 + 2)
        var fatClusters = (allocatableClustersEst + epc - 1) / epc
        var indirectClusters = (fatClusters + epc - 1) / epc
        if (indirectClusters > 32) {
            indirectClusters = 32
            fatClusters = indirectClusters * epc
        }

        val ifcClusterStart = 8L // Block 1
        val fatClusterStart = ifcClusterStart + indirectClusters
        val allocOffset = fatClusterStart + fatClusters
        val allocEnd = backupBlock2 * clustersPerBlock - allocOffset

        val ifcList = IntArray(32) { 0 }
        for (i in 0 until indirectClusters) {
            if (i < 32) {
                ifcList[i] = (ifcClusterStart + i).toInt()
            }
        }

        val badBlockList = IntArray(32) { -1 }

        val superBlock = Ps2SuperBlock(
            magic = Ps2SuperBlock.MAGIC_STRING,
            version = "1.2.0.0",
            pageLen = pageLen,
            pagesPerCluster = pagesPerCluster,
            pagesPerBlock = pagesPerBlock,
            clustersPerCard = totalClusters.toLong(),
            allocOffset = allocOffset,
            allocEnd = allocEnd,
            rootdirCluster = 0L,
            backupBlock1 = backupBlock1,
            backupBlock2 = backupBlock2,
            ifcList = ifcList,
            badBlockList = badBlockList,
            cardType = 2,
            cardFlags = if (useEcc) 0x2B else 0x2A
        )

        val rawPageSize = if (useEcc) 528 else 512
        val totalPages = totalClusters * pagesPerCluster
        val outData = ByteArray(totalPages * rawPageSize)
        // Flash memory begins in erased state (0xFF)
        java.util.Arrays.fill(outData, 0xFF.toByte())

        fun writePage(pageIndex: Int, pageData: ByteArray) {
            val offset = pageIndex * rawPageSize
            System.arraycopy(pageData, 0, outData, offset, 512)
            if (useEcc) {
                val spare = Ps2Ecc.generateSpareArea(pageData)
                System.arraycopy(spare, 0, outData, offset + 512, 16)
            }
        }

        fun writeCluster(clusterIndex: Long, clusterData: ByteArray) {
            val startPage = (clusterIndex * pagesPerCluster).toInt()
            for (p in 0 until pagesPerCluster) {
                val pData = ByteArray(512)
                val srcPos = p * 512
                if (srcPos < clusterData.size) {
                    val len = minOf(512, clusterData.size - srcPos)
                    System.arraycopy(clusterData, srcPos, pData, 0, len)
                }
                writePage(startPage + p, pData)
            }
        }

        // 1. Write Superblock at Page 0
        val sbPage = ByteArray(512)
        val sbBytes = superBlock.toByteArray()
        System.arraycopy(sbBytes, 0, sbPage, 0, sbBytes.size)
        writePage(0, sbPage)

        // 2. Write Indirect FAT Clusters
        var currentFatCluster = fatClusterStart.toInt()
        for (i in 0 until indirectClusters) {
            val ifcBuf = ByteBuffer.allocate(clusterSize).order(ByteOrder.LITTLE_ENDIAN)
            for (j in 0 until epc) {
                if (currentFatCluster < allocOffset) {
                    ifcBuf.putInt(currentFatCluster++)
                } else {
                    ifcBuf.putInt(0xFFFFFFFF.toInt())
                }
            }
            writeCluster(ifcClusterStart + i, ifcBuf.array())
        }

        // 3. Write FAT Clusters
        val totalFatEntries = fatClusters * epc
        val fatBuf = ByteBuffer.allocate(fatClusters * clusterSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalFatEntries) {
            when {
                i == 0 -> fatBuf.putInt(0xFFFFFFFF.toInt()) // Cluster 0 (root dir EOF)
                i < allocEnd -> fatBuf.putInt(0x7FFFFFFF)    // Free allocatable cluster
                else -> fatBuf.putInt(0)                     // Past allocEnd (unallocated/reserved)
            }
        }
        val fatBytes = fatBuf.array()
        for (i in 0 until fatClusters) {
            val cData = ByteArray(clusterSize)
            System.arraycopy(fatBytes, i * clusterSize, cData, 0, clusterSize)
            writeCluster(fatClusterStart + i, cData)
        }

        // 4. Write Root Directory at cluster allocOffset
        val now = Ps2Timestamp.now()
        val rootDot = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                    Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or
                    Ps2DirectoryEntry.DF_EXECUTE or Ps2DirectoryEntry.DF_0400,
            length = 2,
            created = now,
            cluster = 0,
            dirEntry = 0,
            modified = now,
            attr = 0,
            name = "."
        )
        val rootDotDot = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                    Ps2DirectoryEntry.DF_WRITE or Ps2DirectoryEntry.DF_EXECUTE or
                    Ps2DirectoryEntry.DF_0400 or Ps2DirectoryEntry.DF_HIDDEN,
            length = 0,
            created = now,
            cluster = 0,
            dirEntry = 0,
            modified = now,
            attr = 0,
            name = ".."
        )
        val rootDotPage = rootDot.toByteArray()
        val rootDotDotPage = rootDotDot.toByteArray()
        writePage((allocOffset * pagesPerCluster).toInt(), rootDotPage)
        writePage((allocOffset * pagesPerCluster + 1).toInt(), rootDotDotPage)

        // All other clusters (unused clusters 1..7, allocatable clusters 42..8134,
        // reserved clusters, and backup blocks 1 and 2) remain cleanly in erased state (0xFF)
        return outData
    }
}
