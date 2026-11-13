package com.vknxworks.mailctrl.data

import com.vknxworks.mailctrl.data.accounts.AccountStore
import com.vknxworks.mailctrl.data.cache.MailCache
import com.vknxworks.mailctrl.mail.FolderInfo
import com.vknxworks.mailctrl.mail.MailClient
import com.vknxworks.mailctrl.mail.MessageHeader
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.mail.Flags

/**
 * Everything you do to mail, on the server and in the list on the phone. Changes show straight away; when the
 * server refuses, the folder is read again so the list shows the truth.
 */
class MailActions(
    private val mail: MailClient,
    private val cache: MailCache,
    private val accounts: AccountStore,
    /** For reading ahead, which outlives the screen that started it. */
    private val scope: CoroutineScope,
) {
    /** When each folder (and each account's folder list) was last brought up to date, on the phone's clock. */
    private val refreshed = ConcurrentHashMap<String, Long>()

    private fun stamp(key: String) {
        refreshed[key] = SystemClock.elapsedRealtime()
    }

    /** Whether [key] was brought up to date less than [maxAgeMs] ago. */
    private fun fresh(key: String, maxAgeMs: Long): Boolean =
        refreshed[key]?.let { SystemClock.elapsedRealtime() - it < maxAgeMs } == true

    /** Whether a folder's list was refreshed less than [maxAgeMs] ago (switching back to a tab doesn't refresh again). */
    fun isFresh(accountId: String, folder: String, maxAgeMs: Long = RECENT_MS): Boolean = fresh("$accountId|$folder", maxAgeMs)

    fun foldersFresh(accountId: String, maxAgeMs: Long = FOLDERS_RECENT_MS): Boolean = fresh("$accountId|folders", maxAgeMs)

    /** Brings a folder's list up to date with the server, downloading only what changed. */
    suspend fun refresh(accountId: String, folder: String) {
        val known = cache.known(accountId, folder, SYNC_WINDOW)
        val sync = mail.sync(accountId, folder, known, PAGE)
        cache.applySync(accountId, folder, sync)
        stamp("$accountId|$folder")
        if (folder == INBOX) readAhead(accountId, folder)
    }

    /**
     * Reads the newest few unread messages ahead in the background, so they open instantly. They stay unread, and
     * nothing from the internet is loaded.
     */
    private fun readAhead(accountId: String, folder: String) {
        scope.launch {
            runCatching {
                val unread = cache.messages(accountId, folder).asSequence().filter { !it.seen }.take(READ_AHEAD).map { it.uid }.toList()
                mail.prefetch(accountId, folder, unread)
            }
        }
    }

    /**
     * Refreshes the inboxes of [accountIds] at once, skipping those refreshed less than [maxAgeMs] ago (0: all of
     * them). Returns the problems, per account.
     */
    suspend fun refreshInboxes(accountIds: List<String>, maxAgeMs: Long = 0): Map<String, Throwable> = coroutineScope {
        accountIds.filter { maxAgeMs <= 0 || !isFresh(it, INBOX, maxAgeMs) }
            .map { id -> async { id to runCatching { refresh(id, INBOX) }.exceptionOrNull() } }
            .awaitAll()
            .mapNotNull { (id, e) -> e?.let { id to it } }
            .toMap()
    }

    /** Older messages below what's shown. Returns whether there are more still. */
    suspend fun loadMore(accountId: String, folder: String): Boolean {
        val oldest = cache.messages(accountId, folder).minOfOrNull { it.uid } ?: run {
            refresh(accountId, folder)
            return true
        }
        val page = mail.messages(accountId, folder, count = PAGE, beforeUid = oldest)
        cache.putOlder(page.headers)
        return page.hasMore
    }

    suspend fun refreshFolders(accountId: String): List<FolderInfo> {
        val list = mail.folders(accountId)
        cache.putFolders(accountId, list)
        stamp("$accountId|folders")
        return list
    }

    private inline fun groups(headers: Collection<MessageHeader>, block: (String, String, List<Long>) -> Unit) {
        headers.groupBy { it.accountId to it.folder }.forEach { (key, list) -> block(key.first, key.second, list.map { it.uid }) }
    }

    private suspend fun onServer(accountId: String, folder: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            runCatching { refresh(accountId, folder) }
            throw e
        }
    }

    suspend fun setSeen(headers: Collection<MessageHeader>, seen: Boolean) = groups(headers) { acc, folder, uids ->
        cache.setFlags(acc, folder, uids, seen = seen)
        onServer(acc, folder) { mail.setFlag(acc, folder, uids, Flags.Flag.SEEN, seen) }
    }

    suspend fun setFlagged(headers: Collection<MessageHeader>, flagged: Boolean) = groups(headers) { acc, folder, uids ->
        cache.setFlags(acc, folder, uids, flagged = flagged)
        onServer(acc, folder) { mail.setFlag(acc, folder, uids, Flags.Flag.FLAGGED, flagged) }
    }

    suspend fun markAnswered(accountId: String, folder: String, uid: Long) {
        cache.setFlags(accountId, folder, listOf(uid), answered = true)
        runCatching { mail.setFlag(accountId, folder, listOf(uid), Flags.Flag.ANSWERED, true) }
    }

    suspend fun markAllRead(accountId: String, folder: String) {
        cache.markFolderRead(accountId, folder)
        onServer(accountId, folder) { mail.markAllRead(accountId, folder) }
    }

    /** Hides messages from the list before the server is asked (for Undo). */
    suspend fun hide(headers: Collection<MessageHeader>) = groups(headers) { acc, folder, uids -> cache.remove(acc, folder, uids) }

    /** Puts hidden messages back (Undo). */
    suspend fun unhide(headers: Collection<MessageHeader>) = cache.putOlder(headers.toList())

    suspend fun delete(headers: Collection<MessageHeader>) = groups(headers) { acc, folder, uids ->
        cache.remove(acc, folder, uids)
        onServer(acc, folder) { mail.delete(acc, folder, uids) }
    }

    /** Whether messages of this account can be archived (it has an Archive or All Mail folder). */
    fun canArchive(accountId: String, folder: String): Boolean {
        val target = accounts.get(accountId)?.archiveFolder ?: return false
        return target != folder
    }

    suspend fun archive(headers: Collection<MessageHeader>) = groups(headers) { acc, folder, uids ->
        val target = accounts.get(acc)?.archiveFolder ?: return@groups
        if (target == folder) return@groups
        cache.remove(acc, folder, uids)
        onServer(acc, folder) { mail.move(acc, folder, uids, target) }
    }

    suspend fun move(headers: Collection<MessageHeader>, target: String) = groups(headers) { acc, folder, uids ->
        if (target == folder) return@groups
        cache.remove(acc, folder, uids)
        onServer(acc, folder) { mail.move(acc, folder, uids, target) }
    }

    /** Moves to the account's Junk folder. */
    suspend fun junk(headers: Collection<MessageHeader>) = groups(headers) { acc, folder, uids ->
        val target = accounts.get(acc)?.junkFolder ?: return@groups
        if (target == folder) return@groups
        cache.remove(acc, folder, uids)
        onServer(acc, folder) { mail.move(acc, folder, uids, target) }
    }

    suspend fun emptyFolder(accountId: String, folder: String) {
        cache.clearFolder(accountId, folder)
        onServer(accountId, folder) { mail.emptyFolder(accountId, folder) }
    }

    companion object {
        const val INBOX = "INBOX"
        const val PAGE = 50

        /** How many of a folder's newest messages on the phone are checked for changes on every refresh. */
        private const val SYNC_WINDOW = 300

        /** Unread messages read ahead after an inbox refresh. */
        private const val READ_AHEAD = 5

        /** Opening a tab again within this time shows what's there without asking the server again. */
        const val RECENT_MS = 30_000L
        const val FOLDERS_RECENT_MS = 120_000L
    }
}
