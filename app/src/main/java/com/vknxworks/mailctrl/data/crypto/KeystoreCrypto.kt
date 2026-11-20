package com.vknxworks.mailctrl.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts what MailCtrl keeps on the phone (passwords, drafts) with a non-exportable AES key held in the Android Keystore. */
object KeystoreCrypto {
    private const val ALIAS = "mailctrl_data"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    // One at a time: two callers making the key at once would each make one, and whatever the first encrypted
    // could never be read again.
    // The handle to the key, kept after the first look-up so later calls skip the Keystore round trip.
    // It is only a reference: the key itself never leaves the secure hardware.
    private var cached: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cached?.let { return it }
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { cached = it.secretKey; return it.secretKey }
        // A new key goes into the phone's separate security chip (StrongBox) when it has one, otherwise into the
        // regular secure hardware. A key made before stays where it is, so nothing has to be encrypted again.
        return runCatching { generate(strongBox = true) }.getOrElse { generate(strongBox = false) }.also { cached = it }
    }

    @Synchronized
    private fun forget() { cached = null }

    /** Runs [block] with the key; if the kept handle stopped working, looks the key up again and tries once more. */
    private inline fun <T> withKey(block: (SecretKey) -> T): T =
        try {
            block(key())
        } catch (e: Exception) {
            forget()
            block(key())
        }

    private fun generate(strongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String = withKey { key ->
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        withKey { key ->
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }
    }.getOrNull()
}
