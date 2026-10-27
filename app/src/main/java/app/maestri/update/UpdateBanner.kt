package app.maestri.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maestri.diagnostics.Journey
import app.maestri.ui.Style.LocalMaestriColors
import app.maestri.ui.Style.controlShape

/**
 * The discreet in-app banner: a newer published build is available.
 *
 * It states the version, offers "Atualizar" and can be dismissed — dismissing hides the
 * banner for that version only; the notification and the install path stay available.
 */
@Composable
fun UpdateBanner(controller: UpdateController, modifier: Modifier = Modifier) {
    val release by controller.release.collectAsState()
    val visible by controller.bannerVisible.collectAsState()
    val install by controller.install.collectAsState()
    val info = release ?: return
    if (!visible) return

    LaunchedEffect(info.versionCode) {
        Journey.update("banner", "visible", "published=${info.versionCode}")
    }

    val status = when (install) {
        is UpdateController.InstallState.Downloading -> "Baixando…"
        is UpdateController.InstallState.Failed -> "Não foi possível baixar. Tente de novo."
        is UpdateController.InstallState.NeedsPermission ->
            "Permita a instalação de apps desconhecidos para continuar."
        else -> info.notes.ifBlank { "Versão ${info.versionName}" }
    }

    Surface(
        color = LocalMaestriColors.current.canvasChromeElevated,
        shape = controlShape(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(
                Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = LocalMaestriColors.current.accent,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f).padding(start = 8.dp, end = 4.dp)) {
                Text(
                    "Nova versão disponível",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    status,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    Journey.update("tap", "banner", "published=${info.versionCode}")
                    controller.onUpdatePressed()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Atualizar", fontSize = 11.sp) }
            Icon(
                Icons.Filled.Close,
                contentDescription = "Dispensar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { controller.dismiss() },
            )
        }
    }
}
