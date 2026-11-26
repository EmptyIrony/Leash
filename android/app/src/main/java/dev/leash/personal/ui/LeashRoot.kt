package dev.leash.personal.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.leash.personal.core.AppModel
import dev.leash.personal.ui.chat.ChatScreen
import dev.leash.personal.ui.chat.SubagentScreen
import dev.leash.personal.ui.files.ProjectFolderScreen
import dev.leash.personal.ui.list.FolderSessionsScreen
import dev.leash.personal.ui.list.SessionListScreen
import dev.leash.personal.ui.newsession.FolderBrowserScreen
import dev.leash.personal.ui.newsession.NewSessionController
import dev.leash.personal.ui.newsession.NewSessionScreen
import dev.leash.personal.ui.settings.ScannerScreen
import dev.leash.personal.ui.settings.SettingsScreen
import dev.leash.personal.ui.theme.LeashTheme

private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name).orEmpty()
private fun enc(s: String) = Uri.encode(s)

/** The screens and the way between them: list, folder, conversation, subagent, new conversation and its folder browser, project files, settings, scanner. */
@Composable
fun LeashRoot(model: AppModel) {
    val scheme = MaterialTheme.colorScheme
    val colors = LeashTheme.colors
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsStateWithLifecycle()
    val newSession = remember { NewSessionController(model, scope) }

    // a notification tap or a leash://session link: show that conversation with the list under it, so Back always leads to the list
    val request = state.openRequest
    LaunchedEffect(request) {
        if (request != null) {
            model.openHandled(request)
            if (model.visibleSessionId != request.sessionId) nav.openChat(request.sessionId)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.background, colors.bgGradientEnd)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width, 0f), radius = 320.dp.toPx()),
                )
            },
    ) {
        val push: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition = { slideInHorizontally(tween(280)) { it / 5 } + fadeIn(tween(220)) }
        val pop: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition = { slideOutHorizontally(tween(280)) { it / 5 } + fadeOut(tween(200)) }
        NavHost(
            nav, startDestination = "list", Modifier.fillMaxSize(),
            enterTransition = push, exitTransition = { fadeOut(tween(160)) }, popEnterTransition = { fadeIn(tween(220)) }, popExitTransition = pop,
        ) {
            composable("list") {
                SessionListScreen(
                    model,
                    onOpenChat = { nav.navigate("chat/" + enc(it)) },
                    onOpenFolder = { agent, folder -> nav.navigate("folder/" + enc(agent) + "/" + enc(folder)) },
                    onNew = { nav.navigate("new") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("folder/{agent}/{cwd}") { e ->
                FolderSessionsScreen(model, e.arg("agent"), e.arg("cwd"), onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate("chat/" + enc(it)) })
            }
            composable("chat/{id}") { e ->
                ChatScreen(
                    model, e.arg("id"), onBack = { nav.popBackStack() },
                    onOpenSubagent = { root, child -> nav.navigate("subagent/" + enc(root) + "/" + enc(child)) },
                    onBrowseFiles = { agent, cwd -> nav.navigate("project/" + enc(agent) + "/" + enc(cwd)) },
                )
            }
            composable("subagent/{root}/{child}") { e ->
                val root = e.arg("root")
                SubagentScreen(model, root, e.arg("child"), onBack = { nav.popBackStack() }, onOpenSubagent = { child -> nav.navigate("subagent/" + enc(root) + "/" + enc(child)) })
            }
            composable("new") {
                NewSessionScreen(model, newSession, onBack = { nav.popBackStack() }, onBrowse = { nav.navigate("browse") }, onCreated = { nav.openChat(it) })
            }
            composable("browse?path={path}", arguments = listOf(navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                val path = e.arguments?.getString("path")
                FolderBrowserScreen(
                    model, newSession, path, onBack = { nav.popBackStack() },
                    onOpenFolder = { nav.navigate("browse?path=" + enc(it)) }, onCreated = { nav.openChat(it) },
                )
            }
            composable("project/{agent}/{path}") { e ->
                val agent = e.arg("agent")
                ProjectFolderScreen(model, agent, e.arg("path"), onBack = { nav.popBackStack() }, onOpenFolder = { nav.navigate("project/" + enc(agent) + "/" + enc(it)) })
            }
            composable("settings") {
                SettingsScreen(model, onBack = { nav.popBackStack() }, onScan = { nav.navigate("scan") })
            }
            composable("scan") {
                ScannerScreen(onResult = { info -> model.pair(info); nav.popBackStack("list", inclusive = false) }, onBack = { nav.popBackStack() })
            }
        }
    }
}

/** Shows a conversation with the list under it (replacing whatever was stacked above the list). */
private fun NavController.openChat(id: String) {
    navigate("chat/" + enc(id)) { popUpTo("list") }
}
