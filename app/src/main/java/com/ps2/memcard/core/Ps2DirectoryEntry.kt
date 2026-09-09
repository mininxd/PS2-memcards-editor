package com.ps2.memcard.core

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

        // 0x40: name (32 bytes)
        buf.position(0x40)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val copyLen = minOf(nameBytes.size, 31)
        buf.put(nameBytes, 0, copyLen)
        buf.put(0.toByte()) // null terminator

        return bytes
    }

    companion object {
        const val ENTRY_SIZE = 512

        const val DF_READ = 0x0001
        const val DF_WRITE = 0x0002
        const val DF_EXECUTE = 0x0004
        const val DF_PROTECTED = 0x0008
        const val DF_FILE = 0x0010
        const val DF_DIRECTORY = 0x0020
        const val DF_0400 = 0x0400
        const val DF_POCKETSTN = 0x0800
        const val DF_PSX = 0x1000
        const val DF_HIDDEN = 0x2000
        const val DF_EXISTS = 0x8000

        fun parse(data: ByteArray, offset: Int = 0): Ps2DirectoryEntry? {
            if (data.size - offset < ENTRY_SIZE) return null
            val buf = ByteBuffer.wrap(data, offset, ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)

            val mode = buf.short.toInt() and 0xFFFF
            buf.position(0x04)
            val length = buf.int.toLong() and 0xFFFFFFFFL

            val createdBytes = ByteArray(8)
            buf.position(0x08)
            buf.get(createdBytes)
            val created = Ps2Timestamp.parse(createdBytes)

            buf.position(0x10)
            val cluster = buf.int.toLong() and 0xFFFFFFFFL

            buf.position(0x14)
            val dirEntry = buf.int.toLong() and 0xFFFFFFFFL

            val modBytes = ByteArray(8)
            buf.position(0x18)
            buf.get(modBytes)
            val modified = Ps2Timestamp.parse(modBytes)

            buf.position(0x20)
            val attr = buf.int.toLong() and 0xFFFFFFFFL

            buf.position(0x40)
            val nameBytes = ByteArray(32)
            buf.get(nameBytes)
            var nameLen = 0
            while (nameLen < 32 && nameBytes[nameLen] != 0.toByte()) {
                nameLen++
            }
            val name = String(nameBytes, 0, nameLen, Charsets.US_ASCII)

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
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(6)
            val year = buf.short.toInt() and 0xFFFF
            return Ps2Timestamp(
                second = minOf(59, sec),
                minute = minOf(59, min),
                hour = minOf(23, hour),
                day = maxOf(1, minOf(31, day)),
                month = maxOf(1, minOf(12, month)),
                year = if (year in 1990..2100) year else 2000
            )
        }
    }
}
