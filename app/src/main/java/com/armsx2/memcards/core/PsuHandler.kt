package com.armsx2.memcards.core

import java.io.ByteArrayOutputStream

/**
 * Handler for EMS / PS2SaveBuilder .psu save files.
 */
object PsuHandler {

    data class UnpackedPsu(
        val dirEntry: Ps2DirectoryEntry,
        val files: Map<String, ByteArray>
    )

    /**
     * Unpacks a .psu byte array into directory metadata and file contents.
     */
    fun unpackPsu(psuData: ByteArray): UnpackedPsu? {
        if (psuData.size < Ps2DirectoryEntry.ENTRY_SIZE) return null

        val rootEntry = Ps2DirectoryEntry.parse(psuData, 0) ?: return null
        val files = mutableMapOf<String, ByteArray>()

        var offset = Ps2DirectoryEntry.ENTRY_SIZE
        val totalLen = psuData.size

        while (offset + Ps2DirectoryEntry.ENTRY_SIZE <= totalLen) {
            val entry = Ps2DirectoryEntry.parse(psuData, offset) ?: break
            offset += Ps2DirectoryEntry.ENTRY_SIZE

            if (entry.name == "." || entry.name == "..") {
                continue
            }

            val fileLen = entry.length.toInt()
            if (fileLen > 0 && offset + fileLen <= totalLen) {
                val fileData = ByteArray(fileLen)
                System.arraycopy(psuData, offset, fileData, 0, fileLen)
                files[entry.name] = fileData

                // Cluster alignment in PSU (aligned to 1024 bytes)
                val paddedLen = ((fileLen + 1023) / 1024) * 1024
                offset += paddedLen
            } else if (fileLen == 0) {
                files[entry.name] = ByteArray(0)
            }
        }

        return UnpackedPsu(rootEntry, files)
    }

    /**
     * Packs a save folder and its files into standard .psu format.
     */
    fun packPsu(saveName: String, dirEntry: Ps2DirectoryEntry, files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. Root directory entry
        val root = dirEntry.copy(
            name = saveName,
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or Ps2DirectoryEntry.DF_0400,
            length = (files.size + 2).toLong()
        )
        out.write(root.toByteArray())

        // 2. "." entry
        val dot = dirEntry.copy(
            name = ".",
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE,
            length = 0
        )
        out.write(dot.toByteArray())

        // 3. ".." entry
        val dotDot = dirEntry.copy(
            name = "..",
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE,
            length = 0
        )
        out.write(dotDot.toByteArray())

        // 4. File entries + payloads
        for ((fileName, fileData) in files) {
            val fileEntry = Ps2DirectoryEntry(
                mode = Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_READ or Ps2DirectoryEntry.DF_WRITE or Ps2DirectoryEntry.DF_0400,
                length = fileData.size.toLong(),
                created = dirEntry.created,
                cluster = 0,
                dirEntry = 0,
                modified = dirEntry.modified,
                attr = 0,
                name = fileName
            )
            out.write(fileEntry.toByteArray())
            out.write(fileData)

            // Pad to multiple of 1024 bytes
            val remainder = fileData.size % 1024
            if (remainder > 0) {
                val padSize = 1024 - remainder
                out.write(ByteArray(padSize))
            }
        }

        return out.toByteArray()
    }
}
