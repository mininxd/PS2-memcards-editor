package xyz.mininxd.ps2memcards.core

import android.content.Context
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class RecentCard(
    val uriString: String,
    val fileName: String,
    val sizeBytes: Long = 0L,
    val saveCount: Int = 0,
    val lastOpened: Long = System.currentTimeMillis()
)

object RecentCardsManager {
    private const val PREFS_NAME = "memcard_prefs"
    private const val KEY_RECENT = "recent_memcards"
    private const val MAX_RECENTS = 10

    fun getRecentCards(context: Context): List<RecentCard> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<RecentCard>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    RecentCard(
                        uriString = obj.getString("uri"),
                        fileName = obj.getString("fileName"),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        saveCount = obj.optInt("saveCount", 0),
                        lastOpened = obj.optLong("lastOpened", 0L)
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun addRecentCard(context: Context, recent: RecentCard) {
        val list = getRecentCards(context).toMutableList()
        list.removeAll { it.uriString == recent.uriString || it.fileName == recent.fileName }
        list.add(0, recent)
        if (list.size > MAX_RECENTS) {
            list.subList(MAX_RECENTS, list.size).clear()
        }
        saveRecentCards(context, list)
    }

    fun removeRecentCard(context: Context, uriString: String) {
        val list = getRecentCards(context).toMutableList()
        list.removeAll { it.uriString == uriString }
        saveRecentCards(context, list)
    }

    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_RECENT).apply()
    }

    private fun saveRecentCards(context: Context, list: List<RecentCard>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("uri", item.uriString)
                put("fileName", item.fileName)
                put("sizeBytes", item.sizeBytes)
                put("saveCount", item.saveCount)
                put("lastOpened", item.lastOpened)
            }
            arr.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply()
    }
}
