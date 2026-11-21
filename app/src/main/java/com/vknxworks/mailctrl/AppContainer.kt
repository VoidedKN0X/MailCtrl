package com.vknxworks.mailctrl

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.vknxworks.mailctrl.data.MailActions
import com.vknxworks.mailctrl.data.accounts.AccountStore
import com.vknxworks.mailctrl.data.cache.MailCache
import com.vknxworks.mailctrl.data.crypto.DataKeyStore
import com.vknxworks.mailctrl.data.crypto.KeystoreKeyWrapper
import com.vknxworks.mailctrl.data.drafts.Draft
import com.vknxworks.mailctrl.data.drafts.DraftStore
import com.vknxworks.mailctrl.data.local.MailedAddresses
import com.vknxworks.mailctrl.data.local.Outbox
import com.vknxworks.mailctrl.data.local.OutboxItem
import com.vknxworks.mailctrl.data.local.SnoozeStore
import com.vknxworks.mailctrl.mail.Attachment
import com.vknxworks.mailctrl.mail.MessageHeader
import com.vknxworks.mailctrl.notify.LaterWork
import com.vknxworks.mailctrl.ui.components.fullTime
import java.util.concurrent.ConcurrentHashMap
import com.vknxworks.mailctrl.data.local.PictureSenders
import com.vknxworks.mailctrl.data.lock.LockVault
import com.vknxworks.mailctrl.data.pgp.PgpKeyStore
import com.vknxworks.mailctrl.data.pgp.PgpService
import com.vknxworks.mailctrl.pgp.UnlockedKey
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

/** A message that waits a few seconds before it's sent, so it can still be taken back. */
class PendingUndo(val id: String, val accountId: String, val until: Long)

/** The attachments of a message taken back with Undo: files picked on the phone, and those of a forwarded message. */
class ComposeRestore(
    val files: List<Uri>,
    val forwarded: List<Attachment>,
    val forwardFolder: String?,
    val forwardUid: Long?,
)

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Work that outlives a screen. A failure in one job never takes the app down. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)

    /** The app lock's PIN, fingerprint key and the key that seals drafts (and passwords) while locked. */
    val vault = LockVault(context, "mailctrl")
    val accounts = AccountStore(context, vault)
    // Made when first needed, so starting the app (or a background check) doesn't open what it won't use.
    // The data key encrypts the message list at rest; it's only read (off the main thread) when the list is.
    private val dataKey = DataKeyStore(context.noBackupFilesDir, KeystoreKeyWrapper())
    val cache by lazy { MailCache(context, dataKey, appScope) }
    /** Senders whose pictures always load, and the addresses you've mailed (for suggestions): encrypted, on the phone only. */
    val pictureSenders by lazy { PictureSenders(context.noBackupFilesDir, dataKey, appScope) }
    val mailed by lazy { MailedAddresses(context.noBackupFilesDir, dataKey, appScope) }
    /** Messages waiting to be sent (Send later, the Undo seconds) and snoozed messages: encrypted, on the phone only. */
    val outbox by lazy { Outbox(context.noBackupFilesDir, dataKey, appScope) }
    val snoozes by lazy { SnoozeStore(context.noBackupFilesDir, dataKey, appScope) }

    /** A message held for the Undo seconds: the bar at the bottom of the screen offers Undo until [PendingUndo.until]. */
    val pendingUndo = MutableStateFlow<PendingUndo?>(null)

    /** The files of a message taken back with Undo, so writing it again has its attachments (memory only). */
    val composeRestore = ConcurrentHashMap<String, ComposeRestore>()
    /** Messages kept on the phone, encrypted, so they open without the server. */
    val messages by lazy { com.vknxworks.mailctrl.mail.MessageStore(context) }
    val mail by lazy { MailClient(context, accounts, messages) }
    val actions by lazy { MailActions(mail, cache, accounts, appScope) }
    val drafts by lazy { DraftStore(context, vault) }
    val notifier = Notifier(context)
    /** OpenPGP keys (yours and your contacts'), read from storage in the background the first time they're needed. */
    val pgpKeys by lazy { PgpKeyStore(context).also { store -> appScope.launch { runCatching { store.load() } } } }
    private val pgpService = lazy { PgpService(pgpKeys, settings) { mail } }
    val pgp by pgpService

    /** Set by MainActivity for notifications, mailto: links and shares; the main screen consumes it. */
    val pendingAction = MutableStateFlow<PendingAction?>(null)

    /** Set by "All inboxes" in the Folders tab: the Inbox tab switches to it. */
    val showAllInboxes = MutableStateFlow(false)

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
        // OpenPGP passphrases are never remembered past the lock.
        if (pgpService.isInitialized()) pgp.forget()
        // Messages kept in memory to open quickly are forgotten while locked.
        mail.forgetBodies()
        if (accounts.anySealed()) mail.closeAll()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
        drafts.reload()
        // Messages that were due while the passwords were sealed go out now.
        appScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                outbox.load().filter { it.sendAt <= now }.forEach { item ->
                    outbox.reschedule(item.id, now)
                    LaterWork.send(context, item.id, now)
                }
            }
        }
    }

    /** After a message really went out: the message it answers is marked, and its recipients are remembered. */
    suspend fun afterSent(draft: Draft) {
        val folder = draft.replyFolder
        val uid = draft.replyUid
        if (folder != null && uid != null) actions.markAnswered(draft.accountId, folder, uid)
        // Remembered for suggestions the next time you write (only addresses you mailed yourself).
        if (settings.current().suggestRecipients) runCatching { mailed.record(draft.to + draft.cc + draft.bcc) }
    }

    /**
     * Undo, or Cancel in "Scheduled and snoozed": takes a waiting message back and makes it a draft again.
     * Returns the draft, or null when it was already being sent.
     */
    suspend fun takeBack(id: String): Draft? {
        if (pendingUndo.value?.id == id) pendingUndo.value = null
        val item = outbox.cancel(id) ?: return null
        LaterWork.cancelSend(context, id)
        runCatching { drafts.save(item.draft) }
        return item.draft
    }

    /** Sends a waiting message now instead of at its time. */
    suspend fun sendNow(id: String) {
        val now = System.currentTimeMillis()
        outbox.reschedule(id, now)
        LaterWork.send(context, id, now)
    }

    /** Hides [headers] from the lists on this phone until [until]; a notification brings them back. */
    suspend fun snooze(headers: Collection<MessageHeader>, until: Long) {
        if (headers.isEmpty()) return
        snoozes.snooze(headers, until)
        notifier.cancelKeys(headers)
        scheduleSnoozeWake()
    }

    /** Sets the wake-up for the snoozed message that is due first. */
    suspend fun scheduleSnoozeWake() {
        val next = snoozes.load().minOfOrNull { it.until } ?: return
        LaterWork.snooze(context, next)
    }

    /** Shows the snoozed messages whose time has come again, notifies about them, and sets the next wake-up. */
    suspend fun wakeSnoozed() {
        val due = snoozes.takeDue(System.currentTimeMillis())
        if (due.isNotEmpty()) {
            val s = settings.current()
            val all = accounts.accounts.value
            if (s.notifyEnabled) {
                due.forEach { z ->
                    val account = all.firstOrNull { it.id == z.accountId } ?: return@forEach
                    // Deleted or moved meanwhile: nothing to bring back.
                    val h = cache.header(z.accountId, z.folder, z.uid) ?: return@forEach
                    notifier.snoozedBack(account, h, s.notificationContentFor(vault.enabled), all.size > 1)
                }
            }
        }
        scheduleSnoozeWake()
    }

    /**
     * Sends a message in the background, so leaving the screen doesn't stop it. Until it's gone it stays a draft;
     * if it fails, the draft is kept and you're told why.
     */
    fun send(
        draft: Draft,
        attachments: suspend () -> List<OutgoingAttachment>,
        /** Your OpenPGP key, opened just now to sign this message (when its passphrase isn't remembered). */
        signer: UnlockedKey? = null,
        /** "Send later": when to send it. Null: now (after the Undo seconds, when those are set). */
        sendAt: Long? = null,
        /** Its attachments, to hand back to the Compose screen if it's taken back with Undo. */
        restore: ComposeRestore? = null,
        onDone: (Boolean) -> Unit = {},
    ) {
        appScope.launch {
            val account = accounts.get(draft.accountId)
            val ok = try {
                if (account == null) throw IllegalStateException(str(R.string.error_no_account))
                if (draft.encrypt || draft.sign || account.autocrypt) pgpKeys.load()
                // Checked before the attachments are read: a missing key stops it straight away.
                val protection = pgp.outgoing(account, draft, signer)
                val files = attachments()
                val options = settings.current()
                val out =
                    OutgoingMessage(
                        to = draft.to,
                        cc = draft.cc,
                        bcc = draft.bcc,
                        subject = draft.subject,
                        body = draft.body,
                        attachments = files,
                        inReplyTo = draft.inReplyTo,
                        references = draft.references,
                        pgp = protection,
                        // The key in an Autocrypt header is for the account's own address, not for an identity.
                        autocrypt = if (account.sender(draft.from).email.equals(account.email, ignoreCase = true)) pgp.autocryptHeader(account) else null,
                        from = draft.from,
                        protectSubject = options.encryptSubject,
                    )
                // Sent later, or held for the Undo seconds: put together now (signed or encrypted now) and kept
                // encrypted on the phone until its time, when Android's WorkManager sends it.
                val now = System.currentTimeMillis()
                val at = sendAt ?: if (options.undoSend > 0) now + options.undoSend * 1000L else null
                var waiting = false
                if (at != null) {
                    val raw = mail.prepare(draft.accountId, out)
                    waiting = outbox.add(OutboxItem(draft.id, draft.accountId, draft, at, now, undo = sendAt == null), raw)
                    // It can't be kept encrypted on this phone: a scheduled message isn't sent early instead.
                    if (!waiting && sendAt != null) throw IllegalStateException(str(R.string.later_cant_keep))
                }
                if (waiting && at != null) {
                    drafts.delete(draft.id)
                    LaterWork.send(context, draft.id, at)
                    if (sendAt == null) {
                        // Only the message that can still be taken back keeps its files at hand.
                        composeRestore.clear()
                        if (restore != null) composeRestore[draft.id] = restore
                        pendingUndo.value = PendingUndo(draft.id, draft.accountId, at)
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, str(R.string.later_scheduled, fullTime(context, at)), Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val savedSent = mail.send(draft.accountId, out)
                    drafts.delete(draft.id)
                    afterSent(draft)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, str(if (savedSent) R.string.sent else R.string.sent_no_copy), Toast.LENGTH_SHORT).show()
                    }
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
