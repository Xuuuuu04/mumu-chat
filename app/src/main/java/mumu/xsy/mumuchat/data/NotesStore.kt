package mumu.xsy.mumuchat.data

import android.content.Context
import com.google.gson.Gson

data class NoteEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

class NotesStore(
    context: Context,
    private val gson: Gson
) {
    private val prefs = context.getSharedPreferences("mumu_notes", Context.MODE_PRIVATE)

    fun load(): List<NoteEntry> {
        val json = prefs.getString("notes", null)
        return try {
            val arr = gson.fromJson(json, Array<NoteEntry>::class.java)
            arr?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(notes: List<NoteEntry>) {
        prefs.edit().putString("notes", gson.toJson(notes)).apply()
    }
}
