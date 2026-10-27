package com.vknxworks.mailctrl

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vknxworks.mailctrl.notify.NotifySchedule
import com.vknxworks.mailctrl.ui.lock.AppLock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** A string from strings.xml, for code outside Compose (errors, notifications, toasts). */
fun str(@StringRes id: Int, vararg args: Any): String = MailCtrlApp.instance.getString(id, *args)

class MailCtrlApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        val c = container
        c.notifier.createChannels()
        // Passwords are sealed with the app PIN only while notifications are off: background checks need them.
        c.accounts.sealPolicy = { c.vault.enabled && !c.settings.settings.value.notifyEnabled }
        AppLock.onLocked = { c.onLocked() }
        // Attachments opened in other apps last only until MailCtrl starts again.
        c.appScope.launch { runCatching { File(cacheDir, "attachments").deleteRecursively() } }

        c.appScope.launch {
            c.settings.stored
                .map { Triple(it.notifyEnabled, it.notifyInterval, it.pushEnabled) }
                .distinctUntilChanged()
                .collect { runCatching { NotifySchedule.apply(this@MailCtrlApp, c.settings.current()) } }
        }
        // Turning notifications on or off changes whether passwords may be sealed (only while unlocked).
        c.appScope.launch {
            c.settings.stored.map { it.notifyEnabled }.distinctUntilChanged().drop(1).collect {
                if (!c.vault.enabled || c.vault.isOpen) runCatching { c.accounts.applySealing() }
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val s = c.settings.settings.value
                AppLock.onForeground(c.vault.enabled, s.lockTimeout.millis)
            }

            override fun onStop(owner: LifecycleOwner) {
                val s = c.settings.settings.value
                AppLock.onBackground(c.vault.enabled, s.lockTimeout.millis)
            }
        })
    }

    companion object {
        lateinit var instance: MailCtrlApp
            private set
    }
}
