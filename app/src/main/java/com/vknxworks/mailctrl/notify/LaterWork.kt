package com.vknxworks.mailctrl.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.mail.LockedException
import com.vknxworks.mailctrl.ui.components.friendlyMessage
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.mail.MessagingException

/** Schedules the work for "Send later", the Undo seconds and "Snooze" (Android's WorkManager; survives a restart). */
object LaterWork {
    private fun sendName(id: String) = "mailctrl-outbox-$id"
    private const val SNOOZE = "mailctrl-snooze"

    /** Sends the waiting message [id] at [at] (or as soon after as the phone is online). */
    fun send(context: Context, id: String, at: Long) {
        val req = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(OutboxWorker.KEY_ID to id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(sendName(id), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelSend(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(sendName(id))
    }

    /** Wakes snoozed messages at [at], the earliest time one of them is due. */
    fun snooze(context: Context, at: Long) {
        val req = OneTimeWorkRequestBuilder<SnoozeWorker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SNOOZE, ExistingWorkPolicy.REPLACE, req)
    }

    /** The connection to the server was never made, so the message certainly didn't go out: trying again is safe. */
    fun neverConnected(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth++ < 12) {
            if (cause is UnknownHostException || cause is ConnectException || cause is NoRouteToHostException) return true
            cause = if (cause is MessagingException) cause.nextException ?: cause.cause else cause.cause
        }
        return false
    }
}

/** Sends one waiting message when its time has come. */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val c = applicationContext.container
        val claimed = try {
            c.outbox.begin(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return Result.success()
        val (item, raw) = claimed
        val account = c.accounts.get(item.accountId)
        var sent = false
        var error: String? = null
        var retry = false
        try {
            when {
                account == null -> error = applicationContext.getString(R.string.error_no_account)
                // The list is readable but this message isn't: it stays where it is, with the reason.
                raw == null -> error = applicationContext.getString(R.string.later_unreadable)
                // Passwords are sealed with the app PIN and MailCtrl is locked: it goes out once you unlock.
                c.backgroundBlocked() -> error = applicationContext.getString(R.string.later_locked)
                else -> {
                    c.mail.sendPrepared(item.accountId, raw)
                    sent = true
                }
            }
        } catch (e: CancellationException) {
            runCatching { c.outbox.finish(id, sent = false, error = item.error) }
            throw e
        } catch (e: LockedException) {
            error = applicationContext.getString(R.string.later_locked)
        } catch (e: Throwable) {
            // Only when the server was never reached is another try certain not to send it twice.
            if (LaterWork.neverConnected(e) && runAttemptCount < MAX_TRIES) retry = true else error = e.friendlyMessage()
        }
        runCatching { c.outbox.finish(id, sent = sent, error = if (retry) item.error else error) }
        if (sent) {
            runCatching { c.afterSent(item.draft) }
        } else if (error != null && account != null) {
            c.notifier.sendFailed(account, item.draft.subject, error)
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        private const val MAX_TRIES = 5
    }
}

/** Shows snoozed messages again when their time has come, with a notification, and sets up the next wake-up. */
class SnoozeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        try {
            c.wakeSnoozed()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The messages show again the next time MailCtrl opens.
        }
        return Result.success()
    }
}
