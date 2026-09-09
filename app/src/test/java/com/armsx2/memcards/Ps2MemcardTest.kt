package com.armsx2.memcards

import com.armsx2.memcards.core.MemcardFormatter
import com.armsx2.memcards.core.Ps2DirectoryEntry
import com.armsx2.memcards.core.Ps2Ecc
import com.armsx2.memcards.core.Ps2Memcard
import com.armsx2.memcards.core.Ps2SuperBlock
import com.armsx2.memcards.core.Ps2Timestamp
import com.armsx2.memcards.core.PsuHandler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ps2MemcardTest {

    @Test
    fun testEccConversion() {
        val rawPage = ByteArray(512) { (it % 256).toByte() }
        val spare = Ps2Ecc.generateSpareArea(rawPage)
        assertEquals(16, spare.size)

        val rawCard = ByteArray(512 * 16) { (it % 127).toByte() }
        val eccCard = Ps2Ecc.convertRawToEcc(rawCard)
        assertEquals(528 * 16, eccCard.size)

        val recoveredRaw = Ps2Ecc.convertEccToRaw(eccCard)
        assertArrayEquals(rawCard, recoveredRaw)
    }

    @Test
    fun testFormatAndOpenMemcard() {
        // Format 8MB card with ECC
        val cardData = MemcardFormatter.format(sizeInMB = 8, useEcc = true)
        assertNotNull(cardData)
        assertTrue(cardData.size % 528 == 0)

        // Open with parser
        val card = Ps2Memcard.open(cardData)
        assertNotNull(card)
        assertTrue(card!!.hasEcc)
        assertEquals(8192L, card.totalClusters)
        assertEquals(41L, card.allocOffset)
        assertEquals("1.2.0.0", card.superBlock.version)
        assertTrue(card.superBlock.isFormatted())

        // Verify empty saves list
        val saves = card.listSaves()
        assertEquals(0, saves.size)

        // Verify stats
        val stats = card.getStats()
        assertTrue(stats.freeClusters > 0)
        assertTrue(stats.freeSpaceBytes > 0)
    }

    @Test
    fun testPsuPackAndUnpack() {
        val saveName = "BASLUS-21445"
        val testFileContent = "Final Fantasy X Save Game Data".toByteArray(Charsets.UTF_8)
        val files = mapOf("data01.dat" to testFileContent)

        val dirEntry = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE,
            length = 3,
            created = Ps2Timestamp.now(),
            cluster = 0,
            dirEntry = 0,
            modified = Ps2Timestamp.now(),
            attr = 0,
            name = saveName
        )

        val psuBytes = PsuHandler.packPsu(saveName, dirEntry, files)
        assertTrue(psuBytes.size >= Ps2DirectoryEntry.ENTRY_SIZE * 4)

        val unpacked = PsuHandler.unpackPsu(psuBytes)
        assertNotNull(unpacked)
        assertEquals(saveName, unpacked!!.dirEntry.name)
        assertTrue(unpacked.files.containsKey("data01.dat"))
        assertArrayEquals(testFileContent, unpacked.files["data01.dat"])
    }

    @Test
    fun testSaveImportAndExportOnCard() {
        val cardData = MemcardFormatter.format(sizeInMB = 8, useEcc = true)
        val card = Ps2Memcard.open(cardData)!!

        val saveName = "BASLUS-20268"
        val testPayload = ByteArray(2048) { 0x42.toByte() }
        val files = mapOf(
            "save.dat" to testPayload,
            "info.txt" to "Kingdom Hearts 2 Save".toByteArray(Charsets.UTF_8)
        )

        val psuBytes = PsuHandler.packPsu(
            saveName = saveName,
            dirEntry = Ps2DirectoryEntry(
                mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS,
                length = (files.size + 2).toLong(),
                created = Ps2Timestamp.now(),
                cluster = 0,
                dirEntry = 0,
                modified = Ps2Timestamp.now(),
                attr = 0,
                name = saveName
            ),
            files = files
        )

        // Import PSU onto card
        val imported = card.importPsu(psuBytes)
        assertTrue(imported)

        // Verify save is listed
        val saves = card.listSaves()
        assertEquals(1, saves.size)
        assertEquals(saveName, saves[0].directoryName)

        // Read file bytes back from card
        val readData = card.getSaveFileBytes(saveName, "save.dat")
        assertNotNull(readData)
        assertArrayEquals(testPayload, readData)

        // Export save back to PSU
        val exportedPsu = card.exportSaveAsPsu(saveName)
        assertNotNull(exportedPsu)
        val unpackedExport = PsuHandler.unpackPsu(exportedPsu!!)
        assertNotNull(unpackedExport)
        assertArrayEquals(testPayload, unpackedExport!!.files["save.dat"])

        // Delete save
        val deleted = card.deleteSave(saveName)
        assertTrue(deleted)
        assertEquals(0, card.listSaves().size)
    }

    @Test
    fun testVariousSizesAndEcc() {
        // Test 64MB ECC card (matches exact size from hint.txt: 69,206,016 bytes)
        val card64Ecc = MemcardFormatter.format(sizeInMB = 64, useEcc = true)
        assertEquals(69206016, card64Ecc.size)

        val parsed64Ecc = Ps2Memcard.open(card64Ecc)
        assertNotNull(parsed64Ecc)
        assertTrue(parsed64Ecc!!.hasEcc)
        assertEquals(65536L, parsed64Ecc.totalClusters)
        val saves64 = parsed64Ecc.listSaves()
        assertEquals(0, saves64.size)
        val stats64 = parsed64Ecc.getStats()
        assertTrue(stats64.freeClusters > 0)
        assertEquals(69206016L, parsed64Ecc.totalCapacityBytes * 528 / 512)

        // Test 64MB RAW card (67,108,864 bytes)
        val card64Raw = MemcardFormatter.format(sizeInMB = 64, useEcc = false)
        assertEquals(67108864, card64Raw.size)

        val parsed64Raw = Ps2Memcard.open(card64Raw)
        assertNotNull(parsed64Raw)
        assertTrue(!parsed64Raw!!.hasEcc)
        assertEquals(65536L, parsed64Raw.totalClusters)

        // Test 16MB ECC card
        val card16 = MemcardFormatter.format(sizeInMB = 16, useEcc = true)
        val parsed16 = Ps2Memcard.open(card16)
        assertNotNull(parsed16)
        assertTrue(parsed16!!.hasEcc)
        assertEquals(16384L, parsed16.totalClusters)

        // Test 128MB ECC card
        val card128 = MemcardFormatter.format(sizeInMB = 128, useEcc = true)
        val parsed128 = Ps2Memcard.open(card128)
        assertNotNull(parsed128)
        assertTrue(parsed128!!.hasEcc)
        assertEquals(131072L, parsed128.totalClusters)
    }

    @Test
    fun testBoundsSafety() {
        val cardData = MemcardFormatter.format(sizeInMB = 8, useEcc = true)
        val card = Ps2Memcard.open(cardData)!!

        // Negative page indices should not crash or throw IndexOutOfBoundsException
        val negPage = card.readPage(-1)
        assertEquals(512, negPage.size)
        card.writePage(-1, ByteArray(512))

        // Huge page indices should not crash
        val hugePage = card.readPage(Int.MAX_VALUE)
        assertEquals(512, hugePage.size)
        card.writePage(Int.MAX_VALUE, ByteArray(512))

        // Negative cluster indices
        val negCluster = card.readCluster(-1)
        assertEquals(card.clusterSize, negCluster.size)
        card.writeCluster(-1, ByteArray(card.clusterSize))

        // 0xFFFFFFFF cluster index (often EOF or unallocated)
        val eofCluster = card.readCluster(0xFFFFFFFFL)
        assertEquals(card.clusterSize, eofCluster.size)
        card.writeCluster(0xFFFFFFFFL, ByteArray(card.clusterSize))

        // FAT entry bounds
        assertEquals(0L, card.getFatEntry(-1))
        assertEquals(0L, card.getFatEntry(0xFFFFFFFFL))
        assertEquals(0L, card.getFatEntry(Long.MAX_VALUE))
        card.setFatEntry(-1, 0xFFFFFFFFL)
        card.setFatEntry(0xFFFFFFFFL, 0xFFFFFFFFL)

        // Cluster chain with invalid cluster
        val emptyChain = card.readClusterChain(0xFFFFFFFFL)
        assertEquals(0, emptyChain.size)
        val negChain = card.readClusterChain(-1)
        assertEquals(0, negChain.size)
    }
}
