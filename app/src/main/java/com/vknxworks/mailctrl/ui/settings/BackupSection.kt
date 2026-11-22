package com.vknxworks.mailctrl.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vknxworks.mailctrl.BuildConfig
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.settings.AccountRestore
import com.vknxworks.mailctrl.data.settings.BackupFile
import com.vknxworks.mailctrl.data.settings.BackupFormatException
import com.vknxworks.mailctrl.data.settings.SettingsBackups
import com.vknxworks.mailctrl.ui.components.ConfirmDialog
import com.vknxworks.mailctrl.ui.components.Group
import com.vknxworks.mailctrl.ui.components.GroupRow
import com.vknxworks.mailctrl.ui.lock.AppLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.OffsetDateTime

/** A file read from storage, and what restoring it would do to the accounts on this phone. */
private class PendingRestore(val file: BackupFile, val accounts: AccountRestore)

/**
 * "Back up and restore": saves MailCtrl's settings and your accounts' addresses, names, signatures and server
 * addresses to a file you choose, and reads such a file back. Nothing secret is in the file (no passwords, no
 * trusted certificates, no keys), and reading one never turns the app lock off, trusts a certificate or lets
 * an unencrypted connection in without asking: it says what will be replaced first.
 */
@Composable
fun BackupGroup() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingRestore?>(null) }
    /** Waiting for the yes to accounts whose connection isn't encrypted. */
    var pendingUnencrypted by remember { mutableStateOf<PendingRestore?>(null) }

    fun restore(p: PendingRestore) {
        scope.launch {
            val ok = try {
                (p.accounts.added + p.accounts.updated).forEach { c.accounts.put(it, null) }
                val accounts = c.accounts.accounts.value
                c.settings.update { SettingsBackups.settings(p.file, it, accounts) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            val message = when {
                !ok -> R.string.backup_restore_failed
                p.accounts.added.isNotEmpty() -> R.string.backup_restored_passwords
                else -> R.string.backup_restored
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = SettingsBackups.export(c.settings.current(), c.accounts.accounts.value, BuildConfig.VERSION_NAME, OffsetDateTime.now().toString())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no stream")
                }
            }.isSuccess
            Toast.makeText(context, if (ok) R.string.backup_saved else R.string.backup_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // A settings file is a few kilobytes; anything far bigger isn't one.
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BYTES) throw BackupFormatException(newer = false)
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: throw BackupFormatException(newer = false)
                }
                val file = SettingsBackups.parse(text)
                PendingRestore(file, SettingsBackups.accounts(file, c.accounts.accounts.value))
            }
            result.onSuccess { pending = it }.onFailure { e ->
                if (e is CancellationException) throw e
                val message = if (e is BackupFormatException && e.newer) R.string.backup_too_new else R.string.backup_not_valid
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    Group(stringResource(R.string.backup_title), footer = stringResource(R.string.backup_footer)) {
        GroupRow(
            stringResource(R.string.backup_save),
            stringResource(R.string.backup_save_sub),
            Icons.Outlined.FileDownload,
            onClick = {
                AppLock.expectReturn()
                save.launch("mailctrl-settings-${LocalDate.now()}.json")
            },
        )
        GroupRow(
            stringResource(R.string.backup_restore),
            stringResource(R.string.backup_restore_sub),
            Icons.Outlined.FileUpload,
            onClick = {
                AppLock.expectReturn()
                open.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            },
        )
    }

    pending?.let { p ->
        val none = stringResource(R.string.backup_none)
        val added = p.accounts.added.joinToString("\n") { it.email }.ifEmpty { none }
        val updated = p.accounts.updated.joinToString("\n") { it.email }.ifEmpty { none }
        ConfirmDialog(
            stringResource(R.string.backup_restore_title),
            stringResource(R.string.backup_restore_body, p.file.createdAt.take(10).ifEmpty { "?" }, added, updated),
            stringResource(R.string.backup_restore_confirm),
            onDismiss = { pending = null },
        ) {
            pending = null
            // A new account without encryption: the usual warning first, with its own yes.
            if (p.accounts.unencrypted.isEmpty()) restore(p) else pendingUnencrypted = p
        }
    }

    pendingUnencrypted?.let { p ->
        ConfirmDialog(
            stringResource(R.string.backup_unencrypted_title),
            stringResource(R.string.backup_unencrypted_body, p.accounts.unencrypted.joinToString("\n") { it.email + " (" + it.imap.host + ")" }),
            stringResource(R.string.backup_unencrypted_confirm),
            onDismiss = {
                pendingUnencrypted = null
                Toast.makeText(context, R.string.backup_declined, Toast.LENGTH_LONG).show()
            },
        ) {
            pendingUnencrypted = null
            restore(p)
        }
    }
}

private const val MAX_BYTES = 512 * 1024
