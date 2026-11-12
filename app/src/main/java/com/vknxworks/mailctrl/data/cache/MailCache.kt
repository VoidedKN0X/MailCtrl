package com.vknxworks.mailctrl.data.cache

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vknxworks.mailctrl.mail.Address
import com.vknxworks.mailctrl.mail.FolderInfo
import com.vknxworks.mailctrl.mail.FolderRole
import com.vknxworks.mailctrl.mail.FolderSync
import com.vknxworks.mailctrl.mail.KnownRange
import com.vknxworks.mailctrl.mail.MessageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The message lists and folders last seen on your servers, kept on the phone so MailCtrl opens instantly and
 * shows something without a connection. Only headers (sender, subject, date, flags): the messages themselves
 * are fetched from the server when you open them.
 */
class MailCache(context: Context) : SQLiteOpenHelper(context, "mailcache.db", null, 2) {

    private val json = Json { ignoreUnknownKeys = true }
    private val addrList = ListSerializer(Address.serializer())

    /** Goes up on every change, so lists on screen read again. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    // Changes come from several threads at once: count every one of them.
    private fun changed() { _version.update { it + 1 } }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                account TEXT NOT NULL, folder TEXT NOT NULL, uid INTEGER NOT NULL,
                from_name TEXT, from_email TEXT, to_json TEXT, subject TEXT, date INTEGER NOT NULL,
                seen INTEGER NOT NULL, flagged INTEGER NOT NULL, answered INTEGER NOT NULL, attach INTEGER NOT NULL,
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
        // Only a cache: start over.
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS folders")
        db.execSQL("DROP TABLE IF EXISTS folder_sync")
        onCreate(db)
    }

    // ------------------------------------------------------------------ messages

    private fun Cursor.toHeader(): MessageHeader = MessageHeader(
        accountId = getString(0),
        folder = getString(1),
        uid = getLong(2),
        from = getString(4)?.let { Address(getString(3).orEmpty(), it) },
        to = getString(5)?.let { runCatching { json.decodeFromString(addrList, it) }.getOrNull() }.orEmpty(),
        subject = getString(6).orEmpty(),
        date = getLong(7),
        seen = getInt(8) != 0,
        flagged = getInt(9) != 0,
        answered = getInt(10) != 0,
        hasAttachments = getInt(11) != 0,
    )

    private val cols = "account, folder, uid, from_name, from_email, to_json, subject, date, seen, flagged, answered, attach"

    private fun values(h: MessageHeader) = ContentValues().apply {
        put("account", h.accountId)
        put("folder", h.folder)
        put("uid", h.uid)
        put("from_name", h.from?.name)
        put("from_email", h.from?.email)
        put("to_json", json.encodeToString(addrList, h.to))
        put("subject", h.subject)
        put("date", h.date)
        put("seen", if (h.seen) 1 else 0)
        put("flagged", if (h.flagged) 1 else 0)
        put("answered", if (h.answered) 1 else 0)
        put("attach", if (h.hasAttachments) 1 else 0)
    }

    private fun query(sql: String, args: Array<String>): List<MessageHeader> =
        readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toHeader()) } }

    suspend fun messages(accountId: String, folder: String): List<MessageHeader> = withContext(Dispatchers.IO) {
        query("SELECT $cols FROM messages WHERE account = ? AND folder = ? ORDER BY uid DESC", arrayOf(accountId, folder))
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
        val db = writableDatabase
        db.beginTransaction()
        try {
            val min = headers.minOfOrNull { it.uid }
            if (min == null || complete) {
                db.delete("messages", "account = ? AND folder = ?", arrayOf(accountId, folder))
            } else {
                db.delete("messages", "account = ? AND folder = ? AND uid >= ?", arrayOf(accountId, folder, min.toString()))
            }
            headers.forEach { db.insertWithOnConflict("messages", null, values(it), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    /** Older messages ("Load more"), added below what's cached. */
    suspend fun putOlder(headers: List<MessageHeader>) = withContext(Dispatchers.IO) {
        if (headers.isEmpty()) return@withContext
        val db = writableDatabase
        db.beginTransaction()
        try {
            headers.forEach { db.insertWithOnConflict("messages", null, values(it), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
            changed()
        }

    suspend fun markFolderRead(accountId: String, folder: String) = withContext(Dispatchers.IO) {
        writableDatabase.update("messages", ContentValues().apply { put("seen", 1) }, "account = ? AND folder = ?", arrayOf(accountId, folder))
        changed()
    }

    suspend fun remove(accountId: String, folder: String, uids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (uids.isEmpty()) return@withContext
        val db = writableDatabase
        uids.chunked(400).forEach { chunk ->
            db.delete("messages", "account = ? AND folder = ? AND uid IN (${chunk.joinToString(",")})", arrayOf(accountId, folder))
        }
        changed()
    }

    suspend fun clearFolder(accountId: String, folder: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("messages", "account = ? AND folder = ?", arrayOf(accountId, folder))
        changed()
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
            }
            sync.fresh.forEach { db.insertWithOnConflict("messages", null, values(it), SQLiteDatabase.CONFLICT_REPLACE) }
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
        if (sync.reset || sync.removed.isNotEmpty() || sync.fresh.isNotEmpty() || flagChanges > 0) changed()
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

    // ------------------------------------------------------------------ accounts

    suspend fun clearAccount(accountId: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("messages", "account = ?", arrayOf(accountId))
        db.delete("folders", "account = ?", arrayOf(accountId))
        db.delete("folder_sync", "account = ?", arrayOf(accountId))
        changed()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("messages", null, null)
        db.delete("folders", null, null)
        db.delete("folder_sync", null, null)
        changed()
    }
}
