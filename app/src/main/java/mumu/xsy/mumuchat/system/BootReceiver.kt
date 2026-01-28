package mumu.xsy.mumuchat.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import mumu.xsy.mumuchat.data.RemindersStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = RemindersStore(context, Gson())
        val scheduler = ReminderScheduler(context)
        val now = System.currentTimeMillis()
        store.load().filter { it.atMs > now }.forEach { r ->
            scheduler.schedule(r.id, r.atMs, r.title, r.text)
        }
    }
}

