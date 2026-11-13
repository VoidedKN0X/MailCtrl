package com.vknxworks.mailctrl.data.pgp

import android.os.SystemClock
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.data.drafts.Draft
import com.vknxworks.mailctrl.data.settings.SettingsRepository
import com.vknxworks.mailctrl.mail.MailClient
import com.vknxworks.mailctrl.mail.MailException
import com.vknxworks.mailctrl.mail.MessageBody
import com.vknxworks.mailctrl.mail.PgpPayload
import com.vknxworks.mailctrl.pgp.Armor
import com.vknxworks.mailctrl.pgp.Autocrypt
import com.vknxworks.mailctrl.pgp.KeyInfo
import com.vknxworks.mailctrl.pgp.KeyType
import com.vknxworks.mailctrl.pgp.PgpEngine
import com.vknxworks.mailctrl.pgp.PgpFailure
import com.vknxworks.mailctrl.pgp.PgpOutgoing
import com.vknxworks.mailctrl.pgp.SigCheck
import com.vknxworks.mailctrl.pgp.UnlockedKey
import com.vknxworks.mailctrl.pgp.Wkd
import com.vknxworks.mailctrl.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.openpgp.PGPKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRing
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/** A message as shown after OpenPGP: its (decrypted) content and what MailCtrl found out. */
data class PgpView(
    val body: MessageBody,
    /** It was encrypted (whether or not it could be decrypted). */
    val encrypted: Boolean = false,
    val signature: SigCheck? = null,
    /** It's encrypted to this key of yours, whose passphrase is needed. */
    val locked: KeyInfo? = null,
    /** Why it couldn't be decrypted or checked, for people. */
    val error: String? = null,
)

/** A public key found in a message (Autocrypt) or elsewhere, offered for import. */
class KeyOffer(val info: KeyInfo, val ring: PGPPublicKeyRing, /** You already have a different key for this address. */ val replaces: Boolean)

/** A failed OpenPGP step, said for people. */
fun PgpFailure.friendly(): String = str(
    when (kind) {
        PgpFailure.Kind.NOT_PGP -> R.string.pgp_error_not_pgp
        PgpFailure.Kind.NO_SECRET_KEY -> R.string.pgp_error_no_secret_key
        PgpFailure.Kind.BROKEN -> R.string.pgp_error_broken
        PgpFailure.Kind.NOT_PROTECTED -> R.string.pgp_error_not_protected
        PgpFailure.Kind.TOO_BIG -> R.string.pgp_error_too_big
        PgpFailure.Kind.NO_ENCRYPTION_KEY -> R.string.pgp_error_no_encryption_key
        PgpFailure.Kind.NO_SIGNING_KEY -> R.string.pgp_error_no_signing_key
    },
)

/**
 * OpenPGP for the app: reading encrypted and signed mail, making what's sent, and remembering key passphrases for
 * as long as Settings says (in memory only, and never after MailCtrl locks).
 */
class PgpService(
    private val keys: PgpKeyStore,
    private val settings: SettingsRepository,
    private val mail: () -> MailClient,
) {
    private class Remembered(val key: UnlockedKey, val until: Long)

    private val unlocked = ConcurrentHashMap<String, Remembered>()

    // ------------------------------------------------------------------ passphrases

    /** The key, open, when its passphrase is remembered (or it has none). */
    fun unlockedKey(fingerprint: String): UnlockedKey? {
        unlocked[fingerprint]?.let { r ->
            if (SystemClock.elapsedRealtime() < r.until) return r.key
            unlocked.remove(fingerprint, r)
        }
        val ring = keys.get(fingerprint)?.secret ?: return null
        if (PgpEngine.needsPassphrase(ring)) return null
        return runCatching { PgpEngine.unlock(ring, CharArray(0)) }.getOrNull()
    }

    /** Whether the key can be used without asking for its passphrase now. */
    fun isOpen(fingerprint: String): Boolean = unlockedKey(fingerprint) != null

    /**
     * Opens the key with [passphrase] and remembers it for as long as Settings says. The passphrase itself is
     * wiped once used. Throws [WrongPassphraseException].
     */
    suspend fun unlock(fingerprint: String, passphrase: CharArray): UnlockedKey = withContext(Dispatchers.Default) {
        keys.load()
        val ring = keys.get(fingerprint)?.secret ?: throw PgpFailure(PgpFailure.Kind.NO_SECRET_KEY)
        try {
            val key = PgpEngine.unlock(ring, passphrase)
            val keep = settings.current().pgpPassphrase.millis
            when {
                keep < 0 -> unlocked[fingerprint] = Remembered(key, Long.MAX_VALUE)
                keep > 0 -> unlocked[fingerprint] = Remembered(key, SystemClock.elapsedRealtime() + keep)
            }
            key
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /** Forgets every remembered passphrase (MailCtrl locked, or you changed the setting). */
    fun forget() = unlocked.clear()

    // ------------------------------------------------------------------ reading

    /** Decrypts and checks [body] as far as it can. [key]: a key opened just now for this message. */
    suspend fun read(body: MessageBody, key: UnlockedKey? = null): PgpView = withContext(Dispatchers.Default) {
        keys.load()
        try {
            when (val p = body.pgp) {
                null -> PgpView(body)
                is PgpPayload.Signed -> PgpView(body, signature = check { PgpEngine.verifyDetached(p.content, p.signature, keys.lookup) })
                is PgpPayload.InlineSigned -> {
                    val charset = runCatching { Charset.forName(p.charset) }.getOrDefault(Charsets.UTF_8)
                    val c = PgpEngine.parseCleartext(p.text, charset) ?: return@withContext PgpView(body)
                    PgpView(body.copy(text = c.shown, pgp = null), signature = check { PgpEngine.verifyCleartext(c, keys.lookup) })
                }
                is PgpPayload.Encrypted -> decrypt(body, p.data, key) { dec ->
                    val (inner, signed) = mail().decryptedBody(body, dec.data)
                    inner to (dec.signature ?: signed?.let { s -> check { PgpEngine.verifyDetached(s.content, s.signature, keys.lookup) } })
                }
                is PgpPayload.InlineEncrypted -> {
                    val block = INLINE_BLOCK.find(p.text)?.value ?: return@withContext PgpView(body)
                    val hidden = body.copy(text = p.text.replace(block, "").trim().ifEmpty { null })
                    decrypt(hidden, block.toByteArray(Charsets.US_ASCII), key) { dec ->
                        val clear = String(dec.data, Charsets.UTF_8)
                        // Signed in cleartext, then encrypted: check that signature too.
                        val inner = PgpEngine.parseCleartext(clear)
                        val text = p.text.replace(block, inner?.shown ?: clear)
                        val sig = dec.signature ?: inner?.let { c -> check { PgpEngine.verifyCleartext(c, keys.lookup) } }
                        body.copy(text = text, html = null, pgp = null, decrypted = true) to sig
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PgpFailure) {
            PgpView(body.copy(pgp = null), encrypted = body.pgp is PgpPayload.Encrypted || body.pgp is PgpPayload.InlineEncrypted, error = e.friendly())
        }
    }

    /** A signature check that never fails the whole message: a broken signature reads as "bad". */
    private inline fun check(block: () -> SigCheck): SigCheck = try {
        block()
    } catch (e: PgpFailure) {
        SigCheck(com.vknxworks.mailctrl.pgp.SigStatus.BAD, 0L)
    }

    private suspend fun decrypt(
        body: MessageBody,
        data: ByteArray,
        key: UnlockedKey?,
        show: (com.vknxworks.mailctrl.pgp.Decrypted) -> Pair<MessageBody, SigCheck?>,
    ): PgpView {
        val ids = PgpEngine.recipients(data)
        val mine = keys.secretKeys().filter { k -> 0L in ids || ids.any { id -> k.ring.getPublicKey(id) != null } }
        val locked = body.copy(text = if (body.pgp is PgpPayload.Encrypted) null else body.text, html = null, attachments = emptyList(), inlineImages = emptyMap(), hasRemoteContent = false)
        if (mine.isEmpty()) return PgpView(locked, encrypted = true, error = str(R.string.pgp_error_no_secret_key))
        val open = (listOfNotNull(key) + mine.mapNotNull { unlockedKey(it.info.fingerprint) }).distinctBy { it.fingerprint }
        if (open.isEmpty()) return PgpView(locked, encrypted = true, locked = mine.first().info)
        val dec = PgpEngine.decrypt(data, open, keys.lookup)
        val (shown, sig) = show(dec)
        return PgpView(shown, encrypted = true, signature = sig)
    }

    /** The sender's key from their Autocrypt header, when it's for their address and isn't on the phone yet. */
    suspend fun autocryptOffer(body: MessageBody): KeyOffer? = withContext(Dispatchers.Default) {
        val header = body.autocrypt?.let(Autocrypt::parse) ?: return@withContext null
        val from = body.header.from?.email?.trim()?.lowercase() ?: return@withContext null
        if (header.addr != from) return@withContext null
        keys.load()
        val ring = runCatching { PgpEngine.readKeys(header.keyData).firstOrNull()?.let(PgpEngine::publicOf) }.getOrNull() ?: return@withContext null
        val info = PgpEngine.info(ring)
        if (from !in info.emails || keys.get(info.fingerprint) != null) return@withContext null
        KeyOffer(info, ring, replaces = keys.anyForEmail(from).isNotEmpty())
    }

    // ------------------------------------------------------------------ keys

    /** The keys in a file or attachment, without adding them. Throws [PgpFailure] when there are none. */
    suspend fun preview(bytes: ByteArray): List<PGPKeyRing> = withContext(Dispatchers.Default) {
        PgpEngine.readKeys(bytes).ifEmpty { throw PgpFailure(PgpFailure.Kind.NOT_PGP) }
    }

    suspend fun import(rings: List<PGPKeyRing>): List<KeyInfo> = keys.import(rings)

    /** Makes a key for [account] and, when the account has none yet, makes it the account's key. */
    suspend fun generate(name: String, email: String, passphrase: CharArray, type: KeyType, validSeconds: Long): KeyInfo =
        withContext(Dispatchers.Default) {
            try {
                val ring = PgpEngine.generate(name, email, passphrase, type, validSeconds)
                keys.import(listOf(ring)).first()
            } finally {
                passphrase.fill('\u0000')
            }
        }

    /** The public key, armored, to share or save as a .asc file. */
    fun exportPublic(fingerprint: String): String? = keys.get(fingerprint)?.let { Armor.armor(it.public.encoded) }

    suspend fun delete(fingerprint: String) {
        unlocked.remove(fingerprint)
        keys.delete(fingerprint)
    }

    /** Asks [email]'s own domain for their key (WKD). Network: only when you tap it. */
    suspend fun lookup(email: String): List<PGPKeyRing> = withContext(Dispatchers.IO) { Wkd.lookup(email) }

    // ------------------------------------------------------------------ sending

    /** Recipients of [draft] with no key to encrypt to. */
    fun missingKeys(draft: Draft): List<String> =
        (draft.to + draft.cc + draft.bcc).map { it.email.trim().lowercase() }.distinct().filter { keys.forEmail(it).isEmpty() }

    /**
     * What [draft] needs to be sent encrypted and/or signed, or null for a plain message. [signer]: your key, opened
     * just now (when the passphrase isn't remembered).
     */
    fun outgoing(account: Account, draft: Draft, signer: UnlockedKey?): PgpOutgoing? {
        if (!draft.encrypt && !draft.sign) return null
        val own = account.pgpKey?.let(keys::get)?.takeIf { it.info.secret } ?: throw MailException(str(R.string.pgp_error_no_own_key))
        val sig = if (draft.sign) signer ?: unlockedKey(own.info.fingerprint) ?: throw MailException(str(R.string.pgp_error_key_locked)) else null
        val to = if (draft.encrypt) {
            val missing = missingKeys(draft)
            if (missing.isNotEmpty()) throw MailException(str(R.string.pgp_error_missing_keys, missing.joinToString(", ")))
            val theirs = (draft.to + draft.cc + draft.bcc).map { it.email.trim().lowercase() }.distinct().map { email ->
                keys.forEmail(email).firstNotNullOfOrNull { PgpEngine.encryptionKey(it.ring) }
                    ?: throw MailException(str(R.string.pgp_error_missing_keys, email))
            }
            // Encrypted to yourself too, so you can read it in Sent.
            val mine = PgpEngine.encryptionKey(own.ring) ?: throw MailException(str(R.string.pgp_error_own_key_unusable))
            theirs + mine
        } else {
            emptyList()
        }
        return PgpOutgoing(to, sig)
    }

    /** The Autocrypt header for [account], when it's turned on and the account has a key. */
    fun autocryptHeader(account: Account): String? {
        if (!account.autocrypt) return null
        val own = account.pgpKey?.let(keys::get) ?: return null
        return Autocrypt.build(account.email.trim().lowercase(), own.public.encoded)
    }

    private companion object {
        val INLINE_BLOCK = Regex("(?m)^[ \\t]*-----BEGIN PGP MESSAGE-----[\\s\\S]*?-----END PGP MESSAGE-----")
    }
}
