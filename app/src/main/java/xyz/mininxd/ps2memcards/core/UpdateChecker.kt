package xyz.mininxd.ps2memcards.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpdateAvailable(val tagName: String, val releaseUrl: String) : UpdateStatus
    data class UpToDate(val currentVersion: String) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

object UpdateChecker {
    private const val GITHUB_REPO = "mininxd/PS2-memcards-editor"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    suspend fun checkUpdate(currentVersion: String): UpdateStatus = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(LATEST_RELEASE_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PS2MemcardEditor-App")
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val tagName = json.optString("tag_name", "").trim()
                val htmlUrl = json.optString("html_url", "https://github.com/$GITHUB_REPO/releases").trim()

                if (tagName.isBlank()) {
                    return@withContext UpdateStatus.Error("No release tag found")
                }

                val isNewer = isNewerVersion(tagName, currentVersion)
                if (isNewer) {
                    UpdateStatus.UpdateAvailable(tagName, htmlUrl)
                } else {
                    UpdateStatus.UpToDate(currentVersion)
                }
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                UpdateStatus.Error("No releases found")
            } else {
                UpdateStatus.Error("GitHub returned code $responseCode")
            }
        } catch (e: Exception) {
            UpdateStatus.Error(e.localizedMessage ?: "Connection error")
        } finally {
            connection?.disconnect()
        }
    }

    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val cleanRemote = remoteTag.removePrefix("v").removePrefix("V").split("-").first().split("+").first().trim()
        val cleanCurrent = currentVersion.removePrefix("v").removePrefix("V").split("-").first().split("+").first().trim()
        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
