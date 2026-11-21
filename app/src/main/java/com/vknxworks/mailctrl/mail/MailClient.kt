package com.vknxworks.mailctrl.mail

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Base64
import android.util.LruCache
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import com.sun.mail.imap.IMAPStore
import com.sun.mail.imap.protocol.BODY as BodyItem
import com.sun.mail.imap.protocol.FLAGS as FlagsItem
import com.sun.mail.imap.protocol.FetchResponse
import com.sun.mail.imap.protocol.RFC822SIZE
import com.sun.mail.imap.protocol.Status
import com.sun.mail.imap.protocol.UID as UidItem
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.data.accounts.AccountStore
import com.vknxworks.mailctrl.data.pgp.friendly
import com.vknxworks.mailctrl.pgp.PgpEngine
import com.vknxworks.mailctrl.pgp.PgpFailure
import com.vknxworks.mailctrl.pgp.PgpMime
import com.vknxworks.mailctrl.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
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
import javax.mail.internet.MailDateFormat
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

/** A message's flags as the server has them now, and its size in bytes (-1 when not asked). */
data class FlagState(val seen: Boolean, val flagged: Boolean, val answered: Boolean, val size: Long = -1)

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

/** What the speed test measured, in milliseconds (see [MailClient.speedTest]). */
data class SpeedReport(
    val lookupMs: Long,
    val signInMs: Long,
    val roundTripMs: Long,
    val openInboxMs: Long,
    val listMs: Long,
    val listed: Int,
    /** Downloading the newest message; null when it's too big for the test or the inbox is empty. */
    val openMs: Long?,
    val openKb: Int?,
    /** The server compresses the connection. */
    val compressed: Boolean,
    /** The connection is encrypted (SSL/TLS or STARTTLS). */
    val encrypted: Boolean,
)

/** Which connection an action uses: background syncing never makes you wait to open a message. */
enum class Lane {
    /** Refreshing lists, folders, new-mail checks and downloading ahead. The folder stays open, read-only. */
    SYNC,

    /** What you do yourself: opening, flagging, moving. The folder stays open, for changes too. */
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
class MailClient(private val context: Context, private val accounts: AccountStore, private val store: MessageStore) {

    /** Messages are kept on the phone ("Keep messages on this phone"), so downloading ahead keeps many of them. */
    val keepsCopies: Boolean get() = store.enabled

    /** One signed-in IMAP connection, and the folder it has open. */
    private class Link(val store: IMAPStore, val signature: String, val forChanges: Boolean) {
        @Volatile var lastUsed = System.currentTimeMillis()

        /** The folder left open after the last action, so the next action in it needs no extra round trip. */
        @Volatile var current: IMAPFolder? = null

        /**
         * Whether [current] was opened for changes. Noted here because asking the folder (its mode) sends a NOOP
         * to the server, and fails outright once the server has closed it.
         */
        @Volatile var writable = false
            private set

        /**
         * [name] opened for reading, or for changes too when [write]. The folder stays open, so the next action in
         * the same folder needs no extra round trip to the server (opening and closing a folder are one each).
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
                forChanges -> try {
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

        /** After an action: notes the time (the folder stays open for the next one). */
        fun done() {
            lastUsed = System.currentTimeMillis()
        }

        /**
         * Closes the open folder before asking the server about folders in general (the list, counts): with a
         * folder open, JavaMail would sign in a second connection for that.
         */
        fun release() {
            current?.let { f -> runCatching { f.close(false) } }
            current = null
            writable = false
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
                    if (before != null && before != network) {
                        closeAll()
                        onNetworkChanged?.invoke()
                    }
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
            // Compress everything after signing in, when the server offers it (Gmail, Fastmail, Dovecot and most
            // others): lists and messages are several times smaller, which is most of the wait on mobile data.
            p["$k.compress.enable"] = "true"
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
        val trust = ServerTrust.forServer(a.imap.host, a.trustedCerts).also { it.clearRejection() }
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
                    val link = Link(connect(a, passwordOf(a)), signature(a), forChanges = lane == Lane.USER)
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

    /** Called after the phone moved to another network and the old connections were closed (set by the app). */
    @Volatile
    var onNetworkChanged: (() -> Unit)? = null

    /**
     * Signs in ahead of time, so the first thing you tap doesn't wait for it: each account's [Lane.USER] connection
     * (refreshing the inbox signs in the other one). Connections already up are left as they are; while the app lock
     * keeps passwords sealed, nothing happens.
     */
    suspend fun warmUp(accountIds: List<String>) = coroutineScope {
        accountIds.forEach { id -> launch { runCatching { withStore(id, Lane.USER) { _, _ -> } } } }
    }

    /**
     * How long each step with the server takes, measured on a separate, fresh connection: finding the server,
     * signing in, one round trip, opening the inbox, listing its newest messages and downloading the newest one.
     * Nothing is changed and nothing is marked read.
     */
    suspend fun speedTest(accountId: String): SpeedReport = withContext(Dispatchers.IO) {
        val a = accounts.get(accountId) ?: throw MailException(str(R.string.error_no_account))
        val password = passwordOf(a)
        fun <T> timed(block: () -> T): Pair<T, Long> {
            val start = SystemClock.elapsedRealtime()
            val result = block()
            return result to SystemClock.elapsedRealtime() - start
        }
        val lookup = timed { runCatching { InetAddress.getAllByName(a.imap.host) } }.second
        val (store, signIn) = timed { connect(a, password) }
        try {
            val compressed = runCatching { store.hasCapability("COMPRESS=DEFLATE") }.getOrDefault(false)
            val f = store.getFolder("INBOX") as IMAPFolder
            val openInbox = timed { f.open(Folder.READ_ONLY) }.second
            // The middle of three, so one slow answer doesn't skew it.
            val roundTrip = (1..3).map { timed { f.doCommand(IMAPFolder.ProtocolCommand { p -> p.noop(); null }) }.second }.sorted()[1]
            val total = f.messageCount
            val (msgs, list) = timed {
                if (total <= 0) {
                    emptyArray<Message>()
                } else {
                    f.getMessages(maxOf(1, total - SPEED_LIST + 1), total).also { f.fetch(it, listProfile) }
                }
            }
            var openMs: Long? = null
            var openKb: Int? = null
            (msgs.lastOrNull() as? IMAPMessage)?.let { m ->
                val (bytes, ms) = timed {
                    f.fetch(arrayOf<Message>(m), openProfile)
                    val size = runCatching { m.size }.getOrDefault(-1)
                    if (size in 0..WHOLE_MAX) ByteArrayOutputStream(size + 1024).also { m.writeTo(it) }.size() else -1
                }
                if (bytes >= 0) {
                    openMs = ms
                    openKb = (bytes + 1023) / 1024
                }
            }
            runCatching { f.close(false) }
            SpeedReport(
                lookupMs = lookup,
                signInMs = signIn,
                roundTripMs = roundTrip,
                openInboxMs = openInbox,
                listMs = list,
                listed = msgs.size,
                openMs = openMs,
                openKb = openKb,
                compressed = compressed,
                encrypted = a.imap.security != Security.NONE,
            )
        } catch (e: Exception) {
            throw translate(e, null)
        } finally {
            runCatching { store.close() }
        }
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
        val trust = ServerTrust.forServer(a.smtp.host, a.trustedCerts).also { it.clearRejection() }
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
        val list = withStore(accountId, Lane.SYNC) { _, link ->
            link.release()
            listFolders(link.store)
        }
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
        // Which messages it answers, for conversations (In-Reply-To and Message-ID come with the envelope).
        add("References")
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
        add("Autocrypt")
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
            size = runCatching { m.size.toLong() }.getOrDefault(-1L),
            messageId = runCatching { (m as? MimeMessage)?.messageID }.getOrNull()?.let { Threads.ids(it, 1).firstOrNull() },
            refs = runCatching { threadRefs(m) }.getOrDefault(emptyList()),
        )
    }.getOrNull()

    /**
     * The Message-IDs [m] answers. Only what the list request already brought: "References" (asked for with it)
     * and "In-Reply-To" (part of the envelope), so this never costs a request per message.
     */
    private fun threadRefs(m: Message): List<String> {
        val references = m.getHeader("References")?.joinToString(" ")
        val inReplyTo = (m as? IMAPMessage)?.inReplyTo
        return (Threads.ids(references) + Threads.ids(inReplyTo, 3)).distinct().takeLast(20)
    }

    private fun flagState(m: Message) = FlagState(
        seen = m.isSet(Flags.Flag.SEEN),
        flagged = m.isSet(Flags.Flag.FLAGGED),
        answered = m.isSet(Flags.Flag.ANSWERED),
    )

    private fun flagState(f: Flags?, size: Long = -1) = FlagState(
        seen = f?.contains(Flags.Flag.SEEN) == true,
        flagged = f?.contains(Flags.Flag.FLAGGED) == true,
        answered = f?.contains(Flags.Flag.ANSWERED) == true,
        size = size,
    )

    /**
     * One "UID FETCH [uids] ([items])", answered in a single round trip. (JavaMail would split the same work into
     * several requests: finding the messages, then their flags, then their contents.) Everything else the server
     * said meanwhile (new or removed messages, flags changed elsewhere) still reaches the folder. Only answers that
     * carry a UID are returned: a server may also report changes to other messages on its own.
     */
    private fun IMAPFolder.uidFetch(uids: String, items: String): List<FetchResponse> {
        val answers = doCommand(
            IMAPFolder.ProtocolCommand { p ->
                val r = p.command("UID FETCH $uids ($items)", null)
                p.notifyResponseHandlers(r)
                p.handleResult(r[r.size - 1])
                r.filterIsInstance<FetchResponse>().filter { it.getItem(UidItem::class.java) != null }
            },
        )
        @Suppress("UNCHECKED_CAST")
        return answers as List<FetchResponse>
    }

    private fun FetchResponse.uid(): Long = getItem(UidItem::class.java)?.uid ?: -1L

    /** The whole message from a "BODY[]" answer, or null. */
    private fun FetchResponse.wholeMessage(): ByteArray? =
        getItem(BodyItem::class.java)?.takeIf { it.section.isNullOrEmpty() && it.origin <= 0 }?.byteArray?.newBytes

    private fun hasAttachments(p: Part): Boolean {
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as? Multipart ?: return false
            return (0 until mp.count).any { hasAttachments(mp.getBodyPart(it)) }
        }
        return isAttachment(p)
    }

    private fun isAttachment(p: Part): Boolean {
        // A PGP/MIME signature isn't a file for you to open.
        if (p.isMimeType("application/pgp-signature") || p.isMimeType("application/pgp-encrypted")) return false
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
        syncList(accountId, folderName, known, count).also { withContext(Dispatchers.IO) { keptSync(accountId, folderName, it) } }

    /**
     * Keeps the messages in memory in step with what [sync] found: changed flags (read on another device), messages
     * gone from the server, and after a renumbering none at all, since a number may now be a different message.
     */
    private fun keptSync(accountId: String, folderName: String, sync: FolderSync) {
        if (sync.reset) {
            val prefix = bodyKey(accountId, folderName, 0).removeSuffix("0")
            bodies.snapshot().keys.filter { it.startsWith(prefix) }.forEach { bodies.remove(it) }
            raws.snapshot().keys.filter { it.startsWith(prefix) }.forEach { raws.remove(it) }
            // A number may now be a different message: the copies on the phone go too.
            store.clearFolder(accountId, folderName)
            return
        }
        forgetMemory(accountId, folderName, sync.removed)
        store.remove(accountId, folderName, sync.removed)
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
            val min = known.uids.min()
            val have = known.uids.toHashSet()
            // Which messages are there from the oldest the phone has up, with their flags and sizes: one request
            // (it used to be two, plus one to see whether the folder was empty). "n:*" always includes the newest
            // message, even below n: filtered by UID as well.
            val answers = try {
                f.uidFetch("$min:*", "UID FLAGS RFC822.SIZE")
            } catch (e: MessagingException) {
                // Some servers refuse "n:*" in an empty folder.
                if (f.messageCount <= 0) emptyList() else throw e
            }.filter { it.uid() >= min }
            val missing = answers.map { it.uid() }.filter { it !in have }
            // Very far behind (hundreds of new messages): starting over with the newest is quicker.
            if (missing.size > MAX_NEW) return@withStore reset()
            // The folder already knows these messages from the answer above: no request to find them.
            val fresh = if (missing.isEmpty()) {
                emptyArray<Message>()
            } else {
                f.getMessagesByUID(missing.toLongArray()).filterNotNull().filterNot { it.isExpunged }.toTypedArray()
            }
            if (fresh.isNotEmpty()) f.fetch(fresh, listProfile)
            FolderSync(
                uidValidity = validity,
                reset = false,
                fresh = fresh.mapNotNull { header(a.id, f, it) },
                flags = answers.filter { it.uid() in have }.associate {
                    it.uid() to flagState(it.getItem(FlagsItem::class.java), it.getItem(RFC822SIZE::class.java)?.size ?: -1L)
                },
                removed = answers.map { it.uid() }.toHashSet().let { present -> have.filterNot { it in present }.toSet() },
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
            return (text + pictures + (value.pgp?.size ?: 0)).coerceAtLeast(1)
        }
    }

    /**
     * Decrypted PGP/MIME messages, so their attachments can be opened. Memory only, and forgotten when MailCtrl
     * locks, like [bodies]; the decrypted text itself isn't kept here.
     */
    private val decrypteds = object : LruCache<String, ByteArray>(DECRYPTED_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
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
        decrypteds.evictAll()
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
        // OpenPGP signed or encrypted messages are needed whole (up to a limit), whatever their size: the
        // signature covers the exact bytes, and decrypting needs all of it anyway.
        val topType = runCatching { m.contentType }.getOrNull().orEmpty().lowercase()
        val pgpMime = topType.startsWith("multipart/signed") || topType.startsWith("multipart/encrypted")
        val raw = if (size in 0..WHOLE_MAX || (pgpMime && size in 0..PGP_WHOLE_MAX)) {
            runCatching { ByteArrayOutputStream(size + 1024).also { m.writeTo(it) }.toByteArray() }.getOrNull()
        } else {
            null
        }
        val source: MimeMessage = raw?.let { runCatching { MimeMessage(parseSession, ByteArrayInputStream(it)) }.getOrNull() } ?: m
        return bodyFrom(h, source, raw) to raw
    }

    /**
     * A message from its copy as received (downloaded ahead, or kept on the phone): taken apart on the phone, no
     * server needed. The list's details ([known]) fill in what the copy can't tell, like the flags.
     */
    private fun bodyFromRaw(accountId: String, folder: String, uid: Long, raw: ByteArray, known: MessageHeader?, flags: FlagState?): MessageBody {
        val source = MimeMessage(parseSession, ByteArrayInputStream(raw))
        val h = MessageHeader(
            accountId = accountId,
            folder = folder,
            uid = uid,
            from = addresses(runCatching { source.from }.getOrNull()).firstOrNull() ?: known?.from,
            to = addresses(runCatching { source.getRecipients(Message.RecipientType.TO) }.getOrNull()).ifEmpty { known?.to.orEmpty() },
            subject = runCatching { source.subject }.getOrNull()?.trim() ?: known?.subject.orEmpty(),
            date = known?.date?.takeIf { it > 0 } ?: runCatching { source.sentDate?.time }.getOrNull() ?: 0L,
            seen = flags?.seen ?: known?.seen ?: false,
            flagged = flags?.flagged ?: known?.flagged ?: false,
            answered = flags?.answered ?: known?.answered ?: false,
            hasAttachments = known?.hasAttachments ?: runCatching { hasAttachments(source) }.getOrDefault(false),
            size = raw.size.toLong(),
        )
        return bodyFrom(h, source, raw)
    }

    /** A message's text, attachments and pictures, read from [source]: the parsed copy, or the server's message. */
    private fun bodyFrom(h: MessageHeader, source: MimeMessage, raw: ByteArray?): MessageBody {
        val parts = Collected()
        collect(source, "", parts)
        val pgp = pgpPayload(source, raw, parts)
        // Encrypted: the parts are just the version line and the encrypted blob, nothing to show yet.
        if (pgp is PgpPayload.Encrypted) {
            parts.attachments.clear()
            parts.text = null
            parts.html = null
        }
        val html = parts.html
        val from = h.from?.email
        return MessageBody(
            header = h,
            cc = addresses(runCatching { source.getRecipients(Message.RecipientType.CC) }.getOrNull()),
            bcc = addresses(runCatching { source.getRecipients(Message.RecipientType.BCC) }.getOrNull()),
            replyTo = addresses(runCatching { source.replyTo }.getOrNull()).filterNot { it.email.equals(from, ignoreCase = true) },
            messageId = runCatching { source.messageID }.getOrNull(),
            references = runCatching { source.getHeader("References")?.firstOrNull() }.getOrNull(),
            html = html,
            text = parts.text,
            attachments = parts.attachments,
            inlineImages = parts.inlineImages,
            hasRemoteContent = html != null && REMOTE.containsMatchIn(html),
            unsubscribe = runCatching { source.getHeader("List-Unsubscribe")?.firstOrNull() }.getOrNull()?.let { unsubscribeLink(it) },
            pgp = pgp,
            autocrypt = runCatching { source.getHeader("Autocrypt")?.firstOrNull() }.getOrNull()?.take(AUTOCRYPT_MAX),
        )
    }

    /** PGP/MIME (encrypted or signed), or an inline encrypted or signed text, if the message has one. */
    private fun pgpPayload(source: MimeMessage, raw: ByteArray?, parts: Collected): PgpPayload? {
        when (val found = runCatching { PgpMime.find(source) }.getOrNull()) {
            is PgpMime.Found.Encrypted -> return PgpPayload.Encrypted(found.data)
            is PgpMime.Found.Signed -> {
                // The signed bytes have to come from the message exactly as received.
                val content = raw?.let { PgpMime.signedContent(it, found.boundary) }
                if (content != null) return PgpPayload.Signed(content, found.signature)
            }
            null -> Unit
        }
        val text = parts.text ?: return null
        // Only at the start of a line: a block quoted in a reply ("> -----BEGIN …") isn't this message's own.
        return when {
            INLINE_ENCRYPTED.containsMatchIn(text) -> PgpPayload.InlineEncrypted(text)
            INLINE_SIGNED.containsMatchIn(text) -> PgpPayload.InlineSigned(text, parts.textCharset)
            else -> null
        }
    }

    /**
     * A decrypted PGP/MIME message (its inner MIME part), read like any message: text, pictures and attachments.
     * Returns the inner signature to check too, when it was signed and then encrypted as a separate step.
     * The decrypted message is kept in memory only (until MailCtrl locks), so its attachments can be opened.
     */
    fun decryptedBody(original: MessageBody, inner: ByteArray): Pair<MessageBody, PgpPayload.Signed?> {
        val h = original.header
        val firstLine = String(inner, 0, minOf(inner.size, 200), Charsets.ISO_8859_1).substringBefore('\n')
        // Some programs encrypt bare text instead of a MIME part.
        if (!Regex("^[A-Za-z][A-Za-z0-9-]*:").containsMatchIn(firstLine)) {
            return original.copy(text = String(inner, Charsets.UTF_8), html = null, attachments = emptyList(), inlineImages = emptyMap(), hasRemoteContent = false, pgp = null, decrypted = true) to null
        }
        val msg = MimeMessage(parseSession, ByteArrayInputStream(inner))
        val parts = Collected()
        collect(msg, "", parts)
        decrypteds.put(bodyKey(h.accountId, h.folder, h.uid), inner)
        val signed = (runCatching { PgpMime.find(msg) }.getOrNull() as? PgpMime.Found.Signed)?.let { f ->
            PgpMime.signedContent(inner, f.boundary)?.let { PgpPayload.Signed(it, f.signature) }
        }
        // Protected headers: the real subject travels inside the encryption (the outer one can be a placeholder).
        val subject = runCatching { msg.getHeader("Subject")?.let { msg.subject } }.getOrNull()?.trim()
        val html = parts.html
        val body = original.copy(
            header = if (subject.isNullOrEmpty()) h else h.copy(subject = subject),
            html = html,
            text = parts.text,
            attachments = parts.attachments.map { it.copy(path = PGP_PATH + it.path) },
            inlineImages = parts.inlineImages,
            hasRemoteContent = html != null && REMOTE.containsMatchIn(html),
            pgp = null,
            decrypted = true,
        )
        return body to signed
    }

    private fun keep(key: String, body: MessageBody, raw: ByteArray?) {
        bodies.put(key, body)
        if (raw != null) raws.put(key, raw)
    }

    /**
     * Opens one message: its text, attachments and inline pictures. Marks it read when [markRead] (shown at once;
     * the server is told in the background). [known] is the message as the list has it, if at hand.
     *
     * Quickest first: a copy in memory, then the copy kept on the phone (no server at all), then the server, where
     * a small message (most mail) and its flags come in a single request.
     */
    suspend fun open(accountId: String, folderName: String, uid: Long, markRead: Boolean, known: MessageHeader? = null): MessageBody {
        val key = bodyKey(accountId, folderName, uid)
        bodies.get(key)?.let { kept -> return shown(key, kept, markRead) }
        val local = withContext(Dispatchers.IO) {
            store.get(accountId, folderName, uid)?.let { raw -> runCatching { bodyFromRaw(accountId, folderName, uid, raw, known, null) to raw }.getOrNull() }
        }
        if (local != null) {
            keep(key, local.first, local.second)
            return shown(key, local.first, markRead)
        }
        val (body, raw) = withStore(accountId) { a, link ->
            val f = link.folder(folderName, write = false)
            if ((known?.size ?: -1L) in 0..WHOLE_MAX.toLong()) {
                val answer = f.uidFetch("$uid", "UID FLAGS BODY.PEEK[]").firstOrNull { it.uid() == uid }
                    ?: throw MailException(str(R.string.error_message_gone))
                val whole = answer.wholeMessage()
                if (whole != null) {
                    return@withStore bodyFromRaw(a.id, folderName, uid, whole, known, flagState(answer.getItem(FlagsItem::class.java))) to whole
                }
            }
            val m = f.getMessageByUID(uid) as? IMAPMessage ?: throw MailException(str(R.string.error_message_gone))
            loadBody(a, f, m)
        }
        keep(key, body, raw)
        if (raw != null) withContext(Dispatchers.IO) { store.put(accountId, folderName, uid, raw) }
        return shown(key, body, markRead)
    }

    /** [body] as shown: marked read at once when [markRead], and on the server in the background. */
    private fun shown(key: String, body: MessageBody, markRead: Boolean): MessageBody {
        if (!markRead || body.header.seen) return body
        val h = body.header
        val read = body.copy(header = h.copy(seen = true))
        bodies.put(key, read)
        closer.launch {
            runCatching { setFlag(h.accountId, h.folder, listOf(h.uid), Flags.Flag.SEEN, true) }
                // Not marked on the server: the copy in memory says unread again, so the next opening retries.
                .onFailure { keptFlag(h.accountId, h.folder, listOf(h.uid), Flags.Flag.SEEN, false) }
        }
        return read
    }

    /**
     * Downloads messages ahead, so they open without waiting, the way Thunderbird does it: many messages in one
     * request instead of a few requests per message, newest first, a couple of megabytes at a time (so a refresh
     * waits for one batch at most). Kept on the phone when "Keep messages on this phone" is on; otherwise in
     * memory only. Nothing is marked read and nothing from the internet is loaded. Messages already here, bigger
     * than [AHEAD_MAX] (or [PREFETCH_MAX] in memory) or of unknown size are skipped.
     */
    suspend fun prefetch(accountId: String, folderName: String, headers: List<MessageHeader>) {
        val onPhone = store.enabled
        val max = if (onPhone) AHEAD_MAX else PREFETCH_MAX
        val todo = withContext(Dispatchers.IO) {
            headers.filter { h ->
                h.size in 1..max.toLong() && bodies.get(bodyKey(accountId, folderName, h.uid)) == null &&
                    !(onPhone && store.has(accountId, folderName, h.uid))
            }.sortedByDescending { it.uid }
        }
        if (todo.isEmpty()) return
        val batches = mutableListOf<List<MessageHeader>>()
        var batch = mutableListOf<MessageHeader>()
        var bytes = 0L
        for (h in todo) {
            if (batch.isNotEmpty() && (bytes + h.size > BATCH_BYTES || batch.size >= BATCH_COUNT)) {
                batches += batch
                batch = mutableListOf()
                bytes = 0
            }
            batch += h
            bytes += h.size
        }
        if (batch.isNotEmpty()) batches += batch
        for (b in batches) {
            val byUid = b.associateBy { it.uid }
            val got = withStore(accountId, Lane.SYNC) { _, link ->
                val f = link.folder(folderName, write = false)
                f.uidFetch(b.joinToString(",") { it.uid.toString() }, "UID BODY.PEEK[]").mapNotNull { r ->
                    r.wholeMessage()?.let { r.uid() to it }
                }
            }
            withContext(Dispatchers.IO) {
                got.forEach { (uid, raw) ->
                    val h = byUid[uid] ?: return@forEach
                    if (onPhone) {
                        store.put(accountId, folderName, uid, raw)
                    } else {
                        runCatching {
                            val key = bodyKey(accountId, folderName, uid)
                            val body = bodyFromRaw(accountId, folderName, uid, raw, h, null)
                            // Opened (or flagged) meanwhile: that copy is newer than this one.
                            if (bodies.get(key) == null) keep(key, body, raw)
                        }
                    }
                }
            }
        }
    }

    private class Collected {
        var html: String? = null
        var text: String? = null
        /** The plain text's character set, for checking an inline signature over its exact bytes. */
        var textCharset: String = "UTF-8"
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
            // OpenPGP's own parts (a signature, the "Version: 1" line) are handled by MailCtrl, not shown as files.
            type == "application/pgp-signature" || type == "application/pgp-encrypted" -> Unit
            !isAttachment(p) && type == "text/html" && out.html == null -> out.html = readText(p)
            !isAttachment(p) && type == "text/plain" && out.text == null -> {
                out.text = readText(p)
                out.textCharset = charsetOf(p).name()
            }
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
        return String(raw, charsetOf(p))
    }

    private fun charsetOf(p: Part): Charset = runCatching { ContentType(p.contentType).getParameter("charset") }.getOrNull()
        ?.let { runCatching { Charset.forName(MimeUtility.javaCharset(it)) }.getOrNull() }
        ?: Charsets.UTF_8

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
        if (att.path.startsWith(PGP_PATH)) {
            // Inside an encrypted message: only from the decrypted copy in memory.
            val inner = decrypteds.get(bodyKey(accountId, folderName, uid)) ?: throw MailException(str(R.string.pgp_attachment_locked))
            return withContext(Dispatchers.IO) {
                writePart(resolve(MimeMessage(parseSession, ByteArrayInputStream(inner)), att.path.removePrefix(PGP_PATH)), att)
            }
        }
        val kept = raws.get(bodyKey(accountId, folderName, uid)) ?: withContext(Dispatchers.IO) { store.get(accountId, folderName, uid) }
        kept?.let { raw ->
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

    private fun forgetMemory(accountId: String, folderName: String, uids: Collection<Long>) {
        uids.forEach { uid ->
            val key = bodyKey(accountId, folderName, uid)
            bodies.remove(key)
            raws.remove(key)
        }
    }

    /** Messages moved or deleted: their copies in memory and on the phone go. */
    private suspend fun forgetKept(accountId: String, folderName: String, uids: Collection<Long>) {
        forgetMemory(accountId, folderName, uids)
        withContext(Dispatchers.IO) { store.remove(accountId, folderName, uids) }
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
        // The account's own address, or one of its identities.
        val from = a.sender(out.from)
        val domain = from.email.substringAfterLast('@').ifBlank { "localhost" }
        val m = OutgoingMime(session, domain)
        m.setFrom(InternetAddress(from.email, from.name.ifBlank { null }, "UTF-8"))
        fun list(l: List<Address>) = l.map { InternetAddress(it.email, it.name.ifBlank { null }, "UTF-8") }.toTypedArray<javax.mail.Address>()
        if (out.to.isNotEmpty()) m.setRecipients(Message.RecipientType.TO, list(out.to))
        if (out.cc.isNotEmpty()) m.setRecipients(Message.RecipientType.CC, list(out.cc))
        if (out.bcc.isNotEmpty()) m.setRecipients(Message.RecipientType.BCC, list(out.bcc))
        val pgp = out.pgp
        // Encrypted with the subject protected: the outside only says "...", the real one travels inside.
        val hideSubject = out.protectSubject && pgp != null && pgp.encryptTo.isNotEmpty() && out.subject.isNotBlank()
        m.setSubject(if (hideSubject) HIDDEN_SUBJECT else out.subject, "UTF-8")
        m.sentDate = Date()
        // Both come from the message being answered, so they're put on one clean line first.
        out.inReplyTo?.unfoldedHeader()?.takeIf { it.isNotBlank() }?.let { m.setHeader("In-Reply-To", it) }
        out.references?.unfoldedHeader()?.takeIf { it.isNotBlank() }?.let { m.setHeader("References", MimeUtility.fold(12, it)) }
        if (pgp == null) {
            fillContent(m, out, protect = false)
        } else {
            // PGP/MIME: the text and attachments go into one inner part, which is encrypted (and signed inside the
            // encryption), or signed as it is.
            val inner = PgpMime.Inner()
            fillContent(inner, out, protect = true)
            if (hideSubject) {
                // "Protected headers" (draft-autocrypt-lamps-protected-headers): the inner part is marked
                // protected-headers="v1" and carries the real Subject (with From, To and Cc), which Thunderbird,
                // K-9 and MailCtrl itself show instead of the outer "...".
                inner.finish()
                val ct = ContentType(inner.contentType)
                ct.setParameter("protected-headers", "v1")
                inner.setHeader("Content-Type", ct.toString())
                inner.setHeader("Subject", MimeUtility.fold(9, MimeUtility.encodeText(out.subject, "UTF-8", null)))
                inner.setHeader("From", InternetAddress(from.email, from.name.ifBlank { null }, "UTF-8").toString())
                if (out.to.isNotEmpty()) inner.setHeader("To", MimeUtility.fold(4, InternetAddress.toString(list(out.to))))
                if (out.cc.isNotEmpty()) inner.setHeader("Cc", MimeUtility.fold(4, InternetAddress.toString(list(out.cc))))
            }
            val bytes = PgpMime.serialize(inner)
            if (pgp.encryptTo.isNotEmpty()) {
                m.setContent(PgpMime.encrypted(PgpEngine.encrypt(bytes, pgp.encryptTo, pgp.signer)))
            } else {
                val signer = pgp.signer ?: throw PgpFailure(PgpFailure.Kind.NO_SIGNING_KEY)
                m.setContent(PgpMime.signed(bytes, PgpEngine.signDetached(bytes, signer)))
            }
        }
        out.autocrypt?.let { m.setHeader("Autocrypt", it) }
        m.saveChanges()
        return m
    }

    /**
     * The text and attachments, into [part]. [protect]: for a signed or encrypted part, every part gets an
     * encoding no mail server changes on the way (quoted-printable text, base64 files), so signatures still match.
     */
    private fun fillContent(part: MimePart, out: OutgoingMessage, protect: Boolean) {
        fun text(p: MimePart) {
            p.setText(out.body, "UTF-8")
            if (protect) p.setHeader("Content-Transfer-Encoding", "quoted-printable")
        }
        if (out.attachments.isEmpty()) {
            text(part)
        } else {
            val mp = MimeMultipart("mixed")
            mp.addBodyPart(MimeBodyPart().apply { text(this) })
            out.attachments.forEach { att ->
                mp.addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(att.bytes, att.mimeType.ifBlank { "application/octet-stream" }))
                        fileName = att.name
                        disposition = Part.ATTACHMENT
                        if (protect) setHeader("Content-Transfer-Encoding", "base64")
                    },
                )
            }
            part.setContent(mp)
        }
    }

    /**
     * Refuses to send when an address, a name, the subject or an attachment's name holds a line break or
     * another control character. Those end up in the message's headers and in the commands to the mail
     * server, where a line break would let whoever supplied the text (a "mailto:" link, another app's share,
     * a crafted message being answered) add headers, recipients or commands of their own.
     */
    private fun requireCleanHeaders(a: Account, out: OutgoingMessage) {
        val from = a.sender(out.from)
        val values = buildList {
            add(a.email)
            add(a.senderName)
            add(from.email)
            add(from.name)
            (out.to + out.cc + out.bcc).forEach { add(it.email); add(it.name) }
            add(out.subject)
            out.attachments.forEach { add(it.name); add(it.mimeType) }
        }
        if (values.any { it.hasControlChars() }) throw MailException(str(R.string.error_control_chars))
    }

    /**
     * Sends [out] from account [accountId]. Returns false when it was sent but the copy for the Sent folder
     * couldn't be saved (the mail itself is gone either way).
     */
    suspend fun send(accountId: String, out: OutgoingMessage): Boolean = withContext(Dispatchers.IO) {
        val a = accounts.get(accountId) ?: throw MailException(str(R.string.error_no_account))
        requireCleanHeaders(a, out)
        val password = passwordOf(a)
        val trust = ServerTrust.forServer(a.smtp.host, a.trustedCerts).also { it.clearRejection() }
        val (session, proto) = session(a.smtp, smtp = true, trust = trust, from = a.sender(out.from).email)
        val mime = try {
            build(session, a, out)
        } catch (e: PgpFailure) {
            throw MailException(e.friendly(), e)
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

    /**
     * [out] exactly as it will be sent (signed or encrypted now, attachments included), for a message that is
     * sent later: [sendPrepared] sends these bytes. Nothing is sent and no server is contacted.
     */
    suspend fun prepare(accountId: String, out: OutgoingMessage): ByteArray = withContext(Dispatchers.IO) {
        val a = accounts.get(accountId) ?: throw MailException(str(R.string.error_no_account))
        requireCleanHeaders(a, out)
        val trust = ServerTrust.forServer(a.smtp.host, a.trustedCerts)
        val (session, _) = session(a.smtp, smtp = true, trust = trust, from = a.sender(out.from).email)
        try {
            val mime = build(session, a, out)
            ByteArrayOutputStream().also { mime.writeTo(it) }.toByteArray()
        } catch (e: PgpFailure) {
            throw MailException(e.friendly(), e)
        } catch (e: Exception) {
            throw MailException(str(R.string.error_build), e)
        }
    }

    /**
     * Sends a message made by [prepare], with the date of now. Its content isn't touched, so a signature made
     * when it was prepared still matches. Returns false when it was sent but the copy for the Sent folder
     * couldn't be saved.
     */
    suspend fun sendPrepared(accountId: String, raw: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val a = accounts.get(accountId) ?: throw MailException(str(R.string.error_no_account))
        val password = passwordOf(a)
        val trust = ServerTrust.forServer(a.smtp.host, a.trustedCerts).also { it.clearRejection() }
        val sender = runCatching {
            (MimeMessage(parseSession, ByteArrayInputStream(raw)).from?.firstOrNull() as? InternetAddress)?.address
        }.getOrNull()?.takeIf { a.isOwn(it) } ?: a.email
        val (session, proto) = session(a.smtp, smtp = true, trust = trust, from = sender)
        val mime = try {
            MimeMessage(session, ByteArrayInputStream(raw)).apply { setHeader("Date", MailDateFormat().format(Date())) }
        } catch (e: Exception) {
            throw MailException(str(R.string.error_build), e)
        }
        val recipients = mime.allRecipients
        if (recipients == null || recipients.isEmpty()) throw MailException(str(R.string.no_recipient))
        val t = session.getTransport(proto)
        try {
            t.connect(a.smtp.host, a.smtp.port, a.smtp.username, password)
            t.sendMessage(mime, recipients)
        } catch (e: Exception) {
            throw translate(e, trust)
        } finally {
            runCatching { t.close() }
        }
        if (!a.saveSent) return@withContext true
        val sent = a.sentFolder ?: return@withContext false
        runCatching {
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

        /** Messages listed by the speed test. */
        private const val SPEED_LIST = 25

        /** Only messages up to this size are read ahead into memory (with copies on the phone turned off). */
        private const val PREFETCH_MAX = 300 * 1024

        /** Messages up to this size are downloaded ahead and kept on the phone (Thunderbird: 128 KB). */
        private const val AHEAD_MAX = 256 * 1024

        /** Downloading ahead: at most this much, and this many messages, per request. */
        private const val BATCH_BYTES = 2L * 1024 * 1024
        private const val BATCH_COUNT = 40

        /** Memory for opened messages (characters) and whole small messages (bytes). */
        private const val BODY_CACHE_BYTES = 8 * 1024 * 1024
        private const val RAW_CACHE_BYTES = 12 * 1024 * 1024
        private const val DECRYPTED_CACHE_BYTES = 16 * 1024 * 1024

        /** Signed or encrypted messages up to this size are downloaded whole, to check or decrypt them. */
        private const val PGP_WHOLE_MAX = 25 * 1024 * 1024

        /** An Autocrypt header longer than this isn't a key MailCtrl wants (a normal one is a few kilobytes). */
        private const val AUTOCRYPT_MAX = 64 * 1024

        /** What the outside of a message says in place of an encrypted subject (as Thunderbird and K-9 do). */
        const val HIDDEN_SUBJECT = "..."

        /** Attachments inside a decrypted message: their part path, marked so they're read from the decrypted copy. */
        const val PGP_PATH = "pgp:"

        private val INLINE_ENCRYPTED = Regex("(?m)^[ \\t]*-----BEGIN PGP MESSAGE-----")
        private val INLINE_SIGNED = Regex("(?m)^[ \\t]*-----BEGIN PGP SIGNED MESSAGE-----")

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
