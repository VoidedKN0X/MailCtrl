package com.vknxworks.mailctrl.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.settings.AppSettings
import com.vknxworks.mailctrl.notify.NotifyChecker
import com.vknxworks.mailctrl.notify.NotifySchedule
import com.vknxworks.mailctrl.notify.UnifiedPush
import com.vknxworks.mailctrl.ui.components.CtrlSwitch
import com.vknxworks.mailctrl.ui.components.Group
import com.vknxworks.mailctrl.ui.components.GroupChoice
import com.vknxworks.mailctrl.ui.components.GroupRow
import com.vknxworks.mailctrl.ui.components.GroupSwitch
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.components.rememberNotificationAccess
import com.vknxworks.mailctrl.ui.lock.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val INTERVALS = listOf(15, 30, 60, 180, 0)

/** "Every 15 min · instant with ntfy", or "Off": the summary on the You page. */
@Composable
fun notificationsSummary(s: AppSettings): String = when {
    !s.notifyEnabled -> stringResource(R.string.notify_off)
    else -> listOfNotNull(
        if (s.notifyInterval > 0) intervalLabel(s.notifyInterval) else null,
        if (s.pushEnabled && s.pushEndpoint != null) stringResource(R.string.notify_summary_push) else null,
    ).joinToString(" · ").ifBlank { stringResource(R.string.notify_on) }
}

@Composable
private fun intervalLabel(minutes: Int): String = when {
    minutes == 0 -> stringResource(R.string.notify_interval_never)
    minutes < 60 -> stringResource(R.string.notify_interval_minutes, minutes)
    else -> pluralStringResource(R.plurals.notify_interval_hours, minutes / 60, minutes / 60)
}

private fun ignoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

private fun copy(context: Context, text: String) {
    // The push address works like a password (anyone with it can wake MailCtrl), so keep it out of clipboard previews.
    val clip = ClipData.newPlainText("MailCtrl", text).apply {
        description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

@Composable
fun NotificationsScreen() {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    fun update(t: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(t) } }

    // Checked again after the permission dialog and whenever the screen comes back (from Android's settings).
    val access = rememberNotificationAccess()
    val allowed = access.canPost
    var battery by remember { mutableStateOf(ignoringBattery(context)) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        battery = ignoringBattery(context)
        tick++
    }

    fun turnOn() {
        // Start from a clean slate so turning notifications on doesn't report mail you already have.
        NotifyChecker.reset(context)
        update { it.copy(notifyEnabled = true) }
        NotifySchedule.checkNow(context, push = false)
    }
    // Turned on while blocked: switch on for real once notifications are allowed.
    var turningOn by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(allowed, turningOn) {
        if (turningOn && allowed) {
            turningOn = false
            if (!s.notifyEnabled) turnOn()
        }
    }
    // Notifications are on by default: ask for Android's permission once, the first time this screen opens.
    LaunchedEffect(Unit) { if (s.notifyEnabled) access.askOnce() }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.notifications))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Group(footer = stringResource(R.string.notify_intro)) {
                GroupSwitch(
                    stringResource(R.string.notifications),
                    if (s.notifyEnabled && !allowed) stringResource(R.string.notify_blocked) else notificationsSummary(s),
                    s.notifyEnabled && allowed,
                    if (s.notifyEnabled && allowed) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                ) { on ->
                    when {
                        !on -> { turningOn = false; update { it.copy(notifyEnabled = false) } }
                        !allowed -> { turningOn = true; access.fix() }
                        else -> turnOn()
                    }
                }
                if (s.notifyEnabled && !allowed) {
                    GroupRow(
                        stringResource(R.string.notify_open_system),
                        stringResource(R.string.notify_open_system_body),
                        Icons.Outlined.SettingsIcon,
                        iconTint = MaterialTheme.colorScheme.error,
                        showChevron = true,
                        onClick = { access.fix() },
                    )
                }
            }

            if (s.notifyEnabled && allowed) {
                Group(stringResource(R.string.notify_how), footer = stringResource(R.string.notify_how_footer)) {
                    GroupChoice(
                        stringResource(R.string.notify_interval),
                        INTERVALS.map { it to intervalLabel(it) },
                        s.notifyInterval,
                        Icons.Outlined.Schedule,
                    ) { v -> update { it.copy(notifyInterval = v) } }
                    PushRows(s, tick, ::update)
                    val lastCheck = remember(tick, s) { NotifyChecker.lastCheck(context) }
                    val lastPush = remember(tick, s) { NotifyChecker.lastPush(context) }
                    GroupRow(
                        stringResource(R.string.notify_check_now),
                        listOfNotNull(
                            lastCheck.takeIf { it > 0 }?.let { stringResource(R.string.notify_last_check, DateUtils.getRelativeTimeSpanString(it).toString()) },
                            lastPush.takeIf { it > 0 && s.pushEnabled }?.let { stringResource(R.string.notify_last_push, DateUtils.getRelativeTimeSpanString(it).toString()) },
                        ).joinToString(" · ").ifBlank { null },
                        Icons.Outlined.Refresh,
                        onClick = {
                            NotifySchedule.checkNow(context, push = false)
                            Toast.makeText(context, context.getString(R.string.notify_checking), Toast.LENGTH_SHORT).show()
                        },
                    )
                }

                if (accounts.size > 1) {
                    Group(stringResource(R.string.group_accounts), footer = stringResource(R.string.notify_accounts_footer)) {
                        accounts.forEach { a ->
                            GroupSwitch(a.title, a.email.takeIf { it != a.title }, a.notify, Icons.Outlined.AccountCircle) { on ->
                                scope.launch { c.accounts.update(a.id) { it.copy(notify = on) } }
                            }
                        }
                    }
                }

                Group(stringResource(R.string.notify_battery), footer = stringResource(R.string.notify_battery_footer)) {
                    GroupRow(
                        stringResource(if (battery) R.string.notify_battery_ok else R.string.notify_battery_limited),
                        stringResource(if (battery) R.string.notify_battery_ok_body else R.string.notify_battery_limited_body),
                        if (battery) Icons.Outlined.BatteryFull else Icons.Outlined.BatteryAlert,
                        iconTint = if (battery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        showChevron = !battery,
                        onClick = if (battery) null else ({
                            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                            AppLock.expectReturn()
                            runCatching { context.startActivity(ask) }
                                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
                        }),
                    )
                    GroupRow(
                        stringResource(R.string.notify_app_info),
                        stringResource(R.string.notify_app_info_body),
                        Icons.Outlined.SettingsIcon,
                        showChevron = true,
                        onClick = {
                            AppLock.expectReturn()
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The ntfy switch and, once registered, the address to give whatever watches your mail. */
@Composable
private fun PushRows(s: AppSettings, tick: Int, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Checked again whenever the screen comes back, e.g. after installing ntfy.
    val ntfyInstalled = remember(tick, s.pushEnabled) { UnifiedPush.isNtfyInstalled(context) }
    val ntfyUnrestricted = remember(tick) { UnifiedPush.isNtfyUnrestricted(context) }
    val unavailable by UnifiedPush.unavailable.collectAsStateWithLifecycle()
    val lastError = remember(tick, s.pushEnabled) { UnifiedPush.lastError(context) }
    GroupRow(
        stringResource(R.string.notify_push),
        when {
            !ntfyInstalled -> stringResource(R.string.notify_push_none)
            s.pushEnabled && unavailable -> stringResource(R.string.notify_push_unavailable)
            s.pushEnabled && s.pushEndpoint == null -> stringResource(R.string.notify_push_waiting)
            s.pushEnabled -> stringResource(R.string.notify_push_on)
            lastError != null -> stringResource(R.string.notify_push_failed, lastError)
            else -> stringResource(R.string.notify_push_body)
        },
        Icons.Outlined.Bolt,
        onClick = null,
        trailing = {
            CtrlSwitch(
                checked = s.pushEnabled,
                enabled = ntfyInstalled || s.pushEnabled,
                onCheckedChange = { on ->
                    if (on) {
                        // Registering happens as soon as the setting is saved (NotifySchedule.apply).
                        update { it.copy(pushEnabled = true) }
                    } else {
                        UnifiedPush.unregister(context)
                        update { it.copy(pushEnabled = false, pushEndpoint = null) }
                    }
                },
            )
        },
    )
    if (!ntfyInstalled) {
        GroupRow(
            stringResource(R.string.notify_ntfy_missing),
            stringResource(R.string.notify_ntfy_missing_body),
            Icons.Outlined.ErrorOutline,
            iconTint = MaterialTheme.colorScheme.error,
            trailing = {
                FilledTonalButton(onClick = {
                    runCatching { AppLock.expectReturn(); context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UnifiedPush.NTFY_DOWNLOAD))) }
                }) { Text(stringResource(R.string.notify_ntfy_install)) }
            },
        )
    } else if (s.pushEnabled && !ntfyUnrestricted) {
        GroupRow(
            stringResource(R.string.notify_ntfy_battery),
            stringResource(R.string.notify_ntfy_battery_body),
            Icons.Outlined.BatteryAlert,
            iconTint = MaterialTheme.colorScheme.error,
            showChevron = true,
            onClick = {
                runCatching { AppLock.expectReturn(); context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${UnifiedPush.NTFY}"))) }
            },
        )
    }
    val endpoint = s.pushEndpoint
    if (s.pushEnabled && endpoint != null) {
        val share = stringResource(R.string.notify_push_share_title)
        GroupRow(
            stringResource(R.string.notify_push_address),
            endpoint,
            Icons.Outlined.ContentCopy,
            onClick = { copy(context, endpoint) },
            trailing = {
                IconButton(onClick = {
                    AppLock.expectReturn()
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, endpoint), share))
                }) { Icon(Icons.Outlined.Share, share) }
            },
        )
        GroupRow(stringResource(R.string.notify_push_test), stringResource(R.string.notify_push_test_body), Icons.AutoMirrored.Outlined.Send, onClick = {
            scope.launch {
                // Posts to your own push address only, exactly as your mail watcher will.
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val conn = URL(endpoint).openConnection() as HttpURLConnection
                        try {
                            conn.requestMethod = "POST"
                            conn.connectTimeout = 15_000
                            conn.readTimeout = 15_000
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "text/plain")
                            conn.outputStream.use { it.write("MailCtrl test".toByteArray()) }
                            conn.responseCode in 200..299
                        } finally {
                            conn.disconnect()
                        }
                    }.getOrDefault(false)
                }
                Toast.makeText(context, context.getString(if (ok) R.string.notify_push_test_sent else R.string.notify_push_test_failed), Toast.LENGTH_LONG).show()
            }
        })
    }
}
