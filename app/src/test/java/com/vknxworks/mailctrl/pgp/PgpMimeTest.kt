package com.vknxworks.mailctrl.pgp

import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.ContentType
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/** PGP/MIME messages built the way MailCtrl sends them, written out, read back and checked. */
class PgpMimeTest {
    private val session = Session.getInstance(Properties())

    private companion object {
        val alice: PGPSecretKeyRing by lazy { PgpEngine.generate("Alice", "alice@example.org", CharArray(0), KeyType.CURVE25519) }
        val bob: PGPSecretKeyRing by lazy { PgpEngine.generate("Bob", "bob@example.net", CharArray(0), KeyType.CURVE25519) }
    }

    private val certs = CertLookup { id -> listOf(alice, bob).map { it.toCertificate() }.firstOrNull { it.getPublicKey(id) != null } }

    private fun inner(text: String, attachment: ByteArray? = null): PgpMime.Inner = PgpMime.Inner().apply {
        if (attachment == null) {
            setText(text, "UTF-8")
            setHeader("Content-Transfer-Encoding", "quoted-printable")
        } else {
            val mp = MimeMultipart("mixed")
            mp.addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8"); setHeader("Content-Transfer-Encoding", "quoted-printable") })
            mp.addBodyPart(
                MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(attachment, "application/octet-stream"))
                    fileName = "data.bin"
                    disposition = Part.ATTACHMENT
                    setHeader("Content-Transfer-Encoding", "base64")
                },
            )
            setContent(mp)
        }
    }

    private fun message(): MimeMessage = MimeMessage(session).apply {
        setFrom(InternetAddress("alice@example.org"))
        setRecipient(javax.mail.Message.RecipientType.TO, InternetAddress("bob@example.net"))
        subject = "Test"
    }

    private fun roundTrip(m: MimeMessage): Pair<ByteArray, MimeMessage> {
        m.saveChanges()
        val out = ByteArrayOutputStream()
        m.writeTo(out)
        val raw = out.toByteArray()
        return raw to MimeMessage(session, ByteArrayInputStream(raw))
    }

    @Test
    fun signedMessageVerifiesAfterSending() {
        val content = PgpMime.serialize(inner("Hello Bob,\nthis line ends with spaces   \nand ünïcödé.\n"))
        assertTrue(String(content).contains("quoted-printable"))
        val sig = PgpEngine.signDetached(content, PgpEngine.unlock(alice, CharArray(0)))
        val m = message().apply { setContent(PgpMime.signed(content, sig)) }
        val (raw, parsed) = roundTrip(m)

        val ct = ContentType(parsed.contentType)
        assertTrue(ct.match("multipart/signed"))
        assertEquals("application/pgp-signature", ct.getParameter("protocol"))
        assertEquals("pgp-sha256", ct.getParameter("micalg"))

        val found = PgpMime.find(parsed) as PgpMime.Found.Signed
        val signed = PgpMime.signedContent(raw, found.boundary)!!
        assertArrayEquals(content, signed)
        assertEquals(SigStatus.VALID, PgpEngine.verifyDetached(signed, found.signature, certs).status)

        // The first part reads as a normal message part.
        val first = (parsed.content as Multipart).getBodyPart(0)
        assertTrue((first.content as String).startsWith("Hello Bob,"))
    }

    @Test
    fun signedWithAttachmentVerifies() {
        val content = PgpMime.serialize(inner("See attached", ByteArray(3000) { it.toByte() }))
        val sig = PgpEngine.signDetached(content, PgpEngine.unlock(alice, CharArray(0)))
        val (raw, parsed) = roundTrip(message().apply { setContent(PgpMime.signed(content, sig)) })
        val found = PgpMime.find(parsed) as PgpMime.Found.Signed
        assertEquals(SigStatus.VALID, PgpEngine.verifyDetached(PgpMime.signedContent(raw, found.boundary)!!, found.signature, certs).status)
    }

    @Test
    fun signedContentWithBareLineFeeds() {
        // Stored with LF only (some servers and saved .eml files): still the CRLF bytes that were signed.
        val content = PgpMime.serialize(inner("Line one\nLine two\n"))
        val sig = PgpEngine.signDetached(content, PgpEngine.unlock(alice, CharArray(0)))
        val (raw, _) = roundTrip(message().apply { setContent(PgpMime.signed(content, sig)) })
        val lf = String(raw, Charsets.ISO_8859_1).replace("\r\n", "\n").toByteArray(Charsets.ISO_8859_1)
        val parsed = MimeMessage(session, ByteArrayInputStream(lf))
        val found = PgpMime.find(parsed) as PgpMime.Found.Signed
        val signed = PgpMime.signedContent(lf, found.boundary)!!
        assertEquals(SigStatus.VALID, PgpEngine.verifyDetached(signed, found.signature, certs).status)
    }

    @Test
    fun changedSignedMessageIsBad() {
        val content = PgpMime.serialize(inner("Pay 10 euros"))
        val sig = PgpEngine.signDetached(content, PgpEngine.unlock(alice, CharArray(0)))
        val (raw, _) = roundTrip(message().apply { setContent(PgpMime.signed(content, sig)) })
        val forged = String(raw, Charsets.ISO_8859_1).replace("Pay 10 euros", "Pay 99 euros").toByteArray(Charsets.ISO_8859_1)
        val parsed = MimeMessage(session, ByteArrayInputStream(forged))
        val found = PgpMime.find(parsed) as PgpMime.Found.Signed
        assertEquals(SigStatus.BAD, PgpEngine.verifyDetached(PgpMime.signedContent(forged, found.boundary)!!, found.signature, certs).status)
    }

    @Test
    fun encryptedMessageStructureAndContent() {
        val attachment = ByteArray(5000) { (it * 3).toByte() }
        val content = PgpMime.serialize(inner("Secret text", attachment))
        val armored = PgpEngine.encrypt(content, listOf(PgpEngine.encryptionKey(bob)!!, PgpEngine.encryptionKey(alice)!!), PgpEngine.unlock(alice, CharArray(0)))
        val (_, parsed) = roundTrip(message().apply { setContent(PgpMime.encrypted(armored)) })

        val ct = ContentType(parsed.contentType)
        assertTrue(ct.match("multipart/encrypted"))
        assertEquals("application/pgp-encrypted", ct.getParameter("protocol"))
        val mp = parsed.content as Multipart
        assertEquals(2, mp.count)
        assertTrue(mp.getBodyPart(0).isMimeType("application/pgp-encrypted"))
        assertTrue(mp.getBodyPart(1).isMimeType("application/octet-stream"))
        assertEquals("encrypted.asc", mp.getBodyPart(1).fileName)

        val found = PgpMime.find(parsed) as PgpMime.Found.Encrypted
        val clear = PgpEngine.decrypt(found.data, listOf(PgpEngine.unlock(bob, CharArray(0))), certs)
        assertEquals(SigStatus.VALID, clear.signature?.status)
        assertArrayEquals(content, clear.data)
        // The decrypted part is a normal MIME entity with the text and the attachment.
        val innerMsg = MimeMessage(session, ByteArrayInputStream(clear.data))
        val parts = innerMsg.content as Multipart
        assertEquals("Secret text", parts.getBodyPart(0).content)
        assertArrayEquals(attachment, parts.getBodyPart(1).inputStream.readBytes())
    }

    @Test
    fun findsNestedPgpMime() {
        val content = PgpMime.serialize(inner("Nested"))
        val armored = PgpEngine.encrypt(content, listOf(PgpEngine.encryptionKey(bob)!!), null)
        val encryptedPart = MimeBodyPart().apply { setContent(PgpMime.encrypted(armored)) }
        val outer = MimeMultipart("mixed").apply {
            addBodyPart(encryptedPart)
            addBodyPart(MimeBodyPart().apply { setText("-- \nmailing list footer") })
        }
        val (_, parsed) = roundTrip(message().apply { setContent(outer) })
        assertNotNull(PgpMime.find(parsed) as? PgpMime.Found.Encrypted)
    }

    @Test
    fun plainMessageIsNotPgp() {
        val (_, parsed) = roundTrip(message().apply { setText("Just text") })
        assertNull(PgpMime.find(parsed))
        val mixed = message().apply {
            setContent(MimeMultipart("mixed").apply { addBodyPart(MimeBodyPart().apply { setText("a") }) })
        }
        assertNull(PgpMime.find(roundTrip(mixed).second))
    }

    @Test
    fun signedContentEdgeCases() {
        assertNull(PgpMime.signedContent("no boundary here".toByteArray(), "b"))
        val raw = "Content-Type: multipart/signed; boundary=\"b\"\r\n\r\n--b\r\nX: y\r\n\r\nbody\r\n--b\r\nsig\r\n--b--\r\n"
        assertEquals("X: y\r\n\r\nbody", String(PgpMime.signedContent(raw.toByteArray(), "b")!!))
        // A boundary that only starts a longer line isn't the boundary.
        val tricky = "--bb\r\n--b\r\nA\r\n--b \r\nB\r\n--b--\r\n"
        assertEquals("A", String(PgpMime.signedContent(tricky.toByteArray(), "b")!!))
    }

    @Test
    fun canonicalLineEnds() {
        assertEquals("a\r\nb\r\nc\r\n", String(PgpMime.canonical("a\nb\r\nc\r".toByteArray())))
        assertFalse(String(PgpMime.canonical("x\r\n".toByteArray())).contains("\r\r"))
    }
}
