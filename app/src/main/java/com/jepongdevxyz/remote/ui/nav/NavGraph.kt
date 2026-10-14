package com.jepongdevxyz.remote.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jepongdevxyz.remote.core.SessionCoordinator
import com.jepongdevxyz.remote.core.SessionRole
import com.jepongdevxyz.remote.core.SessionState
import com.jepongdevxyz.remote.ui.screens.ChatScreen
import com.jepongdevxyz.remote.ui.screens.ConnectScreen
import com.jepongdevxyz.remote.ui.screens.DevicesScreen
import com.jepongdevxyz.remote.ui.screens.FilesScreen
import com.jepongdevxyz.remote.ui.screens.HistoryScreen
import com.jepongdevxyz.remote.ui.screens.HomeScreen
import com.jepongdevxyz.remote.ui.screens.IncomingScreen
import com.jepongdevxyz.remote.ui.screens.PermissionsScreen
import com.jepongdevxyz.remote.ui.screens.ProfileScreen
import com.jepongdevxyz.remote.ui.screens.QrScanScreen
import com.jepongdevxyz.remote.ui.screens.RemoteScreen
import com.jepongdevxyz.remote.ui.screens.SharingScreen
import com.jepongdevxyz.remote.ui.screens.SplashScreen
import com.jepongdevxyz.remote.ui.screens.WelcomeScreen

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val PERMISSIONS = "permissions"
    const val HOME = "home"
    const val CONNECT = "connect"
    const val QRSCAN = "qrscan"
    const val INCOMING = "incoming"
    const val REMOTE = "remote"
    const val SHARING = "sharing"
    const val FILES = "files"
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val DEVICES = "devices"
    const val PROFILE = "profile"
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val sessionState by SessionCoordinator.state.collectAsState()
    var lastHandled by remember { mutableStateOf<SessionState?>(null) }

    // Session-driven navigation: incoming calls and active sessions surface
    // automatically; returning to idle pops back to Home.
    LaunchedEffect(sessionState) {
        val s = sessionState
        when (s) {
            is SessionState.Incoming -> {
                if (lastHandled !is SessionState.Incoming) {
                    lastHandled = s
                    navController.navigate(Routes.INCOMING) { launchSingleTop = true }
                }
            }
            is SessionState.Active -> {
                if (lastHandled !is SessionState.Active) {
                    lastHandled = s
                    val dest = if (s.role == SessionRole.CONTROLLER) Routes.REMOTE else Routes.SHARING
                    navController.navigate(dest) { launchSingleTop = true }
                }
            }
            is SessionState.Idle -> {
                val prev = lastHandled
                lastHandled = s
                if (prev is SessionState.Incoming || prev is SessionState.Active || prev is SessionState.Dialing) {
                    if (!navController.popBackStack(Routes.HOME, false)) {
                        navController.navigate(Routes.HOME)
                    }
                }
            }
            else -> {
                lastHandled = s
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) { SplashScreen(navController) }
        composable(Routes.WELCOME) { WelcomeScreen(navController) }
        composable(Routes.PERMISSIONS) { PermissionsScreen(navController) }
        composable(Routes.HOME) { HomeScreen(navController) }
        composable(Routes.CONNECT) { ConnectScreen(navController) }
        composable(Routes.QRSCAN) { QrScanScreen(navController) }
        composable(Routes.INCOMING) { IncomingScreen(navController) }
        composable(Routes.REMOTE) { RemoteScreen(navController) }
        composable(Routes.SHARING) { SharingScreen(navController) }
        composable(Routes.FILES) { FilesScreen(navController) }
        composable(Routes.CHAT) { ChatScreen(navController) }
        composable(Routes.HISTORY) { HistoryScreen(navController) }
        composable(Routes.DEVICES) { DevicesScreen(navController) }
        composable(Routes.PROFILE) { ProfileScreen(navController) }
    }
}
