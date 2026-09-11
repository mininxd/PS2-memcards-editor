package xyz.mininxd.ps2memcards.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Handler for Pelican Accessories CodeBreaker (.cbs) PlayStation 2 game save files.
 */
object CbsHandler {

    const val CBS_MAGIC = "CFU\u0000"
    const val CBS_HEADER_MIN_SIZE = 124

    // Initial permutation state ("S") for the RC4 stream cipher used by CodeBreaker saves.
    private val CBS_RC4S = intArrayOf(
        0x5f, 0x1f, 0x85, 0x6f, 0x31, 0xaa, 0x3b, 0x18,
        0x21, 0xb9, 0xce, 0x1c, 0x07, 0x4c, 0x9c, 0xb4,
        0x81, 0xb8, 0xef, 0x98, 0x59, 0xae, 0xf9, 0x26,
        0xe3, 0x80, 0xa3, 0x29, 0x2d, 0x73, 0x51, 0x62,
        0x7c, 0x64, 0x46, 0xf4, 0x34, 0x1a, 0xf6, 0xe1,
        0xba, 0x3a, 0x0d, 0x82, 0x79, 0x0a, 0x5c, 0x16,
        0x71, 0x49, 0x8e, 0xac, 0x8c, 0x9f, 0x35, 0x19,
        0x45, 0x94, 0x3f, 0x56, 0x0c, 0x91, 0x00, 0x0b,
        0xd7, 0xb0, 0xdd, 0x39, 0x66, 0xa1, 0x76, 0x52,
        0x13, 0x57, 0xf3, 0xbb, 0x4e, 0xe5, 0xdc, 0xf0,
        0x65, 0x84, 0xb2, 0xd6, 0xdf, 0x15, 0x3c, 0x63,
        0x1d, 0x89, 0x14, 0xbd, 0xd2, 0x36, 0xfe, 0xb1,
        0xca, 0x8b, 0xa4, 0xc6, 0x9e, 0x67, 0x47, 0x37,
        0x42, 0x6d, 0x6a, 0x03, 0x92, 0x70, 0x05, 0x7d,
        0x96, 0x2f, 0x40, 0x90, 0xc4, 0xf1, 0x3e, 0x3d,
        0x01, 0xf7, 0x68, 0x1e, 0xc3, 0xfc, 0x72, 0xb5,
        0x54, 0xcf, 0xe7, 0x41, 0xe4, 0x4d, 0x83, 0x55,
        0x12, 0x22, 0x09, 0x78, 0xfa, 0xde, 0xa7, 0x06,
        0x08, 0x23, 0xbf, 0x0f, 0xcc, 0xc1, 0x97, 0x61,
        0xc5, 0x4a, 0xe6, 0xa0, 0x11, 0xc2, 0xea, 0x74,
        0x02, 0x87, 0xd5, 0xd1, 0x9d, 0xb7, 0x7e, 0x38,
        0x60, 0x53, 0x95, 0x8d, 0x25, 0x77, 0x10, 0x5e,
        0x9b, 0x7f, 0xd8, 0x6e, 0xda, 0xa2, 0x2e, 0x20,
        0x4f, 0xcd, 0x8f, 0xcb, 0xbe, 0x5a, 0xe0, 0xed,
        0x2c, 0x9a, 0xd4, 0xe2, 0xaf, 0xd0, 0xa9, 0xe8,
        0xad, 0x7a, 0xbc, 0xa8, 0xf2, 0xee, 0xeb, 0xf5,
        0xa6, 0x99, 0x28, 0x24, 0x6c, 0x2b, 0x75, 0x5d,
        0xf8, 0xd3, 0x86, 0x17, 0xfb, 0xc0, 0x7b, 0xb3,
        0x58, 0xdb, 0xc7, 0x4b, 0xff, 0x04, 0x50, 0xe9,
        0x88, 0x69, 0xc9, 0x2a, 0xab, 0xfd, 0x5b, 0x1b,
        0x8a, 0xd9, 0xec, 0x27, 0x44, 0x0e, 0x33, 0xc8,
        0x6b, 0x93, 0x32, 0x48, 0xb6, 0x30, 0x43, 0xa5
    )

    /**
     * Checks if the byte array starts with the CodeBreaker magic header.
     */
    fun isCbs(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == 0x43.toByte() &&
                data[1] == 0x46.toByte() &&
                data[2] == 0x55.toByte() &&
                data[3] == 0x00.toByte()
    }

    /**
     * Encrypts or decrypts a byte buffer using the fixed CodeBreaker RC4 permutation state.
     */
    fun rc4Crypt(data: ByteArray): ByteArray {
        val s = CBS_RC4S.clone()
        val result = data.clone()
        var j = 0
        for (ii in result.indices) {
            val i = (ii + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            val k = (s[i] + s[j]) and 0xFF
            result[ii] = (result[ii].toInt() xor s[k]).toByte()
        }
        return result
    }

    /**
     * Unpacks a CodeBreaker (.cbs) save file into directory entry metadata and files list.
     */
    fun unpackCbs(data: ByteArray): PsuHandler.UnpackedPsu? {
        if (!isCbs(data) || data.size < CBS_HEADER_MIN_SIZE) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        buf.int // d04
        val hlen = buf.int
        if (hlen < CBS_HEADER_MIN_SIZE || hlen > data.size) return null

        val dlen = buf.int // decompressed length
        val flen = buf.int // compressed body length

        val dirnameBytes = ByteArray(32)
        buf.get(dirnameBytes)
        val dirname = String(dirnameBytes, Charsets.US_ASCII).trimEnd('\u0000').trim()

        val createdBytes = ByteArray(8)
        buf.get(createdBytes)
        val created = Ps2Timestamp.parse(createdBytes)

        val modifiedBytes = ByteArray(8)
        buf.get(modifiedBytes)
        val modified = Ps2Timestamp.parse(modifiedBytes)

        buf.int // d44
        buf.int // d48
        val dirmode = buf.int

        // flen can be the total file size or compressed body size
        val available = data.size - hlen
        val compLen = if (flen in 1..available) flen else available
        if (compLen <= 0) return null

        val encryptedBody = ByteArray(compLen)
        System.arraycopy(data, hlen, encryptedBody, 0, compLen)

        val decryptedBody = rc4Crypt(encryptedBody)

        val decompressed = try {
            val inflater = Inflater()
            inflater.setInput(decryptedBody)
            val out = ByteArrayOutputStream(maxOf(1024, dlen))
            val chunk = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(chunk)
                if (count == 0 && inflater.needsInput()) break
                out.write(chunk, 0, count)
            }
            inflater.end()
            out.toByteArray()
        } catch (_: Exception) {
            return null
        }

        if (decompressed.isEmpty()) return null

        // Parse individual files from decompressed body
        val entries = mutableListOf<PsuHandler.PsuEntry>()
        var offset = 0
        while (offset + 64 <= decompressed.size) {
            val fCreatedBytes = decompressed.copyOfRange(offset, offset + 8)
            val fModifiedBytes = decompressed.copyOfRange(offset + 8, offset + 16)
            val fCreated = Ps2Timestamp.parse(fCreatedBytes)
            val fModified = Ps2Timestamp.parse(fModifiedBytes)

            val fBuf = ByteBuffer.wrap(decompressed, offset + 16, 16).order(ByteOrder.LITTLE_ENDIAN)
            val fSize = fBuf.int
            val fMode = fBuf.short.toInt() and 0xFFFF

            val nameBytes = decompressed.copyOfRange(offset + 32, offset + 64)
            val fName = String(nameBytes, Charsets.US_ASCII).trimEnd('\u0000').trim()

            offset += 64
            if (fSize < 0 || offset + fSize > decompressed.size) break

            val fData = ByteArray(fSize)
            System.arraycopy(decompressed, offset, fData, 0, fSize)
            offset += fSize

            val dirEntry = Ps2DirectoryEntry(
                mode = if (fMode != 0) fMode else (Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400),
                length = fSize.toLong(),
                created = fCreated,
                cluster = 0,
                dirEntry = 0,
                modified = fModified,
                attr = 0,
                name = fName
            )
            entries.add(PsuHandler.PsuEntry(dirEntry, fData))
        }

        val mode = if ((dirmode and Ps2DirectoryEntry.DF_DIRECTORY) != 0) {
            dirmode
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
            name = dirname.ifBlank { "IMPORT" }
        )

        return PsuHandler.UnpackedPsu(rootDir, entries)
    }

    /**
     * Packs save files into a CodeBreaker (.cbs) binary format.
     */
    fun packCbs(saveName: String, dirEntry: Ps2DirectoryEntry, files: Map<String, ByteArray>, title: String = ""): ByteArray {
        // 1. Build decompressed body
        val uncompressedOut = ByteArrayOutputStream()
        for ((name, data) in files) {
            val header = ByteArray(64)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(dirEntry.created.toByteArray())
            buf.put(dirEntry.modified.toByteArray())
            buf.putInt(data.size)
            buf.putShort((Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400).toShort())
            buf.putShort(0) // h06
            buf.putInt(0)   // h08
            buf.putInt(0)   // h0C

            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            val cleanName = ByteArray(32)
            System.arraycopy(nameBytes, 0, cleanName, 0, minOf(nameBytes.size, 31))
            buf.put(cleanName)

            uncompressedOut.write(header)
            uncompressedOut.write(data)
        }

        val uncompressedBytes = uncompressedOut.toByteArray()

        // 2. Compress body with zlib (Deflate)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(uncompressedBytes)
        deflater.finish()
        val compOut = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(chunk)
            compOut.write(chunk, 0, count)
        }
        deflater.end()
        val compressedBytes = compOut.toByteArray()

        // 3. Encrypt compressed body with RC4
        val encryptedBody = rc4Crypt(compressedBytes)

        // 4. Build CBS Header (124 bytes)
        val header = ByteArray(CBS_HEADER_MIN_SIZE)
        val hBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        hBuf.put(0x43.toByte()) // C
        hBuf.put(0x46.toByte()) // F
        hBuf.put(0x55.toByte()) // U
        hBuf.put(0x00.toByte()) // \0
        hBuf.putInt(0) // d04
        hBuf.putInt(CBS_HEADER_MIN_SIZE) // hlen = 124
        hBuf.putInt(uncompressedBytes.size) // dlen
        hBuf.putInt(encryptedBody.size) // flen

        val dirNameBytes = saveName.toByteArray(Charsets.US_ASCII)
        val cleanDirName = ByteArray(32)
        System.arraycopy(dirNameBytes, 0, cleanDirName, 0, minOf(dirNameBytes.size, 31))
        hBuf.put(cleanDirName)

        hBuf.put(dirEntry.created.toByteArray())
        hBuf.put(dirEntry.modified.toByteArray())
        hBuf.putInt(0) // d44
        hBuf.putInt(0) // d48
        hBuf.putInt(Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_0400 or Ps2DirectoryEntry.DF_EXISTS) // dirmode
        hBuf.putInt(0) // d50
        hBuf.putInt(0) // d54
        hBuf.putInt(0) // d58

        val titleString = title.ifBlank { saveName }
        val titleBytes = titleString.toByteArray(Charsets.US_ASCII)
        val cleanTitle = ByteArray(32)
        System.arraycopy(titleBytes, 0, cleanTitle, 0, minOf(titleBytes.size, 31))
        hBuf.put(cleanTitle)

        val finalOut = ByteArrayOutputStream(header.size + encryptedBody.size)
        finalOut.write(header)
        finalOut.write(encryptedBody)
        return finalOut.toByteArray()
    }
}
