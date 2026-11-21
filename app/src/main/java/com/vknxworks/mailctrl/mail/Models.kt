package com.vknxworks.mailctrl.mail

import kotlinx.serialization.Serializable

/** How the connection to a mail server is protected. */
@Serializable
enum class Security {
    /** TLS from the first byte (IMAP 993, SMTP 465). */
    SSL,

    /** Starts plain, then switches to TLS before signing in (IMAP 143, SMTP 587). Refuses if the server can't. */
    STARTTLS,

    /** No encryption at all. Only for servers on your own network or over a VPN (hydroxide, Proton Mail Bridge). */
    NONE,
}

/** One server (incoming IMAP or outgoing SMTP). */
@Serializable
data class ServerConfig(
    val host: String,
    val port: Int,
    val security: Security,
    /** Sign-in name; usually the email address. */
    val username: String,
)

/** What a folder is for, from the server's special-use flags (RFC 6154) or its name. */
enum class FolderRole { INBOX, SENT, DRAFTS, TRASH, JUNK, ARCHIVE, ALL, FLAGGED, OTHER }

data class FolderInfo(
    /** The server's full name, e.g. "INBOX" or "[Gmail]/Sent Mail". */
    val fullName: String,
    /** The last part of the name, for showing. */
    val name: String,
    val role: FolderRole,
    /** How deep in the folder tree (0 = top level). */
    val depth: Int,
    val unread: Int,
    val total: Int,
    /** False for folders that only hold other folders. */
    val selectable: Boolean,
)

/** One person in From, To or Cc. */
@Serializable
data class Address(val name: String, val email: String) {
    /** The name, or the address when there's no name. */
    val display: String get() = name.ifBlank { email }
}

/** A message in a list: what the server tells us without downloading the message itself. */
data class MessageHeader(
    val accountId: String,
    val folder: String,
    val uid: Long,
    val from: Address?,
    val to: List<Address>,
    val subject: String,
    /** Sent date in epoch millis (received date when the message has none). */
    val date: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val hasAttachments: Boolean,
    /** Size on the server in bytes, or -1 when unknown. */
    val size: Long = -1,
    /** Its Message-ID (without the angle brackets), for putting conversations together; null when unknown. */
    val messageId: String? = null,
    /** The Message-IDs it answers ("References" and "In-Reply-To"). */
    val refs: List<String> = emptyList(),
) {
    val key: String get() = "$accountId/$folder/$uid"
}

/** An attachment (or an inline picture) of an opened message. */
data class Attachment(
    /** Where it sits in the message: part numbers, e.g. "2" or "1.3". */
    val path: String,
    val name: String,
    val mimeType: String,
    /** Size in bytes as the server reports it (encoded, so roughly a third more than the file), or -1. */
    val size: Long,
    /** For pictures shown inside the message (cid: links). */
    val contentId: String?,
    val isInline: Boolean,
)

/** A message as opened: headers, text and attachments. */
data class MessageBody(
    val header: MessageHeader,
    val cc: List<Address>,
    val bcc: List<Address>,
    val replyTo: List<Address>,
    val messageId: String?,
    val references: String?,
    /** The HTML version, if the message has one. */
    val html: String?,
    /** The plain-text version, if the message has one. */
    val text: String?,
    val attachments: List<Attachment>,
    /** Inline pictures (cid:) already downloaded, as data: URIs, keyed by content id. */
    val inlineImages: Map<String, String>,
    /** The message has pictures or other content from the internet that isn't loaded yet. */
    val hasRemoteContent: Boolean,
    /** "List-Unsubscribe" link, if there is one (shown, never followed by itself). */
    val unsubscribe: String?,
    /** OpenPGP content still to decrypt or check, if the message has any. */
    val pgp: PgpPayload? = null,
    /** The sender's "Autocrypt" header (their public key), if there is one. */
    val autocrypt: String? = null,
    /** This is the decrypted content of an encrypted message (it's kept in memory only). */
    val decrypted: Boolean = false,
    /** It was encrypted with S/MIME (so an answer is encrypted with S/MIME too). */
    val smimeEncrypted: Boolean = false,
)

/** OpenPGP in a received message, as found when it was opened. */
sealed interface PgpPayload {
    /** Bytes kept, for memory accounting. */
    val size: Int

    /** PGP/MIME encrypted (RFC 3156): the encrypted message. */
    class Encrypted(val data: ByteArray) : PgpPayload {
        override val size get() = data.size
    }

    /** PGP/MIME signed: the first part exactly as signed, and the signature. */
    class Signed(val content: ByteArray, val signature: ByteArray) : PgpPayload {
        override val size get() = content.size + signature.size
    }

    /** An encrypted block ("-----BEGIN PGP MESSAGE-----") in the plain text. */
    class InlineEncrypted(val text: String) : PgpPayload {
        override val size get() = text.length
    }

    /** A cleartext-signed text ("-----BEGIN PGP SIGNED MESSAGE-----"), in its own character set. */
    class InlineSigned(val text: String, val charset: String) : PgpPayload {
        override val size get() = text.length
    }

    /** S/MIME encrypted (`smime.p7m`, enveloped-data): the encrypted message. */
    class SmimeEncrypted(val data: ByteArray) : PgpPayload {
        override val size get() = data.size
    }

    /** S/MIME signed (`multipart/signed` with `smime.p7s`): the first part exactly as signed, and the signature. */
    class SmimeSigned(val content: ByteArray, val signature: ByteArray) : PgpPayload {
        override val size get() = content.size + signature.size
    }

    /** S/MIME opaque signed (`smime.p7m`, signed-data): the message is inside the signature. */
    class SmimeOpaque(val data: ByteArray) : PgpPayload {
        override val size get() = data.size
    }
}

/** Something to send. */
data class OutgoingMessage(
    val to: List<Address>,
    val cc: List<Address>,
    val bcc: List<Address>,
    val subject: String,
    val body: String,
    val attachments: List<OutgoingAttachment>,
    /** The message this answers (Message-ID), for threading in the other person's mail app. */
    val inReplyTo: String? = null,
    val references: String? = null,
    /** Encrypt and/or sign with OpenPGP (PGP/MIME). */
    val pgp: com.vknxworks.mailctrl.pgp.PgpOutgoing? = null,
    /** Encrypt and/or sign with S/MIME instead (RFC 8551). */
    val smime: com.vknxworks.mailctrl.smime.SmimeOutgoing? = null,
    /** "Autocrypt" header value with your public key, when the account sends it. */
    val autocrypt: String? = null,
    /** The address to send from when it isn't the account's own (one of its identities). */
    val from: String? = null,
    /** With encryption: the real subject travels inside the encrypted part, and the outside says "...". */
    val protectSubject: Boolean = false,
)

data class OutgoingAttachment(val name: String, val mimeType: String, val bytes: ByteArray)

/** A certificate the system doesn't trust (self-signed, a private CA): shown so you can decide to trust it. */
class UntrustedCertificateException(
    val host: String,
    /** SHA-256 of the server's certificate, as hex. */
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val validUntil: Long,
) : Exception("The server's certificate isn't trusted")

/** Signing in failed: wrong username or password (or an app password is needed). */
class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A line break or another control character. None may ever reach a mail header or an SMTP command: a line
 * break there starts a new header or command of the writer's choosing (header and SMTP injection).
 */
fun String.hasControlChars(): Boolean = any { it.isISOControl() }

/** The same text with every control character (line breaks, tabs, ...) turned into a space. */
fun String.withoutControlChars(): String =
    if (!hasControlChars()) this else String(CharArray(length) { i -> if (this[i].isISOControl()) ' ' else this[i] })

/**
 * A header value taken from a received message (Message-ID, References) on one line: folded lines are joined
 * and any other control character is dropped.
 */
fun String.unfoldedHeader(): String =
    if (!hasControlChars()) this else replace(HEADER_FOLD, " ").filterNot { it.isISOControl() }

private val HEADER_FOLD = Regex("\\r?\\n[ \\t]+")
