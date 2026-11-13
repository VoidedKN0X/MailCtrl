package com.vknxworks.mailctrl.data.pgp

import android.content.Context
import android.util.Base64
import com.vknxworks.mailctrl.data.crypto.KeystoreCrypto
import com.vknxworks.mailctrl.pgp.CertLookup
import com.vknxworks.mailctrl.pgp.KeyInfo
import com.vknxworks.mailctrl.pgp.PgpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.openpgp.PGPKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing

/** A key on the phone: what it is, and the key itself. */
class StoredKey(val info: KeyInfo, val ring: PGPKeyRing) {
    val secret: PGPSecretKeyRing? get() = ring as? PGPSecretKeyRing
    val public: PGPPublicKeyRing get() = PgpEngine.publicOf(ring)
}

/**
 * Your OpenPGP keys and your contacts' public keys, kept on the phone. Every key is encrypted with the key in the
 * phone's secure hardware (like passwords and drafts); secret keys are also still protected by their own
 * passphrase inside that.
 */
class PgpKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("pgp_keys", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _keys = MutableStateFlow<List<StoredKey>>(emptyList())
    /** Every key, your own first. Empty until [load] has run. */
    val keys: StateFlow<List<StoredKey>> = _keys.asStateFlow()

    @Volatile
    private var loaded = false

    /** Reads the keys from storage, the first time it's called. Disk and decryption work: off the main thread. */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        mutex.withLock { if (!loaded) reload() }
    }

    private fun reload() {
        _keys.value = prefs.all.values.mapNotNull { v -> (v as? String)?.let(::decode) }
            .sortedWith(compareBy<StoredKey>({ !it.info.secret }, { it.info.primaryUserId.lowercase() }))
        loaded = true
    }

    private fun decode(stored: String): StoredKey? = runCatching {
        val plain = KeystoreCrypto.decrypt(stored) ?: return null
        val ring = PgpEngine.readKeys(Base64.decode(plain, Base64.NO_WRAP)).firstOrNull() ?: return null
        StoredKey(PgpEngine.info(ring), ring)
    }.getOrNull()

    private fun encode(ring: PGPKeyRing): String = KeystoreCrypto.encrypt(Base64.encodeToString(ring.encoded, Base64.NO_WRAP))

    fun get(fingerprint: String): StoredKey? = _keys.value.firstOrNull { it.info.fingerprint == fingerprint }

    /** The key (yours or a contact's) that has [keyId] as its primary key or one of its subkeys. */
    fun byKeyId(keyId: Long): StoredKey? = _keys.value.firstOrNull { it.ring.getPublicKey(keyId) != null }

    /** Looks up public keys for checking signatures. */
    val lookup = CertLookup { id -> byKeyId(id)?.public }

    fun secretKeys(): List<StoredKey> = _keys.value.filter { it.info.secret }

    /** Keys for [email] that can be encrypted to right now, newest first. */
    fun forEmail(email: String): List<StoredKey> {
        val e = email.trim().lowercase()
        return _keys.value.filter { e in it.info.emails && it.info.canEncrypt }.sortedByDescending { it.info.created }
    }

    /** Keys for [email], usable or not (to tell "no key" from "an expired key"). */
    fun anyForEmail(email: String): List<StoredKey> {
        val e = email.trim().lowercase()
        return _keys.value.filter { e in it.info.emails }
    }

    /**
     * Adds keys. A secret key replaces the public copy of itself; a public key is merged into the copy that's
     * there (picking up a new expiry date or subkey), and leaves your own secret keys alone.
     */
    suspend fun import(rings: List<PGPKeyRing>): List<KeyInfo> = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            val edit = prefs.edit()
            val done = mutableListOf<KeyInfo>()
            for (ring in rings) {
                val fp = PgpEngine.fingerprintOf(ring.publicKey)
                val existing = _keys.value.firstOrNull { it.info.fingerprint == fp }
                val result: PGPKeyRing = when {
                    ring is PGPSecretKeyRing -> ring
                    existing?.secret != null -> existing.ring
                    existing != null -> PgpEngine.merge(existing.public, ring as PGPPublicKeyRing)
                    else -> ring
                }
                edit.putString(fp, encode(result))
                done += PgpEngine.info(result)
            }
            edit.commit()
            reload()
            done
        }
    }

    suspend fun delete(fingerprint: String) = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            prefs.edit().remove(fingerprint).commit()
            reload()
        }
    }
}
