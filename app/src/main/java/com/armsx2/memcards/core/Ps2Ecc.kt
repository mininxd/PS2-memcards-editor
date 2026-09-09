package com.armsx2.memcards.core

/**
 * Port of PS2 Memory Card Reed-Solomon / Hamming ECC calculation
 * Compatible with Sony PS2 hardware, PCSX2, and mymc.
 */
object Ps2Ecc {

    private val PARITY_TABLE = intArrayOf(
        0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1,
        0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0,
        1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 1,
        0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 0,
        1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1,
        0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0,
        1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 0,
        1, 0, 1, 1, 0
    )

    private val COLUMN_PARITY_MASK = intArrayOf(
        0, 7, 22, 17, 37, 34, 51, 52, 52, 51, 34, 37, 17, 22,
        7, 0, 67, 68, 85, 82, 102, 97, 112, 119, 119, 112, 97, 102, 82, 85, 68, 67, 82, 85, 68, 67, 119, 112,
        97, 102, 102, 97, 112, 119, 67, 68, 85, 82, 17, 22, 7, 0, 52, 51, 34, 37, 37, 34, 51, 52, 0, 7, 22, 17,
        97, 102, 119, 112, 68, 67, 82, 85, 85, 82, 67, 68, 112, 119, 102, 97, 34, 37, 52, 51, 7, 0, 17, 22,
        22, 17, 0, 7, 51, 52, 37, 34, 51, 52, 37, 34, 22, 17, 0, 7, 7, 0, 17, 22, 34, 37, 52, 51, 112, 119, 102,
        97, 85, 82, 67, 68, 68, 67, 82, 85, 97, 102, 119, 112, 112, 119, 102, 97, 85, 82, 67, 68, 68, 67, 82,
        85, 97, 102, 119, 112, 51, 52, 37, 34, 22, 17, 0, 7, 7, 0, 17, 22, 34, 37, 52, 51, 34, 37, 52, 51, 7, 0,
        17, 22, 22, 17, 0, 7, 51, 52, 37, 34, 97, 102, 119, 112, 68, 67, 82, 85, 85, 82, 67, 68, 112, 119, 102,
        97, 17, 22, 7, 0, 52, 51, 34, 37, 37, 34, 51, 52, 0, 7, 22, 17, 82, 85, 68, 67, 119, 112, 97, 102, 102,
        97, 112, 119, 67, 68, 85, 82, 67, 68, 85, 82, 102, 97, 112, 119, 119, 112, 97, 102, 82, 85, 68, 67,
        0, 7, 22, 17, 37, 34, 51, 52, 52, 51, 34, 37, 17, 22, 7, 0
    )

    /**
     * Calculates 24-bit ECC checksum for a 128-byte block.
     */
    fun calculateEcc(buf: ByteArray, offset: Int = 0): Int {
        var columnParity = 0x77
        var lineParity0 = 0x7F
        var lineParity1 = 0x7F

        for (i in 0 until 128) {
            val b = buf[offset + i].toInt() and 0xFF
            columnParity = columnParity xor COLUMN_PARITY_MASK[b]
            if (PARITY_TABLE[b] != 0) {
                lineParity0 = lineParity0 xor i.inv()
                lineParity1 = lineParity1 xor i
            }
        }

        return (columnParity and 0xFF) or
                ((lineParity0 and 0xFF) shl 8) or
                ((lineParity1 and 0xFF) shl 16)
    }

    /**
     * Generates 16-byte spare area (ECC + padding) for a 512-byte page.
     */
    fun generateSpareArea(pageData: ByteArray, offset: Int = 0): ByteArray {
        val spare = ByteArray(16)
        for (j in 0 until 4) {
            val ecc = calculateEcc(pageData, offset + j * 128)
            spare[j * 3] = (ecc and 0xFF).toByte()
            spare[j * 3 + 1] = ((ecc shr 8) and 0xFF).toByte()
            spare[j * 3 + 2] = ((ecc shr 16) and 0xFF).toByte()
        }
        // trailing 4 null bytes are already 0
        return spare
    }

    /**
     * Converts a raw memory card byte array (512 bytes/page) to ECC format (528 bytes/page).
     */
    fun convertRawToEcc(raw: ByteArray): ByteArray {
        val pageCount = raw.size / 512
        val ecc = ByteArray(pageCount * 528)
        var srcPos = 0
        var dstPos = 0

        for (i in 0 until pageCount) {
            System.arraycopy(raw, srcPos, ecc, dstPos, 512)
            val spare = generateSpareArea(raw, srcPos)
            System.arraycopy(spare, 0, ecc, dstPos + 512, 16)
            srcPos += 512
            dstPos += 528
        }
        return ecc
    }

    /**
     * Converts an ECC memory card byte array (528 bytes/page) to raw format (512 bytes/page).
     */
    fun convertEccToRaw(ecc: ByteArray): ByteArray {
        val pageCount = ecc.size / 528
        val raw = ByteArray(pageCount * 512)
        var srcPos = 0
        var dstPos = 0

        for (i in 0 until pageCount) {
            System.arraycopy(ecc, srcPos, raw, dstPos, 512)
            srcPos += 528
            dstPos += 512
        }
        return raw
    }
}
