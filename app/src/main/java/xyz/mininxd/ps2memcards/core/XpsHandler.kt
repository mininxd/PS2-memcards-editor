package xyz.mininxd.ps2memcards.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Handler for Datel SharkPort / X-Port (.xps / .sps) PlayStation 2 game save files.
 */
object XpsHandler {

    private val SPS_MAGIC = byteArrayOf(
        0x0D, 0x00, 0x00, 0x00,
        'S'.code.toByte(), 'h'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(),
        'k'.code.toByte(), 'P'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(),
        't'.code.toByte(), 'S'.code.toByte(), 'a'.code.toByte(), 'v'.code.toByte(),
        'e'.code.toByte()
    )

    /**
     * Checks if the byte array starts with the SharkPort / X-Port magic header.
     */
    fun isXps(data: ByteArray): Boolean {
        if (data.size < SPS_MAGIC.size) return false
        for (i in SPS_MAGIC.indices) {
            if (data[i] != SPS_MAGIC[i]) return false
        }
        return true
    }

    /**
     * Unpacks a SharkPort / X-Port (.xps) save file into directory entry metadata and files list.
     */
    fun unpackXps(data: ByteArray): PsuHandler.UnpackedPsu? {
        if (!isXps(data) || data.size < 120) return null

        try {
            var offset = SPS_MAGIC.size
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // savetype
            buf.position(offset)
            buf.int
            offset += 4

            // dirname string
            buf.position(offset)
            val dirnameLen = buf.int
            offset += 4
            if (dirnameLen < 0 || offset + dirnameLen > data.size) return null
            val rawDirname = String(data, offset, dirnameLen, Charsets.US_ASCII).trimEnd('\u0000').trim()
            offset += dirnameLen

            // datestamp string
            buf.position(offset)
            val dateLen = buf.int
            offset += 4
            if (dateLen < 0 || offset + dateLen > data.size) return null
            offset += dateLen

            // comment string
            buf.position(offset)
            val commentLen = buf.int
            offset += 4
            if (commentLen < 0 || offset + commentLen > data.size) return null
            offset += commentLen

            // payload length
            buf.position(offset)
            buf.int
            offset += 4

            // Directory header (at least 98 bytes)
            if (offset + 98 > data.size) return null
            buf.position(offset)
            val dirHlen = buf.short.toInt() and 0xFFFF
            if (dirHlen < 98 || offset + dirHlen > data.size) return null

            val dirNameBytes = ByteArray(64)
            buf.get(dirNameBytes)
            val dirNameFromHeader = String(dirNameBytes, Charsets.US_ASCII).trimEnd('\u0000').trim()

            val dirlen = buf.int // total entries (files + 2)
            buf.position(offset + 78)
            val rawDirMode = buf.short.toInt() and 0xFFFF
            val dirMode = ((rawDirMode and 0xFF) shl 8) or ((rawDirMode ushr 8) and 0xFF)

            val dirCreatedBytes = ByteArray(8)
            buf.position(offset + 82)
            buf.get(dirCreatedBytes)
            val created = Ps2Timestamp.parse(dirCreatedBytes)

            val dirModifiedBytes = ByteArray(8)
            buf.get(dirModifiedBytes)
            val modified = Ps2Timestamp.parse(dirModifiedBytes)

            offset += dirHlen

            val numFiles = maxOf(0, dirlen - 2)
            val entries = mutableListOf<PsuHandler.PsuEntry>()

            for (i in 0 until numFiles) {
                if (offset + 98 > data.size) break
                buf.position(offset)
                val fileHlen = buf.short.toInt() and 0xFFFF
                if (fileHlen < 98 || offset + fileHlen > data.size) break

                val fNameBytes = ByteArray(64)
                buf.get(fNameBytes)
                val fileName = String(fNameBytes, Charsets.US_ASCII).trimEnd('\u0000').trim()

                val fileLen = buf.int
                buf.position(offset + 78)
                val rawFileMode = buf.short.toInt() and 0xFFFF
                val fileMode = ((rawFileMode and 0xFF) shl 8) or ((rawFileMode ushr 8) and 0xFF)

                val fCreatedBytes = ByteArray(8)
                buf.position(offset + 82)
                buf.get(fCreatedBytes)
                val fCreated = Ps2Timestamp.parse(fCreatedBytes)

                val fModifiedBytes = ByteArray(8)
                buf.get(fModifiedBytes)
                val fModified = Ps2Timestamp.parse(fModifiedBytes)

                offset += fileHlen
                if (fileLen < 0 || offset + fileLen > data.size) break

                val fileData = ByteArray(fileLen)
                System.arraycopy(data, offset, fileData, 0, fileLen)
                offset += fileLen

                val dirEntry = Ps2DirectoryEntry(
                    mode = if (fileMode != 0) fileMode else (Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400),
                    length = fileLen.toLong(),
                    created = fCreated,
                    cluster = 0,
                    dirEntry = 0,
                    modified = fModified,
                    attr = 0,
                    name = fileName
                )
                entries.add(PsuHandler.PsuEntry(dirEntry, fileData))
            }

            val finalDirName = dirNameFromHeader.ifBlank { rawDirname }.ifBlank { "IMPORT" }
            val mode = if ((dirMode and Ps2DirectoryEntry.DF_DIRECTORY) != 0) {
                dirMode
            } else {
                Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_0400 or Ps2DirectoryEntry.DF_EXISTS
            }

            val rootDir = Ps2DirectoryEntry(
                mode = mode,
                length = entries.size.toLong(),
                created = created,
                cluster = 0,
                dirEntry = 0,
                modified = modified,
                attr = 0,
                name = finalDirName
            )

            return PsuHandler.UnpackedPsu(rootDir, entries)
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Packs save files into a SharkPort / X-Port (.xps) binary format.
     */
    fun packXps(saveName: String, dirEntry: Ps2DirectoryEntry, files: Map<String, ByteArray>, comment: String = "X-Port Save"): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. Magic
        out.write(SPS_MAGIC)

        // 2. SaveType (uint32 LE = 2)
        val metaBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        metaBuf.putInt(2)
        out.write(metaBuf.array())

        // 3. Length-prefixed strings
        fun writePrefixedString(str: String) {
            val bytes = str.toByteArray(Charsets.US_ASCII)
            val b = ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(bytes.size)
            b.put(bytes)
            out.write(b.array())
        }

        writePrefixedString(saveName)
        writePrefixedString(dirEntry.modified.toFormattedString())
        writePrefixedString(comment)

        // 4. Payload (Directory header + File headers + File contents)
        val payloadOut = ByteArrayOutputStream()

        // Directory header (98 bytes)
        val dirHdr = ByteArray(98)
        val dirBuf = ByteBuffer.wrap(dirHdr).order(ByteOrder.LITTLE_ENDIAN)
        dirBuf.putShort(98.toShort()) // hlen

        val dirNameBytes = saveName.toByteArray(Charsets.US_ASCII)
        val cleanDir = ByteArray(64)
        System.arraycopy(dirNameBytes, 0, cleanDir, 0, minOf(dirNameBytes.size, 63))
        dirBuf.put(cleanDir)

        dirBuf.putInt(files.size + 2) // dirlen = files + 2
        dirBuf.put(ByteArray(8)) // 8 reserved bytes

        val standardDirMode = Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_0400 or Ps2DirectoryEntry.DF_EXISTS
        val swappedDirMode = ((standardDirMode and 0xFF) shl 8) or ((standardDirMode ushr 8) and 0xFF)
        dirBuf.putShort(swappedDirMode.toShort())
        dirBuf.putShort(0.toShort()) // 2 reserved bytes
        dirBuf.put(dirEntry.created.toByteArray())
        dirBuf.put(dirEntry.modified.toByteArray())

        payloadOut.write(dirHdr)

        // Files
        val standardFileMode = Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400
        val swappedFileMode = ((standardFileMode and 0xFF) shl 8) or ((standardFileMode ushr 8) and 0xFF)

        for ((name, data) in files) {
            val fileHdr = ByteArray(98)
            val fileBuf = ByteBuffer.wrap(fileHdr).order(ByteOrder.LITTLE_ENDIAN)
            fileBuf.putShort(98.toShort())

            val fNameBytes = name.toByteArray(Charsets.US_ASCII)
            val cleanFName = ByteArray(64)
            System.arraycopy(fNameBytes, 0, cleanFName, 0, minOf(fNameBytes.size, 63))
            fileBuf.put(cleanFName)

            fileBuf.putInt(data.size)
            fileBuf.put(ByteArray(8)) // 8 reserved bytes
            fileBuf.putShort(swappedFileMode.toShort())
            fileBuf.putShort(0.toShort()) // 2 reserved bytes
            fileBuf.put(dirEntry.created.toByteArray())
            fileBuf.put(dirEntry.modified.toByteArray())

            payloadOut.write(fileHdr)
            payloadOut.write(data)
        }

        val payloadBytes = payloadOut.toByteArray()

        // 5. Write payload length (uint32 LE)
        val flenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        flenBuf.putInt(payloadBytes.size)
        out.write(flenBuf.array())

        // 6. Write payload
        out.write(payloadBytes)

        // 7. Calculate CRC32 checksum over the entire stream and append 4-byte CRC32
        val crc = CRC32()
        crc.update(out.toByteArray())
        val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        crcBuf.putInt(crc.value.toInt())
        out.write(crcBuf.array())

        return out.toByteArray()
    }
}
