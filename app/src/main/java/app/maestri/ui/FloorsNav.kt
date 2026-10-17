package app.maestri.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.maestri.ui.screens.ChangesScreen
import app.maestri.ui.screens.DiffScreen
import app.maestri.ui.screens.FileScreen
import app.maestri.ui.screens.FileTreeScreen
import app.maestri.ui.screens.FloorOverviewScreen
import app.maestri.ui.screens.FloorsScreen
import app.maestri.wire.FloorsApi
import app.maestri.wire.session.WireSession

/**
 * Floors + git file-tree navigation (P3). Install once inside the app's NavHost:
 *
 *     NavHost(...) {
 *         // lane routes (workspaces/feed/canvas/terminal stay as they are)
 *         FloorsNav.installFloorsRoutes(nav, session, nav::navigate)
 *     }
 *
 * Entry points the merger wires (Workspaces/Feed are lane-frozen for P3):
 * from Feed/Workspaces, navigate(`floors/${encp(wsId)}/${encp(wsName)}`).
 *
 * File-tree paths go through route args; they are DOUBLE-encoded because the nav library
 * decodes arguments once (a single-encoded '/' would split the argument into segments).
 */

private const val WS = "{wsId}"
private const val WS_NAME = "{wsName}"

object FloorsNav {
    val floors = "floors/$WS/$WS_NAME"
    val floorOverview = "floors/$WS/$WS_NAME/floor/{floorIdOrGround}"
    val fileTree = "floors/$WS/$WS_NAME/files/{rootPath}"
    val file = "floors/$WS/$WS_NAME/files/{rootPath}/file/{relPath}"
    val changes = "floors/$WS/$WS_NAME/changes/{rootPath}"
    val diff = "floors/$WS/$WS_NAME/changes/{rootPath}/diff/{path}/{scope}"

    /** Paths are opaque (may contain '/': double-encode). */
    fun encp(value: String): String = android.net.Uri.encode(android.net.Uri.encode(value))
    fun decp(value: String): String = android.net.Uri.decode(value)

    fun link(wsId: String, wsName: String): String = "floors/${encp(wsId)}/${encp(wsName)}"

    /**
     * Registers the six floors routes. `nav` needs navigate for the deep hops between
     * the trees; onOverviewBack pops to the list.
     */
    fun installFloorsRoutes(
        nav: NavGraphBuilder,
        session: WireSession,
        navigate: (String) -> Unit,
        pop: () -> Unit,
    ) {
        nav.composable(
            route = floors,
            arguments = listOf(
                navArgument("wsId") { type = NavType.StringType },
                navArgument("wsName") { type = NavType.StringType },
            ),
        ) { entry ->
            val wsId = entry.arguments?.getString("wsId") ?: return@composable
            val wsName = decp(entry.arguments?.getString("wsName") ?: "workspace")
            val api = FloorsApi(session)   // one per open; stateless over the shared session/client
            FloorsScreen(api, wsId, wsName, onBack = pop, onOpenOverview = { floorId, _ ->
                navigate("floors/${encp(wsId)}/${encp(wsName)}/floor/${encp(floorId)}")
            })
        }
        nav.composable(
            route = floorOverview,
            arguments = listOf(
                navArgument("wsId") { type = NavType.StringType },
                navArgument("wsName") { type = NavType.StringType },
                navArgument("floorIdOrGround") { type = NavType.StringType },
            ),
        ) { entry ->
            val wsId = entry.arguments?.getString("wsId") ?: return@composable
            val wsName = decp(entry.arguments?.getString("wsName") ?: "workspace")
            val floorId = decp(entry.arguments?.getString("floorIdOrGround") ?: "ground")
            val api = FloorsApi(session)
            // FileTree roots: an isolated floor browses its own clone folder (feed row's
            // `folder`); ground and simple floors browse the workspace directory — resolved
            // inside the screens via FloorsApi.workingDirectory (behavior-CANDIDATE).
            FloorOverviewScreen(
                api = api,
                wsId = wsId,
                wsName = wsName,
                floorIdOrGround = floorId,
                onBack = pop,
                onOpenFiles = { root ->
                    navigate("floors/${encp(wsId)}/${encp(wsName)}/files/${encp(root)}")
                },
                onOpenChanges = { root ->
                    navigate("floors/${encp(wsId)}/${encp(wsName)}/changes/${encp(root)}")
                },
            )
        }
        nav.composable(
            route = fileTree,
            arguments = listOf(
                navArgument("wsId") { type = NavType.StringType },
                navArgument("wsName") { type = NavType.StringType },
                navArgument("rootPath") { type = NavType.StringType },
            ),
        ) { entry ->
            val wsId = entry.arguments?.getString("wsId") ?: return@composable
            val wsName = decp(entry.arguments?.getString("wsName") ?: "workspace")
            val root = decp(entry.arguments?.getString("rootPath") ?: "/")
            val api = FloorsApi(session)
            FileTreeScreen(
                api = api,
                wsId = wsId,
                wsName = wsName,
                rootPath = root,
                onBack = pop,
                onOpenFile = { rel ->
                    navigate("floors/${encp(wsId)}/${encp(wsName)}/files/${encp(root)}/file/${encp(rel)}")
                },
                onOpenChanges = {
                    navigate("floors/${encp(wsId)}/${encp(wsName)}/changes/${encp(root)}")
                },
            )
        }
        nav.composable(
            route = file,
            arguments = listOf(
                navArgument("wsId") { type = NavType.StringType },
                navArgument("wsName") { type = NavType.StringType },
                navArgument("rootPath") { type = NavType.StringType },
                navArgument("relPath") { type = NavType.StringType },
            ),
        ) { entry ->
            val wsId = entry.arguments?.getString("wsId") ?: return@composable
            val root = decp(entry.arguments?.getString("rootPath") ?: "/")
            val rel = decp(entry.arguments?.getString("relPath") ?: "")
            val api = FloorsApi(session)
            FileScreen(api, wsId, root, rel, onBack = pop)
        }
        nav.composable(
            route = changes,
            arguments = listOf(
                navArgument("wsId") { type = NavType.StringType },
                navArgument("wsName") { type = NavType.StringType },
                navArgument("rootPath") { type = NavType.StringType },
            ),
        ) { entry ->
            val wsId = entry.arguments?.getString("wsId") ?: return@composable
            val wsName = decp(entry.arguments?.getString("wsName") ?: "workspace")
            val root = decp(entry.arguments?.getString("rootPath") ?: "/")
            val api = FloorsApi(session)
            ChangesScreen(
                api = api,
                wsId = wsId,
                wsName = wsName,
                rootPath = root,
                onBack = pop,
                onOpenDiff = { path, scope ->
                    navigate("floors/${encp(wsId)}/${encp(wsName)}/changes/${encp(root)}/diff/${encp(path)}/${encp(scope)}")
                },
            )
        }
        nav.composable(
            route = diff,
            arguments = listOf(
                navArgument("wsId") { type = NavType.StringType },
                navArgument("wsName") { type = NavType.StringType },
                navArgument("rootPath") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType },
                navArgument("scope") { type = NavType.StringType },
            ),
        ) { entry ->
            val wsId = entry.arguments?.getString("wsId") ?: return@composable
            val root = decp(entry.arguments?.getString("rootPath") ?: "/")
            val rel = decp(entry.arguments?.getString("path") ?: "")
            val scope = decp(entry.arguments?.getString("scope") ?: "unstaged")
            val api = FloorsApi(session)
            DiffScreen(api, wsId, apiNode(root), rel, scope, onBack = pop)
        }
    }

}

private fun apiNode(root: String): String = app.maestri.wire.FileTreeNodeCache.get(root) ?: ""

