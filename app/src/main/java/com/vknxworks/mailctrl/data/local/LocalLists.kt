package com.vknxworks.mailctrl.data.local

import com.vknxworks.mailctrl.data.crypto.DataKeyStore
import com.vknxworks.mailctrl.mail.Address
import com.vknxworks.mailctrl.mail.hasControlChars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * The senders whose pictures from the internet always load ("Always load pictures from this sender"). Nobody is
 * in it until you add them from a message. Kept encrypted on the phone ([SealedJsonFile]).
 */
class PictureSenders(dir: File, key: DataKeyStore, scope: CoroutineScope) {
    private val file = SealedJsonFile(File(dir, "picture_senders.bin"), key, ListSerializer(String.serializer()), emptyList())
    private val _senders = MutableStateFlow<List<String>>(emptyList())

    /** The addresses, lower case, in the order they were added. Empty until read from storage. */
    val senders: StateFlow<List<String>> = _senders.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { runCatching { _senders.value = file.read() } }
    }

    private fun clean(email: String) = email.trim().lowercase()

    /** Whether pictures of mail from [email] load by themselves (reads storage the first time). */
    suspend fun allowed(email: String?): Boolean {
        val e = email?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return false
        return withContext(Dispatchers.IO) { runCatching { file.read() }.getOrDefault(emptyList()).also { _senders.value = it } }.contains(e)
    }

    suspend fun set(email: String, always: Boolean) = withContext(Dispatchers.IO) {
        val e = clean(email)
        if (e.isEmpty()) return@withContext
        _senders.value = file.update { list -> if (always) (list - e + e).takeLast(MAX) else list - e }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { _senders.value = file.update { emptyList() } }

    private companion object {
        const val MAX = 2000
    }
}

/** Someone you have sent mail to: what recipient suggestions are made of. */
@Serializable
data class MailedAddress(
    val email: String,
    val name: String = "",
    /** How often you mailed them. */
    val count: Int = 1,
    /** When you last mailed them (epoch millis). */
    val last: Long = 0,
)

/** Picks and orders suggestions; no Android code, so it's tested on the computer. */
object RecipientSuggestions {
    /** What is being typed in an address field: the text after the last comma or semicolon. */
    fun token(field: String): String = field.substring(field.lastIndexOfAny(charArrayOf(',', ';')) + 1).trim()

    /** [field] with what's being typed replaced by [pick], ready for the next address. */
    fun complete(field: String, pick: MailedAddress): String {
        val cut = field.lastIndexOfAny(charArrayOf(',', ';')) + 1
        val before = field.substring(0, cut).trimEnd()
        val shown = if (pick.name.isBlank()) pick.email else "${pick.name.replace("\"", "")} <${pick.email}>"
        return (if (before.isEmpty()) "" else "$before ") + shown + ", "
    }

    /**
     * Up to [limit] of [known] that match what is typed: a name or an address that starts with it (or has a
     * word starting with it), the ones mailed most often first. Addresses already in the field are left out.
     */
    fun suggest(known: List<MailedAddress>, field: String, limit: Int = 4): List<MailedAddress> {
        val typed = token(field).lowercase()
        if (typed.isEmpty() || typed.contains('<') || typed.contains('>')) return emptyList()
        val already = field.lowercase()
        return known.asSequence()
            .filter { a ->
                val email = a.email.lowercase()
                if (email == typed || already.contains(email)) return@filter false
                email.startsWith(typed) || email.substringAfter('@').startsWith(typed) ||
                    a.name.lowercase().split(' ', '.', '-', '_').any { it.startsWith(typed) }
            }
            .sortedWith(compareByDescending<MailedAddress> { it.count }.thenByDescending { it.last })
            .take(limit)
            .toList()
    }

    /** [known] with [sent] added: counted once more, with the newest name, and at most [max] kept (the least used go). */
    fun record(known: List<MailedAddress>, sent: List<Address>, now: Long, max: Int = 500): List<MailedAddress> {
        val map = LinkedHashMap<String, MailedAddress>()
        known.forEach { map[it.email.lowercase()] = it }
        sent.forEach { a ->
            val email = a.email.trim()
            if (email.isEmpty() || email.hasControlChars() || a.name.hasControlChars()) return@forEach
            val k = email.lowercase()
            val old = map[k]
            map[k] = MailedAddress(email, a.name.trim().ifEmpty { old?.name.orEmpty() }, (old?.count ?: 0) + 1, now)
        }
        val all = map.values.toList()
        return if (all.size <= max) all else all.sortedWith(compareByDescending<MailedAddress> { it.last }.thenByDescending { it.count }).take(max)
    }
}

/**
 * The addresses you have sent mail to, for suggestions while you type a recipient. Only ever filled from mail
 * you send yourself: MailCtrl has no contacts permission and never reads the phone's contacts. Kept encrypted
 * on the phone ([SealedJsonFile]).
 */
class MailedAddresses(dir: File, key: DataKeyStore, scope: CoroutineScope) {
    private val file = SealedJsonFile(File(dir, "mailed_addresses.bin"), key, ListSerializer(MailedAddress.serializer()), emptyList())
    private val _known = MutableStateFlow<List<MailedAddress>>(emptyList())
    val known: StateFlow<List<MailedAddress>> = _known.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { runCatching { _known.value = file.read() } }
    }

    suspend fun record(sent: List<Address>) = withContext(Dispatchers.IO) {
        _known.value = file.update { RecipientSuggestions.record(it, sent, System.currentTimeMillis()) }
    }

    suspend fun forget(email: String) = withContext(Dispatchers.IO) {
        _known.value = file.update { list -> list.filterNot { it.email.equals(email, ignoreCase = true) } }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { _known.value = file.update { emptyList() } }
}
