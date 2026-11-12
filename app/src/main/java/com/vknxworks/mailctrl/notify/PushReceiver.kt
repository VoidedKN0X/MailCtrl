package com.vknxworks.mailctrl.notify

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.vknxworks.mailctrl.container
import kotlinx.coroutines.launch

/**
 * Receives what ntfy hands over: the push address, registration errors and wake-ups.
 * A message is only a wake-up call: its content is ignored and MailCtrl checks your servers itself,
 * so nothing in the message is trusted or shown.
 *
 * Exported because ntfy is another app, so every broadcast must carry the secret token of MailCtrl's
 * registration or it's ignored: the token is what keeps other apps out. From Android 14 a broadcast from a
 * sender that identifies itself as an app other than ntfy is dropped too, but a sender can stay anonymous,
 * so that check only adds to the token.
 */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(UnifiedPush.EXTRA_TOKEN) ?: return
        if (token != UnifiedPush.token(context)) return
        // Only rejects senders that identify as another app; anonymous ones get through to the token check.
        val sender = sentFromPackage
        if (sender != null && sender != UnifiedPush.NTFY) return
        val app = context.applicationContext
        when (intent.action) {
            UnifiedPush.ACTION_NEW_ENDPOINT -> {
                val endpoint = intent.getStringExtra(UnifiedPush.EXTRA_ENDPOINT) ?: return
                UnifiedPush.ack(app, token, intent.getStringExtra(UnifiedPush.EXTRA_ID))
                UnifiedPush.onEndpoint(app)
                updateSettings(app) { c -> c.settings.update { it.copy(pushEndpoint = endpoint) } }
            }
            UnifiedPush.ACTION_REGISTRATION_FAILED -> {
                UnifiedPush.onLost(app, intent.getStringExtra(UnifiedPush.EXTRA_REASON) ?: "UNKNOWN")
                updateSettings(app) { c -> c.settings.update { it.copy(pushEnabled = false, pushEndpoint = null) } }
            }
            UnifiedPush.ACTION_UNREGISTERED -> {
                UnifiedPush.onLost(app, null)
                updateSettings(app) { c -> c.settings.update { it.copy(pushEnabled = false, pushEndpoint = null) } }
            }
            UnifiedPush.ACTION_TEMP_UNAVAILABLE -> UnifiedPush.onTempUnavailable()
            UnifiedPush.ACTION_MESSAGE -> {
                UnifiedPush.onMessage()
                NotifyChecker.markPush(app)
                NotifySchedule.checkNow(app, push = true)
                UnifiedPush.ack(app, token, intent.getStringExtra(UnifiedPush.EXTRA_ID))
            }
        }
    }

    private fun updateSettings(context: Context, block: suspend (com.vknxworks.mailctrl.AppContainer) -> Unit) {
        val pending = goAsync()
        val c = context.container
        c.appScope.launch {
            try { block(c) } finally { pending.finish() }
        }
    }
}

/**
 * ntfy binds to this for a few seconds before it delivers a wake-up. That lifts MailCtrl to foreground importance,
 * so the check can start straight away with network access even while the phone is in deep sleep.
 */
class RaiseToForegroundService : Service() {
    private val binder = Binder()
    override fun onBind(intent: Intent?): IBinder = binder
}
