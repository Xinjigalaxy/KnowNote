package com.xinjigalaxy.knownotes.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.xinjigalaxy.knownotes.ui.manage.GroupScreen
import com.xinjigalaxy.knownotes.ui.manage.TagScreen
import com.xinjigalaxy.knownotes.ui.more.ExportScreen
import com.xinjigalaxy.knownotes.ui.more.MoreScreen
import com.xinjigalaxy.knownotes.ui.more.SyncScreen
import com.xinjigalaxy.knownotes.ui.note.NoteEditScreen
import com.xinjigalaxy.knownotes.ui.note.NoteListScreen

object Routes {
    const val NOTES = "notes"
    const val GROUPS = "groups"
    const val TAGS = "tags"
    const val MORE = "more"
    const val EXPORT = "export"
    const val SYNC = "sync"

    /** 0 表示新建。 */
    const val EDIT_PATTERN = "edit?noteId={noteId}"
    fun edit(noteId: Long?): String = "edit?noteId=${noteId ?: 0L}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab(Routes.NOTES, "笔记", Icons.Outlined.StickyNote2, Icons.Filled.StickyNote2),
    Tab(Routes.GROUPS, "分组", Icons.Outlined.Folder, Icons.Filled.Folder),
    Tab(Routes.TAGS, "标签", Icons.Outlined.Sell, Icons.Filled.Sell),
    Tab(Routes.MORE, "更多", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

@Composable
fun KnowNoteRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TABS.any { it.route == currentRoute }

    Scaffold(
        // 内层页面各自带 TopAppBar 处理状态栏，这里只负责底部导航的高度
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.icon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.NOTES,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable(Routes.NOTES) {
                NoteListScreen(
                    onOpenNote = { id -> navController.navigate(Routes.edit(id)) },
                    onCreateNote = { navController.navigate(Routes.edit(null)) },
                )
            }
            composable(Routes.GROUPS) { GroupScreen() }
            composable(Routes.TAGS) { TagScreen() }
            composable(Routes.MORE) {
                MoreScreen(
                    onOpenExport = { navController.navigate(Routes.EXPORT) },
                    onOpenSync = { navController.navigate(Routes.SYNC) },
                )
            }
            composable(
                route = Routes.EDIT_PATTERN,
                arguments = listOf(
                    navArgument("noteId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    }
                ),
            ) { entry ->
                val rawId = entry.arguments?.getLong("noteId") ?: 0L
                NoteEditScreen(
                    noteId = rawId.takeIf { it > 0L },
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.EXPORT) { ExportScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SYNC) { SyncScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
