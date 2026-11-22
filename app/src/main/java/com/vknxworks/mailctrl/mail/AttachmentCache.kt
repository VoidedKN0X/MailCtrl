package com.vknxworks.mailctrl.mail

import java.io.File

/**
 * The attachment files in `cacheDir/attachments/<random>/`, written to open, share or save an attachment (also
 * decrypted ones of OpenPGP and S/MIME messages). They are all removed when MailCtrl starts, and the ones older
 * than [KEEP_MS] when the app locks: "Instant with an open connection" can keep MailCtrl running for days, and a
 * decrypted attachment must not stay on disk that long. The few minutes give an app that was just handed a file
 * the time to open it.
 */
object AttachmentCache {
    const val KEEP_MS = 5 * 60_000L

    fun root(cacheDir: File): File = File(cacheDir, "attachments")

    /**
     * Deletes every folder under [root] whose newest file (or the folder itself) is older than [keepMs] at [now].
     * Returns how many folders were removed. Never throws.
     */
    fun prune(root: File, now: Long, keepMs: Long = KEEP_MS): Int {
        val dirs = runCatching { root.listFiles() }.getOrNull() ?: return 0
        var removed = 0
        for (dir in dirs) {
            val newest = runCatching {
                if (dir.isDirectory) {
                    (dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L).coerceAtLeast(dir.lastModified())
                } else {
                    dir.lastModified()
                }
            }.getOrDefault(0L)
            if (now - newest >= keepMs && runCatching { dir.deleteRecursively() }.getOrDefault(false)) removed++
        }
        return removed
    }
}
