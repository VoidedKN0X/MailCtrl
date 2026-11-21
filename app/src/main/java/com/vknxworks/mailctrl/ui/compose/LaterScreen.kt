package com.vknxworks.mailctrl.ui.compose

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.local.OutboxItem
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.CtrlControlShape
import com.vknxworks.mailctrl.ui.components.Group
import com.vknxworks.mailctrl.ui.components.GroupRow
import com.vknxworks.mailctrl.ui.components.MessageView
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.components.runTask
import com.vknxworks.mailctrl.ui.components.shortWhen
import com.vknxworks.mailctrl.ui.nav.ComposeMode
import com.vknxworks.mailctrl.ui.nav.ComposeRoute
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.delay

/**
 * "Scheduled and snoozed": the messages waiting on this phone to be sent later (send one now, or cancel it and get
 * it back as a draft), and the messages snoozed until later (open one, or put it back in its list now).
 */
@Composable
fun LaterScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val waiting by c.outbox.items.collectAsStateWithLifecycle()
    val snoozed by c.snoozes.items.collectAsStateWithLifecycle()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    var cancelling by remember { mutableStateOf<OutboxItem?>(null) }
    // Messages held for the Undo seconds have their own bar; they only show here when something went wrong.
    val scheduled = waiting.filter { !it.undo || it.error != null }
    val gone = stringResource(R.string.later_already_sent)

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.later_title))
        if (scheduled.isEmpty() && snoozed.isEmpty() && !c.outbox.unreadable) {
            MessageView(Icons.Outlined.Schedule, stringResource(R.string.later_empty), stringResource(R.string.later_empty_body))
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (c.outbox.unreadable) {
                Text(
                    stringResource(R.string.later_list_unreadable),
                    style = MaterialTheme.typography.bodySmall,
                    color = CtrlColors.Error,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
                )
            }
            if (scheduled.isNotEmpty()) {
                Group(stringResource(R.string.later_scheduled_title), footer = stringResource(R.string.later_scheduled_footer)) {
                    scheduled.forEach { item ->
                        val to = (item.draft.to + item.draft.cc + item.draft.bcc).joinToString(", ") { it.display }
                        val account = accounts.firstOrNull { it.id == item.accountId }?.title
                        GroupRow(
                            item.draft.subject.ifBlank { stringResource(R.string.no_subject) },
                            listOfNotNull(
                                stringResource(R.string.to_line, to.ifBlank { "—" }),
                                item.error ?: stringResource(R.string.later_at, shortWhen(context, item.sendAt)),
                                account?.takeIf { accounts.size > 1 },
                            ).joinToString("\n"),
                            icon = Icons.Outlined.Schedule,
                            iconTint = if (item.error != null) CtrlColors.Error else CtrlColors.AccentText,
                            trailing = {
                                Row {
                                    IconButton(onClick = { c.runTask { c.sendNow(item.id) } }) {
                                        Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.later_send_now), tint = CtrlColors.Muted)
                                    }
                                    IconButton(onClick = { cancelling = item }) {
                                        Icon(Icons.Outlined.Close, stringResource(R.string.later_cancel), tint = CtrlColors.Muted)
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (snoozed.isNotEmpty()) {
                Group(stringResource(R.string.snoozed_title), footer = stringResource(R.string.snoozed_footer)) {
                    snoozed.forEach { z ->
                        GroupRow(
                            z.subject.ifBlank { stringResource(R.string.no_subject) },
                            listOf(z.from.ifBlank { stringResource(R.string.unknown_sender) }, stringResource(R.string.snoozed_until, shortWhen(context, z.until))).joinToString("\n"),
                            icon = Icons.Outlined.NotificationsActive,
                            onClick = { nav.message(z.accountId, z.folder, z.uid) },
                            trailing = {
                                IconButton(onClick = { c.runTask { c.snoozes.wake(listOf(z.key)) } }) {
                                    Icon(Icons.Outlined.Close, stringResource(R.string.snooze_end), tint = CtrlColors.Muted)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    cancelling?.let { item ->
        ConfirmDialog(
            stringResource(R.string.later_cancel_title),
            stringResource(R.string.later_cancel_body),
            stringResource(R.string.later_cancel),
            onDismiss = { cancelling = null },
        ) {
            cancelling = null
            c.runTask {
                val draft = c.takeBack(item.id)
                if (draft == null) {
                    Toast.makeText(context, gone, Toast.LENGTH_LONG).show()
                } else {
                    nav.navigate(ComposeRoute(mode = ComposeMode.DRAFT, draftId = draft.id, accountId = draft.accountId))
                }
            }
        }
    }
}

/**
 * The bar shown while a message waits out its Undo seconds: how long is left, and **Undo**, which takes the
 * message back and opens it again to write on. It goes away when the time is up (the message is then sent in
 * the background by Android's WorkManager, also when MailCtrl has been closed meanwhile).
 */
@Composable
fun UndoSendBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val pending by c.pendingUndo.collectAsStateWithLifecycle()
    val p = pending ?: return
    var left by remember(p.id) { mutableLongStateOf(((p.until - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)) }
    LaunchedEffect(p.id) {
        while (true) {
            val ms = p.until - System.currentTimeMillis()
            if (ms <= 0) break
            left = (ms + 999) / 1000
            delay(250)
        }
        // Time's up: the bar goes, unless a newer message took its place.
        if (c.pendingUndo.value === p) c.pendingUndo.value = null
    }
    val gone = stringResource(R.string.later_already_sent)
    Row(
        modifier
            .fillMaxWidth()
            .clip(CtrlControlShape)
            .background(CtrlColors.Raised)
            .border(1.dp, CtrlColors.ControlOutline, CtrlControlShape)
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.undo_send_in, left.toInt(), left.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = CtrlColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = {
            c.runTask {
                val draft = c.takeBack(p.id)
                if (draft == null) {
                    Toast.makeText(context, gone, Toast.LENGTH_LONG).show()
                } else {
                    nav.navigateNow(ComposeRoute(mode = ComposeMode.DRAFT, draftId = draft.id, accountId = draft.accountId))
                }
            }
        }) { Text(stringResource(R.string.undo)) }
    }
}
