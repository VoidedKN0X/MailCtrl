package com.vknxworks.mailctrl.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.MailActions
import com.vknxworks.mailctrl.mail.FolderRole
import com.vknxworks.mailctrl.mail.MessageHeader
import com.vknxworks.mailctrl.ui.components.CtrlChip
import com.vknxworks.mailctrl.ui.components.CtrlFieldShape
import com.vknxworks.mailctrl.ui.components.LocalBottomClearance
import com.vknxworks.mailctrl.ui.components.MessageView
import com.vknxworks.mailctrl.ui.components.TabTitle
import com.vknxworks.mailctrl.ui.components.ctrlFieldColors
import com.vknxworks.mailctrl.ui.components.friendlyMessage
import com.vknxworks.mailctrl.ui.inbox.MessageList
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where to search. */
private enum class Scope(val label: Int) {
    INBOX(R.string.search_inbox),
    SENT(R.string.search_sent),
    ARCHIVE(R.string.search_archive),
    EVERYWHERE(R.string.search_everywhere),
}

/** The Search tab: searches on your mail servers (subject, sender, recipients and text). */
@Composable
fun SearchScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var chosenAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var where by rememberSaveable { mutableStateOf(Scope.INBOX) }
    var results by remember { mutableStateOf<List<MessageHeader>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Which search is the latest: a cancelled one finishing late mustn't stop the spinner of the next.
    val generation = remember { intArrayOf(0) }
    val selection = remember { mutableStateListOf<String>() }
    val account = accounts.firstOrNull { it.id == chosenAccount } ?: accounts.firstOrNull()

    fun run() {
        val q = query.trim()
        val a = account ?: return
        if (q.isEmpty()) return
        focus.clearFocus()
        job?.cancel()
        searching = true
        problem = null
        val gen = ++generation[0]
        job = scope.launch {
            try {
                val folders = when (where) {
                    Scope.INBOX -> listOf(MailActions.INBOX)
                    Scope.SENT -> listOfNotNull(a.sentFolder)
                    Scope.ARCHIVE -> listOfNotNull(a.archiveFolder)
                    Scope.EVERYWHERE -> c.cache.folders(a.id).ifEmpty { c.actions.refreshFolders(a.id) }
                        .filter { it.selectable && it.role != FolderRole.TRASH && it.role != FolderRole.JUNK && it.role != FolderRole.ALL }
                        .map { it.fullName }
                }
                val found = mutableListOf<MessageHeader>()
                for (f in folders) {
                    found += c.mail.search(a.id, f, q, limit = if (where == Scope.EVERYWHERE) 50 else 150)
                    results = found.sortedByDescending { it.date }
                }
                results = found.sortedByDescending { it.date }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.friendlyMessage()
            } finally {
                if (gen == generation[0]) searching = false
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(stringResource(R.string.tab_search))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = ""; results = null; problem = null; job?.cancel() }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.clear))
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            shape = CtrlFieldShape,
            colors = ctrlFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (accounts.size > 1) {
                accounts.forEach { a ->
                    CtrlChip(account?.id == a.id, onClick = { chosenAccount = a.id; if (query.isNotBlank()) run() }, label = a.title)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Scope.entries.filter { s ->
                when (s) {
                    Scope.SENT -> account?.sentFolder != null
                    Scope.ARCHIVE -> account?.archiveFolder != null
                    else -> true
                }
            }.forEach { s ->
                CtrlChip(where == s, onClick = { where = s; if (query.isNotBlank()) run() }, label = stringResource(s.label))
            }
        }
        val list = results
        when {
            list == null && !searching -> MessageView(
                Icons.Outlined.Search,
                stringResource(R.string.search_title),
                problem ?: stringResource(R.string.search_body),
                modifier = Modifier.padding(bottom = LocalBottomClearance.current),
            )
            else -> MessageList(
                headers = list.orEmpty(),
                accountNames = null,
                refreshing = searching,
                onRefresh = ::run,
                hasMore = false,
                loadingMore = false,
                onLoadMore = {},
                selection = selection,
                onOpen = { nav.message(it.accountId, it.folder, it.uid) },
            ) {
                MessageView(
                    Icons.Outlined.SearchOff,
                    stringResource(R.string.search_nothing),
                    problem ?: stringResource(R.string.search_nothing_body),
                    modifier = Modifier.padding(bottom = LocalBottomClearance.current),
                )
            }
        }
    }
}
