package com.armsx2.memcards.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed PS2 icon.sys file metadata
 */
data class Ps2IconSys(
    val header: String = "PS2D",
    val titleBreakOffset: Int = 0,
    val transparency: Int = 0xFF,
    val title: String = "",
    val subtitle: String = "",
    val iconFile: String = "",
    val copyIconFile: String = "",
    val deleteIconFile: String = "",
    val ambientR: Float = 0.5f,
    val ambientG: Float = 0.5f,
    val ambientB: Float = 0.5f,
    val bgTopLeft: Int = 0xFF00439C.toInt(),
    val bgBottomRight: Int = 0xFF002255.toInt()
) {
    val fullTitle: String
        get() = if (subtitle.isNotBlank()) "$title $subtitle" else title

    companion object {
        fun parse(data: ByteArray): Ps2IconSys? {
            if (data.size < 0x1C0) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val headerBytes = ByteArray(4)
            buf.get(headerBytes)
            val header = String(headerBytes, Charsets.US_ASCII)
            if (!header.startsWith("PS2D")) {
                return null
            }

            buf.position(0x06)
            val titleBreakOffset = buf.short.toInt() and 0xFFFF

            buf.position(0x0C)
            val transparency = buf.int

            // Read ambient color floats at 0xB0
            buf.position(0xB0)
            val ambR = buf.float
            val ambG = buf.float
            val ambB = buf.float

            // Read title at 0xC0 (64 bytes)
            buf.position(0xC0)
            val titleRawBytes = ByteArray(64)
            buf.get(titleRawBytes)

            val title: String
            val subtitle: String

            if (titleBreakOffset in 1..63) {
                val line1 = Ps2ShiftJis.decode(titleRawBytes, 0, titleBreakOffset)
                val line2 = Ps2ShiftJis.decode(titleRawBytes, titleBreakOffset, 64 - titleBreakOffset)
                title = line1.trim()
                subtitle = line2.trim()
            } else {
                val full = Ps2ShiftJis.decode(titleRawBytes, 0, 64)
                val lines = full.split('\n', '\r').filter { it.isNotBlank() }
                if (lines.size >= 2) {
                    title = lines[0].trim()
                    subtitle = lines.drop(1).joinToString(" ").trim()
                } else {
                    title = full.trim()
                    subtitle = ""
                }
            }

            // Read icon filename at 0x100 (64 bytes)
            buf.position(0x100)
            val iconFileBytes = ByteArray(64)
            buf.get(iconFileBytes)
            val iconFile = readNullTerminatedAscii(iconFileBytes)

            // Read copy icon filename at 0x140 (64 bytes)
            buf.position(0x140)
            val copyFileBytes = ByteArray(64)
            buf.get(copyFileBytes)
            val copyIconFile = readNullTerminatedAscii(copyFileBytes)

            // Read delete icon filename at 0x180 (64 bytes)
            buf.position(0x180)
            val delFileBytes = ByteArray(64)
            buf.get(delFileBytes)
            val deleteIconFile = readNullTerminatedAscii(delFileBytes)

            return Ps2IconSys(
                header = header,
                titleBreakOffset = titleBreakOffset,
                transparency = transparency,
                title = title,
                subtitle = subtitle,
                iconFile = iconFile,
                copyIconFile = copyIconFile,
                deleteIconFile = deleteIconFile,
                ambientR = if (ambR in 0f..1f) ambR else 0.5f,
                ambientG = if (ambG in 0f..1f) ambG else 0.5f,
                ambientB = if (ambB in 0f..1f) ambB else 0.5f
            )
        }

        private fun readNullTerminatedAscii(bytes: ByteArray): String {
            var len = 0
            while (len < bytes.size && bytes[len] != 0.toByte()) {
                len++
            }
            return String(bytes, 0, len, Charsets.US_ASCII).trim()
        }
    }
}
