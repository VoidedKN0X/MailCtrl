package com.vknxworks.mailctrl.pgp

import org.bouncycastle.bcpg.ArmoredOutputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * ASCII armor ("-----BEGIN PGP …-----"), read by MailCtrl itself so pasted, mailed and hand-edited keys still
 * open: text around the block, CRLF or LF, indented lines, armor headers or none, and with or without the
 * checksum line all work.
 */
object Armor {
    /** One armored block: what it says it is ("PUBLIC KEY BLOCK", "MESSAGE", …) and its binary content. */
    class Block(val type: String, val data: ByteArray)

    private val BEGIN = Regex("^-----BEGIN PGP ([A-Z0-9 ,/]+)-----$")
    private val HEADER = Regex("^[A-Za-z][A-Za-z0-9-]*:.*$")
    private val BASE64 = Regex("[^A-Za-z0-9+/=]")

    /** Whether [bytes] look like armored text rather than binary OpenPGP data. */
    fun isArmored(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 64 * 1024), Charsets.ISO_8859_1)
        return head.contains("-----BEGIN PGP ")
    }

    /**
     * Every armored block in [text], in order. Cleartext-signed messages ("SIGNED MESSAGE") are skipped: their
     * text isn't base64 (see [Cleartext]). Quoted blocks ("> -----BEGIN …") are left alone on purpose.
     */
    fun blocks(text: String): List<Block> {
        val lines = text.split("\r\n", "\n", "\r")
        val out = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val m = BEGIN.matchEntire(lines[i].trim())
            if (m == null) {
                i++
                continue
            }
            val type = m.groupValues[1]
            val end = "-----END PGP $type-----"
            var j = i + 1
            val body = mutableListOf<String>()
            while (j < lines.size && lines[j].trim() != end) {
                body += lines[j].trim()
                j++
            }
            if (j >= lines.size) break // no END line: not a usable block
            if (type != "SIGNED MESSAGE") decode(body)?.let { out += Block(type, it) }
            i = j + 1
        }
        return out
    }

    /** The base64 lines of one block: armor headers (before the first blank line) and the checksum dropped. */
    private fun decode(body: List<String>): ByteArray? {
        var k = 0
        // Headers ("Version: …", "Comment: …") come first; base64 never contains a colon.
        while (k < body.size && body[k].isNotEmpty() && HEADER.matches(body[k])) k++
        val data = StringBuilder()
        for (line in body.drop(k)) {
            if (line.isEmpty()) continue
            // "=AbCd": the optional CRC-24 checksum (base64 lines never start with '=').
            if (line.startsWith("=")) break
            data.append(line.replace(BASE64, ""))
        }
        if (data.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(data.toString()) }.getOrNull()
    }

    /**
     * The binary content of [bytes]: decoded from armor when it's armored (the first block of one of [types],
     * or any block when [types] is empty), or as is.
     */
    fun binary(bytes: ByteArray, vararg types: String): ByteArray {
        if (!isArmored(bytes)) return bytes
        val blocks = blocks(String(bytes, Charsets.UTF_8))
        val block = if (types.isEmpty()) blocks.firstOrNull() else blocks.firstOrNull { it.type in types }
        return block?.data ?: throw PgpFailure(PgpFailure.Kind.NOT_PGP)
    }

    /** Armors binary OpenPGP data. No "Version" or "Comment" header, so nothing says which app made it. */
    fun armor(data: ByteArray): String {
        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream.builder().clearHeaders().build(out)
        armored.write(data)
        armored.close()
        return String(out.toByteArray(), Charsets.US_ASCII)
    }
}
