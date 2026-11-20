package com.vknxworks.mailctrl.data.cache

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vknxworks.mailctrl.data.crypto.DataKeyStore
import com.vknxworks.mailctrl.data.crypto.SealedFile
import com.vknxworks.mailctrl.mail.Address
import com.vknxworks.mailctrl.mail.FolderInfo
import com.vknxworks.mailctrl.mail.FolderRole
import com.vknxworks.mailctrl.mail.FolderSync
import com.vknxworks.mailctrl.mail.KnownRange
import com.vknxworks.mailctrl.mail.MessageHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Who a listed message is from and to, and its subject: what is encrypted together, per message. */
@Serializable
internal data class SealedHeader(
    val n: String? = null,
    val e: String? = null,
    val t: List<Address> = emptyList(),
    val s: String = "",
)

/** The part of one folder's message list that is on screen. */
class FolderPage(
    val headers: List<MessageHeader>,
    /** The oldest message number the page reaches down to (0: everything); null while the folder is empty. */
    val floorUid: Long?,
    /** The phone has older messages of this folder below the page. */
    val olderCached: Boolean,
    /** Unread messages in the whole folder, as cached (not only on the page). */
    val unread: Int,
)

/** How often each folder's message list changed; see [MailCache.listVersions]. */
class ListVersions(private val all: Int = 0, private val folders: Map<String, Int> = emptyMap()) {
    /** Changes whenever the list of [folder] in [accountId] did. */
    fun of(accountId: String, folder: String): Int = all + (folders[key(accountId, folder)] ?: 0)

    /** Changes whenever the list of [folder] in any of [accountIds] did. */
    fun of(accountIds: List<String>, folder: String): Int = all + accountIds.sumOf { folders[key(it, folder)] ?: 0 }

    internal fun bump(accountId: String, folder: String): ListVersions {
        val k = key(accountId, folder)
        return ListVersions(all, folders + (k to (folders[k] ?: 0) + 1))
    }

    internal fun bumpAll(): ListVersions = ListVersions(all + 1, folders)

    private fun key(accountId: String, folder: String) = accountId + "\n" + folder
}

/**
 * The message lists and folders last seen on your servers, kept on the phone so MailCtrl opens instantly and
 * shows something without a connection. Only headers (sender, subject, date, flags): the messages themselves
 * are fetched from the server when you open them.
 *
 * Sender, recipients and subject are encrypted per message with the data key ([dataKey], [SealedFile]): the
 * `subject` column then holds one sealed BLOB with all of them, and `from_name`, `from_email` and `to_json` are
 * NULL. A row whose `subject` is text is from before encryption (or was written while there was no key) and is
 * read as it is; such rows are sealed in the background ([sealOrdinaryRows]). The database itself, the account
 * ids, folder names, message numbers, dates, sizes and flags are not encrypted: the queries sort and filter on
 * them. Never use the sealed columns in SQL (WHERE, ORDER BY, an index).
 *
 * This is only a cache: a sealed row that can't be opened (the key is gone) is removed, and comes back from
 * the server with the next refresh.
 */
class MailCache(
    context: Context,
    private val dataKey: DataKeyStore,
    scope: CoroutineScope,
) : SQLiteOpenHelper(context, "mailcache.db", null, 3) {

    private val json = Json { ignoreUnknownKeys = true }
    private val addrList = ListSerializer(Address.serializer())

    init {
        // Write-ahead logging: a list on screen can be read while a refresh is writing, instead of waiting for it.
        // The lists stay in the same database; SQLite keeps `-wal` and `-shm` files next to it.
        setWriteAheadLoggingEnabled(true)
        // Lists from before encryption are sealed in the background, the first time the cache is used.
        scope.launch { runCatching { sealOrdinaryRows() } }
    }

    /** Goes up on every change, so lists on screen read again. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    // Changes come from several threads at once: count every one of them.
    private fun changed() { _version.update { it + 1 } }

    private val _lists = MutableStateFlow(ListVersions())
    /**
     * Goes up per folder when that folder's message list changes, so a list on screen only reads again when
     * its own folder changed (and not for every write to any other folder or account).
     */
    val listVersions: StateFlow<ListVersions> = _lists.asStateFlow()

    /** The message list of one folder changed. */
    private fun changed(accountId: String, folder: String) {
        _lists.update { it.bump(accountId, folder) }
        changed()
    }

    /** Message lists of many folders changed at once (an account or everything was cleared). */
    private fun changedEverywhere() {
        _lists.update { it.bumpAll() }
        changed()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                account TEXT NOT NULL, folder TEXT NOT NULL, uid INTEGER NOT NULL,
                from_name TEXT, from_email TEXT, to_json TEXT, subject TEXT, date INTEGER NOT NULL,
                seen INTEGER NOT NULL, flagged INTEGER NOT NULL, answered INTEGER NOT NULL, attach INTEGER NOT NULL,
                size INTEGER NOT NULL DEFAULT -1,
                PRIMARY KEY (account, folder, uid)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX messages_date ON messages(folder, date)")
        db.execSQL(
            """
            CREATE TABLE folders (
                account TEXT NOT NULL, full_name TEXT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL,
                depth INTEGER NOT NULL, unread INTEGER NOT NULL, total INTEGER NOT NULL, selectable INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (account, full_name)
            )
            """.trimIndent(),
        )
        // The server's numbering of each folder (UIDVALIDITY): while it stays the same, only changes are downloaded.
        db.execSQL(
            """
            CREATE TABLE folder_sync (
                account TEXT NOT NULL, folder TEXT NOT NULL, validity INTEGER NOT NULL,
                PRIMARY KEY (account, folder)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 2) {
            // Message sizes (for downloading ahead) arrive with the next refresh; the lists stay as they are.
            db.execSQL("ALTER TABLE messages ADD COLUMN size INTEGER NOT NULL DEFAULT -1")
            return
        }
        // Only a cache: start over.
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS folders")
        db.execSQL("DROP TABLE IF EXISTS folder_sync")
        onCreate(db)
    }

    // ------------------------------------------------------------------ messages

    /**
     * The data key, made the first time it's needed; null when there is none to be had (rows are then written
     * as before encryption). Asks the secure hardware the first time: only ever called on a background thread.
     */
    private fun key(): ByteArray? = runCatching { dataKey.get(create = true) }.getOrNull()

    /** Sender, recipients and subject sealed together, checked by opening them again; null when that fails. */
    private fun seal(key: ByteArray, plain: SealedHeader): ByteArray? = runCatching {
        SealedFile.sealChecked(key, json.encodeToString(SealedHeader.serializer(), plain).toByteArray(Charsets.UTF_8))
    }.getOrNull()

    private fun parseTo(stored: String?): List<Address> =
        stored?.let { runCatching { json.decodeFromString(addrList, it) }.getOrNull() }.orEmpty()

    /** The row as a header, or null for a sealed row that can't be opened (no key, or not this key). */
    private fun Cursor.toHeader(key: ByteArray?): MessageHeader? {
        val fromName: String?
        val fromEmail: String?
        val to: List<Address>
        val subject: String
        if (getType(6) == Cursor.FIELD_TYPE_BLOB) {
            if (key == null) return null
            val opened = runCatching {
                json.decodeFromString(SealedHeader.serializer(), String(SealedFile.open(key, getBlob(6)), Charsets.UTF_8))
            }.getOrNull() ?: return null
            fromName = opened.n
            fromEmail = opened.e
            to = opened.t
            subject = opened.s
        } else {
            // From before encryption, or written while there was no key.
            fromName = getString(3)
            fromEmail = getString(4)
            to = parseTo(getString(5))
            subject = getString(6).orEmpty()
        }
        return MessageHeader(
            accountId = getString(0),
            folder = getString(1),
            uid = getLong(2),
            from = fromEmail?.let { Address(fromName.orEmpty(), it) },
            to = to,
            subject = subject,
            date = getLong(7),
            seen = getInt(8) != 0,
            flagged = getInt(9) != 0,
            answered = getInt(10) != 0,
            hasAttachments = getInt(11) != 0,
            size = getLong(12),
        )
    }

    private val cols = "account, folder, uid, from_name, from_email, to_json, subject, date, seen, flagged, answered, attach, size"

    /** The row for [h]: sealed with [key], or as before encryption when there's no key (or sealing failed). */
    private fun values(h: MessageHeader, key: ByteArray?) = ContentValues().apply {
        put("account", h.accountId)
        put("folder", h.folder)
        put("uid", h.uid)
        val sealed = key?.let { seal(it, SealedHeader(h.from?.name, h.from?.email, h.to, h.subject)) }
        if (sealed != null) {
            putNull("from_name")
            putNull("from_email")
            putNull("to_json")
            put("subject", sealed)
        } else {
            put("from_name", h.from?.name)
            put("from_email", h.from?.email)
            put("to_json", json.encodeToString(addrList, h.to))
            put("subject", h.subject)
        }
        put("date", h.date)
        put("seen", if (h.seen) 1 else 0)
        put("flagged", if (h.flagged) 1 else 0)
        put("answered", if (h.answered) 1 else 0)
        put("attach", if (h.hasAttachments) 1 else 0)
        put("size", h.size)
    }

    private fun query(sql: String, args: Array<String>): List<MessageHeader> {
        val key = key()
        val unreadable = ArrayList<Triple<String, String, Long>>()
        val list = readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val h = c.toHeader(key)
                    if (h != null) add(h) else unreadable.add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
                }
            }
        }
        if (unreadable.isNotEmpty()) dropUnreadable(unreadable)
        return list
    }

    /**
     * Sealed rows that can't be opened are of no use: they're removed, so the next refresh of their folder
     * downloads those messages' details again (as it does for any message the list doesn't have). They were
     * already left out of what's shown, so the lists on screen don't need to read again.
     */
    private fun dropUnreadable(rows: List<Triple<String, String, Long>>) {
        runCatching {
            val db = writableDatabase
            db.beginTransaction()
            try {
                rows.forEach { (account, folder, uid) ->
                    db.delete(
                        "messages",
                        "account = ? AND folder = ? AND uid = ? AND typeof(subject) = 'blob'",
                        arrayOf(account, folder, uid.toString()),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Seals the rows that are still ordinary text (lists from before encryption, or written while there was no
     * key), a couple of hundred at a time, each batch in its own transaction: a row is either still ordinary or
     * sealed, never in between, and stopping halfway just leaves the rest for the next start. Nothing changes
     * in what the lists show. Afterwards the database file is rewritten once, so the old text doesn't linger
     * in its unused pages. Without a key it does nothing.
     */
    private suspend fun sealOrdinaryRows(): Unit = withContext(Dispatchers.IO) {
        val key = key() ?: return@withContext
        val db = writableDatabase
        var sealedAny = false
        while (true) {
            var done = 0
            db.beginTransaction()
            try {
                val rows = db.rawQuery(
                    "SELECT account, folder, uid, from_name, from_email, to_json, subject FROM messages WHERE typeof(subject) != 'blob' LIMIT $SEAL_BATCH",
                    null,
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                OrdinaryRow(
                                    account = c.getString(0),
                                    folder = c.getString(1),
                                    uid = c.getLong(2),
                                    header = SealedHeader(c.getString(3), c.getString(4), parseTo(c.getString(5)), c.getString(6).orEmpty()),
                                ),
                            )
                        }
                    }
                }
                for (r in rows) {
                    val sealed = seal(key, r.header) ?: continue
                    done += db.update(
                        "messages",
                        ContentValues().apply {
                            putNull("from_name")
                            putNull("from_email")
                            putNull("to_json")
                            put("subject", sealed)
                        },
                        "account = ? AND folder = ? AND uid = ? AND typeof(subject) != 'blob'",
                        arrayOf(r.account, r.folder, r.uid.toString()),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // Nothing left, or nothing more that can be sealed.
            if (done == 0) break
            sealedAny = true
        }
        if (sealedAny) runCatching { db.execSQL("VACUUM") }
    }

    private class OrdinaryRow(val account: String, val folder: String, val uid: Long, val header: SealedHeader)

    suspend fun messages(accountId: String, folder: String): List<MessageHeader> = withContext(Dispatchers.IO) {
        query("SELECT $cols FROM messages WHERE account = ? AND folder = ? ORDER BY uid DESC", arrayOf(accountId, folder))
    }

    /**
     * The part of a folder's list that is on screen: from the newest message down to [floorUid], or the newest
     * [first] messages when no floor was chosen yet (null). The lists only read (and decrypt) this part again
     * when the folder changes, however many older messages the phone has of it.
     */
    suspend fun page(accountId: String, folder: String, floorUid: Long?, first: Int): FolderPage = withContext(Dispatchers.IO) {
        val headers = if (floorUid == null) {
            query("SELECT $cols FROM messages WHERE account = ? AND folder = ? ORDER BY uid DESC LIMIT $first", arrayOf(accountId, folder))
        } else {
            query(
                "SELECT $cols FROM messages WHERE account = ? AND folder = ? AND uid >= ? ORDER BY uid DESC",
                arrayOf(accountId, folder, floorUid.toString()),
            )
        }
        val floor = floorUid ?: headers.lastOrNull()?.uid
        val db = readableDatabase
        val older = floor != null && db.rawQuery(
            "SELECT 1 FROM messages WHERE account = ? AND folder = ? AND uid < ? LIMIT 1",
            arrayOf(accountId, folder, floor.toString()),
        ).use { c -> c.moveToFirst() }
        val unread = db.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE account = ? AND folder = ? AND seen = 0",
            arrayOf(accountId, folder),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        FolderPage(headers, floor, older, unread)
    }

    /**
     * Where the list should end to show [count] more of the messages the phone already has below [floorUid]:
     * the number of the [count]th message below it, or 0 (everything) when there are fewer.
     */
    suspend fun floorBelow(accountId: String, folder: String, floorUid: Long, count: Int): Long = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT uid FROM messages WHERE account = ? AND folder = ? AND uid < ? ORDER BY uid DESC LIMIT 1 OFFSET ${count - 1}",
            arrayOf(accountId, folder, floorUid.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    /** The newest [limit] messages of a folder ([unreadOnly]: of its unread ones), newest first. */
    suspend fun newest(accountId: String, folder: String, limit: Int, unreadOnly: Boolean = false): List<MessageHeader> = withContext(Dispatchers.IO) {
        val unread = if (unreadOnly) " AND seen = 0" else ""
        query("SELECT $cols FROM messages WHERE account = ? AND folder = ?$unread ORDER BY uid DESC LIMIT $limit", arrayOf(accountId, folder))
    }

    /** The number of the oldest message the phone has of a folder, or null when it has none. */
    suspend fun oldestUid(accountId: String, folder: String): Long? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT MIN(uid) FROM messages WHERE account = ? AND folder = ?",
            arrayOf(accountId, folder),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }

    /** Every account's inbox together, newest first. */
    suspend fun unifiedInbox(accountIds: List<String>, limit: Int = 600): List<MessageHeader> = withContext(Dispatchers.IO) {
        if (accountIds.isEmpty()) return@withContext emptyList()
        val marks = accountIds.joinToString(",") { "?" }
        query("SELECT $cols FROM messages WHERE folder = 'INBOX' AND account IN ($marks) ORDER BY date DESC LIMIT $limit", accountIds.toTypedArray())
    }

    suspend fun header(accountId: String, folder: String, uid: Long): MessageHeader? = withContext(Dispatchers.IO) {
        query("SELECT $cols FROM messages WHERE account = ? AND folder = ? AND uid = ?", arrayOf(accountId, folder, uid.toString())).firstOrNull()
    }

    /**
     * The newest messages of a folder, fresh from the server: they replace everything cached from the oldest of
     * them up (so removed messages go too). Older messages loaded before stay.
     */
    suspend fun putNewest(accountId: String, folder: String, headers: List<MessageHeader>, complete: Boolean) = withContext(Dispatchers.IO) {
        val key = key()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val min = headers.minOfOrNull { it.uid }
            if (min == null || complete) {
                db.delete("messages", "account = ? AND folder = ?", arrayOf(accountId, folder))
            } else {
                db.delete("messages", "account = ? AND folder = ? AND uid >= ?", arrayOf(accountId, folder, min.toString()))
            }
            headers.forEach { db.insertWithOnConflict("messages", null, values(it, key), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed(accountId, folder)
    }

    /** Older messages ("Load more"), added below what's cached. */
    suspend fun putOlder(headers: List<MessageHeader>) = withContext(Dispatchers.IO) {
        if (headers.isEmpty()) return@withContext
        val key = key()
        val db = writableDatabase
        db.beginTransaction()
        try {
            headers.forEach { db.insertWithOnConflict("messages", null, values(it, key), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Usually one folder; each one's list reads again.
        headers.map { it.accountId to it.folder }.distinct().forEach { (a, f) -> _lists.update { it.bump(a, f) } }
        changed()
    }

    /** Shows a change straight away, before the server has answered. */
    suspend fun setFlags(accountId: String, folder: String, uids: Collection<Long>, seen: Boolean? = null, flagged: Boolean? = null, answered: Boolean? = null) =
        withContext(Dispatchers.IO) {
            if (uids.isEmpty()) return@withContext
            val v = ContentValues().apply {
                seen?.let { put("seen", if (it) 1 else 0) }
                flagged?.let { put("flagged", if (it) 1 else 0) }
                answered?.let { put("answered", if (it) 1 else 0) }
            }
            if (v.size() == 0) return@withContext
            val db = writableDatabase
            uids.chunked(400).forEach { chunk ->
                db.update("messages", v, "account = ? AND folder = ? AND uid IN (${chunk.joinToString(",")})", arrayOf(accountId, folder))
            }
            changed(accountId, folder)
        }

    suspend fun markFolderRead(accountId: String, folder: String) = withContext(Dispatchers.IO) {
        writableDatabase.update("messages", ContentValues().apply { put("seen", 1) }, "account = ? AND folder = ?", arrayOf(accountId, folder))
        changed(accountId, folder)
    }

    suspend fun remove(accountId: String, folder: String, uids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (uids.isEmpty()) return@withContext
        val db = writableDatabase
        uids.chunked(400).forEach { chunk ->
            db.delete("messages", "account = ? AND folder = ? AND uid IN (${chunk.joinToString(",")})", arrayOf(accountId, folder))
        }
        changed(accountId, folder)
    }

    suspend fun clearFolder(accountId: String, folder: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("messages", "account = ? AND folder = ?", arrayOf(accountId, folder))
        changed(accountId, folder)
    }

    // ------------------------------------------------------------------ syncing

    /** What the phone has of a folder: its numbering and the newest [limit] message numbers. Null the first time. */
    suspend fun known(accountId: String, folder: String, limit: Int): KnownRange? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val validity = db.rawQuery("SELECT validity FROM folder_sync WHERE account = ? AND folder = ?", arrayOf(accountId, folder))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@withContext null
        val uids = db.rawQuery(
            "SELECT uid FROM messages WHERE account = ? AND folder = ? ORDER BY uid DESC LIMIT $limit",
            arrayOf(accountId, folder),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        KnownRange(validity, uids)
    }

    /** Applies what changed on the server to a folder's list, all at once. */
    suspend fun applySync(accountId: String, folder: String, sync: FolderSync) = withContext(Dispatchers.IO) {
        val key = if (sync.fresh.isEmpty()) null else key()
        val db = writableDatabase
        var flagChanges = 0
        db.beginTransaction()
        try {
            if (sync.reset) db.delete("messages", "account = ? AND folder = ?", arrayOf(accountId, folder))
            sync.removed.chunked(400).forEach { chunk ->
                db.delete("messages", "account = ? AND folder = ? AND uid IN (${chunk.joinToString(",")})", arrayOf(accountId, folder))
            }
            sync.flags.forEach { (uid, f) ->
                val seen = if (f.seen) 1 else 0
                val flagged = if (f.flagged) 1 else 0
                val answered = if (f.answered) 1 else 0
                // Only rows whose flags really differ, so an unchanged folder changes nothing.
                flagChanges += db.update(
                    "messages",
                    ContentValues().apply {
                        put("seen", seen)
                        put("flagged", flagged)
                        put("answered", answered)
                    },
                    "account = ? AND folder = ? AND uid = ? AND (seen != ? OR flagged != ? OR answered != ?)",
                    arrayOf(accountId, folder, uid.toString(), seen.toString(), flagged.toString(), answered.toString()),
                )
                // The size, for downloading ahead (lists from before 0.2.3 don't have it yet). Not a visible change.
                if (f.size >= 0) {
                    db.update(
                        "messages",
                        ContentValues().apply { put("size", f.size) },
                        "account = ? AND folder = ? AND uid = ? AND size != ?",
                        arrayOf(accountId, folder, uid.toString(), f.size.toString()),
                    )
                }
            }
            sync.fresh.forEach { db.insertWithOnConflict("messages", null, values(it, key), SQLiteDatabase.CONFLICT_REPLACE) }
            db.insertWithOnConflict(
                "folder_sync",
                null,
                ContentValues().apply {
                    put("account", accountId)
                    put("folder", folder)
                    put("validity", sync.uidValidity)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Nothing changed (the usual case): the lists on screen don't need to read again.
        if (sync.reset || sync.removed.isNotEmpty() || sync.fresh.isNotEmpty() || flagChanges > 0) changed(accountId, folder)
    }

    // ------------------------------------------------------------------ folders

    suspend fun folders(accountId: String): List<FolderInfo> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT full_name, name, role, depth, unread, total, selectable FROM folders WHERE account = ? ORDER BY position",
            arrayOf(accountId),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        FolderInfo(
                            fullName = c.getString(0),
                            name = c.getString(1),
                            role = runCatching { FolderRole.valueOf(c.getString(2)) }.getOrDefault(FolderRole.OTHER),
                            depth = c.getInt(3),
                            unread = c.getInt(4),
                            total = c.getInt(5),
                            selectable = c.getInt(6) != 0,
                        ),
                    )
                }
            }
        }
    }

    suspend fun putFolders(accountId: String, list: List<FolderInfo>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("folders", "account = ?", arrayOf(accountId))
            list.forEachIndexed { i, f ->
                db.insertWithOnConflict(
                    "folders",
                    null,
                    ContentValues().apply {
                        put("account", accountId)
                        put("full_name", f.fullName)
                        put("name", f.name)
                        put("role", f.role.name)
                        put("depth", f.depth)
                        put("unread", f.unread)
                        put("total", f.total)
                        put("selectable", if (f.selectable) 1 else 0)
                        put("position", i)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    /** Unread messages in each account's inbox, as cached. */
    suspend fun inboxUnread(accountIds: List<String>): Int = withContext(Dispatchers.IO) {
        if (accountIds.isEmpty()) return@withContext 0
        val marks = accountIds.joinToString(",") { "?" }
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE folder = 'INBOX' AND seen = 0 AND account IN ($marks)",
            accountIds.toTypedArray(),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Unread messages in every account's inbox, as cached, in one query. Accounts without any aren't in the map. */
    suspend fun inboxUnreadByAccount(): Map<String, Int> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT account, COUNT(*) FROM messages WHERE folder = 'INBOX' AND seen = 0 GROUP BY account",
            null,
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }
    }

    // ------------------------------------------------------------------ accounts

    suspend fun clearAccount(accountId: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("messages", "account = ?", arrayOf(accountId))
        db.delete("folders", "account = ?", arrayOf(accountId))
        db.delete("folder_sync", "account = ?", arrayOf(accountId))
        changedEverywhere()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("messages", null, null)
        db.delete("folders", null, null)
        db.delete("folder_sync", null, null)
        changedEverywhere()
    }

    private companion object {
        /** Rows sealed per transaction when moving a list from before encryption over. */
        const val SEAL_BATCH = 200
    }
}
