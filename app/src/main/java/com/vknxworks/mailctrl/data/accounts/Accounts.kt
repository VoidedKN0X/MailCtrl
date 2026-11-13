package com.vknxworks.mailctrl.data.accounts

import android.content.Context
import com.vknxworks.mailctrl.data.crypto.KeystoreCrypto
import com.vknxworks.mailctrl.data.lock.LockVault
import com.vknxworks.mailctrl.mail.Security
import com.vknxworks.mailctrl.mail.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** One mail account: who you are, where your mail lives, and how to sign in. */
@Serializable
data class Account(
    val id: String = UUID.randomUUID().toString(),
    /** Your address, used as the sender. */
    val email: String,
    /** The name people see next to your address. */
    val senderName: String = "",
    /** Your own name for the account in MailCtrl (optional). */
    val label: String = "",
    val imap: ServerConfig,
    val smtp: ServerConfig,
    /**
     * The password, encrypted with a key in the phone's secure hardware. With the app lock on and
     * notifications off it's also sealed with the app PIN inside that, so it only opens while unlocked.
     */
    val secretEnc: String = "",
    /** SHA-256 fingerprints of server certificates you chose to trust (self-signed, private CA). */
    val trustedCerts: List<String> = emptyList(),
    /**
     * Put a copy of sent mail in the Sent folder. Off for servers that already do it themselves
     * (otherwise every message would show up twice).
     */
    val saveSent: Boolean = true,
    val signature: String = "",
    /** Check this account for new mail and notify. */
    val notify: Boolean = true,
    /** Folders found on the server for these jobs (full names), so actions work without asking again. */
    val sentFolder: String? = null,
    val draftsFolder: String? = null,
    val trashFolder: String? = null,
    val archiveFolder: String? = null,
    val junkFolder: String? = null,
) {
    val title: String get() = label.trim().ifEmpty { email }

    /** Mail travels unencrypted to and from at least one of the servers. */
    val unencrypted: Boolean get() = imap.security == Security.NONE || smtp.security == Security.NONE
}

/** Server settings MailCtrl knows for an address, or a best guess. */
data class Preset(
    val imap: ServerConfig,
    val smtp: ServerConfig,
    val saveSent: Boolean,
    /** The server address can't be known (it's on your own network): ask for it. */
    val needsHost: Boolean = false,
    /** A Proton Mail address: it's read through hydroxide or Proton Mail Bridge on your own network. */
    val proton: Boolean = false,
    /** Found in MailCtrl's list rather than guessed from the domain. */
    val known: Boolean = false,
)

object Presets {
    private data class Known(
        val imapHost: String,
        val imapPort: Int,
        val imapSec: Security,
        val smtpHost: String,
        val smtpPort: Int,
        val smtpSec: Security,
        val saveSent: Boolean,
    )

    private val ssl = Security.SSL
    private val tls = Security.STARTTLS

    // Providers that need an app password (or a mail password) for IMAP. The server address is all MailCtrl
    // needs to know; nothing is looked up online. saveSent is false where the server files sent mail itself.
    private val known: Map<String, Known> = buildMap {
        val gm = Known("imap.gmail.com", 993, ssl, "smtp.gmail.com", 465, ssl, saveSent = false)
        put("gmail.com", gm)
        put("googlemail.com", gm)
        val yahoo = Known("imap.mail.yahoo.com", 993, ssl, "smtp.mail.yahoo.com", 465, ssl, saveSent = true)
        listOf("yahoo.com", "yahoo.co.uk", "yahoo.fr", "yahoo.de", "ymail.com", "rocketmail.com").forEach { put(it, yahoo) }
        val icloud = Known("imap.mail.me.com", 993, ssl, "smtp.mail.me.com", 587, tls, saveSent = true)
        listOf("icloud.com", "me.com", "mac.com").forEach { put(it, icloud) }
        val fastmail = Known("imap.fastmail.com", 993, ssl, "smtp.fastmail.com", 465, ssl, saveSent = true)
        listOf("fastmail.com", "fastmail.fm").forEach { put(it, fastmail) }
        put("posteo.de", Known("posteo.de", 993, ssl, "posteo.de", 465, ssl, saveSent = true))
        put("posteo.net", Known("posteo.de", 993, ssl, "posteo.de", 465, ssl, saveSent = true))
        put("mailbox.org", Known("imap.mailbox.org", 993, ssl, "smtp.mailbox.org", 465, ssl, saveSent = true))
        val gmx = Known("imap.gmx.net", 993, ssl, "mail.gmx.net", 465, ssl, saveSent = true)
        listOf("gmx.net", "gmx.de", "gmx.at", "gmx.ch").forEach { put(it, gmx) }
        listOf("gmx.com", "gmx.co.uk", "gmx.fr", "gmx.us").forEach { put(it, Known("imap.gmx.com", 993, ssl, "mail.gmx.com", 465, ssl, saveSent = true)) }
        put("web.de", Known("imap.web.de", 993, ssl, "smtp.web.de", 587, tls, saveSent = true))
        put("zoho.com", Known("imap.zoho.com", 993, ssl, "smtp.zoho.com", 465, ssl, saveSent = true))
        put("disroot.org", Known("disroot.org", 993, ssl, "disroot.org", 465, ssl, saveSent = true))
        put("riseup.net", Known("mail.riseup.net", 993, ssl, "mail.riseup.net", 465, ssl, saveSent = true))
        put("telenet.be", Known("imap.telenet.be", 993, ssl, "smtp.telenet.be", 587, tls, saveSent = true))
        put("skynet.be", Known("imap.proximus.be", 993, ssl, "relay.proximus.be", 587, tls, saveSent = true))
        put("proximus.be", Known("imap.proximus.be", 993, ssl, "relay.proximus.be", 587, tls, saveSent = true))
    }

    private val protonDomains = setOf("proton.me", "protonmail.com", "protonmail.ch", "pm.me")

    /**
     * Some providers show app passwords in groups with spaces ("abcd efgh ijkl mnop"); they never contain
     * spaces themselves, so pasted spaces are dropped for those providers.
     */
    fun cleanPassword(email: String, password: String): String =
        if (domainOf(email) in spacedAppPasswords) password.filterNot { it.isWhitespace() } else password

    private val spacedAppPasswords = setOf("gmail.com", "googlemail.com")

    /**
     * Where to make an app password for this address, for the providers that need one. Only opened when you
     * tap the button; MailCtrl itself never contacts these pages.
     */
    fun appPasswordUrl(email: String): String? = when (domainOf(email)) {
        "gmail.com", "googlemail.com" -> "https://myaccount.google.com/apppasswords"
        "yahoo.com", "yahoo.co.uk", "yahoo.fr", "yahoo.de", "ymail.com", "rocketmail.com" ->
            "https://login.yahoo.com/account/security/app-passwords"
        "icloud.com", "me.com", "mac.com" -> "https://account.apple.com/account/manage"
        else -> null
    }

    fun domainOf(email: String): String = email.substringAfterLast('@', "").trim().lowercase()

    /** Tutanota has no IMAP at all: it can't be added to any mail app. */
    fun unsupported(email: String): Boolean = domainOf(email) in setOf("tutanota.com", "tutanota.de", "tutamail.com", "tuta.io", "keemail.me", "tuta.com")

    fun isProton(email: String): Boolean = domainOf(email) in protonDomains

    fun forEmail(email: String): Preset {
        val user = email.trim()
        val domain = domainOf(user)
        if (domain in protonDomains) {
            // hydroxide and Proton Mail Bridge: IMAP on 1143 and SMTP on 1025, on your server's address. Both
            // file sent mail in Proton themselves. hydroxide has no TLS; Bridge uses STARTTLS with its own certificate.
            return Preset(
                imap = ServerConfig("", 1143, Security.NONE, user),
                smtp = ServerConfig("", 1025, Security.NONE, user),
                saveSent = false,
                needsHost = true,
                proton = true,
                known = true,
            )
        }
        known[domain]?.let { k ->
            return Preset(
                imap = ServerConfig(k.imapHost, k.imapPort, k.imapSec, user),
                smtp = ServerConfig(k.smtpHost, k.smtpPort, k.smtpSec, user),
                saveSent = k.saveSent,
                known = true,
            )
        }
        // A guess most providers follow; the next screen lets you change it.
        return Preset(
            imap = ServerConfig(if (domain.isEmpty()) "" else "imap.$domain", 993, Security.SSL, user),
            smtp = ServerConfig(if (domain.isEmpty()) "" else "smtp.$domain", 465, Security.SSL, user),
            saveSent = true,
        )
    }

    /** The usual port for [security], when you switch it on the server screen. */
    fun imapPort(security: Security, current: Int): Int = when {
        current !in setOf(0, 143, 993, 1143) -> current
        security == Security.SSL -> 993
        current == 1143 -> 1143
        else -> 143
    }

    fun smtpPort(security: Security, current: Int): Int = when {
        current !in setOf(0, 25, 465, 587, 1025) -> current
        security == Security.SSL -> 465
        current == 1025 -> 1025
        else -> 587
    }
}

/**
 * Your accounts, kept on the phone. Passwords never leave it except to their own mail server.
 */
class AccountStore(context: Context, private val vault: LockVault) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    /**
     * Whether passwords are sealed with the app PIN as well. Set by the app: only while the app lock is on and
     * notifications are off (background checks need the password while MailCtrl is locked).
     */
    @Volatile
    var sealPolicy: () -> Boolean = { false }

    private fun load(): List<Account> = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString(ListSerializer(Account.serializer()), it) }.getOrNull()
    }.orEmpty()

    private fun save(list: List<Account>) {
        prefs.edit().putString(KEY, json.encodeToString(ListSerializer(Account.serializer()), list)).commit()
        _accounts.value = list
    }

    fun get(id: String): Account? = _accounts.value.firstOrNull { it.id == id }

    /** The password, or null while it's sealed and MailCtrl is locked (or when there's none: see [needsPassword]). */
    fun password(account: Account): String? {
        if (account.secretEnc.isEmpty()) return null
        val plain = KeystoreCrypto.decrypt(account.secretEnc) ?: return null
        return if (LockVault.isSealed(plain)) vault.open(plain) else plain
    }

    /** The password was removed ("Forgot PIN?") and has to be typed again. */
    fun needsPassword(account: Account): Boolean = account.secretEnc.isEmpty()

    /** A password can't be read right now (sealed, and MailCtrl is locked). */
    fun anySealed(): Boolean = _accounts.value.any { a ->
        a.secretEnc.isNotEmpty() && KeystoreCrypto.decrypt(a.secretEnc)?.let(LockVault::isSealed) == true
    }

    private fun wrap(password: String): String {
        val inner = if (sealPolicy() && vault.isOpen) vault.seal(password) ?: password else password
        return KeystoreCrypto.encrypt(inner)
    }

    /** Changes are written to disk and encrypted with the hardware key: never on the main thread. */
    private suspend fun <T> change(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    /** Adds [account] with [password], or replaces the account with the same id. */
    suspend fun put(account: Account, password: String?) = change {
        val stored = if (password != null) account.copy(secretEnc = wrap(password)) else account
        val list = _accounts.value
        save(if (list.any { it.id == stored.id }) list.map { if (it.id == stored.id) stored else it } else list + stored)
    }

    suspend fun update(id: String, transform: (Account) -> Account) = change {
        save(_accounts.value.map { if (it.id == id) transform(it) else it })
    }

    suspend fun remove(id: String) = change {
        save(_accounts.value.filterNot { it.id == id })
    }

    suspend fun move(id: String, up: Boolean) = change {
        val list = _accounts.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return@change
        list.add(j, list.removeAt(i))
        save(list)
    }

    /**
     * Seals or unseals every password to match [sealPolicy]. Needs MailCtrl to be unlocked to seal or unseal;
     * passwords it can't open right now are left as they are.
     */
    suspend fun applySealing() = change {
        val seal = sealPolicy()
        save(
            _accounts.value.map { a ->
                if (a.secretEnc.isEmpty()) return@map a
                val plain = KeystoreCrypto.decrypt(a.secretEnc) ?: return@map a
                val sealed = LockVault.isSealed(plain)
                when {
                    seal && !sealed -> vault.seal(plain)?.let { a.copy(secretEnc = KeystoreCrypto.encrypt(it)) } ?: a
                    !seal && sealed -> vault.open(plain)?.let { a.copy(secretEnc = KeystoreCrypto.encrypt(it)) } ?: a
                    else -> a
                }
            },
        )
    }

    /**
     * "Forgot PIN?": passwords sealed with the old PIN can't be opened any more. They're removed, so those
     * accounts ask for their password again; everything else stays.
     */
    suspend fun dropSealed() = change {
        save(
            _accounts.value.map { a ->
                val plain = a.secretEnc.takeIf { it.isNotEmpty() }?.let(KeystoreCrypto::decrypt)
                if (plain != null && LockVault.isSealed(plain)) a.copy(secretEnc = "") else a
            },
        )
    }

    private companion object {
        const val KEY = "accounts_v1"
    }
}
