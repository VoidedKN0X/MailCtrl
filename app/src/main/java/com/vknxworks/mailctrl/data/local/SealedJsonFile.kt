package com.vknxworks.mailctrl.data.local

import com.vknxworks.mailctrl.data.crypto.DataKeyStore
import com.vknxworks.mailctrl.data.crypto.SealedFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One small file of personal data kept on the phone (the senders whose pictures always load, the addresses you
 * have mailed, snoozed messages), encrypted with the app's data key ([SealedFile], AES-256-GCM).
 *
 * Rules that keep it safe:
 * - a file that exists but can't be opened (the key is gone, or the file is damaged) is never overwritten or
 *   deleted: [unreadable] is set, reading gives the empty value and nothing is written until the app restarts;
 * - without a data key nothing is written at all (the value then lives in memory only), so nothing personal
 *   ever lands on storage unencrypted;
 * - a write replaces the file in one step, after the sealed copy was checked by opening it again.
 *
 * Reading and writing ask the secure hardware for the key the first time: only call this off the main thread.
 */
class SealedJsonFile<T>(
    private val file: File,
    private val key: DataKeyStore,
    private val serializer: KSerializer<T>,
    private val empty: T,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false
    private var value: T = empty

    /** The file is there but can't be read; it is left alone and nothing is saved over it. */
    @Volatile
    var unreadable: Boolean = false
        private set

    @Synchronized
    fun read(): T {
        ensureLoaded()
        return value
    }

    /** Changes the value and saves it. Returns the new value. */
    @Synchronized
    fun update(transform: (T) -> T): T {
        ensureLoaded()
        val next = transform(value)
        if (next == value) return value
        value = next
        persist()
        return next
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        val k = runCatching { key.get(create = false) }.getOrNull()
        val opened = if (k == null) {
            null
        } else {
            runCatching { json.decodeFromString(serializer, String(SealedFile.open(k, file.readBytes()), Charsets.UTF_8)) }.getOrNull()
        }
        if (opened == null) unreadable = true else value = opened
    }

    private fun persist() {
        if (unreadable) return
        val k = runCatching { key.get(create = true) }.getOrNull() ?: return
        val sealed = SealedFile.sealChecked(k, json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            SealedFile.writeAtomic(file, File(file.path + ".tmp"), sealed)
        }
    }
}
