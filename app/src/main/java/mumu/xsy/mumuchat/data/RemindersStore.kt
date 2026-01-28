package mumu.xsy.mumuchat.data

import android.content.Context
import com.google.gson.Gson

data class ReminderEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val atMs: Long,
    val title: String,
    val text: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

class RemindersStore(
    context: Context,
    private val gson: Gson
) {
    private val prefs = context.getSharedPreferences("mumu_reminders", Context.MODE_PRIVATE)

    fun load(): List<ReminderEntry> {
        val json = prefs.getString("reminders", null)
        return try {
            val arr = gson.fromJson(json, Array<ReminderEntry>::class.java)
            arr?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(reminders: List<ReminderEntry>) {
        prefs.edit().putString("reminders", gson.toJson(reminders)).apply()
    }
}

