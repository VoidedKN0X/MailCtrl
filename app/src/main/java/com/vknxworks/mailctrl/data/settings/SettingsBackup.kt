package com.vknxworks.mailctrl.data.settings

import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.data.accounts.Identity
import com.vknxworks.mailctrl.mail.Security
import com.vknxworks.mailctrl.mail.ServerConfig
import com.vknxworks.mailctrl.mail.hasControlChars
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A settings file ("Back up and restore"): MailCtrl's own settings and your accounts' addresses, names,
 * signatures and server addresses. Never in it: passwords, trusted certificates, OpenPGP keys, the app lock and
 * its PIN, the ntfy push address, drafts, mail, or the lists kept encrypted on the phone.
 */
@Serializable
data class BackupFile(
    val app: String = APP,
    val format: Int = FORMAT,
    /** The MailCtrl version that wrote it, and when (for showing only). */
    val version: String = "",
    val createdAt: String = "",
    val settings: BackupSettings = BackupSettings(),
    val accounts: List<BackupAccount> = emptyList(),
) {
    companion object {
        const val APP = "MailCtrl"
        const val FORMAT = 1
    }
}

/**
 * The settings that travel. Every one is optional, so a file from another version restores what it has. Left
 * out on purpose: everything under Privacy and security (app lock, hiding, notification content), how long an
 * OpenPGP passphrase is remembered, subject encryption, the ntfy registration and the open connection.
 */
@Serializable
data class BackupSettings(
    val notifyEnabled: Boolean? = null,
    val notifyInterval: Int? = null,
    val remoteContent: String? = null,
    val markReadOnOpen: Boolean? = null,
    val keepMessages: Boolean? = null,
    val unifiedInbox: Boolean? = null,
    val swipeRight: String? = null,
    val swipeLeft: String? = null,
    val confirmDelete: Boolean? = null,
    /** The address of the account new messages are written from. */
    val defaultAccount: String? = null,
    val wkdLookup: Boolean? = null,
    val darkMessages: Boolean? = null,
    val suggestRecipients: Boolean? = null,
    val conversations: Boolean? = null,
    val undoSend: Int? = null,
)

@Serializable
data class BackupServer(val host: String = "", val port: Int = 0, val security: String = "SSL", val username: String = "")

@Serializable
data class BackupIdentity(val email: String = "", val name: String = "", val signature: String = "")

/** One account without anything secret: no password, no trusted certificates, no OpenPGP key. */
@Serializable
data class BackupAccount(
    val email: String = "",
    val senderName: String = "",
    val label: String = "",
    val imap: BackupServer = BackupServer(),
    val smtp: BackupServer = BackupServer(),
    val saveSent: Boolean = true,
    val signature: String = "",
    val notify: Boolean = true,
    val color: Int = -1,
    val unified: Boolean = true,
    val identities: List<BackupIdentity> = emptyList(),
)

/** The file isn't a MailCtrl settings file, or ([newer]) it's from a newer MailCtrl than this one. */
class BackupFormatException(val newer: Boolean) : Exception()

/** What restoring a file will do to the accounts. */
class AccountRestore(
    /** Accounts that aren't on this phone yet: added without a password, so each asks for it before it's used. */
    val added: List<Account>,
    /** Accounts already here (same address): names, signature, identities and switches are replaced; server, password and certificates stay. */
    val updated: List<Account>,
    /** New accounts whose connection has no encryption ("None"): they need their own yes before anything is restored. */
    val unencrypted: List<Account>,
)

/** Writing and reading settings files. No Android code, so it's tested on the computer. */
object SettingsBackups {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val EMAIL = Regex("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.\\-]{0,251}[A-Za-z0-9])?$")
    private const val MAX_TEXT = 4000

    fun export(s: AppSettings, accounts: List<Account>, version: String, createdAt: String): String = json.encodeToString(
        BackupFile.serializer(),
        BackupFile(
            version = version,
            createdAt = createdAt,
            settings = BackupSettings(
                notifyEnabled = s.notifyEnabled,
                notifyInterval = s.notifyInterval,
                remoteContent = s.remoteContent.name,
                markReadOnOpen = s.markReadOnOpen,
                keepMessages = s.keepMessages,
                unifiedInbox = s.unifiedInbox,
                swipeRight = s.swipeRight.name,
                swipeLeft = s.swipeLeft.name,
                confirmDelete = s.confirmDelete,
                defaultAccount = accounts.firstOrNull { it.id == s.defaultAccount }?.email,
                wkdLookup = s.wkdLookup,
                darkMessages = s.darkMessages,
                suggestRecipients = s.suggestRecipients,
                conversations = s.conversations,
                undoSend = s.undoSend,
            ),
            accounts = accounts.map { a ->
                BackupAccount(
                    email = a.email,
                    senderName = a.senderName,
                    label = a.label,
                    imap = BackupServer(a.imap.host, a.imap.port, a.imap.security.name, a.imap.username),
                    smtp = BackupServer(a.smtp.host, a.smtp.port, a.smtp.security.name, a.smtp.username),
                    saveSent = a.saveSent,
                    signature = a.signature,
                    notify = a.notify,
                    color = a.color,
                    unified = a.unified,
                    identities = a.identities.map { BackupIdentity(it.email, it.name, it.signature) },
                )
            },
        ),
    )

    /** Reads a settings file. Throws [BackupFormatException]. */
    fun parse(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw BackupFormatException(newer = false)
        }
        if (file.app != BackupFile.APP || file.format < 1) throw BackupFormatException(newer = false)
        if (file.format > BackupFile.FORMAT) throw BackupFormatException(newer = true)
        return file
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    /**
     * [current] with the file's settings put over it. Everything the file doesn't carry stays as it is: the app
     * lock and the rest of Privacy and security, the ntfy registration, the open connection. [accounts] are
     * the accounts after restoring, to find the default one by its address.
     */
    fun settings(file: BackupFile, current: AppSettings, accounts: List<Account>): AppSettings {
        val b = file.settings
        return current.copy(
            notifyEnabled = b.notifyEnabled ?: current.notifyEnabled,
            notifyInterval = b.notifyInterval?.takeIf { it in ALLOWED_INTERVALS } ?: current.notifyInterval,
            remoteContent = enumOr(b.remoteContent, current.remoteContent),
            markReadOnOpen = b.markReadOnOpen ?: current.markReadOnOpen,
            keepMessages = b.keepMessages ?: current.keepMessages,
            unifiedInbox = b.unifiedInbox ?: current.unifiedInbox,
            swipeRight = enumOr(b.swipeRight, current.swipeRight),
            swipeLeft = enumOr(b.swipeLeft, current.swipeLeft),
            confirmDelete = b.confirmDelete ?: current.confirmDelete,
            defaultAccount = b.defaultAccount?.let { email -> accounts.firstOrNull { it.email.equals(email, ignoreCase = true) }?.id } ?: current.defaultAccount,
            wkdLookup = b.wkdLookup ?: current.wkdLookup,
            darkMessages = b.darkMessages ?: current.darkMessages,
            suggestRecipients = b.suggestRecipients ?: current.suggestRecipients,
            conversations = b.conversations ?: current.conversations,
            undoSend = b.undoSend?.coerceIn(0, 60) ?: current.undoSend,
        )
    }

    private fun clean(text: String, max: Int = 200): String = text.filterNot { it.isISOControl() }.trim().take(max)

    private fun server(b: BackupServer, fallbackUser: String): ServerConfig? {
        val host = b.host.trim()
        if (host.isEmpty() || host.length > 253 || host.hasControlChars() || host.any { it.isWhitespace() }) return null
        if (b.port !in 1..65535) return null
        val security = Security.entries.firstOrNull { it.name == b.security } ?: return null
        return ServerConfig(host, b.port, security, clean(b.username).ifEmpty { fallbackUser })
    }

    private fun identities(list: List<BackupIdentity>, own: String): List<Identity> = list.asSequence()
        .map { Identity(it.email.trim(), clean(it.name), it.signature.take(MAX_TEXT)) }
        .filter { EMAIL.matches(it.email) && !it.email.equals(own, ignoreCase = true) }
        .distinctBy { it.email.lowercase() }
        .take(50)
        .toList()

    /**
     * What the file's accounts become next to [existing]. An account never arrives with a password or a trusted
     * certificate, and one that is already here keeps its own server settings: a file can't point an account
     * that works at another server. Entries that aren't a usable account are skipped.
     */
    fun accounts(file: BackupFile, existing: List<Account>): AccountRestore {
        val added = ArrayList<Account>()
        val updated = ArrayList<Account>()
        val seen = HashSet<String>()
        for (b in file.accounts.take(50)) {
            val email = b.email.trim()
            if (!EMAIL.matches(email) || !seen.add(email.lowercase())) continue
            val ids = identities(b.identities, email)
            val color = b.color.takeIf { it in -1..31 } ?: -1
            val here = existing.firstOrNull { it.email.equals(email, ignoreCase = true) }
            if (here != null) {
                updated += here.copy(
                    senderName = clean(b.senderName),
                    label = clean(b.label),
                    signature = b.signature.take(MAX_TEXT),
                    notify = b.notify,
                    saveSent = b.saveSent,
                    color = color,
                    unified = b.unified,
                    identities = ids,
                )
            } else {
                val imap = server(b.imap, email) ?: continue
                val smtp = server(b.smtp, email) ?: continue
                added += Account(
                    email = email,
                    senderName = clean(b.senderName),
                    label = clean(b.label),
                    imap = imap,
                    smtp = smtp,
                    // No password, and no certificate is trusted: signing in asks for both again.
                    secretEnc = "",
                    trustedCerts = emptyList(),
                    saveSent = b.saveSent,
                    signature = b.signature.take(MAX_TEXT),
                    notify = b.notify,
                    color = color,
                    unified = b.unified,
                    identities = ids,
                )
            }
        }
        return AccountRestore(added, updated, added.filter { it.unencrypted })
    }

    /** The check intervals the Notifications screen offers (minutes; 0: only when woken). */
    val ALLOWED_INTERVALS = setOf(0, 15, 30, 60, 180)
}
