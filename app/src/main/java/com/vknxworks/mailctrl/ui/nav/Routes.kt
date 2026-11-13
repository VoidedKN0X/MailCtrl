package com.vknxworks.mailctrl.ui.nav

import kotlinx.serialization.Serializable

// Tabs
@Serializable data object InboxRoute
@Serializable data object FoldersRoute
@Serializable data object SearchRoute

/** The messages in one folder of one account. */
@Serializable data class FolderRoute(val accountId: String, val folder: String)

/** One message. */
@Serializable data class MessageRoute(val accountId: String, val folder: String, val uid: Long)

/** Writing a message: new, an answer or a forward of [uid], or a saved draft. */
@Serializable data class ComposeRoute(
    val mode: String = ComposeMode.NEW,
    val accountId: String? = null,
    val folder: String? = null,
    val uid: Long = 0,
    val draftId: String? = null,
    /** Picks up the fields and files waiting in [com.vknxworks.mailctrl.AppContainer.composeRequest]. */
    val fromRequest: Boolean = false,
    /** Start writing to this address. */
    val to: String? = null,
)

object ComposeMode {
    const val NEW = "new"
    const val REPLY = "reply"
    const val REPLY_ALL = "reply_all"
    const val FORWARD = "forward"
    const val DRAFT = "draft"
}

@Serializable data object DraftsRoute
@Serializable data object YouRoute
@Serializable data object SettingsRoute
@Serializable data object NotificationsRoute

/** Adding an account, or changing one ([accountId]). */
@Serializable data class AccountSetupRoute(val accountId: String? = null)
