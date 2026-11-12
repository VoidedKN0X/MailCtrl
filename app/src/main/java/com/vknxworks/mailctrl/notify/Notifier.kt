package com.vknxworks.mailctrl.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.vknxworks.mailctrl.MainActivity
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.data.settings.NotificationContent
import com.vknxworks.mailctrl.mail.MessageHeader
import java.security.MessageDigest
import java.security.SecureRandom

/** New-mail notifications: one per message, grouped per account. */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_MAIL, context.getString(R.string.channel_mail), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_mail_desc)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PROBLEMS, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_problems_desc)
            },
        )
    }

    private fun color() = ContextCompat.getColor(context, R.color.notify_accent)

    /**
     * A random secret made once per install. MainActivity is exported (it's the launcher), so "open this
     * message" is only accepted from intents that carry it, which only MailCtrl's own notifications do.
     */
    private val linkSecret: String by lazy {
        val prefs = context.getSharedPreferences(LINK_PREFS, Context.MODE_PRIVATE)
        prefs.getString(K_LINK_SECRET, null)?.takeIf { it.length >= 32 } ?: run {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val secret = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(K_LINK_SECRET, secret).commit()
            secret
        }
    }

    /** Whether [secret] is the one MailCtrl's own notifications carry. */
    fun isOwnLink(secret: String?): Boolean =
        !secret.isNullOrEmpty() && MessageDigest.isEqual(secret.toByteArray(), linkSecret.toByteArray())

    private fun openIntent(h: MessageHeader?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (h != null) {
                action = MainActivity.ACTION_OPEN_MESSAGE
                putExtra(MainActivity.EXTRA_ACCOUNT, h.accountId)
                putExtra(MainActivity.EXTRA_FOLDER, h.folder)
                putExtra(MainActivity.EXTRA_UID, h.uid)
                putExtra(MainActivity.EXTRA_SECRET, linkSecret)
            } else {
                action = MainActivity.ACTION_OPEN_INBOX
            }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(h: MessageHeader, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(MainActivity.EXTRA_ACCOUNT, h.accountId)
            putExtra(MainActivity.EXTRA_FOLDER, h.folder)
            putExtra(MainActivity.EXTRA_UID, h.uid)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun idFor(h: MessageHeader): Int = h.key.hashCode()

    private fun groupKey(accountId: String) = "mail:$accountId"
    private fun summaryId(accountId: String) = ("summary:$accountId").hashCode()

    /**
     * Shows new messages of one account. [content] decides how much they show; the lock screen only ever says
     * "New mail".
     */
    fun newMail(account: Account, fresh: List<MessageHeader>, content: NotificationContent, multipleAccounts: Boolean) {
        if (fresh.isEmpty() || !allowed(context)) return
        val publicVersion = Notification.Builder(context, CH_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color())
            .setContentTitle(context.getString(R.string.notify_new_mail))
            .build()
        fresh.forEach { h ->
            val id = idFor(h)
            val sender = h.from?.display ?: context.getString(R.string.unknown_sender)
            val subject = h.subject.ifBlank { context.getString(R.string.no_subject) }
            val (title, text) = when (content) {
                NotificationContent.SENDER_AND_SUBJECT -> sender to subject
                NotificationContent.SENDER_ONLY -> sender to context.getString(R.string.notify_new_mail)
                NotificationContent.NOTHING -> context.getString(R.string.notify_new_mail) to null
            }
            val b = Notification.Builder(context, CH_MAIL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(color())
                .setContentTitle(title)
                .setWhen(h.date)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EMAIL)
                .setGroup(groupKey(account.id))
                .setContentIntent(openIntent(h, id))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .addAction(
                    Notification.Action.Builder(null as android.graphics.drawable.Icon?, context.getString(R.string.mark_read), actionIntent(h, NotificationActionReceiver.ACTION_READ, id)).build(),
                )
                .addAction(
                    Notification.Action.Builder(null as android.graphics.drawable.Icon?, context.getString(R.string.delete), actionIntent(h, NotificationActionReceiver.ACTION_DELETE, id + 1)).build(),
                )
            if (text != null) b.setContentText(text)
            if (multipleAccounts) b.setSubText(account.title)
            if (content == NotificationContent.SENDER_AND_SUBJECT) b.setStyle(Notification.BigTextStyle().bigText(subject))
            runCatching { nm.notify(TAG, id, b.build()) }
        }
        val summary = Notification.Builder(context, CH_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color())
            .setContentTitle(context.getString(R.string.notify_new_mail))
            .setSubText(account.title.takeIf { multipleAccounts && content != NotificationContent.NOTHING })
            .setGroup(groupKey(account.id))
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(null, summaryId(account.id)))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        runCatching { nm.notify(TAG, summaryId(account.id), summary) }
    }

    fun cancel(h: MessageHeader) {
        runCatching { nm.cancel(TAG, idFor(h)) }
        cleanSummary(h.accountId, setOf(idFor(h)))
    }

    fun cancelKeys(headers: Collection<MessageHeader>) {
        headers.forEach { runCatching { nm.cancel(TAG, idFor(it)) } }
        val gone = headers.map { idFor(it) }.toSet()
        headers.map { it.accountId }.toSet().forEach { cleanSummary(it, gone) }
    }

    /**
     * Removes the group summary when its last message notification went. [gone] were just cancelled: the list of
     * active notifications can still show them for a moment.
     */
    private fun cleanSummary(accountId: String, gone: Set<Int>) {
        val group = groupKey(accountId)
        val left = runCatching {
            nm.activeNotifications.count { it.tag == TAG && it.notification.group == group && it.id != summaryId(accountId) && it.id !in gone }
        }.getOrDefault(1)
        if (left == 0) runCatching { nm.cancel(TAG, summaryId(accountId)) }
    }

    fun cancelAccount(accountId: String) {
        val group = groupKey(accountId)
        runCatching { nm.activeNotifications.filter { it.notification.group == group }.forEach { nm.cancel(it.tag, it.id) } }
    }

    fun cancelAll() {
        runCatching { nm.activeNotifications.filter { it.tag == TAG }.forEach { nm.cancel(it.tag, it.id) } }
    }

    /** A message couldn't be sent: shown so it isn't lost silently while you're elsewhere. */
    fun sendFailed(account: Account, subject: String, reason: String) {
        if (!allowed(context)) return
        val n = Notification.Builder(context, CH_PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color())
            .setContentTitle(context.getString(R.string.notify_send_failed, subject.ifBlank { context.getString(R.string.no_subject) }))
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setSubText(account.title)
            .setAutoCancel(true)
            .setContentIntent(openIntent(null, 7))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        runCatching { nm.notify(TAG_PROBLEM, account.id.hashCode(), n) }
    }

    companion object {
        const val CH_MAIL = "mail"
        const val CH_PROBLEMS = "problems"
        private const val TAG = "mail"
        private const val TAG_PROBLEM = "problem"
        private const val LINK_PREFS = "notify_links"
        private const val K_LINK_SECRET = "link_secret"

        fun allowed(context: Context): Boolean {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val nm = context.getSystemService(NotificationManager::class.java)
            return granted && nm?.areNotificationsEnabled() == true
        }
    }
}
