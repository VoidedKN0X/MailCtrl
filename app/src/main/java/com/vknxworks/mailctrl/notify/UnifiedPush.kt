package com.vknxworks.mailctrl.notify

import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The phone side of push through ntfy. ntfy keeps one connection to your ntfy server and wakes MailCtrl when one of
 * your servers posts to MailCtrl's push address. This speaks the UnifiedPush Android protocol
 * (https://unifiedpush.org/developers/spec/android/) directly with ntfy, without a library, and only with ntfy.
 */
object UnifiedPush {
    /** ntfy's package name: the only app MailCtrl registers with. */
    const val NTFY = "io.heckel.ntfy"
    const val NTFY_DOWNLOAD = "https://f-droid.org/packages/io.heckel.ntfy/"

    private const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    private const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    private const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val ACTION_TEMP_UNAVAILABLE = "org.unifiedpush.android.connector.TEMP_UNAVAILABLE"
    private const val FEATURE_BYTES_MESSAGE = "org.unifiedpush.android.distributor.feature.BYTES_MESSAGE"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_ID = "id"
    const val EXTRA_REASON = "reason"

    private const val PREFS = "unifiedpush"
    private const val KEY_TOKEN = "token"
    private const val KEY_ERROR = "error"

    /** ntfy reported that its server is down for now (TEMP_UNAVAILABLE); cleared once it's back. */
    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether ntfy is installed and can take registrations. */
    @Suppress("DEPRECATION")
    fun isNtfyInstalled(context: Context): Boolean = runCatching {
        context.packageManager.queryBroadcastReceivers(Intent(ACTION_REGISTER).setPackage(NTFY), 0).isNotEmpty()
    }.getOrDefault(false)

    /** Whether ntfy may run unrestricted; if not, Android can cut its connection while the phone sleeps. */
    fun isNtfyUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(NTFY) == true

    /** The random secret that proves a broadcast really comes from ntfy for our registration. */
    fun token(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)

    /** Why ntfy last refused to register MailCtrl (its reason code), if it did. */
    fun lastError(context: Context): String? = prefs(context).getString(KEY_ERROR, null)

    /** Asks ntfy for an endpoint. The answer (NEW_ENDPOINT or REGISTRATION_FAILED) arrives in [PushReceiver]. */
    fun register(context: Context, description: String): Boolean {
        if (!isNtfyInstalled(context)) return false
        val p = prefs(context)
        val token = p.getString(KEY_TOKEN, null) ?: UUID.randomUUID().toString().also { p.edit().putString(KEY_TOKEN, it).commit() }
        send(
            context,
            Intent(ACTION_REGISTER).apply {
                `package` = NTFY
                putExtra(EXTRA_TOKEN, token)
                putExtra("features", arrayOf(FEATURE_BYTES_MESSAGE))
                putExtra("application", context.packageName)
                putExtra(EXTRA_MESSAGE, description)
            },
        )
        return true
    }

    /** Tells ntfy to drop our registration and forgets the token, so the old address can never wake the app again. */
    fun unregister(context: Context) {
        val token = token(context)
        prefs(context).edit().clear().commit()
        _unavailable.value = false
        if (token != null && isNtfyInstalled(context)) {
            send(context, Intent(ACTION_UNREGISTER).apply { `package` = NTFY; putExtra(EXTRA_TOKEN, token) })
        }
    }

    internal fun onEndpoint(context: Context) {
        prefs(context).edit().remove(KEY_ERROR).apply()
        _unavailable.value = false
    }

    /** ntfy refused or dropped the registration: the next one must use a new token (spec). */
    internal fun onLost(context: Context, reason: String?) {
        val edit = prefs(context).edit().remove(KEY_TOKEN)
        if (reason != null) edit.putString(KEY_ERROR, reason) else edit.remove(KEY_ERROR)
        edit.commit()
        _unavailable.value = false
    }

    internal fun onTempUnavailable() {
        _unavailable.value = true
    }

    internal fun onMessage() {
        _unavailable.value = false
    }

    fun ack(context: Context, token: String, id: String?) {
        if (id.isNullOrEmpty()) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_MESSAGE_ACK).apply {
                    `package` = NTFY
                    putExtra(EXTRA_TOKEN, token)
                    putExtra(EXTRA_ID, id)
                },
            )
        }
    }

    private fun send(context: Context, intent: Intent) {
        // Tells ntfy which app is asking: a pending intent before Android 14, the sender's identity after.
        val pi = PendingIntent.getBroadcast(context, 0, Intent("org.unifiedpush.dummy_app"), PendingIntent.FLAG_IMMUTABLE)
        intent.putExtra("pi", pi)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            } else {
                context.sendBroadcast(intent)
            }
        }
    }
}
