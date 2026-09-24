package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/**
 * Regular width (tablets, unfolded foldables, large windows): at least 600 dp, the Android
 * equivalent of iOS's regular horizontal size class. Pages switch to their "one canvas" layouts
 * (docs/theme-layouts.md), Library becomes a split view and the tabs move to a rail.
 */
@Composable
fun isRegularWidth(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** The page gutter: the theme's on compact widths, 32 dp on regular widths (the spec's canvas margin). */
@Composable
fun pageGutter(regular: Boolean = isRegularWidth()): Dp = if (regular) 32.dp else RommioTheme.spacing.gutter

/**
 * Pull to refresh. The indicator shows only for a refresh the person pulled for, never for the
 * background refresh a page runs when it opens.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    var pulled by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(refreshing) { if (!refreshing) pulled = false }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = pulled && refreshing,
        onRefresh = {
            pulled = true
            onRefresh()
        },
        modifier = modifier,
        content = content,
    )
}

/** One action of a long-press menu (the iOS context menu). */
data class MenuAction(val label: String, val onClick: () -> Unit)

/**
 * A tappable box whose long press opens a small menu (the iOS `contextMenu`), e.g. Resume and
 * Game details on a game card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextMenuBox(
    onClick: () -> Unit,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier.combinedClickable(
            role = Role.Button,
            onClickLabel = onClickLabel,
            onLongClickLabel = actions.firstOrNull()?.label,
            onLongClick = { if (actions.isNotEmpty()) open = true },
            onClick = onClick,
        ),
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, onClick = { open = false; action.onClick() })
            }
        }
    }
}
