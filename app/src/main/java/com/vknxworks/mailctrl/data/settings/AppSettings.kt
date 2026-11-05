package com.vknxworks.mailctrl.data.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.mailctrl.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "mailctrl")

/** When the app lock asks again after you leave the app. */
enum class LockTimeout(@StringRes val label: Int, val millis: Long) {
    IMMEDIATELY(R.string.lock_again_immediately, 0L),
    MINUTE_1(R.string.lock_again_1m, 60_000L),
    MINUTES_5(R.string.lock_again_5m, 5 * 60_000L),
    MINUTES_15(R.string.lock_again_15m, 15 * 60_000L),
    HOUR_1(R.string.lock_again_1h, 60 * 60_000L),
    RESTART(R.string.lock_again_restart, -1L),
}

/** What a new-mail notification shows. */
enum class NotificationContent(@StringRes val label: Int) {
    SENDER_AND_SUBJECT(R.string.notify_content_all),
    SENDER_ONLY(R.string.notify_content_sender),
    NOTHING(R.string.notify_content_nothing),
}

/** Pictures and other content from the internet in messages. */
enum class RemoteContent(@StringRes val label: Int) {
    /** Blocked; a button loads them for one message. */
    ASK(R.string.remote_ask),
    /** Loaded straight away (senders can see that you opened their mail). */
    ALWAYS(R.string.remote_always),
}

/** What swiping a message in a list does. */
enum class SwipeAction(@StringRes val label: Int) {
    NONE(R.string.swipe_none),
    ARCHIVE(R.string.archive),
    DELETE(R.string.delete),
    READ(R.string.swipe_read),
    FLAG(R.string.swipe_flag),
}

data class AppSettings(
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    /** "Hide in screenshots": FLAG_SECURE on every window (also blanks the recent-apps preview). */
    val hideScreenshots: Boolean = false,
    /** "Hide in recent apps": the recent-apps view shows a blank card. Screenshots still work. */
    val hideRecents: Boolean = false,
    val notifyEnabled: Boolean = true,
    /** Minutes between background checks; 0 = only when ntfy wakes MailCtrl. */
    val notifyInterval: Int = 15,
    /**
     * What new-mail notifications show, as the user chose it; null when they never picked one. Use
     * [notificationContentFor]: without a choice, notifications only say "New mail" while the app lock is on.
     */
    val notificationContent: NotificationContent? = null,
    /** Wake-ups through ntfy (UnifiedPush). */
    val pushEnabled: Boolean = false,
    /** The push address ntfy gave MailCtrl. */
    val pushEndpoint: String? = null,
    val remoteContent: RemoteContent = RemoteContent.ASK,
    /** Opening a message marks it read. */
    val markReadOnOpen: Boolean = true,
    /** The Inbox tab shows every account's inbox together. */
    val unifiedInbox: Boolean = true,
    val swipeRight: SwipeAction = SwipeAction.READ,
    val swipeLeft: SwipeAction = SwipeAction.ARCHIVE,
    /** Ask before deleting from a message (lists use Undo instead). */
    val confirmDelete: Boolean = false,
    /** The account new messages are written from (id), or empty for the first one. */
    val defaultAccount: String = "",
) {
    /** The notification content in effect: the user's own choice, or the default for the app lock's state. */
    fun notificationContentFor(lockOn: Boolean): NotificationContent =
        notificationContent ?: if (lockOn) NotificationContent.NOTHING else NotificationContent.SENDER_AND_SUBJECT
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val hideScreenshots = booleanPreferencesKey("hide_screenshots")
        val hideRecents = booleanPreferencesKey("hide_recents")
        val notifyEnabled = booleanPreferencesKey("notify_enabled")
        val notifyInterval = intPreferencesKey("notify_interval")
        val notificationContent = stringPreferencesKey("notification_content")
        /** Set once the user picks a notification content themselves (0.1.4 and later). */
        val notificationContentChosen = booleanPreferencesKey("notification_content_chosen")
        val pushEnabled = booleanPreferencesKey("push_enabled")
        val pushEndpoint = stringPreferencesKey("push_endpoint")
        val remoteContent = stringPreferencesKey("remote_content")
        val markReadOnOpen = booleanPreferencesKey("mark_read_on_open")
        val unifiedInbox = booleanPreferencesKey("unified_inbox")
        val swipeRight = stringPreferencesKey("swipe_right")
        val swipeLeft = stringPreferencesKey("swipe_left")
        val confirmDelete = booleanPreferencesKey("confirm_delete")
        val defaultAccount = stringPreferencesKey("default_account")
    }

    val settings: StateFlow<AppSettings> = context.appDataStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** The stored settings, without [settings]' placeholder defaults at start-up. */
    val stored: Flow<AppSettings> = context.appDataStore.data.map { it.toSettings() }

    /** The stored settings now (waits for them to load at start-up). */
    suspend fun current(): AppSettings = stored.first()

    private fun Preferences.toSettings() = AppSettings(
        lockTimeout = enumOr(this[K.lockTimeout], LockTimeout.IMMEDIATELY),
        hideScreenshots = this[K.hideScreenshots] ?: false,
        hideRecents = this[K.hideRecents] ?: false,
        notifyEnabled = this[K.notifyEnabled] ?: true,
        notifyInterval = this[K.notifyInterval] ?: 15,
        notificationContent = storedNotificationContent(),
        pushEnabled = this[K.pushEnabled] ?: false,
        pushEndpoint = this[K.pushEndpoint],
        remoteContent = enumOr(this[K.remoteContent], RemoteContent.ASK),
        markReadOnOpen = this[K.markReadOnOpen] ?: true,
        unifiedInbox = this[K.unifiedInbox] ?: true,
        swipeRight = enumOr(this[K.swipeRight], SwipeAction.READ),
        swipeLeft = enumOr(this[K.swipeLeft], SwipeAction.ARCHIVE),
        confirmDelete = this[K.confirmDelete] ?: false,
        defaultAccount = this[K.defaultAccount] ?: "",
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appDataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.lockTimeout] = s.lockTimeout.name
            p[K.hideScreenshots] = s.hideScreenshots
            p[K.hideRecents] = s.hideRecents
            p[K.notifyEnabled] = s.notifyEnabled
            p[K.notifyInterval] = s.notifyInterval
            val content = s.notificationContent
            if (content != null) p[K.notificationContent] = content.name else p.remove(K.notificationContent)
            p[K.notificationContentChosen] = content != null
            p[K.pushEnabled] = s.pushEnabled
            val endpoint = s.pushEndpoint
            if (endpoint != null) p[K.pushEndpoint] = endpoint else p.remove(K.pushEndpoint)
            p[K.remoteContent] = s.remoteContent.name
            p[K.markReadOnOpen] = s.markReadOnOpen
            p[K.unifiedInbox] = s.unifiedInbox
            p[K.swipeRight] = s.swipeRight.name
            p[K.swipeLeft] = s.swipeLeft.name
            p[K.confirmDelete] = s.confirmDelete
            p[K.defaultAccount] = s.defaultAccount
        }
    }

    /**
     * The user's own choice, or null. Before 0.1.4 every settings change also saved this setting's default
     * ("Sender and subject"), so a stored value without the "chosen" flag only counts as a choice when it
     * differs from that default.
     */
    private fun Preferences.storedNotificationContent(): NotificationContent? {
        val stored = this[K.notificationContent]?.let { n -> NotificationContent.entries.firstOrNull { it.name == n } }
        return when (this[K.notificationContentChosen]) {
            true -> stored
            false -> null
            null -> stored?.takeIf { it != NotificationContent.SENDER_AND_SUBJECT }
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
