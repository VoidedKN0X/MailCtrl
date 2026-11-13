package com.vknxworks.mailctrl.pgp

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Web Key Directory (WKD): asks the recipient's own mail domain for their public key over HTTPS. Only when you tap
 * "Look up key"; it tells that domain which address you looked up.
 */
object Wkd {
    private const val ALPHABET = "ybndrfg8ejkmcpqxot1uwisza345h769"
    private const val MAX_BYTES = 512 * 1024
    private const val TIMEOUT_MS = 10_000

    /** z-base-32, as WKD uses it for the hashed local part. */
    fun zbase32(data: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    /** Only ASCII letters are lower-cased (as the WKD draft says), so other scripts hash the same everywhere. */
    private fun asciiLower(s: String) = String(CharArray(s.length) { i -> s[i].let { if (it in 'A'..'Z') it + 32 else it } })

    /** The hashed local part: z-base-32 of the SHA-1 of the lower-cased local part. */
    fun hash(localPart: String): String =
        zbase32(MessageDigest.getInstance("SHA-1").digest(asciiLower(localPart).toByteArray(Charsets.UTF_8)))

    /** The addresses to try, in order: the advanced method (openpgpkey. subdomain), then the direct one. */
    fun urls(email: String): List<String> {
        val at = email.lastIndexOf('@')
        require(at > 0 && at < email.length - 1) { "Not an address" }
        val local = email.substring(0, at)
        val domain = asciiLower(email.substring(at + 1).trim())
        require(domain.matches(Regex("[a-z0-9.-]+")) && !domain.startsWith(".") && ".." !in domain) { "Bad domain" }
        val h = hash(local)
        val l = URLEncoder.encode(local, "UTF-8").replace("+", "%20")
        return listOf(
            "https://openpgpkey.$domain/.well-known/openpgpkey/$domain/hu/$h?l=$l",
            "https://$domain/.well-known/openpgpkey/hu/$h?l=$l",
        )
    }

    /** The domain the lookup contacts, for telling the user before it happens. */
    fun domainOf(email: String): String = asciiLower(email.substringAfterLast('@').trim())

    /**
     * Looks the key for [email] up on its domain. Returns the keys found there whose user IDs carry [email], or
     * an empty list. Network errors are thrown. Runs on the calling thread: call it off the main thread.
     */
    fun lookup(email: String): List<org.bouncycastle.openpgp.PGPKeyRing> {
        var lastError: Exception? = null
        var answered = false
        for (url in urls(email)) {
            try {
                val bytes = fetch(url)
                answered = true
                if (bytes == null) continue
                val wanted = email.trim().lowercase()
                val keys = PgpEngine.readKeys(bytes).map { PgpEngine.publicOf(it) }
                    .filter { ring -> ring.publicKey.userIDs.asSequence().any { PgpEngine.emailOf(it) == wanted } }
                if (keys.isNotEmpty()) return keys
            } catch (e: Exception) {
                lastError = e
            }
        }
        // A domain that answered "not found" means there's no key; only when none could be reached is it an error.
        if (!answered) lastError?.let { throw it }
        return emptyList()
    }

    /** One HTTPS GET; null for "not found". Never follows a redirect (it could lead anywhere). */
    private fun fetch(url: String): ByteArray? {
        val conn = URL(url).openConnection() as? HttpsURLConnection ?: return null
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/octet-stream")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw PgpFailure(PgpFailure.Kind.TOO_BIG)
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }
}
