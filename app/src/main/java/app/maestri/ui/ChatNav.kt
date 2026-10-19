package app.maestri.ui

import android.net.Uri
import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.maestri.ui.screens.ChatThreadScreen
import app.maestri.wire.session.WireSession

/**
 * Chat lane routes (P2). One destination: the chat face of a terminal.
 *
 * Registration is ADDITIVE in the app Root: after the terminal composable,
 * call `chatDestinations(nav, session)` (see INTEGRATION in this spec dir).
 *
 * The standalone `live` route is retired: the Pi relay feed is a view mode of
 * TerminalScreen (Terminal | Chat switch, Chat renders LiveFeedContent), so no
 * separate Live screen exists anymore. Settings' Live row deep-links into the
 * last-opened terminal already in Chat mode. The LiveNav constant is gone with
 * it; a stale persisted `live` route fails validation and falls back to
 * Workspaces (see validatedRestoreRoute).
 */
object ChatNav {
    const val pattern = "chat/{wsId}/{terminalId}/{terminalName}"

    /** URL-encode every segment; terminal names are opaque (MainActivity convention). */
    fun route(wsId: String, terminalId: String, terminalName: String): String =
        "chat/" + Uri.encode(wsId) + "/" + Uri.encode(terminalId) + "/" + Uri.encode(terminalName)

    fun enter(nav: NavHostController, wsId: String, terminalId: String, terminalName: String) {
        nav.navigate(route(wsId, terminalId, terminalName))
    }
}

fun NavGraphBuilder.chatDestinations(session: WireSession, nav: NavHostController) {
    composable(
        route = ChatNav.pattern,
        arguments = listOf(
            navArgument("wsId") { type = NavType.StringType },
            navArgument("terminalId") { type = NavType.StringType },
            navArgument("terminalName") { type = NavType.StringType },
        ),
    ) { entry ->
        val wsId = entry.arguments?.getString("wsId") ?: return@composable
        val terminalId = entry.arguments?.getString("terminalId") ?: return@composable
        val terminalName = Uri.decode(entry.arguments?.getString("terminalName") ?: "terminal")
        ChatThreadScreen(
            session = session,
            wsId = wsId,
            terminalId = terminalId,
            terminalName = terminalName,
            onBack = { nav.popBackStack() },
        )
    }
}