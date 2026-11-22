package com.vknxworks.mailctrl.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttachmentCacheTest {
    private fun folder(root: File, name: String, modified: Long): File {
        val dir = File(root, name).apply { mkdirs() }
        val file = File(dir, "file.pdf").apply { writeText("x") }
        file.setLastModified(modified)
        dir.setLastModified(modified)
        return dir
    }

    @Test fun oldFoldersGoAndRecentOnesStay() {
        val root = Files.createTempDirectory("att").toFile()
        try {
            val now = 10_000_000_000L
            val old = folder(root, "old", now - AttachmentCache.KEEP_MS - 1_000)
            val recent = folder(root, "recent", now - 60_000)
            assertEquals(1, AttachmentCache.prune(root, now))
            assertFalse(old.exists())
            assertTrue(recent.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun aMissingFolderIsFine() {
        assertEquals(0, AttachmentCache.prune(File("/nonexistent/attachments-${System.nanoTime()}"), 0L))
    }
}
