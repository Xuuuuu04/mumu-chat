package mumu.xsy.mumuchat.system

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import mumu.xsy.mumuchat.Constants
import mumu.xsy.mumuchat.MainActivity
import mumu.xsy.mumuchat.R

object KeepAliveNotificationFactory {
    fun build(context: Context, title: String, text: String, enableSuperIsland: Boolean): Notification {
        val builder = NotificationCompat.Builder(context, Constants.Notifications.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mumuchat)
            .setColor(ContextCompat.getColor(context, R.color.brand_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildMainPendingIntent(context))
            .addAction(
                0,
                "停止生成",
                buildMainPendingIntent(context, extraAction = Constants.Notifications.ACTION_STOP_GENERATION)
            )

        SuperIsland.attach(builder, context, title, text, kind = "keepalive", enable = enableSuperIsland)
        return builder.build()
    }

    private fun buildMainPendingIntent(context: Context, extraAction: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!extraAction.isNullOrBlank()) putExtra(Constants.Notifications.EXTRA_APP_ACTION, extraAction)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }
}

