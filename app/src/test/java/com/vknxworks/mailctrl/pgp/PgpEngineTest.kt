package com.vknxworks.mailctrl.pgp

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class PgpEngineTest {
    private val pass = "correct horse battery".toCharArray()

    private companion object {
        // Key generation is the slow part: one key each, shared by the tests.
        val alice: PGPSecretKeyRing by lazy { PgpEngine.generate("Alice", "alice@example.org", "correct horse battery".toCharArray(), KeyType.CURVE25519) }
        val bob: PGPSecretKeyRing by lazy { PgpEngine.generate("Bob", "bob@example.net", "bobs secret".toCharArray(), KeyType.CURVE25519) }
    }

    private fun lookup(vararg rings: PGPSecretKeyRing) = CertLookup { id ->
        rings.map { it.toCertificate() }.firstOrNull { r -> r.getPublicKey(id) != null }
    }

    private fun encKey(ring: PGPSecretKeyRing) = PgpEngine.encryptionKey(ring)!!

    @Test
    fun generatedKeyDescribesItself() {
        val info = PgpEngine.info(alice)
        assertEquals(40, info.fingerprint.length)
        assertEquals(listOf("Alice <alice@example.org>"), info.userIds)
        assertEquals(setOf("alice@example.org"), info.emails)
        assertTrue(info.secret)
        assertTrue(info.protected)
        assertTrue(info.canEncrypt)
        assertTrue(info.canSign)
        assertFalse(info.revoked)
        assertNull(info.expires)
        assertEquals("Ed25519", info.algorithm)
        // The public half carries no secret.
        val pub = PgpEngine.info(PgpEngine.publicOf(alice))
        assertFalse(pub.secret)
        assertEquals(info.fingerprint, pub.fingerprint)
    }

    @Test
    fun expiryIsShown() {
        val twoYears = 2L * 365 * 24 * 3600
        val ring = PgpEngine.generate("", "exp@example.org", CharArray(0), KeyType.CURVE25519, validSeconds = twoYears)
        val info = PgpEngine.info(ring)
        assertNotNull(info.expires)
        assertEquals(info.created + twoYears * 1000, info.expires)
        assertEquals(listOf("<exp@example.org>"), info.userIds)
        assertFalse(info.protected)
    }

    @Test
    fun encryptAndDecryptRoundTrip() {
        val data = "Hello Bob, this is secret. Ünïcödé ✓".toByteArray()
        val armored = PgpEngine.encrypt(data, listOf(encKey(bob), encKey(alice)), signer = null)
        assertTrue(String(armored).startsWith("-----BEGIN PGP MESSAGE-----"))
        assertFalse("no version header", String(armored).contains("Version:"))
        // Bob can read it, and so can Alice (encrypt-to-self).
        val forBob = PgpEngine.decrypt(armored, listOf(PgpEngine.unlock(bob, "bobs secret".toCharArray())), lookup())
        assertArrayEquals(data, forBob.data)
        assertNull(forBob.signature)
        val forAlice = PgpEngine.decrypt(armored, listOf(PgpEngine.unlock(alice, pass)), lookup())
        assertArrayEquals(data, forAlice.data)
        assertEquals(2, PgpEngine.recipients(armored).size)
    }

    @Test
    fun signedAndEncryptedVerifies() {
        val data = "Signed and sealed".toByteArray()
        val armored = PgpEngine.encrypt(data, listOf(encKey(bob)), PgpEngine.unlock(alice, pass))
        val bobKey = PgpEngine.unlock(bob, "bobs secret".toCharArray())
        val known = PgpEngine.decrypt(armored, listOf(bobKey), lookup(alice))
        assertArrayEquals(data, known.data)
        assertEquals(SigStatus.VALID, known.signature?.status)
        assertEquals(PgpEngine.info(alice).fingerprint, known.signature?.signerFingerprint)
        assertEquals(setOf("alice@example.org"), known.signature?.signerEmails)
        assertFalse(known.signature!!.keyProblem)
        // Without Alice's public key the signature can't be checked.
        val unknown = PgpEngine.decrypt(armored, listOf(bobKey), lookup())
        assertEquals(SigStatus.UNKNOWN_KEY, unknown.signature?.status)
    }

    @Test
    fun notForMe() {
        val armored = PgpEngine.encrypt("x".toByteArray(), listOf(encKey(bob)), null)
        try {
            PgpEngine.decrypt(armored, listOf(PgpEngine.unlock(alice, pass)), lookup())
            fail("decrypted without the key")
        } catch (e: PgpFailure) {
            assertEquals(PgpFailure.Kind.NO_SECRET_KEY, e.kind)
        }
    }

    @Test
    fun tamperedMessageIsRefused() {
        val data = ByteArray(4000) { (it % 251).toByte() }
        val armored = PgpEngine.encrypt(data, listOf(encKey(bob)), null)
        val bin = Armor.binary(armored).copyOf()
        // Flip a byte well inside the encrypted data (past the session key packet).
        bin[bin.size - 40] = (bin[bin.size - 40].toInt() xor 0x01).toByte()
        try {
            PgpEngine.decrypt(bin, listOf(PgpEngine.unlock(bob, "bobs secret".toCharArray())), lookup())
            fail("tampering wasn't noticed")
        } catch (e: PgpFailure) {
            assertTrue(e.kind == PgpFailure.Kind.BROKEN || e.kind == PgpFailure.Kind.TOO_BIG)
        }
    }

    @Test
    fun wrongPassphraseIsRefused() {
        try {
            PgpEngine.unlock(alice, "wrong".toCharArray())
            fail("opened with the wrong passphrase")
        } catch (e: WrongPassphraseException) {
            // expected
        }
        try {
            PgpEngine.unlock(alice, CharArray(0))
            fail("opened with no passphrase")
        } catch (e: WrongPassphraseException) {
            // expected
        }
        assertFalse(PgpEngine.checkPassphrase(alice, "Correct horse battery".toCharArray()))
        assertTrue(PgpEngine.checkPassphrase(alice, pass))
        assertTrue(PgpEngine.needsPassphrase(alice))
    }

    @Test
    fun unprotectedKeyOpensWithoutPassphrase() {
        val ring = PgpEngine.generate("Open", "open@example.org", CharArray(0), KeyType.CURVE25519)
        assertFalse(PgpEngine.needsPassphrase(ring))
        val key = PgpEngine.unlock(ring, CharArray(0))
        assertEquals(PgpEngine.info(ring).fingerprint, key.fingerprint)
    }

    @Test
    fun detachedSignature() {
        val data = "Content-Type: text/plain\r\n\r\nHi\r\n".toByteArray()
        val sig = PgpEngine.signDetached(data, PgpEngine.unlock(alice, pass))
        assertTrue(String(sig).startsWith("-----BEGIN PGP SIGNATURE-----"))
        assertEquals(SigStatus.VALID, PgpEngine.verifyDetached(data, sig, lookup(alice)).status)
        val changed = data.copyOf().also { it[it.size - 3] = 'X'.code.toByte() }
        assertEquals(SigStatus.BAD, PgpEngine.verifyDetached(changed, sig, lookup(alice)).status)
        val unknown = PgpEngine.verifyDetached(data, sig, lookup(bob))
        assertEquals(SigStatus.UNKNOWN_KEY, unknown.status)
        assertTrue(PgpEngine.keyIdText(unknown.keyId).startsWith("0x"))
    }

    @Test
    fun cleartextSignature() {
        val key = PgpEngine.unlock(alice, pass)
        val lines = listOf("Hello,", "- a line starting with a dash", "trailing spaces   ", "", "Bye")
        val signed = lines.joinToString("\r\n") { it.trimEnd() }.toByteArray()
        val pub = alice.toCertificate().publicKeys.asSequence().first { it.isMasterKey }
        val gen = PGPSignatureGenerator(BcPGPContentSignerBuilder(pub.algorithm, HashAlgorithmTags.SHA256), pub)
        gen.init(PGPSignature.CANONICAL_TEXT_DOCUMENT, key.privateKeys.getValue(pub.keyID))
        gen.update(signed)
        val sigArmor = Armor.armor(gen.generate().encoded)
        val text = buildString {
            append("Some text before\n")
            append("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n")
            lines.forEach { append(if (it.startsWith("-")) "- $it" else it).append("\n") }
            append(sigArmor)
        }
        val parsed = PgpEngine.parseCleartext(text)!!
        assertTrue(parsed.shown.contains("- a line starting with a dash"))
        assertFalse(parsed.shown.contains("BEGIN PGP"))
        assertTrue(parsed.shown.startsWith("Some text before"))
        assertEquals(SigStatus.VALID, PgpEngine.verifyCleartext(parsed, lookup(alice)).status)
        // CRLF mail gives the same result.
        assertEquals(SigStatus.VALID, PgpEngine.verifyCleartext(PgpEngine.parseCleartext(text.replace("\n", "\r\n"))!!, lookup(alice)).status)
        val forged = PgpEngine.parseCleartext(text.replace("Bye", "Bye!"))!!
        assertEquals(SigStatus.BAD, PgpEngine.verifyCleartext(forged, lookup(alice)).status)
    }

    @Test
    fun rsaKeyRoundTrip() {
        val rsa = PgpEngine.generate("Old School", "rsa@example.org", "rsa pass".toCharArray(), KeyType.RSA_4096)
        val info = PgpEngine.info(rsa)
        assertEquals("RSA 4096", info.algorithm)
        assertTrue(info.canEncrypt && info.canSign)
        val unlocked = PgpEngine.unlock(rsa, "rsa pass".toCharArray())
        val data = "RSA works".toByteArray()
        val msg = PgpEngine.encrypt(data, listOf(PgpEngine.encryptionKey(rsa)!!), unlocked)
        val out = PgpEngine.decrypt(msg, listOf(unlocked), CertLookup { id -> rsa.toCertificate().takeIf { it.getPublicKey(id) != null } })
        assertArrayEquals(data, out.data)
        assertEquals(SigStatus.VALID, out.signature?.status)
    }

    @Test
    fun exportedKeyReadsBack() {
        val armored = Armor.armor(PgpEngine.publicOf(alice).encoded)
        assertTrue(armored.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        val back = PgpEngine.readKeys(armored.toByteArray())
        assertEquals(1, back.size)
        assertTrue(back[0] is PGPPublicKeyRing)
        assertEquals(PgpEngine.info(alice).fingerprint, PgpEngine.info(back[0]).fingerprint)
        val secret = PgpEngine.readKeys(Armor.armor(alice.encoded).toByteArray())
        assertTrue(secret.single() is PGPSecretKeyRing)
    }

    @Test
    fun mergeKeepsKey() {
        val pub = PgpEngine.publicOf(bob)
        val merged = PgpEngine.merge(pub, pub)
        assertEquals(PgpEngine.info(pub).fingerprint, PgpEngine.info(merged).fingerprint)
    }

    @Test
    fun emailOfUserIds() {
        assertEquals("a@b.org", PgpEngine.emailOf("Name <A@b.org>"))
        assertEquals("a@b.org", PgpEngine.emailOf("a@b.org"))
        assertNull(PgpEngine.emailOf("Just a name"))
        assertEquals("x@y.z", PgpEngine.emailOf("Weird <name> <x@y.z>"))
    }

    @Test
    fun notPgpData() {
        try {
            PgpEngine.decrypt("hello".toByteArray(), emptyList(), lookup())
            fail()
        } catch (e: PgpFailure) {
            assertTrue(e.kind == PgpFailure.Kind.NOT_PGP || e.kind == PgpFailure.Kind.BROKEN)
        }
        // Base64 of random bytes isn't a key either.
        assertTrue(PgpEngine.readKeys(Base64.getEncoder().encode(ByteArray(64) { 7 })).isEmpty())
    }
}
