package com.vknxworks.mailctrl.ui.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.lock.LockVault
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.LoadingView
import com.vknxworks.mailctrl.ui.components.PillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import javax.crypto.Cipher
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.vknxworks.mailctrl.R

/**
 * App lock state: whether the app is unlocked, and when it locks again. The secrets live in [LockVault].
 * Locking wipes the vault key from memory, so drafts can't be read until the next unlock.
 */
object AppLock {
    var unlocked by mutableStateOf(false)
        private set

    /** Goes up every time the app locks, so the lock screen offers the fingerprint once per lock. */
    var lockCount by mutableIntStateOf(0)
        private set

    /** A PIN screen shown on top of the app: turning the lock on or off, or changing the PIN. */
    var request by mutableStateOf<PinRequest?>(null)

    /** Set by the app: wipes the vault key. */
    var onLocked: () -> Unit = {}

    /** Set by an open chat: keeps the unsent text as a draft before locking closes it. */
    var saveBeforeLock: (() -> Unit)? = null

    /**
     * Goes up every time you come back to the app after really leaving it (not for the fingerprint prompt
     * itself), so the lock screen offers the fingerprint again on every return, not just once per lock.
     */
    var returns by mutableIntStateOf(0)
        private set

    /** The fingerprint or screen lock prompt that is open right now, if any. */
    private var promptSignal: CancellationSignal? = null
    private var promptStartedAt = 0L
    private var promptGeneration = 0

    /**
     * A prompt is open. Some phones drop a prompt without ever answering (for example one asked for while
     * the app was still coming back on screen); such a prompt stops counting after a minute, and asking
     * again always replaces it, so it can never block fingerprint unlock until the app is closed.
     */
    private val authenticating: Boolean
        get() = promptSignal != null && SystemClock.elapsedRealtime() - promptStartedAt < PROMPT_STALE_MS

    private const val PROMPT_STALE_MS = 60_000L

    /**
     * Starts tracking a new prompt, replacing one that is still marked as open (its late answer, if any, is
     * ignored). The returned check is true once, and only for the newest prompt.
     */
    private fun openPrompt(): Pair<CancellationSignal, () -> Boolean> {
        promptSignal?.let { runCatching { it.cancel() } }
        val generation = ++promptGeneration
        val signal = CancellationSignal()
        promptSignal = signal
        promptStartedAt = SystemClock.elapsedRealtime()
        val finish: () -> Boolean = {
            if (generation != promptGeneration || promptSignal !== signal) {
                false
            } else {
                promptSignal = null
                true
            }
        }
        return signal to finish
    }

    private var backgroundAt = 0L
    private var expectReturnAt = 0L

    /**
     * The app itself is opening another app (camera, gallery, file picker, share sheet, a file to
     * view). That trip doesn't count as leaving, so you don't have to unlock again when you come back.
     */
    fun expectReturn() {
        expectReturnAt = SystemClock.elapsedRealtime()
    }

    /** The current trip away was started by the app itself (camera, picker, share sheet …). */
    var exemptTrip = false
        private set

    fun onBackground(enabled: Boolean, timeoutMillis: Long) {
        if (authenticating) return
        backgroundAt = SystemClock.elapsedRealtime()
        // Only right after the app opened something itself; leaving later still counts.
        exemptTrip = expectReturnAt > 0 && backgroundAt - expectReturnAt < 5_000
        expectReturnAt = 0L
        // "As soon as you leave" locks right away, so the key is gone while the app is in the background.
        if (enabled && unlocked && timeoutMillis == 0L && !exemptTrip) lock()
    }

    fun onForeground(enabled: Boolean, timeoutMillis: Long) {
        val since = backgroundAt
        val exempt = exemptTrip
        backgroundAt = 0L
        expectReturnAt = 0L
        exemptTrip = false
        if (since > 0) returns++
        if (!enabled || !unlocked || since <= 0 || timeoutMillis < 0) return
        // A trip the app started still locks if you stay away long: after 5 minutes, or the chosen time if longer.
        val limit = if (exempt) maxOf(timeoutMillis, EXEMPT_TRIP_MAX) else timeoutMillis
        if (SystemClock.elapsedRealtime() - since >= limit) lock()
    }

    private const val EXEMPT_TRIP_MAX = 5 * 60_000L

    fun lock() {
        unlocked = false
        lockCount++
        request = null
        saveBeforeLock?.let { runCatching(it) }
        onLocked()
    }

    fun markUnlocked() {
        backgroundAt = 0L
        unlocked = true
    }

    /** Only class 3 ("strong") biometrics: camera-only face unlock can be fooled by a photo. */
    fun biometricAvailable(context: Context): Boolean = runCatching {
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    /**
     * Shows the fingerprint prompt for [cipher], a Keystore key that only works after a strong match.
     * [onSuccess] gets the unlocked cipher; [onError] gets a message, or null when cancelled.
     */
    fun biometricPrompt(
        activity: Activity,
        title: String,
        subtitle: String,
        cipher: Cipher,
        onError: (String?) -> Unit = {},
        onSuccess: (Cipher) -> Unit,
    ) {
        // Replaces a prompt that is still marked as open; its late answer (if any) is ignored.
        val (signal, finish) = openPrompt()
        try {
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton(activity.getString(R.string.lock_use_pin), activity.mainExecutor) { _, _ ->
                    if (finish()) onError(null)
                }
                .build()
            prompt.authenticate(
                BiometricPrompt.CryptoObject(cipher),
                signal,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (!finish()) return
                        val c = result.cryptoObject?.cipher
                        if (c != null) onSuccess(c) else onError(activity.getString(R.string.lock_fingerprint_key_failed))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!finish()) return
                        val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        onError(if (cancelled) null else errString.toString())
                    }
                },
            )
        } catch (e: Exception) {
            if (finish()) onError(e.message ?: activity.getString(R.string.lock_fingerprint_prompt_failed))
        }
    }

    fun screenLockAvailable(context: Context): Boolean = runCatching {
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    /**
     * The phone's own screen lock (or fingerprint). Only used for "Forgot PIN?", to prove it's the phone's
     * owner before the app lock is reset: texts aren't encrypted with the PIN, so resetting it must not be
     * open to anyone who picks the phone up.
     */
    fun screenLockAuthenticate(activity: Activity, onResult: (String?) -> Unit) {
        // Replaces a prompt that is still marked as open; its late answer (if any) is ignored.
        val (signal, finish) = openPrompt()
        try {
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.lock_reset_prompt_title))
                .setSubtitle(activity.getString(R.string.lock_reset_prompt_subtitle))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!finish()) return
                    onResult(null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (finish()) onResult(errString.toString())
                }
            })
        } catch (e: Exception) {
            if (finish()) onResult(e.message ?: activity.getString(R.string.lock_prompt_failed))
        }
    }
}

/**
 * Waits until the app is really back on screen: resumed, with its window focused. A prompt asked for earlier
 * (while the app is still coming back from the background) is dropped by the system, on some phones without
 * any answer, which used to leave fingerprint unlock not working until the app was closed.
 */
suspend fun awaitOnScreen(lifecycle: Lifecycle, windowInfo: WindowInfo) {
    lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
    snapshotFlow { windowInfo.isWindowFocused }.first { it }
}

/** What a PIN screen on top of the app is for. */
enum class PinRequest { ENABLE, CHANGE, DISABLE }

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Shows [content] only while the app is unlocked (or the lock is off). */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val stored by c.settings.stored.collectAsState(initial = null)
    if (stored == null) {
        LoadingView()
        return
    }
    val vault = c.vault
    val activity = context.findActivity()
    when {
        // Locking while the screen is closing (Back on the first screen, swiped from Recents): nothing new
        // is shown on a window that's going away; the app is locked and opens locked next time.
        vault.enabled && !AppLock.unlocked && (activity?.isFinishing == true || activity?.isDestroyed == true) ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        vault.enabled && !AppLock.unlocked -> PinLockScreen()
        else -> Box(Modifier.fillMaxSize()) {
            content()
            when (AppLock.request) {
                PinRequest.ENABLE -> PinSetupScreen(
                    title = stringResource(R.string.lock_choose_title),
                    intro = stringResource(R.string.lock_choose_intro, LockVault.PIN_MIN),
                    verifyCurrent = false,
                    cancellable = true,
                    onDone = { pin -> c.enableLock(pin); AppLock.request = null },
                    onCancel = { AppLock.request = null },
                )
                PinRequest.CHANGE -> PinSetupScreen(
                    title = stringResource(R.string.lock_change_title),
                    intro = stringResource(R.string.lock_change_intro, LockVault.PIN_MIN),
                    verifyCurrent = true,
                    cancellable = true,
                    onDone = { pin -> withContext(Dispatchers.Default) { vault.changePin(pin) }; AppLock.request = null },
                    onCancel = { AppLock.request = null },
                )
                PinRequest.DISABLE -> PinSetupScreen(
                    title = stringResource(R.string.lock_disable_title),
                    intro = stringResource(R.string.lock_disable_intro),
                    verifyCurrent = true,
                    verifyOnly = true,
                    cancellable = true,
                    onDone = { c.disableLock(); AppLock.request = null },
                    onCancel = { AppLock.request = null },
                )
                null -> Unit
            }
        }
    }
}

@Composable
private fun LockFrame(title: String, message: String, error: String?, actions: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity()
    BackHandler { activity?.moveTaskToBack(true) }
    // No other app's floating window over the lock screen, and no reading it by apps that aren't accessibility tools.
    com.vknxworks.mailctrl.ui.components.GuardedScreen(sensitive = true)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LockBadge()
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                actions()
            }
        }
    }
}

@Composable
private fun LockBadge() {
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(CtrlColors.Accent),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Lock, null, Modifier.size(30.dp), tint = CtrlColors.OnAccent) }
}

/** The lock screen: PIN pad, fingerprint, and "Forgot PIN?". */
@Composable
private fun PinLockScreen() {
    val context = LocalContext.current
    val c = context.container
    val vault = c.vault
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lockout by remember { mutableLongStateOf(vault.lockoutRemaining()) }
    var forgetting by remember { mutableStateOf(false) }
    val bioAvailable = remember(AppLock.lockCount, AppLock.returns) {
        vault.biometricEnabled && AppLock.biometricAvailable(context) && !vault.pinRequired()
    }

    LaunchedEffect(lockout > 0) {
        while (lockout > 0) {
            delay(500)
            lockout = vault.lockoutRemaining()
        }
    }

    fun askFingerprint() {
        val a = activity ?: return
        // Locked while leaving (Back on the first screen, swiped away): no prompt for a closing screen.
        if (a.isFinishing || a.isDestroyed) return
        val cipher = vault.biometricUnlockCipher() ?: run {
            message = if (!vault.biometricEnabled) context.getString(R.string.lock_fingerprint_changed) else null
            return
        }
        AppLock.biometricPrompt(a, context.getString(R.string.lock_fingerprint_title), context.getString(R.string.lock_fingerprint_subtitle), cipher, onError = { message = it }) { unlocked ->
            if (vault.unlockWithBiometric(unlocked)) c.onUnlocked() else message = context.getString(R.string.lock_fingerprint_failed)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowInfo = LocalWindowInfo.current
    // Offered on every lock and every time you come back to the lock screen, once the app is really on screen.
    LaunchedEffect(AppLock.lockCount, AppLock.returns) {
        if (!bioAvailable) return@LaunchedEffect
        awaitOnScreen(lifecycle, windowInfo)
        askFingerprint()
    }

    fun submit() {
        if (checking || lockout > 0 || pin.length < LockVault.PIN_MIN) return
        val entered = pin
        checking = true
        scope.launch {
            // Slow on purpose (it makes guessing slow too), so off the main thread.
            val result = withContext(Dispatchers.Default) { vault.unlockWithPin(entered) }
            checking = false
            pin = ""
            when (result) {
                LockVault.PinResult.Ok -> c.onUnlocked()
                LockVault.PinResult.Wrong, LockVault.PinResult.LockedOut -> {
                    lockout = vault.lockoutRemaining()
                    message = context.getString(R.string.lock_wrong_pin)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(45))
                }
            }
        }
    }

    val activityForBack = activity
    BackHandler { activityForBack?.moveTaskToBack(true) }
    PinPadScreen(
        title = stringResource(R.string.lock_locked),
        message = when {
            lockout > 0 -> stringResource(R.string.lock_too_many, ((lockout + 999) / 1000).toInt())
            message != null -> message!!
            vault.biometricEnabled && vault.pinRequired() -> stringResource(R.string.lock_pin_due)
            else -> stringResource(R.string.lock_enter_pin)
        },
        isError = lockout > 0 || message != null,
        pin = pin,
        shake = shake.value,
        checking = checking,
        enabled = lockout == 0L && !checking,
        onDigit = { d ->
            if (pin.length < LockVault.PIN_MAX) {
                message = null
                pin += d
                if (pin.length == vault.pinLength) submit()
            }
        },
        onBackspace = { pin = pin.dropLast(1) },
        onConfirm = ::submit,
        showConfirm = pin.length >= LockVault.PIN_MIN && pin.length != vault.pinLength,
        onBiometric = if (bioAvailable) ({ askFingerprint() }) else null,
        bottom = {
            TextButton(onClick = { forgetting = true }) { Text(stringResource(R.string.lock_forgot)) }
        },
    )
    if (forgetting) {
        val canReset = AppLock.screenLockAvailable(context)
        ConfirmDialog(
            title = stringResource(R.string.lock_forgot_title),
            body = stringResource(if (canReset) R.string.lock_forgot_body else R.string.lock_forgot_no_screen_lock),
            confirm = stringResource(if (canReset) R.string.lock_forgot_confirm else R.string.close),
            onDismiss = { forgetting = false },
        ) {
            forgetting = false
            val a = activity ?: return@ConfirmDialog
            if (!canReset || a.isFinishing || a.isDestroyed) return@ConfirmDialog
            // Only the phone's owner may reset it: confirm with the phone's own screen lock first.
            AppLock.screenLockAuthenticate(a) { error ->
                if (error == null) scope.launch { c.resetLock() } else message = error
            }
        }
    }
}

/**
 * Choosing a PIN (twice), changing it (current, then new twice) or confirming it to turn the lock off.
 * [onDone] runs off the main thread's critical path inside a coroutine.
 */
@Composable
private fun PinSetupScreen(
    title: String,
    intro: String,
    verifyCurrent: Boolean,
    cancellable: Boolean,
    onDone: suspend (String) -> Unit,
    onCancel: () -> Unit,
    verifyOnly: Boolean = false,
) {
    val context = LocalContext.current
    val vault = context.container.vault
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }
    // 0 = current PIN, 1 = new PIN, 2 = repeat new PIN
    var step by remember { mutableIntStateOf(if (verifyCurrent) 0 else 1) }
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockout by remember { mutableLongStateOf(vault.lockoutRemaining()) }

    LaunchedEffect(lockout > 0) {
        while (lockout > 0) {
            delay(500)
            lockout = vault.lockoutRemaining()
        }
    }

    BackHandler { if (cancellable) onCancel() else activity?.moveTaskToBack(true) }

    fun wrong(text: String) {
        message = text
        pin = ""
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(45)) }
    }

    fun finish(newPin: String) {
        busy = true
        scope.launch {
            runCatching { onDone(newPin) }.onFailure { busy = false; wrong(context.getString(R.string.lock_save_failed)) }
        }
    }

    fun next() {
        if (busy || lockout > 0) return
        when (step) {
            0 -> {
                if (pin.length < LockVault.PIN_MIN) return
                val entered = pin
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) { vault.unlockWithPin(entered) }
                    busy = false
                    if (result == LockVault.PinResult.Ok) {
                        pin = ""
                        message = null
                        if (verifyOnly) finish(entered) else step = 1
                    } else {
                        lockout = vault.lockoutRemaining()
                        wrong(context.getString(R.string.lock_wrong_pin))
                    }
                }
            }
            1 -> {
                if (pin.length < LockVault.PIN_MIN) return
                if (pin.toSet().size == 1 || "0123456789012".contains(pin) || "9876543210987".contains(pin)) {
                    wrong(context.getString(R.string.lock_too_easy))
                    return
                }
                first = pin
                pin = ""
                message = null
                step = 2
            }
            else -> {
                if (pin != first) {
                    first = ""
                    step = 1
                    wrong(context.getString(R.string.lock_mismatch))
                    return
                }
                finish(pin)
            }
        }
    }

    PinPadScreen(
        title = title,
        message = when {
            lockout > 0 -> stringResource(R.string.lock_too_many, ((lockout + 999) / 1000).toInt())
            message != null -> message!!
            step == 0 -> stringResource(R.string.lock_current_pin)
            step == 1 -> intro
            else -> stringResource(R.string.lock_repeat_pin)
        },
        isError = lockout > 0 || message != null,
        pin = pin,
        shake = shake.value,
        checking = busy,
        enabled = !busy && lockout == 0L,
        onDigit = { d -> if (pin.length < LockVault.PIN_MAX) { message = null; pin += d } },
        onBackspace = { pin = pin.dropLast(1) },
        onConfirm = ::next,
        showConfirm = pin.length >= LockVault.PIN_MIN,
        confirmLabel = stringResource(if (step == 2 || (step == 0 && verifyOnly)) R.string.lock_done else R.string.lock_next),
        bottom = { if (cancellable) TextButton(onClick = onCancel) { Text(stringResource(R.string.lock_cancel)) } },
    )
}

@Composable
internal fun PinPadScreen(
    title: String,
    message: String,
    isError: Boolean,
    pin: String,
    shake: Float,
    checking: Boolean,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    showConfirm: Boolean,
    confirmLabel: String = stringResource(R.string.lock_ok),
    /** Shows the fingerprint key (bottom left) while the PIN can't be confirmed yet. */
    onBiometric: (() -> Unit)? = null,
    bottom: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // No other app's floating window over the PIN pad, and no reading it by apps that aren't accessibility tools.
    com.vknxworks.mailctrl.ui.components.GuardedScreen(sensitive = true)
    Surface(Modifier.fillMaxSize(), color = CtrlColors.Background) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val minHeight = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minHeight)
                    .padding(start = 32.dp, end = 32.dp, top = 56.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LockBadge()
                    Spacer(Modifier.height(28.dp))
                    Text("VKNXWORKS · CTRL", style = CtrlType.Label.copy(letterSpacing = 0.18.em), color = CtrlColors.Muted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.035).em),
                        color = CtrlColors.Text,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isError) CtrlColors.Error else CtrlColors.Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                    Spacer(Modifier.height(28.dp))
                    Box(Modifier.height(20.dp).offset { IntOffset(shake.roundToInt(), 0) }, contentAlignment = Alignment.Center) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CtrlColors.AccentText)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                repeat(maxOf(pin.length, LockVault.PIN_MIN)) { i ->
                                    val cell = RoundedCornerShape(5.dp)
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(cell)
                                            .then(
                                                if (i < pin.length) Modifier.background(CtrlColors.Accent)
                                                else Modifier.border(2.dp, CtrlColors.PinEmpty, cell),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
                Column(
                    Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    listOf("123", "456", "789").forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { d ->
                                DigitKey(d.toString(), enabled, Modifier.weight(1f)) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDigit(d)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val side = Modifier.weight(1f)
                        val bio = onBiometric
                        when {
                            // Confirm a PIN that isn't checked by itself (choosing one, or one of another length).
                            showConfirm || bio == null -> PadKey(
                                modifier = side,
                                enabled = enabled && showConfirm,
                                fill = if (enabled && showConfirm) CtrlColors.Accent else Color.Transparent,
                                border = if (enabled && showConfirm) null else CtrlColors.Outline,
                                onClick = onConfirm,
                            ) {
                                Text(
                                    confirmLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (enabled && showConfirm) CtrlColors.OnAccent else CtrlColors.Inactive,
                                    maxLines = 1,
                                )
                            }
                            else -> PadKey(modifier = side, enabled = enabled, fill = Color.Transparent, border = CtrlColors.AccentText, onClick = { bio() }) {
                                Icon(Icons.Outlined.Fingerprint, stringResource(R.string.lock_use_fingerprint), Modifier.size(26.dp), tint = CtrlColors.AccentText)
                            }
                        }
                        DigitKey("0", enabled, Modifier.weight(1f)) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDigit('0')
                        }
                        PadKey(modifier = side, enabled = enabled && pin.isNotEmpty(), fill = Color.Transparent, border = null, onClick = onBackspace) {
                            Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.lock_delete), Modifier.size(24.dp), tint = CtrlColors.Muted)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    bottom()
                }
            }
        }
    }
}

private val KeyShape = RoundedCornerShape(22.dp)

@Composable
private fun PadKey(
    modifier: Modifier,
    enabled: Boolean,
    fill: Color,
    border: Color?,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .height(68.dp)
            .clip(KeyShape)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, KeyShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun DigitKey(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PadKey(modifier, enabled, CtrlColors.Surface, CtrlColors.Outline, onClick) {
        Text(label, style = CtrlType.Key, color = if (enabled) CtrlColors.Text else CtrlColors.Inactive)
    }
}
