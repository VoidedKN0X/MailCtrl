package com.vknxworks.mailctrl.data.smime

import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.data.drafts.Draft
import com.vknxworks.mailctrl.mail.MailException
import com.vknxworks.mailctrl.smime.CertInfo
import com.vknxworks.mailctrl.smime.Pkcs12
import com.vknxworks.mailctrl.smime.SmimeEngine
import com.vknxworks.mailctrl.smime.SmimeFailure
import com.vknxworks.mailctrl.smime.SmimeIdentity
import com.vknxworks.mailctrl.smime.SmimeOutgoing
import com.vknxworks.mailctrl.smime.SmimeSigStatus
import com.vknxworks.mailctrl.smime.SmimeVerified
import com.vknxworks.mailctrl.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x509.Certificate
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

/** An S/MIME signature on a message, as shown. */
class SmimeSigView(
    val status: SmimeSigStatus,
    /** The signer's certificate, when it was in the message. */
    val signer: CertInfo?,
    /** It leads to a certificate authority the phone trusts (Android's own list, and the ones you added to it). */
    val trusted: Boolean,
    /** The signer's certificate, to keep for encrypting to them; null when it's already on the phone or can't encrypt. */
    val offer: Certificate?,
)

/** A failed S/MIME step, said for people. */
fun SmimeFailure.friendly(): String = str(
    when (kind) {
        SmimeFailure.Kind.NOT_SMIME -> R.string.smime_error_not_smime
        SmimeFailure.Kind.BAD_PASSWORD -> R.string.smime_error_password
        SmimeFailure.Kind.NO_KEY_IN_FILE -> R.string.smime_error_no_key
        SmimeFailure.Kind.NOT_FOR_YOU -> R.string.smime_error_not_for_you
        SmimeFailure.Kind.UNSUPPORTED -> R.string.smime_error_unsupported
        SmimeFailure.Kind.BROKEN -> R.string.smime_error_broken
        SmimeFailure.Kind.TOO_BIG -> R.string.smime_error_too_big
        SmimeFailure.Kind.CANT_ENCRYPT_TO -> R.string.smime_error_cant_encrypt_to
    },
)

/**
 * S/MIME for the app: checking signatures (and whether the phone trusts who signed), decrypting with your
 * certificates, keeping the certificates of people who signed, and making what's sent.
 */
class SmimeService(private val store: SmimeStore) {

    // ------------------------------------------------------------------ certificates

    /** Reads a .p12/.pfx file with its password. The password is wiped once used. */
    suspend fun readIdentity(bytes: ByteArray, password: CharArray): SmimeIdentity = withContext(Dispatchers.Default) {
        try {
            Pkcs12.read(bytes, password)
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun importOwn(id: SmimeIdentity): CertInfo = store.importOwn(id)

    /** Certificates in a .cer/.crt/.pem file. */
    suspend fun readCerts(bytes: ByteArray): List<Certificate> = withContext(Dispatchers.Default) { SmimeEngine.readCerts(bytes) }

    suspend fun importContacts(certs: List<Certificate>): List<CertInfo> = store.importContacts(certs)

    suspend fun delete(fingerprint: String) = store.delete(fingerprint)

    // ------------------------------------------------------------------ reading

    /** Checks a signature: detached over [content], or opaque ([content] null, the message inside). */
    suspend fun verify(content: ByteArray?, signature: ByteArray): Pair<SmimeSigView, ByteArray?> = withContext(Dispatchers.Default) {
        store.load()
        val v: SmimeVerified = SmimeEngine.verify(content, signature)
        val signer = v.signer
        val info = signer?.let(SmimeEngine::info)
        val trusted = v.status == SmimeSigStatus.VALID && signer != null && trusted(signer, v.certs)
        val offer = signer?.takeIf { v.status == SmimeSigStatus.VALID && info != null && info.canEncrypt && store.get(info.fingerprint) == null }
        SmimeSigView(v.status, info, trusted, offer) to v.content
    }

    /** Decrypts with your certificates: the inner MIME part. */
    suspend fun decrypt(data: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        store.load()
        val ids = store.identities()
        if (ids.isEmpty()) throw SmimeFailure(SmimeFailure.Kind.NOT_FOR_YOU)
        SmimeEngine.decrypt(data, ids)
    }

    /**
     * Keeps the certificate of someone whose signature checked out, for encrypting to them later: done by itself
     * when the phone trusts it and it's for the sender's own address; otherwise only when you tap Keep.
     */
    suspend fun keep(cert: Certificate) = store.importContacts(listOf(cert))

    /** Whether [cert] leads, through the certificates in the message, to a certificate authority the phone trusts. */
    private fun trusted(cert: Certificate, others: List<Certificate>): Boolean = runCatching {
        val anchors = anchors() ?: return false
        val cf = CertificateFactory.getInstance("X.509")
        fun x509(c: Certificate) = cf.generateCertificate(ByteArrayInputStream(c.encoded)) as X509Certificate
        val leaf = x509(cert)
        val pool = others.map(::x509)
        val anchorSubjects = anchors.map { it.trustedCert.subjectX500Principal }.toSet()
        // The path from the signer up to (not including) a trusted authority, from the certificates in the message.
        val path = mutableListOf(leaf)
        while (path.size < 8) {
            val last = path.last()
            if (last.issuerX500Principal in anchorSubjects) break
            if (last.issuerX500Principal == last.subjectX500Principal) break
            val next = pool.firstOrNull { it.subjectX500Principal == last.issuerX500Principal && it !in path } ?: break
            path += next
        }
        if (path.last().issuerX500Principal !in anchorSubjects) return false
        val params = PKIXParameters(anchors).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(path), params)
        true
    }.getOrDefault(false)

    @Volatile
    private var anchorCache: Set<TrustAnchor>? = null

    /** The phone's trusted certificate authorities (Android's list and the ones you added in Settings). */
    private fun anchors(): Set<TrustAnchor>? {
        anchorCache?.let { return it }
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val set = ks.aliases().toList().mapNotNull { (ks.getCertificate(it) as? X509Certificate)?.let { c -> TrustAnchor(c, null) } }.toSet()
        if (set.isEmpty()) return null
        anchorCache = set
        return set
    }

    // ------------------------------------------------------------------ sending

    /** The account's own certificate with its key, when it has one that's still on the phone. */
    fun own(account: Account): SmimeIdentity? = account.smimeCert?.let(store::get)?.identity

    /** Recipients of [draft] with no certificate to encrypt to. */
    fun missingCerts(draft: Draft): List<String> =
        (draft.to + draft.cc + draft.bcc).map { it.email.trim().lowercase() }.distinct().filter { store.forEmail(it).isEmpty() }

    /** What [draft] needs to be sent encrypted and/or signed with S/MIME, or null when it isn't. */
    fun outgoing(account: Account, draft: Draft): SmimeOutgoing? {
        if (!draft.smime || (!draft.encrypt && !draft.sign)) return null
        val own = own(account) ?: throw MailException(str(R.string.smime_error_no_own_cert))
        val ownInfo = SmimeEngine.info(own.cert)
        if (draft.sign && (!ownInfo.canSign || ownInfo.expired())) throw MailException(str(R.string.smime_error_own_cert_unusable))
        val to = if (draft.encrypt) {
            val missing = missingCerts(draft)
            if (missing.isNotEmpty()) throw MailException(str(R.string.smime_error_missing_certs, missing.joinToString(", ")))
            if (!ownInfo.canEncrypt) throw MailException(str(R.string.smime_error_own_cert_unusable))
            // Encrypted to yourself too, so you can read it in Sent.
            (draft.to + draft.cc + draft.bcc).map { it.email.trim().lowercase() }.distinct().map { store.forEmail(it).first().cert } + own.cert
        } else {
            emptyList()
        }
        return SmimeOutgoing(if (draft.sign) own else null, to)
    }
}
