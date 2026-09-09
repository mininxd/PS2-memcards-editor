package xyz.armsx2.memcard

import xyz.armsx2.memcard.core.MemcardFormatter
import xyz.armsx2.memcard.core.Ps2DirectoryEntry
import xyz.armsx2.memcard.core.Ps2Ecc
import xyz.armsx2.memcard.core.Ps2Memcard
import xyz.armsx2.memcard.core.Ps2SuperBlock
import xyz.armsx2.memcard.core.Ps2Timestamp
import xyz.armsx2.memcard.core.PsuHandler
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
}
