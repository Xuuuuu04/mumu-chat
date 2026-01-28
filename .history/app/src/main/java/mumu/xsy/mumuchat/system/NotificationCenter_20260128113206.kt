package mumu.xsy.mumuchat.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import mumu.xsy.mumuchat.Constants
import mumu.xsy.mumuchat.MainActivity
import mumu.xsy.mumuchat.R

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
            .setSmallIcon(R.drawable.ic_stat_mumuchat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildMainPendingIntent())
            .setColor(ContextCompat.getColor(context, R.color.brand_accent))

        SuperIsland.attach(builder, context, title, text, kind = "ongoing", enable = enableSuperIsland)

        if (stopAction) {
            builder.addAction(
                0,
                "停止生成",
                buildMainPendingIntent(extraAction = Constants.Notifications.ACTION_STOP_GENERATION)
            )
        }
        nm.notify(idFor(actionId), builder.build())
    }

    fun cancel(actionId: String) {
        nm.cancel(idFor(actionId))
    }

    fun showReminder(id: String, title: String, text: String?) {
        if (!canPostNotifications()) return
        ensureChannel()
        val builder = NotificationCompat.Builder(context, Constants.Notifications.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mumuchat)
            .setColor(ContextCompat.getColor(context, R.color.brand_accent))
            .setContentTitle(title)
            .setContentText(text.orEmpty())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(buildMainPendingIntent())
        nm.notify(idFor("reminder:$id"), builder.build())
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

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(Constants.Notifications.CHANNEL_STATUS)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                Constants.Notifications.CHANNEL_STATUS,
                "灵动状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "任务进度与快捷操作"
            }
        )
    }

    private fun idFor(actionId: String): Int = (actionId.hashCode() and 0x7fffffff)
}
