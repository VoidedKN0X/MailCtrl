package com.vknxworks.mailctrl.ui.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.settings.SwipeAction
import com.vknxworks.mailctrl.mail.FolderInfo
import com.vknxworks.mailctrl.mail.FolderRole
import com.vknxworks.mailctrl.mail.MessageHeader
import com.vknxworks.mailctrl.ui.components.ActionSheet
import com.vknxworks.mailctrl.ui.components.Avatar
import com.vknxworks.mailctrl.ui.components.CtrlCardShape
import com.vknxworks.mailctrl.ui.components.CtrlControlShape
import com.vknxworks.mailctrl.ui.components.CtrlSheetShape
import com.vknxworks.mailctrl.ui.components.CtrlSquareButton
import com.vknxworks.mailctrl.ui.components.LocalBottomClearance
import com.vknxworks.mailctrl.ui.components.SectionLabel
import com.vknxworks.mailctrl.ui.components.SheetAction
import com.vknxworks.mailctrl.ui.components.avatarColor
import com.vknxworks.mailctrl.ui.components.runTask
import com.vknxworks.mailctrl.ui.components.shortTime
import com.vknxworks.mailctrl.ui.components.topDivider
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/**
 * A list of messages: tap opens one, long-press starts selecting, swipe left or right does what you chose in
 * Settings, pull down refreshes and scrolling to the end loads older mail.
 */
@Composable
fun MessageList(
    headers: List<MessageHeader>,
    /** Account names to show on each row (the combined inbox), or null when all rows are one account's. */
    accountNames: Map<String, String>?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    selection: SnapshotStateList<String>,
    onOpen: (MessageHeader) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    /** In Sent and Drafts the recipients are shown instead of the sender. */
    showRecipients: Boolean = false,
    empty: @Composable () -> Unit,
) {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Swiped away (archive, delete): hidden at once, done for real once the Undo bar is gone.
    val pending = remember { mutableStateListOf<String>() }
    val shown = remember(headers, pending.toList()) { headers.filter { it.key !in pending } }
    val keys = remember(headers) { headers.map { it.key }.toSet() }
    LaunchedEffect(keys) { selection.retainAll { it in keys } }
    BackHandler(enabled = selection.isNotEmpty()) { selection.clear() }
    val chosen = headers.filter { it.key in selection }

    /** Shows the Undo bar; [commit] runs once it's gone without Undo, [undo] when Undo is tapped. */
    fun offerUndo(message: String, undo: () -> Unit, commit: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            var undone = false
            try {
                val result = snackbar.showSnackbar(message, actionLabel = context.getString(R.string.undo), duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) { undone = true; undo() }
            } finally {
                // Also when the screen goes away while the bar is up: the action still happens.
                if (!undone) commit()
            }
        }
    }

    fun archiveOrDelete(items: List<MessageHeader>, archive: Boolean) {
        if (items.isEmpty()) return
        val k = items.map { it.key }
        pending.addAll(k)
        c.notifier.cancelKeys(items)
        val msg = if (archive) context.resources.getQuantityString(R.plurals.archived_n, items.size, items.size)
        else context.resources.getQuantityString(R.plurals.deleted_n, items.size, items.size)
        offerUndo(
            msg,
            undo = { pending.removeAll(k) },
            commit = {
                c.runTask {
                    try {
                        if (archive) c.actions.archive(items) else c.actions.delete(items)
                    } catch (e: Exception) {
                        // Didn't work: show them again (the folder is read again too).
                        pending.removeAll(k)
                        throw e
                    }
                }
            },
        )
    }

    fun onSwipe(h: MessageHeader, action: SwipeAction) {
        when (action) {
            SwipeAction.NONE -> Unit
            SwipeAction.READ -> {
                if (!h.seen) c.notifier.cancel(h)
                c.runTask { c.actions.setSeen(listOf(h), !h.seen) }
            }
            SwipeAction.FLAG -> c.runTask { c.actions.setFlagged(listOf(h), !h.flagged) }
            SwipeAction.ARCHIVE -> if (c.actions.canArchive(h.accountId, h.folder)) archiveOrDelete(listOf(h), archive = true)
            else android.widget.Toast.makeText(context, context.getString(R.string.no_archive_folder), android.widget.Toast.LENGTH_SHORT).show()
            SwipeAction.DELETE -> archiveOrDelete(listOf(h), archive = false)
        }
    }

    // Older messages load by themselves when you scroll near the end.
    val shownCount by rememberUpdatedState(shown.size)
    val busy by rememberUpdatedState(loadingMore)
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (!busy && shownCount > 0 && last >= shownCount - 5) loadMore() }
    }

    var moving by remember { mutableStateOf<List<MessageHeader>?>(null) }
    var more by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                if (shown.isEmpty() && !refreshing) {
                    // Scrollable, so pull-to-refresh still works on an empty list.
                    LazyColumn(Modifier.fillMaxSize()) { item { Box(Modifier.fillParentMaxSize()) { empty() } } }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = state,
                        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp + LocalBottomClearance.current),
                    ) {
                        itemsIndexed(shown, key = { _, h -> h.key }) { index, h ->
                            val row: @Composable (Modifier) -> Unit = { mod ->
                                MessageRow(
                                    first = index == 0,
                                    h = h,
                                    account = accountNames?.get(h.accountId),
                                    showRecipients = showRecipients,
                                    selected = h.key in selection,
                                    modifier = mod,
                                    onClick = {
                                        if (selection.isNotEmpty()) {
                                            if (h.key in selection) selection.remove(h.key) else selection.add(h.key)
                                        } else {
                                            onOpen(h)
                                        }
                                    },
                                    onLongClick = { if (h.key in selection) selection.remove(h.key) else selection.add(h.key) },
                                )
                            }
                            if (selection.isEmpty() && (settings.swipeLeft != SwipeAction.NONE || settings.swipeRight != SwipeAction.NONE)) {
                                SwipeableRow(h, settings.swipeRight, settings.swipeLeft, Modifier.animateItem(), onSwipe = { onSwipe(h, it) }) { row(Modifier) }
                            } else {
                                row(Modifier.animateItem())
                            }
                        }
                        if (loadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = CtrlColors.AccentText)
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomClearance.current + 8.dp, start = 12.dp, end = 12.dp),
            ) { data ->
                Snackbar(data, shape = CtrlControlShape, containerColor = CtrlColors.Raised, contentColor = CtrlColors.Text, actionColor = CtrlColors.AccentText)
            }
        }
        if (selection.isNotEmpty() && chosen.isNotEmpty()) {
            val anyUnread = chosen.any { !it.seen }
            val allFlagged = chosen.all { it.flagged }
            val canArchive = chosen.all { c.actions.canArchive(it.accountId, it.folder) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = LocalBottomClearance.current)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(CtrlCardShape)
                    .background(CtrlColors.Surface)
                    .border(1.dp, CtrlColors.ControlOutline, CtrlCardShape)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BarAction(
                    if (anyUnread) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread,
                    stringResource(if (anyUnread) R.string.mark_read else R.string.mark_unread),
                ) {
                    val sel = chosen
                    selection.clear()
                    if (anyUnread) c.notifier.cancelKeys(sel)
                    c.runTask { c.actions.setSeen(sel, anyUnread) }
                }
                BarAction(Icons.Outlined.Flag, stringResource(if (allFlagged) R.string.unflag else R.string.flag)) {
                    val sel = chosen
                    selection.clear()
                    c.runTask { c.actions.setFlagged(sel, !allFlagged) }
                }
                if (canArchive) {
                    BarAction(Icons.Outlined.Archive, stringResource(R.string.archive)) {
                        val sel = chosen
                        selection.clear()
                        archiveOrDelete(sel, archive = true)
                    }
                }
                BarAction(Icons.Outlined.Delete, stringResource(R.string.delete)) {
                    val sel = chosen
                    selection.clear()
                    archiveOrDelete(sel, archive = false)
                }
                if (chosen.map { it.accountId }.distinct().size == 1) {
                    BarAction(Icons.Outlined.MoreVert, stringResource(R.string.more)) { more = true }
                }
            }
        }
    }

    if (more && chosen.isNotEmpty()) {
        val oneAccount = chosen.map { it.accountId }.distinct().size == 1
        val junk = chosen.all { h -> c.accounts.get(h.accountId)?.junkFolder.let { it != null && it != h.folder } }
        val actions = buildList {
            add(SheetAction(context.getString(R.string.move_to), Icons.Outlined.DriveFileMove) { moving = chosen })
            if (junk) {
                add(SheetAction(context.getString(R.string.move_to_junk), Icons.Outlined.Report) {
                    val sel = chosen
                    selection.clear()
                    c.notifier.cancelKeys(sel)
                    c.runTask { c.actions.junk(sel) }
                })
            }
        }
        if (oneAccount) {
            ActionSheet(pluralStringResource(R.plurals.selected_count, chosen.size, chosen.size), null, actions, onDismiss = { more = false })
        }
    }

    moving?.let { items ->
        FolderPicker(
            accountId = items.first().accountId,
            exclude = items.map { it.folder }.toSet(),
            onDismiss = { moving = null },
        ) { target ->
            moving = null
            selection.clear()
            c.runTask { c.actions.move(items, target.fullName) }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, label, Modifier.size(22.dp), tint = CtrlColors.Text) }
        Text(label.uppercase(), style = CtrlType.Status, color = CtrlColors.Muted, maxLines = 1)
    }
}

/** The bar with the selection count and a close button, in place of a screen's title. */
@Composable
fun SelectionTitle(count: Int, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Gutter - 4.dp, end = Gutter, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.close), onClick = onClose)
        Spacer(Modifier.width(14.dp))
        Text(pluralStringResource(R.plurals.selected_count, count, count), style = MaterialTheme.typography.titleLarge, color = CtrlColors.Text)
    }
}

@Composable
fun MessageRow(
    first: Boolean,
    h: MessageHeader,
    account: String?,
    showRecipients: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val unread = !h.seen
    val who = if (showRecipients) {
        h.to.joinToString(", ") { it.display }.ifBlank { stringResource(R.string.no_recipients) }
    } else {
        h.from?.display ?: stringResource(R.string.unknown_sender)
    }
    val key = if (showRecipients) h.to.firstOrNull()?.email.orEmpty() else h.from?.email.orEmpty()
    Row(
        modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.topDivider())
            .background(if (selected) CtrlColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            Avatar(who, key.ifBlank { who })
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    null,
                    Modifier.align(Alignment.BottomEnd).size(20.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp)),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unread) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(CtrlColors.Accent))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    who,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    color = CtrlColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    shortTime(context, h.date).uppercase(),
                    style = CtrlType.Status,
                    color = if (unread) CtrlColors.AccentText else CtrlColors.Inactive,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (h.answered) {
                    Icon(Icons.AutoMirrored.Outlined.Reply, stringResource(R.string.answered), Modifier.size(15.dp), tint = CtrlColors.Muted)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    h.subject.ifBlank { stringResource(R.string.no_subject) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) CtrlColors.Text else CtrlColors.Muted,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (h.hasAttachments) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.AttachFile, stringResource(R.string.has_attachments), Modifier.size(16.dp), tint = CtrlColors.Muted)
                }
                if (h.flagged) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Flag, stringResource(R.string.flagged), Modifier.size(16.dp), tint = CtrlColors.AccentText)
                }
            }
            if (account != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(2.dp)).background(avatarColor(h.accountId)))
                    Spacer(Modifier.width(6.dp))
                    Text(account.uppercase(), style = CtrlType.Status, color = CtrlColors.Inactive, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private data class SwipeLook(val icon: ImageVector, val label: Int, val destructive: Boolean)

private fun swipeLook(action: SwipeAction, h: MessageHeader): SwipeLook? = when (action) {
    SwipeAction.NONE -> null
    SwipeAction.READ -> if (h.seen) SwipeLook(Icons.Outlined.MarkEmailUnread, R.string.mark_unread, false)
    else SwipeLook(Icons.Outlined.MarkEmailRead, R.string.mark_read, false)
    SwipeAction.FLAG -> SwipeLook(Icons.Outlined.Flag, if (h.flagged) R.string.unflag else R.string.flag, false)
    SwipeAction.ARCHIVE -> SwipeLook(Icons.Outlined.Archive, R.string.archive, false)
    SwipeAction.DELETE -> SwipeLook(Icons.Outlined.Delete, R.string.delete, true)
}

@Composable
private fun SwipeableRow(
    h: MessageHeader,
    right: SwipeAction,
    left: SwipeAction,
    modifier: Modifier,
    onSwipe: (SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentRight by rememberUpdatedState(right)
    val currentLeft by rememberUpdatedState(left)
    val lastFired = remember { longArrayOf(0L) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentRight
                SwipeToDismissBoxValue.EndToStart -> currentLeft
                SwipeToDismissBoxValue.Settled -> null
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (action != null && action != SwipeAction.NONE && now - lastFired[0] > 600) {
                lastFired[0] = now
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnSwipe(action)
            }
            // Always spring back: the list itself shows what changed.
            false
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = right != SwipeAction.NONE,
        enableDismissFromEndToStart = left != SwipeAction.NONE,
        backgroundContent = {
            val direction = state.dismissDirection
            val action = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> right
                SwipeToDismissBoxValue.EndToStart -> left
                SwipeToDismissBoxValue.Settled -> null
            }
            val look = action?.let { swipeLook(it, h) }
            if (look != null) {
                val bg = if (look.destructive) CtrlColors.Error else CtrlColors.Accent
                val fg = if (look.destructive) Color.White else CtrlColors.OnAccent
                Row(
                    Modifier.fillMaxSize().background(bg).padding(horizontal = Gutter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (direction == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                ) {
                    Icon(look.icon, null, Modifier.size(22.dp), tint = fg)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(look.label).uppercase(), style = CtrlType.Status, color = fg, maxLines = 1)
                }
            }
        },
    ) {
        Box(Modifier.background(CtrlColors.Background)) { content() }
    }
}

/** Picks a folder of one account (Move to…). */
@Composable
fun FolderPicker(accountId: String, exclude: Set<String>, onDismiss: () -> Unit, onPick: (FolderInfo) -> Unit) {
    val c = LocalContext.current.container
    val folders by produceState<List<FolderInfo>?>(null, accountId) {
        value = c.cache.folders(accountId).ifEmpty { runCatching { c.actions.refreshFolders(accountId) }.getOrDefault(emptyList()) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = CtrlSheetShape,
        containerColor = CtrlColors.Surface,
        contentColor = CtrlColors.Text,
    ) {
        SectionLabel(stringResource(R.string.move_to), Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        val list = folders?.filter { it.selectable && it.fullName !in exclude }
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CtrlColors.AccentText)
            }
            list.isEmpty() -> Text(
                stringResource(R.string.no_folders),
                Modifier.padding(24.dp),
                color = CtrlColors.Muted,
            )
            else -> LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(list, key = { _, f -> f.fullName }) { i, f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (i > 0) Modifier.topDivider(24.dp) else Modifier)
                            .clickable { onPick(f) }
                            .heightIn(min = 52.dp)
                            .padding(start = 24.dp + (f.depth * 16).dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(folderIcon(f.role), null, Modifier.size(20.dp), tint = CtrlColors.Muted)
                        Spacer(Modifier.width(16.dp))
                        Text(folderName(f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

fun folderIcon(role: FolderRole): ImageVector = when (role) {
    FolderRole.INBOX -> Icons.Outlined.MarkEmailUnread
    FolderRole.SENT -> Icons.AutoMirrored.Outlined.Reply
    FolderRole.DRAFTS -> Icons.Outlined.Drafts
    FolderRole.TRASH -> Icons.Outlined.Delete
    FolderRole.JUNK -> Icons.Outlined.Report
    FolderRole.ARCHIVE, FolderRole.ALL -> Icons.Outlined.Archive
    FolderRole.FLAGGED -> Icons.Outlined.Flag
    FolderRole.OTHER -> Icons.Outlined.Folder
}

/** The folder's name, with the special folders in your language. */
@Composable
fun folderName(f: FolderInfo): String = when (f.role) {
    FolderRole.INBOX -> stringResource(R.string.folder_inbox)
    FolderRole.SENT -> stringResource(R.string.folder_sent)
    FolderRole.DRAFTS -> stringResource(R.string.folder_drafts)
    FolderRole.TRASH -> stringResource(R.string.folder_trash)
    FolderRole.JUNK -> stringResource(R.string.folder_junk)
    else -> f.name
}
