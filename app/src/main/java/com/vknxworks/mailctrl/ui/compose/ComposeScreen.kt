package com.vknxworks.mailctrl.ui.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.drafts.Draft
import com.vknxworks.mailctrl.mail.Address
import com.vknxworks.mailctrl.mail.Attachment
import com.vknxworks.mailctrl.mail.MessageBody
import com.vknxworks.mailctrl.mail.OutgoingAttachment
import com.vknxworks.mailctrl.str
import com.vknxworks.mailctrl.ui.components.ChoiceSheet
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.CtrlCardShape
import com.vknxworks.mailctrl.ui.components.CtrlFieldShape
import com.vknxworks.mailctrl.ui.components.CtrlSquareButton
import com.vknxworks.mailctrl.ui.components.LoadingView
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.components.attachmentSize
import com.vknxworks.mailctrl.ui.components.ctrlFieldColors
import com.vknxworks.mailctrl.ui.components.fileSize
import com.vknxworks.mailctrl.ui.components.friendlyMessage
import com.vknxworks.mailctrl.ui.components.fullTime
import com.vknxworks.mailctrl.ui.lock.AppLock
import com.vknxworks.mailctrl.ui.nav.ComposeMode
import com.vknxworks.mailctrl.ui.nav.ComposeRoute
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.mail.internet.InternetAddress

/** A file picked on the phone, added to the message. */
private data class LocalFile(val uri: Uri, val name: String, val size: Long, val mimeType: String)

/** Everything Compose starts with. */
private data class Start(
    val accountId: String,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String? = null,
    val references: String? = null,
    val replyFolder: String? = null,
    val replyUid: Long? = null,
    val files: List<Uri> = emptyList(),
    /** Attachments of the message being forwarded, fetched from the server when sending. */
    val forwarded: List<Attachment> = emptyList(),
    val forwardFolder: String? = null,
    val forwardUid: Long? = null,
)

@Composable
fun ComposeScreen(route: ComposeRoute) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val draftId = rememberSaveable { route.draftId ?: UUID.randomUUID().toString() }

    var start by remember { mutableStateOf<Start?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        val fallback = route.accountId?.takeIf { id -> accounts.any { it.id == id } }
            ?: settings.defaultAccount.takeIf { id -> accounts.any { it.id == id } }
            ?: accounts.firstOrNull()?.id
            ?: return@LaunchedEffect
        start = try {
            buildStart(context, route, fallback)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.friendlyMessage()
            Start(fallback)
        }
    }

    val s = start
    if (s == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(stringResource(R.string.write))
            LoadingView()
        }
        return
    }
    ComposeForm(draftId, s, problem)
}

private suspend fun buildStart(context: Context, route: ComposeRoute, fallbackAccount: String): Start {
    val c = context.container
    val account = c.accounts.get(route.accountId ?: fallbackAccount) ?: c.accounts.get(fallbackAccount)!!
    val signature = account.signature.takeIf { it.isNotBlank() }?.let { "\n\n-- \n$it" }.orEmpty()
    when (route.mode) {
        ComposeMode.DRAFT -> {
            val d = route.draftId?.let { c.drafts.get(it) } ?: return Start(account.id, body = signature)
            return Start(
                accountId = d.accountId,
                to = d.to.joinToString(", ") { it.formatted() },
                cc = d.cc.joinToString(", ") { it.formatted() },
                bcc = d.bcc.joinToString(", ") { it.formatted() },
                subject = d.subject,
                body = d.body,
                inReplyTo = d.inReplyTo,
                references = d.references,
                replyFolder = d.replyFolder,
                replyUid = d.replyUid,
            )
        }
        ComposeMode.REPLY, ComposeMode.REPLY_ALL, ComposeMode.FORWARD -> {
            val folder = route.folder ?: return Start(account.id, body = signature)
            val original = c.lastOpened?.takeIf { it.header.accountId == account.id && it.header.folder == folder && it.header.uid == route.uid }
                ?: c.mail.open(account.id, folder, route.uid, markRead = false)
            val h = original.header
            val quotedText = plainText(original)
            if (route.mode == ComposeMode.FORWARD) {
                val head = buildString {
                    append("\n\n---------- ").append(str(R.string.forwarded_message)).append(" ----------\n")
                    append(str(R.string.field_from)).append(": ").append(h.from?.formatted().orEmpty()).append('\n')
                    append(str(R.string.field_date)).append(": ").append(fullTime(context, h.date)).append('\n')
                    append(str(R.string.field_subject)).append(": ").append(h.subject).append('\n')
                    append(str(R.string.field_to)).append(": ").append(h.to.joinToString(", ") { it.formatted() }).append('\n')
                    if (original.cc.isNotEmpty()) append(str(R.string.field_cc)).append(": ").append(original.cc.joinToString(", ") { it.formatted() }).append('\n')
                    append('\n')
                }
                return Start(
                    accountId = account.id,
                    subject = prefixed(h.subject, "Fwd: ", listOf("fwd:", "fw:")),
                    body = signature + head + quotedText,
                    forwarded = original.attachments.filterNot { it.isInline && original.inlineImages.containsKey(it.contentId) },
                    forwardFolder = folder,
                    forwardUid = route.uid,
                )
            }
            val me = account.email.lowercase()
            val replyTo = original.replyTo.ifEmpty { listOfNotNull(h.from) }
            // Answering your own sent message goes to its recipients again.
            val to = if (h.from?.email.equals(account.email, ignoreCase = true)) h.to else replyTo
            val cc = if (route.mode == ComposeMode.REPLY_ALL) {
                (h.to + original.cc).filter { a -> a.email.lowercase() != me && to.none { it.email.equals(a.email, ignoreCase = true) } }
                    .distinctBy { it.email.lowercase() }
            } else {
                emptyList()
            }
            val intro = str(R.string.reply_intro, fullTime(context, h.date), h.from?.display ?: str(R.string.unknown_sender))
            val quoted = quotedText.trimEnd().lines().joinToString("\n") { if (it.startsWith(">")) ">$it" else "> $it" }
            return Start(
                accountId = account.id,
                to = to.joinToString(", ") { it.formatted() },
                cc = cc.joinToString(", ") { it.formatted() },
                subject = prefixed(h.subject, "Re: ", listOf("re:")),
                body = signature + "\n\n" + intro + "\n" + quoted,
                inReplyTo = original.messageId,
                references = listOfNotNull(original.references, original.messageId).joinToString(" ").trim().ifBlank { null },
                replyFolder = folder,
                replyUid = route.uid,
            )
        }
        else -> {
            val request = if (route.fromRequest) c.composeRequest.value.also { c.composeRequest.value = null } else null
            return Start(
                accountId = account.id,
                to = (request?.to ?: listOfNotNull(route.to)).joinToString(", "),
                cc = request?.cc.orEmpty().joinToString(", "),
                bcc = request?.bcc.orEmpty().joinToString(", "),
                subject = request?.subject.orEmpty(),
                body = request?.body.orEmpty() + signature,
                files = request?.attachments.orEmpty(),
            )
        }
    }
}

private fun prefixed(subject: String, prefix: String, existing: List<String>): String =
    if (existing.any { subject.trim().startsWith(it, ignoreCase = true) }) subject.trim() else prefix + subject.trim()

private fun Address.formatted(): String = if (name.isBlank()) email else "${name.replace("\"", "")} <$email>"

/** The message as plain text, for quoting: its text version, or the HTML version without the markup. */
private fun plainText(body: MessageBody): String {
    body.text?.takeIf { it.isNotBlank() }?.let { return it }
    val html = body.html ?: return ""
    val stripped = html
        .replace(Regex("(?is)<(head|style|script|title)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?is)<!--.*?-->"), "")
    return HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        .replace('￼', ' ')
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** Reads "Name <a@b>, c@d" into addresses; null when one of them isn't an address. */
private fun parseAddresses(text: String): List<Address>? {
    if (text.isBlank()) return emptyList()
    return runCatching {
        InternetAddress.parse(text.replace(';', ','), false).map { a ->
            val email = a.address?.trim().orEmpty()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) return null
            Address(a.personal?.trim().orEmpty(), email)
        }
    }.getOrNull()
}

@Composable
private fun ComposeForm(draftId: String, start: Start, loadProblem: String?) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    var accountId by rememberSaveable { mutableStateOf(start.accountId) }
    var to by rememberSaveable { mutableStateOf(start.to) }
    var cc by rememberSaveable { mutableStateOf(start.cc) }
    var bcc by rememberSaveable { mutableStateOf(start.bcc) }
    var showCc by rememberSaveable { mutableStateOf(start.cc.isNotBlank() || start.bcc.isNotBlank()) }
    var subject by rememberSaveable { mutableStateOf(start.subject) }
    var body by rememberSaveable { mutableStateOf(start.body) }
    val files = remember { mutableStateListOf<LocalFile>() }
    val forwarded = remember { mutableStateListOf<Attachment>().apply { addAll(start.forwarded) } }
    var picking by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    // Sent or thrown away: nothing to keep as a draft when the screen closes.
    var finished by remember { mutableStateOf(false) }
    val account = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()

    LaunchedEffect(Unit) {
        loadProblem?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        if (start.files.isNotEmpty()) {
            val found = withContext(Dispatchers.IO) { start.files.mapNotNull { describe(context, it) } }
            files.addAll(found)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        c.appScope.launchDescribe(context, uris) { found -> files.addAll(found.filter { f -> files.none { it.uri == f.uri } }) }
    }

    fun draft(): Draft = Draft(
        id = draftId,
        accountId = account?.id ?: accountId,
        to = parseAddresses(to) ?: emptyList(),
        cc = parseAddresses(cc) ?: emptyList(),
        bcc = parseAddresses(bcc) ?: emptyList(),
        subject = subject,
        body = body,
        inReplyTo = start.inReplyTo,
        references = start.references,
        replyFolder = start.replyFolder,
        replyUid = start.replyUid,
    )

    // Unsent text is kept as a draft: when you leave, and before the app lock closes the screen.
    val keep by rememberUpdatedState { now: Boolean ->
        // Only once you've written something (not just opened a reply with its quote and signature).
        val changed = to != start.to || cc != start.cc || bcc != start.bcc || subject != start.subject || body != start.body
        if (!finished && changed) {
            val d = draft()
            if (now) {
                // Locking wipes the key right after this: saved straight away, while it can still be sealed.
                runCatching { runBlocking { c.drafts.save(d) } }
            } else {
                c.appScope.launchSave(c, d)
            }
        }
    }
    DisposableEffect(Unit) {
        val beforeLock: () -> Unit = { keep(true) }
        AppLock.saveBeforeLock = beforeLock
        onDispose {
            // Another Compose screen may have taken over already.
            if (AppLock.saveBeforeLock === beforeLock) AppLock.saveBeforeLock = null
            keep(false)
        }
    }

    fun send() {
        val a = account ?: return
        val toList = parseAddresses(to)
        val ccList = parseAddresses(cc)
        val bccList = parseAddresses(bcc)
        if (toList == null || ccList == null || bccList == null) {
            Toast.makeText(context, context.getString(R.string.bad_address), Toast.LENGTH_LONG).show()
            return
        }
        if (toList.isEmpty() && ccList.isEmpty() && bccList.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.no_recipient), Toast.LENGTH_LONG).show()
            return
        }
        val d = draft().copy(accountId = a.id, to = toList, cc = ccList, bcc = bccList)
        val local = files.toList()
        val fwd = forwarded.toList()
        val fwdFolder = start.forwardFolder
        val fwdUid = start.forwardUid
        finished = true
        Toast.makeText(context, context.getString(R.string.sending), Toast.LENGTH_SHORT).show()
        c.send(d, attachments = {
            val out = mutableListOf<OutgoingAttachment>()
            var total = 0L
            for (f in local) {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException(str(R.string.attachment_unreadable, f.name))
                total += bytes.size
                if (total > MAX_TOTAL) throw IllegalStateException(str(R.string.attachments_too_big))
                out += OutgoingAttachment(f.name, f.mimeType, bytes)
            }
            if (fwdFolder != null && fwdUid != null) {
                for (att in fwd) {
                    val bytes = c.mail.attachmentBytes(d.accountId, fwdFolder, fwdUid, att)
                    total += bytes.size
                    if (total > MAX_TOTAL) throw IllegalStateException(str(R.string.attachments_too_big))
                    out += OutgoingAttachment(att.name, att.mimeType, bytes)
                }
            }
            out
        })
        nav.back()
    }

    BackHandler { nav.back() }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.write)) {
            CtrlSquareButton(Icons.Outlined.AttachFile, stringResource(R.string.attach), onClick = {
                AppLock.expectReturn()
                picker.launch(arrayOf("*/*"))
            })
            Spacer(Modifier.width(8.dp))
            CtrlSquareButton(Icons.Outlined.Delete, stringResource(R.string.discard), onClick = { discarding = true })
            Spacer(Modifier.width(8.dp))
            CtrlSquareButton(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send), onClick = ::send)
            Spacer(Modifier.width(4.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (accounts.size > 1 && account != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(CtrlFieldShape)
                        .background(CtrlColors.Surface)
                        .border(1.dp, CtrlColors.ControlOutline, CtrlFieldShape)
                        .clickable { picking = true }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.field_from).uppercase(), style = CtrlType.Label, color = CtrlColors.Muted, modifier = Modifier.width(56.dp))
                    Text(account.email, style = MaterialTheme.typography.bodyLarge, color = CtrlColors.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, tint = CtrlColors.Muted)
                }
            }
            AddressField(stringResource(R.string.field_to), to, { to = it }) {
                if (!showCc) IconButton(onClick = { showCc = true }) { Text(stringResource(R.string.cc_bcc), style = CtrlType.Status, color = CtrlColors.AccentText) }
            }
            if (showCc) {
                AddressField(stringResource(R.string.field_cc), cc, { cc = it })
                AddressField(stringResource(R.string.field_bcc), bcc, { bcc = it })
            }
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text(stringResource(R.string.field_subject)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = CtrlFieldShape,
                colors = ctrlFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (files.isNotEmpty() || forwarded.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    files.forEach { f -> FileChip(f.name, fileSize(f.size)) { files.remove(f) } }
                    forwarded.forEach { a -> FileChip(a.name, attachmentSize(a.size)) { forwarded.remove(a) } }
                }
            }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text(stringResource(R.string.body_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = CtrlFieldShape,
                colors = ctrlFieldColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
            )
            if (account?.unencrypted == true) {
                Text(stringResource(R.string.compose_unencrypted), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
            }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }

    if (picking) {
        ChoiceSheet(
            stringResource(R.string.field_from),
            accounts.map { it.id to it.email },
            accountId,
            onDismiss = { picking = false },
        ) { id ->
            picking = false
            accountId = id
        }
    }

    if (discarding) {
        ConfirmDialog(
            stringResource(R.string.discard_title),
            stringResource(R.string.discard_body),
            stringResource(R.string.discard),
            onDismiss = { discarding = false },
        ) {
            discarding = false
            finished = true
            c.appScope.launchDelete(c, draftId)
            nav.back()
        }
    }
}

@Composable
private fun AddressField(label: String, value: String, onChange: (String) -> Unit, trailing: (@Composable () -> Unit)? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = false,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        trailingIcon = trailing,
        shape = CtrlFieldShape,
        colors = ctrlFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FileChip(name: String, size: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CtrlCardShape)
            .background(CtrlColors.Surface)
            .border(1.dp, CtrlColors.Outline, CtrlCardShape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp), tint = CtrlColors.Muted)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (size.isNotBlank()) Text(size, style = CtrlType.Meta, color = CtrlColors.Muted)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, stringResource(R.string.remove), tint = CtrlColors.Muted) }
    }
}

/** Name, size and type of a picked file. */
private fun describe(context: Context, uri: Uri): LocalFile? = runCatching {
    var name = uri.lastPathSegment ?: "attachment"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) {
            cur.getString(0)?.let { name = it }
            if (!cur.isNull(1)) size = cur.getLong(1)
        }
    }
    LocalFile(uri, name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
}.getOrNull()

private fun kotlinx.coroutines.CoroutineScope.launchDescribe(context: Context, uris: List<Uri>, onFound: (List<LocalFile>) -> Unit) {
    launch(Dispatchers.IO) {
        val found = uris.mapNotNull { describe(context, it) }
        withContext(Dispatchers.Main) { onFound(found) }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchSave(c: com.vknxworks.mailctrl.AppContainer, d: Draft) {
    launch { runCatching { c.drafts.save(d) } }
}

private fun kotlinx.coroutines.CoroutineScope.launchDelete(c: com.vknxworks.mailctrl.AppContainer, id: String) {
    launch { runCatching { c.drafts.delete(id) } }
}

private const val MAX_TOTAL = 25L * 1024 * 1024
