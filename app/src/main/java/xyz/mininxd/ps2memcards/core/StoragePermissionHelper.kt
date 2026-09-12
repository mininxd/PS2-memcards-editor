package xyz.mininxd.ps2memcards.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

object StoragePermissionHelper {

    /**
     * Checks whether the app has been granted full storage access permissions.
     *
     * - On Android 11+ (API 30+): checks if all-files access (MANAGE_EXTERNAL_STORAGE)
     *   is granted via [Environment.isExternalStorageManager].
     * - On Android 10 and below: checks if both [Manifest.permission.READ_EXTERNAL_STORAGE]
     *   and [Manifest.permission.WRITE_EXTERNAL_STORAGE] are granted.
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            readGranted && writeGranted
        }
    }

    /**
     * Attempts to resolve a content or file [Uri] to a real [File] on disk.
     */
    fun resolveFileFromUri(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it) }
        }

        if (uri.scheme == "content") {
            try {
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    val docId = DocumentsContract.getDocumentId(uri)
                    if (docId != null) {
                        if (docId.startsWith("primary:")) {
                            val relativePath = docId.substringAfter("primary:")
                            val file = File(Environment.getExternalStorageDirectory(), relativePath)
                            if (file.exists()) return file
                        } else if (docId.contains(":")) {
                            val parts = docId.split(":", limit = 2)
                            val storageId = parts[0]
                            val relativePath = parts[1]
                            val file = File("/storage/$storageId", relativePath)
                            if (file.exists()) return file
                        }
                    }
                }
            } catch (_: Throwable) {}

            try {
                if (DocumentsContract.isTreeUri(uri)) {
                    val treeDocId = DocumentsContract.getTreeDocumentId(uri)
                    if (treeDocId != null) {
                        if (treeDocId.startsWith("primary:")) {
                            val relativePath = treeDocId.substringAfter("primary:")
                            val file = File(Environment.getExternalStorageDirectory(), relativePath)
                            if (file.exists()) return file
                        } else if (treeDocId.contains(":")) {
                            val parts = treeDocId.split(":", limit = 2)
                            val file = File("/storage/${parts[0]}", parts[1])
                            if (file.exists()) return file
                        }
                    }
                }
            } catch (_: Throwable) {}

            val path = uri.path
            if (path != null) {
                val direct = File(path)
                if (direct.exists()) return direct
                if (path.contains("primary:")) {
                    val relative = path.substringAfter("primary:")
                    val file = File(Environment.getExternalStorageDirectory(), relative)
                    if (file.exists()) return file
                }
            }

            try {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (idx >= 0) {
                            val p = cursor.getString(idx)
                            if (!p.isNullOrBlank()) {
                                val file = File(p)
                                if (file.exists()) return file
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return null
    }
}
