package mumu.xsy.mumuchat.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class DocEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

class DocsStore(context: Context, private val gson: Gson) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mumu_docs", Context.MODE_PRIVATE)
    private val key = "docs"

    private fun loadAll(): MutableList<DocEntry> {
        val json = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<List<DocEntry>>() {}.type
            gson.fromJson<List<DocEntry>>(json, type).toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveAll(list: List<DocEntry>) {
        prefs.edit().putString(key, gson.toJson(list)).apply()
    }

    fun add(title: String, content: String, tags: List<String>): DocEntry {
        val all = loadAll()
        val doc = DocEntry(title = title, content = content, tags = tags)
        all.add(doc)
        saveAll(all)
        return doc
    }

    fun list(limit: Int): List<DocEntry> {
        return loadAll().sortedByDescending { it.updatedAt }.take(limit)
    }

    fun search(query: String, limit: Int): List<DocEntry> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return loadAll()
            .sortedByDescending { it.updatedAt }
            .filter { it.title.lowercase().contains(q) || it.content.lowercase().contains(q) || it.tags.any { t -> t.lowercase().contains(q) } }
            .take(limit)
    }
}

