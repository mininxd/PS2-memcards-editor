package com.armsx2.memcards.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PS2 Memory Card Superblock (340 bytes)
 * Located at Page 0 of the memory card.
 */
data class Ps2SuperBlock(
    val magic: String = MAGIC_STRING,
    val version: String = "1.2.0.0",
    val pageLen: Int = 512,
    val pagesPerCluster: Int = 2,
    val pagesPerBlock: Int = 16,
    val clustersPerCard: Long = 8192L,
    val allocOffset: Long = 41L,
    val allocEnd: Long = 8135L,
    val rootdirCluster: Long = 0L,
    val backupBlock1: Long = 1023L,
    val backupBlock2: Long = 1022L,
    val ifcList: IntArray = IntArray(32) { if (it == 0) 8 else 0 },
    val badBlockList: IntArray = IntArray(32) { -1 },
    val cardType: Int = 2,
    val cardFlags: Int = 0x2B
) {
    val clusterSize: Int get() = pageLen * pagesPerCluster
    val blockSize: Int get() = pageLen * pagesPerBlock
    val totalCapacityBytes: Long get() = clustersPerCard * clusterSize
    val totalCapacityMb: Double get() = totalCapacityBytes.toDouble() / (1024.0 * 1024.0)

    val allocatableClusters: Long get() = clustersPerCard - allocOffset

    fun isFormatted(): Boolean {
        return magic.startsWith("Sony PS2 Memory Card Format")
    }

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(340)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // 0x00: Magic (28 bytes)
        val magicBytes = magic.toByteArray(Charsets.US_ASCII)
        buf.position(0)
        buf.put(magicBytes, 0, minOf(magicBytes.size, 28))

        // 0x1C: Version (12 bytes)
        val verBytes = version.toByteArray(Charsets.US_ASCII)
        buf.position(0x1C)
        buf.put(verBytes, 0, minOf(verBytes.size, 12))

        // 0x28: page_len
        buf.position(0x28)
        buf.putShort(pageLen.toShort())

        // 0x2A: pages_per_cluster
        buf.putShort(pagesPerCluster.toShort())

        // 0x2C: pages_per_block
        buf.putShort(pagesPerBlock.toShort())

        // 0x2E: unused
        buf.putShort(0xFF00.toShort())

        // 0x30: clusters_per_card
        buf.putInt(clustersPerCard.toInt())

        // 0x34: alloc_offset
        buf.putInt(allocOffset.toInt())

        // 0x38: alloc_end
        buf.putInt(allocEnd.toInt())

        // 0x3C: rootdir_cluster
        buf.putInt(rootdirCluster.toInt())

        // 0x40: backup_block1
        buf.putInt(backupBlock1.toInt())

        // 0x44: backup_block2
        buf.putInt(backupBlock2.toInt())

        // 0x50: ifc_list (32 words)
        buf.position(0x50)
        for (ifc in ifcList) {
            buf.putInt(ifc)
        }

        // 0xD0: bad_block_list (32 words)
        buf.position(0xD0)
        for (bad in badBlockList) {
            buf.putInt(bad)
        }

        // 0x150: card_type
        buf.position(0x150)
        buf.put(cardType.toByte())

        // 0x151: card_flags
        buf.put(cardFlags.toByte())

        return bytes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Ps2SuperBlock
        return magic == other.magic &&
                version == other.version &&
                pageLen == other.pageLen &&
                pagesPerCluster == other.pagesPerCluster &&
                pagesPerBlock == other.pagesPerBlock &&
                clustersPerCard == other.clustersPerCard &&
                allocOffset == other.allocOffset &&
                rootdirCluster == other.rootdirCluster
    }

    override fun hashCode(): Int {
        var result = magic.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + clustersPerCard.hashCode()
        return result
    }

    companion object {
        const val MAGIC_STRING = "Sony PS2 Memory Card Format "
        const val SUPERBLOCK_SIZE = 340

        fun parse(data: ByteArray): Ps2SuperBlock? {
            if (data.size < SUPERBLOCK_SIZE) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val magicBytes = ByteArray(28)
            buf.get(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII).trimEnd('\u0000')

            if (!magic.startsWith("Sony PS2 Memory Card Format")) {
                return null
            }

            buf.position(0x1C)
            val verBytes = ByteArray(12)
            buf.get(verBytes)
            val version = String(verBytes, Charsets.US_ASCII).trimEnd('\u0000')

            buf.position(0x28)
            val pageLen = buf.short.toInt() and 0xFFFF
            val pagesPerCluster = buf.short.toInt() and 0xFFFF
            val pagesPerBlock = buf.short.toInt() and 0xFFFF
            buf.short // unused

            val clustersPerCard = buf.int.toLong() and 0xFFFFFFFFL
            val allocOffset = buf.int.toLong() and 0xFFFFFFFFL
            val allocEnd = buf.int.toLong() and 0xFFFFFFFFL
            val rootdirCluster = buf.int.toLong() and 0xFFFFFFFFL
            val backupBlock1 = buf.int.toLong() and 0xFFFFFFFFL
            val backupBlock2 = buf.int.toLong() and 0xFFFFFFFFL

            buf.position(0x50)
            val ifcList = IntArray(32)
            for (i in 0 until 32) {
                ifcList[i] = buf.int
            }

            buf.position(0xD0)
            val badBlockList = IntArray(32)
            for (i in 0 until 32) {
                badBlockList[i] = buf.int
            }

            buf.position(0x150)
            val cardType = buf.get().toInt() and 0xFF
            val cardFlags = buf.get().toInt() and 0xFF

            return Ps2SuperBlock(
                magic = magic,
                version = version,
                pageLen = if (pageLen > 0) pageLen else 512,
                pagesPerCluster = if (pagesPerCluster > 0) pagesPerCluster else 2,
                pagesPerBlock = if (pagesPerBlock > 0) pagesPerBlock else 16,
                clustersPerCard = clustersPerCard,
                allocOffset = allocOffset,
                allocEnd = allocEnd,
                rootdirCluster = rootdirCluster,
                backupBlock1 = backupBlock1,
                backupBlock2 = backupBlock2,
                ifcList = ifcList,
                badBlockList = badBlockList,
                cardType = cardType,
                cardFlags = cardFlags
            )
        }
    }
}
