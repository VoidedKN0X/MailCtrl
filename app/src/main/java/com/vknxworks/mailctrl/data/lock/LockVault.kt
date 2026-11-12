package com.vknxworks.mailctrl.data.lock

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The app lock's secrets (the shared Ctrl series lock).
 *
 * - A random 256-bit **vault key** encrypts your unsent drafts. It is only in memory while the app is
 *   unlocked and is wiped when it locks, so a locked app can't read its own drafts.
 * - The vault key is stored twice, encrypted:
 *   - with a key made from your **PIN**: PBKDF2 (600 000 rounds) and then an HMAC with a key that never
 *     leaves the phone's secure hardware (StrongBox when there is one). Copying the app's files off the
 *     phone isn't enough to guess the PIN somewhere else.
 *   - with a Keystore key that only works right after a **strong biometric** match (fingerprint), and
 *     stops working when a new fingerprint is added.
 * - Wrong PINs lock the screen for 30 seconds after 5 tries, doubling up to 15 minutes. The counter
 *   survives restarts and the wait can't be skipped by changing the clock.
 * - The PIN is needed instead of the fingerprint after the phone restarts, and at least every 72 hours.
 *
 * Nothing here ever stores the PIN itself.
 */
class LockVault(context: Context, private val aliasPrefix: String, prefsName: String = "app_lock_vault") {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private var _enabled by mutableStateOf(prefs.contains(K_PIN_WRAPPED))
    /** A PIN is set, so the app lock is on. */
    val enabled: Boolean get() = _enabled

    private var _bio by mutableStateOf(prefs.contains(K_BIO_WRAPPED))
    /** Fingerprint unlock is set up. */
    val biometricEnabled: Boolean get() = _enabled && _bio

    /** Number of digits in the PIN, so the lock screen can check it as soon as it's complete. */
    val pinLength: Int get() = prefs.getInt(K_PIN_LENGTH, 0)

    @Volatile private var vaultKey: ByteArray? = null

    /** The vault key is in memory: the app is unlocked. */
    val isOpen: Boolean get() = vaultKey != null

    // ---------------------------------------------------------------- PIN

    /** Turns the lock on with [pin], making a new vault key. The vault is open afterwards. */
    fun setUp(pin: String) {
        require(pin.length in PIN_MIN..PIN_MAX && pin.all(Char::isDigit))
        close()
        deleteKey(bioAlias)
        val key = ByteArray(32).also(random::nextBytes)
        storePin(pin, key)
        prefs.edit().remove(K_BIO_WRAPPED).remove(K_FAILED).remove(K_LOCKOUT_REMAINING)
            .remove(K_LOCKOUT_BASE).remove(K_LOCKOUT_BOOT).commit()
        _bio = false
        _enabled = true
        vaultKey = key
        markPinUsed()
    }

    /** Changes the PIN while unlocked. The vault key stays the same, so nothing has to be encrypted again. */
    fun changePin(newPin: String): Boolean {
        require(newPin.length in PIN_MIN..PIN_MAX && newPin.all(Char::isDigit))
        val key = vaultKey ?: return false
        storePin(newPin, key)
        markPinUsed()
        return true
    }

    private fun storePin(pin: String, key: ByteArray) {
        val salt = ByteArray(16).also(random::nextBytes)
        val kek = deriveKek(pin, salt)
        val wrapped = aesEncrypt(kek, key)
        kek.fill(0)
        prefs.edit()
            .putString(K_PIN_SALT, b64(salt))
            .putString(K_PIN_WRAPPED, b64(wrapped))
            .putInt(K_PIN_LENGTH, pin.length)
            .commit()
    }

    /**
     * Checks [pin] (slow on purpose; call it off the main thread). Every try is counted before it's
     * checked, so killing the app mid-check doesn't give a free try.
     */
    fun unlockWithPin(pin: String): PinResult {
        if (lockoutRemaining() > 0) return PinResult.LockedOut
        val fails = failedAttempts + 1
        failedAttempts = fails
        val salt = prefs.getString(K_PIN_SALT, null)?.let(::unb64) ?: return PinResult.Wrong
        val wrapped = prefs.getString(K_PIN_WRAPPED, null)?.let(::unb64) ?: return PinResult.Wrong
        val kek = runCatching { deriveKek(pin, salt) }.getOrNull() ?: return PinResult.Wrong
        val key = runCatching { aesDecrypt(kek, wrapped) }.getOrNull()
        kek.fill(0)
        if (key == null) {
            if (fails >= MAX_TRIES) {
                // 30 s, doubling for every further batch of wrong PINs, capped at 15 minutes.
                startLockout((30_000L shl ((fails - MAX_TRIES) / MAX_TRIES).coerceAtMost(5)).coerceAtMost(900_000L))
            }
            return PinResult.Wrong
        }
        failedAttempts = 0
        vaultKey = key
        markPinUsed()
        return PinResult.Ok
    }

    enum class PinResult { Ok, Wrong, LockedOut }

    /** PBKDF2 stretches the PIN, then the hardware-only key mixes in a secret that never leaves the phone. */
    private fun deriveKek(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ROUNDS, 256)
        val stretched = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(pepperKey())
        val out = mac.doFinal(stretched)
        stretched.fill(0)
        return out
    }

    private fun pepperKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(pepperAlias, null) as? SecretKey)?.let { return it }
        fun make(strongBox: Boolean): SecretKey {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(pepperAlias, KeyProperties.PURPOSE_SIGN)
                    .setIsStrongBoxBacked(strongBox)
                    .build()
            )
            return gen.generateKey()
        }
        val hasStrongBox = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return if (hasStrongBox) runCatching { make(true) }.getOrElse { make(false) } else make(false)
    }

    // ---------------------------------------------------------------- fingerprint

    /** Whether the fingerprint may be used now, or the PIN is needed (after a restart or 72 hours). */
    fun pinRequired(): Boolean {
        val at = prefs.getLong(K_PIN_AT, -1L)
        val now = SystemClock.elapsedRealtime()
        return at < 0 || prefs.getInt(K_PIN_BOOT, -2) != bootCount() || now < at || now - at > PIN_EVERY_MS
    }

    private fun markPinUsed() {
        prefs.edit().putLong(K_PIN_AT, SystemClock.elapsedRealtime()).putInt(K_PIN_BOOT, bootCount()).apply()
    }

    /** A cipher for the fingerprint prompt that turns fingerprint unlock on. Only while unlocked. */
    fun biometricEnrolCipher(): Cipher? {
        if (vaultKey == null) return null
        return runCatching {
            deleteKey(bioAlias)
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(bioAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    // 0 = a fresh fingerprint match for every single use.
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            val key = gen.generateKey()
            Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
        }.getOrNull()
    }

    /** Finishes turning fingerprint unlock on with the cipher the prompt unlocked. */
    fun finishBiometricEnrol(cipher: Cipher): Boolean {
        val key = vaultKey ?: return false
        return runCatching {
            val ct = cipher.doFinal(key)
            prefs.edit().putString(K_BIO_WRAPPED, b64(cipher.iv + ct)).commit()
            _bio = true
        }.isSuccess
    }

    fun disableBiometric() {
        deleteKey(bioAlias)
        prefs.edit().remove(K_BIO_WRAPPED).commit()
        _bio = false
    }

    /**
     * A cipher for the unlock prompt, or null when fingerprint unlock is off, the PIN is due, or the key
     * was invalidated by a newly added fingerprint (which turns fingerprint unlock off).
     */
    fun biometricUnlockCipher(): Cipher? {
        if (!biometricEnabled || pinRequired() || lockoutRemaining() > 0) return null
        val data = prefs.getString(K_BIO_WRAPPED, null)?.let(::unb64) ?: return null
        return try {
            val key = keyStore().getKey(bioAlias, null) as? SecretKey ?: run { disableBiometric(); return null }
            Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, IV_LEN)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBiometric()
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Opens the vault with the cipher a strong fingerprint match unlocked. A faked "success" can't do this. */
    fun unlockWithBiometric(cipher: Cipher): Boolean {
        val data = prefs.getString(K_BIO_WRAPPED, null)?.let(::unb64) ?: return false
        val key = runCatching { cipher.doFinal(data, IV_LEN, data.size - IV_LEN) }.getOrNull() ?: return false
        if (key.size != 32) return false
        failedAttempts = 0
        vaultKey = key
        return true
    }

    // ---------------------------------------------------------------- vault

    /** Wipes the vault key from memory: the app is locked. */
    fun close() {
        vaultKey?.fill(0)
        vaultKey = null
    }

    /** Turns the lock off and forgets every key. Decrypt what you need with [open] first. */
    fun disable() {
        close()
        deleteKey(bioAlias)
        deleteKey(pepperAlias)
        prefs.edit().clear().commit()
        _enabled = false
        _bio = false
    }

    /** Encrypts [plain] with the vault key. Only while unlocked. */
    fun seal(plain: String): String? {
        val key = vaultKey ?: return null
        return SEALED_PREFIX + b64(aesEncrypt(key, plain.toByteArray(Charsets.UTF_8)))
    }

    /** Decrypts what [seal] made, or null while locked. */
    fun open(sealed: String): String? {
        val key = vaultKey ?: return null
        if (!isSealed(sealed)) return null
        return runCatching { String(aesDecrypt(key, unb64(sealed.removePrefix(SEALED_PREFIX))), Charsets.UTF_8) }.getOrNull()
    }

    // ---------------------------------------------------------------- lockout

    private var failedAttempts: Int
        get() = prefs.getInt(K_FAILED, 0)
        set(v) { prefs.edit().putInt(K_FAILED, v).commit() }

    /**
     * Measured with the monotonic clock so changing the date or time can't end it. After a reboot the
     * remaining time continues from the last checkpoint.
     */
    private fun startLockout(durationMs: Long) {
        prefs.edit()
            .putLong(K_LOCKOUT_REMAINING, durationMs)
            .putLong(K_LOCKOUT_BASE, SystemClock.elapsedRealtime())
            .putInt(K_LOCKOUT_BOOT, bootCount())
            .commit()
    }

    /** Milliseconds of wrong-PIN wait left (0 when none). */
    fun lockoutRemaining(): Long {
        val remaining = prefs.getLong(K_LOCKOUT_REMAINING, 0L)
        if (remaining <= 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        val base = prefs.getLong(K_LOCKOUT_BASE, now)
        val rebooted = prefs.getInt(K_LOCKOUT_BOOT, -1) != bootCount() || now < base
        val left = if (rebooted) remaining else (remaining - (now - base)).coerceAtLeast(0L)
        val edit = prefs.edit()
        if (left <= 0L) edit.remove(K_LOCKOUT_REMAINING).remove(K_LOCKOUT_BASE).remove(K_LOCKOUT_BOOT)
        else edit.putLong(K_LOCKOUT_REMAINING, left).putLong(K_LOCKOUT_BASE, now).putInt(K_LOCKOUT_BOOT, bootCount())
        edit.apply()
        return left
    }

    // ---------------------------------------------------------------- helpers

    private val pepperAlias get() = "${aliasPrefix}_lock_pepper"
    private val bioAlias get() = "${aliasPrefix}_lock_biometric"

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    private fun deleteKey(alias: String) { runCatching { keyStore().deleteEntry(alias) } }

    private fun aesEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    private fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, IV_LEN))
        return c.doFinal(data, IV_LEN, data.size - IV_LEN)
    }

    private fun bootCount(): Int =
        runCatching { android.provider.Settings.Global.getInt(appContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val PIN_MIN = 6
        const val PIN_MAX = 12
        const val SEALED_PREFIX = "vault1:"
        fun isSealed(value: String) = value.startsWith(SEALED_PREFIX)

        private const val KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val PBKDF2_ROUNDS = 600_000
        private const val MAX_TRIES = 5
        private const val PIN_EVERY_MS = 72L * 60 * 60 * 1000

        private const val K_PIN_SALT = "pin_salt"
        private const val K_PIN_WRAPPED = "pin_wrapped_key"
        private const val K_PIN_LENGTH = "pin_length"
        private const val K_BIO_WRAPPED = "bio_wrapped_key"
        private const val K_PIN_AT = "pin_used_elapsed"
        private const val K_PIN_BOOT = "pin_used_boot"
        private const val K_FAILED = "pin_failed"
        private const val K_LOCKOUT_REMAINING = "lockout_remaining"
        private const val K_LOCKOUT_BASE = "lockout_base_elapsed"
        private const val K_LOCKOUT_BOOT = "lockout_boot"
    }
}
