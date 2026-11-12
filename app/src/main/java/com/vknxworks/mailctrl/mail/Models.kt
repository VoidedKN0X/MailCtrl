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
)

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
