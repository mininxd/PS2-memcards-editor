package com.armsx2.memcards.core

import java.io.ByteArrayOutputStream

/**
 * Handler for EMS / PS2SaveBuilder .psu save files.
 * Fully aligned with myMCpp and PS2 Memory Card filesystem specification.
 */
object PsuHandler {

    data class PsuEntry(
        val dirEntry: Ps2DirectoryEntry,
        val data: ByteArray
    )

    data class UnpackedPsu(
        val dirEntry: Ps2DirectoryEntry,
        val entries: List<PsuEntry>
    ) {
        val files: Map<String, ByteArray>
            get() = entries.associate { it.dirEntry.name to it.data }

        constructor(dirEntry: Ps2DirectoryEntry, filesMap: Map<String, ByteArray>) : this(
            dirEntry = dirEntry,
            entries = filesMap.map { (name, bytes) ->
                PsuEntry(
                    dirEntry = Ps2DirectoryEntry(
                        mode = Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
                        length = bytes.size.toLong(),
                        created = dirEntry.created,
                        cluster = 0,
                        dirEntry = 0,
                        modified = dirEntry.modified,
                        attr = 0,
                        name = name
                    ),
                    data = bytes
                )
            }
        )
    }

    /**
     * Unpacks a .psu byte array into directory metadata and file entries with metadata.
     */
    fun unpackPsu(psuData: ByteArray): UnpackedPsu? {
        if (psuData.size < Ps2DirectoryEntry.ENTRY_SIZE) return null

        val rootEntry = Ps2DirectoryEntry.parse(psuData, 0) ?: return null
        if (!rootEntry.isDirectory) return null

        val entries = mutableListOf<PsuEntry>()

        // Check whether entries 1 and 2 are '.' and '..'
        var offset = Ps2DirectoryEntry.ENTRY_SIZE
        if (offset + Ps2DirectoryEntry.ENTRY_SIZE * 2 <= psuData.size) {
            val dot = Ps2DirectoryEntry.parse(psuData, offset)
            val dotDot = Ps2DirectoryEntry.parse(psuData, offset + Ps2DirectoryEntry.ENTRY_SIZE)
            if (dot?.name == "." && dotDot?.name == "..") {
                offset += Ps2DirectoryEntry.ENTRY_SIZE * 2
            }
        }

        val totalLen = psuData.size
        while (offset + Ps2DirectoryEntry.ENTRY_SIZE <= totalLen) {
            val ent = Ps2DirectoryEntry.parse(psuData, offset) ?: break
            offset += Ps2DirectoryEntry.ENTRY_SIZE

            if (ent.name == "." || ent.name == "..") {
                continue
            }

            val fileLen = ent.length.toInt()
            val fileData = if (fileLen > 0 && offset + fileLen <= totalLen) {
                val buf = ByteArray(fileLen)
                System.arraycopy(psuData, offset, buf, 0, fileLen)
                val pad = ((fileLen + 1023) / 1024 * 1024) - fileLen
                offset += fileLen + pad
                buf
            } else {
                ByteArray(0)
            }

            entries.add(PsuEntry(ent, fileData))
        }

        return UnpackedPsu(rootEntry, entries)
    }

    /**
     * Packs a save folder and its files into standard .psu format matching myMCpp saveEms.
     */
    fun packPsu(saveName: String, dirEntry: Ps2DirectoryEntry, files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val numFiles = files.size

        // 1. Root directory entry
        val root = dirEntry.copy(
            name = saveName,
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
            length = (numFiles + 2).toLong()
        )
        out.write(root.toByteArray())

        // 2. "." entry
        val dot = root.copy(
            name = ".",
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
            length = (numFiles + 2).toLong(),
            dirEntry = 0
        )
        out.write(dot.toByteArray())

        // 3. ".." entry
        val dotDot = root.copy(
            name = "..",
            mode = Ps2DirectoryEntry.DF_DIRECTORY or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
            length = 0,
            dirEntry = 0
        )
        out.write(dotDot.toByteArray())

        // 4. File entries + payloads
        for ((fileName, fileData) in files) {
            val fileEntry = Ps2DirectoryEntry(
                mode = Ps2DirectoryEntry.DF_FILE or Ps2DirectoryEntry.DF_EXISTS or Ps2DirectoryEntry.DF_RWX or Ps2DirectoryEntry.DF_0400,
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
