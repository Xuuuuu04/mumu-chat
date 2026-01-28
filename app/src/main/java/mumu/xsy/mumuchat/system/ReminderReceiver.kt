package mumu.xsy.mumuchat.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import mumu.xsy.mumuchat.data.RemindersStore

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TRIGGER) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "提醒" }
        val text = intent.getStringExtra(EXTRA_TEXT)

        NotificationCenter(context).showReminder(id = id, title = title, text = text)

        val store = RemindersStore(context, Gson())
        val next = store.load().filterNot { it.id == id }
        store.save(next)
    }

    companion object {
        const val ACTION_TRIGGER = "mumu.xsy.mumuchat.action.REMINDER_TRIGGER"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }
}

