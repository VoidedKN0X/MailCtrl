package com.vknxworks.mailctrl.mail

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Base64
import android.util.LruCache
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import com.sun.mail.imap.IMAPStore
import com.sun.mail.imap.protocol.Status
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.data.accounts.AccountStore
import com.vknxworks.mailctrl.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Date
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.activation.DataHandler
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.FolderClosedException
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.ReadOnlyFolderException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.StoreClosedException
import javax.mail.UIDFolder
import javax.mail.internet.ContentType
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimePart
import javax.mail.internet.MimeUtility
import javax.mail.search.BodyTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.RecipientStringTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm
import javax.mail.util.ByteArrayDataSource
import javax.net.ssl.SSLException

/** The app is locked and the password is sealed with the app PIN: nothing can sign in until it's unlocked. */
class LockedException : Exception(str(R.string.error_locked))

/** The account's password was removed ("Forgot PIN?") and has to be typed again. */
class PasswordNeededException : Exception(str(R.string.error_password_needed))

/** A list of messages, newest first, and whether there are older ones. */
data class MessagePage(val headers: List<MessageHeader>, val hasMore: Boolean)

/** What the phone already has of a folder: the server's numbering of it, and the newest message numbers in its list. */
data class KnownRange(val uidValidity: Long, val uids: List<Long>)

/** A message's flags as the server has them now. */
data class FlagState(val seen: Boolean, val flagged: Boolean, val answered: Boolean)

/** What changed in a folder since its list on the phone was last brought up to date. */
data class FolderSync(
    val uidValidity: Long,
    /** Start the list over with [fresh] (the first time, or after the server renumbered its messages). */
    val reset: Boolean,
    /** Messages the phone doesn't have yet. */
    val fresh: List<MessageHeader>,
    /** The current flags of messages the phone already has. */
    val flags: Map<Long, FlagState>,
    /** Messages on the phone that are gone from the server. */
    val removed: Set<Long>,
    /** After a [reset]: whether there are older messages than [fresh]. */
    val hasMore: Boolean,
)

/** Which connection an action uses: background syncing never makes you wait to open a message. */
enum class Lane {
    /** Refreshing lists, folders, new-mail checks and reading ahead. Folders are closed after each step. */
    SYNC,

    /** What you do yourself: opening, flagging, moving. The folder stays open for the next action in it. */
    USER,
}

/** What checking the inbox for new mail found. */
data class NewMailResult(
    val fresh: List<MessageHeader>,
    val lastUid: Long,
    val uidValidity: Long,
    val unread: Int,
)

/**
 * Talks to your mail servers: IMAP for reading and organising, SMTP for sending.
 *
 * Each account keeps two IMAP connections open while it's in use, one per [Lane], so a refresh running in the
 * background never holds up opening a message. Connections are reused for minutes instead of signing in again for
 * every action, and dropped when the phone switches networks (Wi-Fi, mobile data, VPN), where they would hang.
 */
class MailClient(private val context: Context, private val accounts: AccountStore) {

    /** One signed-in IMAP connection, and the folder it has open. */
    private class Link(val store: IMAPStore, val signature: String, val keepOpen: Boolean) {
        @Volatile var lastUsed = System.currentTimeMillis()

        /** The folder left open after the last action (only on the [Lane.USER] connection). */
        @Volatile var current: IMAPFolder? = null

        /**
         * Whether [current] was opened for changes. Noted here because asking the folder (its mode) sends a NOOP
         * to the server, and fails outright once the server has closed it.
         */
        @Volatile var writable = false
            private set

        /**
         * [name] opened for reading, or for changes too when [write]. On the [Lane.USER] connection the folder
         * stays open, so the next action in the same folder needs no extra round trip to the server.
         */
        fun folder(name: String, write: Boolean): IMAPFolder {
            current?.let { f ->
                // Used a moment ago: still open (if the server closed it meanwhile, the action fails and is retried).
                val recent = System.currentTimeMillis() - lastUsed < TRUST_MS
                if (f.fullName == name && (!write || writable) && (recent || f.isOpen)) return f
                current = null
                writable = false
                // Closing hands the connection back, so the next folder opens on it without a new sign-in. No
                // isOpen first: that costs a NOOP, and closing a folder that's already closed just fails here.
                runCatching { f.close(false) }
            }
            val f = store.getFolder(name) as IMAPFolder
            var rw = true
            val opened = when {
                write -> f.apply { open(Folder.READ_WRITE) }
                // Kept open for later changes too, unless the folder is read-only on the server.
                keepOpen -> try {
                    f.apply { open(Folder.READ_WRITE) }
                } catch (e: ReadOnlyFolderException) {
                    rw = false
                    (store.getFolder(name) as IMAPFolder).apply { open(Folder.READ_ONLY) }
                }
                else -> {
                    rw = false
                    f.apply { open(Folder.READ_ONLY) }
                }
            }
            current = opened
            writable = rw
            return opened
        }

        /** After an action: closes the folder unless it's kept, and notes the time. */
        fun done() {
            if (!keepOpen) {
                current?.let { f -> runCatching { f.close(false) } }
                current = null
                writable = false
            }
            lastUsed = System.currentTimeMillis()
        }

        /** Whether it went unused so long that the network may have dropped it without a word (then asking hangs). */
        fun stale(): Boolean = System.currentTimeMillis() - lastUsed > KEEP_MS

        /**
         * Whether the connection still works: taken as yes when it was used a moment ago (unless [ask]), one quick
         * NOOP otherwise.
         */
        fun alive(ask: Boolean = false): Boolean {
            if (!ask && System.currentTimeMillis() - lastUsed < TRUST_MS) return true
            val f = current
            // With a folder open, ask on the folder's own connection (the store's would sign in a second one).
            return if (f != null) f.isOpen else store.isConnected
        }

        fun close() {
            runCatching { current?.close(false) }
            runCatching { store.close() }
        }
    }

    private val links = ConcurrentHashMap<String, Link>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun key(accountId: String, lane: Lane) = "$accountId|${lane.name}"

    private fun lockFor(key: String) = locks.getOrPut(key) { Mutex() }

    /** Signing out of a server is network traffic: it never happens on the main thread. */
    private val closer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun closeLater(link: Link) {
        closer.launch { link.close() }
    }

    /** Changes to any of these mean the open connection is for old settings. */
    private fun signature(a: Account) = listOf(a.imap, a.trustedCerts, a.secretEnc).hashCode().toString()

    init {
        watchNetwork()
    }

    /**
     * A connection made over one network hangs once the phone moves to another (Wi-Fi to mobile data, a VPN going
     * up or down) until it times out. They're closed at once instead, and the next action signs in again.
     */
    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                @Volatile private var last: Network? = null

                override fun onAvailable(network: Network) {
                    val before = last
                    last = network
                    if (before != null && before != network) closeAll()
                }

                override fun onLost(network: Network) {
                    if (network == last) {
                        last = null
                        closeAll()
                    }
                }
            })
        }
    }

    // ------------------------------------------------------------------ sessions

    private fun session(server: ServerConfig, smtp: Boolean, trust: ServerTrust, from: String? = null): Pair<Session, String> {
        val proto = when {
            smtp && server.security == Security.SSL -> "smtps"
            smtp -> "smtp"
            server.security == Security.SSL -> "imaps"
            else -> "imap"
        }
        val p = Properties()
        val k = "mail.$proto"
        p["$k.host"] = server.host
        p["$k.port"] = server.port.toString()
        p["$k.connectiontimeout"] = "15000"
        p["$k.timeout"] = "30000"
        p["$k.writetimeout"] = "30000"
        // Only plain password sign-in; never NTLM, which leaks the phone's name.
        p["$k.auth.mechanisms"] = "PLAIN LOGIN"
        // Only current TLS versions; 1.0 and 1.1 are broken.
        if (server.security != Security.NONE) p["$k.ssl.protocols"] = "TLSv1.3 TLSv1.2"
        when (server.security) {
            Security.SSL -> {
                p["$k.ssl.enable"] = "true"
                p["$k.ssl.socketFactory"] = trust.socketFactory()
                // ServerTrust checks the name itself (and allows certificates you trusted by fingerprint).
                p["$k.ssl.checkserveridentity"] = "false"
            }
            Security.STARTTLS -> {
                p["$k.starttls.enable"] = "true"
                p["$k.starttls.required"] = "true"
                p["$k.ssl.socketFactory"] = trust.socketFactory()
                p["$k.ssl.checkserveridentity"] = "false"
            }
            Security.NONE -> p["$k.starttls.enable"] = "false"
        }
        if (smtp) {
            p["$k.auth"] = "true"
            // The greeting names the phone; a neutral address instead of its host name.
            p["$k.localhost"] = "[127.0.0.1]"
            if (from != null) p["$k.from"] = from
        } else {
            // Opening a message or downloading a file never marks it read by itself; MailCtrl does that when you read it.
            p["$k.peek"] = "true"
            p["$k.fetchsize"] = (1024 * 1024).toString()
            p["$k.partialfetch"] = "true"
            // Spare signed-in connections are kept for 5 minutes instead of 45 seconds. (MailCtrl uses one per lane,
            // which JavaMail keeps anyway; withStore signs in afresh after 5 idle minutes, see Link.stale.)
            p["$k.connectionpooltimeout"] = KEEP_MS.toString()
        }
        p["mail.mime.address.strict"] = "false"
        p["mail.mime.decodetext.strict"] = "false"
        p["mail.mime.decodefilename"] = "true"
        p["mail.mime.encodefilename"] = "true"
        return Session.getInstance(p) to proto
    }

    /** Turns what went wrong into something a person can act on. */
    private fun translate(e: Throwable, trust: ServerTrust?): Throwable {
        trust?.rejection()?.let { return it }
        if (e is UntrustedCertificateException || e is AuthException || e is MailException || e is LockedException || e is PasswordNeededException) return e
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is AuthenticationFailedException -> return authError(cause.message, e)
                is UnknownHostException -> return MailException(str(R.string.error_unknown_host), e)
                is SocketTimeoutException -> return MailException(str(R.string.error_timeout), e)
                is ConnectException, is NoRouteToHostException -> return MailException(str(R.string.error_connect), e)
                is SSLException -> return MailException(str(R.string.error_tls), e)
            }
            cause = if (cause is MessagingException) cause.nextException ?: cause.cause else cause.cause
        }
        val msg = e.message.orEmpty()
        return when {
            msg.contains("STARTTLS", ignoreCase = true) -> MailException(str(R.string.error_starttls), e)
            msg.contains("[AUTHENTICATIONFAILED]", ignoreCase = true) || msg.contains("Invalid credentials", ignoreCase = true) ||
                msg.contains("Application-specific password", ignoreCase = true) -> authError(msg, e)
            else -> MailException(msg.ifBlank { str(R.string.error_generic) }, e)
        }
    }

    /**
     * A refused sign-in, with what the server said about it: that often tells exactly what's wrong (an app
     * password is needed, the account must allow mail apps first, the password is wrong).
     */
    private fun authError(serverText: String?, e: Throwable): AuthException {
        val said = serverText.orEmpty().replace(Regex("\\s+"), " ").trim().take(300)
        return when {
            said.contains("Application-specific password", ignoreCase = true) -> AuthException(str(R.string.error_app_password), e)
            said.contains("web browser", ignoreCase = true) || said.contains("WEBALERT", ignoreCase = true) ->
                AuthException(str(R.string.error_web_login), e)
            said.isBlank() -> AuthException(str(R.string.error_auth), e)
            else -> AuthException(str(R.string.error_auth) + "\n\n" + str(R.string.server_said, said), e)
        }
    }

    private fun passwordOf(a: Account): String {
        if (accounts.needsPassword(a)) throw PasswordNeededException()
        return accounts.password(a) ?: throw LockedException()
    }

    private fun connect(a: Account, password: String): IMAPStore {
        val trust = ServerTrust(a.imap.host, a.trustedCerts)
        val (session, proto) = session(a.imap, smtp = false, trust = trust)
        val store = session.getStore(proto) as IMAPStore
        try {
            store.connect(a.imap.host, a.imap.port, a.imap.username, password)
        } catch (e: Exception) {
            runCatching { store.close() }
            throw translate(e, trust)
        }
        return store
    }

    /**
     * Runs [block] with the account's IMAP connection for [lane], signing in (again) when needed. When the server
     * dropped an idle connection, it signs in once more and tries again.
     */
    private suspend fun <T> withStore(accountId: String, lane: Lane = Lane.USER, block: (Account, Link) -> T): T =
        withContext(Dispatchers.IO) {
            val key = key(accountId, lane)
            lockFor(key).withLock {
                val a = accounts.get(accountId) ?: throw MailException(str(R.string.error_no_account))
                fun open(): Link {
                    // Signed out in the background: on a dead connection that waits for the read timeout.
                    links.remove(key)?.let { closeLater(it) }
                    val link = Link(connect(a, passwordOf(a)), signature(a), keepOpen = lane == Lane.USER)
                    links[key] = link
                    return link
                }
                // Unused for longer than it's kept: signing in again is quicker than a NOOP that may wait for the
                // read timeout on a connection the network dropped silently (common on mobile data).
                links[key]?.takeIf { it.stale() }?.let { old -> if (links.remove(key, old)) closeLater(old) }
                val existing = links[key]?.takeIf { it.signature == signature(a) && runCatching { it.alive() }.getOrDefault(false) }
                val link = existing ?: open()
                try {
                    block(a, link).also { link.done() }
                } catch (e: Exception) {
                    // The NOOP last: when the error already says the connection broke, there's no need to ask.
                    val dropped = e is StoreClosedException || e is FolderClosedException || e is IllegalStateException ||
                        (e is MessagingException && e.nextException is java.io.IOException) ||
                        !runCatching { link.alive(ask = true) }.getOrDefault(false)
                    if (existing != null && dropped) {
                        try {
                            val again = open()
                            block(a, again).also { again.done() }
                        } catch (e2: Exception) {
                            links.remove(key)?.let { closeLater(it) }
                            throw translate(e2, null)
                        }
                    } else {
                        runCatching { link.done() }
                        // A brand-new connection that broke is not kept for the next action.
                        if (dropped && links.remove(key, link)) closeLater(link)
                        throw translate(e, null)
                    }
                }
            }
        }

    /** Closes every open connection (when the app is locked, the network changes, or an account changes). */
    fun closeAll() {
        // One by one, so a connection opened meanwhile is either closed too or kept, never dropped while open.
        links.keys.toList().forEach { k -> links.remove(k)?.let { closeLater(it) } }
    }

    fun close(accountId: String) {
        Lane.entries.forEach { lane -> links.remove(key(accountId, lane))?.let { closeLater(it) } }
    }

    /** Whether the server can [cap], asked on [f]'s own connection (no second sign-in, no network traffic). */
    private fun IMAPFolder.can(cap: String): Boolean =
        runCatching { doCommand(IMAPFolder.ProtocolCommand { p -> p.hasCapability(cap) }) as? Boolean }.getOrNull() ?: false

    // ------------------------------------------------------------------ setup

    /** Signs in to both servers with [password] and finds the special folders. Used when adding or changing an account. */
    suspend fun test(a: Account, password: String): Account = withContext(Dispatchers.IO) {
        val store = connect(a, password)
        val found = try {
            listFolders(store)
        } catch (e: Exception) {
            throw translate(e, null)
        } finally {
            runCatching { store.close() }
        }
        testSmtp(a, password)
        withRoles(a, found)
    }

    private fun testSmtp(a: Account, password: String) {
        val trust = ServerTrust(a.smtp.host, a.trustedCerts)
        val (session, proto) = session(a.smtp, smtp = true, trust = trust, from = a.email)
        val t = session.getTransport(proto)
        try {
            t.connect(a.smtp.host, a.smtp.port, a.smtp.username, password)
        } catch (e: Exception) {
            throw translate(e, trust)
        } finally {
            runCatching { t.close() }
        }
    }

    private fun withRoles(a: Account, folders: List<FolderInfo>): Account {
        fun role(r: FolderRole) = folders.firstOrNull { it.role == r && it.selectable }?.fullName
        return a.copy(
            sentFolder = role(FolderRole.SENT),
            draftsFolder = role(FolderRole.DRAFTS),
            trashFolder = role(FolderRole.TRASH),
            // Gmail-style servers have no Archive folder: moving to All Mail is how they archive.
            archiveFolder = role(FolderRole.ARCHIVE) ?: role(FolderRole.ALL),
            junkFolder = role(FolderRole.JUNK),
        )
    }

    // ------------------------------------------------------------------ folders

    private fun roleOf(f: IMAPFolder): FolderRole {
        if (f.fullName.equals("INBOX", ignoreCase = true)) return FolderRole.INBOX
        val attrs = runCatching { f.attributes }.getOrNull().orEmpty().map { it.lowercase() }
        when {
            "\\sent" in attrs -> return FolderRole.SENT
            "\\drafts" in attrs -> return FolderRole.DRAFTS
            "\\trash" in attrs -> return FolderRole.TRASH
            "\\junk" in attrs -> return FolderRole.JUNK
            "\\archive" in attrs -> return FolderRole.ARCHIVE
            "\\all" in attrs -> return FolderRole.ALL
            "\\flagged" in attrs -> return FolderRole.FLAGGED
        }
        // Servers without special-use flags: go by the usual names.
        return when (f.name.lowercase()) {
            "sent", "sent items", "sent mail", "sent messages", "verzonden", "verzonden items", "gesendet", "envoyés" -> FolderRole.SENT
            "drafts", "draft", "concepten", "entwürfe", "brouillons" -> FolderRole.DRAFTS
            "trash", "deleted", "deleted items", "deleted messages", "bin", "prullenbak", "papierkorb", "corbeille" -> FolderRole.TRASH
            "junk", "spam", "junk e-mail", "ongewenst" -> FolderRole.JUNK
            "archive", "archives", "archief", "archiv" -> FolderRole.ARCHIVE
            else -> FolderRole.OTHER
        }
    }

    private fun listFolders(store: IMAPStore): List<FolderInfo> {
        val all = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()
        val infos = all.map { f ->
            val attrs = runCatching { f.attributes }.getOrNull().orEmpty().map { it.lowercase() }
            val selectable = "\\noselect" !in attrs && "\\nonexistent" !in attrs
            val sep = runCatching { f.separator }.getOrDefault('/')
            val role = roleOf(f)
            var unread = 0
            var total = 0
            // One request per folder for both counts. Gmail's All Mail is skipped: counting it is slow and its
            // number says little.
            if (selectable && role != FolderRole.ALL) {
                runCatching {
                    val st = f.doCommand(IMAPFolder.ProtocolCommand { p -> p.status(f.fullName, arrayOf("MESSAGES", "UNSEEN")) }) as? Status
                    if (st != null) {
                        unread = st.unseen
                        total = st.total
                    }
                }
            }
            FolderInfo(
                fullName = f.fullName,
                name = if (role == FolderRole.INBOX) "Inbox" else f.name,
                role = role,
                depth = f.fullName.count { it == sep },
                unread = unread.coerceAtLeast(0),
                total = total.coerceAtLeast(0),
                selectable = selectable,
            )
        }
        val order = listOf(FolderRole.INBOX, FolderRole.DRAFTS, FolderRole.SENT, FolderRole.ARCHIVE, FolderRole.ALL, FolderRole.FLAGGED, FolderRole.JUNK, FolderRole.TRASH)
        val special = infos.filter { it.role != FolderRole.OTHER }.sortedBy { order.indexOf(it.role) }
        val others = infos.filter { it.role == FolderRole.OTHER }.sortedBy { it.fullName.lowercase() }
        return special + others
    }

    /** Every folder of the account, special folders first. Also remembers which folder is Sent, Trash and so on. */
    suspend fun folders(accountId: String): List<FolderInfo> {
        val list = withStore(accountId, Lane.SYNC) { _, link -> listFolders(link.store) }
        val a = accounts.get(accountId)
        if (a != null) {
            val updated = withRoles(a, list)
            if (updated != a) accounts.update(accountId) { withRoles(it, list) }
        }
        return list
    }

    // ------------------------------------------------------------------ messages

    private val listProfile = FetchProfile().apply {
        add(FetchProfile.Item.ENVELOPE)
        add(FetchProfile.Item.FLAGS)
        add(FetchProfile.Item.CONTENT_INFO)
        add(UIDFolder.FetchProfileItem.UID)
    }

    /** Only what can change about a message the phone already has. */
    private val flagsProfile = FetchProfile().apply {
        add(FetchProfile.Item.FLAGS)
        add(UIDFolder.FetchProfileItem.UID)
    }

    /** Opening a message: the list's details, its size, and the two headers MailCtrl uses, in one request. */
    private val openProfile = FetchProfile().apply {
        add(FetchProfile.Item.ENVELOPE)
        add(FetchProfile.Item.FLAGS)
        add(FetchProfile.Item.CONTENT_INFO)
        add(FetchProfile.Item.SIZE)
        add(UIDFolder.FetchProfileItem.UID)
        add("References")
        add("List-Unsubscribe")
    }

    private fun InternetAddress.toAddress(): Address = Address(personal?.trim().orEmpty(), address?.trim().orEmpty())

    private fun addresses(list: Array<javax.mail.Address>?): List<Address> =
        list.orEmpty().filterIsInstance<InternetAddress>().map { it.toAddress() }

    private fun header(accountId: String, folder: IMAPFolder, m: Message): MessageHeader? = runCatching {
        MessageHeader(
            accountId = accountId,
            folder = folder.fullName,
            uid = folder.getUID(m),
            from = addresses(runCatching { m.from }.getOrNull()).firstOrNull(),
            to = addresses(runCatching { m.getRecipients(Message.RecipientType.TO) }.getOrNull()),
            subject = runCatching { m.subject }.getOrNull()?.trim().orEmpty(),
            date = (runCatching { m.sentDate }.getOrNull() ?: runCatching { m.receivedDate }.getOrNull())?.time ?: 0L,
            seen = m.isSet(Flags.Flag.SEEN),
            flagged = m.isSet(Flags.Flag.FLAGGED),
            answered = m.isSet(Flags.Flag.ANSWERED),
            hasAttachments = runCatching { hasAttachments(m) }.getOrDefault(false),
        )
    }.getOrNull()

    private fun flagState(m: Message) = FlagState(
        seen = m.isSet(Flags.Flag.SEEN),
        flagged = m.isSet(Flags.Flag.FLAGGED),
        answered = m.isSet(Flags.Flag.ANSWERED),
    )

    private fun hasAttachments(p: Part): Boolean {
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as? Multipart ?: return false
            return (0 until mp.count).any { hasAttachments(mp.getBodyPart(it)) }
        }
        return isAttachment(p)
    }

    private fun isAttachment(p: Part): Boolean {
        val disposition = runCatching { p.disposition }.getOrNull()
        if (Part.ATTACHMENT.equals(disposition, ignoreCase = true)) return true
        if (p.isMimeType("message/rfc822")) return true
        val name = runCatching { p.fileName }.getOrNull()
        if (name.isNullOrBlank()) return false
        // Pictures shown inside the message aren't attachments.
        if (Part.INLINE.equals(disposition, ignoreCase = true) && contentIdOf(p) != null) return false
        if (contentIdOf(p) != null && p.isMimeType("image/*") && disposition == null) return false
        return true
    }

    private fun contentIdOf(p: Part): String? = (p as? MimePart)?.let { runCatching { it.contentID }.getOrNull() }
        ?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotBlank() }

    /** The newest [count] messages of an open folder, newest first. */
    private fun newest(a: Account, f: IMAPFolder, count: Int): MessagePage {
        val total = f.messageCount
        if (total <= 0) return MessagePage(emptyList(), false)
        val start = maxOf(1, total - count + 1)
        val msgs = f.getMessages(start, total)
        f.fetch(msgs, listProfile)
        return MessagePage(msgs.mapNotNull { header(a.id, f, it) }.sortedByDescending { it.uid }, start > 1)
    }

    /**
     * Brings a folder's list on the phone up to date. Only what changed is downloaded: messages the phone doesn't
     * have yet in full, and for the ones it has just their flags (read, flagged, answered) and whether they're
     * still there. The first time, or when the server renumbered the folder, it starts over with the newest [count].
     */
    suspend fun sync(accountId: String, folderName: String, known: KnownRange?, count: Int): FolderSync =
        syncList(accountId, folderName, known, count).also { keptSync(accountId, folderName, it) }

    /**
     * Keeps the messages in memory in step with what [sync] found: changed flags (read on another device), messages
     * gone from the server, and after a renumbering none at all, since a number may now be a different message.
     */
    private fun keptSync(accountId: String, folderName: String, sync: FolderSync) {
        if (sync.reset) {
            val prefix = bodyKey(accountId, folderName, 0).removeSuffix("0")
            bodies.snapshot().keys.filter { it.startsWith(prefix) }.forEach { bodies.remove(it) }
            raws.snapshot().keys.filter { it.startsWith(prefix) }.forEach { raws.remove(it) }
            return
        }
        forgetKept(accountId, folderName, sync.removed)
        sync.flags.forEach { (uid, s) ->
            val key = bodyKey(accountId, folderName, uid)
            val b = bodies.get(key) ?: return@forEach
            if (b.header.seen != s.seen || b.header.flagged != s.flagged || b.header.answered != s.answered) {
                bodies.put(key, b.copy(header = b.header.copy(seen = s.seen, flagged = s.flagged, answered = s.answered)))
            }
        }
    }

    private suspend fun syncList(accountId: String, folderName: String, known: KnownRange?, count: Int): FolderSync =
        withStore(accountId, Lane.SYNC) { a, link ->
            val f = link.folder(folderName, write = false)
            val validity = f.uidValidity
            fun reset(): FolderSync {
                val page = newest(a, f, count)
                return FolderSync(validity, reset = true, fresh = page.headers, flags = emptyMap(), removed = emptySet(), hasMore = page.hasMore)
            }
            if (known == null || known.uids.isEmpty() || known.uidValidity != validity) return@withStore reset()
            if (f.messageCount <= 0) {
                return@withStore FolderSync(validity, reset = true, fresh = emptyList(), flags = emptyMap(), removed = emptySet(), hasMore = false)
            }
            val min = known.uids.min()
            val have = known.uids.toHashSet()
            // "n:*" always includes the newest message, even below n: filter by UID as well.
            val msgs = f.getMessagesByUID(min, UIDFolder.LASTUID).filterNotNull().filter { f.getUID(it) >= min }
            val missing = msgs.filter { f.getUID(it) !in have }
            // Very far behind (hundreds of new messages): starting over with the newest is quicker.
            if (missing.size > MAX_NEW) return@withStore reset()
            val kept = msgs.filter { f.getUID(it) in have }.toTypedArray()
            if (kept.isNotEmpty()) f.fetch(kept, flagsProfile)
            val fresh = missing.toTypedArray()
            if (fresh.isNotEmpty()) f.fetch(fresh, listProfile)
            val present = msgs.map { f.getUID(it) }.toHashSet()
            FolderSync(
                uidValidity = validity,
                reset = false,
                fresh = fresh.mapNotNull { header(a.id, f, it) },
                flags = kept.associate { f.getUID(it) to flagState(it) },
                removed = have.filterNot { it in present }.toSet(),
                hasMore = false,
            )
        }

    /** Older messages than [beforeUid], newest first ("Load more"). */
    suspend fun messages(accountId: String, folderName: String, count: Int, beforeUid: Long? = null): MessagePage =
        withStore(accountId, Lane.SYNC) { a, link ->
            val f = link.folder(folderName, write = false)
            if (beforeUid == null) return@withStore newest(a, f, count)
            if (f.messageCount <= 0) return@withStore MessagePage(emptyList(), false)
            val m = f.getMessageByUID(beforeUid)
            val end = if (m != null) {
                m.messageNumber - 1
            } else {
                // The message was removed meanwhile: carry on from the newest one before it.
                val below = if (beforeUid > 1) f.getMessagesByUID(1, beforeUid - 1) else emptyArray<Message>()
                below.lastOrNull()?.messageNumber ?: 0
            }
            if (end < 1) return@withStore MessagePage(emptyList(), false)
            val start = maxOf(1, end - count + 1)
            val msgs = f.getMessages(start, end)
            f.fetch(msgs, listProfile)
            MessagePage(msgs.mapNotNull { header(a.id, f, it) }.sortedByDescending { it.uid }, start > 1)
        }

    /** Server-side search in one folder: subject, sender, recipients and text. */
    suspend fun search(accountId: String, folderName: String, query: String, limit: Int = 100): List<MessageHeader> =
        withStore(accountId) { a, link ->
            val f = link.folder(folderName, write = false)
            val term: SearchTerm = OrTerm(
                arrayOf(
                    SubjectTerm(query),
                    FromStringTerm(query),
                    RecipientStringTerm(Message.RecipientType.TO, query),
                    BodyTerm(query),
                ),
            )
            val found = f.search(term).takeLast(limit).toTypedArray()
            f.fetch(found, listProfile)
            found.mapNotNull { header(a.id, f, it) }.sortedByDescending { it.date }
        }

    // ------------------------------------------------------------------ opening messages

    /**
     * Messages opened (or read ahead) lately, kept in memory only, so opening one again is instant. Cleared when
     * MailCtrl locks; never written to storage.
     */
    private val bodies = object : LruCache<String, MessageBody>(BODY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: MessageBody): Int {
            val text = (value.html?.length ?: 0) + (value.text?.length ?: 0)
            val pictures = value.inlineImages.values.sumOf { it.length }
            return (text + pictures).coerceAtLeast(1)
        }
    }

    /** The whole of small messages, so their attachments open without downloading again. Memory only, like [bodies]. */
    private val raws = object : LruCache<String, ByteArray>(RAW_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    private fun bodyKey(accountId: String, folder: String, uid: Long) = "$accountId|$folder|$uid"

    /** Forgets every message kept in memory (when MailCtrl locks). */
    fun forgetBodies() {
        bodies.evictAll()
        raws.evictAll()
    }

    /** For reading kept copies of small messages: no server, just the MIME rules. */
    private val parseSession: Session by lazy {
        Session.getInstance(
            Properties().apply {
                put("mail.mime.address.strict", "false")
                put("mail.mime.decodetext.strict", "false")
                put("mail.mime.decodefilename", "true")
            },
        )
    }

    /**
     * Reads one message. A small one (most mail) is downloaded whole in a single request and taken apart on the
     * phone; a big one part by part, so its attachments only download when you open them.
     */
    private fun loadBody(a: Account, f: IMAPFolder, m: IMAPMessage): Pair<MessageBody, ByteArray?> {
        f.fetch(arrayOf<Message>(m), openProfile)
        val h = header(a.id, f, m) ?: throw MailException(str(R.string.error_generic))
        val size = runCatching { m.size }.getOrDefault(-1)
        val raw = if (size in 0..WHOLE_MAX) {
            runCatching { ByteArrayOutputStream(size + 1024).also { m.writeTo(it) }.toByteArray() }.getOrNull()
        } else {
            null
        }
        val source: MimeMessage = raw?.let { runCatching { MimeMessage(parseSession, ByteArrayInputStream(it)) }.getOrNull() } ?: m
        val parts = Collected()
        collect(source, "", parts)
        val html = parts.html
        val from = h.from?.email
        val body = MessageBody(
            header = h,
            cc = addresses(runCatching { m.getRecipients(Message.RecipientType.CC) }.getOrNull()),
            bcc = addresses(runCatching { m.getRecipients(Message.RecipientType.BCC) }.getOrNull()),
            replyTo = addresses(runCatching { m.replyTo }.getOrNull()).filterNot { it.email.equals(from, ignoreCase = true) },
            messageId = runCatching { m.messageID }.getOrNull(),
            references = runCatching { source.getHeader("References")?.firstOrNull() }.getOrNull(),
            html = html,
            text = parts.text,
            attachments = parts.attachments,
            inlineImages = parts.inlineImages,
            hasRemoteContent = html != null && REMOTE.containsMatchIn(html),
            unsubscribe = runCatching { source.getHeader("List-Unsubscribe")?.firstOrNull() }.getOrNull()?.let { unsubscribeLink(it) },
        )
        return body to raw
    }

    private fun keep(key: String, body: MessageBody, raw: ByteArray?) {
        bodies.put(key, body)
        if (raw != null) raws.put(key, raw)
    }

    /** Opens one message: its text, attachments and inline pictures. Marks it read when [markRead]. */
    suspend fun open(accountId: String, folderName: String, uid: Long, markRead: Boolean): MessageBody {
        val key = bodyKey(accountId, folderName, uid)
        bodies.get(key)?.let { kept ->
            if (!markRead || kept.header.seen) return kept
            // Shown straight away; the server is told it's read in the background.
            val read = kept.copy(header = kept.header.copy(seen = true))
            bodies.put(key, read)
            closer.launch {
                runCatching { setFlag(accountId, folderName, listOf(uid), Flags.Flag.SEEN, true) }
                    // Not marked on the server: the copy in memory says unread again, so the next opening retries.
                    .onFailure { keptFlag(accountId, folderName, listOf(uid), Flags.Flag.SEEN, false) }
            }
            return read
        }
        return withStore(accountId) { a, link ->
            val f = link.folder(folderName, write = false)
            val writable = link.writable
            val m = f.getMessageByUID(uid) as? IMAPMessage ?: throw MailException(str(R.string.error_message_gone))
            val (body, raw) = loadBody(a, f, m)
            if (markRead && writable && !body.header.seen) runCatching { m.setFlag(Flags.Flag.SEEN, true) }
            val shown = if (markRead && writable) body.copy(header = body.header.copy(seen = true)) else body
            keep(key, shown, raw)
            shown
        }
    }

    /**
     * Reads a few small messages ahead (the newest unread ones), so they open instantly. Nothing is marked read and
     * nothing from the internet is loaded; the copies live in memory only.
     */
    suspend fun prefetch(accountId: String, folderName: String, uids: List<Long>) {
        val todo = uids.filter { bodies.get(bodyKey(accountId, folderName, it)) == null }
        if (todo.isEmpty()) return
        withStore(accountId, Lane.SYNC) { a, link ->
            val f = link.folder(folderName, write = false)
            val msgs = f.getMessagesByUID(todo.toLongArray()).filterNotNull().filterIsInstance<IMAPMessage>()
            val sizes = FetchProfile().apply {
                add(FetchProfile.Item.SIZE)
                add(UIDFolder.FetchProfileItem.UID)
            }
            f.fetch(msgs.toTypedArray<Message>(), sizes)
            msgs.filter { runCatching { it.size }.getOrDefault(-1) in 0..PREFETCH_MAX }.forEach { m ->
                runCatching {
                    val (body, raw) = loadBody(a, f, m)
                    val key = bodyKey(a.id, folderName, f.getUID(m))
                    // Opened (or flagged) meanwhile: that copy is newer than this one.
                    if (bodies.get(key) == null) keep(key, body, raw)
                }
            }
        }
    }

    private class Collected {
        var html: String? = null
        var text: String? = null
        val attachments = mutableListOf<Attachment>()
        val inlineImages = mutableMapOf<String, String>()
        var inlineBytes = 0L
    }

    private fun collect(p: Part, path: String, out: Collected) {
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as? Multipart ?: return
            for (i in 0 until mp.count) {
                collect(mp.getBodyPart(i), if (path.isEmpty()) "${i + 1}" else "$path.${i + 1}", out)
            }
            return
        }
        val type = runCatching { ContentType(p.contentType).baseType.lowercase() }.getOrDefault("application/octet-stream")
        val cid = contentIdOf(p)
        val size = runCatching { p.size.toLong() }.getOrDefault(-1L)
        when {
            !isAttachment(p) && type == "text/html" && out.html == null -> out.html = readText(p)
            !isAttachment(p) && type == "text/plain" && out.text == null -> out.text = readText(p)
            !isAttachment(p) && type.startsWith("text/") -> Unit // other text versions (calendar, enriched): ignored
            else -> {
                val name = runCatching { p.fileName }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: if (type == "message/rfc822") "message.eml" else "attachment"
                val isInline = cid != null && type.startsWith("image/") && !Part.ATTACHMENT.equals(runCatching { p.disposition }.getOrNull(), true)
                out.attachments += Attachment(path, name, type, size, cid, isInline)
                // Pictures inside the message are fetched right away (small ones), so the message shows complete.
                if (isInline && cid != null && size in 0..INLINE_MAX && out.inlineBytes + size <= INLINE_TOTAL_MAX) {
                    runCatching {
                        val bytes = p.inputStream.use { it.readLimited(INLINE_MAX) }
                        out.inlineBytes += bytes.size
                        out.inlineImages[cid] = "data:$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
            }
        }
    }

    private fun InputStream.readLimited(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw MailException(str(R.string.error_too_big))
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** A text part as a string, even when it names a character set the phone doesn't know. */
    private fun readText(p: Part): String {
        val raw = try {
            p.inputStream.use { it.readLimited(TEXT_MAX) }
        } catch (e: MailException) {
            return str(R.string.message_too_long)
        } catch (e: Exception) {
            // An encoding the phone can't undo: show the text as it came, rather than nothing.
            val rawStream = (p as? MimeBodyPart)?.rawInputStream ?: (p as? MimeMessage)?.rawInputStream ?: return ""
            try {
                rawStream.use { it.readLimited(TEXT_MAX) }
            } catch (e2: MailException) {
                return str(R.string.message_too_long)
            }
        }
        val charset = runCatching { ContentType(p.contentType).getParameter("charset") }.getOrNull()
            ?.let { runCatching { Charset.forName(MimeUtility.javaCharset(it)) }.getOrNull() }
            ?: Charsets.UTF_8
        return String(raw, charset)
    }

    /** Finds the part at [path] ("2", "1.3"; empty for a single-part message). */
    private fun resolve(m: Message, path: String): Part {
        var part: Part = m
        if (path.isEmpty()) return part
        for (seg in path.split('.')) {
            val mp = part.content as? Multipart ?: throw MailException(str(R.string.error_attachment_gone))
            val i = seg.toIntOrNull()?.minus(1) ?: throw MailException(str(R.string.error_attachment_gone))
            if (i !in 0 until mp.count) throw MailException(str(R.string.error_attachment_gone))
            part = mp.getBodyPart(i)
        }
        return part
    }

    /** Writes [part] to a new file in MailCtrl's cache. */
    private fun writePart(part: Part, att: Attachment): File {
        val dir = File(context.cacheDir, "attachments/${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, safeFileName(att.name))
        try {
            file.outputStream().use { out ->
                val content = if (part.isMimeType("message/rfc822")) part.content else null
                if (content is MimeMessage) {
                    content.writeTo(out)
                } else {
                    part.inputStream.use { it.copyTo(out) }
                }
            }
        } catch (e: Exception) {
            // Don't leave half a file behind.
            runCatching { dir.deleteRecursively() }
            throw e
        }
        return file
    }

    /**
     * Downloads an attachment into MailCtrl's cache, to open or save it. For a small message opened a moment ago
     * it comes from the copy in memory, with no download at all.
     */
    suspend fun download(accountId: String, folderName: String, uid: Long, att: Attachment): File {
        raws.get(bodyKey(accountId, folderName, uid))?.let { raw ->
            val local = withContext(Dispatchers.IO) {
                runCatching { writePart(resolve(MimeMessage(parseSession, ByteArrayInputStream(raw)), att.path), att) }.getOrNull()
            }
            if (local != null) return local
        }
        return withStore(accountId) { _, link ->
            val f = link.folder(folderName, write = false)
            val m = f.getMessageByUID(uid) ?: throw MailException(str(R.string.error_message_gone))
            writePart(resolve(m, att.path), att)
        }
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').take(120).ifBlank { "attachment" }

    // ------------------------------------------------------------------ changes

    private fun IMAPFolder.byUids(uids: Collection<Long>): Array<Message> =
        getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()

    /** Keeps the messages in memory in step with a change, so opening one again shows it right. */
    private fun keptFlag(accountId: String, folderName: String, uids: Collection<Long>, flag: Flags.Flag, value: Boolean) {
        uids.forEach { uid ->
            val key = bodyKey(accountId, folderName, uid)
            val b = bodies.get(key) ?: return@forEach
            val h = when (flag) {
                Flags.Flag.SEEN -> b.header.copy(seen = value)
                Flags.Flag.FLAGGED -> b.header.copy(flagged = value)
                Flags.Flag.ANSWERED -> b.header.copy(answered = value)
                else -> return@forEach
            }
            bodies.put(key, b.copy(header = h))
        }
    }

    private fun forgetKept(accountId: String, folderName: String, uids: Collection<Long>) {
        uids.forEach { uid ->
            val key = bodyKey(accountId, folderName, uid)
            bodies.remove(key)
            raws.remove(key)
        }
    }

    suspend fun setFlag(accountId: String, folderName: String, uids: Collection<Long>, flag: Flags.Flag, value: Boolean) {
        if (uids.isEmpty()) return
        withStore(accountId) { _, link ->
            val f = link.folder(folderName, write = true)
            // Messages removed meanwhile are skipped; with none left there's nothing to change.
            val msgs = f.byUids(uids)
            if (msgs.isEmpty()) return@withStore
            f.setFlags(msgs, Flags(flag), value)
        }
        keptFlag(accountId, folderName, uids, flag, value)
    }

    suspend fun markAllRead(accountId: String, folderName: String): Unit = withStore(accountId) { _, link ->
        val f = link.folder(folderName, write = true)
        val unread = f.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
        if (unread.isNotEmpty()) f.setFlags(unread, Flags(Flags.Flag.SEEN), true)
    }

    /** Moves messages to another folder of the same account. */
    suspend fun move(accountId: String, folderName: String, uids: Collection<Long>, target: String) {
        if (uids.isEmpty() || folderName == target) return
        withStore(accountId) { _, link ->
            val f = link.folder(folderName, write = true)
            val msgs = f.byUids(uids)
            if (msgs.isEmpty()) return@withStore
            val dest = link.store.getFolder(target)
            if (f.can("MOVE")) {
                f.moveMessages(msgs, dest)
            } else {
                f.copyMessages(msgs, dest)
                f.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
                expunge(f, msgs)
            }
        }
        forgetKept(accountId, folderName, uids)
    }

    private fun expunge(f: IMAPFolder, msgs: Array<Message>) {
        if (f.can("UIDPLUS")) f.expunge(msgs) else f.expunge()
    }

    /** Deletes messages: to the Trash folder, or for good when they're already there (or there is no Trash). */
    suspend fun delete(accountId: String, folderName: String, uids: Collection<Long>) {
        if (uids.isEmpty()) return
        val trash = accounts.get(accountId)?.trashFolder
        if (trash != null && trash != folderName) {
            move(accountId, folderName, uids, trash)
            return
        }
        withStore(accountId) { _, link ->
            val f = link.folder(folderName, write = true)
            val msgs = f.byUids(uids)
            if (msgs.isEmpty()) return@withStore
            f.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
            expunge(f, msgs)
        }
        forgetKept(accountId, folderName, uids)
    }

    /** Deletes everything in a folder for good (Trash or Junk). */
    suspend fun emptyFolder(accountId: String, folderName: String): Unit = withStore(accountId) { _, link ->
        val f = link.folder(folderName, write = true)
        val msgs = f.messages
        if (msgs.isNotEmpty()) {
            f.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
            f.expunge()
        }
    }

    // ------------------------------------------------------------------ new mail

    /**
     * Unread messages in the inbox that arrived after [lastUid]. The first time (no [uidValidity] noted yet, or the
     * server renumbered its messages) it only notes where the inbox is, so turning notifications on doesn't report
     * old mail. An inbox that was empty then is noted as UID 0, so its first message is still reported.
     */
    suspend fun checkInbox(accountId: String, lastUid: Long, uidValidity: Long): NewMailResult = withStore(accountId, Lane.SYNC) { a, link ->
        val f = link.folder("INBOX", write = false)
        val validity = f.uidValidity
        val total = f.messageCount
        val unread = runCatching { f.unreadMessageCount }.getOrDefault(0).coerceAtLeast(0)
        val newest = if (total > 0) f.getUID(f.getMessage(total)) else 0L
        if (uidValidity == 0L || validity != uidValidity) {
            return@withStore NewMailResult(emptyList(), newest, validity, unread)
        }
        if (newest <= lastUid) return@withStore NewMailResult(emptyList(), lastUid, validity, unread)
        // "n:*" always includes the newest message, even below n: filter by UID as well.
        val msgs = f.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID).filterNotNull().toTypedArray()
        f.fetch(msgs, listProfile)
        val fresh = msgs.mapNotNull { header(a.id, f, it) }.filter { it.uid > lastUid && !it.seen }.sortedBy { it.uid }
        NewMailResult(fresh.takeLast(20), maxOf(lastUid, newest), validity, unread)
    }

    // ------------------------------------------------------------------ sending

    /** A message that names its own Message-ID: random, under your own domain, without the app's name in it. */
    private class OutgoingMime(session: Session, private val domain: String) : MimeMessage(session) {
        override fun updateMessageID() {
            setHeader("Message-ID", "<${UUID.randomUUID()}@$domain>")
        }
    }

    private fun build(session: Session, a: Account, out: OutgoingMessage): MimeMessage {
        val domain = a.email.substringAfterLast('@').ifBlank { "localhost" }
        val m = OutgoingMime(session, domain)
        m.setFrom(InternetAddress(a.email, a.senderName.ifBlank { null }, "UTF-8"))
        fun list(l: List<Address>) = l.map { InternetAddress(it.email, it.name.ifBlank { null }, "UTF-8") }.toTypedArray<javax.mail.Address>()
        if (out.to.isNotEmpty()) m.setRecipients(Message.RecipientType.TO, list(out.to))
        if (out.cc.isNotEmpty()) m.setRecipients(Message.RecipientType.CC, list(out.cc))
        if (out.bcc.isNotEmpty()) m.setRecipients(Message.RecipientType.BCC, list(out.bcc))
        m.setSubject(out.subject, "UTF-8")
        m.sentDate = Date()
        out.inReplyTo?.let { m.setHeader("In-Reply-To", it) }
        out.references?.let { m.setHeader("References", MimeUtility.fold(12, it)) }
        if (out.attachments.isEmpty()) {
            m.setText(out.body, "UTF-8")
        } else {
            val mp = MimeMultipart("mixed")
            mp.addBodyPart(MimeBodyPart().apply { setText(out.body, "UTF-8") })
            out.attachments.forEach { att ->
                mp.addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(att.bytes, att.mimeType.ifBlank { "application/octet-stream" }))
                        fileName = att.name
                        disposition = Part.ATTACHMENT
                    },
                )
            }
            m.setContent(mp)
        }
        m.saveChanges()
        return m
    }

    /**
     * Sends [out] from account [accountId]. Returns false when it was sent but the copy for the Sent folder
     * couldn't be saved (the mail itself is gone either way).
     */
    suspend fun send(accountId: String, out: OutgoingMessage): Boolean = withContext(Dispatchers.IO) {
        val a = accounts.get(accountId) ?: throw MailException(str(R.string.error_no_account))
        val password = passwordOf(a)
        val trust = ServerTrust(a.smtp.host, a.trustedCerts)
        val (session, proto) = session(a.smtp, smtp = true, trust = trust, from = a.email)
        val mime = try {
            build(session, a, out)
        } catch (e: Exception) {
            throw MailException(str(R.string.error_build), e)
        }
        val t = session.getTransport(proto)
        try {
            t.connect(a.smtp.host, a.smtp.port, a.smtp.username, password)
            t.sendMessage(mime, mime.allRecipients)
        } catch (e: Exception) {
            throw translate(e, trust)
        } finally {
            runCatching { t.close() }
        }
        if (!a.saveSent) return@withContext true
        val sent = a.sentFolder ?: return@withContext false
        runCatching {
            // On the sync connection, which has no folder open, so appending needs no extra sign-in.
            withStore(accountId, Lane.SYNC) { _, link ->
                val f = link.store.getFolder(sent)
                mime.setFlag(Flags.Flag.SEEN, true)
                f.appendMessages(arrayOf<Message>(mime))
            }
        }.isSuccess
    }

    /** The bytes of an attachment, for forwarding it. */
    suspend fun attachmentBytes(accountId: String, folderName: String, uid: Long, att: Attachment): ByteArray {
        val file = download(accountId, folderName, uid, att)
        // Reading the file is disk work: off the main thread, wherever this was called from.
        return withContext(Dispatchers.IO) {
            try {
                if (file.length() > FORWARD_MAX) throw MailException(str(R.string.error_too_big))
                file.readBytes()
            } finally {
                runCatching { file.parentFile?.deleteRecursively() }
            }
        }
    }

    companion object {
        private const val INLINE_MAX = 3L * 1024 * 1024
        private const val INLINE_TOTAL_MAX = 12L * 1024 * 1024
        private const val TEXT_MAX = 6L * 1024 * 1024
        private const val FORWARD_MAX = 25L * 1024 * 1024

        /** A connection used this recently is taken to still work, without asking the server. */
        private const val TRUST_MS = 60_000L

        /** How long an unused connection stays signed in. */
        private const val KEEP_MS = 5 * 60_000L

        /** More new messages than this since the last refresh: start the list over instead. */
        private const val MAX_NEW = 300

        /** Messages up to this size are downloaded whole in one request when opened. */
        private const val WHOLE_MAX = 900 * 1024

        /** Only messages up to this size are read ahead. */
        private const val PREFETCH_MAX = 300 * 1024

        /** Memory for opened messages (characters) and whole small messages (bytes). */
        private const val BODY_CACHE_BYTES = 8 * 1024 * 1024
        private const val RAW_CACHE_BYTES = 12 * 1024 * 1024

        /** Pictures, styles or frames from the internet in an HTML message. */
        val REMOTE = Regex("""(?i)(\bsrc\s*=|\bsrcset\s*=|\bbackground\s*=|\bposter\s*=|url\s*\(|@import)\s*["']?\s*(https?:)?//""")

        private fun unsubscribeLink(header: String): String? {
            val links = Regex("<([^>]+)>").findAll(header).map { it.groupValues[1].trim() }.toList()
            return links.firstOrNull { it.startsWith("https://", true) } ?: links.firstOrNull { it.startsWith("mailto:", true) }
        }
    }
}

/** Something went wrong talking to a mail server; [message] is for people. */
class MailException(message: String, cause: Throwable? = null) : Exception(message, cause)
