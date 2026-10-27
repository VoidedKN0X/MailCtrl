package com.vknxworks.mailctrl

import android.content.Intent
import android.graphics.Color
import android.net.MailTo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.mailctrl.ui.components.ScreenPrivacy
import com.vknxworks.mailctrl.ui.components.WindowPrivacy
import com.vknxworks.mailctrl.ui.lock.AppLock
import com.vknxworks.mailctrl.ui.lock.AppLockGate
import com.vknxworks.mailctrl.ui.nav.AppRoot
import com.vknxworks.mailctrl.ui.theme.MailCtrlTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle()
            // Follows the device's dark/light setting, with see-through bars over the app's background.
            val dark = isSystemInDarkTheme()
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            // "Hide in screenshots" (off by default); always on for the lock and PIN screens and password fields.
            val covered = (c.vault.enabled && !AppLock.unlocked) || AppLock.request != null
            val secure = settings.hideScreenshots || covered || ScreenPrivacy.forced > 0
            // "Hide in recent apps" (off by default). With the app lock on, Recents always shows a blank card:
            // a preview there would show your mail without the PIN.
            WindowPrivacy(this, secure, hideRecents = settings.hideRecents || c.vault.enabled)
            MailCtrlTheme(dark) {
                AppLockGate { AppRoot() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Turns a notification tap, a mailto: link or a share into something to open. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Other apps start this screen: a malformed intent (bad extras) is ignored instead of crashing.
        val action = runCatching { actionFor(intent) }.getOrNull()
        if (action != null) container.pendingAction.value = action
    }

    private fun actionFor(intent: Intent): PendingAction? = when (intent.action) {
        ACTION_OPEN_MESSAGE -> {
            val account = intent.getStringExtra(EXTRA_ACCOUNT)
            val folder = intent.getStringExtra(EXTRA_FOLDER)
            val uid = intent.getLongExtra(EXTRA_UID, -1L)
            if (account != null && folder != null && uid > 0) PendingAction.OpenMessage(account, folder, uid) else PendingAction.OpenInbox
        }
        ACTION_OPEN_INBOX -> PendingAction.OpenInbox
        ACTION_COMPOSE -> PendingAction.Compose()
        Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme.equals("mailto", ignoreCase = true) }?.let { mailto(it, intent) }
        Intent.ACTION_SEND -> {
            val file = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.takeIf(::isShareable)
            PendingAction.Compose(
                to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList().orEmpty(),
                cc = intent.getStringArrayExtra(Intent.EXTRA_CC)?.toList().orEmpty(),
                bcc = intent.getStringArrayExtra(Intent.EXTRA_BCC)?.toList().orEmpty(),
                subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
                body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
                attachments = listOfNotNull(file),
            )
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val files = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            PendingAction.Compose(
                to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList().orEmpty(),
                subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
                body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
                attachments = files.filterNotNull().filter(::isShareable).take(MAX_SHARED_FILES),
            )
        }
        else -> null
    }

    private fun mailto(uri: Uri, intent: Intent): PendingAction.Compose {
        val m = MailTo.parse(uri.toString())
        fun split(v: String?) = v?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return PendingAction.Compose(
            to = split(m.to) + intent.getStringArrayExtra(Intent.EXTRA_EMAIL).orEmpty(),
            cc = split(m.cc),
            bcc = split(m.headers["bcc"]),
            subject = m.subject ?: intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
            body = m.body ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
        )
    }

    /**
     * Only content another app actually shares: never a file path, and never a link back to MailCtrl's own files
     * (another app could otherwise make MailCtrl send its own private files).
     */
    private fun isShareable(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        // "content://0@<authority>" still reaches <authority> (the number is a user id), so look past it.
        val authority = uri.authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return false
        return !authority.startsWith(packageName)
    }

    companion object {
        const val ACTION_OPEN_MESSAGE = "com.vknxworks.mailctrl.OPEN_MESSAGE"
        const val ACTION_OPEN_INBOX = "com.vknxworks.mailctrl.OPEN_INBOX"
        const val ACTION_COMPOSE = "com.vknxworks.mailctrl.COMPOSE"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_UID = "uid"
        private const val MAX_SHARED_FILES = 20
    }
}
