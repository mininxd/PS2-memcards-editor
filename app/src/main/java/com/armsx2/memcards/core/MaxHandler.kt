package com.armsx2.memcards.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Handler for Datel Action Replay MAX / MAX Drive (.max) PlayStation 2 game save files.
 */
object MaxHandler {

    const val MAX_MAGIC = "Ps2PowerSave"
    const val MAX_HEADER_SIZE = 92 // 0x5C

    data class MaxHeader(
        val magic: String,
        val crc: Long,
        val dirName: String,
        val iconSysTitle: String,
        val compressedLength: Long,
        val fileCount: Int,
        val uncompressedLength: Long
    )

    /**
     * Checks if the given byte array represents an Action Replay MAX save file.
     */
    fun isMax(data: ByteArray): Boolean {
        if (data.size < MAX_HEADER_SIZE) return false
        val magicBytes = data.copyOfRange(0, 12)
        val magic = String(magicBytes, Charsets.US_ASCII)
        return magic == MAX_MAGIC
    }

    /**
     * Parses the 92-byte header of a .max file.
     */
    fun parseHeader(data: ByteArray): MaxHeader? {
        if (data.size < MAX_HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data, 0, MAX_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        val magicBytes = ByteArray(12)
        buf.get(magicBytes)
        val magic = String(magicBytes, Charsets.US_ASCII)
        if (magic != MAX_MAGIC) return null

        val crc = buf.int.toLong() and 0xFFFFFFFFL

        val dirNameBytes = ByteArray(32)
        buf.get(dirNameBytes)
        val dirName = String(dirNameBytes, Charsets.US_ASCII).trimEnd('\u0000').trim()

        val titleBytes = ByteArray(32)
        buf.get(titleBytes)
        val title = String(titleBytes, Charsets.US_ASCII).trimEnd('\u0000').trim()

        val compressedLength = buf.int.toLong() and 0xFFFFFFFFL
        val fileCount = buf.int
        val uncompressedLength = buf.int.toLong() and 0xFFFFFFFFL

        return MaxHeader(
            magic = magic,
            crc = crc,
            dirName = dirName,
            iconSysTitle = title,
            compressedLength = compressedLength,
            fileCount = fileCount,
            uncompressedLength = uncompressedLength
        )
    }

    /**
     * Unpacks an Action Replay MAX (.max) save file into directory metadata and files map.
     */
    fun unpackMax(data: ByteArray): PsuHandler.UnpackedPsu? {
        val header = parseHeader(data) ?: return null
        val payloadOffset = MAX_HEADER_SIZE
        val payloadAvailable = data.size - payloadOffset
        if (payloadAvailable <= 0 || header.uncompressedLength <= 0) return null

        // In mymc / max format:
        // compressedLength is usually compressedSize + 4. If clen == uncompressedLength, compressedSize is the rest of the file.
        val compSize = if (header.compressedLength == header.uncompressedLength) {
            payloadAvailable
        } else {
            val desired = (header.compressedLength - 4).toInt()
            if (desired in 1..payloadAvailable) desired else payloadAvailable
        }

        val compressedPayload = ByteArray(compSize)
        System.arraycopy(data, payloadOffset, compressedPayload, 0, compSize)

        val decompressed = try {
            Ps2Lzari.decompress(compressedPayload, header.uncompressedLength.toInt())
        } catch (_: Exception) {
            return null
        }

        val files = mutableMapOf<String, ByteArray>()
        var off = 0
        val decompLen = decompressed.size
        val dBuf = ByteBuffer.wrap(decompressed).order(ByteOrder.LITTLE_ENDIAN)

        for (f in 0 until header.fileCount) {
            if (off + 36 > decompLen) break
            dBuf.position(off)
            val flen = dBuf.int
            val nameBytes = ByteArray(32)
            dBuf.get(nameBytes)
            val fName = String(nameBytes, Charsets.US_ASCII).trimEnd('\u0000').trim()
            off += 36

            if (flen < 0 || off + flen > decompLen) break
            val fData = ByteArray(flen)
            System.arraycopy(decompressed, off, fData, 0, flen)
            files[fName] = fData
            off += flen

            // Alignment: (off + 8) aligned to 16
            val rem = (off + 8) % 16
            if (rem != 0) {
                off += (16 - rem)
            }
        }

        if (files.isEmpty()) return null

        val now = Ps2Timestamp.now()
        val dirEntry = Ps2DirectoryEntry(
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or
                    Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or
                    Ps2DirectoryEntry.DF_EXECUTE or Ps2DirectoryEntry.DF_0400,
            length = (files.size + 2).toLong(),
            created = now,
            cluster = 0,
            dirEntry = 0,
            modified = now,
            attr = 0,
            name = header.dirName.ifBlank { "IMPORT" }
        )

        return PsuHandler.UnpackedPsu(dirEntry, files)
    }

    /**
     * Packs a save folder into Action Replay MAX (.max) format.
     */
    fun packMax(
        dirName: String,
        iconSysTitle: String,
        files: Map<String, ByteArray>
    ): ByteArray {
        val uncomp = ByteArrayOutputStream()
        for ((name, data) in files) {
            val nameBuf = ByteArray(32)
            val b = name.toByteArray(Charsets.US_ASCII)
            System.arraycopy(b, 0, nameBuf, 0, minOf(b.size, 31))

            val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
            uncomp.write(lenBuf)
            uncomp.write(nameBuf)
            uncomp.write(data)

            val currentLen = uncomp.size()
            val rem = (currentLen + 8) % 16
            if (rem != 0) {
                uncomp.write(ByteArray(16 - rem))
            }
        }

        val uncompBytes = uncomp.toByteArray()
        val compressed = Ps2Lzari.compress(uncompBytes)

        val hdrBuf = ByteBuffer.allocate(MAX_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = MAX_MAGIC.toByteArray(Charsets.US_ASCII)
        hdrBuf.put(magicBytes, 0, 12)
        hdrBuf.putInt(0) // CRC placeholder

        val dirBytes = ByteArray(32)
        val d = dirName.toByteArray(Charsets.US_ASCII)
        System.arraycopy(d, 0, dirBytes, 0, minOf(d.size, 31))
        hdrBuf.put(dirBytes)

        val titleBytes = ByteArray(32)
        val t = iconSysTitle.toByteArray(Charsets.US_ASCII)
        System.arraycopy(t, 0, titleBytes, 0, minOf(t.size, 31))
        hdrBuf.put(titleBytes)

        hdrBuf.putInt(compressed.size + 4)
        hdrBuf.putInt(files.size)
        hdrBuf.putInt(uncompBytes.size)

        val headerArray = hdrBuf.array()

        // Compute CRC-32
        val crcCalc = CRC32()
        crcCalc.update(headerArray)
        crcCalc.update(compressed)
        val crcValue = crcCalc.value

        hdrBuf.position(12)
        hdrBuf.putInt(crcValue.toInt())

        val result = ByteArray(MAX_HEADER_SIZE + compressed.size)
        System.arraycopy(hdrBuf.array(), 0, result, 0, MAX_HEADER_SIZE)
        System.arraycopy(compressed, 0, result, MAX_HEADER_SIZE, compressed.size)
        return result
    }
}
