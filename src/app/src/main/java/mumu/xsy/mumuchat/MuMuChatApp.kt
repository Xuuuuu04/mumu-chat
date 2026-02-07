package mumu.xsy.mumuchat

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class MuMuChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppForeground.inForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                AppForeground.inForeground = false
            }
        })
    }
}

