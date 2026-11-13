package com.vknxworks.mailctrl.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.mail.FolderInfo
import com.vknxworks.mailctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.mailctrl.ui.components.Group
import com.vknxworks.mailctrl.ui.components.GroupRow
import com.vknxworks.mailctrl.ui.components.LocalBottomClearance
import com.vknxworks.mailctrl.ui.components.TabTitle
import com.vknxworks.mailctrl.ui.components.friendlyMessage
import com.vknxworks.mailctrl.ui.components.topDivider
import com.vknxworks.mailctrl.ui.inbox.folderIcon
import com.vknxworks.mailctrl.ui.inbox.folderName
import com.vknxworks.mailctrl.ui.nav.AccountSetupRoute
import com.vknxworks.mailctrl.ui.nav.DraftsRoute
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The Folders tab: every account with its folders, and the drafts kept on the phone. */
@Composable
fun FoldersScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    val drafts by c.drafts.drafts.collectAsStateWithLifecycle()
    val version by c.cache.version.collectAsStateWithLifecycle()
    val folders by produceState<Map<String, List<FolderInfo>>>(emptyMap(), version, accounts) {
        value = accounts.associate { it.id to c.cache.folders(it.id) }
    }
    val problems = remember { mutableStateMapOf<String, String>() }
    var refreshing by remember { mutableStateOf(false) }

    /** [force]: pulled down. Otherwise accounts whose folders were read a moment ago are left as they are. */
    fun refresh(force: Boolean = true) {
        if (refreshing) return
        val due = if (force) accounts else accounts.filterNot { c.actions.foldersFresh(it.id) }
        if (due.isEmpty()) return
        refreshing = true
        scope.launch {
            coroutineScope {
                due.map { a -> async { a.id to runCatching { c.actions.refreshFolders(a.id) }.exceptionOrNull() } }.awaitAll()
            }.forEach { (id, e) -> if (e == null) problems.remove(id) else problems[id] = e.friendlyMessage() }
            refreshing = false
        }
    }
    LaunchedEffect(accounts.map { it.id }) { refresh(force = false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(stringResource(R.string.tab_folders))
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp + LocalBottomClearance.current)) {
                if (drafts.isNotEmpty()) {
                    Group {
                        GroupRow(
                            stringResource(R.string.drafts_local),
                            stringResource(R.string.drafts_local_subtitle, drafts.size),
                            icon = Icons.Outlined.EditNote,
                            showChevron = true,
                            onClick = { nav.navigate(DraftsRoute) },
                        )
                    }
                }
                accounts.forEach { a ->
                    AccountFolders(a, folders[a.id].orEmpty(), problems[a.id], loading = refreshing) { f -> nav.folder(a.id, f.fullName) }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CtrlSecondaryButton(stringResource(R.string.add_account), onClick = { nav.navigate(AccountSetupRoute()) }, icon = Icons.Outlined.Add)
                }
            }
        }
    }
}

@Composable
private fun AccountFolders(a: Account, folders: List<FolderInfo>, problem: String?, loading: Boolean, onOpen: (FolderInfo) -> Unit) {
    Group(a.title, footer = problem) {
        val list = folders.filter { it.selectable || folders.any { child -> child.fullName.startsWith(it.fullName) && child.fullName != it.fullName } }
        if (list.isEmpty()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CtrlColors.AccentText)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.loading_folders), color = CtrlColors.Muted, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp), tint = CtrlColors.Muted)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.no_folders), color = CtrlColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        list.forEachIndexed { i, f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (i > 0) Modifier.topDivider(0.dp, CtrlColors.Outline) else Modifier)
                    .clickable(enabled = f.selectable) { onOpen(f) }
                    .heightIn(min = 54.dp)
                    .padding(start = 16.dp + (f.depth * 18).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(folderIcon(f.role), null, Modifier.size(20.dp), tint = if (f.unread > 0) CtrlColors.AccentText else CtrlColors.Muted)
                Spacer(Modifier.width(14.dp))
                Text(
                    folderName(f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (f.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (f.selectable) CtrlColors.Text else CtrlColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (f.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.widthIn(min = 22.dp).heightIn(min = 22.dp).clip(RoundedCornerShape(7.dp)).background(CtrlColors.Accent).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (f.unread > 999) "999+" else f.unread.toString(),
                            style = CtrlType.Status.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                            color = CtrlColors.OnAccent,
                        )
                    }
                } else if (f.total > 0) {
                    Text(f.total.toString(), style = CtrlType.Meta, color = CtrlColors.Inactive)
                }
            }
        }
    }
}
