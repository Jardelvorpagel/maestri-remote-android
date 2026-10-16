package app.maestri.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.maestri.ui.FloorsNav
import app.maestri.ui.screens.CanvasScreen
import app.maestri.ui.screens.FeedScreen
import app.maestri.ui.screens.PairingScreen
import app.maestri.ui.screens.TerminalScreen
import app.maestri.ui.chatDestinations
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.maestri.ui.ChatNav
import app.maestri.wire.NotificationsRouter
import app.maestri.ui.notificationsDestination
import app.maestri.ui.screens.WorkspacesScreen
import app.maestri.wire.DeepLinkBus
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // P4-A: a notification tap that COLD-STARTS the app lands its deep link here.
        intent?.getStringExtra(NotificationsRouter.EXTRA_DEEPLINK)?.let { DeepLinkBus.routes.tryEmit(it) }
        val session = (application as MaestriApp).session
        setContent {
            MaestriTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(session)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // P4-A: a notification tap that WARMS an existing task lands its deep link here.
        intent.getStringExtra(NotificationsRouter.EXTRA_DEEPLINK)?.let { DeepLinkBus.routes.tryEmit(it) }
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
            // ---- P4-A: foreground-WSS notification router (option A; the ONLY additive wiring)
            val activityContext = LocalContext.current
            val hookScope = rememberCoroutineScope()
            val appForeground = remember { MutableStateFlow(false) }
            val router = remember {
                NotificationsRouter(session, activityContext.applicationContext, appForeground)
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> router.onPermissionResult(granted) }
            LaunchedEffect(Unit) {
                launch {
                    router.permissionNudge.collect { // ask ONCE, at the first natural moment
                        if (!router.permissionAsked()) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                launch {
                    DeepLinkBus.routes.collect { route ->
                        runCatching { nav.navigate(route) }
                    }
                }
            }
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    appForeground.value = event == Lifecycle.Event.ON_RESUME
                }
                (activityContext as? ComponentActivity)?.lifecycle?.addObserver(observer)
                router.start()
                onDispose {
                    (activityContext as? ComponentActivity)?.lifecycle?.removeObserver(observer)
                    router.stop()
                }
            }
            NavHost(navController = nav, startDestination = "workspaces") {
                composable("workspaces") {
                    WorkspacesScreen(
                        session = session,
                        onOpenWorkspace = { wsId, wsName -> nav.navigate("feedWithFloors/$wsId/${enc(wsName)}") },
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
                notificationsDestination(router)
                // ── P3 floors lane ────────────────────────────────────────────────────
                // LANES MERGER: this temp "Floors" affordance (FAB inside the feed below)
                // exists only because FeedScreen is lane-frozen this wave; the proper
                // entry is the merger's floor-chips wiring (WorkspacesScreen/FeedScreen),
                // which should navigate via FloorsNav.link(wsId, wsName).
                FloorsNav.installFloorsRoutes(
                    nav = this,
                    session = session,
                    navigate = { r -> nav.navigate(r) },
                    pop = { nav.popBackStack() },
                )
                composable(
                    route = "feedWithFloors/{wsId}/{wsName}",
                    arguments = listOf(
                        navArgument("wsId") { type = NavType.StringType },
                        navArgument("wsName") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val wsId = entry.arguments?.getString("wsId") ?: return@composable
                    val wsName = entry.arguments?.getString("wsName") ?: "workspace"
                    Box(Modifier.fillMaxSize()) {
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
                        ExtendedFloatingActionButton(
                            onClick = { nav.navigate(FloorsNav.link(wsId, wsName)) },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp),
                            containerColor = app.maestri.ui.Surface1,
                        ) { Text("⇆ Floors") }
                    }
                }
            }
        }
    }
}

/** Terminal names are opaque; URL-encode them for the nav route. */
fun enc(value: String): String = android.net.Uri.encode(value)

/** Decode a nav argument that was URL-encoded by [enc]. */
fun dec(value: String): String = android.net.Uri.decode(value)