package com.armsx2.memcards.core

import android.graphics.Bitmap

/**
 * Represents a PS2 Game Save folder on a memory card.
 */
data class Ps2Save(
    val directoryName: String,
    val title: String,
    val subtitle: String = "",
    val sizeInBytes: Long = 0,
    val createdDate: String = "",
    val modifiedDate: String = "",
    val isProtected: Boolean = false,
    val isHidden: Boolean = false,
    val isPocketStation: Boolean = false,
    val isPsx: Boolean = false,
    val dirEntry: Ps2DirectoryEntry,
    val files: List<Ps2SaveFile> = emptyList(),
    val iconSys: Ps2IconSys? = null,
    val iconBitmap: Bitmap? = null
) {
    val sizeInKb: Long get() = (sizeInBytes + 1023) / 1024
    val sizeInMb: Double get() = sizeInBytes / (1024.0 * 1024.0)

    val displayTitle: String get() = title.ifBlank { directoryName }
    val displaySubtitle: String get() = subtitle

    val fileCount: Int get() = files.size
}

/**
 * Represents an individual file stored inside a PS2 save folder.
 */
data class Ps2SaveFile(
    val name: String,
    val sizeInBytes: Long,
    val modifiedDate: String,
    val dirEntry: Ps2DirectoryEntry,
    val data: ByteArray? = null
) {
    val sizeInKb: Long get() = (sizeInBytes + 1023) / 1024

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Ps2SaveFile
        return name == other.name && sizeInBytes == other.sizeInBytes && modifiedDate == other.modifiedDate
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + sizeInBytes.hashCode()
        return result
    }
}
