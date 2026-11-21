package com.vknxworks.mailctrl

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vknxworks.mailctrl.notify.IdleControl
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
        // "Instant with an open connection" (IMAP IDLE): its service follows the two settings it depends on.
        c.appScope.launch {
            c.settings.stored.map { it.notifyEnabled to it.idle }.distinctUntilChanged().collect { (_, idle) ->
                runCatching {
                    // The channel only exists once you've turned this on, so it doesn't sit unused in Android's settings.
                    if (idle) c.notifier.createIdleChannel()
                    IdleControl.apply(this@MailCtrlApp, c.settings.current())
                }
            }
        }
        // Turning notifications on or off changes whether passwords may be sealed (only while unlocked).
        c.appScope.launch {
            c.settings.stored.map { it.notifyEnabled }.distinctUntilChanged().drop(1).collect {
                if (!c.vault.enabled || c.vault.isOpen) runCatching { c.accounts.applySealing() }
            }
        }

        // "Keep messages on this phone": turned off, every copy is removed straight away.
        c.appScope.launch {
            c.settings.stored.map { it.keepMessages }.distinctUntilChanged().collect { keep ->
                c.messages.enabled = keep
                if (!keep) runCatching { c.messages.clearAll() }
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val s = c.settings.settings.value
                AppLock.onForeground(c.vault.enabled, s.lockTimeout.millis)
                foreground = true
                // After switching networks while MailCtrl is open, sign in again right away too.
                c.mail.onNetworkChanged = { if (foreground) signInAhead() }
                signInAhead()
                // Snoozed messages whose time passed while the phone slept show again as soon as MailCtrl opens.
                c.appScope.launch { runCatching { c.wakeSnoozed() } }
            }

            override fun onStop(owner: LifecycleOwner) {
                val s = c.settings.settings.value
                AppLock.onBackground(c.vault.enabled, s.lockTimeout.millis)
                foreground = false
            }
        })
    }

    /** MailCtrl is on screen. */
    @Volatile
    private var foreground = false

    /**
     * Signs in to every account in the background as soon as MailCtrl opens, so the message you tap first doesn't
     * wait for a sign-in.
     */
    private fun signInAhead() {
        val c = container
        val ids = c.accounts.accounts.value.map { it.id }
        if (ids.isEmpty()) return
        c.appScope.launch { runCatching { c.mail.warmUp(ids) } }
    }

    companion object {
        lateinit var instance: MailCtrlApp
            private set
    }
}
