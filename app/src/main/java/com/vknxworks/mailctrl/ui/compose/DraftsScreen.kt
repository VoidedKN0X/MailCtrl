package com.vknxworks.mailctrl.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.drafts.Draft
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.MessageView
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.components.runTask
import com.vknxworks.mailctrl.ui.components.shortTime
import com.vknxworks.mailctrl.ui.components.topDivider
import com.vknxworks.mailctrl.ui.nav.ComposeMode
import com.vknxworks.mailctrl.ui.nav.ComposeRoute
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter

/** Messages you started writing, kept on the phone (encrypted) until you send or delete them. */
@Composable
fun DraftsScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val drafts by c.drafts.drafts.collectAsStateWithLifecycle()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Draft?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.drafts_local))
        if (drafts.isEmpty()) {
            MessageView(Icons.Outlined.EditNote, stringResource(R.string.drafts_empty), stringResource(R.string.drafts_empty_body))
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(drafts, key = { _, d -> d.id }) { i, d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (i > 0) Modifier.topDivider() else Modifier)
                            .clickable { nav.navigate(ComposeRoute(mode = ComposeMode.DRAFT, draftId = d.id, accountId = d.accountId)) }
                            .heightIn(min = 64.dp)
                            .padding(start = Gutter, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    d.to.joinToString(", ") { it.display }.ifBlank { stringResource(R.string.no_recipients) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = CtrlColors.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(shortTime(context, d.updated).uppercase(), style = CtrlType.Status, color = CtrlColors.Inactive)
                            }
                            Text(
                                d.subject.ifBlank { stringResource(R.string.no_subject) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = CtrlColors.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (accounts.size > 1) {
                                Text(
                                    (accounts.firstOrNull { it.id == d.accountId }?.title ?: "").uppercase(),
                                    style = CtrlType.Status,
                                    color = CtrlColors.Inactive,
                                    maxLines = 1,
                                )
                            }
                        }
                        IconButton(onClick = { deleting = d }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = CtrlColors.Muted) }
                    }
                }
            }
        }
    }

    deleting?.let { d ->
        ConfirmDialog(
            stringResource(R.string.discard_title),
            stringResource(R.string.discard_body),
            stringResource(R.string.discard),
            onDismiss = { deleting = null },
        ) {
            deleting = null
            c.runTask { c.drafts.delete(d.id) }
        }
    }
}
