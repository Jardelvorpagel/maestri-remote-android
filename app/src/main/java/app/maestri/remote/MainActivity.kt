package app.maestri.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.maestri.ui.MaestriTheme
import app.maestri.ui.screens.CanvasScreen
import app.maestri.ui.screens.FeedScreen
import app.maestri.ui.screens.PairingScreen
import app.maestri.ui.screens.TerminalScreen
import app.maestri.ui.chatDestinations
import app.maestri.ui.ChatNav
import app.maestri.ui.screens.WorkspacesScreen
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = (application as MaestriApp).session
        setContent {
            MaestriTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(session)
                }
            }
        }
    }
}

@Composable
fun Root(session: WireSession) {
    val paired by session.pairing.collectAsState()
    var authNotice by remember { mutableStateOf<String?>(null) }
    var reauth by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        session.authLost.collect {
            authNotice = it
            reauth = true
        }
    }
    LaunchedEffect(paired) {
        if (paired != null) reauth = false
    }

    when {
        paired == null || reauth -> PairingScreen(session, authNotice = authNotice, onNoticeShown = { authNotice = null })
        else -> {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "workspaces") {
                composable("workspaces") {
                    WorkspacesScreen(
                        session = session,
                        onOpenWorkspace = { wsId, wsName -> nav.navigate("feed/$wsId/${enc(wsName)}") },
                    )
                }
                composable(
                    route = "feed/{wsId}/{wsName}",
                    arguments = listOf(
                        navArgument("wsId") { type = NavType.StringType },
                        navArgument("wsName") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val wsId = entry.arguments?.getString("wsId") ?: return@composable
                    val wsName = entry.arguments?.getString("wsName") ?: "workspace"
                    FeedScreen(
                        session = session,
                        wsId = wsId,
                        wsName = wsName,
                        onBack = { nav.popBackStack() },
                        onOpenCanvas = { nav.navigate("canvas/${enc(wsId)}/${enc(wsName)}") },
                        onOpenTerminal = { terminalId, terminalName ->
                            nav.navigate("terminal/${enc(wsId)}/${enc(terminalId)}/${enc(terminalName)}")
                        },
                    )
                }
                composable(
                    route = "canvas/{wsId}/{wsName}",
                    arguments = listOf(
                        navArgument("wsId") { type = NavType.StringType },
                        navArgument("wsName") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val wsId = entry.arguments?.getString("wsId") ?: return@composable
                    val wsName = entry.arguments?.getString("wsName") ?: "workspace"
                    CanvasScreen(
                        session = session,
                        wsId = wsId,
                        wsName = wsName,
                        onBack = { nav.popBackStack() },
                        onOpenTerminal = { terminalId, terminalName ->
                            nav.navigate("terminal/${enc(wsId)}/${enc(terminalId)}/${enc(terminalName)}")
                        },
                    )
                }
                composable(
                    route = "terminal/{wsId}/{terminalId}/{terminalName}",
                    arguments = listOf(
                        navArgument("wsId") { type = NavType.StringType },
                        navArgument("terminalId") { type = NavType.StringType },
                        navArgument("terminalName") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val terminalId = entry.arguments?.getString("terminalId") ?: return@composable
                    val terminalName = dec(entry.arguments?.getString("terminalName") ?: "")
                    val wsId = entry.arguments?.getString("wsId") ?: ""
                    TerminalScreen(
                        session = session,
                        terminalId = terminalId,
                        terminalName = terminalName,
                        wsId = wsId,
                        onBack = { nav.popBackStack() },
                        onEnterChat = { chatTerminalId, chatTerminalName ->
                            nav.navigate(ChatNav.route(wsId, chatTerminalId, chatTerminalName))
                        },
                    )
                }
                chatDestinations(session, nav)
            }
        }
    }
}

/** Terminal names are opaque; URL-encode them for the nav route. */
fun enc(value: String): String = android.net.Uri.encode(value)

/** Decode a nav argument that was URL-encoded by [enc]. */
fun dec(value: String): String = android.net.Uri.decode(value)