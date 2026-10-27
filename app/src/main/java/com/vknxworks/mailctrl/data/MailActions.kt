package com.vknxworks.mailctrl.data

import com.vknxworks.mailctrl.data.accounts.AccountStore
import com.vknxworks.mailctrl.data.cache.MailCache
import com.vknxworks.mailctrl.mail.FolderInfo
import com.vknxworks.mailctrl.mail.MailClient
import com.vknxworks.mailctrl.mail.MessageHeader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.mail.Flags

/**
 * Everything you do to mail, on the server and in the list on the phone. Changes show straight away; when the
 * server refuses, the folder is read again so the list shows the truth.
 */
class MailActions(
    private val mail: MailClient,
    private val cache: MailCache,
    private val accounts: AccountStore,
) {
    /** Newest messages of a folder, from the server. */
    suspend fun refresh(accountId: String, folder: String) {
        val cached = cache.messages(accountId, folder).size
        val page = mail.messages(accountId, folder, count = cached.coerceIn(PAGE, MAX_REFRESH))
        cache.putNewest(accountId, folder, page.headers, complete = !page.hasMore)
    }

    /** Refreshes every account's inbox at once. Returns the problems, per account. */
    suspend fun refreshInboxes(accountIds: List<String>): Map<String, Throwable> = coroutineScope {
        accountIds.map { id -> async { id to runCatching { refresh(id, INBOX) }.exceptionOrNull() } }
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
        private const val MAX_REFRESH = 200
    }
}
