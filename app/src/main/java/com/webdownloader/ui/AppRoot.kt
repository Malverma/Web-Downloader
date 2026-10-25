package com.webdownloader.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.webdownloader.ui.screens.BookScreen
import com.webdownloader.ui.screens.BrowserScreen
import com.webdownloader.ui.screens.DownloadsScreen
import com.webdownloader.ui.screens.LibraryScreen
import com.webdownloader.ui.screens.PickerScreen
import com.webdownloader.ui.screens.ReaderScreen
import com.webdownloader.ui.screens.SearchScreen
import com.webdownloader.ui.screens.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "Library", Icons.AutoMirrored.Filled.LibraryBooks),
    Tab("search", "Search", Icons.Default.Search),
    Tab("browse", "Browse", Icons.Default.Language),
    Tab("downloads", "Downloads", Icons.Default.Download),
    Tab("settings", "Settings", Icons.Default.Settings),
)

private fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.openBrowser(url: String) {
    navigate("browse?url=${Uri.encode(url)}") {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
    }
}

@Composable
fun AppRoot(sharedUrl: String?, onSharedUrlConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val tabRoute = route.substringBefore('?')
    val showBar = tabs.any { it.route == tabRoute }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            nav.openBrowser(sharedUrl)
            onSharedUrlConsumed()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tabRoute == tab.route,
                            onClick = { nav.openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = "library") {
                composable("library") {
                    LibraryScreen(
                        onOpenBook = { nav.navigate("book/$it") },
                        onBrowse = { nav.openTab("browse") },
                    )
                }
                composable("search") {
                    SearchScreen(onOpenSeries = { url -> nav.navigate("picker?url=${Uri.encode(url)}&select=") })
                }
                composable(
                    "browse?url={url}",
                    arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { backStack ->
                    BrowserScreen(
                        initialUrl = backStack.arguments?.getString("url"),
                        onAddSeries = { series, select ->
                            nav.navigate("picker?url=${Uri.encode(series)}&select=${Uri.encode(select.orEmpty())}")
                        },
                    )
                }
                composable(
                    "picker?url={url}&select={select}",
                    arguments = listOf(
                        navArgument("url") { type = NavType.StringType; defaultValue = "" },
                        navArgument("select") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { backStack ->
                    PickerScreen(
                        seriesUrl = backStack.arguments?.getString("url").orEmpty(),
                        selectUrl = backStack.arguments?.getString("select")?.takeIf { it.isNotBlank() },
                        onBack = { nav.popBackStack() },
                        onDone = { bookId ->
                            nav.popBackStack()
                            nav.navigate("book/$bookId")
                        },
                    )
                }
                composable("book/{bookId}", arguments = listOf(navArgument("bookId") { type = NavType.LongType })) { backStack ->
                    BookScreen(
                        bookId = backStack.arguments?.getLong("bookId") ?: 0,
                        onBack = { nav.popBackStack() },
                        onRead = { nav.navigate("reader/$it") },
                        onOpenUrl = { nav.openBrowser(it) },
                    )
                }
                composable("reader/{chapterId}", arguments = listOf(navArgument("chapterId") { type = NavType.LongType })) { backStack ->
                    ReaderScreen(
                        chapterId = backStack.arguments?.getLong("chapterId") ?: 0,
                        onBack = { nav.popBackStack() },
                        onOpenChapter = { id ->
                            nav.popBackStack()
                            nav.navigate("reader/$id")
                        },
                        onReadOnline = { nav.openBrowser(it) },
                    )
                }
                composable("downloads") { DownloadsScreen(onOpenBrowser = { nav.openBrowser(it) }) }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}
