package com.vknxworks.mailctrl.smime

import com.vknxworks.mailctrl.pgp.PgpMime
import com.vknxworks.mailctrl.pgp.PgpMime.readLimited
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import javax.activation.DataHandler
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.ContentType
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimePart
import javax.mail.util.ByteArrayDataSource

/**
 * How S/MIME messages (RFC 8551) are put together and taken apart. Only JavaMail, no Android code.
 *
 * - Signed: `multipart/signed` (protocol `application/pkcs7-signature`) with the message part exactly as signed and
 *   its signature (`smime.p7s`).
 * - Encrypted: the whole message is one `application/pkcs7-mime; smime-type=enveloped-data` part (`smime.p7m`).
 * - Opaque signed (some Outlook setups): `application/pkcs7-mime; smime-type=signed-data`, the message inside the
 *   signature.
 */
object SmimeMime {
    private const val SIGNATURE_TYPES = "application/pkcs7-signature application/x-pkcs7-signature"
    private val MIME_TYPES = setOf("application/pkcs7-mime", "application/x-pkcs7-mime")

    /** Whether [type] (a Content-Type, lower case) is an S/MIME signature part, which isn't a file for you to open. */
    fun isSignaturePart(type: String): Boolean = type.substringBefore(';').trim() in SIGNATURE_TYPES.split(' ')

    /** Whether a message's top-level Content-Type means it's needed whole (encrypted or opaque signed). */
    fun isWholeMessageType(type: String): Boolean = type.substringBefore(';').trim().lowercase() in MIME_TYPES

    /** `multipart/signed`: [content] (from [PgpMime.serialize]) exactly as signed, then the DER [signature]. */
    fun signed(content: ByteArray, signature: ByteArray): MimeMultipart {
        val mp = PgpMime.PgpMultipart("signed", listOf("protocol" to "application/pkcs7-signature", "micalg" to "sha-256"))
        mp.addBodyPart(PgpMime.ExactPart(content))
        mp.addBodyPart(
            MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(signature, "application/pkcs7-signature"))
                setHeader("Content-Type", "application/pkcs7-signature; name=\"smime.p7s\"")
                setHeader("Content-Transfer-Encoding", "base64")
                setHeader("Content-Disposition", "attachment; filename=\"smime.p7s\"")
                setHeader("Content-Description", "S/MIME Cryptographic Signature")
            },
        )
        return mp
    }

    /** The bytes of a signed multipart as one MIME part, to encrypt (signed first, then encrypted). */
    fun serializeSigned(mp: MimeMultipart): ByteArray {
        val part = PgpMime.Inner()
        part.setContent(mp)
        return PgpMime.serialize(part)
    }

    /** Makes [part] (the whole message) the encrypted `smime.p7m`, holding the DER [envelope]. */
    fun putEncrypted(part: MimePart, envelope: ByteArray) {
        part.dataHandler = DataHandler(ByteArrayDataSource(envelope, "application/pkcs7-mime"))
        part.setHeader("Content-Type", "application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"")
        part.setHeader("Content-Transfer-Encoding", "base64")
        part.setHeader("Content-Disposition", "attachment; filename=\"smime.p7m\"")
        part.setHeader("Content-Description", "S/MIME Encrypted Message")
    }

    /** What a received message turned out to be. */
    sealed interface Found {
        class Encrypted(val data: ByteArray) : Found

        class Signed(val boundary: String, val signature: ByteArray) : Found

        /** Signed with the message inside the signature. */
        class Opaque(val data: ByteArray) : Found
    }

    /** Finds S/MIME in [part]: at the top, or inside a multipart (a mailing list may wrap it with a footer). */
    fun find(part: Part, depth: Int = 0): Found? {
        if (depth > 8) return null
        val ct = runCatching { ContentType(part.contentType) }.getOrNull() ?: return null
        val base = ct.baseType.lowercase()
        val name = (ct.getParameter("name") ?: runCatching { part.fileName }.getOrNull()).orEmpty().lowercase()
        if (base in MIME_TYPES || (base == "application/octet-stream" && name.endsWith(".p7m"))) {
            val data = part.inputStream.use { it.readLimited() }
            val kind = ct.getParameter("smime-type")?.lowercase()
            val oid = SmimeEngine.kindOf(data)
            return when {
                oid == CMSObjectIdentifiers.signedData -> Found.Opaque(data)
                oid == CMSObjectIdentifiers.envelopedData || oid == CMSObjectIdentifiers.authEnvelopedData -> Found.Encrypted(data)
                // Damaged, but labelled as an encrypted message: shown as one that can't be decrypted.
                kind == "enveloped-data" || kind == "authenveloped-data" -> Found.Encrypted(data)
                else -> null
            }
        }
        if (!part.isMimeType("multipart/*")) return null
        val mp = part.content as? Multipart ?: return null
        val protocol = ct.getParameter("protocol")?.trim()?.lowercase()
        if (ct.match("multipart/signed") && protocol != null && isSignaturePart(protocol) && mp.count >= 2) {
            val boundary = ct.getParameter("boundary") ?: return null
            return Found.Signed(boundary, mp.getBodyPart(1).inputStream.use { it.readLimited() })
        }
        // Only look inside mixed multiparts for a whole S/MIME part when it's the first one: an attached .p7m file
        // further down is an attachment, not this message.
        for (i in 0 until mp.count) {
            val child = mp.getBodyPart(i)
            if (i > 0 && !child.isMimeType("multipart/*")) continue
            find(child, depth + 1)?.let { return it }
        }
        return null
    }
}
