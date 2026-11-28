package dev.leash.personal.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.TailnetStatus
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
import dev.leash.personal.ui.design.LocalReduceMotion
import dev.leash.personal.ui.design.MotionCurve
import dev.leash.personal.ui.design.SpaceBackground

private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name).orEmpty()
private fun enc(s: String) = Uri.encode(s)

/** The screens and the way between them: list, folder, conversation, subagent, new conversation and its folder browser, project files, settings, scanner. */
@Composable
fun LeashRoot(model: AppModel) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsStateWithLifecycle()
    val tailnetState = model.tailnet?.state?.collectAsStateWithLifecycle()?.value
    val newSession = remember { NewSessionController(model, scope) }
    val backEntry by nav.currentBackStackEntryAsState()
    val onSettingsFlow = backEntry?.destination?.route in setOf("settings", "scan")
    val ready = state.settingsLoaded && (tailnetState == null || tailnetState.loaded)
    val gateActive = ready && model.tailnet != null && !state.tailnetGateDismissed &&
        tailnetState?.enabled == true && state.isConfigured && !state.useDemo
    val showGate = gateActive && !onSettingsFlow

    // a notification tap or a leash://session link: show that conversation with the list under it, so Back always leads to the list
    val request = state.openRequest
    LaunchedEffect(request, ready, gateActive) {
        if (request != null && ready && !gateActive) {
            model.openHandled(request)
            if (model.visibleSessionId != request.sessionId) nav.openChat(request.sessionId)
        }
    }

    val reduced = LocalReduceMotion.current
    val layoutF = MotionCurve.Layout.spec<Float>(reduced, 0.001f)
    val contentAlpha by animateFloatAsState(if (!ready || showGate) 0f else 1f, layoutF, label = "rootAlpha")
    val contentScale by animateFloatAsState(if (!ready || showGate) (if (reduced) 1f else 0.96f) else 1f, layoutF, label = "rootScale")

    Box(Modifier.fillMaxSize()) {
        SpaceBackground()
        // push: the new screen slides in from the end 30% and fades in, the old one fades (DESIGN.md §7). Pop follows
        // the platform's predictive back: the outgoing screen shrinks to 0.9 and fades while the gesture scrubs it.
        val layoutI = MotionCurve.Layout.spec<IntOffset>(reduced, IntOffset(1, 1))
        val quickF = MotionCurve.Quick.spec<Float>(reduced, 0.001f)
        val push: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
            if (reduced) fadeIn(layoutF)
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, layoutI) { it * 3 / 10 } + fadeIn(layoutF)
        }
        val pushOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { fadeOut(quickF) }
        val popIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { fadeIn(layoutF) }
        val popOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            if (reduced) fadeOut(quickF) else scaleOut(layoutF, targetScale = 0.9f) + fadeOut(layoutF)
        }
        NavHost(
            nav, startDestination = "list",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = contentAlpha
                    scaleX = contentScale
                    scaleY = contentScale
                }
                .then(if (!ready || showGate) Modifier.semantics { invisibleToUser() } else Modifier),
            enterTransition = push, exitTransition = pushOut, popEnterTransition = popIn, popExitTransition = popOut,
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
                    onOpenChat = { nav.openChat(it) },
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
        AnimatedVisibility(
            visible = showGate,
            enter = fadeIn(layoutF),
            exit = if (reduced) fadeOut(layoutF) else scaleOut(layoutF, targetScale = 1.04f) + fadeOut(layoutF),
        ) {
            TailnetBootGate(
                status = tailnetState?.status ?: TailnetStatus.Stopped,
                onRetry = { model.retryTailnet() },
                onOpenSettings = { nav.navigate("settings") },
                onContinueAnyway = { model.dismissTailnetGate() },
                onConnectedDone = { model.dismissTailnetGate() },
            )
        }
    }
}

/** Shows a conversation with the list under it (replacing whatever was stacked above the list). */
private fun NavController.openChat(id: String) {
    navigate("chat/" + enc(id)) { popUpTo("list") }
}
