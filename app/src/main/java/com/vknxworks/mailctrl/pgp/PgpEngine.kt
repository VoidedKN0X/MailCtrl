package com.vknxworks.mailctrl.pgp

import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.KeyIdentifier
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.Features
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyRing
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPMarker
import org.bouncycastle.openpgp.PGPOnePassSignature
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.bc.BcOpenPGPImplementation
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.charset.Charset
import java.security.SecureRandom
import java.util.Date
import java.util.Locale

/** The kind of key MailCtrl makes. */
enum class KeyType {
    /** Ed25519 for signing and Curve25519 (X25519) for encryption: small, fast, and what GnuPG makes by default. */
    CURVE25519,

    /** RSA with 4096 bits, for people writing to very old OpenPGP software. */
    RSA_4096,
}

/** What MailCtrl shows about a key. */
data class KeyInfo(
    /** The primary key's fingerprint, 40 hex digits (64 for version 6 keys), upper case. */
    val fingerprint: String,
    val keyId: Long,
    val userIds: List<String>,
    val created: Long,
    /** When the key stops working, or null when it never expires. */
    val expires: Long?,
    val revoked: Boolean,
    /** It has a valid key to encrypt to right now. */
    val canEncrypt: Boolean,
    /** It has a valid key to sign with right now. */
    val canSign: Boolean,
    /** The secret key is here too (it's one of your own keys). */
    val secret: Boolean,
    /** The secret key needs a passphrase. */
    val protected: Boolean,
    val algorithm: String,
) {
    val emails: Set<String> get() = userIds.mapNotNull { PgpEngine.emailOf(it) }.toSet()

    val primaryUserId: String get() = userIds.firstOrNull().orEmpty()

    fun expired(now: Long = System.currentTimeMillis()): Boolean = expires != null && expires <= now
}

enum class SigStatus {
    /** Checked against a key on the phone, and it matches. */
    VALID,

    /** Signed with a key that isn't on the phone, so it can't be checked. */
    UNKNOWN_KEY,

    /** The signature doesn't match: the message was changed, or the signature is forged. */
    BAD,
}

/** What checking a signature found. */
data class SigCheck(
    val status: SigStatus,
    /** The key ID the signature names. */
    val keyId: Long,
    /** The signer's key (primary fingerprint), when it's on the phone. */
    val signerFingerprint: String? = null,
    val signerUserIds: List<String> = emptyList(),
    val created: Long? = null,
    /** Valid, but the key has expired or was revoked, or isn't meant for signing. */
    val keyProblem: Boolean = false,
) {
    val signerEmails: Set<String> get() = signerUserIds.mapNotNull { PgpEngine.emailOf(it) }.toSet()
}

/** The passphrase didn't open the secret key. */
class WrongPassphraseException : Exception("Wrong passphrase")

/** OpenPGP data that can't be used; [kind] says why, for showing it to people. */
class PgpFailure(val kind: Kind, message: String? = null, cause: Throwable? = null) : Exception(message ?: kind.name, cause) {
    enum class Kind {
        /** Not OpenPGP data (or not the kind expected). */
        NOT_PGP,

        /** Encrypted, but not to any of your keys. */
        NO_SECRET_KEY,

        /** Damaged, or changed on the way. */
        BROKEN,

        /** Encrypted without integrity protection, which can be tampered with (EFAIL): refused. */
        NOT_PROTECTED,

        /** Too big to handle on the phone. */
        TOO_BIG,

        /** A recipient has no key to encrypt to. */
        NO_ENCRYPTION_KEY,

        /** Your key can't sign (expired, revoked, or no signing key). */
        NO_SIGNING_KEY,
    }
}

/** A secret key opened with its passphrase. Lives in memory only, for as long as the passphrase is remembered. */
class UnlockedKey internal constructor(
    val fingerprint: String,
    val ring: PGPSecretKeyRing,
    internal val privateKeys: Map<Long, PGPPrivateKey>,
)

/** Finds a stored public key by the key ID of its primary key or any subkey. */
fun interface CertLookup {
    fun byKeyId(keyId: Long): PGPPublicKeyRing?
}

/** A decrypted message: its content, and its signature when it was signed too. */
class Decrypted(val data: ByteArray, val signature: SigCheck?)

/** Something to send encrypted, signed, or both. */
class PgpOutgoing(
    /** Every recipient's encryption key, and yours (so you can read your own sent mail). Empty = sign only. */
    val encryptTo: List<PGPPublicKey>,
    /** Your key, when the message is signed. */
    val signer: UnlockedKey?,
)

/**
 * OpenPGP with Bouncy Castle's lightweight API: keys, encrypting, signing, decrypting and checking signatures.
 * No Android code here, so all of it is tested on the computer.
 *
 * Keys are made as version 4 keys with the classic algorithms (EdDSA and Curve25519 ECDH, or RSA) that every
 * OpenPGP program reads, including GnuPG, Thunderbird and Proton. Messages are encrypted with AES-256 and an
 * integrity check, and signed with SHA-256.
 */
object PgpEngine {
    private val rng = SecureRandom()
    private val fingerprints = BcKeyFingerprintCalculator()
    private val digests = BcPGPDigestCalculatorProvider()
    private val verifiers = BcPGPContentVerifierBuilderProvider()
    private val impl by lazy { BcOpenPGPImplementation() }

    /** Keys bigger than this aren't read (a normal key is a few kilobytes). */
    const val MAX_KEY_BYTES = 2 * 1024 * 1024

    /** Decrypted content bigger than this isn't kept. */
    const val MAX_PLAIN_BYTES = 64L * 1024 * 1024

    private const val BUFFER = 1 shl 16

    // ------------------------------------------------------------------ names

    private val EMAIL_IN_ANGLES = Regex("<([^<>\\s]+@[^<>\\s]+)>")
    private val BARE_EMAIL = Regex("^[^<>\\s]+@[^<>\\s]+$")

    /** The address in a user ID ("Name <a@b>" or just "a@b"), in lower case. */
    fun emailOf(userId: String): String? {
        val inAngles = EMAIL_IN_ANGLES.findAll(userId).lastOrNull()?.groupValues?.get(1)
        val email = inAngles ?: userId.trim().takeIf { BARE_EMAIL.matches(it) }
        return email?.lowercase(Locale.ROOT)
    }

    fun fingerprintOf(key: PGPPublicKey): String = hex(key.fingerprint)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /** "1234 5678 9ABC …": a fingerprint in groups of four, for reading out and comparing. */
    fun pretty(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")

    /** "0x1234ABCD5678EF90" */
    fun keyIdText(keyId: Long): String = "0x" + "%016X".format(keyId)

    // ------------------------------------------------------------------ keys

    /**
     * Makes a new key for [email], protected with [passphrase] (AES-256, iterated and salted SHA-256), valid for
     * [validSeconds] (0 = no end date).
     */
    fun generate(
        name: String,
        email: String,
        passphrase: CharArray,
        type: KeyType,
        validSeconds: Long = 0,
        now: Date = Date(),
    ): PGPSecretKeyRing {
        val userId = if (name.isBlank()) "<${email.trim()}>" else "${name.trim()} <${email.trim()}>"
        val primary: BcPGPKeyPair
        val sub: BcPGPKeyPair
        when (type) {
            KeyType.CURVE25519 -> {
                val ed = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(rng)) }.generateKeyPair()
                val x = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(rng)) }.generateKeyPair()
                primary = BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.EDDSA_LEGACY, ed, now)
                sub = BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.ECDH, x, now)
            }
            KeyType.RSA_4096 -> {
                fun rsa() = RSAKeyPairGenerator().apply {
                    init(RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), rng, 4096, 100))
                }.generateKeyPair()
                primary = BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, rsa(), now)
                sub = BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, rsa(), now)
            }
        }
        val certification = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
            setPreferredSymmetricAlgorithms(
                false,
                intArrayOf(SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_192, SymmetricKeyAlgorithmTags.AES_128),
            )
            setPreferredHashAlgorithms(
                false,
                intArrayOf(HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA224),
            )
            setPreferredCompressionAlgorithms(
                false,
                intArrayOf(CompressionAlgorithmTags.ZLIB, CompressionAlgorithmTags.BZIP2, CompressionAlgorithmTags.ZIP, CompressionAlgorithmTags.UNCOMPRESSED),
            )
            setFeature(false, Features.FEATURE_MODIFICATION_DETECTION)
            setPrimaryUserID(false, true)
            if (validSeconds > 0) setKeyExpirationTime(false, validSeconds)
        }
        val binding = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
            if (validSeconds > 0) setKeyExpirationTime(false, validSeconds)
        }
        // 0xE0: about 16 million bytes hashed per try, so guessing passphrases is slow.
        val encryptor = if (passphrase.isEmpty()) {
            null
        } else {
            BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, digests.get(HashAlgorithmTags.SHA256), 0xE0)
                .setSecureRandom(rng)
                .build(passphrase)
        }
        val gen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            primary,
            userId,
            digests.get(HashAlgorithmTags.SHA1),
            certification.generate(),
            null,
            BcPGPContentSignerBuilder(primary.publicKey.algorithm, HashAlgorithmTags.SHA512),
            encryptor,
        )
        gen.addSubKey(sub, binding.generate(), null)
        return gen.generateSecretKeyRing()
    }

    /**
     * Every key in [bytes]: armored (any number of blocks, with text around them) or binary (one or several keys
     * one after the other). Public and secret keys both come back; anything else is skipped.
     */
    fun readKeys(bytes: ByteArray): List<PGPKeyRing> {
        if (bytes.size > MAX_KEY_BYTES) throw PgpFailure(PgpFailure.Kind.TOO_BIG)
        val parts = if (Armor.isArmored(bytes)) {
            Armor.blocks(String(bytes, Charsets.UTF_8)).filter { "KEY BLOCK" in it.type }.map { it.data }
        } else {
            listOf(bytes)
        }
        val found = mutableListOf<PGPKeyRing>()
        for (part in parts) {
            val factory = BcPGPObjectFactory(ByteArrayInputStream(part))
            while (true) {
                val o = try {
                    factory.nextObject()
                } catch (e: IOException) {
                    null
                } catch (e: RuntimeException) {
                    null
                } ?: break
                when (o) {
                    is PGPPublicKeyRing -> found += o
                    is PGPSecretKeyRing -> found += o
                    else -> Unit
                }
            }
        }
        return found
    }

    /** The public half of a secret key, to give to other people. */
    fun publicOf(ring: PGPKeyRing): PGPPublicKeyRing = when (ring) {
        is PGPPublicKeyRing -> ring
        is PGPSecretKeyRing -> ring.toCertificate()
        else -> throw PgpFailure(PgpFailure.Kind.NOT_PGP)
    }

    /** Two copies of the same public key (an update from the sender, say) put together. */
    fun merge(old: PGPPublicKeyRing, new: PGPPublicKeyRing): PGPPublicKeyRing =
        runCatching { PGPPublicKeyRing.join(old, new) }.getOrDefault(new)

    private fun certificate(ring: PGPKeyRing): OpenPGPCertificate? =
        runCatching { OpenPGPCertificate(publicOf(ring), impl) }.getOrNull()

    fun info(ring: PGPKeyRing): KeyInfo {
        val pk = ring.publicKey
        val cert = certificate(ring)
        val created = pk.creationTime.time
        val expires = cert?.let { c -> runCatching { c.expirationTime }.getOrNull()?.time }
            ?: pk.validSeconds.takeIf { it > 0 }?.let { created + it * 1000 }
        val secret = ring is PGPSecretKeyRing
        return KeyInfo(
            fingerprint = fingerprintOf(pk),
            keyId = pk.keyID,
            userIds = pk.userIDs.asSequence().toList(),
            created = created,
            expires = expires,
            revoked = pk.hasRevocation() || runCatching { cert?.revocation != null }.getOrDefault(false),
            canEncrypt = cert != null && runCatching { cert.encryptionKeys.isNotEmpty() }.getOrDefault(false),
            canSign = cert != null && runCatching { cert.signingKeys.isNotEmpty() }.getOrDefault(false),
            secret = secret,
            protected = secret && needsPassphrase(ring as PGPSecretKeyRing),
            algorithm = algorithmName(pk),
        )
    }

    private fun algorithmName(pk: PGPPublicKey): String = when (pk.algorithm) {
        PublicKeyAlgorithmTags.EDDSA_LEGACY, PublicKeyAlgorithmTags.Ed25519 -> "Ed25519"
        PublicKeyAlgorithmTags.Ed448 -> "Ed448"
        PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_SIGN, PublicKeyAlgorithmTags.RSA_ENCRYPT -> "RSA ${pk.bitStrength}"
        PublicKeyAlgorithmTags.ECDSA -> "ECDSA ${pk.bitStrength}"
        PublicKeyAlgorithmTags.DSA -> "DSA ${pk.bitStrength}"
        else -> "#${pk.algorithm}"
    }

    /** The newest valid key to encrypt to, or null when there's none (expired, revoked, or none at all). */
    fun encryptionKey(ring: PGPKeyRing): PGPPublicKey? = certificate(ring)?.let { cert ->
        runCatching { cert.encryptionKeys.maxByOrNull { it.creationTime }?.pgpPublicKey }.getOrNull()
    }

    /** The secret key needs a passphrase to open. */
    fun needsPassphrase(ring: PGPSecretKeyRing): Boolean =
        ring.secretKeys.asSequence().any { !it.isPrivateKeyEmpty && it.keyEncryptionAlgorithm != SymmetricKeyAlgorithmTags.NULL }

    /** Opens every part of a secret key with [passphrase]. */
    fun unlock(ring: PGPSecretKeyRing, passphrase: CharArray): UnlockedKey {
        val decryptor = BcPBESecretKeyDecryptorBuilder(digests).build(passphrase)
        val keys = mutableMapOf<Long, PGPPrivateKey>()
        for (sk in ring.secretKeys) {
            // Parts kept elsewhere (a smartcard, an offline primary key) have no secret here.
            if (sk.isPrivateKeyEmpty) continue
            val d = if (sk.keyEncryptionAlgorithm == SymmetricKeyAlgorithmTags.NULL) null else decryptor
            keys[sk.keyID] = try {
                sk.extractPrivateKey(d) ?: continue
            } catch (e: PGPException) {
                throw WrongPassphraseException()
            } catch (e: IllegalArgumentException) {
                throw WrongPassphraseException()
            }
        }
        if (keys.isEmpty()) throw PgpFailure(PgpFailure.Kind.NO_SECRET_KEY)
        return UnlockedKey(fingerprintOf(ring.publicKey), ring, keys)
    }

    /** Whether [passphrase] opens the key, without keeping anything. */
    fun checkPassphrase(ring: PGPSecretKeyRing, passphrase: CharArray): Boolean =
        runCatching { unlock(ring, passphrase) }.isSuccess

    // ------------------------------------------------------------------ writing

    private fun signingKey(key: UnlockedKey): Pair<PGPPublicKey, PGPPrivateKey> {
        val cert = certificate(key.ring)
        val candidates = runCatching { cert?.signingKeys.orEmpty() }.getOrDefault(emptyList())
            .sortedByDescending { it.creationTime }
            .map { it.pgpPublicKey }
        val pub = candidates.firstOrNull { it.keyID in key.privateKeys } ?: throw PgpFailure(PgpFailure.Kind.NO_SIGNING_KEY)
        return pub to key.privateKeys.getValue(pub.keyID)
    }

    private fun signatureGenerator(key: UnlockedKey, type: Int): PGPSignatureGenerator {
        val (pub, priv) = signingKey(key)
        val gen = PGPSignatureGenerator(BcPGPContentSignerBuilder(pub.algorithm, HashAlgorithmTags.SHA256), pub)
        gen.init(type, priv)
        gen.setHashedSubpackets(
            PGPSignatureSubpacketGenerator().apply {
                setSignatureCreationTime(false, Date())
                setIssuerFingerprint(false, pub)
            }.generate(),
        )
        return gen
    }

    /**
     * Encrypts [data] to [recipients] (AES-256 with an integrity check, compressed), signed inside with [signer]
     * when given. Armored, as PGP/MIME expects.
     */
    fun encrypt(data: ByteArray, recipients: List<PGPPublicKey>, signer: UnlockedKey?): ByteArray {
        if (recipients.isEmpty()) throw PgpFailure(PgpFailure.Kind.NO_ENCRYPTION_KEY)
        val out = ByteArrayOutputStream(data.size / 2 + 4096)
        val armored = org.bouncycastle.bcpg.ArmoredOutputStream.builder().clearHeaders().build(out)
        val gen = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true).setSecureRandom(rng),
        )
        recipients.distinctBy { it.keyID }.forEach { gen.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(it).setSecureRandom(rng)) }
        val enc = gen.open(armored, ByteArray(BUFFER))
        writeLiteral(enc, data, signer)
        enc.close()
        armored.close()
        return out.toByteArray()
    }

    private fun writeLiteral(out: OutputStream, data: ByteArray, signer: UnlockedKey?) {
        val comp = PGPCompressedDataGenerator(CompressionAlgorithmTags.ZLIB)
        val cOut = comp.open(out, ByteArray(BUFFER))
        val sig = signer?.let { signatureGenerator(it, PGPSignature.BINARY_DOCUMENT) }
        sig?.generateOnePassVersion(false)?.encode(cOut)
        val lit = PGPLiteralDataGenerator()
        val lOut = lit.open(cOut, PGPLiteralData.BINARY, "", data.size.toLong(), Date())
        lOut.write(data)
        lit.close()
        sig?.update(data)
        sig?.generate()?.encode(cOut)
        comp.close()
    }

    /** A separate (detached) signature over [data], armored: the second part of a PGP/MIME signed message. */
    fun signDetached(data: ByteArray, signer: UnlockedKey): ByteArray {
        val gen = signatureGenerator(signer, PGPSignature.BINARY_DOCUMENT)
        gen.update(data)
        val out = ByteArrayOutputStream()
        val armored = org.bouncycastle.bcpg.ArmoredOutputStream.builder().clearHeaders().build(out)
        gen.generate().encode(armored)
        armored.close()
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ reading

    private fun factory(input: InputStream) = BcPGPObjectFactory(input)

    private fun encryptedList(message: ByteArray): PGPEncryptedDataList {
        val f = factory(ByteArrayInputStream(Armor.binary(message, "MESSAGE")))
        while (true) {
            val o = try {
                f.nextObject()
            } catch (e: IOException) {
                throw PgpFailure(PgpFailure.Kind.BROKEN, cause = e)
            } catch (e: RuntimeException) {
                throw PgpFailure(PgpFailure.Kind.BROKEN, cause = e)
            } ?: throw PgpFailure(PgpFailure.Kind.NOT_PGP)
            when (o) {
                is PGPEncryptedDataList -> return o
                is PGPMarker -> continue
                else -> throw PgpFailure(PgpFailure.Kind.NOT_PGP)
            }
        }
    }

    /** The key IDs a message is encrypted to (0 for a hidden recipient). */
    fun recipients(message: ByteArray): List<Long> =
        encryptedList(message).encryptedDataObjects.asSequence().filterIsInstance<PGPPublicKeyEncryptedData>().map { it.keyID }.toList()

    /**
     * Decrypts [message] with one of [keys], and checks the signature inside it against [certs]. Messages
     * without an integrity check are refused (they can be altered without anyone noticing).
     */
    fun decrypt(message: ByteArray, keys: List<UnlockedKey>, certs: CertLookup): Decrypted {
        val list = encryptedList(message)
        val sessions = list.encryptedDataObjects.asSequence().filterIsInstance<PGPPublicKeyEncryptedData>().toList()
        val all = keys.flatMap { it.privateKeys.entries }
        var stream: InputStream? = null
        var used: PGPPublicKeyEncryptedData? = null
        // Named recipients first, then hidden ones (key ID 0) with every key there is.
        for (ed in sessions.sortedBy { if (it.keyID == 0L) 1 else 0 }) {
            val tries = if (ed.keyID == 0L) all.map { it.value } else listOfNotNull(all.firstOrNull { it.key == ed.keyID }?.value)
            for (priv in tries) {
                stream = runCatching { ed.getDataStream(BcPublicKeyDataDecryptorFactory(priv)) }.getOrNull()
                if (stream != null) {
                    used = ed
                    break
                }
            }
            if (stream != null) break
        }
        val ed = used ?: throw PgpFailure(PgpFailure.Kind.NO_SECRET_KEY)
        if (!ed.isIntegrityProtected) throw PgpFailure(PgpFailure.Kind.NOT_PROTECTED)
        val result = try {
            readContent(stream!!, certs)
        } catch (e: PgpFailure) {
            throw e
        } catch (e: Exception) {
            throw PgpFailure(PgpFailure.Kind.BROKEN, cause = e)
        }
        // The integrity check (MDC) can only be checked once everything was read.
        val intact = try {
            ed.verify()
        } catch (e: PGPException) {
            // AEAD-encrypted data checks itself while it's read; only the old MDC check can fail here.
            ed.algorithm != SymmetricKeyAlgorithmTags.NULL
        }
        if (!intact) throw PgpFailure(PgpFailure.Kind.BROKEN)
        return result
    }

    private class Pending(val ops: PGPOnePassSignature, val ring: PGPPublicKeyRing?)

    private fun readContent(input: InputStream, certs: CertLookup): Decrypted {
        var f = factory(input)
        val pending = mutableListOf<Pending>()
        var early: PGPSignatureList? = null
        var data: ByteArray? = null
        var check: SigCheck? = null
        while (true) {
            val o = f.nextObject() ?: break
            when (o) {
                is PGPCompressedData -> f = factory(o.dataStream)
                is PGPOnePassSignatureList -> for (ops in o) {
                    val ring = certs.byKeyId(ops.keyID)
                    if (ring != null) runCatching { ops.init(verifiers, ring.getPublicKey(ops.keyID)) }
                    pending += Pending(ops, ring)
                }
                is PGPLiteralData -> {
                    val bytes = o.inputStream.readLimited(MAX_PLAIN_BYTES)
                    pending.forEach { if (it.ring != null) it.ops.update(bytes) }
                    data = bytes
                    early?.let { check = checkList(it, bytes, certs) }
                }
                is PGPSignatureList -> {
                    val d = data
                    if (d == null) {
                        early = o
                    } else if (pending.isNotEmpty()) {
                        check = checkOnePass(pending, o)
                    } else {
                        check = checkList(o, d, certs)
                    }
                }
                else -> Unit
            }
        }
        return Decrypted(data ?: throw PgpFailure(PgpFailure.Kind.BROKEN), check)
    }

    private fun checkOnePass(pending: List<Pending>, sigs: PGPSignatureList): SigCheck? {
        val results = pending.map { p ->
            val sig = sigs.firstOrNull { it.keyID == p.ops.keyID }
            when {
                sig == null -> SigCheck(SigStatus.BAD, p.ops.keyID)
                p.ring == null -> SigCheck(SigStatus.UNKNOWN_KEY, p.ops.keyID)
                else -> describe(sig, p.ring, runCatching { p.ops.verify(sig) }.getOrDefault(false))
            }
        }
        return best(results)
    }

    /** One good signature is enough; otherwise a bad one is reported before an unknown one. */
    private fun best(results: List<SigCheck>): SigCheck? =
        results.firstOrNull { it.status == SigStatus.VALID }
            ?: results.firstOrNull { it.status == SigStatus.BAD }
            ?: results.firstOrNull()

    private fun checkList(sigs: PGPSignatureList, data: ByteArray, certs: CertLookup): SigCheck? =
        best(sigs.map { sig -> checkOne(sig, data, certs) })

    private fun issuerKeyId(sig: PGPSignature): Long {
        if (sig.keyID != 0L) return sig.keyID
        // Only a fingerprint named: the key ID is its last 8 bytes (version 4).
        return runCatching { sig.keyIdentifiers.firstOrNull { it.keyId != 0L }?.keyId }.getOrNull() ?: 0L
    }

    private fun checkOne(sig: PGPSignature, data: ByteArray, certs: CertLookup): SigCheck {
        val id = issuerKeyId(sig)
        val ring = certs.byKeyId(id) ?: return SigCheck(SigStatus.UNKNOWN_KEY, id, created = sig.creationTime?.time)
        val ok = runCatching {
            sig.init(verifiers, ring.getPublicKey(id))
            sig.update(data)
            sig.verify()
        }.getOrDefault(false)
        return describe(sig, ring, ok)
    }

    private fun describe(sig: PGPSignature, ring: PGPPublicKeyRing, ok: Boolean): SigCheck {
        val id = issuerKeyId(sig)
        val created = sig.creationTime
        val cert = certificate(ring)
        val problem = ok && (
            cert == null ||
                runCatching { cert.getKey(KeyIdentifier(id))?.isSigningKey(created) != true }.getOrDefault(true) ||
                runCatching { cert.revocation != null }.getOrDefault(false) ||
                runCatching { cert.expirationTime?.let { it.time <= System.currentTimeMillis() } == true }.getOrDefault(false)
            )
        return SigCheck(
            status = if (ok) SigStatus.VALID else SigStatus.BAD,
            keyId = id,
            signerFingerprint = fingerprintOf(ring.publicKey),
            signerUserIds = ring.publicKey.userIDs.asSequence().toList(),
            created = created?.time,
            keyProblem = problem,
        )
    }

    /** Checks a separate (detached) signature, such as a PGP/MIME signed message's, over [data]. */
    fun verifyDetached(data: ByteArray, signature: ByteArray, certs: CertLookup): SigCheck {
        val sigs = readSignatures(signature)
        if (sigs.isEmpty()) throw PgpFailure(PgpFailure.Kind.NOT_PGP)
        return best(sigs.map { checkOne(it, data, certs) })!!
    }

    private fun readSignatures(bytes: ByteArray): List<PGPSignature> {
        var f = factory(ByteArrayInputStream(Armor.binary(bytes, "SIGNATURE", "MESSAGE")))
        val out = mutableListOf<PGPSignature>()
        while (true) {
            val o = try {
                f.nextObject()
            } catch (e: IOException) {
                throw PgpFailure(PgpFailure.Kind.BROKEN, cause = e)
            } catch (e: RuntimeException) {
                throw PgpFailure(PgpFailure.Kind.BROKEN, cause = e)
            } ?: break
            when (o) {
                is PGPSignatureList -> out += o.toList()
                is PGPCompressedData -> f = factory(o.dataStream)
                else -> Unit
            }
        }
        return out
    }

    /** A cleartext-signed text ("-----BEGIN PGP SIGNED MESSAGE-----"), as it reads and as it was signed. */
    class Cleartext(
        /** The text with the armor lines taken out, for showing. */
        val shown: String,
        /** What was signed: the signed lines, trailing spaces removed, joined with CRLF. */
        val signed: ByteArray,
        val signature: ByteArray,
    )

    /** Reads an inline-signed text. [charset] is the text's own, so the signed bytes match the sender's. */
    fun parseCleartext(text: String, charset: Charset = Charsets.UTF_8): Cleartext? {
        val lines = text.split("\r\n", "\n", "\r")
        val start = lines.indexOfFirst { it.trim() == "-----BEGIN PGP SIGNED MESSAGE-----" }
        if (start < 0) return null
        var i = start + 1
        // "Hash: SHA256" and other armor headers, up to the blank line.
        while (i < lines.size && lines[i].isNotBlank()) i++
        i++
        val sigStart = (i until lines.size).firstOrNull { lines[it].trim() == "-----BEGIN PGP SIGNATURE-----" } ?: return null
        val sigEnd = (sigStart until lines.size).firstOrNull { lines[it].trim() == "-----END PGP SIGNATURE-----" } ?: return null
        val body = lines.subList(i.coerceAtMost(sigStart), sigStart).map { if (it.startsWith("- ")) it.substring(2) else it }
        val signed = body.joinToString("\r\n") { it.trimEnd(' ', '\t') }.toByteArray(charset)
        val signature = lines.subList(sigStart, sigEnd + 1).joinToString("\n").toByteArray(Charsets.US_ASCII)
        val before = lines.subList(0, start).joinToString("\n")
        val after = lines.subList(sigEnd + 1, lines.size).joinToString("\n")
        val shown = listOf(before, body.joinToString("\n"), after).filter { it.isNotBlank() }.joinToString("\n").trim('\n')
        return Cleartext(shown, signed, signature)
    }

    fun verifyCleartext(c: Cleartext, certs: CertLookup): SigCheck = verifyDetached(c.signed, c.signature, certs)

    private fun InputStream.readLimited(max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw PgpFailure(PgpFailure.Kind.TOO_BIG)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
