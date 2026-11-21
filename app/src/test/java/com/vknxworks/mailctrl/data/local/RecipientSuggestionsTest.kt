package com.vknxworks.mailctrl.data.local

import com.vknxworks.mailctrl.mail.Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientSuggestionsTest {
    private val known = listOf(
        MailedAddress("anna@example.org", "Anna De Smet", count = 5, last = 10),
        MailedAddress("an@work.example", "", count = 1, last = 20),
        MailedAddress("bob@example.org", "Bob", count = 9, last = 5),
    )

    @Test
    fun matchesTheStartOfANameOrAnAddress() {
        assertEquals(listOf("anna@example.org", "an@work.example"), RecipientSuggestions.suggest(known, "an").map { it.email })
        assertEquals(listOf("anna@example.org"), RecipientSuggestions.suggest(known, "smet").map { it.email })
        assertEquals(listOf("an@work.example"), RecipientSuggestions.suggest(known, "work").map { it.email })
        assertTrue(RecipientSuggestions.suggest(known, "").isEmpty())
    }

    @Test
    fun onlyWhatIsBeingTypedCountsAndAddressesAlreadyThereAreLeftOut() {
        assertEquals(listOf("an@work.example"), RecipientSuggestions.suggest(known, "Anna De Smet <anna@example.org>, an").map { it.email })
        assertTrue(RecipientSuggestions.suggest(known, "bob@example.org, ").isEmpty())
    }

    @Test
    fun aPickReplacesWhatWasTyped() {
        assertEquals("Anna De Smet <anna@example.org>, ", RecipientSuggestions.complete("an", known[0]))
        assertEquals("bob@example.org, an@work.example, ", RecipientSuggestions.complete("bob@example.org, a", known[1]))
    }

    @Test
    fun sendingCountsAndRemembersTheNewestName() {
        val after = RecipientSuggestions.record(known, listOf(Address("Anna", "ANNA@example.org"), Address("", "new@example.org")), now = 99)
        val anna = after.first { it.email.equals("anna@example.org", ignoreCase = true) }
        assertEquals(6, anna.count)
        assertEquals("Anna", anna.name)
        assertEquals(1, after.first { it.email == "new@example.org" }.count)
        // A line break in an address never gets in.
        assertEquals(after.size, RecipientSuggestions.record(after, listOf(Address("", "x@y.org\nBcc: z@y.org")), now = 100).size)
    }
}
