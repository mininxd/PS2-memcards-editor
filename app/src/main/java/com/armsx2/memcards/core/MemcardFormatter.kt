package com.armsx2.memcards.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Creates and formats PS2 memory card images (8MB, 16MB, 32MB, 64MB, 128MB).
 */
object MemcardFormatter {

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

        // Raw buffer in memory (512 bytes per page)
        val rawSize = totalClusters * clusterSize
        val rawData = ByteArray(rawSize)

        // Write Superblock at Cluster 0, Page 0
        val sbBytes = superBlock.toByteArray()
        System.arraycopy(sbBytes, 0, rawData, 0, sbBytes.size)

        // Write Indirect FAT
        var currentFatCluster = fatClusterStart.toInt()
        val indirectBuf = ByteBuffer.allocate(indirectClusters * clusterSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until indirectClusters) {
            for (j in 0 until epc) {
                if (currentFatCluster < allocOffset) {
                    indirectBuf.putInt(currentFatCluster++)
                } else {
                    indirectBuf.putInt(0xFFFFFFFF.toInt())
                }
            }
        }
        val ifcBytes = indirectBuf.array()
        System.arraycopy(ifcBytes, 0, rawData, (ifcClusterStart * clusterSize).toInt(), ifcBytes.size)

        // Write FAT Table
        // Root directory occupies cluster 0: FAT[0] = 0xFFFFFFFF (bit 31 set + EOF)
        // All other clusters free: FAT[i] = 0x7FFFFFFF (PS2MC_FAT_CHAIN_END_UNALLOC)
        val fatBuf = ByteBuffer.allocate(fatClusters * clusterSize).order(ByteOrder.LITTLE_ENDIAN)
        val totalFatEntries = (fatClusters * clusterSize) / 4
        for (i in 0 until totalFatEntries) {
            fatBuf.putInt(0x7FFFFFFF)
        }
        fatBuf.putInt(0, 0xFFFFFFFF.toInt()) // Cluster 0 (root dir)
        val fatBytes = fatBuf.array()
        System.arraycopy(fatBytes, 0, rawData, (fatClusterStart * clusterSize).toInt(), fatBytes.size)

        // Write Root Directory (Cluster allocOffset)
        val rootDirPos = (allocOffset * clusterSize).toInt()
        val now = Ps2Timestamp.now()

        // Root dir "." entry
        val rootDot = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                    Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or
                    Ps2DirectoryEntry.DF_EXECUTE or Ps2DirectoryEntry.DF_0400,
            length = 2, // "." and ".."
            created = now,
            cluster = 0,
            dirEntry = 0,
            modified = now,
            attr = 0,
            name = "."
        )
        val rootDotBytes = rootDot.toByteArray()
        System.arraycopy(rootDotBytes, 0, rawData, rootDirPos, rootDotBytes.size)

        // Root dir ".." entry (Sony mcman McCreateDirentry: mode = 0xA426, hidden, write/execute, no read)
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
        val rootDotDotBytes = rootDotDot.toByteArray()
        System.arraycopy(rootDotDotBytes, 0, rawData, rootDirPos + Ps2DirectoryEntry.ENTRY_SIZE, rootDotDotBytes.size)

        // Backup block 2 must be in erased flash state (all 0xFF including spare area)
        // Sony mcman checkBackupBlocks checks backup block 2 to ensure no programming was interrupted
        return if (useEcc) {
            val eccData = Ps2Ecc.convertRawToEcc(rawData)
            val backup2Start = (backupBlock2 * pagesPerBlock * 528).toInt()
            val backup2Len = pagesPerBlock * 528
            java.util.Arrays.fill(eccData, backup2Start, backup2Start + backup2Len, 0xFF.toByte())
            eccData
        } else {
            val backup2Start = (backupBlock2 * pagesPerBlock * 512).toInt()
            val backup2Len = pagesPerBlock * 512
            java.util.Arrays.fill(rawData, backup2Start, backup2Start + backup2Len, 0xFF.toByte())
            rawData
        }
    }
}
