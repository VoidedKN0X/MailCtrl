package com.vknxworks.mailctrl.pgp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.activation.DataHandler
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.ContentType
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * PGP/MIME (RFC 3156): how encrypted and signed messages are put together and taken apart. Only JavaMail, no
 * Android code, so it's tested on the computer.
 *
 * - Encrypted: `multipart/encrypted` with a "Version: 1" part and the encrypted message (`encrypted.asc`).
 * - Signed: `multipart/signed` with the message part exactly as signed, and its signature (`signature.asc`).
 */
object PgpMime {
    /** Encrypted or signed parts bigger than this aren't read. */
    const val MAX_PART_BYTES = 64L * 1024 * 1024

    /** The message part MailCtrl encrypts or signs: built like any part, then written out once with [serialize]. */
    class Inner : MimeBodyPart() {
        fun finish() = updateHeaders()
    }

    /**
     * A part written out byte for byte as it was signed. JavaMail would otherwise rebuild its headers and could
     * re-encode its content, and the signature would no longer match.
     */
    private class ExactPart(private val bytes: ByteArray) : MimeBodyPart(ByteArrayInputStream(bytes)) {
        override fun writeTo(os: OutputStream) {
            os.write(bytes)
        }

        override fun updateHeaders() = Unit
    }

    /** A multipart with the extra Content-Type parameters PGP/MIME needs (protocol, micalg). */
    private class PgpMultipart(subtype: String, params: List<Pair<String, String>>) : MimeMultipart(subtype) {
        init {
            val ct = ContentType(contentType)
            params.forEach { (k, v) -> ct.setParameter(k, v) }
            contentType = ct.toString()
        }
    }

    /** [part] as the bytes that get encrypted or signed: headers and content, with CRLF line ends. */
    fun serialize(part: Inner): ByteArray {
        part.finish()
        val out = ByteArrayOutputStream()
        part.writeTo(out)
        return canonical(out.toByteArray())
    }

    /** Every line ending as CRLF, as OpenPGP signatures over MIME parts expect. */
    fun canonical(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size + bytes.size / 32)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            when (b) {
                '\r'.code.toByte() -> {
                    out.write('\r'.code)
                    out.write('\n'.code)
                    if (i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) i++
                }
                '\n'.code.toByte() -> {
                    out.write('\r'.code)
                    out.write('\n'.code)
                }
                else -> out.write(b.toInt())
            }
            i++
        }
        return out.toByteArray()
    }

    private fun part(content: ByteArray, type: String, description: String, disposition: String? = null) = MimeBodyPart().apply {
        dataHandler = DataHandler(ByteArrayDataSource(content, type))
        setHeader("Content-Type", type)
        setHeader("Content-Description", description)
        if (disposition != null) setHeader("Content-Disposition", disposition)
        setHeader("Content-Transfer-Encoding", "7bit")
    }

    /** `multipart/signed`: [content] (from [serialize]) exactly as signed, then its armored [signature]. */
    fun signed(content: ByteArray, signature: ByteArray, micalg: String = "pgp-sha256"): MimeMultipart {
        val mp = PgpMultipart("signed", listOf("micalg" to micalg, "protocol" to "application/pgp-signature"))
        mp.addBodyPart(ExactPart(content))
        mp.addBodyPart(
            part(
                canonical(signature),
                "application/pgp-signature; name=\"signature.asc\"",
                "OpenPGP digital signature",
                "attachment; filename=\"signature.asc\"",
            ),
        )
        return mp
    }

    /** `multipart/encrypted`: the version part, then the armored encrypted message. */
    fun encrypted(armored: ByteArray): MimeMultipart {
        val mp = PgpMultipart("encrypted", listOf("protocol" to "application/pgp-encrypted"))
        mp.addBodyPart(part("Version: 1\r\n".toByteArray(Charsets.US_ASCII), "application/pgp-encrypted", "PGP/MIME version identification"))
        mp.addBodyPart(
            part(
                canonical(armored),
                "application/octet-stream; name=\"encrypted.asc\"",
                "OpenPGP encrypted message",
                "inline; filename=\"encrypted.asc\"",
            ),
        )
        return mp
    }

    /** What a received message turned out to be. */
    sealed interface Found {
        /** PGP/MIME encrypted: the encrypted message. */
        class Encrypted(val data: ByteArray) : Found

        /** PGP/MIME signed: its boundary (to find the signed bytes in the raw message) and the signature. */
        class Signed(val boundary: String, val signature: ByteArray) : Found
    }

    /** Finds PGP/MIME in [part]: at the top, or inside a multipart (a mailing list may wrap it with a footer). */
    fun find(part: Part, depth: Int = 0): Found? {
        if (depth > 8 || !part.isMimeType("multipart/*")) return null
        val ct = runCatching { ContentType(part.contentType) }.getOrNull() ?: return null
        val mp = part.content as? Multipart ?: return null
        val protocol = ct.getParameter("protocol")?.trim()?.lowercase()
        if (ct.match("multipart/encrypted") && protocol == "application/pgp-encrypted" && mp.count >= 2) {
            return Found.Encrypted(mp.getBodyPart(1).inputStream.use { it.readLimited() })
        }
        if (ct.match("multipart/signed") && protocol == "application/pgp-signature" && mp.count >= 2) {
            val boundary = ct.getParameter("boundary") ?: return null
            return Found.Signed(boundary, mp.getBodyPart(1).inputStream.use { it.readLimited() })
        }
        for (i in 0 until mp.count) find(mp.getBodyPart(i), depth + 1)?.let { return it }
        return null
    }

    /**
     * The first part of a `multipart/signed` with [boundary], exactly as it is in [raw] (the whole message as
     * received): from after the first boundary line to just before the line break that comes before the next one.
     * That's what the sender signed (RFC 3156, section 5).
     */
    fun signedContent(raw: ByteArray, boundary: String): ByteArray? {
        val text = String(raw, Charsets.ISO_8859_1)
        val delimiter = "--$boundary"
        fun lineStarts(): Sequence<Int> = sequence {
            yield(0)
            var i = text.indexOf('\n')
            while (i >= 0) {
                yield(i + 1)
                i = text.indexOf('\n', i + 1)
            }
        }
        fun lineAt(start: Int): String {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            return text.substring(start, end).trimEnd('\r', ' ', '\t')
        }
        val starts = lineStarts().toList()
        val first = starts.indexOfFirst { lineAt(it) == delimiter }
        if (first < 0) return null
        val contentStart = starts.getOrNull(first + 1) ?: return null
        val next = starts.drop(first + 1).firstOrNull { s -> lineAt(s).let { it == delimiter || it == "$delimiter--" } } ?: return null
        // The line break before the next boundary belongs to the boundary, not to the content.
        var end = next - 1 // the '\n'
        if (end > contentStart && text[end - 1] == '\r') end--
        if (end < contentStart) return ByteArray(0)
        return canonical(text.substring(contentStart, end).toByteArray(Charsets.ISO_8859_1))
    }

    private fun InputStream.readLimited(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_PART_BYTES) throw PgpFailure(PgpFailure.Kind.TOO_BIG)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
