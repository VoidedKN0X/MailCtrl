package com.vknxworks.mailctrl.data.crypto

import android.util.Base64

/**
 * Wraps the data key with the Keystore key MailCtrl already has for passwords and drafts ([KeystoreCrypto]:
 * AES-256-GCM, not exportable, no fingerprint or PIN needed), so there is no second Keystore key:
 *
 *     "MAILCTRL-KEY1:" | what KeystoreCrypto.encrypt made of the key (Base64 text)
 *
 * The data key itself stays a separate, random key; passwords and drafts are not touched by this.
 */
class KeystoreKeyWrapper : KeyWrapper {
    override fun wrap(plain: ByteArray): ByteArray =
        (PREFIX + KeystoreCrypto.encrypt(Base64.encodeToString(plain, Base64.NO_WRAP))).toByteArray(Charsets.US_ASCII)

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val text = String(wrapped, Charsets.US_ASCII)
        if (!text.startsWith(PREFIX)) throw IllegalStateException("not a key file")
        // Null when the Keystore key is gone or not the one this was wrapped with.
        val inner = KeystoreCrypto.decrypt(text.substring(PREFIX.length)) ?: throw IllegalStateException("can't unwrap the data key")
        return Base64.decode(inner, Base64.NO_WRAP)
    }

    private companion object {
        const val PREFIX = "MAILCTRL-KEY1:"
    }
}
