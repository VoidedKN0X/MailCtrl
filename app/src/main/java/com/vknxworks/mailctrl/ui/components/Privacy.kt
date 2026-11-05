package com.vknxworks.mailctrl.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.vknxworks.mailctrl.ui.lock.findActivity

/**
 * Screens that always get FLAG_SECURE, whatever "Hide in screenshots" says: signing in (password or
 * access token). A count rather than a flag, so two such screens overlapping during
 * a transition can't clear it early.
 */
object ScreenPrivacy {
    var forced by mutableIntStateOf(0)
        private set

    internal fun acquire() { forced++ }
    internal fun release() { forced = (forced - 1).coerceAtLeast(0) }
}

/** Keeps FLAG_SECURE on for as long as the calling screen is shown. */
@Composable
fun SecureScreen() {
    DisposableEffect(Unit) {
        ScreenPrivacy.acquire()
        onDispose { ScreenPrivacy.release() }
    }
}

/**
 * Applies "Hide in screenshots" ([secure]: FLAG_SECURE, which also blanks the recent-apps preview) and
 * "Hide in recent apps" ([hideRecents]: a blank card in Recents, screenshots still work) to [activity]'s
 * window, and again whenever they change, so a switch works at once in every open screen.
 */
@Composable
fun WindowPrivacy(activity: Activity, secure: Boolean, hideRecents: Boolean) {
    LaunchedEffect(secure) {
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    LaunchedEffect(hideRecents) { activity.setRecentsScreenshotEnabled(!hideRecents) }
}

/**
 * Copies [text] marked as sensitive, so Android hides it from the clipboard preview and keyboards
 * don't keep it in their clipboard history.
 */
fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/**
 * Lock, PIN and confirmation screens. While one is showing, other apps' floating windows (chat heads, overlays)
 * are hidden from its window so they can't cover it or fake a button, and on lock and PIN screens apps that
 * aren't real accessibility tools can't read the window (Android 14+). Counted per window, so nesting works.
 */
object ScreenGuard {
    private val counts = java.util.WeakHashMap<android.view.Window, IntArray>()

    fun acquire(window: android.view.Window, sensitive: Boolean) {
        val c = counts.getOrPut(window) { IntArray(2) }
        c[0]++
        if (sensitive) c[1]++
        apply(window, c)
    }

    fun release(window: android.view.Window, sensitive: Boolean) {
        val c = counts[window] ?: return
        c[0] = (c[0] - 1).coerceAtLeast(0)
        if (sensitive) c[1] = (c[1] - 1).coerceAtLeast(0)
        apply(window, c)
        if (c[0] == 0 && c[1] == 0) counts.remove(window)
    }

    private fun apply(window: android.view.Window, c: IntArray) {
        runCatching { window.setHideOverlayWindows(c[0] > 0) }
        runCatching {
            window.decorView.setAccessibilityDataSensitive(
                if (c[1] > 0) android.view.View.ACCESSIBILITY_DATA_SENSITIVE_YES else android.view.View.ACCESSIBILITY_DATA_SENSITIVE_AUTO,
            )
        }
    }
}

/** Guards the window this screen is shown in (its own dialog window, or the activity's) while it's shown. */
@Composable
fun GuardedScreen(sensitive: Boolean = false) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, sensitive) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?: view.context.findActivity()?.window
        window?.let { ScreenGuard.acquire(it, sensitive) }
        onDispose { window?.let { ScreenGuard.release(it, sensitive) } }
    }
}
