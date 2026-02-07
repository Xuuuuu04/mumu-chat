package mumu.xsy.mumuchat.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class ReminderScheduler(
    private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(id: String, atMs: Long, title: String, text: String?) {
        val pi = buildPendingIntent(id, title, text)
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        }
    }

    fun cancel(id: String, title: String?, text: String?) {
        val pi = buildPendingIntent(id, title, text)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(id: String, title: String?, text: String?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER
            putExtra(ReminderReceiver.EXTRA_ID, id)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_TEXT, text)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }
}
