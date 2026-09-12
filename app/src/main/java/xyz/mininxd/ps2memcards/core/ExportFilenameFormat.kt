package xyz.mininxd.ps2memcards.core

import android.content.Context

enum class ExportFilenameFormat(
    val key: String,
    val title: String,
    val description: String,
    val previewExample: String
) {
    GAME_NAME_AND_PRODUCT_ID(
        key = "game_name_product_id",
        title = "Game Name + Product ID",
        description = "Combine game title and product serial ID",
        previewExample = "Final Fantasy X - BASLUS-20312.psu"
    ),
    GAME_NAME(
        key = "game_name",
        title = "Game Name",
        description = "Save using the game title",
        previewExample = "Final Fantasy X.psu"
    ),
    PRODUCT_ID(
        key = "product_id",
        title = "Product ID",
        description = "Save using the game product serial ID",
        previewExample = "BASLUS-20312.psu"
    );

    companion object {
        private const val PREFS_NAME = "memcard_prefs"
        private const val KEY_EXPORT_FORMAT = "export_filename_format"

        fun fromKey(key: String?): ExportFilenameFormat {
            return entries.firstOrNull { it.key == key } ?: GAME_NAME_AND_PRODUCT_ID
        }

        fun getSavedFormat(context: Context): ExportFilenameFormat {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = prefs.getString(KEY_EXPORT_FORMAT, null)
            return fromKey(key)
        }

        fun saveFormat(context: Context, format: ExportFilenameFormat) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_EXPORT_FORMAT, format.key).apply()
        }

        fun generateFilename(save: Ps2Save, extension: String, format: ExportFilenameFormat): String {
            val rawTitle = save.title.ifBlank { save.directoryName }
            val cleanTitle = rawTitle.replace(Regex("""[/\\?%*:|"<>]"""), "_").trim()
            val productId = save.directoryName

            val baseName = when (format) {
                GAME_NAME -> cleanTitle
                PRODUCT_ID -> productId
                GAME_NAME_AND_PRODUCT_ID -> {
                    if (cleanTitle.equals(productId, ignoreCase = true)) {
                        productId
                    } else {
                        "$cleanTitle - $productId"
                    }
                }
            }
            val safeName = baseName.ifBlank { productId }.replace(Regex("""[/\\?%*:|"<>]"""), "_").trim()
            return "$safeName.$extension"
        }
    }
}
