package com.loglab.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.loglab.app.ui.capture.CaptureScreen
import com.loglab.app.ui.connect.ConnectScreen
import com.loglab.app.ui.crash.CrashScreen
import com.loglab.app.ui.export.ExportScreen
import com.loglab.app.ui.guide.GuideScreen
import com.loglab.app.ui.logview.LogViewScreen
import com.loglab.app.ui.settings.SettingsScreen
import com.loglab.app.ui.tail.TailScreen

private data class Destination(val route: String, val label: String, val icon: ImageVector)

/** 底部导航只保留 4 个高频入口；导出并入抓取页顶栏保存按钮，运行日志从设置页进入 */
private val destinations = listOf(
    Destination("capture", "抓取", Icons.AutoMirrored.Filled.ListAlt),
    Destination("crash", "崩溃", Icons.Filled.BugReport),
    Destination("tail", "实时", Icons.Filled.Stream),
    Destination("settings", "设置", Icons.Filled.Settings)
)

private val topRoutes = destinations.map { it.route }.toSet()

@Composable
fun LogcatApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        // 顶栏由各页面自带的 TopAppBar 处理状态栏避让（enableEdgeToEdge 下不重叠）；
        // 这里只负责底部导航，insets 归零避免双重 padding
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentRoute in topRoutes) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "capture",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            composable("capture") {
                CaptureScreen(
                    onGoConnect = { navController.navigate("connect") },
                    onGoGuide = { navController.navigate("guide") },
                    onGoExport = { navController.navigate("export") }
                )
            }
            composable("crash") { CrashScreen() }
            composable("tail") { TailScreen(onGoConnect = { navController.navigate("connect") }) }
            composable("settings") {
                SettingsScreen(onGoLogView = { navController.navigate("logview") })
            }
            // 二级页：无底部导航，顶栏返回
            composable("export") { ExportScreen(onBack = { navController.popBackStack() }) }
            composable("logview") { LogViewScreen(onBack = { navController.popBackStack() }) }
            composable("connect") { ConnectScreen(onBack = { navController.popBackStack() }) }
            composable("guide") { GuideScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

@Composable
fun AppTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}
