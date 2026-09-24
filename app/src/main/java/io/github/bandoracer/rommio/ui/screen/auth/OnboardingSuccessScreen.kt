package io.github.bandoracer.rommio.ui.screen.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.ui.component.DotLeader
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.LocalRowInset
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.flow.flowOf

/**
 * First sync (docs/theme-layouts.md "Setup"): after signing in, the library arrives — platforms,
 * games and collections with their counts — then into Play.
 */
@Composable
fun OnboardingSuccessScreen(
    onContinue: () -> Unit,
    container: AppContainer? = null,
) {
    val theme = RommioTheme.current
    val platforms by (container?.repository?.observeCachedPlatforms() ?: flowOf(emptyList())).collectAsStateWithLifecycle(emptyList())
    val collections by (container?.repository?.observeCachedCollections() ?: flowOf(emptyList())).collectAsStateWithLifecycle(emptyList())
    val profile by (container?.repository?.activeProfileFlow() ?: flowOf(null)).collectAsStateWithLifecycle(null)
    LaunchedEffect(container) {
        runCatching { container?.repository?.refreshActiveProfileCache(force = false) }
    }
    val games = platforms.sumOf { it.romCount }
    val ready = platforms.isNotEmpty()
    OnboardingFrame(
        step = OnboardingStep.Finish,
        title = "Getting your library ready",
        subtitle = "",
        stepSubtitle = profile?.label ?: "RomM",
        modifier = Modifier.testTag("screen.firstSync"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!ready) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = theme.colors.tint)
            RoleText(
                if (ready) "Your library is on this device" else "Fetching platforms, games and collections",
                theme.type.secondary,
                color = theme.colors.inkSecondary,
            )
        }
        ListGroup {
            SyncRow("Platforms", platforms.size.takeIf { it > 0 }?.toString())
            ListDivider()
            SyncRow("Games", games.takeIf { it > 0 }?.let { java.text.NumberFormat.getIntegerInstance().format(it) })
            ListDivider()
            SyncRow("Collections", collections.size.takeIf { it > 0 }?.toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Sync, contentDescription = null, tint = theme.colors.tint, modifier = Modifier.size(20.dp))
            RoleText(
                "Game saves and resume points sync between your devices through your server.",
                theme.type.secondary,
                color = theme.colors.inkSecondary,
            )
        }
        PrimaryButton(
            text = "Start playing",
            icon = Icons.Outlined.PlayArrow,
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().testTag("firstSync.start"),
        )
        RoleText(
            "Artwork keeps arriving in the background.",
            theme.type.caption,
            color = theme.colors.inkTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SyncRow(title: String, value: String?) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = theme.spacing.rowHeight)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = LocalRowInset.current, vertical = if (theme.isRiso) 11.dp else 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoleText(
            title,
            if (theme.isRiso) theme.type.section else theme.type.bodyEmphasis,
            size = if (theme.isRiso) 20f else 16f,
            color = c.ink,
            modifier = if (theme.isRiso) Modifier else Modifier.weight(1f),
        )
        if (theme.isRiso) DotLeader(Modifier.weight(1f))
        RoleText(value ?: "…", theme.type.numeral, size = 15f, color = c.ink)
        Icon(
            if (value == null) Icons.Outlined.RadioButtonUnchecked else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (value == null) c.inkTertiary else c.ok,
            modifier = Modifier.size(20.dp),
        )
    }
}
