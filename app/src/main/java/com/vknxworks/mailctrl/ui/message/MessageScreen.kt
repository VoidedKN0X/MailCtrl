package com.vknxworks.mailctrl.ui.message

import android.content.Context
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unsubscribe
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.pgp.KeyOffer
import com.vknxworks.mailctrl.data.pgp.PgpView
import com.vknxworks.mailctrl.data.settings.RemoteContent
import com.vknxworks.mailctrl.pgp.KeyInfo
import com.vknxworks.mailctrl.pgp.PgpEngine
import com.vknxworks.mailctrl.pgp.PgpFailure
import com.vknxworks.mailctrl.pgp.UnlockedKey
import com.vknxworks.mailctrl.ui.pgp.KeyImportDialog
import com.vknxworks.mailctrl.ui.pgp.PassphraseDialog
import com.vknxworks.mailctrl.ui.pgp.WkdLookup
import org.bouncycastle.openpgp.PGPKeyRing
import com.vknxworks.mailctrl.mail.Address
import com.vknxworks.mailctrl.mail.Attachment
import com.vknxworks.mailctrl.mail.MessageBody
import com.vknxworks.mailctrl.mail.MessageHeader
import com.vknxworks.mailctrl.ui.components.ActionSheet
import com.vknxworks.mailctrl.ui.components.Avatar
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.CtrlAlertDialog
import com.vknxworks.mailctrl.ui.components.CtrlCardShape
import com.vknxworks.mailctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.mailctrl.ui.components.CtrlSquareButton
import com.vknxworks.mailctrl.ui.components.ErrorView
import com.vknxworks.mailctrl.ui.components.LoadingView
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.components.SheetAction
import com.vknxworks.mailctrl.ui.components.attachmentSize
import com.vknxworks.mailctrl.ui.components.friendlyMessage
import com.vknxworks.mailctrl.ui.components.fullTime
import com.vknxworks.mailctrl.ui.components.openFile
import com.vknxworks.mailctrl.ui.components.openLink
import com.vknxworks.mailctrl.ui.components.runTask
import com.vknxworks.mailctrl.ui.components.shareFile
import com.vknxworks.mailctrl.ui.inbox.FolderPicker
import com.vknxworks.mailctrl.ui.lock.AppLock
import com.vknxworks.mailctrl.ui.nav.ComposeMode
import com.vknxworks.mailctrl.ui.nav.ComposeRoute
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.nav.MessageRoute
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Loaded {
    /** [preview]: the message as it is in the list on the phone, shown while the rest downloads. */
    data class Loading(val preview: MessageHeader? = null) : Loaded
    data class Ready(val body: MessageBody) : Loaded
    data class Failed(val message: String) : Loaded
}

/** One message: who it's from, its text and attachments, and what you can do with it. */
@Composable
fun MessageScreen(accountId: String, folder: String, uid: Long) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Loaded>(Loaded.Loading()) }
    var remote by remember { mutableStateOf(false) }
    var linkToOpen by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    // OpenPGP: what decrypting and checking found, a key in the message to import, and the dialogs for them.
    var pgpView by remember { mutableStateOf<PgpView?>(null) }
    var pgpBusy by remember { mutableStateOf(false) }
    var pgpRun by remember { mutableIntStateOf(0) }
    var offer by remember { mutableStateOf<KeyOffer?>(null) }
    var unlocking by remember { mutableStateOf<KeyInfo?>(null) }
    // A key opened for this message: kept while it's open, even when passphrases aren't remembered.
    var openedKey by remember { mutableStateOf<UnlockedKey?>(null) }
    var importRings by remember { mutableStateOf<List<PGPKeyRing>?>(null) }
    var importReplaces by remember { mutableStateOf(false) }
    var wkdEmail by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(accountId, folder, uid, attempt) {
        state = Loaded.Loading(c.cache.header(accountId, folder, uid))
        state = try {
            // The saved settings, not the placeholder ones a message opened right at start-up would see.
            val saved = c.settings.current()
            val body = c.mail.open(accountId, folder, uid, markRead = saved.markReadOnOpen)
            c.lastOpened = body
            if (body.header.seen) c.cache.setFlags(accountId, folder, listOf(uid), seen = true)
            c.notifier.cancel(body.header)
            remote = saved.remoteContent == RemoteContent.ALWAYS
            Loaded.Ready(body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Loaded.Failed(e.friendlyMessage())
        }
    }

    val body = (state as? Loaded.Ready)?.body
    val h = body?.header
    val canArchive = c.actions.canArchive(accountId, folder)

    // Decrypts and checks signatures once the message is there, and again after a key was unlocked or imported.
    LaunchedEffect(h?.key, pgpRun) {
        val b = (state as? Loaded.Ready)?.body ?: return@LaunchedEffect
        if (b.pgp != null) {
            pgpBusy = true
            try {
                val v = c.pgp.read(b, openedKey)
                pgpView = v
                // Answering or forwarding quotes the decrypted text.
                if (v.body.decrypted) c.lastOpened = v.body
            } finally {
                pgpBusy = false
            }
        } else {
            pgpView = null
        }
        offer = if (b.autocrypt != null) c.pgp.autocryptOffer(b) else null
    }

    fun leave() = nav.close(MessageRoute::class)

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("") {
            if (h != null && body != null) {
                CtrlSquareButton(if (h.flagged) Icons.Filled.Flag else Icons.Outlined.Flag, stringResource(if (h.flagged) R.string.unflag else R.string.flag), onClick = {
                    val now = !h.flagged
                    state = Loaded.Ready(body.copy(header = h.copy(flagged = now)))
                    c.runTask { c.actions.setFlagged(listOf(h), now) }
                })
                Spacer(Modifier.width(8.dp))
                if (canArchive) {
                    CtrlSquareButton(Icons.Outlined.Archive, stringResource(R.string.archive), onClick = {
                        c.runTask { c.actions.archive(listOf(h)) }
                        leave()
                    })
                    Spacer(Modifier.width(8.dp))
                }
                CtrlSquareButton(Icons.Outlined.Delete, stringResource(R.string.delete), onClick = {
                    if (settings.confirmDelete) {
                        deleting = true
                    } else {
                        c.runTask { c.actions.delete(listOf(h)) }
                        leave()
                    }
                })
                Spacer(Modifier.width(8.dp))
                CtrlSquareButton(Icons.Outlined.MoreVert, stringResource(R.string.more), onClick = { more = true })
                Spacer(Modifier.width(4.dp))
            }
        }
        when (val s = state) {
            is Loaded.Loading -> if (s.preview != null) MessagePreview(s.preview) else LoadingView()
            is Loaded.Failed -> ErrorView(s.message, onRetry = { attempt++ })
            is Loaded.Ready -> {
                // The decrypted content, with the flags as they are now.
                val view = pgpView
                val shown = view?.body?.let { v -> v.copy(header = s.body.header.copy(subject = v.header.subject)) } ?: s.body
                MessageContent(
                    shown,
                    remote = remote,
                    showDetails = details,
                    onToggleDetails = { details = !details },
                    onLoadRemote = { remote = true },
                    onLink = { url -> linkToOpen = url },
                    onReply = { mode -> nav.compose(ComposeRoute(mode = mode, accountId = accountId, folder = folder, uid = uid)) },
                    onKeys = { rings ->
                        importReplaces = false
                        importRings = rings
                    },
                ) {
                    if (pgpBusy && view == null) PgpWorking()
                    if (view != null) {
                        PgpBanners(
                            view,
                            s.body.header.from,
                            onUnlock = { unlocking = view.locked },
                            onLookup = s.body.header.from?.email?.takeIf { settings.wkdLookup }?.let { email -> { wkdEmail = email } },
                        )
                    }
                    offer?.let { o ->
                        KeyOfferBanner(o) {
                            importReplaces = o.replaces
                            importRings = listOf(o.ring)
                        }
                    }
                }
            }
        }
    }

    unlocking?.let { info ->
        PassphraseDialog(info, onDismiss = { unlocking = null }) { key ->
            unlocking = null
            openedKey = key
            pgpRun++
        }
    }
    importRings?.let { rings ->
        KeyImportDialog(rings, onDismiss = { importRings = null }, replaces = importReplaces) { pgpRun++ }
    }
    wkdEmail?.let { email -> WkdLookup(email, onDismiss = { wkdEmail = null }) { pgpRun++ } }

    if (more && h != null && body != null) {
        val hasJunk = c.accounts.get(accountId)?.junkFolder.let { it != null && it != folder }
        val actions = buildList {
            add(SheetAction(stringResource(R.string.mark_unread), Icons.Outlined.MarkEmailUnread) {
                c.runTask { c.actions.setSeen(listOf(h), false) }
                leave()
            })
            add(SheetAction(stringResource(R.string.move_to), Icons.Outlined.DriveFileMove) { moving = true })
            if (hasJunk) {
                add(SheetAction(stringResource(R.string.move_to_junk), Icons.Outlined.Report) {
                    c.runTask { c.actions.junk(listOf(h)) }
                    leave()
                })
            }
            body.unsubscribe?.let { link ->
                add(SheetAction(stringResource(R.string.unsubscribe), Icons.Outlined.Unsubscribe) { linkToOpen = link })
            }
        }
        ActionSheet(h.subject.ifBlank { stringResource(R.string.no_subject) }, h.from?.display, actions, onDismiss = { more = false })
    }

    if (moving && h != null) {
        FolderPicker(accountId, setOf(folder), onDismiss = { moving = false }) { target ->
            moving = false
            c.runTask { c.actions.move(listOf(h), target.fullName) }
            leave()
        }
    }

    if (deleting && h != null) {
        ConfirmDialog(
            stringResource(R.string.delete_message_title),
            stringResource(R.string.delete_message_body),
            stringResource(R.string.delete),
            onDismiss = { deleting = false },
        ) {
            deleting = false
            c.runTask { c.actions.delete(listOf(h)) }
            leave()
        }
    }

    linkToOpen?.let { url ->
        LinkDialog(url, onDismiss = { linkToOpen = null }) {
            linkToOpen = null
            if (url.startsWith("mailto:", ignoreCase = true)) {
                val m = runCatching { android.net.MailTo.parse(url) }.getOrNull()
                c.composeRequest.value = com.vknxworks.mailctrl.PendingAction.Compose(
                    to = m?.to?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                    subject = m?.subject.orEmpty(),
                    body = m?.body.orEmpty(),
                )
                nav.compose(ComposeRoute(fromRequest = true, accountId = accountId))
            } else {
                openLink(context, url)
            }
        }
    }
}

/** Asks before leaving the app for a link, and shows where it really goes (links in mail can lie about that). */
@Composable
private fun LinkDialog(url: String, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val uri = remember(url) { runCatching { Uri.parse(url) }.getOrNull() }
    val mail = url.startsWith("mailto:", ignoreCase = true)
    val web = uri?.scheme?.lowercase() in setOf("http", "https")
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (mail) R.string.link_mail_title else R.string.link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (web) Text(uri?.host.orEmpty(), style = MaterialTheme.typography.titleMedium, color = CtrlColors.Text)
                SelectionContainer { Text(url.take(600), style = CtrlType.Meta, color = CtrlColors.Muted) }
                if (uri?.scheme?.lowercase() == "http") Text(stringResource(R.string.link_unencrypted), color = CtrlColors.Error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onOpen, enabled = web || mail) { Text(stringResource(if (mail) R.string.write else R.string.open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Subject and sender straight away, from the list on the phone, while the message itself downloads. */
@Composable
private fun MessagePreview(h: MessageHeader) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Text(
            h.subject.ifBlank { stringResource(R.string.no_subject) },
            style = MaterialTheme.typography.headlineSmall,
            color = CtrlColors.Text,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 4.dp, bottom = 14.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val from = h.from
            Avatar(from?.display ?: "?", from?.email ?: "?", size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(from?.display ?: stringResource(R.string.unknown_sender), style = MaterialTheme.typography.titleSmall, color = CtrlColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(from?.email.orEmpty(), style = CtrlType.Meta, color = CtrlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(fullTime(context, h.date), style = CtrlType.Status, color = CtrlColors.Inactive, maxLines = 2, modifier = Modifier.padding(start = 8.dp).width(110.dp))
        }
        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = CtrlColors.AccentText)
        }
    }
}

@Composable
private fun MessageContent(
    body: MessageBody,
    remote: Boolean,
    showDetails: Boolean,
    onToggleDetails: () -> Unit,
    onLoadRemote: () -> Unit,
    onLink: (String) -> Unit,
    onReply: (String) -> Unit,
    onKeys: (List<PGPKeyRing>) -> Unit,
    /** OpenPGP status lines, under the sender. */
    pgp: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val h = body.header
    val files = body.attachments.filterNot { it.isInline && body.inlineImages.containsKey(it.contentId) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            h.subject.ifBlank { stringResource(R.string.no_subject) },
            style = MaterialTheme.typography.headlineSmall,
            color = CtrlColors.Text,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 4.dp, bottom = 14.dp),
        )
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggleDetails).padding(horizontal = Gutter, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val from = h.from
            Avatar(from?.display ?: "?", from?.email ?: "?", size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(from?.display ?: stringResource(R.string.unknown_sender), style = MaterialTheme.typography.titleSmall, color = CtrlColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(from?.email.orEmpty(), style = CtrlType.Meta, color = CtrlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(fullTime(context, h.date), style = CtrlType.Status, color = CtrlColors.Inactive, maxLines = 2, modifier = Modifier.padding(start = 8.dp).width(110.dp))
        }
        if (showDetails) {
            Column(Modifier.padding(horizontal = Gutter, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AddressLine(stringResource(R.string.field_to), h.to)
                if (body.cc.isNotEmpty()) AddressLine(stringResource(R.string.field_cc), body.cc)
                if (body.bcc.isNotEmpty()) AddressLine(stringResource(R.string.field_bcc), body.bcc)
                if (body.replyTo.isNotEmpty()) AddressLine(stringResource(R.string.field_reply_to), body.replyTo)
            }
        } else {
            Text(
                stringResource(R.string.to_line, (h.to + body.cc).joinToString(", ") { it.display }.ifBlank { "—" }),
                style = MaterialTheme.typography.bodySmall,
                color = CtrlColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Gutter),
            )
        }
        Spacer(Modifier.height(12.dp))
        pgp()

        if (body.hasRemoteContent && !remote) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gutter, vertical = 4.dp)
                    .clip(CtrlCardShape)
                    .background(CtrlColors.Surface)
                    .border(1.dp, CtrlColors.Outline, CtrlCardShape)
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Image, null, Modifier.size(20.dp), tint = CtrlColors.Muted)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.remote_blocked), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onLoadRemote) { Text(stringResource(R.string.remote_load)) }
            }
            Spacer(Modifier.height(8.dp))
        }

        val html = body.html
        if (html != null) {
            val page = remember(html, remote, body.inlineImages) { prepareHtml(html, body.inlineImages, remote) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(CtrlCardShape)
                    .border(1.dp, CtrlColors.Outline, CtrlCardShape),
            ) {
                HtmlView(page, remote, onLink, Modifier.fillMaxWidth().heightIn(min = 80.dp))
            }
        } else {
            val text = body.text.orEmpty()
            SelectionContainer {
                Text(
                    linkified(text, CtrlColors.AccentText, onLink),
                    style = MaterialTheme.typography.bodyLarge,
                    color = CtrlColors.Text,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
                )
            }
        }

        if (files.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.vknxworks.mailctrl.ui.components.SectionLabel(context.resources.getQuantityString(R.plurals.attachments_n, files.size, files.size))
                files.forEach { AttachmentRow(h.accountId, h.folder, h.uid, it, onKeys) }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CtrlSecondaryButton(stringResource(R.string.reply), onClick = { onReply(ComposeMode.REPLY) }, icon = Icons.AutoMirrored.Outlined.Reply, modifier = Modifier.weight(1f))
            if ((h.to.size + body.cc.size) > 1) {
                CtrlSecondaryButton(stringResource(R.string.reply_all), onClick = { onReply(ComposeMode.REPLY_ALL) }, icon = Icons.AutoMirrored.Outlined.ReplyAll, modifier = Modifier.weight(1f))
            }
            CtrlSecondaryButton(stringResource(R.string.forward), onClick = { onReply(ComposeMode.FORWARD) }, icon = Icons.AutoMirrored.Outlined.Forward, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AddressLine(label: String, list: List<Address>) {
    Row {
        Text(label.uppercase(), style = CtrlType.Label, color = CtrlColors.Muted, modifier = Modifier.width(72.dp).padding(top = 2.dp))
        SelectionContainer {
            Text(
                list.joinToString("\n") { if (it.name.isBlank()) it.email else "${it.name} <${it.email}>" },
                style = MaterialTheme.typography.bodySmall,
                color = CtrlColors.Text,
            )
        }
    }
}

/** Plain text with web links and addresses you can tap (each one asks first). */
private fun linkified(text: String, color: androidx.compose.ui.graphics.Color, onLink: (String) -> Unit): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    data class Hit(val start: Int, val end: Int, val url: String)
    val hits = mutableListOf<Hit>()
    val web = Patterns.WEB_URL.matcher(text)
    while (web.find()) {
        val raw = text.substring(web.start(), web.end())
        if (!raw.contains('.')) continue
        // Addresses are handled below.
        if (web.start() > 0 && text[web.start() - 1] == '@') continue
        val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        hits += Hit(web.start(), web.end(), url)
    }
    val mail = Patterns.EMAIL_ADDRESS.matcher(text)
    while (mail.find()) {
        if (hits.any { mail.start() < it.end && mail.end() > it.start }) {
            hits.removeAll { mail.start() < it.end && mail.end() > it.start }
        }
        hits += Hit(mail.start(), mail.end(), "mailto:" + text.substring(mail.start(), mail.end()))
    }
    hits.sortBy { it.start }
    return buildAnnotatedString {
        var last = 0
        for (hit in hits) {
            if (hit.start < last) continue
            append(text.substring(last, hit.start))
            withLink(LinkAnnotation.Clickable(hit.url, style) { onLink(hit.url) }) { append(text.substring(hit.start, hit.end)) }
            last = hit.end
        }
        append(text.substring(last))
    }
}

/** An attachment: tap opens it in another app; share or save it with the buttons. */
@Composable
private fun AttachmentRow(accountId: String, folder: String, uid: Long, att: Attachment, onKeys: (List<PGPKeyRing>) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(att.mimeType.ifBlank { "application/octet-stream" })) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) scope.launch { save(context, file, uri) }
    }

    fun withFile(block: (File) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block(c.mail.download(accountId, folder, uid, att))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.friendlyMessage(), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(CtrlCardShape)
            .background(CtrlColors.Surface)
            .border(1.dp, CtrlColors.Outline, CtrlCardShape)
            .clickable { withFile { openFile(context, it, att.mimeType) } }
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CtrlColors.AccentText)
        else Icon(Icons.Outlined.AttachFile, null, Modifier.size(20.dp), tint = CtrlColors.Muted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(att.name, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(att.mimeType, attachmentSize(att.size)).filter { it.isNotBlank() }.joinToString(" · "), style = CtrlType.Meta, color = CtrlColors.Muted, maxLines = 1)
        }
        if (looksLikeKey(att.name, att.mimeType)) {
            IconButton(onClick = {
                withFile { f ->
                    scope.launch {
                        try {
                            val bytes = withContext(Dispatchers.IO) {
                                if (f.length() > PgpEngine.MAX_KEY_BYTES) throw PgpFailure(PgpFailure.Kind.TOO_BIG)
                                f.readBytes()
                            }
                            onKeys(c.pgp.preview(bytes))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: PgpFailure) {
                            Toast.makeText(context, context.getString(R.string.pgp_no_key_in_file), Toast.LENGTH_LONG).show()
                        } finally {
                            withContext(Dispatchers.IO) { runCatching { f.parentFile?.deleteRecursively() } }
                        }
                    }
                }
            }) {
                Icon(Icons.Outlined.Key, stringResource(R.string.pgp_import_key), tint = CtrlColors.AccentText)
            }
        }
        IconButton(onClick = { withFile { shareFile(context, it, att.mimeType) } }) {
            Icon(Icons.Outlined.Share, stringResource(R.string.share), tint = CtrlColors.Muted)
        }
        IconButton(onClick = {
            withFile { f ->
                pendingSave = f
                AppLock.expectReturn()
                saver.launch(att.name)
            }
        }) {
            Icon(Icons.Outlined.SaveAlt, stringResource(R.string.save), tint = CtrlColors.Muted)
        }
    }
}

private suspend fun save(context: Context, file: File, uri: Uri) {
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
        }.isSuccess
    }
    Toast.makeText(context, context.getString(if (ok) R.string.saved else R.string.save_failed), Toast.LENGTH_SHORT).show()
}
