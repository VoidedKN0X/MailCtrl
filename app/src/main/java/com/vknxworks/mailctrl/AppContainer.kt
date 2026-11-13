package com.vknxworks.mailctrl

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.vknxworks.mailctrl.data.MailActions
import com.vknxworks.mailctrl.data.accounts.AccountStore
import com.vknxworks.mailctrl.data.cache.MailCache
import com.vknxworks.mailctrl.data.drafts.Draft
import com.vknxworks.mailctrl.data.drafts.DraftStore
import com.vknxworks.mailctrl.data.lock.LockVault
import com.vknxworks.mailctrl.data.settings.SettingsRepository
import com.vknxworks.mailctrl.mail.MailClient
import com.vknxworks.mailctrl.mail.MessageBody
import com.vknxworks.mailctrl.mail.OutgoingAttachment
import com.vknxworks.mailctrl.mail.OutgoingMessage
import com.vknxworks.mailctrl.notify.Notifier
import com.vknxworks.mailctrl.ui.components.friendlyMessage
import com.vknxworks.mailctrl.ui.lock.AppLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something another app, a notification or a shortcut asked the app to open. */
sealed interface PendingAction {
    data class OpenMessage(val accountId: String, val folder: String, val uid: Long) : PendingAction
    data object OpenInbox : PendingAction
    /** Write a new message, with some fields and files ready (mailto: links, shares). */
    data class Compose(
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val subject: String = "",
        val body: String = "",
        val attachments: List<Uri> = emptyList(),
    ) : PendingAction
}

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Work that outlives a screen. A failure in one job never takes the app down. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)

    /** The app lock's PIN, fingerprint key and the key that seals drafts (and passwords) while locked. */
    val vault = LockVault(context, "mailctrl")
    val accounts = AccountStore(context, vault)
    // Made when first needed, so starting the app (or a background check) doesn't open what it won't use.
    val cache by lazy { MailCache(context) }
    val mail by lazy { MailClient(context, accounts) }
    val actions by lazy { MailActions(mail, cache, accounts, appScope) }
    val drafts by lazy { DraftStore(context, vault) }
    val notifier = Notifier(context)

    /** Set by MainActivity for notifications, mailto: links and shares; the main screen consumes it. */
    val pendingAction = MutableStateFlow<PendingAction?>(null)

    /** What to put in the next Compose screen that opens (a share, a mailto: link). */
    val composeRequest = MutableStateFlow<PendingAction.Compose?>(null)

    /** The message open on screen, so answering or forwarding it doesn't download it again. */
    @Volatile
    var lastOpened: MessageBody? = null

    /**
     * Background work can't read the passwords right now: they're sealed with the app lock (notifications are
     * off) and MailCtrl is locked.
     */
    fun backgroundBlocked(): Boolean = vault.enabled && !vault.isOpen && accounts.anySealed()

    /** Turns the app lock on: drafts are sealed with the PIN, and passwords too when notifications are off. */
    suspend fun enableLock(pin: String) {
        // Unlocked first, so the lock screen doesn't flash up while the PIN is being saved.
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
        runCatching { accounts.applySealing() }
        runCatching { drafts.sealAll() }
    }

    /** Turns the app lock off (after the PIN was confirmed): secrets go back to the hardware key only. */
    suspend fun disableLock() {
        val policy = accounts.sealPolicy
        accounts.sealPolicy = { false }
        try {
            runCatching { accounts.applySealing() }
            runCatching { drafts.unsealAll() }
        } finally {
            accounts.sealPolicy = policy
        }
        vault.disable()
    }

    /**
     * "Forgot PIN?", after the phone's own screen lock confirmed it's you: turns the app lock off. Drafts and
     * passwords sealed with the forgotten PIN can't be read any more: drafts are removed, and those accounts
     * ask for their password again.
     */
    suspend fun resetLock() {
        runCatching { accounts.dropSealed() }
        runCatching { drafts.dropSealed() }
        vault.disable()
        AppLock.markUnlocked()
    }

    /** Locked: wipe the key from memory and close mail connections. */
    fun onLocked() {
        vault.close()
        lastOpened = null
        // Messages kept in memory to open quickly are forgotten while locked.
        mail.forgetBodies()
        if (accounts.anySealed()) mail.closeAll()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
        drafts.reload()
    }

    /**
     * Sends a message in the background, so leaving the screen doesn't stop it. Until it's gone it stays a draft;
     * if it fails, the draft is kept and you're told why.
     */
    fun send(draft: Draft, attachments: suspend () -> List<OutgoingAttachment>, onDone: (Boolean) -> Unit = {}) {
        appScope.launch {
            val account = accounts.get(draft.accountId)
            val ok = try {
                if (account == null) throw IllegalStateException(str(R.string.error_no_account))
                val files = attachments()
                val savedSent = mail.send(
                    draft.accountId,
                    OutgoingMessage(
                        to = draft.to,
                        cc = draft.cc,
                        bcc = draft.bcc,
                        subject = draft.subject,
                        body = draft.body,
                        attachments = files,
                        inReplyTo = draft.inReplyTo,
                        references = draft.references,
                    ),
                )
                drafts.delete(draft.id)
                val folder = draft.replyFolder
                val uid = draft.replyUid
                if (folder != null && uid != null) actions.markAnswered(draft.accountId, folder, uid)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, str(if (savedSent) R.string.sent else R.string.sent_no_copy), Toast.LENGTH_SHORT).show()
                }
                true
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val reason = e.friendlyMessage()
                runCatching { drafts.save(draft) }
                if (account != null) notifier.sendFailed(account, draft.subject, reason)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, str(R.string.send_failed, reason), Toast.LENGTH_LONG).show()
                }
                false
            }
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as MailCtrlApp).container
