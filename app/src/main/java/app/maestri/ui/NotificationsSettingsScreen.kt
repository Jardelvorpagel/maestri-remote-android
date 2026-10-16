package app.maestri.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.maestri.ui.NotificationsStrings as NS
import app.maestri.wire.NotificationsRouter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * P4-A notifications settings (destination route "notifications-settings" via
 * [NotificationsNav]; Settings v1 from the sibling lane links into this route).
 * Per-channel toggles are SOFTWARE gates on posting (Android channel importance is
 * user-owned after creation — the honest adaptation of iOS's per-kind toggles).
 */
object NotificationsNav {
    const val ROUTE = "notifications-settings"
}

@Composable
fun NotificationsSettingsScreen(router: NotificationsRouter) {
    val context = LocalContext.current.applicationContext
    var master by remember { mutableStateOf(router.systemPostNotificationsEnabled()) }
    var chatOn by remember { mutableStateOf(router.isChannelEnabled(NotificationsRouter.CHAN_CHAT)) }
    var agentsOn by remember { mutableStateOf(router.isChannelEnabled(NotificationsRouter.CHAN_AGENTS)) }
    var systemOn by remember { mutableStateOf(router.isChannelEnabled(NotificationsRouter.CHAN_SYSTEM)) }
    var activityOn by remember { mutableStateOf(router.isActivityRowEnabled()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            NS.pushSettingsTitle,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            NS.sectionNotifications,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                NS.channelChatName,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(
                checked = chatOn && master,
                onCheckedChange = {
                    chatOn = it
                    router.setChannelEnabled(NotificationsRouter.CHAN_CHAT, it)
                },
                enabled = master,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                NS.channelAgentsName,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(
                checked = agentsOn && master,
                onCheckedChange = {
                    agentsOn = it
                    router.setChannelEnabled(NotificationsRouter.CHAN_AGENTS, it)
                },
                enabled = master,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                NS.channelSystemName,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(
                checked = systemOn && master,
                onCheckedChange = {
                    systemOn = it
                    router.setChannelEnabled(NotificationsRouter.CHAN_SYSTEM, it)
                },
                enabled = master,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    NS.activityBlurb,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = activityOn,
                onCheckedChange = {
                    activityOn = it
                    router.setActivityRowEnabled(it)
                },
            )
        }
        Text(
            NS.activityToggleNote,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!master) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            }) {
                Text(NS.allowInSettings, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            router.clearAllNotifications()
        }) {
            Text(NS.clearAll, fontSize = 13.sp)
        }
    }
}
/** P4-A destination registration (sibling Settings v1 links into this route by constant). */
fun NavGraphBuilder.notificationsDestination(router: NotificationsRouter) {
    composable(NotificationsNav.ROUTE) {
        NotificationsSettingsScreen(router)
    }
}
