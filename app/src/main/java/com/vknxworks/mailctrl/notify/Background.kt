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
                    c.notifier.newMail(a, r.fresh, s.notificationContent, all.size > 1)
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

/** "Mark as read" and "Delete" on a new-mail notification. Only MailCtrl's own notifications can send these. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val account = intent.getStringExtra(MainActivity.EXTRA_ACCOUNT) ?: return
        val folder = intent.getStringExtra(MainActivity.EXTRA_FOLDER) ?: return
        val uid = intent.getLongExtra(MainActivity.EXTRA_UID, -1L).takeIf { it > 0 } ?: return
        val c = context.applicationContext.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                val h = c.cache.header(account, folder, uid) ?: MessageHeader(account, folder, uid, null, emptyList(), "", 0L, false, false, false, false)
                c.notifier.cancel(h)
                if (c.backgroundBlocked()) return@launch
                when (intent.action) {
                    ACTION_READ -> runCatching { c.actions.setSeen(listOf(h), true) }
                    ACTION_DELETE -> runCatching { c.actions.delete(listOf(h)) }
                }
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
