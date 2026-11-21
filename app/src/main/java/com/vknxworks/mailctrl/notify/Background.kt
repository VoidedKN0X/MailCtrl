package com.vknxworks.mailctrl.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vknxworks.mailctrl.AppContainer
import com.vknxworks.mailctrl.MainActivity
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.settings.AppSettings
import com.vknxworks.mailctrl.mail.MessageHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Remembers, per account, the newest inbox message already seen, so only new mail is notified. */
object NotifyChecker {
    private const val PREFS = "notify_state"
    private const val LAST_CHECK = "last_check"
    private const val LAST_PUSH = "last_push"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCheck(context: Context): Long = prefs(context).getLong(LAST_CHECK, 0L)
    fun lastPush(context: Context): Long = prefs(context).getLong(LAST_PUSH, 0L)

    fun markPush(context: Context) {
        prefs(context).edit().putLong(LAST_PUSH, System.currentTimeMillis()).apply()
    }

    /** Starts over: the next check only notes where each inbox is, so old mail isn't reported. */
    fun reset(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        p.all.keys.filter { it.startsWith("uid:") || it.startsWith("validity:") }.forEach { edit.remove(it) }
        edit.apply()
    }

    fun forget(context: Context, accountId: String) {
        prefs(context).edit().remove("uid:$accountId").remove("validity:$accountId").apply()
    }

    /** Checks every account that notifies, and shows what's new. */
    suspend fun check(c: AppContainer) {
        val context = c.context
        val s = c.settings.current()
        if (!s.notifyEnabled) return
        val all = c.accounts.accounts.value
        val list = all.filter { it.notify && !c.accounts.needsPassword(it) }
        val p = prefs(context)
        for (a in list) {
            try {
                val last = p.getLong("uid:${a.id}", 0L)
                val validity = p.getLong("validity:${a.id}", 0L)
                val r = c.mail.checkInbox(a.id, last, validity)
                p.edit().putLong("uid:${a.id}", r.lastUid).putLong("validity:${a.id}", r.uidValidity).apply()
                if (r.fresh.isNotEmpty()) {
                    // The inbox list shows them too, the next time it's opened.
                    c.cache.putOlder(r.fresh)
                    c.notifier.newMail(a, r.fresh, s.notificationContentFor(c.vault.enabled), all.size > 1)
                    // Downloaded now, so tapping the notification opens the message without waiting.
                    if (s.keepMessages) {
                        // Started in the background: the settings may not have reached the store yet.
                        c.messages.enabled = true
                        runCatching { c.mail.prefetch(a.id, "INBOX", r.fresh) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // One account failing doesn't stop the others; the next check tries again.
            }
        }
        p.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
    }
}

/** One check of every account (periodic, after a push, or "Check now"). */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        try {
            // Passwords sealed with the app lock and MailCtrl is locked: nothing can sign in until it's unlocked.
            if (c.backgroundBlocked()) return Result.success()
            // "Instant with an open connection" on, but Android stopped its service: started again when Android allows.
            runCatching { IdleControl.apply(applicationContext, c.settings.current()) }
            // A push usually arrives the moment the mail does; give the server a moment to file it.
            if (inputData.getBoolean(KEY_PUSH, false)) delay(2_000)
            NotifyChecker.check(c)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Never retry in a loop; the next check or push tries again.
        }
        return Result.success()
    }

    companion object {
        const val KEY_PUSH = "push"
    }
}

object NotifySchedule {
    private const val PERIODIC = "mailctrl-check"
    private const val NOW = "mailctrl-check-now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Applies the notification settings: periodic checks and the registration with ntfy. */
    fun apply(context: Context, s: AppSettings) {
        val wm = WorkManager.getInstance(context)
        if (s.notifyEnabled && s.notifyInterval > 0) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(s.notifyInterval.toLong(), TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(PERIODIC)
        }
        // Registering again at start-up recovers from a reinstalled ntfy; with the same token ntfy keeps the address.
        if (s.notifyEnabled && s.pushEnabled) {
            runCatching { UnifiedPush.register(context, context.getString(R.string.app_name)) }
        }
    }

    /** Checks straight away (a push woke MailCtrl, or you tapped Check now). */
    fun checkNow(context: Context, push: Boolean) {
        val req = OneTimeWorkRequestBuilder<CheckWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(CheckWorker.KEY_PUSH to push))
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

/**
 * "Mark as read" and "Delete" on a new-mail notification. Only MailCtrl's own notifications can send these.
 *
 * The notification goes at once; the work with the mail server (signing in, then marking or deleting) is handed
 * to [NotificationActionWorker]. A broadcast only gets a few seconds, and a slow sign-in could run past that,
 * with Android stopping MailCtrl and the action lost.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val account = intent.getStringExtra(MainActivity.EXTRA_ACCOUNT) ?: return
        val folder = intent.getStringExtra(MainActivity.EXTRA_FOLDER) ?: return
        val uid = intent.getLongExtra(MainActivity.EXTRA_UID, -1L).takeIf { it > 0 } ?: return
        val action = intent.action
        val app = context.applicationContext
        val c = app.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                val h = c.cache.header(account, folder, uid) ?: MessageHeader(account, folder, uid, null, emptyList(), "", 0L, false, false, false, false)
                c.notifier.cancel(h)
                if (c.backgroundBlocked()) return@launch
                if (action != ACTION_READ && action != ACTION_DELETE) return@launch
                val req = OneTimeWorkRequestBuilder<NotificationActionWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(
                        workDataOf(
                            NotificationActionWorker.KEY_ACCOUNT to account,
                            NotificationActionWorker.KEY_FOLDER to folder,
                            NotificationActionWorker.KEY_UID to uid,
                            NotificationActionWorker.KEY_ACTION to action,
                        ),
                    )
                    .build()
                // One job per message and action; tapping the same action twice doesn't queue it twice.
                WorkManager.getInstance(app).enqueueUniqueWork("mailctrl-action-$action-$account-$folder-$uid", ExistingWorkPolicy.KEEP, req)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_READ = "com.vknxworks.mailctrl.MARK_READ"
        const val ACTION_DELETE = "com.vknxworks.mailctrl.DELETE"
    }
}

/** Does what a notification's "Mark as read" or "Delete" asked for, on the phone's list and on the server. */
class NotificationActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val account = inputData.getString(KEY_ACCOUNT) ?: return Result.success()
        val folder = inputData.getString(KEY_FOLDER) ?: return Result.success()
        val uid = inputData.getLong(KEY_UID, -1L).takeIf { it > 0 } ?: return Result.success()
        val c = applicationContext.container
        try {
            // Locked in the meantime, with the passwords sealed: nothing can sign in.
            if (c.backgroundBlocked()) return Result.success()
            val h = c.cache.header(account, folder, uid) ?: MessageHeader(account, folder, uid, null, emptyList(), "", 0L, false, false, false, false)
            when (inputData.getString(KEY_ACTION)) {
                NotificationActionReceiver.ACTION_READ -> c.actions.setSeen(listOf(h), true)
                NotificationActionReceiver.ACTION_DELETE -> c.actions.delete(listOf(h))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // As before: an action that fails isn't tried again (the next refresh shows the message as it is on the server).
        }
        return Result.success()
    }

    companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_FOLDER = "folder"
        const val KEY_UID = "uid"
        const val KEY_ACTION = "action"
    }
}
