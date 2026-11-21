package com.vknxworks.mailctrl.smime

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.AuthenticatedSafe
import org.bouncycastle.asn1.pkcs.CertBag
import org.bouncycastle.asn1.pkcs.EncryptedData
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.PBES2Parameters
import org.bouncycastle.asn1.pkcs.PBKDF2Params
import org.bouncycastle.asn1.pkcs.PKCS12PBEParams
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.Pfx
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.SafeBag
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.CipherParameters
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.PBEParametersGenerator
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.DESedeEngine
import org.bouncycastle.crypto.engines.RC2Engine
import org.bouncycastle.crypto.generators.PKCS12ParametersGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.RC2Parameters
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * Reads a .p12/.pfx file (PKCS #12, RFC 7292): your private key and certificate (and the chain that came with
 * it), as exported by Windows, macOS, Firefox, OpenSSL or a certificate authority. Bouncy Castle's lightweight API
 * only (no JCA provider, nothing loaded by name).
 *
 * Understands the encryption those programs use: PBES2 with PBKDF2 (HMAC-SHA-1/-256/-384/-512) and AES-CBC or
 * Triple DES (OpenSSL 3, current Windows), and the older PKCS #12 schemes with Triple DES or RC2 (older OpenSSL and
 * Windows, Firefox). The file's integrity check (MAC) is checked first, so a wrong password is reported as such.
 */
object Pkcs12 {
    const val MAX_BYTES = 1 * 1024 * 1024

    fun read(bytes: ByteArray, password: CharArray): SmimeIdentity {
        if (bytes.size > MAX_BYTES) throw SmimeFailure(SmimeFailure.Kind.TOO_BIG)
        val pfx = try {
            Pfx.getInstance(ASN1InputStream(ByteArrayInputStream(bytes), bytes.size).use { it.readObject() })
        } catch (e: Exception) {
            throw SmimeFailure(SmimeFailure.Kind.NOT_SMIME)
        } ?: throw SmimeFailure(SmimeFailure.Kind.NOT_SMIME)
        val authSafe = pfx.authSafe
        if (authSafe.contentType != PKCSObjectIdentifiers.data) throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        val authBytes = ASN1OctetString.getInstance(authSafe.content).octets
        pfx.macData?.let { mac -> checkMac(mac, authBytes, password) }

        val keys = mutableListOf<Pair<ByteArray?, PrivateKeyInfo>>()
        val certs = mutableListOf<Pair<ByteArray?, Certificate>>()
        val safe = AuthenticatedSafe.getInstance(ASN1Primitive.fromByteArray(authBytes))
        for (ci in safe.contentInfo) {
            val contents: ByteArray = when (ci.contentType) {
                PKCSObjectIdentifiers.data -> ASN1OctetString.getInstance(ci.content).octets
                PKCSObjectIdentifiers.encryptedData -> {
                    val ed = EncryptedData.getInstance(ci.content)
                    decrypt(ed.encryptionAlgorithm, ed.content.octets, password)
                }
                else -> continue
            }
            for (bagObj in ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(contents))) {
                val bag = SafeBag.getInstance(bagObj)
                val localId = localKeyId(bag)
                when (bag.bagId) {
                    PKCSObjectIdentifiers.keyBag -> keys += localId to PrivateKeyInfo.getInstance(bag.bagValue)
                    PKCSObjectIdentifiers.pkcs8ShroudedKeyBag -> {
                        val epki = EncryptedPrivateKeyInfo.getInstance(bag.bagValue)
                        val plain = decrypt(epki.encryptionAlgorithm, epki.encryptedData, password)
                        keys += localId to PrivateKeyInfo.getInstance(ASN1Primitive.fromByteArray(plain))
                    }
                    PKCSObjectIdentifiers.certBag -> {
                        val cb = CertBag.getInstance(bag.bagValue)
                        if (cb.certId == PKCSObjectIdentifiers.x509Certificate) {
                            certs += localId to Certificate.getInstance(ASN1Primitive.fromByteArray(ASN1OctetString.getInstance(cb.certValue).octets))
                        }
                    }
                }
            }
        }
        // The key and its certificate: by the file's "local key ID", or else by matching public keys.
        for ((keyId, info) in keys) {
            val key = SmimeEngine.keyFrom(info)
            val leaf = keyId?.let { id -> certs.firstOrNull { it.first?.contentEquals(id) == true }?.second }
                ?: certs.map { it.second }.firstOrNull { matches(key, it) }
                ?: continue
            if (!matches(key, leaf)) continue
            val rest = certs.map { it.second }.filter { SmimeEngine.fingerprint(it) != SmimeEngine.fingerprint(leaf) }
            return SmimeIdentity(key, info, listOf(leaf) + orderChain(leaf, rest))
        }
        throw SmimeFailure(SmimeFailure.Kind.NO_KEY_IN_FILE)
    }

    /** The issuers of [leaf] in order (as far as they're in the file), then anything else. */
    private fun orderChain(leaf: Certificate, rest: List<Certificate>): List<Certificate> {
        val out = mutableListOf<Certificate>()
        var cur = leaf
        val left = rest.toMutableList()
        while (cur.subject != cur.issuer) {
            val next = left.firstOrNull { it.subject == cur.issuer } ?: break
            out += next
            left -= next
            cur = next
        }
        return out + left
    }

    private fun matches(key: org.bouncycastle.crypto.params.AsymmetricKeyParameter, cert: Certificate): Boolean {
        val pub = runCatching { PublicKeyFactory.createKey(cert.subjectPublicKeyInfo) }.getOrNull() ?: return false
        return when {
            key is RSAPrivateCrtKeyParameters && pub is RSAKeyParameters -> key.modulus == pub.modulus
            key is ECPrivateKeyParameters && pub is ECPublicKeyParameters ->
                pub.q.normalize() == key.parameters.g.multiply(key.d).normalize()
            else -> false
        }
    }

    private fun localKeyId(bag: SafeBag): ByteArray? = runCatching {
        bag.bagAttributes?.toArray()?.map { org.bouncycastle.asn1.pkcs.Attribute.getInstance(it) }
            ?.firstOrNull { it.attrType == PKCSObjectIdentifiers.pkcs_9_at_localKeyId }
            ?.attrValues?.getObjectAt(0)?.let { ASN1OctetString.getInstance(it).octets }
    }.getOrNull()

    private fun checkMac(mac: org.bouncycastle.asn1.pkcs.MacData, data: ByteArray, password: CharArray) {
        val alg = mac.mac.algorithmId.algorithm
        val digest = SmimeEngine.digestFor(alg) ?: throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        val iterations = mac.iterationCount.toInt()
        if (iterations !in 1..10_000_000) throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        // An empty password is written as two zero bytes by some programs and as nothing by others: try both.
        val candidates = if (password.isEmpty()) listOf(PBEParametersGenerator.PKCS12PasswordToBytes(password), ByteArray(0)) else listOf(PBEParametersGenerator.PKCS12PasswordToBytes(password))
        val expected = mac.mac.digest
        for (pw in candidates) {
            val gen = PKCS12ParametersGenerator(SmimeEngine.digestFor(alg)!!)
            gen.init(pw, mac.salt, iterations)
            val key = gen.generateDerivedMacParameters(digest.digestSize * 8) as KeyParameter
            val hmac = HMac(SmimeEngine.digestFor(alg)!!)
            hmac.init(key)
            hmac.update(data, 0, data.size)
            val out = ByteArray(hmac.macSize).also { hmac.doFinal(it, 0) }
            pw.fill(0)
            if (MessageDigest.isEqual(out, expected)) return
        }
        throw SmimeFailure(SmimeFailure.Kind.BAD_PASSWORD)
    }

    private fun decrypt(alg: AlgorithmIdentifier, data: ByteArray, password: CharArray): ByteArray {
        val oid = alg.algorithm
        if (oid == PKCSObjectIdentifiers.id_PBES2) return pbes2(PBES2Parameters.getInstance(alg.parameters), data, password)
        val p = PKCS12PBEParams.getInstance(alg.parameters)
        val iterations = p.iterations.toInt()
        if (iterations !in 1..10_000_000) throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        val (engine, keyBits) = when (oid) {
            PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC -> DESedeEngine() to 192
            PKCSObjectIdentifiers.pbeWithSHAAnd2_KeyTripleDES_CBC -> DESedeEngine() to 128
            PKCSObjectIdentifiers.pbeWithSHAAnd128BitRC2_CBC -> RC2Engine() to 128
            PKCSObjectIdentifiers.pbeWithSHAAnd40BitRC2_CBC -> RC2Engine() to 40
            else -> throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        }
        val pw = PBEParametersGenerator.PKCS12PasswordToBytes(password)
        val gen = PKCS12ParametersGenerator(SHA1Digest())
        gen.init(pw, p.iv, iterations)
        val params = gen.generateDerivedParameters(keyBits, 64) as ParametersWithIV
        pw.fill(0)
        val cipherParams: CipherParameters = if (engine is RC2Engine) {
            ParametersWithIV(RC2Parameters((params.parameters as KeyParameter).key, keyBits), params.iv)
        } else {
            params
        }
        return run(engine, cipherParams, data)
    }

    private fun pbes2(p: PBES2Parameters, data: ByteArray, password: CharArray): ByteArray {
        if (p.keyDerivationFunc.algorithm != PKCSObjectIdentifiers.id_PBKDF2) throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        val kdf = PBKDF2Params.getInstance(p.keyDerivationFunc.parameters)
        val iterations = kdf.iterationCount.toInt()
        if (iterations !in 1..10_000_000) throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        val prf: Digest = when (kdf.prf.algorithm) {
            PKCSObjectIdentifiers.id_hmacWithSHA1 -> SHA1Digest()
            PKCSObjectIdentifiers.id_hmacWithSHA256 -> SHA256Digest()
            PKCSObjectIdentifiers.id_hmacWithSHA384 -> org.bouncycastle.crypto.digests.SHA384Digest()
            PKCSObjectIdentifiers.id_hmacWithSHA512 -> org.bouncycastle.crypto.digests.SHA512Digest()
            else -> throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        }
        val scheme = p.encryptionScheme
        val (engine: BlockCipher, keyBytes) = when (scheme.algorithm) {
            NISTObjectIdentifiers.id_aes128_CBC -> AESEngine.newInstance() to 16
            NISTObjectIdentifiers.id_aes192_CBC -> AESEngine.newInstance() to 24
            NISTObjectIdentifiers.id_aes256_CBC -> AESEngine.newInstance() to 32
            PKCSObjectIdentifiers.des_EDE3_CBC -> DESedeEngine() to 24
            else -> throw SmimeFailure(SmimeFailure.Kind.UNSUPPORTED)
        }
        val iv = ASN1OctetString.getInstance(scheme.parameters).octets
        val pw = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password)
        val gen = PKCS5S2ParametersGenerator(prf)
        gen.init(pw, kdf.salt, iterations)
        val key = gen.generateDerivedParameters(keyBytes * 8) as KeyParameter
        pw.fill(0)
        return run(engine, ParametersWithIV(key, iv), data)
    }

    private fun run(engine: BlockCipher, params: CipherParameters, data: ByteArray): ByteArray {
        val c = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(engine), PKCS7Padding())
        return try {
            c.init(false, params)
            val out = ByteArray(c.getOutputSize(data.size))
            var n = c.processBytes(data, 0, data.size, out, 0)
            n += c.doFinal(out, n)
            out.copyOf(n)
        } catch (e: Exception) {
            // Without a MAC to check, a wrong password shows up here.
            throw SmimeFailure(SmimeFailure.Kind.BAD_PASSWORD)
        }
    }
}
