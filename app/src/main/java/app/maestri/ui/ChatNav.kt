package app.maestri.ui

import android.net.Uri
import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.maestri.live.LiveRoute
import app.maestri.ui.screens.ChatThreadScreen
import app.maestri.wire.session.WireSession

/**
 * Chat lane routes (P2). One destination: the chat face of a terminal.
 *
 * Registration is ADDITIVE in the app Root: after the terminal composable,
 * call `chatDestinations(nav, session)` (see INTEGRATION in this spec dir).
 */
object LiveNav {
    const val pattern = "live"
    fun route(): String = pattern
}

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
    composable(route = LiveNav.pattern) {
        LiveRoute(onBack = { nav.popBackStack() })
    }
}