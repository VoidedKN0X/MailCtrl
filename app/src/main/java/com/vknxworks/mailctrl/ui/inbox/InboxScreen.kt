package com.vknxworks.mailctrl.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.MailActions
import com.vknxworks.mailctrl.mail.FolderRole
import com.vknxworks.mailctrl.mail.LockedException
import com.vknxworks.mailctrl.mail.MessageHeader
import com.vknxworks.mailctrl.mail.PasswordNeededException
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.CtrlButtonShape
import com.vknxworks.mailctrl.ui.components.CtrlCardShape
import com.vknxworks.mailctrl.ui.components.CtrlChip
import com.vknxworks.mailctrl.ui.components.CtrlSquareButton
import com.vknxworks.mailctrl.ui.components.LocalBottomClearance
import com.vknxworks.mailctrl.ui.components.MessageView
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.components.TabTitle
import com.vknxworks.mailctrl.ui.components.friendlyMessage
import com.vknxworks.mailctrl.ui.components.rememberNotificationAccess
import com.vknxworks.mailctrl.ui.components.runTask
import com.vknxworks.mailctrl.ui.nav.AccountSetupRoute
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A problem refreshing one account, shown above the list. */
private data class AccountProblem(val accountId: String, val error: Throwable)

/** The Inbox tab: every account's inbox together, or one account's. */
@Composable
fun InboxScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val version by c.cache.version.collectAsStateWithLifecycle()
    // null = all accounts together.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedId = chosen?.takeIf { id -> accounts.any { it.id == id } }
        ?: if (!settings.unifiedInbox || accounts.size == 1) accounts.firstOrNull()?.id else null
    val ids = if (selectedId == null) accounts.map { it.id } else listOf(selectedId)

    val headers by produceState<List<MessageHeader>?>(null, version, ids) {
        value = if (selectedId == null) c.cache.unifiedInbox(ids) else c.cache.messages(selectedId, MailActions.INBOX)
    }
    var refreshing by remember { mutableStateOf(false) }
    val problems = remember { mutableStateListOf<AccountProblem>() }
    val hasMore = remember { mutableStateMapOf<String, Boolean>() }
    var loadingMore by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<String>() }

    /** [force]: pulled down or retried. Otherwise inboxes refreshed a moment ago are left as they are. */
    fun refresh(force: Boolean = true) {
        if (refreshing) return
        val due = if (force) ids else ids.filterNot { c.actions.isFresh(it, MailActions.INBOX) }
        if (due.isEmpty()) return
        refreshing = true
        scope.launch {
            val errors = c.actions.refreshInboxes(due)
            problems.removeAll { it.accountId in due }
            errors.forEach { (id, e) -> problems += AccountProblem(id, e) }
            due.filter { it !in errors }.forEach { hasMore[it] = true }
            refreshing = false
        }
    }

    fun loadMore() {
        if (loadingMore) return
        val more = ids.filter { hasMore[it] != false }
        if (more.isEmpty()) return
        loadingMore = true
        scope.launch {
            coroutineScope {
                more.map { id -> async { id to runCatching { c.actions.loadMore(id, MailActions.INBOX) }.getOrDefault(false) } }.awaitAll()
            }.forEach { (id, m) -> hasMore[id] = m }
            loadingMore = false
        }
    }

    // Fresh from the servers every time the tab opens (the cached list shows meanwhile). A refresh still
    // running for the accounts shown before is waited for, so the ones shown now are refreshed too.
    LaunchedEffect(ids) {
        snapshotFlow { refreshing }.first { !it }
        refresh(force = false)
    }

    // Notifications are on from the start: Android asks once whether MailCtrl may show them.
    val access = rememberNotificationAccess()
    LaunchedEffect(Unit) { if (c.settings.current().notifyEnabled) access.askOnce() }

    val unread = headers?.count { !it.seen } ?: 0
    val listState = rememberLazyListState()
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val names = remember(accounts, selectedId) { if (selectedId == null && accounts.size > 1) accounts.associate { it.id to it.title } else null }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (selection.isEmpty()) {
                TabTitle(stringResource(R.string.tab_inbox)) {
                    if (unread > 0) {
                        CtrlSquareButton(Icons.Outlined.DoneAll, stringResource(R.string.mark_all_read), onClick = {
                            val targets = ids
                            c.runTask { targets.forEach { c.actions.markAllRead(it, MailActions.INBOX) } }
                            headers?.let { c.notifier.cancelKeys(it) }
                        })
                        Spacer(Modifier.width(8.dp))
                    }
                }
            } else {
                SelectionTitle(selection.size) { selection.clear() }
            }
            if (accounts.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (settings.unifiedInbox) {
                        CtrlChip(selectedId == null, onClick = { chosen = null }, label = stringResource(R.string.all_accounts))
                    }
                    accounts.forEach { a ->
                        CtrlChip(selectedId == a.id, onClick = { chosen = a.id }, label = a.title)
                    }
                }
            }
            problems.filter { it.accountId in ids }.forEach { p -> ProblemBanner(p) { refresh() } }
            MessageList(
                headers = headers.orEmpty(),
                accountNames = names,
                refreshing = refreshing || headers == null,
                onRefresh = { refresh() },
                hasMore = ids.any { hasMore[it] != false },
                loadingMore = loadingMore,
                onLoadMore = ::loadMore,
                selection = selection,
                onOpen = { nav.message(it.accountId, it.folder, it.uid) },
                state = listState,
            ) {
                MessageView(
                    Icons.Outlined.Inbox,
                    stringResource(R.string.inbox_empty),
                    stringResource(R.string.inbox_empty_body),
                    modifier = Modifier.padding(bottom = LocalBottomClearance.current),
                )
            }
        }
        if (selection.isEmpty()) {
            val clearance = LocalBottomClearance.current
            ExtendedFloatingActionButton(
                onClick = { nav.compose() },
                expanded = expandedFab,
                icon = { Icon(Icons.Outlined.Edit, null, Modifier.size(20.dp)) },
                text = { Text(stringResource(R.string.write)) },
                shape = CtrlButtonShape,
                containerColor = CtrlColors.Accent,
                contentColor = CtrlColors.OnAccent,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = if (clearance > 0.dp) clearance + 12.dp else 16.dp),
            )
        }
    }
}

/** "Couldn't refresh …": tap to try again, or to type the password again when it's needed. */
@Composable
private fun ProblemBanner(p: AccountProblem, onRetry: () -> Unit) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val account = c.accounts.get(p.accountId)
    val needsPassword = p.error is PasswordNeededException
    val locked = p.error is LockedException
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter, vertical = 6.dp)
            .clip(CtrlCardShape)
            .background(CtrlColors.Surface)
            .border(1.dp, CtrlColors.Outline, CtrlCardShape)
            .clickable { if (needsPassword) nav.navigate(AccountSetupRoute(p.accountId)) else onRetry() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (needsPassword) Icons.Outlined.Key else Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = CtrlColors.Error)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.refresh_failed, account?.title ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = CtrlColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    needsPassword -> stringResource(R.string.password_needed_tap)
                    locked -> stringResource(R.string.error_locked)
                    else -> p.error.friendlyMessage() + " · " + stringResource(R.string.tap_to_retry)
                },
                style = MaterialTheme.typography.bodySmall,
                color = CtrlColors.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One folder of one account. */
@Composable
fun FolderScreen(accountId: String, folder: String) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val version by c.cache.version.collectAsStateWithLifecycle()
    val headers by produceState<List<MessageHeader>?>(null, version, accountId, folder) { value = c.cache.messages(accountId, folder) }
    val info by produceState<com.vknxworks.mailctrl.mail.FolderInfo?>(null, version, accountId, folder) {
        value = c.cache.folders(accountId).firstOrNull { it.fullName == folder }
    }
    var refreshing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Throwable?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var emptying by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<String>() }

    fun refresh(force: Boolean = true) {
        if (refreshing) return
        if (!force && c.actions.isFresh(accountId, folder)) return
        refreshing = true
        scope.launch {
            problem = runCatching { c.actions.refresh(accountId, folder) }.exceptionOrNull()
            if (problem == null) hasMore = true
            refreshing = false
        }
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            runCatching { c.actions.loadMore(accountId, folder) }
                .onSuccess { hasMore = it }
                .onFailure { problem = it }
            loadingMore = false
        }
    }

    LaunchedEffect(accountId, folder) { refresh(force = false) }

    val f = info
    val title = f?.let { folderName(it) } ?: folder.substringAfterLast('/')
    val role = f?.role ?: FolderRole.OTHER
    val unread = headers?.count { !it.seen } ?: 0

    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) {
            ScreenTopBar(title) {
                if (unread > 0) {
                    CtrlSquareButton(Icons.Outlined.DoneAll, stringResource(R.string.mark_all_read), onClick = {
                        c.runTask { c.actions.markAllRead(accountId, folder) }
                    })
                    Spacer(Modifier.width(8.dp))
                }
                if ((role == FolderRole.TRASH || role == FolderRole.JUNK) && !headers.isNullOrEmpty()) {
                    CtrlSquareButton(Icons.Outlined.DeleteSweep, stringResource(R.string.empty_folder), onClick = { emptying = true })
                    Spacer(Modifier.width(8.dp))
                }
            }
        } else {
            Box(Modifier.statusBarsPadding()) { SelectionTitle(selection.size) { selection.clear() } }
        }
        problem?.let { e -> ProblemBanner(AccountProblem(accountId, e)) { refresh() } }
        MessageList(
            headers = headers.orEmpty(),
            accountNames = null,
            refreshing = refreshing || headers == null,
            onRefresh = { refresh() },
            hasMore = hasMore,
            loadingMore = loadingMore,
            onLoadMore = ::loadMore,
            selection = selection,
            onOpen = { h -> nav.message(h.accountId, h.folder, h.uid) },
            showRecipients = role == FolderRole.SENT || role == FolderRole.DRAFTS,
        ) {
            MessageView(Icons.Outlined.Inbox, stringResource(R.string.folder_empty), null)
        }
    }

    if (emptying) {
        ConfirmDialog(
            stringResource(R.string.empty_folder_title, title),
            stringResource(R.string.empty_folder_body),
            stringResource(R.string.empty_folder),
            onDismiss = { emptying = false },
        ) {
            emptying = false
            c.runTask { c.actions.emptyFolder(accountId, folder) }
        }
    }
}
