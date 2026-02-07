package mumu.xsy.mumuchat.data

import android.content.Context
import com.google.gson.Gson

data class RecentActionEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val tool: String,
    val summary: String,
    val approved: Boolean? = null,
    val status: String? = null,
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

class ActionsStore(
    context: Context,
    private val gson: Gson
) {
    private val prefs = context.getSharedPreferences("mumu_actions", Context.MODE_PRIVATE)

    fun load(): List<RecentActionEntry> {
        val json = prefs.getString("recent_actions", null)
        return try {
            val arr = gson.fromJson(json, Array<RecentActionEntry>::class.java)
            arr?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(actions: List<RecentActionEntry>) {
        prefs.edit().putString("recent_actions", gson.toJson(actions)).apply()
    }
}

