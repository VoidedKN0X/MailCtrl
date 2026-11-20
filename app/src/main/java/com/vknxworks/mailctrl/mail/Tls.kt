package com.vknxworks.mailctrl.mail

import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Checks a mail server's certificate: either the phone trusts it (a public certificate authority, or one you
 * installed on the phone) and it's made out to the server's name, or it's one you chose to trust before, matched
 * by its exact fingerprint (self-signed certificates such as Proton Mail Bridge's).
 *
 * A certificate that fails is remembered in [rejected], so MailCtrl can show it and ask.
 *
 * Use [forServer]: one checker per server for the whole run of the app, so connections after the first resume the
 * earlier secure session (a shorter handshake) instead of starting from scratch.
 */
class ServerTrust(private val host: String, private val pinned: Collection<String>) : X509TrustManager {
    private val system: X509TrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** The server's certificate, when it was refused. */
    @Volatile
    var rejected: X509Certificate? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Not a server")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate")
        // A certificate you trusted yourself: exactly this one, whatever name it carries (Bridge's says 127.0.0.1).
        if (fingerprint(leaf) in pinned) return
        try {
            system.checkServerTrusted(chain, authType)
            if (!matchesHost(leaf, host)) throw CertificateException("Certificate isn't for $host")
        } catch (e: CertificateException) {
            rejected = leaf
            throw e
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    /** One TLS context per checker: its session cache is what lets later connections resume. */
    private val factory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(this), null)
        ctx.socketFactory
    }

    fun socketFactory(): SSLSocketFactory = factory

    /** Forgets a refusal from an earlier connection, before trying again. */
    fun clearRejection() {
        rejected = null
    }

    /** What to show about a refused certificate, or null when the failure wasn't about the certificate. */
    fun rejection(): UntrustedCertificateException? = rejected?.let { cert ->
        UntrustedCertificateException(
            host = host,
            fingerprint = fingerprint(cert),
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            validUntil = cert.notAfter.time,
        )
    }

    companion object {
        private val shared = ConcurrentHashMap<String, ServerTrust>()

        /**
         * The checker for [host] with exactly these trusted certificates. A resumed session is only possible with the
         * same checker, so trusting or forgetting a certificate (a different set) always starts with a full check.
         */
        fun forServer(host: String, pinned: Collection<String>): ServerTrust {
            val set = pinned.toSortedSet()
            val key = host.trim().lowercase(Locale.ROOT) + "|" + set.joinToString(",")
            return shared.getOrPut(key) { ServerTrust(host, set) }
        }

        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

        /** "AB:CD:…" in pairs, for reading out loud and comparing. */
        fun pretty(fingerprint: String): String = fingerprint.chunked(2).joinToString(":")

        /** The name check browsers do: the subject alternative names, with one-label wildcards. */
        fun matchesHost(cert: X509Certificate, host: String): Boolean {
            val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
            val names = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
            // Literal addresses only (no name lookup): 192.168.1.10 or an IPv6 address.
            val isIp = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h) ||
                (h.contains(':') && h.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' })
            if (isIp) {
                val target = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
                return names.any { it.size >= 2 && it[0] == 7 && runCatching { InetAddress.getByName(it[1] as String) }.getOrNull() == target }
            }
            val dns = names.filter { it.size >= 2 && it[0] == 2 }.mapNotNull { (it[1] as? String)?.lowercase(Locale.ROOT)?.trimEnd('.') }
            val candidates = dns.ifEmpty {
                // Very old certificates only name the server in the subject's CN.
                Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1)?.let { listOf(it.lowercase(Locale.ROOT)) }.orEmpty()
            }
            return candidates.any { pattern ->
                when {
                    pattern == h -> true
                    pattern.startsWith("*.") -> {
                        val suffix = pattern.substring(1) // ".example.com"
                        // Never a wildcard for a whole top-level domain ("*.com").
                        suffix.count { it == '.' } >= 2 &&
                            h.endsWith(suffix) && h.length > suffix.length && !h.substring(0, h.length - suffix.length).contains('.')
                    }
                    else -> false
                }
            }
        }
    }
}
