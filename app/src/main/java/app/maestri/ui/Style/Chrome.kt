package app.maestri.ui.Style

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v2 chrome pieces. The spine is the phone navigation: Workspaces · Feed · Chat —
 * roots show it instead of a back arrow; pushed screens (canvas, terminal, notes,
 * floors) keep their back affordance. Icons are text glyphs on the system sans,
 * labelled at 10.5, active tab in terracotta (spec §6).
 *
 * Glass note: Compose 1.7 cannot blur the BACKDROP behind a composable, so "glass" here
 * is translucency (92–94 % of the chrome colour) + hairline ring + soft halo. Documented
 * deviation from the design's blur(18px) saturate(1.4).
 */
object Chrome {
    val tabGlyphs = listOf("\u25A4", "\u224B", "\u25CD") // ▤ ≋ ◍
    val tabLabels = listOf("Workspaces", "Feed", "Chat")
}

@Composable
fun MaestriSpine(
    active: Int,
    onWorkspaces: () -> Unit,
    onFeed: (() -> Unit)?,
    onChat: (() -> Unit)?,
    badges: Map<Int, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val c = LocalMaestriColors.current
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(56.dp)
            .background(c.glass)
            .border(1.dp, c.hairline)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SpineTab(0, active, badges[0] ?: 0, enabled = true, onClick = onWorkspaces)
            SpineTab(1, active, badges[1] ?: 0, enabled = onFeed != null, onClick = { onFeed?.invoke() })
            SpineTab(2, active, badges[2] ?: 0, enabled = onChat != null, onClick = { onChat?.invoke() })
        }
    }
}

@Composable
private fun SpineTab(
    index: Int,
    active: Int,
    badge: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalMaestriColors.current
    val on = index == active
    val tint = when {
        !enabled -> c.secondaryLabel.copy(alpha = 0.45f)
        on -> c.accent
        else -> Palette.StoneGray
    }
    Column(
        Modifier
            .height(56.dp)
            .padding(horizontal = 14.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = Chrome.tabLabels[index] },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(Chrome.tabGlyphs[index], color = tint, fontSize = 17.sp)
        Text(
            if (badge > 0) "${Chrome.tabLabels[index]} · $badge" else Chrome.tabLabels[index],
            color = tint,
            fontSize = 10.5.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 44 dp circular tonal button with the ring-depth treatment; the ACTIVE state is the
 * v2 addition (terracotta ring + terracotta glyph) — the v2 mock was missing it.
 */
@Composable
fun IconPill(
    glyph: String,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val c = LocalMaestriColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else 1f,
        animationSpec = tween(Motion.Fast, easing = Motion.Ease),
        label = "ipill-press",
    )
    val tint = when {
        !enabled -> c.secondaryLabel.copy(alpha = 0.4f)
        destructive -> c.danger
        active -> c.accent
        else -> c.secondaryLabel
    }
    Box(
        Modifier
            .scale(scale)
            .size(44.dp)
            .background(c.glass, CircleShape)
            .border(1.dp, if (active) c.accent else c.hairline, CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = source,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = tint, fontSize = 17.sp)
    }
}

/** Section overline: system sans 500 caps, 10 sp, +0.8 letter spacing. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = Palette.StoneGray,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
    )
}
