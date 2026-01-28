package mumu.xsy.mumuchat.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import mumu.xsy.mumuchat.Constants
import mumu.xsy.mumuchat.MainActivity
import mumu.xsy.mumuchat.R

class MuMuKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "运行中" }
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val enableSuperIsland = intent?.getBooleanExtra(EXTRA_ENABLE_SUPER_ISLAND, false) == true

        if (!canPostNotifications()) return START_NOT_STICKY
        ensureChannel()

        val notification = buildNotification(title, text, enableSuperIsland)
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(Constants.Notifications.KEEPALIVE_NOTIFICATION_ID, notification)
        } else {
            startForeground(Constants.Notifications.KEEPALIVE_NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun buildNotification(title: String, text: String, enableSuperIsland: Boolean): android.app.Notification {
        val builder = NotificationCompat.Builder(this, Constants.Notifications.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mumuchat)
            .setColor(ContextCompat.getColor(this, R.color.brand_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildMainPendingIntent())
            .addAction(
                0,
                "停止生成",
                buildMainPendingIntent(extraAction = Constants.Notifications.ACTION_STOP_GENERATION)
            )

        SuperIsland.attach(builder, this, title, text, kind = "keepalive", enable = enableSuperIsland)
        return builder.build()
    }

    private fun buildMainPendingIntent(extraAction: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!extraAction.isNullOrBlank()) putExtra(Constants.Notifications.EXTRA_APP_ACTION, extraAction)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun canPostNotifications(): Boolean {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(Constants.Notifications.CHANNEL_STATUS)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                Constants.Notifications.CHANNEL_STATUS,
                "灵动状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "任务进度与快捷操作" }
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "mumu.xsy.mumuchat.action.KEEPALIVE_START"
        const val ACTION_STOP = "mumu.xsy.mumuchat.action.KEEPALIVE_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ENABLE_SUPER_ISLAND = "enable_super_island"
    }
}
