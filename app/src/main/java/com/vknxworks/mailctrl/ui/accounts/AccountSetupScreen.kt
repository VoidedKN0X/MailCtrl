package com.vknxworks.mailctrl.ui.accounts

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.accounts.Account
import com.vknxworks.mailctrl.data.accounts.Presets
import com.vknxworks.mailctrl.mail.AuthException
import com.vknxworks.mailctrl.mail.Security
import com.vknxworks.mailctrl.mail.ServerConfig
import com.vknxworks.mailctrl.mail.ServerTrust
import com.vknxworks.mailctrl.mail.UntrustedCertificateException
import com.vknxworks.mailctrl.notify.NotifyChecker
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.CtrlAlertDialog
import com.vknxworks.mailctrl.ui.components.CtrlButton
import com.vknxworks.mailctrl.ui.components.CtrlCardShape
import com.vknxworks.mailctrl.ui.components.CtrlChip
import com.vknxworks.mailctrl.ui.components.CtrlFieldShape
import com.vknxworks.mailctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.mailctrl.ui.components.CtrlSubTopBar
import com.vknxworks.mailctrl.ui.components.CtrlWordmark
import com.vknxworks.mailctrl.ui.components.Group
import com.vknxworks.mailctrl.ui.components.GroupSwitch
import com.vknxworks.mailctrl.ui.components.SectionLabel
import com.vknxworks.mailctrl.ui.components.SecureScreen
import com.vknxworks.mailctrl.ui.components.ctrlFieldColors
import com.vknxworks.mailctrl.ui.components.friendlyMessage
import com.vknxworks.mailctrl.ui.components.openLink
import com.vknxworks.mailctrl.ui.components.runTask
import com.vknxworks.mailctrl.ui.nav.LocalNavigator
import com.vknxworks.mailctrl.ui.theme.CtrlColors
import com.vknxworks.mailctrl.ui.theme.CtrlType
import com.vknxworks.mailctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/**
 * Adding an account (the first one is the start screen), or changing one. A known address only needs the
 * password; anything else shows the server settings to check or fill in.
 */
@Composable
fun AccountSetupScreen(accountId: String?, first: Boolean) {
    val context = LocalContext.current
    val c = context.container
    // The first account is added before the app's screens exist: there's nothing to go back to.
    val nav = if (first) null else LocalNavigator.current
    val scope = rememberCoroutineScope()
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    val existing = accountId?.let { id -> accounts.firstOrNull { it.id == id } }
    val editing = existing != null
    SecureScreen()

    var email by rememberSaveable { mutableStateOf(existing?.email.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var senderName by rememberSaveable { mutableStateOf(existing?.senderName.orEmpty()) }
    var label by rememberSaveable { mutableStateOf(existing?.label.orEmpty()) }
    var signature by rememberSaveable { mutableStateOf(existing?.signature.orEmpty()) }
    var notify by rememberSaveable { mutableStateOf(existing?.notify ?: true) }
    var saveSent by rememberSaveable { mutableStateOf(existing?.saveSent ?: true) }
    // Server settings show for editing, unknown providers, Proton, and when the automatic try failed.
    var showServers by rememberSaveable { mutableStateOf(editing) }
    var proton by rememberSaveable { mutableStateOf(existing?.let { Presets.isProton(it.email) } ?: false) }
    var imapHost by rememberSaveable { mutableStateOf(existing?.imap?.host.orEmpty()) }
    var imapPort by rememberSaveable { mutableStateOf(existing?.imap?.port?.toString() ?: "993") }
    var imapSec by rememberSaveable { mutableStateOf(existing?.imap?.security ?: Security.SSL) }
    var imapUser by rememberSaveable { mutableStateOf(existing?.imap?.username.orEmpty()) }
    var smtpHost by rememberSaveable { mutableStateOf(existing?.smtp?.host.orEmpty()) }
    var smtpPort by rememberSaveable { mutableStateOf(existing?.smtp?.port?.toString() ?: "465") }
    var smtpSec by rememberSaveable { mutableStateOf(existing?.smtp?.security ?: Security.SSL) }
    var smtpUser by rememberSaveable { mutableStateOf(existing?.smtp?.username.orEmpty()) }
    var trusted by remember { mutableStateOf(existing?.trustedCerts.orEmpty()) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var untrusted by remember { mutableStateOf<UntrustedCertificateException?>(null) }
    var removing by remember { mutableStateOf(false) }

    fun applyPreset(address: String) {
        val p = Presets.forEmail(address)
        proton = p.proton
        imapHost = p.imap.host; imapPort = p.imap.port.toString(); imapSec = p.imap.security; imapUser = p.imap.username
        smtpHost = p.smtp.host; smtpPort = p.smtp.port.toString(); smtpSec = p.smtp.security; smtpUser = p.smtp.username
        saveSent = p.saveSent
    }

    fun build(): Account? {
        val ip = imapPort.toIntOrNull()?.takeIf { it in 1..65535 }
        val sp = smtpPort.toIntOrNull()?.takeIf { it in 1..65535 }
        if (imapHost.isBlank() || smtpHost.isBlank() || ip == null || sp == null) {
            error = context.getString(R.string.setup_fill_servers)
            showServers = true
            return null
        }
        val base = existing ?: Account(
            email = email.trim(),
            imap = ServerConfig("", 0, Security.SSL, ""),
            smtp = ServerConfig("", 0, Security.SSL, ""),
        )
        return base.copy(
            email = email.trim(),
            senderName = senderName.trim(),
            label = label.trim(),
            signature = signature.trimEnd(),
            notify = notify,
            saveSent = saveSent,
            imap = ServerConfig(imapHost.trim(), ip, imapSec, imapUser.trim().ifBlank { email.trim() }),
            smtp = ServerConfig(smtpHost.trim(), sp, smtpSec, smtpUser.trim().ifBlank { email.trim() }),
            trustedCerts = trusted,
        )
    }

    fun finish() {
        if (nav != null) nav.back()
    }

    fun submit() {
        if (busy) return
        error = null
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            error = context.getString(R.string.setup_bad_email)
            return
        }
        if (Presets.unsupported(email)) {
            error = context.getString(R.string.setup_no_imap)
            return
        }
        // Settings shown on screen are what you see; otherwise they come from the address each time.
        if (!editing && !showServers) applyPreset(email.trim())
        val preset = Presets.forEmail(email.trim())
        if (!editing && preset.needsHost && imapHost.isBlank()) {
            showServers = true
            error = context.getString(R.string.setup_proton_host)
            return
        }
        val a = build() ?: return
        val old = existing
        val needsTest = old == null || password.isNotEmpty() || a.imap != old.imap || a.smtp != old.smtp ||
            a.trustedCerts != old.trustedCerts || c.accounts.needsPassword(old)
        if (needsTest && password.isEmpty()) {
            error = context.getString(R.string.setup_password_needed)
            return
        }
        if (!needsTest) {
            // Only names, signature or switches changed: no need to sign in again.
            c.runTask { c.accounts.put(a, null) }
            finish()
            return
        }
        busy = true
        scope.launch {
            try {
                val pw = Presets.cleanPassword(a.email, password)
                val tested = c.mail.test(a, pw)
                c.mail.close(tested.id)
                c.accounts.put(tested, pw)
                if (!editing) NotifyChecker.forget(context, tested.id)
                c.runTask { c.actions.refreshFolders(tested.id) }
                busy = false
                finish()
            } catch (e: UntrustedCertificateException) {
                busy = false
                untrusted = e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                error = e.friendlyMessage()
                // A known address that didn't sign in: show what was tried, so it can be checked.
                if (e !is AuthException) showServers = true
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (first) {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Gutter, end = Gutter, top = 20.dp)) {
                CtrlWordmark()
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineLarge, color = CtrlColors.Text)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted)
            }
        } else {
            CtrlSubTopBar(stringResource(if (editing) R.string.account_edit else R.string.add_account), onBack = { nav?.back() })
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 12.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field(stringResource(R.string.setup_email), email, { email = it; if (!editing && !showServers) proton = Presets.isProton(it) }, KeyboardType.Email, enabled = !editing)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(if (editing) R.string.setup_password_keep else if (proton) R.string.setup_password_proton else R.string.setup_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                    }
                },
                shape = CtrlFieldShape,
                colors = ctrlFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Note(Icons.Outlined.Info, stringResource(if (proton) R.string.setup_proton_note else R.string.setup_app_password_note))
            // Known providers that need an app password: a shortcut to the page where you make one.
            val appPasswordUrl = if (proton) null else Presets.appPasswordUrl(email.trim())
            if (appPasswordUrl != null) {
                CtrlSecondaryButton(
                    stringResource(R.string.setup_make_app_password),
                    onClick = { openLink(context, appPasswordUrl) },
                    icon = Icons.Outlined.Key,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Field(stringResource(R.string.setup_sender_name), senderName, { senderName = it })

            if (showServers) {
                ServerFields(
                    title = stringResource(R.string.setup_incoming),
                    host = imapHost, onHost = { imapHost = it },
                    port = imapPort, onPort = { imapPort = it },
                    security = imapSec, onSecurity = { s -> imapSec = s; imapPort = Presets.imapPort(s, imapPort.toIntOrNull() ?: 0).toString() },
                    user = imapUser, onUser = { imapUser = it },
                    userHint = email.trim(),
                )
                ServerFields(
                    title = stringResource(R.string.setup_outgoing),
                    host = smtpHost, onHost = { smtpHost = it },
                    port = smtpPort, onPort = { smtpPort = it },
                    security = smtpSec, onSecurity = { s -> smtpSec = s; smtpPort = Presets.smtpPort(s, smtpPort.toIntOrNull() ?: 0).toString() },
                    user = smtpUser, onUser = { smtpUser = it },
                    userHint = email.trim(),
                )
                if (imapSec == Security.NONE || smtpSec == Security.NONE) {
                    Note(Icons.Outlined.WarningAmber, stringResource(R.string.setup_unencrypted), warning = true)
                }
                if (trusted.isNotEmpty()) {
                    Note(Icons.Outlined.Info, stringResource(R.string.setup_trusted_certs, trusted.size))
                    TextButton(onClick = { trusted = emptyList() }) { Text(stringResource(R.string.setup_forget_certs)) }
                }
            } else if (!editing) {
                TextButton(onClick = {
                    if (imapHost.isBlank() && email.contains('@')) applyPreset(email.trim())
                    showServers = true
                }) { Text(stringResource(R.string.setup_manual)) }
            }

            if (editing || showServers) {
                Group(padded = false) {
                    GroupSwitch(stringResource(R.string.setup_save_sent), stringResource(R.string.setup_save_sent_subtitle), saveSent) { saveSent = it }
                    GroupSwitch(stringResource(R.string.setup_notify), stringResource(R.string.setup_notify_subtitle), notify) { notify = it }
                }
            }
            if (editing) {
                Field(stringResource(R.string.setup_label), label, { label = it })
                OutlinedTextField(
                    value = signature,
                    onValueChange = { signature = it },
                    label = { Text(stringResource(R.string.setup_signature)) },
                    minLines = 3,
                    shape = CtrlFieldShape,
                    colors = ctrlFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            error?.let { Note(Icons.Outlined.WarningAmber, it, warning = true) }
            CtrlButton(
                stringResource(if (editing) R.string.save else R.string.setup_sign_in),
                onClick = ::submit,
                enabled = !busy && email.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CtrlColors.AccentText)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.setup_checking), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted)
                }
            }
            if (editing) {
                Spacer(Modifier.height(8.dp))
                CtrlSecondaryButton(stringResource(R.string.account_remove), onClick = { removing = true }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                stringResource(R.string.setup_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = CtrlColors.Muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }

    untrusted?.let { cert ->
        com.vknxworks.mailctrl.ui.components.GuardedScreen()
        CtrlAlertDialog(
            onDismissRequest = { untrusted = null },
            title = { Text(stringResource(R.string.cert_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.cert_body, cert.host))
                    SectionLabel(stringResource(R.string.cert_fingerprint))
                    SelectionContainer { Text(ServerTrust.pretty(cert.fingerprint), style = CtrlType.Meta, color = CtrlColors.Text) }
                    SectionLabel(stringResource(R.string.cert_subject))
                    Text(cert.subject, style = CtrlType.Meta, color = CtrlColors.Text)
                    SectionLabel(stringResource(R.string.cert_issuer))
                    Text(cert.issuer, style = CtrlType.Meta, color = CtrlColors.Text)
                    SectionLabel(stringResource(R.string.cert_valid_until))
                    Text(DateUtils.formatDateTime(context, cert.validUntil, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR), style = CtrlType.Meta, color = CtrlColors.Text)
                    Text(stringResource(R.string.cert_advice), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    trusted = (trusted + cert.fingerprint).distinct()
                    untrusted = null
                    submit()
                }) { Text(stringResource(R.string.cert_trust)) }
            },
            dismissButton = { TextButton(onClick = { untrusted = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (removing && existing != null) {
        ConfirmDialog(
            stringResource(R.string.account_remove_title, existing.title),
            stringResource(R.string.account_remove_body),
            stringResource(R.string.account_remove),
            onDismiss = { removing = false },
        ) {
            removing = false
            val id = existing.id
            nav?.back()
            c.runTask {
                c.mail.close(id)
                c.notifier.cancelAccount(id)
                NotifyChecker.forget(context, id)
                c.drafts.deleteForAccount(id)
                c.cache.clearAccount(id)
                c.accounts.remove(id)
                c.settings.update { if (it.defaultAccount == id) it.copy(defaultAccount = "") else it }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        shape = CtrlFieldShape,
        colors = ctrlFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Note(icon: ImageVector, text: String, warning: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CtrlColors.Surface, CtrlCardShape)
            .border(1.dp, if (warning) CtrlColors.Error else CtrlColors.Outline, CtrlCardShape)
            .padding(14.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (warning) CtrlColors.Error else CtrlColors.Muted)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = CtrlColors.Text)
    }
}

@Composable
private fun ServerFields(
    title: String,
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    security: Security,
    onSecurity: (Security) -> Unit,
    user: String,
    onUser: (String) -> Unit,
    userHint: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, Modifier.padding(start = 4.dp, top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { onHost(it.trim()) },
                label = { Text(stringResource(R.string.setup_server)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = CtrlFieldShape,
                colors = ctrlFieldColors(),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { v -> onPort(v.filter { it.isDigit() }.take(5)) },
                label = { Text(stringResource(R.string.setup_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = CtrlFieldShape,
                colors = ctrlFieldColors(),
                modifier = Modifier.width(96.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CtrlChip(security == Security.SSL, onClick = { onSecurity(Security.SSL) }, label = stringResource(R.string.security_ssl))
            CtrlChip(security == Security.STARTTLS, onClick = { onSecurity(Security.STARTTLS) }, label = stringResource(R.string.security_starttls))
            CtrlChip(security == Security.NONE, onClick = { onSecurity(Security.NONE) }, label = stringResource(R.string.security_none))
        }
        OutlinedTextField(
            value = user,
            onValueChange = onUser,
            label = { Text(stringResource(R.string.setup_username)) },
            placeholder = { Text(userHint) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            shape = CtrlFieldShape,
            colors = ctrlFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
