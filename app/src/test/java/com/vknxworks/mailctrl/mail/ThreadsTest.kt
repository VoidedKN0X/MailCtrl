package com.vknxworks.mailctrl.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadsTest {
    private data class M(val account: String, val id: String?, val refs: List<String>, val subject: String)

    private fun group(list: List<M>) = Threads.group(list, { it.account }, { it.id }, { it.refs }, { it.subject })

    @Test
    fun answersJoinTheMessageTheyName() {
        val list = listOf(
            M("x", "5", listOf("1", "3"), "Re: Hello"),
            M("x", "4", emptyList(), "Newsletter"),
            M("x", "3", listOf("1"), "Re: Hello"),
            M("x", "2", emptyList(), "Newsletter"),
            M("x", "1", emptyList(), "Hello"),
        )
        val threads = group(list)
        assertEquals(3, threads.size)
        assertEquals(listOf("5", "3", "1"), threads[0].map { it.id })
        // The same subject without an answer prefix (two newsletters) is not a conversation.
        assertEquals(listOf("4"), threads[1].map { it.id })
        assertEquals(listOf("2"), threads[2].map { it.id })
    }

    @Test
    fun accountsAreNeverMixed() {
        val threads = group(listOf(M("x", "2", listOf("1"), "Re: Hello"), M("y", "1", emptyList(), "Hello")))
        assertEquals(2, threads.size)
    }

    @Test
    fun twoAnswersToAMessageThatIsNotInTheListBelongTogether() {
        val threads = group(listOf(M("x", "3", listOf("1"), "Something else"), M("x", "2", listOf("1"), "Another subject")))
        assertEquals(1, threads.size)
    }

    @Test
    fun withoutHeadersAnAnswerJoinsBySubject() {
        val threads = group(listOf(M("x", null, emptyList(), "Fwd: budget 2026"), M("x", null, emptyList(), "Budget   2026")))
        assertEquals(1, threads.size)
    }

    @Test
    fun subjectsAndIds() {
        assertEquals("hi there", Threads.baseSubject("RE: Fwd:  AW: Hi  there"))
        assertTrue(Threads.isAnswer("Antw: iets"))
        assertFalse(Threads.isAnswer("Regarding: iets"))
        assertEquals(listOf("a@b", "c@d"), Threads.ids("<a@b>\r\n <c@d>"))
        assertEquals(listOf("a@b"), Threads.ids("a@b"))
        assertEquals(emptyList<String>(), Threads.ids(null))
    }
}
