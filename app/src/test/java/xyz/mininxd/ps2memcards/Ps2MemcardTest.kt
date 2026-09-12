package xyz.mininxd.ps2memcards

import xyz.mininxd.ps2memcards.core.FolderMemcardHandler
import xyz.mininxd.ps2memcards.core.MaxHandler
import xyz.mininxd.ps2memcards.core.MemcardFormatter
import xyz.mininxd.ps2memcards.core.Ps2DirectoryEntry
import xyz.mininxd.ps2memcards.core.Ps2Ecc
import xyz.mininxd.ps2memcards.core.Ps2FileDetector
import xyz.mininxd.ps2memcards.core.Ps2FileType
import xyz.mininxd.ps2memcards.core.Ps2Lzari
import xyz.mininxd.ps2memcards.core.Ps2Memcard
import xyz.mininxd.ps2memcards.core.Ps2SuperBlock
import xyz.mininxd.ps2memcards.core.Ps2Timestamp
import xyz.mininxd.ps2memcards.core.PsuHandler
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun testLzariCompressionRoundtrip() {
        val originalText = "Action Replay MAX (.max) save file format test with repetition! " +
                "Resident Evil 4, Metal Gear Solid 3, God of War 2. " +
                "ABCDEF0123456789".repeat(10)
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)

        val compressed = Ps2Lzari.compress(originalBytes)
        assertTrue(compressed.isNotEmpty())
        assertTrue(compressed.size < originalBytes.size)

        val decompressed = Ps2Lzari.decompress(compressed, originalBytes.size)
        assertArrayEquals(originalBytes, decompressed)
    }

    @Test
    fun testMaxSavePackAndUnpack() {
        val dirName = "BASLUS-21445"
        val title = "Final Fantasy X"
        val iconSysBytes = "icon.sys test payload".toByteArray(Charsets.UTF_8)
        val gameSaveBytes = ByteArray(3000) { (it % 250).toByte() }
        val files = mapOf(
            "icon.sys" to iconSysBytes,
            "data.bin" to gameSaveBytes
        )

        val packedMax = MaxHandler.packMax(dirName, title, files)
        assertNotNull(packedMax)
        assertTrue(MaxHandler.isMax(packedMax))

        val header = MaxHandler.parseHeader(packedMax)
        assertNotNull(header)
        assertEquals(dirName, header!!.dirName)
        assertEquals(title, header.iconSysTitle)
        assertEquals(files.size, header.fileCount)

        val unpacked = MaxHandler.unpackMax(packedMax)
        assertNotNull(unpacked)
        assertEquals(dirName, unpacked!!.dirEntry.name)
        assertEquals(files.size, unpacked.files.size)
        assertArrayEquals(iconSysBytes, unpacked.files["icon.sys"])
        assertArrayEquals(gameSaveBytes, unpacked.files["data.bin"])
    }

    @Test
    fun testMaxSaveImportAndExportOnCard() {
        val cardData = MemcardFormatter.format(sizeInMB = 8, useEcc = true)
        val card = Ps2Memcard.open(cardData)!!

        val dirName = "SLUS-20672"
        val title = "Gran Turismo 4"
        val filePayload = ByteArray(1500) { 0x5A.toByte() }
        val files = mapOf(
            "save.dat" to filePayload,
            "meta.bin" to "GT4 Profile".toByteArray(Charsets.UTF_8)
        )

        val maxData = MaxHandler.packMax(dirName, title, files)
        assertTrue(card.importSave(maxData))

        // Verify save is visible on card
        val saves = card.listSaves()
        assertEquals(1, saves.size)
        assertEquals(dirName, saves[0].directoryName)

        // Read file bytes back
        val readData = card.getSaveFileBytes(dirName, "save.dat")
        assertNotNull(readData)
        assertArrayEquals(filePayload, readData)

        // Export as MAX
        val exportedMax = card.exportSaveAsMax(dirName)
        assertNotNull(exportedMax)
        assertTrue(MaxHandler.isMax(exportedMax!!))

        val reUnpacked = MaxHandler.unpackMax(exportedMax)
        assertNotNull(reUnpacked)
        assertEquals(dirName, reUnpacked!!.dirEntry.name)
        assertArrayEquals(filePayload, reUnpacked.files["save.dat"])

        // Delete save
        assertTrue(card.deleteSave(dirName))
        assertEquals(0, card.listSaves().size)
    }

    @Test
    fun testMultipleSavesImportAndList() {
        val cardData = MemcardFormatter.format(sizeInMB = 8, useEcc = true)
        val card = Ps2Memcard.open(cardData)!!

        // 1. Import First Save (Black)
        val dir1 = "BESLES-54030"
        val max1 = MaxHandler.packMax(
            dirName = dir1,
            iconSysTitle = "BLACK",
            files = mapOf("icon.sys" to ByteArray(964), "view.ico" to ByteArray(1000), "BESLES-54030" to ByteArray(500))
        )
        assertTrue(card.importSave(max1))

        var saves = card.listSaves()
        assertEquals(1, saves.size)
        assertEquals(dir1, saves[0].directoryName)

        // 2. Import Second Save (Mortal Kombat: Shaolin Monks)
        val dir2 = "BASLUS-21087"
        val max2 = MaxHandler.packMax(
            dirName = dir2,
            iconSysTitle = "Mortal Kombat",
            files = mapOf("icon.sys" to ByteArray(964), "game.icn" to ByteArray(1200), "BASLUS-21087" to ByteArray(800))
        )
        assertTrue(card.importSave(max2))

        saves = card.listSaves()
        assertEquals(2, saves.size)
        val saveNames = saves.map { it.directoryName }.toSet()
        assertTrue(saveNames.contains(dir1))
        assertTrue(saveNames.contains(dir2))

        // 3. Verify BIOS directory backlinks
        val rootCluster = if (card.superBlock.rootdirCluster >= card.allocOffset) {
            card.superBlock.rootdirCluster - card.allocOffset
        } else {
            card.superBlock.rootdirCluster
        }
        val rootEntries = card.readDirents(rootCluster)
        assertEquals(4, rootEntries.size) // ".", "..", dir1, dir2

        for (e in rootEntries) {
            if (e.name == "." || e.name == "..") continue
            val sub = card.readDirents(e.cluster)
            assertTrue(sub.isNotEmpty())
            val dot = sub[0]
            val slot = dot.dirEntry.toInt()
            assertTrue(slot in rootEntries.indices)
            assertEquals(e.name, rootEntries[slot].name)
        }

        // 4. Delete first save and verify second save remains intact and backlink updated
        assertTrue(card.deleteSave(dir1))
        saves = card.listSaves()
        assertEquals(1, saves.size)
        assertEquals(dir2, saves[0].directoryName)

        val updatedRoot = card.readDirents(rootCluster)
        assertEquals(3, updatedRoot.size) // ".", "..", dir2
        val sub2 = card.readDirents(updatedRoot[2].cluster)
        assertEquals(2, sub2[0].dirEntry.toInt())
        assertEquals(dir2, updatedRoot[2].name)
    }

    @Test
    fun testCreateUnformattedCard() {
        val unformatted = MemcardFormatter.createUnformatted(sizeInMB = 8, useEcc = true)
        assertEquals(8650752, unformatted.size)
        // Verify 100% 0xFF bytes (matching armsx_mcd001.ps2 and PCSX2's FileMcd_CreateNewCard)
        for (i in unformatted.indices step 4096) {
            assertEquals(0xFF.toByte(), unformatted[i])
        }

        val card = Ps2Memcard.open(unformatted)
        assertNotNull(card)
        assertFalse(card!!.isFormatted)
        assertTrue(card.hasEcc)
        assertEquals(8192L, card.totalClusters)
        assertEquals(0, card.listSaves().size)

        val stats = card.getStats()
        assertFalse(stats.isFormatted)
        assertEquals(8388608L, stats.totalSpaceBytes)

        // Operations that require a filesystem should safely reject or fail
        assertFalse(card.writeFile(0, "test.bin", ByteArray(10)))
        assertFalse(card.deleteSave("test"))
        assertEquals(0xFFFFFFFFL, card.makeDir("test"))
        assertFalse(card.importSave(ByteArray(100)))
    }

    @Test
    fun testCreateUnformattedRawCard() {
        val unformattedRaw = MemcardFormatter.createUnformatted(sizeInMB = 8, useEcc = false)
        assertEquals(8388608, unformattedRaw.size)
        for (i in unformattedRaw.indices step 4096) {
            assertEquals(0xFF.toByte(), unformattedRaw[i])
        }

        val card = Ps2Memcard.open(unformattedRaw)
        assertNotNull(card)
        assertFalse(card!!.isFormatted)
        assertFalse(card.hasEcc)
        assertEquals(8192L, card.totalClusters)
        assertEquals(0, card.listSaves().size)
    }

    @Test
    fun testFormattedCardHasErasedUnallocatedClusters() {
        val formatted = MemcardFormatter.format(sizeInMB = 8, useEcc = true)
        assertEquals(8650752, formatted.size)

        val card = Ps2Memcard.open(formatted)
        assertNotNull(card)
        assertTrue(card!!.isFormatted)
        assertTrue(card.hasEcc)

        // Verify unallocated clusters have 0xFF (flash erased state)
        // Cluster 100 is an unallocated cluster (allocOffset is 41, root dir is 41)
        val cluster100Data = card.readCluster(100)
        assertEquals(1024, cluster100Data.size)
        for (b in cluster100Data) {
            assertEquals(0xFF.toByte(), b)
        }

        // Verify spare area of an unallocated page (e.g., page 200 = cluster 100 page 0)
        // Page 200 raw offset is 200 * 528 = 105600
        val page200Offset = 200 * 528
        for (i in 0 until 528) {
            assertEquals(0xFF.toByte(), formatted[page200Offset + i])
        }

        // Verify backup blocks 1 and 2 (block 1023 and 1022) are also erased (0xFF)
        // Block 1023 cluster is 1023 * 8 = 8184
        val backupClusterData = card.readCluster(8184)
        for (b in backupClusterData) {
            assertEquals(0xFF.toByte(), b)
        }
    }

    @Test
    fun testFormatUnformattedCardAndImportSave() {
        // 1. Start with an unformatted card (as created by PCSX2 or our createUnformatted)
        val unformatted = MemcardFormatter.createUnformatted(sizeInMB = 8, useEcc = true)
        val unformattedCard = Ps2Memcard.open(unformatted)
        assertNotNull(unformattedCard)
        assertFalse(unformattedCard!!.isFormatted)

        // 2. Format the card
        val formattedBytes = MemcardFormatter.format(
            sizeInMB = unformattedCard.totalCapacityMb.toInt(),
            useEcc = unformattedCard.hasEcc
        )
        val formattedCard = Ps2Memcard.open(formattedBytes)
        assertNotNull(formattedCard)
        assertTrue(formattedCard!!.isFormatted)
        assertEquals(0, formattedCard.listSaves().size)

        // 3. Import a save onto formatted card
        val dirName = "BASLUS-00001"
        val testPayload = "Hello PS2 Save".toByteArray(Charsets.UTF_8)
        val psuBytes = PsuHandler.packPsu(
            saveName = dirName,
            dirEntry = Ps2DirectoryEntry(
                mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS,
                length = 3,
                created = Ps2Timestamp.now(),
                cluster = 0,
                dirEntry = 0,
                modified = Ps2Timestamp.now(),
                attr = 0,
                name = dirName
            ),
            files = mapOf("data.bin" to testPayload)
        )
        assertTrue(formattedCard.importPsu(psuBytes))

        val saves = formattedCard.listSaves()
        assertEquals(1, saves.size)
        assertEquals(dirName, saves[0].directoryName)
        assertArrayEquals(testPayload, formattedCard.getSaveFileBytes(dirName, "data.bin"))
    }

    @Test
    fun testDirectoryEntryOffsetParsing() {
        val now = Ps2Timestamp.now()
        val entry1 = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
            length = 5,
            created = now,
            cluster = 42,
            dirEntry = 2,
            modified = now,
            attr = 0,
            name = "FIRST_ENTRY"
        )
        val entry2 = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
            length = 99,
            created = now,
            cluster = 108,
            dirEntry = 3,
            modified = now,
            attr = 0,
            name = "SECOND_ENTRY"
        )

        val buffer = ByteArray(1024)
        System.arraycopy(entry1.toByteArray(), 0, buffer, 0, 512)
        System.arraycopy(entry2.toByteArray(), 0, buffer, 512, 512)

        val parsed1 = Ps2DirectoryEntry.parse(buffer, 0)
        val parsed2 = Ps2DirectoryEntry.parse(buffer, 512)

        assertNotNull(parsed1)
        assertNotNull(parsed2)

        assertEquals("FIRST_ENTRY", parsed1!!.name)
        assertEquals(42L, parsed1.cluster)
        assertEquals(5L, parsed1.length)
        assertEquals(2L, parsed1.dirEntry)

        // Verify that parsing at offset 512 does NOT read cluster or length from entry 1 at offset 0
        assertEquals("SECOND_ENTRY", parsed2!!.name)
        assertEquals(108L, parsed2.cluster)
        assertEquals(99L, parsed2.length)
        assertEquals(3L, parsed2.dirEntry)
    }

    @Test
    fun testMultipleSaveImportsSequentialNoCollision() {
        val cardData = MemcardFormatter.format(sizeInMB = 8, useEcc = true)
        val card = Ps2Memcard.open(cardData)!!

        val mkPayload = ByteArray(5000) { 0x11.toByte() }
        val mkIconSys = createMockIconSys("Mortal Kombat", "game.icn")
        val psu1 = PsuHandler.packPsu(
            saveName = "BASLUS-21087",
            dirEntry = Ps2DirectoryEntry(
                mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS,
                length = 4,
                created = Ps2Timestamp.now(),
                cluster = 0,
                dirEntry = 0,
                modified = Ps2Timestamp.now(),
                attr = 0,
                name = "BASLUS-21087"
            ),
            files = mapOf("icon.sys" to mkIconSys, "save.bin" to mkPayload)
        )
        assertTrue(card.importPsu(psu1))

        val blackPayload = ByteArray(2000) { 0x22.toByte() }
        val blackIconSys = createMockIconSys("BLACK", "view.ico")
        val psu2 = PsuHandler.packPsu(
            saveName = "BESLES-54030",
            dirEntry = Ps2DirectoryEntry(
                mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS,
                length = 4,
                created = Ps2Timestamp.now(),
                cluster = 0,
                dirEntry = 0,
                modified = Ps2Timestamp.now(),
                attr = 0,
                name = "BESLES-54030"
            ),
            files = mapOf("icon.sys" to blackIconSys, "view.ico" to ByteArray(100), "black.bin" to blackPayload)
        )
        assertTrue(card.importPsu(psu2))

        val saves = card.listSaves()
        assertEquals(2, saves.size)

        val save1 = saves.firstOrNull { it.directoryName == "BASLUS-21087" }
        val save2 = saves.firstOrNull { it.directoryName == "BESLES-54030" }

        assertNotNull(save1)
        assertNotNull(save2)

        // Verify titles are unique and not duplicated
        assertEquals("Mortal Kombat", save1!!.title)
        assertEquals("BLACK", save2!!.title)

        // Verify clusters are unique
        assertTrue(save1.dirEntry.cluster != save2!!.dirEntry.cluster)

        // Verify sizes are unique
        assertTrue(save1.sizeInBytes != save2.sizeInBytes)

        // Verify files are intact and separated
        assertArrayEquals(mkPayload, card.getSaveFileBytes("BASLUS-21087", "save.bin"))
        assertArrayEquals(blackPayload, card.getSaveFileBytes("BESLES-54030", "black.bin"))
    }

    private fun createMockIconSys(title: String, iconName: String): ByteArray {
        val data = ByteArray(964)
        data[0] = 'P'.code.toByte()
        data[1] = 'S'.code.toByte()
        data[2] = '2'.code.toByte()
        data[3] = 'D'.code.toByte()
        val titleBytes = title.toByteArray(Charsets.US_ASCII)
        System.arraycopy(titleBytes, 0, data, 0xC0, minOf(titleBytes.size, 64))
        val iconBytes = iconName.toByteArray(Charsets.US_ASCII)
        System.arraycopy(iconBytes, 0, data, 0x104, minOf(iconBytes.size, 64))
        return data
    }

    @Test
    fun testDetectPcsx2SuperblockAndFolderCard() {
        val sbFile = File("saves_test/MemoryCard/_pcsx2_superblock")
        if (sbFile.exists()) {
            val result = Ps2FileDetector.detect(sbFile)
            assertEquals(Ps2FileType.PS2_FOLDER_MEMCARD, result.fileType)
            assertTrue(result.isValid)
            assertTrue(result.isMemcard)
            assertNotNull(result.folderDir)
            assertEquals("MemoryCard", result.folderDir?.name)

            // Test detecting byte array directly
            val bytes = sbFile.readBytes()
            val detectedFromBytes = Ps2FileDetector.detect(bytes, "_pcsx2_superblock")
            assertEquals(Ps2FileType.PS2_FOLDER_MEMCARD, detectedFromBytes)

            // Test detecting folder directly
            val folderResult = Ps2FileDetector.detect(File("saves_test/MemoryCard"))
            assertEquals(Ps2FileType.PS2_FOLDER_MEMCARD, folderResult.fileType)
            assertTrue(folderResult.isValid)
            assertTrue(folderResult.isMemcard)
        }
    }

    @Test
    fun testDetectStandardMemcardImage() {
        val mcdFile = File("saves_test/app_mcd001.ps2")
        if (mcdFile.exists()) {
            val result = Ps2FileDetector.detect(mcdFile)
            assertEquals(Ps2FileType.PS2_MEMCARD_IMAGE, result.fileType)
            assertTrue(result.isValid)
            assertTrue(result.isMemcard)
        }

        // Test with freshly formatted card bytes
        val formatted = MemcardFormatter.format(sizeInMB = 8, useEcc = true)
        val detected = Ps2FileDetector.detect(formatted, "test.ps2")
        assertEquals(Ps2FileType.PS2_MEMCARD_IMAGE, detected)
    }

    @Test
    fun testDetectSavegames() {
        val maxFile = File("saves_test/black.max")
        if (maxFile.exists()) {
            val result = Ps2FileDetector.detect(maxFile)
            assertEquals(Ps2FileType.SAVEGAME_MAX, result.fileType)
            assertTrue(result.isValid)
            assertTrue(result.isSavegame)
        }

        // Test PSU save detection
        val psuBytes = PsuHandler.packPsu(
            saveName = "BASLUS-21445",
            dirEntry = Ps2DirectoryEntry(
                mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX,
                length = 3,
                created = Ps2Timestamp.now(),
                cluster = 0,
                dirEntry = 0,
                modified = Ps2Timestamp.now(),
                attr = 0,
                name = "BASLUS-21445"
            ),
            files = mapOf("data.bin" to ByteArray(100))
        )
        val psuResult = Ps2FileDetector.detect(psuBytes, "save.psu")
        assertEquals(Ps2FileType.SAVEGAME_PSU, psuResult)
    }

    @Test
    fun testRejectInvalidFiles() {
        // Random text
        val textBytes = "This is a plain text file, not a memory card or savegame.".toByteArray(Charsets.UTF_8)
        val textResult = Ps2FileDetector.detect(textBytes, "notes.txt")
        assertEquals(Ps2FileType.INVALID, textResult)

        // Random binary noise
        val noiseBytes = ByteArray(500) { (it * 7 % 256).toByte() }
        val noiseResult = Ps2FileDetector.detect(noiseBytes, "corrupt.bin")
        assertEquals(Ps2FileType.INVALID, noiseResult)

        // Empty file
        val emptyResult = Ps2FileDetector.detect(ByteArray(0), "empty.dat")
        assertEquals(Ps2FileType.INVALID, emptyResult)

        // Superblock byte array passed without filename should still be recognized as superblock
        val sbFile = File("saves_test/MemoryCard/_pcsx2_superblock")
        if (sbFile.exists()) {
            val sbBytes = sbFile.readBytes()
            val detected = Ps2FileDetector.detect(sbBytes, "unknown_file")
            assertEquals(Ps2FileType.PS2_FOLDER_MEMCARD, detected)
        }
    }

    @Test
    fun testPcsx2IndexParsing() {
        val sampleIndex = "{\$ROOT: {timeCreated: 1789222698,timeModified: 1789222700}," +
                "gh.icn: {order: 1,timeCreated: 1789222698,timeModified: 1789222698}," +
                "icon.sys: {order: 2,timeCreated: 1789222699,timeModified: 1789222699}," +
                "BASLUS-21447: {order: 3,timeCreated: 1789222699,timeModified: 1789222699}," +
                "data: {order: 4,timeCreated: 1789222699,timeModified: 1789222700}}"

        val parsed = FolderMemcardHandler.parseIndexContent(sampleIndex)
        assertNotNull(parsed.rootCreated)
        assertNotNull(parsed.rootModified)
        assertEquals(1, parsed.fileOrder["gh.icn"])
        assertEquals(2, parsed.fileOrder["icon.sys"])
        assertEquals(3, parsed.fileOrder["BASLUS-21447"])
        assertEquals(4, parsed.fileOrder["data"])
    }

    @Test
    fun testLoadFolderMemcardFromTestDirectory() {
        val folder = File("saves_test/MemoryCard")
        if (folder.exists() && folder.isDirectory) {
            val card = FolderMemcardHandler.loadFolderMemcard(folder)
            assertNotNull(card)
            assertTrue(card!!.superBlock.isFormatted())

            val saves = card.listSaves()
            assertEquals(1, saves.size)
            assertEquals("BASLUS-21447", saves[0].directoryName)

            // Verify the save files
            val fileNames = saves[0].files.map { it.name }.toSet()
            assertTrue(fileNames.contains("BASLUS-21447"))
            assertTrue(fileNames.contains("data"))
            assertTrue(fileNames.contains("gh.icn"))
            assertTrue(fileNames.contains("icon.sys"))

            // Verify _pcsx2_index was NOT imported as a PS2 save file
            assertFalse(fileNames.contains("_pcsx2_index"))

            // Verify data file size matches
            val dataBytes = card.getSaveFileBytes("BASLUS-21447", "data")
            assertNotNull(dataBytes)
            assertEquals(138240, dataBytes!!.size)

            // Verify icon.sys exists
            val iconBytes = card.getSaveFileBytes("BASLUS-21447", "icon.sys")
            assertNotNull(iconBytes)
            assertEquals(964, iconBytes!!.size)
        }
    }

    @Test
    fun testFolderMemcardRoundtrip() {
        val sourceFolder = File("saves_test/MemoryCard")
        if (!sourceFolder.exists()) return

        val card = FolderMemcardHandler.loadFolderMemcard(sourceFolder)
        assertNotNull(card)

        // Convert to temp folder
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_folder_mcd_${System.currentTimeMillis()}")
        try {
            val converted = FolderMemcardHandler.convertFileToFolder(card!!, tempDir)
            assertTrue(converted)

            val sbFile = File(tempDir, "_pcsx2_superblock")
            assertTrue(sbFile.exists())
            assertEquals(8192, sbFile.length())

            val saveDir = File(tempDir, "BASLUS-21447")
            assertTrue(saveDir.exists() && saveDir.isDirectory)
            assertTrue(File(saveDir, "data").exists())
            assertTrue(File(saveDir, "_pcsx2_index").exists())

            // Reload from temp folder
            val reloaded = FolderMemcardHandler.loadFolderMemcard(tempDir)
            assertNotNull(reloaded)
            val reSaves = reloaded!!.listSaves()
            assertEquals(1, reSaves.size)
            assertEquals("BASLUS-21447", reSaves[0].directoryName)
            assertEquals(138240, reloaded.getSaveFileBytes("BASLUS-21447", "data")?.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}


