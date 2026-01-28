package mumu.xsy.mumuchat.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import mumu.xsy.mumuchat.Constants
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

        runCatching { ensureChannel() }.onFailure { e ->
            Log.e(TAG, "ensureChannel failed", e)
        }

        val bootstrap = NotificationCompat.Builder(this, Constants.Notifications.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mumuchat)
            .setColor(ContextCompat.getColor(this, R.color.brand_accent))
            .setContentTitle("木灵运行中")
            .setContentText("准备中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildMainPendingIntent())
            .build()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    Constants.Notifications.KEEPALIVE_NOTIFICATION_ID,
                    bootstrap,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(Constants.Notifications.KEEPALIVE_NOTIFICATION_ID, bootstrap)
            }
        }.onFailure { e ->
            Log.e(TAG, "startForeground failed", e)
        }.isSuccess
        if (!ok) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = KeepAliveNotificationFactory.build(
            context = this,
            title = title,
            text = text,
            enableSuperIsland = enableSuperIsland
        )
        NotificationManagerCompat.from(this).notify(Constants.Notifications.KEEPALIVE_NOTIFICATION_ID, notification)
        return START_STICKY
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
        private const val TAG = "MuMuKeepAliveService"
        const val ACTION_START = "mumu.xsy.mumuchat.action.KEEPALIVE_START"
        const val ACTION_STOP = "mumu.xsy.mumuchat.action.KEEPALIVE_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ENABLE_SUPER_ISLAND = "enable_super_island"
    }
}
