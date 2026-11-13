package com.vknxworks.mailctrl.ui.inbox

import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.data.accounts.AccountColors
import com.vknxworks.mailctrl.mail.Security
import com.vknxworks.mailctrl.mail.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxSelectionTest {
    private fun account(id: String, unified: Boolean = true, color: Int = -1) = Account(
        id = id,
        email = "$id@example.org",
        imap = ServerConfig("imap.example.org", 993, Security.SSL, id),
        smtp = ServerConfig("smtp.example.org", 465, Security.SSL, id),
        unified = unified,
        color = color,
    )

    private val a = account("a")
    private val b = account("b")
    private val c = account("c", unified = false)

    @Test
    fun allInboxesUsesOnlyAccountsInIt() {
        val s = inboxSelection(listOf(a, b, c), unifiedSetting = true, chosen = null)
        assertTrue(s.allInboxes)
        assertNull(s.selectedId)
        assertEquals(listOf("a", "b"), s.ids)
        assertEquals(listOf(a, b), s.inAll)
    }

    @Test
    fun oneAccountChosen() {
        val s = inboxSelection(listOf(a, b, c), unifiedSetting = true, chosen = "c")
        assertEquals("c", s.selectedId)
        assertEquals(listOf("c"), s.ids)
    }

    @Test
    fun removedAccountFallsBackToAllInboxes() {
        val s = inboxSelection(listOf(a, b), unifiedSetting = true, chosen = "gone")
        assertNull(s.selectedId)
        assertEquals(listOf("a", "b"), s.ids)
    }

    @Test
    fun settingOffShowsFirstAccount() {
        val s = inboxSelection(listOf(a, b), unifiedSetting = false, chosen = null)
        assertFalse(s.allInboxes)
        assertEquals(listOf("a"), s.ids)
    }

    @Test
    fun singleAccountIsNeverAllInboxes() {
        val s = inboxSelection(listOf(a), unifiedSetting = true, chosen = null)
        assertFalse(s.allInboxes)
        assertEquals("a", s.selectedId)
    }

    @Test
    fun noAccountInAllInboxesMeansEveryAccount() {
        val x = account("x", unified = false)
        val y = account("y", unified = false)
        assertEquals(listOf("x", "y"), inboxSelection(listOf(x, y), true, null).ids)
    }

    @Test
    fun accountColours() {
        // A picked colour wins; otherwise a steady one from the account, the same every time.
        assertEquals(AccountColors.palette[3], AccountColors.of(account("p", color = 3)))
        assertEquals(AccountColors.of(account("q")), AccountColors.of(account("q")))
        assertEquals(AccountColors.forKey("q"), AccountColors.of(account("q", color = 99)))
        assertTrue(AccountColors.of(account("r")) in AccountColors.palette)
    }
}
