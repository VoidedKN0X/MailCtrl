package com.vknxworks.mailctrl.data.local

import com.vknxworks.mailctrl.data.crypto.DataKeyStore
import com.vknxworks.mailctrl.data.crypto.SealedFile
import com.vknxworks.mailctrl.data.drafts.Draft
import com.vknxworks.mailctrl.mail.MessageHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** A message waiting on the phone to be sent: scheduled by you ("Send later"), or held for the Undo seconds. */
@Serializable
data class OutboxItem(
    /** The id of the draft it was written as. */
    val id: String,
    val accountId: String,
    /** What was written, to show it in the list and to put it back as a draft when you cancel. */
    val draft: Draft,
    /** When it's sent (epoch millis). */
    val sendAt: Long,
    val created: Long,
    /** Held for the Undo seconds, rather than scheduled by you. */
    val undo: Boolean = false,
    /** Why it couldn't be sent (it then waits for "Send now" or "Cancel"). */
    val error: String? = null,
)

/**
 * Messages waiting to be sent. Each is kept exactly as it will go out (already signed or encrypted, attachments
 * included), sealed with the data key ([SealedFile]) in its own file; the list of them is a [SealedJsonFile].
 * They're protected by the phone's secure hardware but not by the app PIN, so they can be sent while MailCtrl
 * is locked.
 *
 * A waiting message exists nowhere else, so nothing here deletes what it can't read: a message file that
 * can't be opened stays where it is and its entry gets an error, until you cancel it yourself.
 */
class Outbox(dir: File, private val key: DataKeyStore, scope: CoroutineScope) {
    private val root = File(dir, "outbox")
    private val index = SealedJsonFile(File(root, "outbox.bin"), key, ListSerializer(OutboxItem.serializer()), emptyList())
    private val mutex = Mutex()
    /** Being sent right now: can't be cancelled any more. */
    private val sending = HashSet<String>()

    private val _items = MutableStateFlow<List<OutboxItem>>(emptyList())
    val items: StateFlow<List<OutboxItem>> = _items.asStateFlow()

    /** The saved list can't be read on this phone right now; it's left alone and nothing can be scheduled. */
    val unreadable: Boolean get() = index.unreadable

    init {
        scope.launch { runCatching { load() } }
    }

    private fun file(id: String) = File(root, id.filter { it.isLetterOrDigit() || it == '-' }.take(80) + ".msg")

    private fun publish(list: List<OutboxItem>): List<OutboxItem> = list.sortedBy { it.sendAt }.also { _items.value = it }

    suspend fun load(): List<OutboxItem> = withContext(Dispatchers.IO) { mutex.withLock { publish(index.read()) } }

    /**
     * Keeps [raw] (the message as it will be sent) and lists [item]. False when it can't be kept encrypted on
     * the phone (no data key, or the saved list can't be read): then nothing was written.
     */
    suspend fun add(item: OutboxItem, raw: ByteArray): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            index.read()
            if (index.unreadable) return@withLock false
            val k = runCatching { key.get(create = true) }.getOrNull() ?: return@withLock false
            val sealed = SealedFile.sealChecked(k, raw) ?: return@withLock false
            val target = file(item.id)
            val written = runCatching {
                root.mkdirs()
                SealedFile.writeAtomic(target, File(target.path + ".tmp"), sealed)
            }.isSuccess
            if (!written) return@withLock false
            publish(index.update { list -> list.filterNot { it.id == item.id } + item })
            // Only counts when the list really has it now (it's saved in one step, or not at all).
            index.read().any { it.id == item.id }
        }
    }

    /**
     * Claims [id] for sending: its entry and the message itself (null when the file can't be read), or null when
     * it's gone or already being sent. Follow with [finish].
     */
    suspend fun begin(id: String): Pair<OutboxItem, ByteArray?>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val item = index.read().firstOrNull { it.id == id } ?: return@withLock null
            if (!sending.add(id)) return@withLock null
            val k = runCatching { key.get(create = false) }.getOrNull()
            val raw = if (k == null) null else runCatching { SealedFile.open(k, file(id).readBytes()) }.getOrNull()
            item to raw
        }
    }

    /** After [begin]: [sent] removes the message; otherwise it stays, with [error] when there is one. */
    suspend fun finish(id: String, sent: Boolean, error: String? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            sending.remove(id)
            if (sent) {
                publish(index.update { list -> list.filterNot { it.id == id } })
                runCatching { file(id).delete() }
            } else {
                publish(index.update { list -> list.map { if (it.id == id) it.copy(error = error) else it } })
            }
        }
    }

    /** A new time for a waiting message ("Send now"); its error is cleared. */
    suspend fun reschedule(id: String, sendAt: Long) = withContext(Dispatchers.IO) {
        mutex.withLock { publish(index.update { list -> list.map { if (it.id == id) it.copy(sendAt = sendAt, error = null) else it } }) }
    }

    /**
     * Takes [id] back (Undo, or Cancel): its entry, or null when it's already being sent or gone. The message
     * file is removed, because you asked for it.
     */
    suspend fun cancel(id: String): OutboxItem? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (id in sending) return@withLock null
            val item = index.read().firstOrNull { it.id == id } ?: return@withLock null
            publish(index.update { list -> list.filterNot { it.id == id } })
            // Still listed (the list couldn't be saved): the message stays too.
            if (index.read().any { it.id == id }) return@withLock null
            runCatching { file(id).delete() }
            item
        }
    }
}

/** A message hidden from the lists on this phone until [until]. */
@Serializable
data class Snoozed(
    val accountId: String,
    val folder: String,
    val uid: Long,
    val until: Long,
    val from: String = "",
    val subject: String = "",
) {
    val key: String get() = "$accountId/$folder/$uid"
}

/**
 * Snoozed messages: hidden from the lists on this phone until their time, then shown again (with a
 * notification). Nothing changes on the mail server, so other mail programs keep showing them. Kept encrypted
 * on the phone ([SealedJsonFile]).
 */
class SnoozeStore(dir: File, key: DataKeyStore, scope: CoroutineScope) {
    private val file = SealedJsonFile(File(dir, "snoozed.bin"), key, ListSerializer(Snoozed.serializer()), emptyList())
    private val _items = MutableStateFlow<List<Snoozed>>(emptyList())
    val items: StateFlow<List<Snoozed>> = _items.asStateFlow()
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /** The keys ([MessageHeader.key]) of messages the lists leave out right now. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { runCatching { publish(file.read()) } }
    }

    private fun publish(list: List<Snoozed>): List<Snoozed> {
        val sorted = list.sortedBy { it.until }
        _items.value = sorted
        _hidden.value = sorted.map { it.key }.toSet()
        return sorted
    }

    /** The list, read from storage (also when the read started at creation hasn't finished yet). */
    suspend fun load(): List<Snoozed> = withContext(Dispatchers.IO) { publish(file.read()) }

    suspend fun snooze(headers: Collection<MessageHeader>, until: Long) = withContext(Dispatchers.IO) {
        val keys = headers.map { it.key }.toSet()
        publish(
            file.update { list ->
                (list.filterNot { it.key in keys } + headers.map { h -> Snoozed(h.accountId, h.folder, h.uid, until, h.from?.display.orEmpty(), h.subject) }).takeLast(MAX)
            },
        )
    }

    /** Shows messages again now. */
    suspend fun wake(keys: Collection<String>) = withContext(Dispatchers.IO) {
        val set = keys.toSet()
        publish(file.update { list -> list.filterNot { it.key in set } })
    }

    /** The messages whose time has come, taken off the list (so they show again). */
    suspend fun takeDue(now: Long): List<Snoozed> = withContext(Dispatchers.IO) {
        val due = file.read().filter { it.until <= now }
        if (due.isNotEmpty()) publish(file.update { list -> list.filterNot { it.until <= now } })
        due
    }

    suspend fun forgetAccount(accountId: String) = withContext(Dispatchers.IO) {
        publish(file.update { list -> list.filterNot { it.accountId == accountId } })
    }

    private companion object {
        const val MAX = 500
    }
}

/** The ready-made times offered by "Send later" and "Snooze". No Android code, so it's tested on the computer. */
object LaterTimes {
    enum class Preset { LATER_TODAY, THIS_EVENING, TOMORROW_MORNING, NEXT_WEEK }

    /**
     * The presets that make sense at [now], with their times: in three hours (while that's still today before
     * 22:00), this evening at 18:00 (until 16:00), tomorrow at 8:00, and next Monday at 8:00.
     */
    fun presets(now: ZonedDateTime): List<Pair<Preset, ZonedDateTime>> = buildList {
        val inThree = now.plusHours(3).withSecond(0).withNano(0)
        if (inThree.toLocalDate() == now.toLocalDate() && inThree.hour < 22) add(Preset.LATER_TODAY to inThree)
        if (now.hour < 16) add(Preset.THIS_EVENING to at(now, 18))
        add(Preset.TOMORROW_MORNING to at(now.plusDays(1), 8))
        add(Preset.NEXT_WEEK to at(now.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), 8))
    }

    private fun at(day: ZonedDateTime, hour: Int): ZonedDateTime = day.withHour(hour).withMinute(0).withSecond(0).withNano(0)
}
