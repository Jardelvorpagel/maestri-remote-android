package app.maestri.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.maestri.ui.notes.NoteEditorScreen
import app.maestri.wire.session.WireSession

/**
 * P1b notes lane navigation — ONE extension for the merger to wire inside the NavHost block
 * of `MainActivity.Root` (this file is lane-owned; MainActivity itself stays untouched):
 *
 *     NavHost(navController = nav, startDestination = "workspaces") {
 *         // … existing destinations …
 *         noteEditorRoute(session /* = WireSession */, onBack = { nav.popBackStack() })
 *     }
 *
 * and, where a note is opened from (canvas lane's CanvasScreen call site), navigate with:
 *
 *     nav.navigate("note/${enc(wsId)}/$nodeId")
 *
 * or the [openNote] helper below. The route keeps only ids — the editor fetches the note
 * itself (cards never carry text), so there is nothing to pass through.
 */

/** Adds the full-screen note editor destination. Call once inside the NavHost block. */
fun NavGraphBuilder.noteEditorRoute(session: WireSession, onBack: () -> Unit) {
    composable(
        route = "note/{wsId}/{nodeId}",
        arguments = listOf(
            navArgument("wsId") { type = NavType.StringType },
            navArgument("nodeId") { type = NavType.StringType },
        ),
    ) { entry ->
        val wsId = entry.arguments?.getString("wsId") ?: return@composable
        val nodeId = entry.arguments?.getString("nodeId") ?: return@composable
        NoteEditorScreen(
            session = session,
            wsId = wsId,
            nodeId = nodeId,
            onBack = onBack,
        )
    }
}

/** Convenience for call sites: open the note editor (ids only — the editor fetches on open). */
fun androidx.navigation.NavController.openNote(wsId: String, nodeId: String) {
    navigate("note/$wsId/$nodeId")
}