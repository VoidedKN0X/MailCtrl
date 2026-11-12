package com.vknxworks.mailctrl.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vknxworks.mailctrl.PendingAction
import com.vknxworks.mailctrl.R
import com.vknxworks.mailctrl.container
import com.vknxworks.mailctrl.ui.accounts.AccountSetupScreen
import com.vknxworks.mailctrl.ui.compose.ComposeScreen
import com.vknxworks.mailctrl.ui.compose.DraftsScreen
import com.vknxworks.mailctrl.ui.components.CtrlBottomBar
import com.vknxworks.mailctrl.ui.components.CtrlNavItem
import com.vknxworks.mailctrl.ui.components.LocalBottomClearance
import com.vknxworks.mailctrl.ui.folders.FoldersScreen
import com.vknxworks.mailctrl.ui.inbox.FolderScreen
import com.vknxworks.mailctrl.ui.inbox.InboxScreen
import com.vknxworks.mailctrl.ui.message.MessageScreen
import com.vknxworks.mailctrl.ui.search.SearchScreen
import com.vknxworks.mailctrl.ui.settings.NotificationsScreen
import com.vknxworks.mailctrl.ui.settings.SettingsScreen
import com.vknxworks.mailctrl.ui.settings.YouScreen
import com.vknxworks.mailctrl.ui.theme.CtrlColors

@Composable
fun AppRoot() {
    val c = LocalContext.current.container
    val accounts by c.accounts.accounts.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // No account yet: adding the first one is the start screen.
        if (accounts.isEmpty()) AccountSetupScreen(accountId = null, first = true) else MainScaffold()
    }
}

private data class TopDest(val route: Any, val item: CtrlNavItem)

private val topDestinations = listOf(
    TopDest(InboxRoute, CtrlNavItem(R.string.tab_inbox, Icons.Outlined.Inbox)),
    TopDest(FoldersRoute, CtrlNavItem(R.string.tab_folders, Icons.Outlined.FolderOpen)),
    TopDest(SearchRoute, CtrlNavItem(R.string.tab_search, Icons.Outlined.Search)),
)

@Composable
private fun MainScaffold() {
    val c = LocalContext.current.container
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    val backStack by navController.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val selectedIndex = topDestinations.indexOfFirst { top -> dest?.hierarchy?.any { it.hasRoute(top.route::class) } == true }

    fun selectTab(i: Int) {
        navController.navigate(topDestinations[i].route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Notifications, mailto: links and shares from other apps.
    val pending by c.pendingAction.collectAsStateWithLifecycle()
    LaunchedEffect(pending, backStack != null) {
        val action = pending ?: return@LaunchedEffect
        if (backStack == null) return@LaunchedEffect
        c.pendingAction.value = null
        when (action) {
            is PendingAction.OpenMessage -> navigator.openMessageFresh(action.accountId, action.folder, action.uid)
            PendingAction.OpenInbox -> selectTab(0)
            is PendingAction.Compose -> {
                c.composeRequest.value = action
                navigator.composeFresh(ComposeRoute(fromRequest = true))
            }
        }
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (maxWidth >= 600.dp) {
                // Tablets and unfolded foldables: a side rail.
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding(),
                    ) {
                        Spacer(Modifier.weight(1f))
                        topDestinations.forEachIndexed { i, top ->
                            NavigationRailItem(
                                selected = i == selectedIndex,
                                onClick = { selectTab(i) },
                                icon = { Icon(top.item.icon, contentDescription = stringResource(top.item.label), modifier = Modifier.size(20.dp)) },
                                label = { Text(stringResource(top.item.label)) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CtrlColors.OnAccent,
                                    selectedTextColor = CtrlColors.Text,
                                    indicatorColor = CtrlColors.Accent,
                                    unselectedIconColor = CtrlColors.Muted,
                                    unselectedTextColor = CtrlColors.Muted,
                                ),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding().imePadding()) { AppNavHost(navController) }
                }
            } else {
                PhoneLayout(navController, showTabs = selectedIndex >= 0, selectedIndex = selectedIndex, onSelect = ::selectTab)
            }
        }
    }
}

/**
 * Phones: content runs edge to edge and scrolls under the floating Ctrl bottom bar.
 * The bar shows on the main tabs; pushed screens get the full height.
 */
@Composable
private fun PhoneLayout(
    navController: NavHostController,
    showTabs: Boolean,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val density = LocalDensity.current
    var overlayHeight by remember { mutableStateOf(0.dp) }

    CompositionLocalProvider(LocalBottomClearance provides if (showTabs) overlayHeight else 0.dp) {
        Box(Modifier.fillMaxSize()) {
            // Pushed screens stop above the navigation bar and the keyboard; the tabs scroll under the bar.
            Box(Modifier.fillMaxSize().then(if (showTabs) Modifier else Modifier.navigationBarsPadding().imePadding())) {
                AppNavHost(navController)
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { overlayHeight = with(density) { it.height.toDp() } }
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(
                    visible = showTabs,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                ) {
                    CtrlBottomBar(items = topDestinations.map { it.item }, selectedIndex = selectedIndex, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(navController, startDestination = InboxRoute) {
        composable<InboxRoute> { InboxScreen() }
        composable<FoldersRoute> { FoldersScreen() }
        composable<SearchRoute> { SearchScreen() }

        composable<FolderRoute> {
            val r = it.toRoute<FolderRoute>()
            FolderScreen(r.accountId, r.folder)
        }
        composable<MessageRoute> {
            val r = it.toRoute<MessageRoute>()
            MessageScreen(r.accountId, r.folder, r.uid)
        }
        composable<ComposeRoute> { ComposeScreen(it.toRoute<ComposeRoute>()) }
        composable<DraftsRoute> { DraftsScreen() }

        composable<YouRoute> { YouScreen() }
        composable<SettingsRoute> { SettingsScreen() }
        composable<NotificationsRoute> { NotificationsScreen() }
        composable<AccountSetupRoute> { AccountSetupScreen(it.toRoute<AccountSetupRoute>().accountId, first = false) }
    }
}
