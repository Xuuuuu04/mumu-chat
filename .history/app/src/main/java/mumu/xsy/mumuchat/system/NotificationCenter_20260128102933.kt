package mumu.xsy.mumuchat.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.PendingIntent
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import mumu.xsy.mumuchat.Constants
import mumu.xsy.mumuchat.MainActivity
import mumu.xsy.mumuchat.R
import org.json.JSONObject

class NotificationCenter(
    private val context: Context
) {
    private val nm = NotificationManagerCompat.from(context)

    fun showOngoing(
        actionId: String,
        title: String,
        text: String,
        stopAction: Boolean,
        enableSuperIsland: Boolean
    ) {
        if (!canPostNotifications()) return
        ensureChannel()
        val builder = NotificationCompat.Builder(context, Constants.Notifications.CHANNEL_STATUS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildMainPendingIntent())

        if (enableSuperIsland && focusProtocolVersion() >= 3) {
            builder.setExtras(Bundle().apply {
                putString("miui.focus.param", buildSuperIslandParam(title = title, text = text))
            })
        }

        if (stopAction) {
            builder.addAction(
                0,
                "停止",
                buildMainPendingIntent(extraAction = Constants.Notifications.ACTION_STOP_GENERATION)
            )
        }
        nm.notify(idFor(actionId), builder.build())
    }

    fun cancel(actionId: String) {
        nm.cancel(idFor(actionId))
    }

    private fun buildMainPendingIntent(extraAction: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!extraAction.isNullOrBlank()) putExtra(Constants.Notifications.EXTRA_APP_ACTION, extraAction)
        }
        return PendingIntent.getActivity(context, 0, intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return flags
    }

    private fun canPostNotifications(): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun focusProtocolVersion(): Int {
        return try {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        } catch (_: Exception) {
            0
        }
    }

    private fun buildSuperIslandParam(title: String, text: String): String {
        val root = JSONObject()
        val paramV2 = JSONObject()
        val island = JSONObject()
        island.put("smallIslandArea", JSONObject().apply {
            put("title", title)
            put("text", text)
        })
        island.put("bigIslandArea", JSONObject().apply {
            put("title", title)
            put("text", text)
        })
        island.put("shareData", JSONObject())
        paramV2.put("param_island", island)
        root.put("param_v2", paramV2)
        return root.toString()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(Constants.Notifications.CHANNEL_STATUS)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                Constants.Notifications.CHANNEL_STATUS,
                "状态提醒",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun idFor(actionId: String): Int = (actionId.hashCode() and 0x7fffffff)
}
