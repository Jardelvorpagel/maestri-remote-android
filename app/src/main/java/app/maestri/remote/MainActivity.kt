package app.maestri.remote

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import app.maestri.ui.settings.AppLockGate
import app.maestri.ui.settings.AppLockStore
import app.maestri.ui.settings.applyRecentsBlanking
import app.maestri.ui.settings.SettingsScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import app.maestri.ui.Style.MaestriSpine
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
import androidx.navigation.compose.currentBackStackEntryAsState
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
import app.maestri.ui.NotificationsNav
import app.maestri.ui.notificationsDestination
import app.maestri.ui.screens.WorkspacesScreen
import app.maestri.wire.DeepLinkBus
import app.maestri.wire.session.WireSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import app.maestri.ui.Style.LocalMaestriColors

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // P4-A: a notification tap that COLD-STARTS the app lands its deep link here.
        intent?.getStringExtra(NotificationsRouter.EXTRA_DEEPLINK)?.let { DeepLinkBus.routes.tryEmit(it) }
        val session = (application as MaestriApp).session
        setContent {
            MaestriTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(session, appLock())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // P4-A: a notification tap that WARMS an existing task lands its deep link here.
        intent.getStringExtra(NotificationsRouter.EXTRA_DEEPLINK)?.let { DeepLinkBus.routes.tryEmit(it) }
    }
    // P6 app-lock lifecycle (spec 4.1/4.2): stamp leave (rotations skipped), re-arm on
    // entry; the gate sits on the NavHost branch ONLY — pairing never gated (W-1 ruling).
    override fun onStart() {
        super.onStart()
        val store = appLock()
        store.rearmOnStart(
            System.currentTimeMillis(),
            store.enabled.value,
            store.graceSeconds.value,
        )
        applyRecentsBlanking(this, store.locked)
    }

    override fun onStop() {
        super.onStop()
        val store = appLock()
        store.stampLeave(System.currentTimeMillis(), isChangingConfigurations)
        // Spec 4.6: grace == 0 means auto-re-arm — blank the recents snapshot too.
        if (store.enabled.value && store.graceSeconds.value == 0) {
            applyRecentsBlanking(this, true)
        }
    }

    private fun appLock(): AppLockStore = (application as MaestriApp).appLock
}

@Composable
fun Root(session: WireSession, appLock: AppLockStore) {
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
            AppLockGate(appLock) {
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
            // ---- todo #55: last-destination persistence + cold restore (nav host half).
            // The save observes the back stack (resolved route + args rebuilt from the
            // entry bundle — DataStore survives process death; SavedStateHandle alone
            // would not). The one-shot restore runs UNDER the lock gate: the cover
            // hides everything until unlock, and the restored screen is what unlock
            // reveals — lock/security semantics untouched.
            val navRestore = remember { NavRestoreStore(activityContext.applicationContext) }
            val currentEntry by nav.currentBackStackEntryAsState()
            LaunchedEffect(currentEntry) {
                val pattern = currentEntry?.destination?.route ?: return@LaunchedEffect
                val resolved = restoreResolveRoute(pattern, currentEntry?.arguments)
                    ?: return@LaunchedEffect
                navRestore.saveRoute(resolved)
            }
            LaunchedEffect(Unit) {
                // A buffered cold-start deep link (notification tap) always wins: the
                // settle delay lets its collect navigate first, and the guard below
                // refuses to clobber it — or any user navigation that already happened.
                delay(750)
                if (nav.currentDestination?.route != "workspaces") return@LaunchedEffect
                val saved = navRestore.awaitRoute()?.takeIf { it.isNotBlank() }
                    ?: return@LaunchedEffect
                val target = runCatching { validatedRestoreRoute(session, saved) }.getOrNull()
                    ?: return@LaunchedEffect
                runCatching { nav.navigate(target) }
            }
            // v2 chrome: last-visited workspace/terminal feed the 3-tab spine targets.
            var lastWs by remember { mutableStateOf<Pair<String, String>?>(null) }
            var lastTerm by remember { mutableStateOf<Triple<String, String, String>?>(null) }
            NavHost(navController = nav, startDestination = "workspaces") {
                composable("workspaces") {
                    Box(Modifier.fillMaxSize()) {
                        WorkspacesScreen(
                            session = session,
                            onOpenWorkspace = { wsId, wsName -> nav.navigate("feed/$wsId/${enc(wsName)}") },
                            onOpenFloors = { wsId, wsName -> nav.navigate(FloorsNav.link(wsId, wsName)) },
                            onOpenSettings = { nav.navigate("settings") },
                            // ＋ pill = pair another machine (the pairing flow — recoverable path
                            // from the lane brief); 🔔 pill = the P4-A notifications surface.
                            onAddMachine = { reauth = true },
                            onOpenNotifications = { nav.navigate(NotificationsNav.ROUTE) },
                            notificationsArmed = router.systemPostNotificationsEnabled(),
                        )
                        MaestriSpine(
                            active = 0,
                            onWorkspaces = {},
                            onFeed = lastWs?.let { (id, name) -> { nav.navigate("feed/$id/${enc(name)}") } },
                            onChat = lastTerm?.let { (id, tid, name) -> { nav.navigate(ChatNav.route(id, tid, name)) } },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
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
                    LaunchedEffect(wsId) { lastWs = wsId to wsName }
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
                        onOpenFloors = { nav.navigate(FloorsNav.link(wsId, wsName)) },
                    )
                    MaestriSpine(
                        active = 1,
                        onWorkspaces = { nav.navigate("workspaces") { launchSingleTop = true; popUpTo("workspaces") { inclusive = false } } },
                        onFeed = {},
                        onChat = lastTerm?.let { (id, tid, name) -> { nav.navigate(ChatNav.route(id, tid, name)) } },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    }
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
                    LaunchedEffect(terminalId) { lastTerm = Triple(wsId, terminalId, terminalName) }
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
                composable("settings") {
                    SettingsScreen(
                        session = session,
                        appLock = appLock,
                        onBack = { nav.popBackStack() },
                        // P4-A owns the route + its real row; navigate only when installed (W-4).
                        openNotificationsRoute = { r -> if (nav.graph.findNode(r) != null) nav.navigate(r) },
                    )
                }
                chatDestinations(session, nav)
                notificationsDestination(router)
                // ── P3 floors lane ────────────────────────────────────────────────────
                // Entry affordances live on WorkspacesScreen rows and the FeedScreen
                // chips row (both navigate via FloorsNav.link); the floor-chips lane
                // retired the old temp feedWithFloors FAB wrapper.
                FloorsNav.installFloorsRoutes(
                    nav = this,
                    session = session,
                    navigate = { r -> nav.navigate(r) },
                    pop = { nav.popBackStack() },
                )
            }
            }
        }
    }
}

/** Terminal names are opaque; URL-encode them for the nav route. */
fun enc(value: String): String = android.net.Uri.encode(value)

/** Decode a nav argument that was URL-encoded by [enc]. */
fun dec(value: String): String = android.net.Uri.decode(value)

/** Rebuild a resolved route from a destination pattern + its argument bundle.
 *  The nav library Uri-decodes segments once on parse; re-encoding inverts it
 *  exactly — including the floors lane's double-encoded values. Null when any
 *  placeholder has no value (a half-resolved route is never persisted). */
fun restoreResolveRoute(pattern: String, args: Bundle?): String? {
    if (args == null) return null
    val names = Regex("\\{([^}]+)\\}").findAll(pattern).map { it.groupValues[1] }.toList()
    if (names.isEmpty()) return pattern
    var out = pattern
    for (name in names) {
        val value = args.getString(name) ?: return null
        out = out.replace("{$name}", android.net.Uri.encode(value))
    }
    return out
}

/** Validate a persisted route against live host state. Returns the route when it
 *  is safe to navigate, else null — the caller stays on Workspaces, the honest
 *  fallback for stale ids (deleted workspace/terminal). Never throws; every hop
 *  is a read-only call on existing session APIs (~16s worst-case budget). */
suspend fun validatedRestoreRoute(session: WireSession, route: String): String? {
    val head = route.substringBefore('/')
    if (head == "workspaces") return null // already the start destination
    if (head == "settings" || head == "notifications-settings") return route
    val segs = route.split('/')
    // Workspace-scoped heads: segment 1 is the id (floors double-encodes it).
    val wsId = when (head) {
        "feed", "canvas", "terminal", "chat", "note" ->
            segs.getOrNull(1)?.let { runCatching { dec(it) }.getOrNull() }
        "floors" ->
            segs.getOrNull(1)?.let { runCatching { FloorsNav.decp(it) }.getOrNull() }
        else -> return null // unknown pattern — never navigate blind
    } ?: return null
    val known = withTimeoutOrNull(8000) { runCatching { session.workspaces() }.getOrNull() }
        ?: return null // offline / unpaired / 401: stay on root; screens show their own states
    if (known.none { it.id == wsId }) return null // deleted workspace -> honest root
    if (head == "terminal" || head == "chat") {
        val terminalId = segs.getOrNull(2)?.let { runCatching { dec(it) }.getOrNull() }
            ?: return null
        val pairing = withTimeoutOrNull(8000) {
            runCatching { session.pairing.first { it != null } }.getOrNull()
        } ?: return null
        val cards = withTimeoutOrNull(8000) {
            runCatching { session.feedOnce(pairing, wsId, null).terminalCards }.getOrNull()
        } ?: return null
        if (cards.none { it.id == terminalId }) return null // deleted terminal -> honest root
    }
    return route
}
