package com.vknxworks.mailctrl.ui.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.mail.MessageHeader
import com.vknxworks.mailctrl.mail.Threads
import com.vknxworks.mailctrl.ui.components.MessageView
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import kotlinx.coroutines.launch

/** How many of a folder's newest messages (and of Sent) are looked through for a conversation. */
private const val LOOKED_THROUGH = 600
private const val SENT_LOOKED_THROUGH = 300

/**
 * One conversation: the message [uid] of [folder] and everything in that folder's list that belongs with it,
 * plus your own answers from the Sent folder when the phone has them. Newest first; tap a message to open it.
 * Swiping and selecting work as in any list, per message.
 */
@Composable
fun ConversationScreen(accountId: String, folder: String, uid: Long) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val lists by c.cache.listVersions.collectAsStateWithLifecycle()
    val sent = c.accounts.get(accountId)?.sentFolder?.takeIf { it != folder }
    val version = lists.of(accountId, folder) + (sent?.let { lists.of(accountId, it) } ?: 0)
    val messages by produceState<List<MessageHeader>?>(null, accountId, folder, uid, version) {
        val here = c.cache.newest(accountId, folder, LOOKED_THROUGH)
        val mine = sent?.let { c.cache.newest(accountId, it, SENT_LOOKED_THROUGH) }.orEmpty()
        // Put together the same way the list does, then your own answers are added by what they answer.
        val all = (here + mine).sortedByDescending { it.date }
        val threads = Threads.group(all, { it.accountId }, { it.messageId }, { it.refs }, { it.subject })
        value = threads.firstOrNull { t -> t.any { it.folder == folder && it.uid == uid } }.orEmpty()
    }
    var refreshing by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<String>() }
    val list = messages
    val title = list?.lastOrNull()?.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.conversation)

    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) {
            ScreenTopBar(title)
        } else {
            Box(Modifier.statusBarsPadding()) { SelectionTitle(selection.size) { selection.clear() } }
        }
        MessageList(
            headers = list.orEmpty(),
            accountNames = null,
            refreshing = refreshing || list == null,
            onRefresh = {
                if (!refreshing) {
                    refreshing = true
                    scope.launch {
                        runCatching { c.actions.refresh(accountId, folder) }
                        refreshing = false
                    }
                }
            },
            hasMore = false,
            loadingMore = false,
            onLoadMore = {},
            selection = selection,
            onOpen = { h -> nav.message(h.accountId, h.folder, h.uid) },
        ) {
            MessageView(Icons.Outlined.Forum, stringResource(R.string.conversation_empty), null)
        }
    }
}
