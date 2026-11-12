package com.vknxworks.mailctrl.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vknxworks.mailctrl.notify.Notifier
import com.vknxworks.mailctrl.ui.lock.AppLock
import com.vknxworks.mailctrl.ui.lock.findActivity

/**
 * Android 13+ only lets MailCtrl post notifications once you've allowed it. This remembers whether
 * MailCtrl already asked (so it asks by itself at most once) and knows when Android won't show its
 * dialog any more, so the only way left is MailCtrl's notification settings.
 */
object NotificationPermission {
    private const val PREFS = "notify_permission"
    private const val KEY_ASKED = "asked"
    private const val KEY_AUTO_ASKED = "auto_asked"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Whether Android will still show its permission dialog (it stops after you've said no twice). */
    fun canAsk(context: Context): Boolean {
        if (!prefs(context).getBoolean(KEY_ASKED, false)) return true
        return context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
    }

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun autoAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_ASKED, false)

    fun markAutoAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_AUTO_ASKED, true).apply()
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Whether MailCtrl can show notifications right now, and how to fix it when it can't. */
class NotificationAccess internal constructor(
    private val context: Context,
    /** The Android 13 permission is granted. */
    val granted: Boolean,
    /** Notifications can really be shown: the permission is granted and they aren't turned off in Android's settings. */
    val canPost: Boolean,
    private val launcher: ManagedActivityResultLauncher<String, Boolean>,
) {
    private fun ask() {
        NotificationPermission.markAsked(context)
        AppLock.expectReturn()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Shows Android's permission dialog while it still can, otherwise opens MailCtrl's notification settings. */
    fun fix() {
        if (canPost) return
        if (!granted && NotificationPermission.canAsk(context)) {
            ask()
        } else {
            AppLock.expectReturn()
            runCatching { context.startActivity(NotificationPermission.settingsIntent(context)) }
        }
    }

    /** Shows Android's permission dialog if the permission is missing and Android still shows it; never opens settings. */
    fun askIfPossible() {
        if (!granted && NotificationPermission.canAsk(context)) ask()
    }

    /** Asks by itself, at most once per install, when notifications are in use but not allowed yet. */
    fun askOnce() {
        if (granted || NotificationPermission.autoAsked(context)) return
        NotificationPermission.markAutoAsked(context)
        if (NotificationPermission.canAsk(context)) ask()
    }
}

/** [NotificationAccess] that updates after the permission dialog and whenever MailCtrl comes back on screen (from Android's settings). */
@Composable
fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) version++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(version) { NotificationPermission.granted(context) }
    val canPost = remember(version) { Notifier.allowed(context) }
    return remember(granted, canPost, launcher) { NotificationAccess(context, granted, canPost, launcher) }
}
