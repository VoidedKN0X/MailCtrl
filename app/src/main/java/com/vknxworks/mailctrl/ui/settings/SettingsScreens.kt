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
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.CtrlAlertDialog
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Forum
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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.outlined.Undo
import com.vknxworks.mailctrl.ui.nav.LaterRoute
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
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
import com.vknxworks.mailctrl.data.settings.PassphraseMemory
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
import com.vknxworks.mailctrl.ui.components.accountColor
import com.vknxworks.mailctrl.ui.components.avatarInk
import com.vknxworks.mailctrl.ui.lock.AppLock
import com.vknxworks.mailctrl.ui.lock.PinRequest
import com.vknxworks.mailctrl.ui.lock.findActivity
import com.vknxworks.mailctrl.ui.nav.AccountSetupRoute
import com.vknxworks.mailctrl.ui.nav.DraftsRoute
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.nav.NotificationsRoute
import com.vknxworks.mailctrl.ui.nav.PgpKeysRoute
import com.vknxworks.mailctrl.ui.nav.SettingsRoute
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/** The choices for "Undo send": how many seconds a message waits before it really goes out. */
private val UNDO_SECONDS = listOf(0, 5, 10, 20, 30)

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
    val waiting by c.outbox.items.collectAsStateWithLifecycle()
    val snoozed by c.snoozes.items.collectAsStateWithLifecycle()
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
                    val tone = accountColor(a)
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
                    stringResource(R.string.later_title),
                    stringResource(R.string.later_subtitle, waiting.count { !it.undo }, snoozed.size),
                    icon = Icons.Outlined.Schedule,
                    showChevron = true,
                    onClick = { nav.navigate(LaterRoute) },
                )
                GroupRow(
                    stringResource(R.string.drafts_local),
                    stringResource(R.string.drafts_local_subtitle, drafts.size),
                    icon = Icons.Outlined.EditNote,
                    showChevron = true,
                    onClick = { nav.navigate(DraftsRoute) },
                )
                GroupRow(
                    stringResource(R.string.pgp_keys),
                    stringResource(R.string.pgp_keys_subtitle),
                    icon = Icons.Outlined.VpnKey,
                    showChevron = true,
                    onClick = { nav.navigate(PgpKeysRoute) },
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
    val nav = LocalNavigator.current
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    fun update(t: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(t) } }
    val bioAvailable = remember { AppLock.biometricAvailable(context) }
    val pictureSenders by c.pictureSenders.senders.collectAsStateWithLifecycle()
    val mailed by c.mailed.known.collectAsStateWithLifecycle()
    var showPictureSenders by remember { mutableStateOf(false) }
    var forgettingMailed by remember { mutableStateOf(false) }

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
                GroupRow(
                    stringResource(R.string.picture_senders),
                    pluralStringResource(R.plurals.picture_senders_n, pictureSenders.size, pictureSenders.size),
                    icon = Icons.Outlined.HowToReg,
                    showChevron = true,
                    onClick = { showPictureSenders = true },
                )
                GroupSwitch(
                    stringResource(R.string.dark_messages),
                    stringResource(R.string.dark_messages_subtitle),
                    s.darkMessages,
                    icon = Icons.Outlined.Contrast,
                ) { v -> update { it.copy(darkMessages = v) } }
                GroupSwitch(
                    stringResource(R.string.mark_read_on_open),
                    stringResource(R.string.mark_read_on_open_subtitle),
                    s.markReadOnOpen,
                    icon = Icons.Outlined.MarkEmailRead,
                ) { v -> update { it.copy(markReadOnOpen = v) } }
                GroupSwitch(
                    stringResource(R.string.keep_messages),
                    stringResource(R.string.keep_messages_subtitle),
                    s.keepMessages,
                    icon = Icons.Outlined.DownloadForOffline,
                ) { v -> update { it.copy(keepMessages = v) } }
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

            Group(stringResource(R.string.group_writing), footer = stringResource(R.string.undo_send_footer)) {
                GroupSwitch(
                    stringResource(R.string.suggest_recipients),
                    stringResource(R.string.suggest_recipients_subtitle),
                    s.suggestRecipients,
                    icon = Icons.Outlined.PersonSearch,
                ) { v -> update { it.copy(suggestRecipients = v) } }
                GroupChoice(
                    stringResource(R.string.undo_send),
                    UNDO_SECONDS.map { n -> n to if (n == 0) stringResource(R.string.undo_send_off) else pluralStringResource(R.plurals.undo_send_seconds, n, n) },
                    s.undoSend,
                    icon = Icons.AutoMirrored.Outlined.Undo,
                    fallbackLabel = pluralStringResource(R.plurals.undo_send_seconds, s.undoSend, s.undoSend),
                ) { v -> update { it.copy(undoSend = v) } }
                GroupRow(
                    stringResource(R.string.mailed_forget),
                    pluralStringResource(R.plurals.mailed_n, mailed.size, mailed.size),
                    icon = Icons.Outlined.PersonRemove,
                    onClick = if (mailed.isEmpty()) null else ({ forgettingMailed = true }),
                )
            }

            Group(stringResource(R.string.group_pgp), footer = stringResource(R.string.pgp_settings_footer)) {
                GroupRow(
                    stringResource(R.string.pgp_keys),
                    stringResource(R.string.pgp_keys_subtitle),
                    icon = Icons.Outlined.VpnKey,
                    showChevron = true,
                    onClick = { nav.navigate(PgpKeysRoute) },
                )
                GroupChoice(
                    stringResource(R.string.pgp_remember),
                    PassphraseMemory.entries.map { it to stringResource(it.label) },
                    s.pgpPassphrase,
                    icon = Icons.Outlined.Timer,
                ) { v ->
                    // A shorter time applies straight away: passphrases remembered until now are forgotten.
                    c.pgp.forget()
                    update { it.copy(pgpPassphrase = v) }
                }
                GroupSwitch(
                    stringResource(R.string.pgp_encrypt_subject),
                    stringResource(R.string.pgp_encrypt_subject_subtitle),
                    s.encryptSubject,
                    icon = Icons.AutoMirrored.Outlined.Subject,
                ) { v -> update { it.copy(encryptSubject = v) } }
                GroupSwitch(
                    stringResource(R.string.pgp_wkd),
                    stringResource(R.string.pgp_wkd_subtitle),
                    s.wkdLookup,
                    icon = Icons.Outlined.TravelExplore,
                ) { v -> update { it.copy(wkdLookup = v) } }
            }

            Group(stringResource(R.string.group_lists), footer = stringResource(R.string.swipe_footer)) {
                GroupSwitch(
                    stringResource(R.string.conversations),
                    stringResource(R.string.conversations_subtitle),
                    s.conversations,
                    icon = Icons.Outlined.Forum,
                ) { v -> update { it.copy(conversations = v) } }
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
                    s.notificationContentFor(lockOn),
                    icon = Icons.Outlined.Notifications,
                ) { v -> update { it.copy(notificationContent = v) } }
            }
        }
    }

    if (showPictureSenders) {
        CtrlAlertDialog(
            onDismissRequest = { showPictureSenders = false },
            title = { Text(stringResource(R.string.picture_senders)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (pictureSenders.isEmpty()) {
                        Text(stringResource(R.string.picture_senders_none), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted)
                    }
                    pictureSenders.forEach { sender ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(sender, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text, modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { c.pictureSenders.set(sender, false) } }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.remove), tint = CtrlColors.Muted)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPictureSenders = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (forgettingMailed) {
        ConfirmDialog(
            stringResource(R.string.mailed_forget_title),
            stringResource(R.string.mailed_forget_body),
            stringResource(R.string.mailed_forget_confirm),
            onDismiss = { forgettingMailed = false },
        ) {
            forgettingMailed = false
            scope.launch { c.mailed.clear() }
        }
    }
}
