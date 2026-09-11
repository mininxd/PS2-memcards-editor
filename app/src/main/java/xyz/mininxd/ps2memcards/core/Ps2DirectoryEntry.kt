package xyz.mininxd.ps2memcards.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * PS2 Memory Card Directory Entry (512 bytes)
 */
data class Ps2DirectoryEntry(
    val mode: Int,
    val length: Long,
    val created: Ps2Timestamp,
    val cluster: Long,
    val dirEntry: Long,
    val modified: Ps2Timestamp,
    val attr: Long,
    val name: String
) {
    val isFile: Boolean get() = (mode and DF_FILE) != 0
    val isDirectory: Boolean get() = (mode and DF_DIRECTORY) != 0
    val isExists: Boolean get() = (mode and DF_EXISTS) != 0
    val isProtected: Boolean get() = (mode and DF_PROTECTED) != 0
    val isHidden: Boolean get() = (mode and DF_HIDDEN) != 0
    val isPocketStation: Boolean get() = (mode and DF_POCKETSTN) != 0
    val isPsx: Boolean get() = (mode and DF_PSX) != 0

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(ENTRY_SIZE)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // 0x00: mode
        buf.position(0x00)
        buf.putShort(mode.toShort())

        // 0x04: length
        buf.position(0x04)
        buf.putInt(length.toInt())

        // 0x08: created (8 bytes)
        buf.position(0x08)
        buf.put(created.toByteArray())

        // 0x10: cluster
        buf.position(0x10)
        buf.putInt(cluster.toInt())

        // 0x14: dir_entry
        buf.position(0x14)
        buf.putInt(dirEntry.toInt())

        // 0x18: modified (8 bytes)
        buf.position(0x18)
        buf.put(modified.toByteArray())

        // 0x20: attr
        buf.position(0x20)
        buf.putInt(attr.toInt())

        // 0x40: name (up to 448 bytes)
        buf.position(0x40)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val copyLen = minOf(nameBytes.size, ENTRY_SIZE - 0x40 - 1)
        buf.put(nameBytes, 0, copyLen)
        buf.put(0.toByte()) // null terminator

        return bytes
    }

    companion object {
        const val ENTRY_SIZE = 512

        const val DF_READ = 0x0001
        const val DF_WRITE = 0x0002
        const val DF_EXECUTE = 0x0004
        const val DF_RWX = DF_READ or DF_WRITE or DF_EXECUTE
        const val DF_PROTECTED = 0x0008
        const val DF_FILE = 0x0010
        const val DF_DIRECTORY = 0x0020
        const val DF_0400 = 0x0400
        const val DF_POCKETSTN = 0x0800
        const val DF_PSX = 0x1000
        const val DF_HIDDEN = 0x2000
        const val DF_EXISTS = 0x8000

        fun parse(data: ByteArray, offset: Int = 0): Ps2DirectoryEntry? {
            if (offset < 0 || offset + ENTRY_SIZE > data.size) return null

            fun readU16(off: Int): Int =
                (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

            fun readU32(off: Int): Long =
                (data[off].toLong() and 0xFFL) or
                ((data[off + 1].toLong() and 0xFFL) shl 8) or
                ((data[off + 2].toLong() and 0xFFL) shl 16) or
                ((data[off + 3].toLong() and 0xFFL) shl 24)

            val mode = readU16(offset + 0x00)
            val length = readU32(offset + 0x04)

            val created = Ps2Timestamp.parse(data.copyOfRange(offset + 0x08, offset + 0x10))
            val cluster = readU32(offset + 0x10)
            val dirEntry = readU32(offset + 0x14)
            val modified = Ps2Timestamp.parse(data.copyOfRange(offset + 0x18, offset + 0x20))
            val attr = readU32(offset + 0x20)

            val maxNameLen = ENTRY_SIZE - 0x40
            var nameLen = 0
            while (nameLen < maxNameLen && offset + 0x40 + nameLen < data.size && data[offset + 0x40 + nameLen] != 0.toByte()) {
                nameLen++
            }
            val name = if (nameLen > 0) {
                try {
                    String(data, offset + 0x40, nameLen, Charsets.UTF_8).trim()
                } catch (_: Throwable) {
                    String(data, offset + 0x40, nameLen, Charsets.US_ASCII).trim()
                }
            } else ""

            return Ps2DirectoryEntry(
                mode = mode,
                length = length,
                created = created,
                cluster = cluster,
                dirEntry = dirEntry,
                modified = modified,
                attr = attr,
                name = name
            )
        }
    }
}

/**
 * PS2 8-byte Time of Day timestamp (Japan timezone UTC+9)
 */
data class Ps2Timestamp(
    val second: Int = 0,
    val minute: Int = 0,
    val hour: Int = 0,
    val day: Int = 1,
    val month: Int = 1,
    val year: Int = 2000
) {
    fun toFormattedString(): String {
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second)
    }

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(8)
        bytes[0] = 0 // unused
        bytes[1] = second.toByte()
        bytes[2] = minute.toByte()
        bytes[3] = hour.toByte()
        bytes[4] = day.toByte()
        bytes[5] = month.toByte()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(6)
        buf.putShort(year.toShort())
        return bytes
    }

    companion object {
        fun now(): Ps2Timestamp {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"))
            return Ps2Timestamp(
                second = cal.get(Calendar.SECOND),
                minute = cal.get(Calendar.MINUTE),
                hour = cal.get(Calendar.HOUR_OF_DAY),
                day = cal.get(Calendar.DAY_OF_MONTH),
                month = cal.get(Calendar.MONTH) + 1,
                year = cal.get(Calendar.YEAR)
            )
        }

        fun parse(bytes: ByteArray): Ps2Timestamp {
            if (bytes.size < 8) return Ps2Timestamp()
            val sec = bytes[1].toInt() and 0xFF
            val min = bytes[2].toInt() and 0xFF
            val hour = bytes[3].toInt() and 0xFF
            val day = bytes[4].toInt() and 0xFF
            val month = bytes[5].toInt() and 0xFF
            val year = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
            return Ps2Timestamp(
                second = minOf(59, sec),
                minute = minOf(59, min),
                hour = minOf(23, hour),
                day = maxOf(1, minOf(31, day)),
                month = maxOf(1, minOf(12, month)),
                year = if (year in 1990..2100) year else 2000
            )
        }

        fun fromValues(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Ps2Timestamp {
            return Ps2Timestamp(
                second = second.coerceIn(0, 59),
                minute = minute.coerceIn(0, 59),
                hour = hour.coerceIn(0, 23),
                day = day.coerceIn(1, 31),
                month = month.coerceIn(1, 12),
                year = year.coerceIn(1990, 2100)
            )
        }
    }
}
