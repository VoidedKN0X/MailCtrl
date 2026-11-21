package com.vknxworks.mailctrl.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import com.vknxworks.mailctrl.MainActivity
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.data.settings.AppSettings
import com.vknxworks.mailctrl.mail.AuthException
import com.vknxworks.mailctrl.mail.LockedException
import com.vknxworks.mailctrl.mail.MailClient
import com.vknxworks.mailctrl.mail.PasswordNeededException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** How the open connection of one account is doing, for the Notifications screen. */
enum class IdleState {
    /** Signing in. */
    CONNECTING,

    /** Waiting for the server to announce new mail. */
    WAITING,

    /** The server doesn't offer IMAP IDLE: this account is only checked on the schedule (and by ntfy). */
    UNSUPPORTED,

    /** Sign-in was refused, or the password has to be typed again: not tried again until MailCtrl restarts it. */
    REFUSED,

    /** No connection right now; trying again by itself. */
    RETRYING,
}

/** Starts and stops [IdleService] to match the settings. */
object IdleControl {
    private val _states = MutableStateFlow<Map<String, IdleState>>(emptyMap())

    /** The state per account id, while the service runs. */
    val states: StateFlow<Map<String, IdleState>> = _states.asStateFlow()

    internal fun set(accountId: String, state: IdleState?) {
        _states.update { if (state == null) it - accountId else it + (accountId to state) }
    }

    internal fun clear() {
        _states.value = emptyMap()
    }

    /** Whether the open connection is wanted at all. */
    fun wanted(s: AppSettings): Boolean = s.notifyEnabled && s.idle

    /**
     * Starts the service when "Instant with an open connection" is on, stops it otherwise. Android only lets an
     * app start such a service while it's on screen, after a restart of the phone, or when its battery use is
     * unrestricted; when it refuses, nothing happens and the scheduled checks carry on.
     */
    fun apply(context: Context, s: AppSettings) {
        val intent = Intent(context, IdleService::class.java)
        if (wanted(s)) {
            runCatching { context.startForegroundService(intent) }
        } else {
            runCatching { context.stopService(intent) }
        }
    }
}

/** After the phone restarts: the open connection is set up again when it's turned on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val c = app.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                IdleControl.apply(app, c.settings.current())
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * "Instant with an open connection" (IMAP IDLE, RFC 2177), off until you turn it on: keeps one connection per
 * notifying account open on its inbox, and the mail server says so the moment a message arrives. MailCtrl then
 * runs its normal new-mail check, which shows the notification. No ntfy and no other server is involved.
 *
 * It runs as a foreground service (Android shows its notification while it runs), because Android would
 * otherwise close the connections as soon as MailCtrl leaves the screen. Every ten minutes each connection is
 * asked for a sign of life, so servers and routers don't drop it for being quiet. A lost connection is made
 * again with growing pauses (up to 15 minutes), and at once when the phone gets a network back. The scheduled
 * background check stays on as a fallback.
 */
class IdleService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchers = HashMap<String, Watcher>()
    private var keepAlive: ScheduledExecutorService? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val c = container
        // Follows the accounts and settings: an account added, removed or changed gets its connection (re)made.
        scope.launch {
            combine(c.accounts.accounts, c.settings.stored) { accounts, s ->
                if (IdleControl.wanted(s)) accounts.filter { it.notify }.map { it.id to listOf(it.imap, it.trustedCerts, it.secretEnc).hashCode() } else null
            }.distinctUntilChanged().collect { wanted ->
                if (wanted == null) {
                    stopSelf()
                } else {
                    sync(wanted.toMap())
                }
            }
        }
        keepAlive = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "idle-keepalive").apply { isDaemon = true } }.also { exec ->
            exec.scheduleWithFixedDelay({ current().forEach { it.poke() } }, KEEP_ALIVE_MIN, KEEP_ALIVE_MIN, TimeUnit.MINUTES)
        }
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        // Restarted by Android after it had to stop MailCtrl: picks up where it was.
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        keepAlive?.shutdownNow()
        keepAlive = null
        synchronized(watchers) {
            watchers.values.forEach { it.stop() }
            watchers.clear()
        }
        scope.cancel()
        IdleControl.clear()
        super.onDestroy()
    }

    private fun goForeground() {
        // Made here too, so the notification always has its channel (it's only ever made once).
        runCatching { container.notifier.createIdleChannel() }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_INBOX).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, Notifier.CH_IDLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.idle_notification))
            .setContentText(getString(R.string.idle_notification_body))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            // Android refused (started from the background without leave to): no open connection, checks carry on.
            .onFailure { stopSelf() }
    }

    private fun current(): List<Watcher> = synchronized(watchers) { watchers.values.toList() }

    /** One watcher per wanted account; a changed account (server, password) gets a new one. */
    private fun sync(wanted: Map<String, Int>) {
        synchronized(watchers) {
            watchers.entries.filter { (id, w) -> wanted[id] != w.signature }.forEach { (id, w) ->
                w.stop()
                watchers.remove(id)
                IdleControl.set(id, null)
            }
            wanted.forEach { (id, signature) ->
                if (id !in watchers) watchers[id] = Watcher(id, signature).also { it.start() }
            }
        }
    }

    /** The phone moved to another network (or got one back): the old connections are dead, so start over now. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var last: Network? = null

            override fun onAvailable(network: Network) {
                val before = last
                last = network
                if (before != network) keepAlive?.execute { current().forEach { it.reconnectNow() } }
            }

            override fun onLost(network: Network) {
                if (network == last) last = null
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
    }

    /** Keeps one account's connection open, on a thread of its own (the mail library's calls block). */
    private inner class Watcher(val accountId: String, val signature: Int) {
        @Volatile private var running = true
        @Volatile private var link: MailClient.IdleLink? = null
        private val pause = Object()
        private val thread = Thread({ loop() }, "idle-watch").apply { isDaemon = true }

        fun start() = thread.start()

        fun stop() {
            running = false
            closeLink()
            wake()
        }

        /** Asks the server for a sign of life; a connection that doesn't answer is closed, and made again. */
        fun poke() {
            val l = link ?: return
            runCatching { l.poke() }.onFailure { closeLink() }
        }

        fun reconnectNow() {
            closeLink()
            wake()
        }

        private fun closeLink() {
            val l = link
            link = null
            // Signing out is network traffic and can wait on a dead connection: never on the caller's thread.
            if (l != null) Thread({ l.close() }, "idle-close").apply { isDaemon = true }.start()
        }

        private fun wake() {
            synchronized(pause) { pause.notifyAll() }
        }

        private fun sleep(ms: Long) {
            synchronized(pause) { runCatching { pause.wait(ms) } }
        }

        private fun online(): Boolean = getSystemService(ConnectivityManager::class.java)?.activeNetwork != null

        private fun loop() {
            val c = container
            var backoff = FIRST_RETRY_MS
            while (running) {
                try {
                    // Nothing to sign in with right now: no network, or the passwords are sealed while locked.
                    if (!online() || c.backgroundBlocked()) {
                        IdleControl.set(accountId, IdleState.RETRYING)
                        sleep(WAIT_MS)
                        continue
                    }
                    IdleControl.set(accountId, IdleState.CONNECTING)
                    val l = c.mail.openIdle(accountId)
                    if (!running) {
                        l.close()
                        break
                    }
                    if (!l.supported) {
                        l.close()
                        IdleControl.set(accountId, IdleState.UNSUPPORTED)
                        return
                    }
                    link = l
                    backoff = FIRST_RETRY_MS
                    IdleControl.set(accountId, IdleState.WAITING)
                    // Mail that came in while there was no connection.
                    NotifySchedule.checkNow(applicationContext, push = false)
                    var known = l.count()
                    while (running && link === l) {
                        l.idle()
                        val now = l.count()
                        // More messages than before: MailCtrl's normal check finds and announces what's new. The
                        // phone is kept awake for the few seconds until that check has started.
                        if (now > known) {
                            runCatching {
                                getSystemService(PowerManager::class.java)
                                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mailctrl:idle")
                                    ?.acquire(WAKE_MS)
                            }
                            NotifySchedule.checkNow(applicationContext, push = false)
                        }
                        known = now
                    }
                } catch (e: Throwable) {
                    closeLink()
                    if (!running) break
                    if (e is AuthException || e is PasswordNeededException) {
                        // Trying a refused password again and again gets accounts blocked.
                        IdleControl.set(accountId, IdleState.REFUSED)
                        return
                    }
                    IdleControl.set(accountId, IdleState.RETRYING)
                    sleep(if (e is LockedException) WAIT_MS else backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 0x1D1E
        const val KEEP_ALIVE_MIN = 10L
        const val FIRST_RETRY_MS = 15_000L
        const val MAX_RETRY_MS = 15 * 60_000L
        const val WAIT_MS = 60_000L
        const val WAKE_MS = 15_000L
    }
}
