package com.vknxworks.mailctrl.smime

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.EncryptedContentInfo
import org.bouncycastle.asn1.cms.EnvelopedData
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber
import org.bouncycastle.asn1.cms.KeyTransRecipientInfo
import org.bouncycastle.asn1.cms.RecipientIdentifier
import org.bouncycastle.asn1.cms.RecipientInfo
import org.bouncycastle.asn1.cms.SignedData
import org.bouncycastle.asn1.cms.SignerIdentifier
import org.bouncycastle.asn1.cms.SignerInfo
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.RSAESOAEPparams
import org.bouncycastle.asn1.pkcs.RSASSAPSSparams
import org.bouncycastle.asn1.smime.SMIMECapabilityVector
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.AsymmetricBlockCipher
import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.Signer
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.encodings.OAEPEncoding
import org.bouncycastle.crypto.encodings.PKCS1Encoding
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.DESedeEngine
import org.bouncycastle.crypto.engines.RSABlindedEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.DSADigestSigner
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.crypto.signers.PSSSigner
import org.bouncycastle.crypto.signers.RSADigestSigner
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.Locale

/** Why an S/MIME step didn't work. */
class SmimeFailure(val kind: Kind) : Exception(kind.name) {
    enum class Kind {
        /** Not S/MIME data (or not the kind that was expected). */
        NOT_SMIME,

        /** A .p12/.pfx file whose password is wrong. */
        BAD_PASSWORD,

        /** A .p12/.pfx file without a private key and its certificate. */
        NO_KEY_IN_FILE,

        /** Encrypted, but to none of your certificates. */
        NOT_FOR_YOU,

        /** Uses an algorithm MailCtrl doesn't do. */
        UNSUPPORTED,

        /** Damaged, or decrypting gave nonsense. */
        BROKEN,

        TOO_BIG,

        /** A recipient's certificate can't be encrypted to (not RSA, or not meant for encrypting). */
        CANT_ENCRYPT_TO,
    }
}

/** What MailCtrl shows and checks about a certificate. */
data class CertInfo(
    /** SHA-256 of the certificate, upper-case hex: how MailCtrl names it. */
    val fingerprint: String,
    /** Its name (CN), or the whole subject when there's none. */
    val name: String,
    /** Its e-mail addresses, lower case. */
    val emails: List<String>,
    /** Who issued it (CN, or the whole issuer). */
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val canSign: Boolean,
    val canEncrypt: Boolean,
    val selfSigned: Boolean,
    /** "RSA 2048", "ECDSA P-256". */
    val algorithm: String,
) {
    fun expired(now: Long = System.currentTimeMillis()) = now > notAfter || now < notBefore
}

/** Your certificate with its private key, as read from a .p12/.pfx file. [chain]: the certificate first. */
class SmimeIdentity(val key: AsymmetricKeyParameter, val keyInfo: PrivateKeyInfo, val chain: List<Certificate>) {
    val cert: Certificate get() = chain.first()
}

/** What a message sent with S/MIME needs: your certificate to sign with, and every certificate to encrypt to. */
class SmimeOutgoing(
    /** Your certificate and key, when the message is signed. */
    val signer: SmimeIdentity?,
    /** Every recipient's certificate, and yours (so you can read your own sent mail). Empty = sign only. */
    val encryptTo: List<Certificate>,
)

enum class SmimeSigStatus {
    /** The signature matches the message and the certificate in it. */
    VALID,

    /** The signature doesn't match: the message was changed, or the signature is forged. */
    BAD,

    /** Signed with an algorithm MailCtrl can't check. */
    UNSUPPORTED,

    /** The signer's certificate isn't in the message, so it can't be checked. */
    NO_CERT,
}

/** What checking an S/MIME signature found. Who it is and whether to believe it is decided by the caller. */
class SmimeVerified(
    val status: SmimeSigStatus,
    val signer: Certificate?,
    /** Every certificate in the signature (the signer's chain, as the sender included it). */
    val certs: List<Certificate>,
    val signingTime: Long?,
    /** For a signature that carries the message inside it (opaque signed): the message. */
    val content: ByteArray?,
)

/**
 * S/MIME (RFC 8551) with CMS (RFC 5652), on Bouncy Castle's lightweight API only: its ASN.1 classes and its
 * crypto engines, no JCA provider and nothing loaded by name. No Android code, so it's tested on the computer.
 *
 * Sends: signatures with SHA-256 (RSA PKCS #1 v1.5 or ECDSA), encryption with AES-256-CBC and the key wrapped
 * for each RSA certificate with PKCS #1 v1.5 (what Thunderbird, Outlook and Apple Mail all read).
 * Reads: signatures with SHA-1/SHA-2 and RSA, RSA-PSS or ECDSA; encryption with AES-CBC or Triple DES, the key
 * wrapped with RSA PKCS #1 v1.5 or RSA-OAEP. Not done: elliptic-curve encryption (key agreement) and AES-GCM
 * (AuthEnvelopedData); those report [SmimeFailure.Kind.UNSUPPORTED].
 */
object SmimeEngine {
    const val MAX_BYTES = 64 * 1024 * 1024

    private val EMAIL_PROTECTION = KeyPurposeId.id_kp_emailProtection
    private val ANY_PURPOSE = KeyPurposeId.anyExtendedKeyUsage

    // ------------------------------------------------------------------ certificates

    fun readCert(der: ByteArray): Certificate = try {
        Certificate.getInstance(parse(der))
    } catch (e: SmimeFailure) {
        throw e
    } catch (e: Exception) {
        throw SmimeFailure(SmimeFailure.Kind.NOT_SMIME)
    }

    /** Certificates in a .cer/.crt/.pem file: DER, or one or more PEM blocks. */
    fun readCerts(bytes: ByteArray): List<Certificate> {
        val text = String(bytes, Charsets.ISO_8859_1)
        if (text.contains("-----BEGIN CERTIFICATE-----")) {
            return PEM.findAll(text).map { m ->
                val b64 = m.groupValues[1].filterNot { it.isWhitespace() }
                readCert(java.util.Base64.getDecoder().decode(b64))
            }.toList().ifEmpty { throw SmimeFailure(SmimeFailure.Kind.NOT_SMIME) }
        }
        return listOf(readCert(bytes))
    }

    fun pem(cert: Certificate): String {
        val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }

    fun fingerprint(cert: Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.getEncoded(ASN1Encoding.DER)).joinToString("") { "%02X".format(it) }

    fun info(cert: Certificate): CertInfo {
        val tbs = cert.tbsCertificate
        val exts = tbs.extensions
        val emails = linkedSetOf<String>()
        runCatching {
            exts?.getExtension(Extension.subjectAlternativeName)?.let { ext ->
                GeneralNames.getInstance(ext.parsedValue).names
                    .filter { it.tagNo == GeneralName.rfc822Name }
                    .forEach { emails += it.name.toString().trim().lowercase(Locale.ROOT) }
            }
        }
        runCatching {
            cert.subject.getRDNs(BCStyle.EmailAddress).forEach { rdn ->
                emails += IETFUtils.valueToString(rdn.first.value).trim().lowercase(Locale.ROOT)
            }
        }
        val ku = runCatching { exts?.getExtension(Extension.keyUsage)?.let { KeyUsage.getInstance(it.parsedValue) } }.getOrNull()
        val eku = runCatching { exts?.getExtension(Extension.extendedKeyUsage)?.let { ExtendedKeyUsage.getInstance(it.parsedValue) } }.getOrNull()
        val forMail = eku == null || eku.hasKeyPurposeId(EMAIL_PROTECTION) || eku.hasKeyPurposeId(ANY_PURPOSE)
        val keyOid = tbs.subjectPublicKeyInfo.algorithm.algorithm
        val rsa = keyOid == PKCSObjectIdentifiers.rsaEncryption
        val ec = keyOid == X9ObjectIdentifiers.id_ecPublicKey
        val algorithm = runCatching {
            when {
                rsa -> "RSA " + (PublicKeyFactory.createKey(tbs.subjectPublicKeyInfo) as RSAKeyParameters).modulus.bitLength()
                ec -> "ECDSA " + (ASN1ObjectIdentifier.getInstance(tbs.subjectPublicKeyInfo.algorithm.parameters)
                    .let { ECNamedCurveTable.getName(it) } ?: "")
                else -> keyOid.id
            }
        }.getOrDefault(keyOid.id).trim()
        return CertInfo(
            fingerprint = fingerprint(cert),
            name = nameOf(cert.subject),
            emails = emails.toList(),
            issuer = nameOf(cert.issuer),
            notBefore = cert.startDate.date.time,
            notAfter = cert.endDate.date.time,
            canSign = forMail && (rsa || ec) && (ku == null || ku.hasUsages(KeyUsage.digitalSignature) || ku.hasUsages(KeyUsage.nonRepudiation)),
            canEncrypt = forMail && rsa && (ku == null || ku.hasUsages(KeyUsage.keyEncipherment)),
            selfSigned = cert.subject == cert.issuer,
            algorithm = algorithm,
        )
    }

    private fun nameOf(name: X500Name): String =
        name.getRDNs(BCStyle.CN).firstOrNull()?.let { IETFUtils.valueToString(it.first.value) } ?: name.toString()

    private fun subjectKeyId(cert: Certificate): ByteArray? = runCatching {
        cert.tbsCertificate.extensions?.getExtension(Extension.subjectKeyIdentifier)?.let { SubjectKeyIdentifier.getInstance(it.parsedValue).keyIdentifier }
    }.getOrNull()

    /** Whether a SignerIdentifier / RecipientIdentifier ([id]: IssuerAndSerialNumber or [0] key ID) names [cert]. */
    private fun names(id: ASN1Encodable?, cert: Certificate): Boolean {
        val prim = id?.toASN1Primitive() ?: return false
        if (prim is ASN1TaggedObject) {
            val ski = subjectKeyId(cert) ?: return false
            return ASN1OctetString.getInstance(prim, false).octets.contentEquals(ski)
        }
        val ias = runCatching { IssuerAndSerialNumber.getInstance(prim) }.getOrNull() ?: return false
        return ias.name == cert.issuer && ias.serialNumber.value == cert.serialNumber.value
    }

    // ------------------------------------------------------------------ signing

    /**
     * A detached signature (DER `ContentInfo` with `SignedData`, no content) over [content], the MIME part exactly as
     * it is sent (CRLF line ends). Carries your certificate chain, so the other side can check it and encrypt to you.
     */
    fun sign(content: ByteArray, id: SmimeIdentity, now: Date = Date()): ByteArray {
        val hash = digest(SHA256Digest(), content)
        val caps = SMIMECapabilityVector().apply {
            addCapability(NISTObjectIdentifiers.id_aes256_CBC)
            addCapability(NISTObjectIdentifiers.id_aes128_CBC)
        }
        val attrs = ASN1EncodableVector().apply {
            add(Attribute(CMSAttributes.contentType, DERSet(CMSObjectIdentifiers.data)))
            add(Attribute(CMSAttributes.signingTime, DERSet(Time(now))))
            add(Attribute(CMSAttributes.messageDigest, DERSet(DEROctetString(hash))))
            add(Attribute(PKCSObjectIdentifiers.pkcs_9_at_smimeCapabilities, DERSet(DERSequence(caps.toASN1EncodableVector()))))
        }
        val signed = DERSet(attrs)
        val toSign = signed.getEncoded(ASN1Encoding.DER)
        val (signer, sigAlg) = when (val key = id.key) {
            is RSAKeyParameters -> RSADigestSigner(SHA256Digest()) to AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE)
            is ECPrivateKeyParameters -> DSADigestSigner(ECDSASigner(HMacDSAKCalculator(SHA256Digest())), SHA256Digest()) to
                AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
            else -> throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        }
        signer.init(true, id.key)
        signer.update(toSign, 0, toSign.size)
        val signature = signer.generateSignature()
        val digestAlg = AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)
        val signerInfo = SignerInfo(
            SignerIdentifier(IssuerAndSerialNumber(id.cert)),
            digestAlg,
            signed,
            sigAlg,
            DEROctetString(signature),
            null as ASN1Set?,
        )
        val sd = SignedData(
            DERSet(digestAlg),
            ContentInfo(CMSObjectIdentifiers.data, null),
            DERSet(ASN1EncodableVector().apply { id.chain.forEach { add(it) } }),
            null as ASN1Set?,
            DERSet(signerInfo),
        )
        return ContentInfo(CMSObjectIdentifiers.signedData, sd).getEncoded(ASN1Encoding.DER)
    }

    // ------------------------------------------------------------------ checking signatures

    /**
     * Checks an S/MIME signature: detached over [content] (the first part of `multipart/signed`, exactly as
     * received), or, with [content] null, an opaque one that carries the message itself.
     */
    fun verify(content: ByteArray?, der: ByteArray): SmimeVerified {
        val ci = contentInfo(der)
        if (ci.contentType != CMSObjectIdentifiers.signedData) throw SmimeFailure(SmimeFailure.Kind.NOT_SMIME)
        val sd = try {
            SignedData.getInstance(ci.content)
        } catch (e: Exception) {
            throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        }
        val inside = (sd.encapContentInfo.content as? ASN1OctetString)?.octets
        val data = content ?: inside ?: throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        val certs = sd.certificates?.toArray()?.mapNotNull { e ->
            val p = e.toASN1Primitive()
            if (p is ASN1Sequence) runCatching { Certificate.getInstance(p) }.getOrNull() else null
        }.orEmpty()
        val infos = sd.signerInfos.toArray().map { SignerInfo.getInstance(it) }
        if (infos.isEmpty()) throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        var best: SmimeVerified? = null
        for (si in infos) {
            val r = verifyOne(si, data, certs, if (content == null) inside else null)
            if (r.status == SmimeSigStatus.VALID) return r
            if (best == null || rank(r.status) < rank(best.status)) best = r
        }
        return best!!
    }

    /** Which result to report when no signer checks out: a bad signature first, it's the one that matters. */
    private fun rank(s: SmimeSigStatus) = when (s) {
        SmimeSigStatus.VALID -> 0
        SmimeSigStatus.BAD -> 1
        SmimeSigStatus.NO_CERT -> 2
        SmimeSigStatus.UNSUPPORTED -> 3
    }

    private fun verifyOne(si: SignerInfo, data: ByteArray, certs: List<Certificate>, inside: ByteArray?): SmimeVerified {
        val signer = certs.firstOrNull { names(si.sid.id, it) }
        val signedAttrs = si.authenticatedAttributes
        val time = signedAttrs?.let { attrValue(it, CMSAttributes.signingTime) }?.let { runCatching { Time.getInstance(it).date.time }.getOrNull() }
        fun result(s: SmimeSigStatus) = SmimeVerified(s, signer, certs, time, inside)
        if (signer == null) return result(SmimeSigStatus.NO_CERT)
        val digestAlg = si.digestAlgorithm.algorithm
        val hash = digest(digestFor(digestAlg) ?: return result(SmimeSigStatus.UNSUPPORTED), data)
        val toVerify: ByteArray = if (signedAttrs != null) {
            val md = attrValue(signedAttrs, CMSAttributes.messageDigest)?.let { runCatching { ASN1OctetString.getInstance(it).octets }.getOrNull() }
                ?: return result(SmimeSigStatus.BAD)
            if (!MessageDigest.isEqual(md, hash)) return result(SmimeSigStatus.BAD)
            signedAttrs.getEncoded(ASN1Encoding.DER)
        } else {
            data
        }
        val pub = runCatching { PublicKeyFactory.createKey(signer.subjectPublicKeyInfo) }.getOrNull() ?: return result(SmimeSigStatus.UNSUPPORTED)
        val sigAlg = si.digestEncryptionAlgorithm
        val v: Signer = verifierFor(sigAlg, digestAlg, pub) ?: return result(SmimeSigStatus.UNSUPPORTED)
        val ok = runCatching {
            v.init(false, pub)
            v.update(toVerify, 0, toVerify.size)
            v.verifySignature(si.encryptedDigest.octets)
        }.getOrDefault(false)
        return result(if (ok) SmimeSigStatus.VALID else SmimeSigStatus.BAD)
    }

    private fun verifierFor(sigAlg: AlgorithmIdentifier, digestAlg: ASN1ObjectIdentifier, pub: AsymmetricKeyParameter): Signer? {
        val oid = sigAlg.algorithm
        return when {
            oid == PKCSObjectIdentifiers.id_RSASSA_PSS && pub is RSAKeyParameters -> {
                val p = RSASSAPSSparams.getInstance(sigAlg.parameters)
                val d = digestFor(p.hashAlgorithm.algorithm) ?: return null
                val mgfHash = runCatching { AlgorithmIdentifier.getInstance(p.maskGenAlgorithm.parameters).algorithm }.getOrNull() ?: return null
                val mgf = digestFor(mgfHash) ?: return null
                PSSSigner(RSABlindedEngine(), d, mgf, p.saltLength.toInt())
            }
            pub is RSAKeyParameters && (oid == PKCSObjectIdentifiers.rsaEncryption || oid in RSA_WITH) ->
                RSADigestSigner(digestFor(RSA_WITH[oid] ?: digestAlg) ?: return null)
            pub is ECPublicKeyParameters && (oid == X9ObjectIdentifiers.id_ecPublicKey || oid in ECDSA_WITH) ->
                DSADigestSigner(ECDSASigner(), digestFor(ECDSA_WITH[oid] ?: digestAlg) ?: return null)
            else -> null
        }
    }

    private fun attrValue(attrs: ASN1Set, oid: ASN1ObjectIdentifier): ASN1Encodable? =
        attrs.toArray().map { Attribute.getInstance(it) }.firstOrNull { it.attrType == oid }?.attrValues?.takeIf { it.size() > 0 }?.getObjectAt(0)

    // ------------------------------------------------------------------ encrypting

    /**
     * Encrypts [content] (a whole MIME part) to [recipients] (include your own certificate, so you can read it in
     * Sent): DER `ContentInfo` with `EnvelopedData`, AES-256-CBC, the key wrapped with RSA PKCS #1 v1.5.
     */
    fun encrypt(content: ByteArray, recipients: List<Certificate>, random: SecureRandom = SecureRandom()): ByteArray {
        if (recipients.isEmpty()) throw SmimeFailure(SmimeFailure.Kind.CANT_ENCRYPT_TO)
        val cek = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        val encrypted = cbc(AESEngine.newInstance(), true, cek, iv, content)
        val infos = ASN1EncodableVector()
        for (cert in recipients.distinctBy { fingerprint(it) }) {
            val pub = runCatching { PublicKeyFactory.createKey(cert.subjectPublicKeyInfo) }.getOrNull()
            if (pub !is RSAKeyParameters) throw SmimeFailure(SmimeFailure.Kind.CANT_ENCRYPT_TO)
            val wrap = PKCS1Encoding(RSABlindedEngine())
            wrap.init(true, ParametersWithRandom(pub, random))
            val ek = wrap.processBlock(cek, 0, cek.size)
            infos.add(
                RecipientInfo(
                    KeyTransRecipientInfo(
                        RecipientIdentifier(IssuerAndSerialNumber(cert)),
                        AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE),
                        DEROctetString(ek),
                    ),
                ),
            )
        }
        cek.fill(0)
        val eci = EncryptedContentInfo(
            CMSObjectIdentifiers.data,
            AlgorithmIdentifier(NISTObjectIdentifiers.id_aes256_CBC, DEROctetString(iv)),
            DEROctetString(encrypted),
        )
        val ed = EnvelopedData(null, DERSet(infos), eci, null as ASN1Set?)
        return ContentInfo(CMSObjectIdentifiers.envelopedData, ed).getEncoded(ASN1Encoding.DER)
    }

    // ------------------------------------------------------------------ decrypting

    /** Whether [der] is encrypted to one of [certs] (to tell "not for you" from "your certificate is missing"). */
    fun encryptedTo(der: ByteArray, certs: List<Certificate>): List<Certificate> {
        val ed = enveloped(der)
        return certs.filter { cert -> ed.recipientInfos.toArray().any { ri -> (RecipientInfo.getInstance(ri).info as? KeyTransRecipientInfo)?.let { names(it.recipientIdentifier.id, cert) } == true } }
    }

    /** Decrypts an S/MIME encrypted message with whichever of [identities] it's encrypted to: the inner MIME part. */
    fun decrypt(der: ByteArray, identities: List<SmimeIdentity>): ByteArray {
        val ed = enveloped(der)
        val eci = ed.encryptedContentInfo
        var sawMine = false
        var sawOther = false
        for (ri in ed.recipientInfos.toArray()) {
            val info = RecipientInfo.getInstance(ri).info
            if (info !is KeyTransRecipientInfo) {
                sawOther = true
                continue
            }
            for (id in identities) {
                if (!names(info.recipientIdentifier.id, id.cert)) continue
                sawMine = true
                val cek = unwrap(info, id.key) ?: continue
                try {
                    return decryptContent(eci, cek)
                } catch (e: SmimeFailure) {
                    if (e.kind == SmimeFailure.Kind.UNSUPPORTED) throw e
                } finally {
                    cek.fill(0)
                }
            }
        }
        throw SmimeFailure(
            when {
                sawMine -> SmimeFailure.Kind.BROKEN
                sawOther -> SmimeFailure.Kind.UNSUPPORTED
                else -> SmimeFailure.Kind.NOT_FOR_YOU
            },
        )
    }

    private fun enveloped(der: ByteArray): EnvelopedData {
        val ci = contentInfo(der)
        when (ci.contentType) {
            CMSObjectIdentifiers.envelopedData -> Unit
            CMSObjectIdentifiers.authEnvelopedData -> throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
            else -> throw SmimeFailure(SmimeFailure.Kind.NOT_SMIME)
        }
        return try {
            EnvelopedData.getInstance(ci.content)
        } catch (e: Exception) {
            throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        }
    }

    private fun unwrap(info: KeyTransRecipientInfo, key: AsymmetricKeyParameter): ByteArray? {
        if (key !is RSAKeyParameters) return null
        val alg = info.keyEncryptionAlgorithm
        val cipher: AsymmetricBlockCipher = when (alg.algorithm) {
            PKCSObjectIdentifiers.rsaEncryption -> PKCS1Encoding(RSABlindedEngine())
            PKCSObjectIdentifiers.id_RSAES_OAEP -> {
                val p = RSAESOAEPparams.getInstance(alg.parameters ?: DERSequence())
                val hash = digestFor(p.hashAlgorithm.algorithm) ?: throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
                val mgfHash = runCatching { AlgorithmIdentifier.getInstance(p.maskGenAlgorithm.parameters).algorithm }.getOrNull()
                    ?: throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
                val mgf = digestFor(mgfHash) ?: throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
                val label = runCatching { ASN1OctetString.getInstance(p.pSourceAlgorithm.parameters).octets }.getOrNull()
                OAEPEncoding(RSABlindedEngine(), hash, mgf, label)
            }
            else -> throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        }
        return try {
            cipher.init(false, key)
            val ek = info.encryptedKey.octets
            cipher.processBlock(ek, 0, ek.size)
        } catch (e: InvalidCipherTextException) {
            null
        }
    }

    private fun decryptContent(eci: EncryptedContentInfo, cek: ByteArray): ByteArray {
        val alg = eci.contentEncryptionAlgorithm
        val data = eci.encryptedContent?.octets ?: throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        val engine: BlockCipher = when (alg.algorithm) {
            NISTObjectIdentifiers.id_aes128_CBC, NISTObjectIdentifiers.id_aes192_CBC, NISTObjectIdentifiers.id_aes256_CBC -> AESEngine.newInstance()
            PKCSObjectIdentifiers.des_EDE3_CBC -> DESedeEngine()
            else -> throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        }
        val iv = runCatching { ASN1OctetString.getInstance(alg.parameters).octets }.getOrNull() ?: throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        val want = when (alg.algorithm) {
            NISTObjectIdentifiers.id_aes128_CBC -> 16
            NISTObjectIdentifiers.id_aes192_CBC -> 24
            NISTObjectIdentifiers.id_aes256_CBC -> 32
            else -> 24
        }
        if (cek.size != want) throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        return cbc(engine, false, cek, iv, data)
    }

    // ------------------------------------------------------------------ helpers

    /** What [der] holds: signed or encrypted, or null when it isn't CMS. */
    fun kindOf(der: ByteArray): ASN1ObjectIdentifier? = runCatching { contentInfo(der).contentType }.getOrNull()

    private fun parse(der: ByteArray): ASN1Primitive {
        if (der.size > MAX_BYTES) throw SmimeFailure(SmimeFailure.Kind.TOO_BIG)
        return ASN1InputStream(ByteArrayInputStream(der), der.size).use { it.readObject() } ?: throw SmimeFailure(SmimeFailure.Kind.NOT_SMIME)
    }

    private fun contentInfo(der: ByteArray): ContentInfo = try {
        ContentInfo.getInstance(parse(der))
    } catch (e: SmimeFailure) {
        throw e
    } catch (e: Exception) {
        throw SmimeFailure(SmimeFailure.Kind.NOT_SMIME)
    }

    internal fun cbc(engine: BlockCipher, encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(engine), PKCS7Padding())
        return try {
            c.init(encrypt, ParametersWithIV(KeyParameter(key), iv))
            val out = ByteArray(c.getOutputSize(data.size))
            var n = c.processBytes(data, 0, data.size, out, 0)
            n += c.doFinal(out, n)
            out.copyOf(n)
        } catch (e: InvalidCipherTextException) {
            throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        } catch (e: IllegalArgumentException) {
            throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        } catch (e: org.bouncycastle.crypto.DataLengthException) {
            throw SmimeFailure(SmimeFailure.Kind.BROKEN)
        }
    }

    internal fun digestFor(oid: ASN1ObjectIdentifier): Digest? = when (oid) {
        OIWObjectIdentifiers.idSHA1 -> SHA1Digest()
        NISTObjectIdentifiers.id_sha224 -> SHA224Digest()
        NISTObjectIdentifiers.id_sha256 -> SHA256Digest()
        NISTObjectIdentifiers.id_sha384 -> SHA384Digest()
        NISTObjectIdentifiers.id_sha512 -> SHA512Digest()
        else -> null
    }

    private fun digest(d: Digest, data: ByteArray): ByteArray {
        d.update(data, 0, data.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    /** The private key from a stored PKCS #8 blob. */
    fun keyFrom(info: PrivateKeyInfo): AsymmetricKeyParameter = PrivateKeyFactory.createKey(info)

    /** Signature algorithms that name their digest. */
    private val RSA_WITH = mapOf(
        PKCSObjectIdentifiers.sha1WithRSAEncryption to OIWObjectIdentifiers.idSHA1,
        PKCSObjectIdentifiers.sha224WithRSAEncryption to NISTObjectIdentifiers.id_sha224,
        PKCSObjectIdentifiers.sha256WithRSAEncryption to NISTObjectIdentifiers.id_sha256,
        PKCSObjectIdentifiers.sha384WithRSAEncryption to NISTObjectIdentifiers.id_sha384,
        PKCSObjectIdentifiers.sha512WithRSAEncryption to NISTObjectIdentifiers.id_sha512,
    )
    private val ECDSA_WITH = mapOf(
        X9ObjectIdentifiers.ecdsa_with_SHA1 to OIWObjectIdentifiers.idSHA1,
        X9ObjectIdentifiers.ecdsa_with_SHA224 to NISTObjectIdentifiers.id_sha224,
        X9ObjectIdentifiers.ecdsa_with_SHA256 to NISTObjectIdentifiers.id_sha256,
        X9ObjectIdentifiers.ecdsa_with_SHA384 to NISTObjectIdentifiers.id_sha384,
        X9ObjectIdentifiers.ecdsa_with_SHA512 to NISTObjectIdentifiers.id_sha512,
    )

    private val PEM = Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----")
}
