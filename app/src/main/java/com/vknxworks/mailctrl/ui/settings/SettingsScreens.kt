package com.vknxworks.mailctrl.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.BuildConfig
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.lock.LockVault
import com.vknxworks.mailctrl.data.settings.AppSettings
import com.vknxworks.mailctrl.data.settings.LockTimeout
import com.vknxworks.mailctrl.data.settings.NotificationContent
import com.vknxworks.mailctrl.data.settings.RemoteContent
import com.vknxworks.mailctrl.data.settings.SwipeAction
import com.vknxworks.mailctrl.ui.components.CtrlButton
import com.vknxworks.mailctrl.ui.components.CtrlSquareButton
import com.vknxworks.mailctrl.ui.components.Group
import com.vknxworks.mailctrl.ui.components.GroupChoice
import com.vknxworks.mailctrl.ui.components.GroupRow
import com.vknxworks.mailctrl.ui.components.GroupSwitch
import com.vknxworks.mailctrl.ui.components.LocalBottomClearance
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.components.SectionLabel
import com.vknxworks.mailctrl.ui.components.avatarColor
import com.vknxworks.mailctrl.ui.components.avatarInk
import com.vknxworks.mailctrl.ui.lock.AppLock
import com.vknxworks.mailctrl.ui.lock.PinRequest
import com.vknxworks.mailctrl.ui.lock.findActivity
import com.vknxworks.mailctrl.ui.nav.AccountSetupRoute
import com.vknxworks.mailctrl.ui.nav.DraftsRoute
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.nav.NotificationsRoute
import com.vknxworks.mailctrl.ui.nav.SettingsRoute
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

private const val SPONSOR_URL = "https://github.com/sponsors/VoidedKN0X"
private const val REPO_URL = "https://github.com/VoidedKN0X/MailCtrl"
private const val ISSUES_URL = "$REPO_URL/issues"
private const val LICENCE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

/** The You page, opened from the profile picture: your accounts, settings, notifications and About. */
@Composable
fun YouScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    val drafts by c.drafts.drafts.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    fun open(url: String) {
        AppLock.expectReturn()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.close), onClick = { nav.back() })
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SectionLabel(stringResource(R.string.you_title)) }
            Spacer(Modifier.width(44.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp + LocalBottomClearance.current)) {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(68.dp).clip(RoundedCornerShape(22.dp)).background(CtrlColors.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_notification), null, Modifier.size(32.dp), tint = CtrlColors.OnAccent)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = CtrlColors.Text)
                    Text(
                        context.resources.getQuantityString(R.plurals.accounts_n, accounts.size, accounts.size),
                        style = CtrlType.Meta,
                        color = CtrlColors.Muted,
                    )
                }
            }

            Group(stringResource(R.string.group_accounts)) {
                accounts.forEach { a ->
                    val tone = avatarColor(a.id)
                    GroupRow(
                        a.title,
                        listOfNotNull(
                            a.email.takeIf { it != a.title },
                            stringResource(R.string.account_password_needed).takeIf { c.accounts.needsPassword(a) },
                            stringResource(R.string.account_unencrypted).takeIf { a.unencrypted },
                        ).joinToString("  ·  ").ifBlank { null },
                        icon = Icons.Outlined.AccountCircle,
                        iconTint = avatarInk(tone),
                        showChevron = true,
                        onClick = { nav.navigate(AccountSetupRoute(a.id)) },
                    )
                }
                GroupRow(stringResource(R.string.add_account), icon = Icons.Outlined.Add, showChevron = true, onClick = { nav.navigate(AccountSetupRoute()) })
            }

            Group {
                GroupRow(stringResource(R.string.settings), icon = Icons.Outlined.SettingsIcon, showChevron = true, onClick = { nav.navigate(SettingsRoute) })
                GroupRow(
                    stringResource(R.string.notifications),
                    notificationsSummary(settings),
                    icon = Icons.Outlined.NotificationsActive,
                    showChevron = true,
                    onClick = { nav.navigate(NotificationsRoute) },
                )
                GroupRow(
                    stringResource(R.string.drafts_local),
                    stringResource(R.string.drafts_local_subtitle, drafts.size),
                    icon = Icons.Outlined.EditNote,
                    showChevron = true,
                    onClick = { nav.navigate(DraftsRoute) },
                )
            }

            Group(stringResource(R.string.group_about)) {
                GroupRow(stringResource(R.string.bugs_title), stringResource(R.string.bugs_subtitle), icon = Icons.Outlined.BugReport, showChevron = true, onClick = { open(ISSUES_URL) })
                GroupRow(stringResource(R.string.source_title), stringResource(R.string.source_subtitle), icon = Icons.Outlined.Code, showChevron = true, onClick = { open(REPO_URL) })
                GroupRow(stringResource(R.string.licence_title), stringResource(R.string.licence_subtitle), icon = Icons.Outlined.Gavel, showChevron = true, onClick = { open(LICENCE_URL) })
            }
            Spacer(Modifier.height(12.dp))
            CtrlButton(
                stringResource(R.string.support_title),
                onClick = { open(SPONSOR_URL) },
                icon = Icons.Outlined.FavoriteBorder,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
            )
            Text(
                stringResource(R.string.support_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = CtrlColors.Muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
            Text(
                "GOOGLE-FREE · TELEMETRY-FREE · ARM64",
                style = CtrlType.Label,
                color = CtrlColors.Muted,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.about_footer, BuildConfig.VERSION_NAME).uppercase(),
                style = CtrlType.Label,
                color = CtrlColors.Inactive,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    fun update(t: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(t) } }
    val bioAvailable = remember { AppLock.biometricAvailable(context) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.settings))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Group(stringResource(R.string.group_reading), footer = stringResource(R.string.remote_footer)) {
                GroupChoice(
                    stringResource(R.string.remote_content),
                    RemoteContent.entries.map { it to stringResource(it.label) },
                    s.remoteContent,
                    icon = Icons.Outlined.Image,
                ) { v -> update { it.copy(remoteContent = v) } }
                GroupSwitch(
                    stringResource(R.string.mark_read_on_open),
                    stringResource(R.string.mark_read_on_open_subtitle),
                    s.markReadOnOpen,
                    icon = Icons.Outlined.MarkEmailRead,
                ) { v -> update { it.copy(markReadOnOpen = v) } }
                if (accounts.size > 1) {
                    GroupSwitch(
                        stringResource(R.string.unified_inbox),
                        stringResource(R.string.unified_inbox_subtitle),
                        s.unifiedInbox,
                        icon = Icons.Outlined.Inbox,
                    ) { v -> update { it.copy(unifiedInbox = v) } }
                    GroupChoice(
                        stringResource(R.string.default_account),
                        accounts.map { it.id to it.email },
                        s.defaultAccount.takeIf { id -> accounts.any { it.id == id } } ?: accounts.first().id,
                        icon = Icons.Outlined.Mail,
                    ) { v -> update { it.copy(defaultAccount = v) } }
                }
            }

            Group(stringResource(R.string.group_lists), footer = stringResource(R.string.swipe_footer)) {
                GroupChoice(
                    stringResource(R.string.swipe_right),
                    SwipeAction.entries.map { it to stringResource(it.label) },
                    s.swipeRight,
                    icon = Icons.AutoMirrored.Outlined.ArrowForward,
                ) { v -> update { it.copy(swipeRight = v) } }
                GroupChoice(
                    stringResource(R.string.swipe_left),
                    SwipeAction.entries.map { it to stringResource(it.label) },
                    s.swipeLeft,
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                ) { v -> update { it.copy(swipeLeft = v) } }
                GroupSwitch(
                    stringResource(R.string.confirm_delete),
                    stringResource(R.string.confirm_delete_subtitle),
                    s.confirmDelete,
                    icon = Icons.Outlined.DeleteOutline,
                ) { v -> update { it.copy(confirmDelete = v) } }
            }

            Group(
                stringResource(R.string.group_privacy),
                footer = stringResource(if (c.vault.enabled && s.notifyEnabled) R.string.privacy_footer_notify else R.string.privacy_footer),
            ) {
                val lockOn = c.vault.enabled
                GroupSwitch(
                    stringResource(R.string.lock_app),
                    stringResource(R.string.lock_app_subtitle),
                    lockOn,
                    icon = Icons.Outlined.Lock,
                ) { on -> AppLock.request = if (on) PinRequest.ENABLE else PinRequest.DISABLE }
                if (lockOn) {
                    GroupRow(
                        stringResource(R.string.lock_change_pin),
                        stringResource(R.string.lock_change_pin_subtitle, LockVault.PIN_MIN),
                        icon = Icons.Outlined.Password,
                        showChevron = true,
                        onClick = { AppLock.request = PinRequest.CHANGE },
                    )
                    GroupSwitch(
                        stringResource(R.string.lock_fingerprint),
                        stringResource(if (bioAvailable || c.vault.biometricEnabled) R.string.lock_fingerprint_settings_subtitle else R.string.lock_fingerprint_unavailable),
                        c.vault.biometricEnabled,
                        icon = Icons.Outlined.Fingerprint,
                        enabled = bioAvailable || c.vault.biometricEnabled,
                    ) { on ->
                        if (!on) {
                            c.vault.disableBiometric()
                        } else {
                            val activity = context.findActivity() ?: return@GroupSwitch
                            val failed = context.getString(R.string.lock_fingerprint_setup_failed)
                            val cipher = c.vault.biometricEnrolCipher() ?: run {
                                Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                                return@GroupSwitch
                            }
                            AppLock.biometricPrompt(
                                activity,
                                context.getString(R.string.lock_fingerprint_enable_title),
                                context.getString(R.string.lock_fingerprint_enable_subtitle),
                                cipher,
                            ) { unlocked ->
                                if (!c.vault.finishBiometricEnrol(unlocked)) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    GroupChoice(
                        stringResource(R.string.lock_again),
                        LockTimeout.entries.map { it to stringResource(it.label) },
                        s.lockTimeout,
                        icon = Icons.Outlined.Timer,
                    ) { v -> update { it.copy(lockTimeout = v) } }
                    GroupRow(
                        stringResource(R.string.passwords_sealed),
                        stringResource(if (s.notifyEnabled) R.string.passwords_sealed_off else R.string.passwords_sealed_on),
                        icon = if (s.notifyEnabled) Icons.Outlined.LockOpen else Icons.Outlined.Key,
                    )
                }
                GroupSwitch(
                    stringResource(R.string.hide_screenshots),
                    stringResource(R.string.hide_screenshots_subtitle),
                    s.hideScreenshots,
                    icon = Icons.Outlined.VisibilityOff,
                ) { v -> update { it.copy(hideScreenshots = v) } }
                GroupSwitch(
                    stringResource(R.string.hide_recents),
                    stringResource(R.string.hide_recents_subtitle),
                    s.hideRecents,
                    icon = Icons.Outlined.Layers,
                ) { v -> update { it.copy(hideRecents = v) } }
                GroupChoice(
                    stringResource(R.string.notify_content),
                    NotificationContent.entries.map { it to stringResource(it.label) },
                    s.notificationContent,
                    icon = Icons.Outlined.Notifications,
                ) { v -> update { it.copy(notificationContent = v) } }
            }
        }
    }
}
