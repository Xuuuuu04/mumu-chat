package mumu.xsy.mumuchat.system

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.json.JSONObject

object SuperIsland {
    fun focusProtocolVersion(context: Context): Int {
        return try {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        } catch (_: Exception) {
            0
        }
    }

    fun attach(
        builder: NotificationCompat.Builder,
        context: Context,
        title: String,
        text: String,
        kind: String,
        enable: Boolean
    ) {
        if (!enable) return
        if (focusProtocolVersion(context) < 3) return
        builder.setExtras(Bundle().apply {
            putString("miui.focus.param", buildParam(title, text, kind))
        })
    }

    private fun buildParam(title: String, text: String, kind: String): String {
        val safeTitle = title.trim().take(24)
        val safeText = text.trim().take(64)

        val root = JSONObject()
        val paramV2 = JSONObject()
        val island = JSONObject()
        island.put("biz", kind)
        island.put("smallIslandArea", JSONObject().apply {
            put("title", safeTitle)
            put("text", safeText)
        })
        island.put("bigIslandArea", JSONObject().apply {
            put("title", safeTitle)
            put("text", safeText)
        })
        island.put("shareData", JSONObject())
        paramV2.put("param_island", island)
        root.put("param_v2", paramV2)
        return root.toString()
    }
}

