package mumu.xsy.mumuchat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import mumu.xsy.mumuchat.ChatSession

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "sessions")

class SessionsStore(
    private val context: Context,
    private val gson: Gson
) {
    private val sessionsKey = stringPreferencesKey("sessions_data")

    suspend fun load(): List<ChatSession> {
        return try {
            val json = context.sessionDataStore.data.first()[sessionsKey]
            if (json.isNullOrBlank()) return emptyList()
            gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<ChatSession>>() {}.type)
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun save(sessions: List<ChatSession>) {
        val json = gson.toJson(sessions)
        context.sessionDataStore.edit { preferences ->
            preferences[sessionsKey] = json
        }
    }
}

