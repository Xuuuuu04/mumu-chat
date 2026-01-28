package mumu.xsy.mumuchat.data

import android.content.Context
import com.google.gson.Gson
import mumu.xsy.mumuchat.AppSettings

class SettingsStore(
    context: Context,
    private val gson: Gson
) {
    private val prefs = context.getSharedPreferences("mumu_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val json = prefs.getString("settings", null)
        return try {
            (gson.fromJson(json, AppSettings::class.java) ?: AppSettings()).normalized()
        } catch (_: Exception) {
            AppSettings().normalized()
        }
    }

    fun save(settings: AppSettings) {
        prefs.edit().putString("settings", gson.toJson(settings)).apply()
    }
}
