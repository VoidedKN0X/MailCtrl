package com.vknxworks.mailctrl.mail

import android.content.Context
import com.vknxworks.mailctrl.data.crypto.KeystoreCrypto
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Copies of messages kept on the phone, so they open without asking the server (as Thunderbird does). Messages
 * are kept whole, exactly as received, and encrypted (AES-256-GCM) with a key that is itself encrypted by the
 * phone's secure hardware (Android Keystore) and never leaves this phone. Each copy is tied to its account, folder
 * and message number, so copies can't be swapped. Up to [LIMIT] in all; the copies opened least recently go first.
 *
 * Only used while [enabled] ("Keep messages on this phone" in Settings); turning that off removes every copy.
 */
class MessageStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "messages")
    private val keyFile = File(context.noBackupFilesDir, "messages.key")
    private val random = SecureRandom()

    /** Whether new copies are kept. Set from the settings once they're loaded; until then nothing is written. */
    @Volatile
    var enabled: Boolean = false

    /** Bytes on disk, counted once on first use and kept up to date after that. */
    private val used = AtomicLong(-1)

    // ------------------------------------------------------------------ key

    @Volatile
    private var key: SecretKey? = null

    /**
     * The key that encrypts the copies: random, made once, and stored only encrypted by the Android Keystore. Only
     * this one Keystore call per app start; the copies themselves are encrypted in software, which is fast.
     */
    @Synchronized
    private fun key(): SecretKey {
        key?.let { return it }
        val stored = runCatching { keyFile.readText() }.getOrNull()
        val decoded = stored?.let { KeystoreCrypto.decrypt(it) }?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
        val k = if (decoded != null && decoded.size == 32) {
            SecretKeySpec(decoded, "AES")
        } else {
            // No key yet, or it can't be read any more (the Keystore was reset): copies made with it are unreadable.
            root.deleteRecursively()
            used.set(0)
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            val tmp = File(keyFile.path + ".tmp")
            tmp.writeText(KeystoreCrypto.encrypt(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
            if (!tmp.renameTo(keyFile)) throw IllegalStateException("Can't save the message key")
            SecretKeySpec(bytes, "AES")
        }
        key = k
        return k
    }

    // ------------------------------------------------------------------ files

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    private fun accountDir(accountId: String) = File(root, hash(accountId))
    private fun folderDir(accountId: String, folder: String) = File(accountDir(accountId), hash(folder))
    private fun file(accountId: String, folder: String, uid: Long) = File(folderDir(accountId, folder), uid.toString())

    /** What the encryption is bound to: a copy only decrypts as the message it was saved as. */
    private fun label(accountId: String, folder: String, uid: Long) = "$accountId\u0000$folder\u0000$uid".toByteArray(Charsets.UTF_8)

    fun has(accountId: String, folder: String, uid: Long): Boolean = file(accountId, folder, uid).exists()

    /** The message as received, or null when there's no copy (or it can't be read; then it's removed). */
    fun get(accountId: String, folder: String, uid: Long): ByteArray? {
        val f = file(accountId, folder, uid)
        if (!f.exists()) return null
        return try {
            val data = f.readBytes()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV))
            cipher.updateAAD(label(accountId, folder, uid))
            val plain = cipher.doFinal(data, IV, data.size - IV)
            // Opened now: kept longer than copies nobody looked at.
            f.setLastModified(System.currentTimeMillis())
            plain
        } catch (_: Exception) {
            delete(f)
            null
        }
    }

    /** Keeps a copy of a message (up to [MAX_ONE]); does nothing while turned off. */
    fun put(accountId: String, folder: String, uid: Long, raw: ByteArray) {
        if (!enabled || raw.isEmpty() || raw.size > MAX_ONE) return
        runCatching {
            val iv = ByteArray(IV).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(label(accountId, folder, uid))
            val sealed = iv + cipher.doFinal(raw)
            val target = file(accountId, folder, uid)
            // Counted before this copy is written, so it's added exactly once below.
            used()
            target.parentFile?.mkdirs()
            val before = if (target.exists()) target.length() else 0L
            // Written next to it and then renamed: a copy is either whole or not there.
            val tmp = File(target.parentFile, "$uid.tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
            if (used.get() >= 0) used.addAndGet(sealed.size - before)
            if (used.get() > LIMIT) trim()
        }
    }

    fun remove(accountId: String, folder: String, uids: Collection<Long>) {
        uids.forEach { delete(file(accountId, folder, it)) }
    }

    /** Every copy of one folder (when the server renumbered it). */
    fun clearFolder(accountId: String, folder: String) = clearDir(folderDir(accountId, folder))

    fun clearAccount(accountId: String) = clearDir(accountDir(accountId))

    /** Removes every copy ("Keep messages on this phone" turned off). */
    fun clearAll() = clearDir(root)

    private fun clearDir(dir: File) {
        if (!dir.exists()) return
        dir.deleteRecursively()
        used.set(-1)
    }

    private fun delete(f: File) {
        val size = f.length()
        if (f.delete() && used.get() >= 0) used.addAndGet(-size)
    }

    private fun allFiles(): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    private fun used(): Long {
        if (used.get() < 0) used.compareAndSet(-1, allFiles().sumOf { it.length() })
        return used.get()
    }

    /** Over [LIMIT]: removes the copies opened (or saved) least recently, down to three quarters of it. */
    @Synchronized
    private fun trim() {
        val files = allFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= LIMIT * 3 / 4) break
            val size = f.length()
            if (f.delete()) total -= size
        }
        used.set(total)
    }

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV = 12

        /** Room for copies on the phone, in all. */
        const val LIMIT = 300L * 1024 * 1024

        /** The biggest message kept (one opened yourself; downloading ahead stops well below this). */
        const val MAX_ONE = 1024 * 1024
    }
}
