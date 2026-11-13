package com.vknxworks.mailctrl.pgp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocryptWkdTest {
    @Test
    fun autocryptRoundTrip() {
        val key = PgpEngine.publicOf(PgpEngine.generate("Me", "me@example.org", CharArray(0), KeyType.CURVE25519)).encoded
        val header = Autocrypt.build("me@example.org", key)
        assertTrue(header.startsWith("addr=me@example.org; keydata="))
        // Folded: no line longer than 78 characters.
        assertTrue(header.split("\r\n").all { it.length <= 78 })
        val parsed = Autocrypt.parse(header)!!
        assertEquals("me@example.org", parsed.addr)
        assertFalse(parsed.preferEncrypt)
        assertArrayEquals(key, parsed.keyData)
        assertEquals(1, PgpEngine.readKeys(parsed.keyData).size)
    }

    @Test
    fun autocryptAttributes() {
        val h = Autocrypt.parse("addr=Someone@Example.org; prefer-encrypt=mutual; _ignored=1; keydata=AAEC AwQ=")
        assertNotNull(h)
        assertEquals("someone@example.org", h!!.addr)
        assertTrue(h.preferEncrypt)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), h.keyData)
        // An unknown attribute without "_" makes the header invalid (Autocrypt level 1).
        assertNull(Autocrypt.parse("addr=a@b.c; critical=1; keydata=AAEC"))
        assertNull(Autocrypt.parse("keydata=AAEC"))
        assertNull(Autocrypt.parse("addr=a@b.c"))
        assertNull(Autocrypt.parse("addr=a@b.c; keydata=!!!"))
    }

    @Test
    fun wkdHashMatchesTheSpecification() {
        // The example from the Web Key Directory draft.
        assertEquals("iy9q119eutrkn8s1mk4r39qejnbu3n5q", Wkd.hash("Joe.Doe"))
        val urls = Wkd.urls("Joe.Doe@Example.ORG")
        assertEquals(
            "https://openpgpkey.example.org/.well-known/openpgpkey/example.org/hu/iy9q119eutrkn8s1mk4r39qejnbu3n5q?l=Joe.Doe",
            urls[0],
        )
        assertEquals("https://example.org/.well-known/openpgpkey/hu/iy9q119eutrkn8s1mk4r39qejnbu3n5q?l=Joe.Doe", urls[1])
        assertEquals("example.org", Wkd.domainOf("Joe.Doe@Example.ORG"))
    }

    @Test
    fun wkdRefusesOddDomains() {
        listOf("nobody", "a@", "a@exa mple.org", "a@exa/mple.org", "a@.org", "a@x..org").forEach { bad ->
            assertTrue(bad, runCatching { Wkd.urls(bad) }.isFailure)
        }
        // The local part is escaped in the query.
        assertTrue(Wkd.urls("a b+c@example.org")[1].endsWith("?l=a%20b%2Bc"))
    }

    @Test
    fun zbase32() {
        assertEquals("", Wkd.zbase32(ByteArray(0)))
        assertEquals("yy", Wkd.zbase32(byteArrayOf(0)))
        assertEquals("6y", Wkd.zbase32(byteArrayOf(0xF0.toByte())))
    }
}
