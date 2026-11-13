package com.vknxworks.mailctrl.pgp

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Keys as people really send them: pasted into mail, with odd line ends, headers, or several in one file. */
class ArmorImportTest {
    private companion object {
        val one: PGPSecretKeyRing by lazy { PgpEngine.generate("One", "one@example.org", CharArray(0), KeyType.CURVE25519) }
        val two: PGPSecretKeyRing by lazy { PgpEngine.generate("Two", "two@example.org", CharArray(0), KeyType.CURVE25519) }
    }

    private val pubOne get() = PgpEngine.publicOf(one).encoded
    private val pubTwo get() = PgpEngine.publicOf(two).encoded
    private val fpOne get() = PgpEngine.info(one).fingerprint
    private val fpTwo get() = PgpEngine.info(two).fingerprint

    private fun fps(text: String) = PgpEngine.readKeys(text.toByteArray()).map { PgpEngine.info(it).fingerprint }

    /** Armor written by hand, so headers and checksum can be left out or added. */
    private fun armor(data: ByteArray, headers: List<String> = emptyList(), crc: Boolean = true, type: String = "PUBLIC KEY BLOCK"): String {
        val b64 = Base64.getEncoder().encodeToString(data).chunked(64)
        return buildString {
            append("-----BEGIN PGP $type-----\n")
            headers.forEach { append(it).append('\n') }
            append('\n')
            b64.forEach { append(it).append('\n') }
            if (crc) append("=").append(crc24(data)).append('\n')
            append("-----END PGP $type-----\n")
        }
    }

    private fun crc24(data: ByteArray): String {
        var crc = 0xB704CE
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 16)
            repeat(8) {
                crc = crc shl 1
                if (crc and 0x1000000 != 0) crc = crc xor 0x1864CFB
            }
        }
        crc = crc and 0xFFFFFF
        return Base64.getEncoder().encodeToString(byteArrayOf((crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte()))
    }

    @Test
    fun withHeaders() {
        assertEquals(listOf(fpOne), fps(armor(pubOne, listOf("Version: GnuPG v2", "Comment: https://example.org/key"))))
    }

    @Test
    fun crlfLineEnds() {
        assertEquals(listOf(fpOne), fps(armor(pubOne).replace("\n", "\r\n")))
    }

    @Test
    fun textAroundTheBlock() {
        val mail = "Hi! Here is my key:\n\n" + armor(pubOne) + "\nThanks,\nOne\n"
        assertEquals(listOf(fpOne), fps(mail))
    }

    @Test
    fun noHeadersAndNoBlankLine() {
        // Some tools leave the blank line out when there are no headers.
        val text = armor(pubOne).replace("-----\n\n", "-----\n")
        assertEquals(listOf(fpOne), fps(text))
    }

    @Test
    fun withoutChecksum() {
        assertEquals(listOf(fpOne), fps(armor(pubOne, crc = false)))
    }

    @Test
    fun indentedLines() {
        val text = armor(pubOne).lines().joinToString("\n") { "    $it" }
        assertEquals(listOf(fpOne), fps(text))
    }

    @Test
    fun trailingSpaces() {
        val text = armor(pubOne).lines().joinToString("\n") { "$it  \t" }
        assertEquals(listOf(fpOne), fps(text))
    }

    @Test
    fun twoBlocksInOneFile() {
        assertEquals(listOf(fpOne, fpTwo), fps(armor(pubOne) + "\n" + armor(pubTwo)))
    }

    @Test
    fun twoKeysInOneBlock() {
        assertEquals(listOf(fpOne, fpTwo), fps(armor(pubOne + pubTwo)))
    }

    @Test
    fun binaryKeys() {
        val keys = PgpEngine.readKeys(pubOne + pubTwo)
        assertEquals(listOf(fpOne, fpTwo), keys.map { PgpEngine.info(it).fingerprint })
        assertTrue(keys.all { it is PGPPublicKeyRing })
    }

    @Test
    fun secretKeyBlock() {
        val keys = PgpEngine.readKeys(armor(one.encoded, type = "PRIVATE KEY BLOCK").toByteArray())
        assertTrue(keys.single() is PGPSecretKeyRing)
    }

    @Test
    fun quotedBlockIsIgnored() {
        val quoted = armor(pubOne).lines().joinToString("\n") { "> $it" }
        assertTrue(fps(quoted).isEmpty())
    }

    @Test
    fun missingEndLine() {
        val text = armor(pubOne).substringBefore("-----END")
        assertTrue(fps(text).isEmpty())
    }

    @Test
    fun garbage() {
        assertTrue(fps("-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nnot base64 at all !!!\n-----END PGP PUBLIC KEY BLOCK-----\n").isEmpty())
        assertTrue(PgpEngine.readKeys(ByteArray(300) { (it * 7).toByte() }).isEmpty())
        assertTrue(fps("").isEmpty())
    }

    @Test
    fun damagedSecondKeyKeepsFirst() {
        val broken = pubOne + pubTwo.copyOf(pubTwo.size / 2)
        val keys = PgpEngine.readKeys(armor(broken).toByteArray())
        assertEquals(fpOne, PgpEngine.info(keys.first()).fingerprint)
    }

    @Test
    fun messageBlocksArentKeys() {
        val msg = PgpEngine.encrypt("x".toByteArray(), listOf(PgpEngine.encryptionKey(one)!!), null)
        assertTrue(PgpEngine.readKeys(msg).isEmpty())
    }
}
