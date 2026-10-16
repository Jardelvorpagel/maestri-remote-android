package app.maestri.wire

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.maestri.remote.MainActivity
import app.maestri.ui.ChatNav
import app.maestri.ui.NotificationsStrings as NS
import app.maestri.wire.session.WireSession
import app.maestri.wire.ChatVisibility
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * P4-A foreground-WSS notification router (design doc:
 * ~/knowledge/sources/2026-10-16-maestri-android-p4a-foreground-push-spec.md).
 *
 * Option A rules, enforced here:
 *  - NO registration/grant/relay routes are ever called (banked: PUT /api/push/registration
 *    requires a relay-issued grant; 6 well-formed variants → 400 — APNs-only verdict).
 *  - NO foreground service, no boot receiver: notifications only while the app process is
 *    alive and the feed stream delivers snapshots; they pause when the stream does.
 *  - The sole input is the EXISTING feed stream (session.feedSnapshot, public on WireSession —
 *    WireSession is FROZEN and gains no accessors) diffed snapshot-against-snapshot.
 *  - Row 4 (chat) is powered by the terminal-card chatPreview (<4 recent entries + unreadCount,
 *    present while the chat face is ON) — no second chat stream is opened; the chat screen's
 *    own stream stays the only socket. Own sends are never notified: gate is role + our minted
 *    clientMessageId registry + on-screen visibility (unreadCount is NEVER a trigger by itself).
 *  - 401 auth loss (session.authLost, public SharedFlow): cancel all, stop, single system
 *    notification (banked "pare de tentar" contract). No reconnect loop here.
 *  - No retro-notify on resync: on workspace switch or re-pair the diff state reseeds silently.
 *  - Suppression: rows 1–3,5,6,8,9 post only while the app is backgrounded; on foreground the
 *    in-app surfaces supersede and posted rows are cancelled. Auth-lost posts in either state.
 *  - Permission: POST_NOTIFICATIONS asked ONCE via [permissionNudge] → the MainActivity hook
 *    launches the runtime request; honest denial = notification-less, in-app surfaces only.
 */
class NotificationsRouter(
    private val session: WireSession,
    context: Context,
    private val appForeground: MutableStateFlow<Boolean>,
) {
    private val appContext = context.applicationContext
    private val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Emitted once when an event wants attention and the runtime permission was never asked. */
    val permissionNudge = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var startJobs: List<Job> = emptyList()
    private var askedPermission = false

    // -------------------------------------------------------------- settings surface

    fun isChannelEnabled(channelId: String): Boolean = prefs.getBoolean(channelId, true)
    fun setChannelEnabled(channelId: String, enabled: Boolean) {
        prefs.edit().putBoolean(channelId, enabled).apply()
    }

    fun isActivityRowEnabled(): Boolean = prefs.getBoolean(CHAN_ACTIVITY, false)
    fun setActivityRowEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(CHAN_ACTIVITY, enabled).apply()
    }

    fun systemPostNotificationsEnabled(): Boolean = nm.areNotificationsEnabled()
    fun clearAllNotifications() = nm.cancelAll()
    fun permissionAsked(): Boolean = prefs.getBoolean(KEY_PERMISSION_ASKED, false)

    fun onPermissionResult(granted: Boolean) {
        prefs.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
        askedPermission = true
        if (granted) ensureChannels()
    }

    // -------------------------------------------------------------- lifecycle

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannels(
            listOf(
                channel(CHAN_CHAT, NS.channelChatName, NotificationManager.IMPORTANCE_HIGH),
                channel(CHAN_AGENTS, NS.channelAgentsName, NotificationManager.IMPORTANCE_DEFAULT),
                channel(CHAN_SYSTEM, NS.channelSystemName, NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    private fun channel(id: String, name: String, importance: Int) =
        NotificationChannel(id, name, importance).apply { setShowBadge(true) }

    fun start(): List<Job> {
        if (startJobs.isNotEmpty()) return startJobs
        askedPermission = permissionAsked()
        ensureChannels()
        val jobs = mutableListOf<Job>()

        jobs += session.scope.launch { runFeedDiffLoop() }
        jobs += session.scope.launch {
            session.authLost.collect { message -> onAuthLost(message) }
        }
        startJobs = jobs
        return jobs
    }

    fun stop() {
        startJobs.forEach { it.cancel() }
        startJobs = emptyList()
    }

    // -------------------------------------------------------------- row 7: auth loss

    private fun onAuthLost(message: String?) {
        // Banked contract: 401 everywhere = "pare de tentar" — stop, return to pairing UX.
        nm.cancelAll()
        permissionNudge.tryEmit(Unit) // pair-time is a natural moment to (re)ask, once
        post(
            notificationId = AUTH_LOST_ID,
            channelId = CHAN_SYSTEM,
            channelGate = true,           // the one notification allowed regardless of toggles
            bypassForeground = true,      // and of the foreground suppression
            title = NS.appName,
            body = message ?: NS.authLostFallbackBody,
            deeplink = null,              // MainActivity's authLost UX (banked) handles routing
        )
    }

    // -------------------------------------------------------------- feed diff engine

    private suspend fun runFeedDiffLoop() {
        var prev: FeedSnapshot? = null
        var prevForeground = true
        // conversationId → ids of entries the diff has already seen (bounded)
        val chatSeen = HashMap<String, LinkedHashSet<String>>()
        // terminalId → conversationId the last diff saw this chat face on
        val chatConversation = HashMap<String, String>()

        session.feedSnapshot.collect { snap ->
            if (snap == null) {
                prev = null
                return@collect
            }
            val fg = appForeground.value
            val old = prev

            // Reseed silently on the FIRST snapshot or a workspace switch (no retro-notify).
            if (old == null || old.workspaceId != snap.workspaceId) {
                seenSeed(snap, chatSeen, chatConversation)
                prev = snap
                prevForeground = fg
                if (fg && old != null) nm.cancelAll() // in-app surfaces supersede, per spec §3
                return@collect
            }

            // Foreground transition: cancel posted rows (auth-lost keeps its own id out of this).
            if (fg && !prevForeground) {
                nm.cancelAll()
            }
            prevForeground = fg

            if (!fg) {
                diffRows(old, snap, chatSeen, chatConversation)
            } else {
                // Track seen state even in foreground so backgrounding never replays history.
                seenTrack(snap, chatSeen, chatConversation)
            }
            prev = snap
        }
    }

    private fun diffRows(
        old: FeedSnapshot,
        new: FeedSnapshot,
        chatSeen: HashMap<String, LinkedHashSet<String>>,
        chatConversation: HashMap<String, String>,
    ) {
        // ---- row 1 + 6 + 3(clone missing) across terminal cards
        val oldCards = old.terminalCards.associateBy { it.id }
        val oldFloors = old.floors.associateBy { it.id }
        val oldPending = old.pendingFloors.associateBy { it.id }
        val newPending = new.pendingFloors.associateBy { it.id }

        for (card in new.terminalCards) {
            val before = oldCards[card.id] ?: continue // new cards reseed, never notify

            // Row 6 — maestro request stands (needsAttention true WITH a request message).
            val mNew = card.maestroRequestMessage
            val mOld = before.maestroRequestMessage
            if (mNew != null && mNew != mOld) {
                post(
                    notificationId = stableId("maestro:${card.id}"),
                    channelId = CHAN_SYSTEM,
                    channelGate = isChannelEnabled(CHAN_SYSTEM),
                    title = NS.maestroTitle,
                    body = mNew,
                    deeplink = feedLink(new.workspaceId, new.workspaceName),
                )
            } else if (mNew == null && mOld != null) {
                nm.cancel(stableId("maestro:${card.id}"))
            }

            // Row 1 — needs attention WITHOUT a maestroRequest (agent finished / needs you).
            if (card.needsAttention && !before.needsAttention && card.maestroRequestMessage == null) {
                val floorStatusText = card.floorId?.let { oldFloors[it]?.statusText ?: new.floors.firstOrNull { it.id == card.floorId }?.statusText }
                post(
                    notificationId = stableId("att:${card.id}"),
                    channelId = CHAN_AGENTS,
                    channelGate = isChannelEnabled(CHAN_AGENTS),
                    title = card.name,
                    body = floorStatusText ?: NS.attentionBody,
                    deeplink = terminalLink(new.workspaceId, card.id, card.name),
                )
            } else if (!card.needsAttention && before.needsAttention) {
                nm.cancel(stableId("att:${card.id}"))
            }

            // Row 3 (part 2) — floor clone missing.
            val floorNow = new.floors.firstOrNull { it.id == card.floorId }
            if (floorNow?.isCloneMissing == true &&
                oldFloors[floorNow.id]?.isCloneMissing != true
            ) {
                post(
                    notificationId = stableId("clone:${floorNow.id}"),
                    channelId = CHAN_AGENTS,
                    channelGate = isChannelEnabled(CHAN_AGENTS),
                    title = floorNow.name,
                    body = NS.cloneMissingBody,
                    deeplink = feedLink(new.workspaceId, new.workspaceName),
                )
            }

            // Row 9 — terminal activity behind the default-OFF toggle (never by default).
            if (isActivityRowEnabled() && card.isLive && !(before.isLive || oldCards[card.id]?.isLive == true)) {
                post(
                    notificationId = stableId("act:${card.id}"),
                    channelId = CHAN_AGENTS,
                    channelGate = true,
                    title = card.name,
                    body = card.preview.lastOrNull() ?: NS.attentionBody,
                    deeplink = terminalLink(new.workspaceId, card.id, card.name),
                )
            }
        }

        // ---- row 3 (part 1): pending floor copy failure (error appeared)
        for (pending in new.pendingFloors) {
            val before = oldPending[pending.id]
            if (pending.error != null && !before?.error.isNullOrBlank() == false && before !== pending) {
                if (before?.error == null || before.error.isBlank()) {
                    post(
                        notificationId = stableId("copy:${pending.id}"),
                        channelId = CHAN_AGENTS,
                        channelGate = isChannelEnabled(CHAN_AGENTS),
                        title = pending.name,
                        body = pending.error ?: NS.copyFailedBody,
                        deeplink = feedLink(new.workspaceId, new.workspaceName),
                    )
                }
            }
        }

        // ---- row 2: floor landed (pending id vanished AND the same floor now exists)
        val newFloorIds = new.floors.mapNotNull { it.id }.toSet()
        for ((id, pending) in oldPending) {
            if (id !in newPending && id in newFloorIds) {
                post(
                    notificationId = stableId("land:$id"),
                    channelId = CHAN_AGENTS,
                    channelGate = isChannelEnabled(CHAN_AGENTS),
                    title = pending.name,
                    body = NS.landedBody(pending.branch ?: pending.name),
                    deeplink = feedLink(new.workspaceId, new.workspaceName),
                )
            }
        }

        // ---- row 5: pull request attention / state on floors
        for (floor in new.floors) {
            val fid = floor.id ?: continue
            val before = oldFloors[fid]
            if (before == null) continue
            val attNew = floor.pullAttention
            val attOld = before.pullAttention
            val stateOld = before.pullState
            val stateNew = floor.pullState
            if (attNew != null && attNew != attOld && attNew != "checksRunning" && stateNew != "merged") {
                post(
                    notificationId = stableId("pr:$fid"),
                    channelId = CHAN_AGENTS,
                    channelGate = isChannelEnabled(CHAN_AGENTS),
                    title = floor.name,
                    body = NS.pullBody(floor.pullNumber, attNew, floor.pullTitle ?: ""),
                    deeplink = feedLink(new.workspaceId, new.workspaceName),
                )
            }
            // merged/closed → silent cancel (host clears the floor status with it)
            if ((stateNew == "merged" || stateNew == "closed") && stateOld != stateNew) {
                nm.cancel(stableId("pr:$fid"))
            }
        }

        // ---- row 8: workspace locked by the host's plan
        val lockNew = new.workspace?.isLocked == true
        val lockOld = old.workspace?.isLocked == true
        if (lockNew && !lockOld) {
            post(
                notificationId = stableId("lock:${new.workspaceId}"),
                channelId = CHAN_SYSTEM,
                channelGate = isChannelEnabled(CHAN_SYSTEM),
                title = new.workspaceName,
                body = NS.lockBody,
                deeplink = feedLink(new.workspaceId, new.workspaceName),
            )
        } else if (!lockNew && lockOld) {
            nm.cancel(stableId("lock:${new.workspaceId}"))
        }

        // ---- row 4: chat messages from the card chatPreview (chat face ON)
        for (card in new.terminalCards) {
            val preview = card.chatPreview ?: continue
            if (card.id in ChatVisibility.visibleTerminalIds.value) continue // on-screen: in-app
            val conversationId = "${new.workspaceId}:${card.id}"
            val previous = chatConversation[card.id]
            if (previous != conversationId) {
                // Conversation (or face) changed: reseed from what preview already holds.
                chatConversation[card.id] = conversationId
                chatSeen[conversationId] = LinkedHashSet(preview.entries.map { it.id })
                continue
            }
            val seen = chatSeen.getOrPut(conversationId) { LinkedHashSet() }
            for (entry in preview.entries) {
                if (entry.id in seen) continue
                seen.add(entry.id)
                if (seen.size > 64) {
                    val it = seen.iterator(); it.next(); it.remove() // bound memory
                }
                // NEVER notify own sends: user-role entries and our own minted ids are skipped.
                if (entry.isUser) continue
                if (entry.clientMessageId != null && entry.clientMessageId in ChatVisibility.mintedMessageIds.value) continue
                post(
                    notificationId = stableId("chat:$conversationId"),
                    channelId = CHAN_CHAT,
                    channelGate = isChannelEnabled(CHAN_CHAT),
                    title = entry.authorName?.takeIf { it.isNotBlank() } ?: card.name,
                    body = entry.text.take(80),
                    deeplink = ChatNav.route(new.workspaceId, card.id, card.name),
                    person = entry.authorName,
                )
            }
        }
    }

    /** Seed-only pass: record what the snapshot already contains so the first diff never replays. */
    private fun seenTrack(
        snap: FeedSnapshot,
        chatSeen: HashMap<String, LinkedHashSet<String>>,
        chatConversation: HashMap<String, String>,
    ) {
        for (card in snap.terminalCards) {
            val preview = card.chatPreview ?: continue
            val conversationId = "${snap.workspaceId}:${card.id}"
            if (chatConversation[card.id] != conversationId) {
                chatConversation[card.id] = conversationId
            }
            val seen = chatSeen.getOrPut(conversationId) { LinkedHashSet() }
            seen.addAll(preview.entries.map { it.id })
            if (seen.size > 64) {
                val it = seen.iterator(); it.next(); it.remove()
            }
        }
    }

    private fun seenSeed(
        snap: FeedSnapshot,
        chatSeen: HashMap<String, LinkedHashSet<String>>,
        chatConversation: HashMap<String, String>,
    ) {
        // Full reseed: clear conversation map entries for terminals gone from this workspace,
        // seed every chatPreview present (and only chat face ON produces one).
        chatConversation.keys.retainAll(snap.terminalCards.map { it.id }.toSet())
        chatSeen.clear()
        seenTrack(snap, chatSeen, chatConversation)
    }

    // -------------------------------------------------------------- posting

    private fun post(
        notificationId: Int,
        channelId: String,
        channelGate: Boolean,
        title: String,
        body: String,
        deeplink: String?,
        person: String? = null,
        bypassForeground: Boolean = false,
    ) {
        if (!channelGate) return
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (!askedPermission) {
                askedPermission = true
                permissionNudge.tryEmit(Unit)
            }
            return // honest denial state: in-app surfaces only — never nag
        }
        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (person != null) builder.addPerson(
            androidx.core.app.Person.Builder().setName(person).setKey(person).build()
        )
        if (deeplink != null) builder.setContentIntent(deeplinkIntent(deeplink, notificationId))
        runCatching { nm.notify(notificationId, builder.build()) }
    }

    private fun deeplinkIntent(route: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            requestCode,
            Intent(appContext, MainActivity::class.java)
                .putExtra(EXTRA_DEEPLINK, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun feedLink(wsId: String, wsName: String): String =
        "feedWithFloors/${AndroidNet.enc(wsId)}/${AndroidNet.enc(wsName)}"

    private fun terminalLink(wsId: String, terminalId: String, terminalName: String): String =
        "terminal/${AndroidNet.enc(wsId)}/${AndroidNet.enc(terminalId)}/${AndroidNet.enc(terminalName)}"

    private fun stableId(key: String): Int = 10_000_000 + key.hashCode().let { if (it < 0) -it else it }

    companion object {
        const val EXTRA_DEEPLINK = "maestri.deeplink"
        const val CHAN_CHAT = "chat"
        const val CHAN_AGENTS = "agents"
        const val CHAN_SYSTEM = "system"
        const val CHAN_ACTIVITY = "activityRow" // row 9 toggle, default OFF
        const val PREFS = "remote.notifications"
        const val KEY_PERMISSION_ASKED = "permission.asked"
        private const val AUTH_LOST_ID = 401
    }
}

/** Private helper so the router does not import android.net.Uri at several sites. */
private object AndroidNet {
    fun enc(value: String): String = android.net.Uri.encode(value)
}

/** Deep-link bus: MainActivity minted PendingIntents land here; the hook navigates. */
object DeepLinkBus {
    val routes = MutableSharedFlow<String>(extraBufferCapacity = 8)
}
