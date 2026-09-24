package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/**
 * A modal prompt drawn by the theme's components (the player's start prompt, its confirmations):
 * Aurora a raised glass card with the glass edge and shadow, Riso a paper card in an ink keyline
 * on the pink plate. The content scrolls when the window is short (landscape phones).
 */
@Composable
fun ThemedDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val theme = RommioTheme.current
        val c = theme.colors
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
        val face = if (theme.isRiso) {
            Modifier
                .plateOffset(c.accent1, (theme.material.misregistration * 2).dp)
                .themedSurface(theme, 0.dp, fill = c.groundRaised)
        } else {
            Modifier.themedSurface(theme, theme.radius.card, fill = theme.solidSurface)
        }
        CompositionLocalProvider(LocalContentColor provides c.ink, LocalInCard provides true) {
            Column(
                modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .then(face)
                    .verticalScroll(rememberScrollState())
                    .padding(PaddingValues(horizontal = 20.dp, vertical = 20.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** A prompt's heading: a quiet kicker line (optional) over the `headline` title. */
@Composable
fun DialogHeading(title: String, kicker: String? = null) {
    val theme = RommioTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        kicker?.let {
            if (theme.isRiso) Kicker(it) else RoleText(it, theme.type.section, color = theme.colors.inkSecondary, maxLines = 1)
        }
        RoleText(title, theme.type.headline, color = theme.colors.ink, modifier = Modifier.semantics { heading() })
    }
}

/** A prompt's running text in `secondary` / `inkSecondary`. */
@Composable
fun DialogText(text: String, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    RoleText(text, theme.type.secondary, color = theme.colors.inkSecondary, modifier = modifier)
}
