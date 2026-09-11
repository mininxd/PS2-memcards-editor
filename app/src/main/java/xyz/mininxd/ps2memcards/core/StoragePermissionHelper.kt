package xyz.mininxd.ps2memcards.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

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
}
