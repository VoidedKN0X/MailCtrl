package com.vknxworks.mailctrl.data.crypto

import java.io.File
import java.security.SecureRandom

/** Wraps and unwraps the data key with a key that never leaves the phone's secure hardware. */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * The app's **data key**: 32 random bytes that encrypt the message list at rest ([SealedFile]). It is
 * stored in `data.key` in the app's private folder (the part that is never backed up), itself encrypted
 * ("wrapped") by the AES-256-GCM key in the Android Keystore that also protects passwords
 * ([KeystoreKeyWrapper]), and kept in memory while the app runs. Like that key it needs no fingerprint or
 * PIN, so mail checks keep working in the background.
 *
 * Rules that keep data safe:
 * - an existing `data.key` is never overwritten or replaced by a new key: if it can't be unwrapped (the
 *   Keystore key is gone) there is simply no key, and the message list is kept unencrypted as before;
 * - a new key is only handed out after its file was written in one step, read back and unwrapped again.
 */
class DataKeyStore(dir: File, private val wrapper: KeyWrapper) {
    private val file = File(dir, FILE)
    private val tmp = File(dir, "$FILE.tmp")
    private val aside = File(dir, "$FILE.unreadable")

    private var cached: ByteArray? = null
    /** Unwrapping or making the key failed in this run; not tried again until [reset]. */
    private var failed = false

    /**
     * The data key, or null when there is none to be had. With [create] a new key is made when no key file
     * exists yet. Slow the first time (it asks the secure hardware): call it off the main thread.
     */
    @Synchronized
    fun get(create: Boolean): ByteArray? {
        cached?.let { return it }
        if (failed) return null
        if (file.exists()) {
            val key = try {
                wrapper.unwrap(file.readBytes()).takeIf { it.size == KEY_LEN }
            } catch (e: Throwable) {
                null
            }
            if (key == null) failed = true else cached = key
            return key
        }
        if (!create) return null
        var written = false
        try {
            val key = ByteArray(KEY_LEN).also(SecureRandom()::nextBytes)
            SealedFile.writeAtomic(file, tmp, wrapper.wrap(key))
            written = true
            if (!wrapper.unwrap(file.readBytes()).contentEquals(key)) throw IllegalStateException("key check failed")
            cached = key
            return key
        } catch (e: Throwable) {
            // Nothing was encrypted with this key yet (it was never handed out), so its file can go.
            failed = true
            try {
                if (written) file.delete()
                tmp.delete()
            } catch (ignored: Throwable) {
            }
            return null
        }
    }

    /** Forgets that the key couldn't be had, so the next [get] asks the secure hardware again. */
    @Synchronized
    fun reset() {
        failed = false
    }

    /**
     * Gives up on a key that can't be unwrapped, so a new one is made at the next [get]. With [keep] the old
     * key file is set aside as `data.key.unreadable` instead of deleted.
     */
    @Synchronized
    fun discard(keep: Boolean) {
        cached = null
        failed = false
        try {
            if (keep && file.exists()) {
                aside.delete()
                file.renameTo(aside)
            }
            file.delete()
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        const val FILE = "data.key"
        private const val KEY_LEN = 32
    }
}
