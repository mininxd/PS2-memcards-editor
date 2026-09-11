package xyz.mininxd.ps2memcards.core

import java.nio.charset.Charset

/**
 * Decoder for PS2 Shift-JIS (CP932) and full-width character strings.
 */
object Ps2ShiftJis {

    private val SHIFT_JIS = Charset.forName("Shift_JIS")
    private val WINDOWS_31J = try {
        Charset.forName("windows-31j")
    } catch (e: Exception) {
        SHIFT_JIS
    }

    /**
     * Decodes raw bytes to UTF-8 string using Shift-JIS / CP932 encoding.
     */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        if (length <= 0 || offset >= bytes.size) return ""
        val actualLen = minOf(length, bytes.size - offset)

        // Find null terminator if any
        var endPos = offset
        while (endPos < offset + actualLen && bytes[endPos] != 0.toByte()) {
            endPos++
        }
        val cleanLen = endPos - offset
        if (cleanLen <= 0) return ""

        return try {
            val text = String(bytes, offset, cleanLen, WINDOWS_31J)
            convertFullWidthToHalfWidth(text)
        } catch (e: Exception) {
            try {
                val text = String(bytes, offset, cleanLen, SHIFT_JIS)
                convertFullWidthToHalfWidth(text)
            } catch (e2: Exception) {
                String(bytes, offset, cleanLen, Charsets.ISO_8859_1)
            }
        }
    }

    /**
     * Converts full-width ASCII characters (commonly used in PS2 Japanese titles)
     * e.g. Ｆｉｎａｌ　Ｆａｎｔａｓｙ -> Final Fantasy
     */
    fun convertFullWidthToHalfWidth(input: String): String {
        var hasFullWidth = false
        for (i in 0 until input.length) {
            val code = input[i].code
            if (code == 0x3000 || code in 0xFF01..0xFF5E) {
                hasFullWidth = true
                break
            }
        }
        if (!hasFullWidth) return input

        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch.code) {
                0x3000 -> sb.append(' ') // Full-width space
                in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar()) // Full-width ASCII variants
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
