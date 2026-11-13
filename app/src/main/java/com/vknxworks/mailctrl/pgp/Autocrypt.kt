package com.vknxworks.mailctrl.pgp

import java.util.Base64
import java.util.Locale

/**
 * The Autocrypt header (autocrypt.org, level 1): a mail program puts your public key in every message you send,
 * so the people you write to can encrypt their answer. MailCtrl only sends it when you turn it on for an account,
 * and only imports a key from it when you tap Import.
 */
object Autocrypt {
    /** A parsed header: the address it's for, and the key. */
    class Header(val addr: String, val preferEncrypt: Boolean, val keyData: ByteArray)

    /** Reads an "Autocrypt:" header value. Null when it's malformed or uses an attribute MailCtrl doesn't know. */
    fun parse(value: String): Header? {
        val unfolded = value.replace("\r", "").replace("\n", "")
        var addr: String? = null
        var keyData: String? = null
        var mutual = false
        for (piece in unfolded.split(';')) {
            val eq = piece.indexOf('=')
            if (eq <= 0) continue
            val name = piece.substring(0, eq).trim().lowercase(Locale.ROOT)
            val v = piece.substring(eq + 1).trim()
            when (name) {
                "addr" -> addr = v.trim('<', '>', ' ').lowercase(Locale.ROOT)
                "keydata" -> keyData = v
                "prefer-encrypt" -> mutual = v.equals("mutual", ignoreCase = true)
                // Unknown "critical" attributes make the whole header invalid (the spec says so); "_x" ones don't.
                else -> if (!name.startsWith("_")) return null
            }
        }
        val a = addr?.takeIf { '@' in it } ?: return null
        val k = keyData ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(k.filterNot { it.isWhitespace() }) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > PgpEngine.MAX_KEY_BYTES) return null
        return Header(a, mutual, bytes)
    }

    /** The header value for [addr] with the public key [keyData], folded into lines of 76 characters. */
    fun build(addr: String, keyData: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(keyData)
        return "addr=$addr; keydata=" + b64.chunked(76).joinToString("") { "\r\n $it" }
    }
}
