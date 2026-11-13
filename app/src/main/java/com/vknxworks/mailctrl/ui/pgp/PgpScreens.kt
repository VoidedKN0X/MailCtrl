package com.vknxworks.mailctrl.ui.pgp

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.pgp.friendly
import com.vknxworks.mailctrl.pgp.KeyInfo
import com.vknxworks.mailctrl.pgp.KeyType
import com.vknxworks.mailctrl.pgp.PgpEngine
import com.vknxworks.mailctrl.pgp.PgpFailure
import com.vknxworks.mailctrl.pgp.UnlockedKey
import com.vknxworks.mailctrl.pgp.Wkd
import com.vknxworks.mailctrl.pgp.WrongPassphraseException
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.CtrlAlertDialog
import com.vknxworks.mailctrl.ui.components.CtrlButton
import com.vknxworks.mailctrl.ui.components.CtrlFieldShape
import com.vknxworks.mailctrl.ui.components.CtrlSubTopBar
import com.vknxworks.mailctrl.ui.components.Group
import com.vknxworks.mailctrl.ui.components.GroupChoice
import com.vknxworks.mailctrl.ui.components.GroupRow
import com.vknxworks.mailctrl.ui.components.GroupSwitch
import com.vknxworks.mailctrl.ui.components.GuardedScreen
import com.vknxworks.mailctrl.ui.components.InfoDialog
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.components.SecureScreen
import com.vknxworks.mailctrl.ui.components.TextInputDialog
import com.vknxworks.mailctrl.ui.components.ctrlFieldColors
import com.vknxworks.mailctrl.ui.components.friendlyMessage
import com.vknxworks.mailctrl.ui.components.runTask
import com.vknxworks.mailctrl.ui.components.shareFile
import com.vknxworks.mailctrl.ui.lock.AppLock
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.nav.PgpGenerateRoute
import com.vknxworks.mailctrl.ui.nav.PgpKeyRoute
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.openpgp.PGPKeyRing
import java.io.File
import java.util.UUID

// ------------------------------------------------------------------ small helpers

private fun date(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)

/** The last 16 digits of a fingerprint in groups of four: enough to tell keys apart at a glance. */
fun shortFingerprint(fingerprint: String): String = PgpEngine.pretty(fingerprint.takeLast(16))

/** One line about a key: its short fingerprint and whether it still works. */
@Composable
fun keySummary(info: KeyInfo): String {
    val context = LocalContext.current
    val state = when {
        info.revoked -> stringResource(R.string.pgp_state_revoked)
        info.expired() -> stringResource(R.string.pgp_state_expired, date(context, info.expires!!))
        info.expires != null -> stringResource(R.string.pgp_state_expires, date(context, info.expires))
        !info.canEncrypt && !info.canSign -> stringResource(R.string.pgp_state_unusable)
        else -> stringResource(R.string.pgp_state_no_expiry)
    }
    return shortFingerprint(info.fingerprint) + "  ·  " + state
}

private fun keyUsable(info: KeyInfo) = !info.revoked && !info.expired() && (info.canEncrypt || info.canSign)

private suspend fun readUri(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > PgpEngine.MAX_KEY_BYTES) throw PgpFailure(PgpFailure.Kind.TOO_BIG)
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: throw PgpFailure(PgpFailure.Kind.NOT_PGP)
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

@Composable
private fun KeyRow(info: KeyInfo, onClick: () -> Unit) {
    GroupRow(
        info.primaryUserId.ifBlank { PgpEngine.pretty(info.fingerprint) },
        keySummary(info),
        icon = if (info.secret) Icons.Outlined.VpnKey else Icons.Outlined.Key,
        iconTint = if (keyUsable(info)) CtrlColors.AccentText else CtrlColors.Error,
        showChevron = true,
        onClick = onClick,
    )
}

// ------------------------------------------------------------------ keys list

/** Your keys and your contacts' keys: make, import, look up and open them. */
@Composable
fun PgpKeysScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val keys by c.pgpKeys.keys.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.pgpKeys.load() }
    var importing by remember { mutableStateOf<List<PGPKeyRing>?>(null) }
    var askEmail by remember { mutableStateOf(false) }
    var wkdEmail by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                importing = c.pgp.preview(readUri(context, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PgpFailure) {
                toast(context, e.friendly())
            } catch (e: Exception) {
                toast(context, context.getString(R.string.pgp_file_unreadable))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.pgp_keys))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            val own = keys.filter { it.info.secret }
            val theirs = keys.filterNot { it.info.secret }
            Group(stringResource(R.string.pgp_your_keys), footer = stringResource(R.string.pgp_your_keys_footer)) {
                own.forEach { k -> KeyRow(k.info) { nav.navigate(PgpKeyRoute(k.info.fingerprint)) } }
                GroupRow(stringResource(R.string.pgp_generate), icon = Icons.Outlined.Add, showChevron = true, onClick = { nav.navigate(PgpGenerateRoute()) })
                GroupRow(
                    stringResource(R.string.pgp_import_file),
                    stringResource(R.string.pgp_import_file_subtitle),
                    icon = Icons.Outlined.FileOpen,
                    onClick = {
                        AppLock.expectReturn()
                        picker.launch(arrayOf("*/*"))
                    },
                )
            }
            Group(stringResource(R.string.pgp_contact_keys), footer = stringResource(R.string.pgp_contact_keys_footer)) {
                if (theirs.isEmpty()) {
                    GroupRow(stringResource(R.string.pgp_no_contact_keys), icon = Icons.Outlined.Key, iconTint = CtrlColors.Muted)
                }
                theirs.forEach { k -> KeyRow(k.info) { nav.navigate(PgpKeyRoute(k.info.fingerprint)) } }
                if (settings.wkdLookup) {
                    GroupRow(
                        stringResource(R.string.pgp_wkd_find),
                        stringResource(R.string.pgp_wkd_find_subtitle),
                        icon = Icons.Outlined.TravelExplore,
                        onClick = { askEmail = true },
                    )
                }
            }
        }
    }

    importing?.let { rings -> KeyImportDialog(rings, onDismiss = { importing = null }) { importing = null } }
    if (askEmail) {
        TextInputDialog(
            title = stringResource(R.string.pgp_wkd_find),
            label = stringResource(R.string.setup_email),
            confirm = stringResource(R.string.pgp_wkd_look_up),
            onDismiss = { askEmail = false },
        ) { email ->
            askEmail = false
            if (android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) wkdEmail = email
            else toast(context, context.getString(R.string.setup_bad_email))
        }
    }
    wkdEmail?.let { email -> WkdLookup(email, onDismiss = { wkdEmail = null }) }
}

// ------------------------------------------------------------------ one key

@Composable
fun PgpKeyScreen(fingerprint: String) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val keys by c.pgpKeys.keys.collectAsStateWithLifecycle()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.pgpKeys.load() }
    val key = keys.firstOrNull { it.info.fingerprint == fingerprint }
    var deleting by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pgp-keys")) { uri ->
        val text = pendingSave
        pendingSave = null
        if (uri != null && text != null) {
            c.runTask {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream") }.isSuccess
                }
                toast(context, context.getString(if (ok) R.string.saved else R.string.save_failed))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(if (key?.info?.secret == true) R.string.pgp_your_key else R.string.pgp_contact_key))
        if (key == null) return@Column
        val info = key.info
        val fileName = remember(info) {
            ((PgpEngine.emailOf(info.primaryUserId) ?: "key") + "-" + info.fingerprint.takeLast(8))
                .replace(Regex("[^A-Za-z0-9@._-]"), "_") + ".asc"
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                info.userIds.ifEmpty { listOf("—") }.forEach { Text(it, style = MaterialTheme.typography.titleMedium, color = CtrlColors.Text) }
            }
            Group(stringResource(R.string.pgp_fingerprint), footer = stringResource(R.string.pgp_fingerprint_footer)) {
                SelectionContainer {
                    Text(
                        PgpEngine.pretty(info.fingerprint),
                        style = CtrlType.Meta.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                        color = CtrlColors.Text,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
            Group(stringResource(R.string.pgp_details)) {
                GroupRow(stringResource(R.string.pgp_created), date(context, info.created))
                GroupRow(
                    stringResource(R.string.pgp_expires),
                    when {
                        info.revoked -> stringResource(R.string.pgp_state_revoked)
                        info.expires == null -> stringResource(R.string.pgp_never)
                        info.expired() -> stringResource(R.string.pgp_state_expired, date(context, info.expires))
                        else -> date(context, info.expires)
                    },
                )
                GroupRow(stringResource(R.string.pgp_algorithm), info.algorithm)
                GroupRow(
                    stringResource(R.string.pgp_usable_for),
                    listOfNotNull(
                        stringResource(R.string.pgp_use_encrypt).takeIf { info.canEncrypt },
                        stringResource(R.string.pgp_use_sign).takeIf { info.canSign },
                    ).joinToString(", ").ifEmpty { stringResource(R.string.pgp_state_unusable) },
                )
                if (info.secret) {
                    GroupRow(
                        stringResource(R.string.pgp_protection),
                        stringResource(if (info.protected) R.string.pgp_protected else R.string.pgp_unprotected),
                    )
                }
            }
            if (info.secret && accounts.isNotEmpty()) {
                Group(stringResource(R.string.pgp_used_by), footer = stringResource(R.string.pgp_used_by_footer)) {
                    accounts.forEach { a ->
                        GroupSwitch(a.title, a.email.takeIf { it != a.title }, a.pgpKey == fingerprint) { on ->
                            c.runTask { c.accounts.update(a.id) { it.copy(pgpKey = if (on) fingerprint else null) } }
                        }
                    }
                }
            }
            Group {
                GroupRow(stringResource(R.string.pgp_share_public), icon = Icons.Outlined.Share, onClick = {
                    val text = c.pgp.exportPublic(fingerprint) ?: return@GroupRow
                    c.runTask {
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(context.cacheDir, "attachments/${UUID.randomUUID()}").apply { mkdirs() }
                            File(dir, fileName).apply { writeText(text) }
                        }
                        shareFile(context, file, "application/pgp-keys")
                    }
                })
                GroupRow(stringResource(R.string.pgp_save_public), icon = Icons.Outlined.SaveAlt, onClick = {
                    pendingSave = c.pgp.exportPublic(fingerprint) ?: return@GroupRow
                    AppLock.expectReturn()
                    saver.launch(fileName)
                })
                GroupRow(stringResource(R.string.pgp_delete), icon = Icons.Outlined.Delete, iconTint = CtrlColors.Error, titleColor = CtrlColors.Error, onClick = { deleting = true })
            }
        }
    }

    if (deleting && key != null) {
        ConfirmDialog(
            stringResource(R.string.pgp_delete_title),
            stringResource(if (key.info.secret) R.string.pgp_delete_secret_body else R.string.pgp_delete_body),
            stringResource(R.string.delete),
            onDismiss = { deleting = false },
        ) {
            deleting = false
            nav.back()
            c.runTask {
                c.pgp.delete(fingerprint)
                accounts.filter { it.pgpKey == fingerprint }.forEach { a -> c.accounts.update(a.id) { it.copy(pgpKey = null) } }
            }
        }
    }
}

// ------------------------------------------------------------------ making a key

private val validities = listOf(0, 1, 2, 5)

@Composable
fun PgpGenerateScreen(accountId: String?) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    SecureScreen()
    var chosen by rememberSaveable { mutableStateOf(accountId ?: accounts.firstOrNull()?.id) }
    val account = accounts.firstOrNull { it.id == chosen } ?: accounts.firstOrNull()
    var name by rememberSaveable(account?.id) { mutableStateOf(account?.senderName.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(KeyType.CURVE25519) }
    var years by rememberSaveable { mutableIntStateOf(0) }
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun make() {
        val a = account ?: return
        error = when {
            pass.length < PASSPHRASE_MIN -> context.getString(R.string.pgp_passphrase_short, PASSPHRASE_MIN)
            pass != repeat -> context.getString(R.string.pgp_passphrase_mismatch)
            else -> null
        }
        if (error != null) return
        busy = true
        val chars = pass.toCharArray()
        pass = ""
        repeat = ""
        // Kept going if the screen closes meanwhile: an RSA key can take a while.
        c.appScope.launch(Dispatchers.Main) {
            try {
                val info = c.pgp.generate(name, a.email, chars, type, years * 365L * 24 * 3600)
                if (c.accounts.get(a.id)?.pgpKey == null) c.accounts.update(a.id) { it.copy(pgpKey = info.fingerprint) }
                toast(context, context.getString(R.string.pgp_generated))
                nav.close(PgpGenerateRoute::class)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendlyMessage()
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        CtrlSubTopBar(stringResource(R.string.pgp_generate), onBack = { nav.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.pgp_generate_intro), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted)
            Group(padded = false) {
                if (accounts.size > 1) {
                    GroupChoice(stringResource(R.string.pgp_for_account), accounts.map { it.id to it.email }, account?.id) { chosen = it }
                } else if (account != null) {
                    GroupRow(stringResource(R.string.pgp_for_account), account.email)
                }
                GroupChoice(
                    stringResource(R.string.pgp_key_type),
                    listOf(KeyType.CURVE25519 to stringResource(R.string.pgp_type_25519), KeyType.RSA_4096 to stringResource(R.string.pgp_type_rsa)),
                    type,
                ) { type = it }
                GroupChoice(
                    stringResource(R.string.pgp_valid_for),
                    validities.map { y -> y to if (y == 0) stringResource(R.string.pgp_never) else pluralStringResource(R.plurals.pgp_years, y, y) },
                    years,
                ) { years = it }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.pgp_name)) },
                singleLine = true,
                shape = CtrlFieldShape,
                colors = ctrlFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            PassphraseField(pass, { pass = it; error = null }, stringResource(R.string.pgp_passphrase), show) { show = !show }
            PassphraseField(repeat, { repeat = it; error = null }, stringResource(R.string.pgp_passphrase_repeat), show) { show = !show }
            Text(stringResource(R.string.pgp_passphrase_note), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CtrlColors.Error) }
            CtrlButton(
                stringResource(R.string.pgp_generate_button),
                onClick = ::make,
                enabled = !busy && account != null,
                icon = Icons.Outlined.VpnKey,
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CtrlColors.AccentText)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(if (type == KeyType.RSA_4096) R.string.pgp_generating_rsa else R.string.pgp_generating),
                        style = MaterialTheme.typography.bodySmall,
                        color = CtrlColors.Muted,
                    )
                }
            }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}

private const val PASSPHRASE_MIN = 8

@Composable
private fun PassphraseField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    show: Boolean,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onDone: (() -> Unit)? = null,
    onToggle: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(if (show) R.string.pgp_hide_passphrase else R.string.pgp_show_passphrase),
                )
            }
        },
        shape = CtrlFieldShape,
        colors = ctrlFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

// ------------------------------------------------------------------ dialogs

/** Asks for a key's passphrase. Kept out of screenshots, and nothing can be drawn over it. */
@Composable
fun PassphraseDialog(info: KeyInfo, onDismiss: () -> Unit, onUnlocked: (UnlockedKey) -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    SecureScreen()
    GuardedScreen(sensitive = true)
    var pass by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (pass.isEmpty() || busy) return
        busy = true
        val chars = pass.toCharArray()
        scope.launch {
            try {
                val key = c.pgp.unlock(info.fingerprint, chars)
                pass = ""
                onUnlocked(key)
            } catch (e: WrongPassphraseException) {
                wrong = true
                busy = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wrong = true
                busy = false
            }
        }
    }

    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pgp_unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info.primaryUserId, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text)
                Text(shortFingerprint(info.fingerprint), style = CtrlType.Meta, color = CtrlColors.Muted)
                PassphraseField(
                    pass,
                    { pass = it; wrong = false },
                    stringResource(R.string.pgp_passphrase),
                    show,
                    modifier = Modifier.focusRequester(focus),
                    isError = wrong,
                    onDone = ::submit,
                ) { show = !show }
                if (wrong) Text(stringResource(R.string.pgp_wrong_passphrase), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Error)
                Text(
                    stringResource(R.string.pgp_unlock_remembered, stringResource(settings.pgpPassphrase.label)),
                    style = MaterialTheme.typography.bodySmall,
                    color = CtrlColors.Muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = pass.isNotEmpty() && !busy) { Text(stringResource(R.string.pgp_unlock)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Shows the keys about to be imported (fingerprints to compare) and imports them when you say so. */
@Composable
fun KeyImportDialog(rings: List<PGPKeyRing>, onDismiss: () -> Unit, replaces: Boolean = false, onDone: (List<KeyInfo>) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val infos = remember(rings) { rings.map { PgpEngine.info(it) } }
    GuardedScreen()
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.pgp_import_title, infos.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                infos.forEach { info ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        info.userIds.ifEmpty { listOf("—") }.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text) }
                        Text(PgpEngine.pretty(info.fingerprint), style = CtrlType.Meta, color = CtrlColors.Text)
                        Text(
                            listOf(
                                stringResource(if (info.secret) R.string.pgp_kind_secret else R.string.pgp_kind_public),
                                info.algorithm,
                                keySummary(info).substringAfter("·").trim(),
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (keyUsable(info)) CtrlColors.Muted else CtrlColors.Error,
                        )
                    }
                }
                if (replaces) Text(stringResource(R.string.pgp_import_replaces), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Error)
                Text(stringResource(R.string.pgp_import_check), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                c.runTask {
                    val done = c.pgp.import(rings)
                    toast(context, context.resources.getQuantityString(R.plurals.pgp_imported, done.size, done.size))
                    onDone(done)
                }
            }) { Text(stringResource(R.string.pgp_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private sealed interface WkdPhase {
    data object Ask : WkdPhase
    data object Searching : WkdPhase
    class Found(val rings: List<PGPKeyRing>) : WkdPhase
    data object Nothing : WkdPhase
    class Failed(val message: String) : WkdPhase
}

/**
 * Looks [email]'s key up on its own domain (Web Key Directory). Says which domain it contacts and asks first;
 * then shows what it found to import.
 */
@Composable
fun WkdLookup(email: String, onDismiss: () -> Unit, onImported: (List<KeyInfo>) -> Unit = {}) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<WkdPhase>(WkdPhase.Ask) }
    var job by remember { mutableStateOf<Job?>(null) }
    val domain = remember(email) { Wkd.domainOf(email) }
    when (val p = phase) {
        WkdPhase.Ask -> CtrlAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pgp_wkd_title)) },
            text = { Text(stringResource(R.string.pgp_wkd_body, email, domain)) },
            confirmButton = {
                TextButton(onClick = {
                    phase = WkdPhase.Searching
                    job = scope.launch {
                        phase = try {
                            val found = c.pgp.lookup(email)
                            if (found.isEmpty()) WkdPhase.Nothing else WkdPhase.Found(found)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: PgpFailure) {
                            WkdPhase.Failed(e.friendly())
                        } catch (e: Exception) {
                            WkdPhase.Failed(com.vknxworks.mailctrl.str(R.string.pgp_wkd_failed, domain))
                        }
                    }
                }) { Text(stringResource(R.string.pgp_wkd_look_up)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        WkdPhase.Searching -> CtrlAlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pgp_wkd_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CtrlColors.AccentText)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.pgp_wkd_searching, domain))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    job?.cancel()
                    onDismiss()
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
        is WkdPhase.Found -> KeyImportDialog(p.rings, onDismiss = onDismiss, replaces = false, onDone = onImported)
        WkdPhase.Nothing -> InfoDialog(stringResource(R.string.pgp_wkd_title), stringResource(R.string.pgp_wkd_none, email), onDismiss)
        is WkdPhase.Failed -> InfoDialog(stringResource(R.string.pgp_wkd_title), p.message, onDismiss)
    }
}
