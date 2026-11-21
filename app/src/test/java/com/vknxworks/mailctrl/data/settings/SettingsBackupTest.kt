package com.vknxworks.mailctrl.data.settings

import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.data.accounts.Identity
import com.vknxworks.mailctrl.mail.Security
import com.vknxworks.mailctrl.mail.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsBackupTest {
    private val gmail = Account(
        id = "g",
        email = "me@gmail.com",
        senderName = "Me",
        imap = ServerConfig("imap.gmail.com", 993, Security.SSL, "me@gmail.com"),
        smtp = ServerConfig("smtp.gmail.com", 465, Security.SSL, "me@gmail.com"),
        secretEnc = "SECRET-PASSWORD-BLOB",
        trustedCerts = listOf("AA11BB22"),
        pgpKey = "FINGERPRINT",
        identities = listOf(Identity("alias@example.org", "Alias")),
    )
    private val proton = Account(
        id = "p",
        email = "me@proton.me",
        imap = ServerConfig("10.0.0.5", 1143, Security.NONE, "me@proton.me"),
        smtp = ServerConfig("10.0.0.5", 1025, Security.NONE, "me@proton.me"),
        secretEnc = "BRIDGE-PASSWORD-BLOB",
    )

    @Test
    fun theFileHoldsNothingSecret() {
        val text = SettingsBackups.export(AppSettings(pushEndpoint = "https://ntfy.example/upSECRET", defaultAccount = "p"), listOf(gmail, proton), "0.5.0", "2026-11-21T10:00:00+01:00")
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("BLOB"))
        assertFalse(text.contains("AA11BB22"))
        assertFalse(text.contains("FINGERPRINT"))
        assertFalse(text.contains("lock", ignoreCase = true))
        assertTrue(text.contains("alias@example.org"))
    }

    @Test
    fun newAccountsArriveWithoutPasswordOrCertificateAndUnencryptedOnesAreListed() {
        val file = SettingsBackups.parse(SettingsBackups.export(AppSettings(), listOf(gmail, proton), "0.5.0", ""))
        val restore = SettingsBackups.accounts(file, existing = emptyList())
        assertEquals(2, restore.added.size)
        assertTrue(restore.added.all { it.secretEnc.isEmpty() && it.trustedCerts.isEmpty() && it.pgpKey == null })
        assertEquals(listOf("me@proton.me"), restore.unencrypted.map { it.email })
    }

    @Test
    fun anAccountAlreadyHereKeepsItsServerPasswordAndCertificates() {
        val moved = gmail.copy(imap = ServerConfig("evil.example", 143, Security.NONE, "x"), senderName = "New name")
        val file = SettingsBackups.parse(SettingsBackups.export(AppSettings(), listOf(moved), "0.5.0", ""))
        val restore = SettingsBackups.accounts(file, existing = listOf(gmail))
        assertTrue(restore.added.isEmpty())
        val updated = restore.updated.single()
        assertEquals("New name", updated.senderName)
        assertEquals(gmail.imap, updated.imap)
        assertEquals(gmail.secretEnc, updated.secretEnc)
        assertEquals(gmail.trustedCerts, updated.trustedCerts)
        assertTrue(restore.unencrypted.isEmpty())
    }

    @Test
    fun privacySettingsStayAsTheyAreOnThePhone() {
        val onPhone = AppSettings(hideScreenshots = true, hideRecents = true, lockTimeout = LockTimeout.IMMEDIATELY, notificationContent = NotificationContent.NOTHING, encryptSubject = true, conversations = true)
        val file = SettingsBackups.parse(SettingsBackups.export(AppSettings(conversations = false, undoSend = 10), emptyList(), "0.5.0", ""))
        val after = SettingsBackups.settings(file, onPhone, emptyList())
        assertFalse(after.conversations)
        assertEquals(10, after.undoSend)
        assertTrue(after.hideScreenshots && after.hideRecents && after.encryptSubject)
        assertEquals(LockTimeout.IMMEDIATELY, after.lockTimeout)
        assertEquals(NotificationContent.NOTHING, after.notificationContent)
    }

    @Test
    fun otherFilesAreRefused() {
        try {
            SettingsBackups.parse("{\"app\":\"SomethingElse\"}")
            fail()
        } catch (e: BackupFormatException) {
            assertFalse(e.newer)
        }
        try {
            SettingsBackups.parse("{\"app\":\"MailCtrl\",\"format\":99}")
            fail()
        } catch (e: BackupFormatException) {
            assertTrue(e.newer)
        }
    }
}
