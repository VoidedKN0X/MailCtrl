package com.vknxworks.mailctrl.data.smime

import android.content.Context
import android.util.Base64
import com.vknxworks.mailctrl.data.crypto.KeystoreCrypto
import com.vknxworks.mailctrl.smime.CertInfo
import com.vknxworks.mailctrl.smime.SmimeEngine
import com.vknxworks.mailctrl.smime.SmimeIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.Certificate

/** A certificate on the phone: yours (with its private key and chain) or a contact's. */
class StoredCert(val info: CertInfo, val cert: Certificate, val identity: SmimeIdentity?) {
    val own: Boolean get() = identity != null
}

/**
 * Your S/MIME certificates (with their private keys) and your contacts' certificates, kept on the phone. Every
 * entry is encrypted with the key in the phone's secure hardware, like OpenPGP keys, passwords and drafts. Your
 * private key is kept without the .p12 file's password, so it can sign and decrypt without asking each time.
 */
class SmimeStore(context: Context) {
    private val prefs = context.getSharedPreferences("smime_certs", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _certs = MutableStateFlow<List<StoredCert>>(emptyList())
    /** Every certificate, your own first. Empty until [load] has run. */
    val certs: StateFlow<List<StoredCert>> = _certs.asStateFlow()

    @Volatile
    private var loaded = false

    /** Reads the certificates from storage, the first time it's called. Disk and decryption work: off the main thread. */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        mutex.withLock { if (!loaded) reload() }
    }

    private fun reload() {
        _certs.value = prefs.all.values.mapNotNull { v -> (v as? String)?.let(::decode) }
            .sortedWith(compareBy<StoredCert>({ !it.own }, { it.info.name.lowercase() }))
        loaded = true
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(stored: String): StoredCert? = runCatching {
        val lines = (KeystoreCrypto.decrypt(stored) ?: return null).split('\n').filter { it.isNotBlank() }
        when (lines.firstOrNull()) {
            OWN -> {
                val info = PrivateKeyInfo.getInstance(Base64.decode(lines[1], Base64.NO_WRAP))
                val chain = lines.drop(2).map { SmimeEngine.readCert(Base64.decode(it, Base64.NO_WRAP)) }
                val id = SmimeIdentity(SmimeEngine.keyFrom(info), info, chain)
                StoredCert(SmimeEngine.info(id.cert), id.cert, id)
            }
            CONTACT -> {
                val cert = SmimeEngine.readCert(Base64.decode(lines[1], Base64.NO_WRAP))
                StoredCert(SmimeEngine.info(cert), cert, null)
            }
            else -> null
        }
    }.getOrNull()

    fun get(fingerprint: String): StoredCert? = _certs.value.firstOrNull { it.info.fingerprint == fingerprint }

    fun identities(): List<SmimeIdentity> = _certs.value.mapNotNull { it.identity }

    /** Certificates for [email] that can be encrypted to right now, the one that lasts longest first. */
    fun forEmail(email: String): List<StoredCert> {
        val e = email.trim().lowercase()
        return _certs.value.filter { e in it.info.emails && it.info.canEncrypt && !it.info.expired() }.sortedByDescending { it.info.notAfter }
    }

    /** Adds (or replaces) your certificate with its private key. */
    suspend fun importOwn(id: SmimeIdentity): CertInfo = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            val info = SmimeEngine.info(id.cert)
            val text = (listOf(OWN, b64(id.keyInfo.encoded)) + id.chain.map { b64(it.encoded) }).joinToString("\n")
            prefs.edit().putString(info.fingerprint, KeystoreCrypto.encrypt(text)).commit()
            reload()
            info
        }
    }

    /** Adds contacts' certificates. One that's already on the phone (yours included) is left as it is. */
    suspend fun importContacts(certs: List<Certificate>): List<CertInfo> = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            val edit = prefs.edit()
            val added = mutableListOf<CertInfo>()
            for (cert in certs) {
                val info = SmimeEngine.info(cert)
                if (_certs.value.any { it.info.fingerprint == info.fingerprint } || added.any { it.fingerprint == info.fingerprint }) continue
                edit.putString(info.fingerprint, KeystoreCrypto.encrypt(listOf(CONTACT, b64(cert.encoded)).joinToString("\n")))
                added += info
            }
            edit.commit()
            reload()
            added
        }
    }

    suspend fun delete(fingerprint: String) = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            prefs.edit().remove(fingerprint).commit()
            reload()
        }
    }

    private companion object {
        const val OWN = "own"
        const val CONTACT = "contact"
    }
}
