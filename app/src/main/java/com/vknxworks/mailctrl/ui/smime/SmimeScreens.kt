package com.vknxworks.mailctrl.ui.smime

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ContactMail
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.smime.friendly
import com.vknxworks.mailctrl.pgp.PgpEngine
import com.vknxworks.mailctrl.smime.CertInfo
import com.vknxworks.mailctrl.smime.Pkcs12
import com.vknxworks.mailctrl.smime.SmimeEngine
import com.vknxworks.mailctrl.smime.SmimeFailure
import com.vknxworks.mailctrl.smime.SmimeIdentity
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.CtrlAlertDialog
import com.vknxworks.mailctrl.ui.components.CtrlFieldShape
import com.vknxworks.mailctrl.ui.components.Group
import com.vknxworks.mailctrl.ui.components.GroupRow
import com.vknxworks.mailctrl.ui.components.GroupSwitch
import com.vknxworks.mailctrl.ui.components.GuardedScreen
import com.vknxworks.mailctrl.ui.components.ScreenTopBar
import com.vknxworks.mailctrl.ui.components.SecureScreen
import com.vknxworks.mailctrl.ui.components.ctrlFieldColors
import com.vknxworks.mailctrl.ui.components.runTask
import com.vknxworks.mailctrl.ui.components.shareFile
import com.vknxworks.mailctrl.ui.lock.AppLock
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.nav.SmimeCertRoute
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x509.Certificate
import java.io.File
import java.util.UUID

// ------------------------------------------------------------------ small helpers

private fun date(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)

/** The last 16 digits of a certificate's SHA-256 fingerprint in groups of four. */
fun shortCertFingerprint(fingerprint: String): String = PgpEngine.pretty(fingerprint.takeLast(16))

/** One line about a certificate: its addresses and until when it works. */
@Composable
fun certSummary(info: CertInfo): String {
    val context = LocalContext.current
    val state = if (info.expired()) stringResource(R.string.smime_state_expired, date(context, info.notAfter))
    else stringResource(R.string.smime_state_valid_until, date(context, info.notAfter))
    return listOf(info.emails.joinToString(", ").ifEmpty { shortCertFingerprint(info.fingerprint) }, state).joinToString("  ·  ")
}

private suspend fun readUri(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > Pkcs12.MAX_BYTES) throw SmimeFailure(SmimeFailure.Kind.TOO_BIG)
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: throw SmimeFailure(SmimeFailure.Kind.NOT_SMIME)
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

@Composable
private fun CertRow(info: CertInfo, own: Boolean, onClick: () -> Unit) {
    GroupRow(
        info.name,
        certSummary(info),
        icon = if (own) Icons.Outlined.Badge else Icons.Outlined.ContactMail,
        iconTint = if (!info.expired() && (info.canEncrypt || info.canSign)) CtrlColors.AccentText else CtrlColors.Error,
        showChevron = true,
        onClick = onClick,
    )
}

// ------------------------------------------------------------------ list

/** Your S/MIME certificates and your contacts' certificates: import and open them. */
@Composable
fun SmimeCertsScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val certs by c.smimeCerts.certs.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.smimeCerts.load() }
    var p12 by remember { mutableStateOf<ByteArray?>(null) }
    var identity by remember { mutableStateOf<SmimeIdentity?>(null) }
    var contacts by remember { mutableStateOf<List<Certificate>?>(null) }

    val ownPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                p12 = readUri(context, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmimeFailure) {
                toast(context, e.friendly())
            } catch (e: Exception) {
                toast(context, context.getString(R.string.smime_file_unreadable))
            }
        }
    }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                contacts = c.smime.readCerts(readUri(context, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmimeFailure) {
                toast(context, e.friendly())
            } catch (e: Exception) {
                toast(context, context.getString(R.string.smime_file_unreadable))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.smime_certs))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            val own = certs.filter { it.own }
            val theirs = certs.filterNot { it.own }
            Group(stringResource(R.string.smime_your_certs), footer = stringResource(R.string.smime_your_certs_footer)) {
                own.forEach { s -> CertRow(s.info, true) { nav.navigate(SmimeCertRoute(s.info.fingerprint)) } }
                GroupRow(
                    stringResource(R.string.smime_import_own),
                    stringResource(R.string.smime_import_own_subtitle),
                    icon = Icons.Outlined.Add,
                    onClick = {
                        AppLock.expectReturn()
                        ownPicker.launch(arrayOf("*/*"))
                    },
                )
            }
            Group(stringResource(R.string.smime_contact_certs), footer = stringResource(R.string.smime_contact_certs_footer)) {
                if (theirs.isEmpty()) {
                    GroupRow(stringResource(R.string.smime_no_contact_certs), icon = Icons.Outlined.ContactMail, iconTint = CtrlColors.Muted)
                }
                theirs.forEach { s -> CertRow(s.info, false) { nav.navigate(SmimeCertRoute(s.info.fingerprint)) } }
                GroupRow(
                    stringResource(R.string.smime_import_contact),
                    stringResource(R.string.smime_import_contact_subtitle),
                    icon = Icons.Outlined.FileOpen,
                    onClick = {
                        AppLock.expectReturn()
                        contactPicker.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }

    p12?.let { bytes ->
        PasswordDialog(onDismiss = { p12 = null }) { password ->
            // Wrong password: the dialog stays and says so.
            try {
                identity = c.smime.readIdentity(bytes, password)
                p12 = null
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmimeFailure) {
                if (e.kind == SmimeFailure.Kind.BAD_PASSWORD) context.getString(R.string.smime_error_password)
                else {
                    p12 = null
                    toast(context, e.friendly())
                    null
                }
            } catch (e: Exception) {
                p12 = null
                toast(context, context.getString(R.string.smime_file_unreadable))
                null
            }
        }
    }
    identity?.let { id ->
        val info = remember(id) { SmimeEngine.info(id.cert) }
        CtrlAlertDialog(
            onDismissRequest = { identity = null },
            title = { Text(stringResource(R.string.smime_import_own_title)) },
            text = { CertPreview(info) },
            confirmButton = {
                TextButton(onClick = {
                    identity = null
                    c.runTask {
                        val added = c.smime.importOwn(id)
                        // The certificate becomes the S/MIME certificate of the accounts it's for that have none yet.
                        c.accounts.accounts.value.filter { a -> a.smimeCert == null && a.email.trim().lowercase() in added.emails }
                            .forEach { a -> c.accounts.update(a.id) { it.copy(smimeCert = added.fingerprint) } }
                        toast(context, context.getString(R.string.smime_imported))
                    }
                }) { Text(stringResource(R.string.smime_import)) }
            },
            dismissButton = { TextButton(onClick = { identity = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    contacts?.let { list ->
        val infos = remember(list) { list.map(SmimeEngine::info) }
        CtrlAlertDialog(
            onDismissRequest = { contacts = null },
            title = { Text(stringResource(R.string.smime_import_contact_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    infos.forEach { CertPreview(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    contacts = null
                    c.runTask {
                        c.smime.importContacts(list)
                        toast(context, context.getString(R.string.smime_imported))
                    }
                }) { Text(stringResource(R.string.smime_import)) }
            },
            dismissButton = { TextButton(onClick = { contacts = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Who a certificate is for, who issued it, until when, and its fingerprint to compare. */
@Composable
private fun CertPreview(info: CertInfo) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(info.name, style = MaterialTheme.typography.bodyLarge, color = CtrlColors.Text)
        if (info.emails.isNotEmpty()) Text(info.emails.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text)
        Text(stringResource(R.string.smime_issued_by, info.issuer), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
        Text(
            if (info.expired()) stringResource(R.string.smime_state_expired, date(context, info.notAfter))
            else stringResource(R.string.smime_state_valid_until, date(context, info.notAfter)),
            style = MaterialTheme.typography.bodySmall,
            color = if (info.expired()) CtrlColors.Error else CtrlColors.Muted,
        )
        Text(shortCertFingerprint(info.fingerprint), style = CtrlType.Meta, color = CtrlColors.Muted)
    }
}

/**
 * Asks for a .p12/.pfx file's password. Kept out of screenshots, and nothing can be drawn over it. [onSubmit] returns
 * an error to show, or null when it's done.
 */
@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onSubmit: suspend (CharArray) -> String?) {
    SecureScreen()
    GuardedScreen(sensitive = true)
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (busy) return
        busy = true
        val chars = pass.toCharArray()
        scope.launch {
            try {
                error = onSubmit(chars)
                if (error == null) pass = ""
            } finally {
                chars.fill('\u0000')
                busy = false
            }
        }
    }

    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smime_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.smime_password_body), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text)
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it; error = null },
                    label = { Text(stringResource(R.string.smime_password)) },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(
                                if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                stringResource(if (show) R.string.pgp_hide_passphrase else R.string.pgp_show_passphrase),
                            )
                        }
                    },
                    shape = CtrlFieldShape,
                    colors = ctrlFieldColors(),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CtrlColors.Error) }
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !busy) { Text(stringResource(R.string.smime_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ one certificate

@Composable
fun SmimeCertScreen(fingerprint: String) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val certs by c.smimeCerts.certs.collectAsStateWithLifecycle()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.smimeCerts.load() }
    val stored = certs.firstOrNull { it.info.fingerprint == fingerprint }
    var deleting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(if (stored?.own == true) R.string.smime_your_cert else R.string.smime_contact_cert))
        if (stored == null) return@Column
        val info = stored.info
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Column(Modifier.padding(horizontal = Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info.name, style = MaterialTheme.typography.titleMedium, color = CtrlColors.Text)
                info.emails.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text) }
            }
            Group(stringResource(R.string.smime_fingerprint), footer = stringResource(R.string.smime_fingerprint_footer)) {
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
                GroupRow(stringResource(R.string.smime_issuer), if (info.selfSigned) stringResource(R.string.smime_self_signed) else info.issuer)
                GroupRow(stringResource(R.string.smime_valid_from), date(context, info.notBefore))
                GroupRow(
                    stringResource(R.string.pgp_expires),
                    if (info.expired()) stringResource(R.string.smime_state_expired, date(context, info.notAfter)) else date(context, info.notAfter),
                )
                GroupRow(stringResource(R.string.pgp_algorithm), info.algorithm)
                GroupRow(
                    stringResource(R.string.pgp_usable_for),
                    listOfNotNull(
                        stringResource(R.string.pgp_use_encrypt).takeIf { info.canEncrypt },
                        stringResource(R.string.pgp_use_sign).takeIf { info.canSign },
                    ).joinToString(", ").ifEmpty { stringResource(R.string.pgp_state_unusable) },
                )
            }
            if (stored.own && accounts.isNotEmpty()) {
                Group(stringResource(R.string.pgp_used_by), footer = stringResource(R.string.smime_used_by_footer)) {
                    accounts.forEach { a ->
                        GroupSwitch(a.title, a.email.takeIf { it != a.title }, a.smimeCert == fingerprint) { on ->
                            c.runTask { c.accounts.update(a.id) { it.copy(smimeCert = if (on) fingerprint else null) } }
                        }
                    }
                }
            }
            Group {
                GroupRow(stringResource(R.string.smime_share_cert), icon = Icons.Outlined.Share, onClick = {
                    val name = (info.emails.firstOrNull() ?: "certificate").replace(Regex("[^A-Za-z0-9@._-]"), "_") + ".pem"
                    c.runTask {
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(context.cacheDir, "attachments/${UUID.randomUUID()}").apply { mkdirs() }
                            File(dir, name).apply { writeText(SmimeEngine.pem(stored.cert)) }
                        }
                        shareFile(context, file, "application/x-pem-file")
                    }
                })
                GroupRow(stringResource(R.string.smime_delete), icon = Icons.Outlined.Delete, iconTint = CtrlColors.Error, titleColor = CtrlColors.Error, onClick = { deleting = true })
            }
        }
    }

    if (deleting && stored != null) {
        ConfirmDialog(
            stringResource(R.string.smime_delete_title),
            stringResource(if (stored.own) R.string.smime_delete_own_body else R.string.smime_delete_body),
            stringResource(R.string.delete),
            onDismiss = { deleting = false },
        ) {
            deleting = false
            nav.back()
            c.runTask {
                c.smime.delete(fingerprint)
                accounts.filter { it.smimeCert == fingerprint }.forEach { a -> c.accounts.update(a.id) { it.copy(smimeCert = null) } }
            }
        }
    }
}
