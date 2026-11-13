package com.vknxworks.mailctrl.data.drafts

import android.content.Context
import com.vknxworks.mailctrl.data.crypto.KeystoreCrypto
import com.vknxworks.mailctrl.data.lock.LockVault
import com.vknxworks.mailctrl.mail.Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** A message you started writing, kept on the phone until you send or delete it. */
@Serializable
data class Draft(
    val id: String = UUID.randomUUID().toString(),
    val accountId: String,
    val to: List<Address> = emptyList(),
    val cc: List<Address> = emptyList(),
    val bcc: List<Address> = emptyList(),
    val subject: String = "",
    val body: String = "",
    /** The message this answers, so a reply sent later still threads. */
    val inReplyTo: String? = null,
    val references: String? = null,
    /** The message answered, to mark it answered when this is sent. */
    val replyFolder: String? = null,
    val replyUid: Long? = null,
    /** Send it encrypted with OpenPGP. */
    val encrypt: Boolean = false,
    /** Sign it with your OpenPGP key. */
    val sign: Boolean = false,
    val updated: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean get() = to.isEmpty() && cc.isEmpty() && bcc.isEmpty() && subject.isBlank() && body.isBlank()
}

/**
 * Drafts, each encrypted with a key in the phone's secure hardware. With the app lock on they're also sealed
 * with the app PIN, so they can't be read while MailCtrl is locked.
 */
class DraftStore(context: Context, private val vault: LockVault) {
    private val prefs = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _drafts = MutableStateFlow<List<Draft>>(emptyList())
    /** The drafts that can be read now (all of them while unlocked), newest first. */
    val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()

    init {
        reload()
    }

    /** Reads every draft again (after unlocking, the sealed ones open). */
    fun reload() {
        _drafts.value = prefs.all.values.mapNotNull { v -> (v as? String)?.let(::decode) }.sortedByDescending { it.updated }
    }

    private fun decode(stored: String): Draft? {
        val plain = KeystoreCrypto.decrypt(stored) ?: return null
        val text = if (LockVault.isSealed(plain)) vault.open(plain) ?: return null else plain
        return runCatching { json.decodeFromString(Draft.serializer(), text) }.getOrNull()
    }

    private fun encode(d: Draft): String? {
        val text = json.encodeToString(Draft.serializer(), d)
        val inner = when {
            !vault.enabled -> text
            vault.isOpen -> vault.seal(text) ?: return null
            // Locked: it can't be sealed, and storing it plainly would break the promise.
            else -> return null
        }
        return KeystoreCrypto.encrypt(inner)
    }

    suspend fun save(d: Draft) = withContext(Dispatchers.IO) {
        if (d.isEmpty) {
            prefs.edit().remove(d.id).commit()
        } else {
            val stored = encode(d.copy(updated = System.currentTimeMillis())) ?: return@withContext
            prefs.edit().putString(d.id, stored).commit()
        }
        reload()
    }

    fun get(id: String): Draft? = _drafts.value.firstOrNull { it.id == id }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(id).commit()
        reload()
    }

    /** Drops the drafts of an account that was removed. */
    suspend fun deleteForAccount(accountId: String) = withContext(Dispatchers.IO) {
        val edit = prefs.edit()
        _drafts.value.filter { it.accountId == accountId }.forEach { edit.remove(it.id) }
        edit.commit()
        reload()
    }

    /** The app lock was turned on: seal every draft with the PIN. */
    suspend fun sealAll() = rewrite()

    /** The app lock is being turned off (the vault is still open): store drafts with the hardware key only. */
    suspend fun unsealAll() = withContext(Dispatchers.IO) {
        val edit = prefs.edit()
        prefs.all.forEach { (k, v) ->
            val plain = (v as? String)?.let(KeystoreCrypto::decrypt) ?: return@forEach
            if (LockVault.isSealed(plain)) vault.open(plain)?.let { edit.putString(k, KeystoreCrypto.encrypt(it)) }
        }
        edit.commit()
        reload()
    }

    private suspend fun rewrite() = withContext(Dispatchers.IO) {
        val edit = prefs.edit()
        prefs.all.forEach { (k, v) ->
            val d = (v as? String)?.let(::decode) ?: return@forEach
            encode(d)?.let { edit.putString(k, it) }
        }
        edit.commit()
        reload()
    }

    /** "Forgot PIN?": drafts sealed with the old PIN can't be opened any more. */
    suspend fun dropSealed() = withContext(Dispatchers.IO) {
        val edit = prefs.edit()
        prefs.all.forEach { (k, v) ->
            val plain = (v as? String)?.let(KeystoreCrypto::decrypt)
            if (plain == null || LockVault.isSealed(plain)) edit.remove(k)
        }
        edit.commit()
        reload()
    }
}
