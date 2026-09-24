package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/** Large page title for pages that draw their own heading, with optional supporting line and trailing actions. */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val theme = RommioTheme.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoleText(
                text = title,
                role = theme.type.title,
                color = theme.colors.ink,
                maxLines = 2,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            actions?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = it)
            }
        }
        subtitle?.let {
            val riso = theme.isRiso
            RoleText(
                text = it,
                role = if (riso) theme.type.body else theme.type.secondary,
                size = if (riso) 15f else null,
                italic = riso,
                color = theme.colors.inkSecondary,
            )
        }
    }
}

/**
 * A section header above a group (docs/theme-layouts.md §2). Aurora: `section` in `inkSecondary`,
 * a trailing count in `numeral` 12.5 `inkTertiary`, an action in `tint`. Riso: `section` in `ink`,
 * a 1.5 dp ink rule filling the line, the count in `label` 10 `inkSecondary`, an underlined action;
 * 8 dp below.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    supportingText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val riso = theme.isRiso
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (riso) 8.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(if (riso) 10.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoleText(
                text = title,
                role = theme.type.section,
                color = if (riso) c.ink else c.inkSecondary,
                maxLines = 1,
                modifier = Modifier
                    .then(if (riso) Modifier else Modifier.weight(1f))
                    .semantics { heading() },
            )
            if (riso) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(theme.strokeWidth)
                        .background(c.ink),
                )
            }
            meta?.let {
                RoleText(
                    text = it,
                    role = if (riso) theme.type.label else theme.type.numeral,
                    size = if (riso) 10f else 12.5f,
                    color = if (riso) c.inkSecondary else c.inkTertiary,
                    maxLines = 1,
                )
            }
            if (actionLabel != null && onAction != null) {
                val role = theme.type.label
                CasedText(
                    text = actionLabel,
                    uppercase = role.uppercase,
                    style = role.style,
                    color = if (riso) c.ink else theme.tintText,
                    textDecoration = if (riso) TextDecoration.Underline else null,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(if (riso) RoundedCornerShape(0.dp) else theme.radius.chipShape)
                        .clickable(role = Role.Button, onClick = onAction)
                        .heightIn(min = 32.dp)
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
            action?.invoke()
        }
        supportingText?.let {
            RoleText(text = it, role = theme.type.caption, color = c.inkSecondary)
        }
    }
}

/**
 * Horizontal padding of list rows: 16 dp inside an Aurora glass group; Riso's paper rows run
 * flush with the text column (0).
 */
val LocalRowInset = staticCompositionLocalOf { 16.dp }

/**
 * A group of rows (docs §2 "Row group"); separate rows with [ListDivider]. Aurora: one glass card
 * (radius `card`), rows padded 16/9. Riso: no card — rows padded 0/11 on the paper, full-width
 * 1 dp hairline rules and a closing hairline. Optional [title] draws a [SectionHeader] above and
 * [footer] a caption below.
 */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    meta: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = RommioTheme.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (theme.isRiso) 0.dp else 8.dp)) {
        title?.let { SectionHeader(title = it, meta = meta, modifier = Modifier.padding(horizontal = if (theme.isRiso) 0.dp else 6.dp)) }
        if (theme.isRiso) {
            if (LocalInCard.current) {
                Column(modifier = Modifier.fillMaxWidth(), content = content)
            } else {
                CompositionLocalProvider(LocalRowInset provides 0.dp) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        content()
                        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.colors.hairline))
                    }
                }
            }
        } else {
            GlassSurface(modifier = Modifier.fillMaxWidth(), content = content)
        }
        footer?.let {
            RoleText(
                text = it,
                role = theme.type.caption,
                color = theme.colors.inkSecondary,
                modifier = Modifier.padding(horizontal = if (theme.isRiso) 0.dp else 6.dp, vertical = if (theme.isRiso) 8.dp else 0.dp),
            )
        }
    }
}

/**
 * The rule between rows: Aurora a 0.5 dp hairline inset 16 (58 past icons, 68 past covers);
 * Riso a full-width 1 dp hairline.
 */
@Composable
fun ListDivider(inset: Dp = 16.dp) {
    val theme = RommioTheme.current
    val flush = theme.isRiso
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (flush) 0.dp else inset)
            .height(if (theme.isRiso) 1.dp else 0.5.dp)
            .background(theme.colors.hairline),
    )
}

/** The disclosure mark: Aurora a chevron in `inkTertiary`, Riso an ink arrow. */
@Composable
fun Chevron() {
    val theme = RommioTheme.current
    Icon(
        if (theme.isRiso) Icons.AutoMirrored.Outlined.ArrowForward else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = if (theme.isRiso) theme.colors.ink else theme.colors.inkTertiary,
        modifier = Modifier.size(if (theme.isRiso) 16.dp else 22.dp),
    )
}

/**
 * A row (docs §2): optional leading element, title (`bodyEmphasis` 16) and subtitle (`secondary`
 * 13), and a trailing value, control or chevron. A title…value row (a value and nothing else
 * trailing) is a value row: Aurora puts the value in `numeral` 15 `inkSecondary` at the end;
 * Riso runs dot leaders to the value in `numeral` 12.5 `ink`.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Color.Unspecified,
    subtitleMaxLines: Int = 2,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val theme = RommioTheme.current
    val leaders = value != null && trailing == null && subtitle == null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(theme.spacing.rowHeight, 48.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = LocalRowInset.current, vertical = if (theme.isRiso) 11.dp else 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (leaders) {
                LeaderLine(label = title, value = value!!, labelColor = titleColor)
            } else {
                RoleText(
                    text = title,
                    role = theme.type.bodyEmphasis,
                    size = 16f,
                    color = if (titleColor == Color.Unspecified) theme.colors.ink else titleColor,
                    maxLines = 2,
                )
            }
            subtitle?.takeIf { it.isNotEmpty() }?.let {
                RoleText(text = it, role = theme.type.secondary, size = 13f, color = theme.colors.inkSecondary, maxLines = subtitleMaxLines)
            }
        }
        if (!leaders) {
            value?.let {
                RoleText(
                    text = it,
                    role = theme.type.numeral,
                    size = if (theme.isRiso) 12.5f else 15f,
                    color = if (theme.isRiso) theme.colors.ink else theme.colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }
        trailing?.invoke()
        if (showChevron) Chevron()
    }
}

/** Dot leaders between an entry and its value (Riso): 1.8 dp dots every 6 dp in `inkSecondary` 80 %, on the baseline. */
@Composable
fun DotLeader(modifier: Modifier = Modifier) {
    val dot = RommioTheme.colors.inkSecondary.copy(alpha = 0.8f)
    Box(
        modifier
            .widthIn(min = 14.dp)
            .height(8.dp)
            .drawBehind {
                val r = 0.9.dp.toPx()
                val step = 6.dp.toPx()
                val y = size.height - 2.dp.toPx()
                var x = 1.dp.toPx()
                while (x < size.width - 1.dp.toPx()) {
                    drawCircle(dot, radius = r, center = Offset(x, y))
                    x += step
                }
            },
    )
}

/**
 * A title…value line (docs §2 "Value row"). Aurora: title `bodyEmphasis` 16 and the value in
 * `numeral` 15 `inkSecondary` at either end. Riso: the title, dot leaders on the baseline and the
 * value in `numeral` 12.5 `ink`.
 */
@Composable
fun LeaderLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelColor: Color = Color.Unspecified,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        LeaderLayout(
            modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            start = {
                RoleText(
                    text = label,
                    role = theme.type.bodyEmphasis,
                    size = 16f,
                    color = if (labelColor == Color.Unspecified) c.ink else labelColor,
                    maxLines = 2,
                )
            },
            end = { RoleText(text = value, role = theme.type.numeral, size = 12.5f, color = c.ink, maxLines = 1) },
        )
        return
    }
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RoleText(
            text = label,
            role = theme.type.bodyEmphasis,
            size = 16f,
            color = if (labelColor == Color.Unspecified) c.ink else labelColor,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        RoleText(
            text = value,
            role = theme.type.numeral,
            size = if (theme.isRiso) 12.5f else 15f,
            color = if (theme.isRiso) c.ink else c.inkSecondary,
            maxLines = 1,
        )
    }
}

/** A standalone label…value row (details, facts); see [LeaderLine]. */
@Composable
fun LeaderRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    ListRow(title = label, value = value, subtitle = subtitle, modifier = modifier)
}

/** A list row with a switch in the page tint; the whole row toggles. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(theme.spacing.rowHeight, 48.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = LocalRowInset.current, vertical = if (theme.isRiso) 11.dp else 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RoleText(text = title, role = theme.type.bodyEmphasis, size = 16f, color = c.ink)
            subtitle?.let { RoleText(text = it, role = theme.type.secondary, size = 13f, color = c.inkSecondary) }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = if (theme.isRiso) {
                SwitchDefaults.colors(
                    checkedTrackColor = c.ink,
                    checkedThumbColor = c.ground,
                    checkedBorderColor = c.ink,
                    uncheckedTrackColor = Color.Transparent,
                    uncheckedThumbColor = c.ink,
                    uncheckedBorderColor = c.ink,
                )
            } else {
                SwitchDefaults.colors(
                    checkedTrackColor = c.tint,
                    checkedThumbColor = Color.White,
                    checkedBorderColor = c.tint,
                    uncheckedTrackColor = c.surfaceStrong,
                    uncheckedThumbColor = c.inkTertiary,
                    uncheckedBorderColor = theme.strokeColor,
                )
            },
        )
    }
}

/**
 * A figure with its label (Stats, Settings, Downloads). Aurora: a glass card, the `section` label
 * with a `mark1` symbol, the figure in `statValue` 34. Riso: a `label` 9.5 over `statValue` 27,
 * padded 10/12, no card (Riso boxes the figures together).
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    large: Boolean = true,
    icon: ImageVector? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        Column(
            modifier = modifier
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            RoleText(label, theme.type.label, size = 9.5f, color = c.inkSecondary, maxLines = 1)
            RoleText(value, theme.type.statValue, size = if (large) 27f else 22f, color = c.ink, maxLines = 1)
            caption?.let { RoleText(it, theme.type.caption, color = c.inkSecondary, maxLines = 2) }
        }
        return
    }
    GlassSurface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, contentDescription = null, tint = theme.mark1, modifier = Modifier.size(13.dp)) }
            RoleText(label, theme.type.section, color = c.inkSecondary, maxLines = 1)
        }
        RoleText(value, theme.type.statValue, size = if (large) 34f else 24f, color = c.ink, maxLines = 1)
        caption?.let { RoleText(it, theme.type.caption, color = c.inkSecondary, maxLines = 2) }
    }
}

/**
 * An empty or error state in the page's voice (docs §2). Aurora: a 34 dp symbol in
 * `inkTertiary`, `headline` and `secondary`. Riso: `headline`, a 48 × 3 ink rule and an italic
 * `body` 15. Centred, at most 420 wide. An optional action follows as a secondary button.
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (theme.isRiso) {
                RoleText(title, theme.type.headline, color = c.ink, textAlign = TextAlign.Center)
                Box(Modifier.width(48.dp).height(3.dp).background(c.ink))
            } else {
                icon?.let {
                    Icon(it, contentDescription = null, tint = c.inkTertiary, modifier = Modifier.padding(bottom = 4.dp).size(34.dp))
                }
                RoleText(title, theme.type.headline, color = c.ink, textAlign = TextAlign.Center)
            }
            if (message.isNotBlank()) {
                RoleText(
                    message,
                    if (theme.isRiso) theme.type.body else theme.type.secondary,
                    size = if (theme.isRiso) 15f else null,
                    italic = theme.isRiso,
                    color = c.inkSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                SecondaryButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

// ---- Panels used by setup, downloads, game and settings content -------------------------------

@Composable
fun CompactPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    badge: String? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Panel(modifier = modifier, title = title, subtitle = subtitle, badge = badge, prominent = false, contentPadding = contentPadding, content = content)
}

@Composable
private fun Panel(
    modifier: Modifier,
    title: String?,
    subtitle: String?,
    badge: String?,
    prominent: Boolean,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = RommioTheme.current
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(theme.spacing.stack),
    ) {
        if (!title.isNullOrBlank() || subtitle != null || badge != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!title.isNullOrBlank() || badge != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!title.isNullOrBlank()) {
                            RoleText(
                                text = title,
                                role = if (prominent) theme.type.headline else theme.type.bodyEmphasis,
                                color = theme.colors.ink,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        badge?.let { Tag(it) }
                    }
                }
                subtitle?.let { RoleText(text = it, role = theme.type.secondary, color = theme.colors.inkSecondary) }
            }
        }
        content()
    }
}

@Composable
fun LoadingSkeletonPanel(
    modifier: Modifier = Modifier,
    showArtwork: Boolean = false,
    lines: Int = 3,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showArtwork) {
            SkeletonBlock(Modifier.fillMaxWidth().height(140.dp), RoundedCornerShape(RommioTheme.radius.cover))
        }
        repeat(lines) { index ->
            SkeletonBlock(
                Modifier
                    .fillMaxWidth(if (index == lines - 1) 0.55f else 1f)
                    .height(if (index == 0) 20.dp else 14.dp),
            )
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50)) {
    Spacer(
        modifier = modifier
            .clip(if (RommioTheme.isRiso) androidx.compose.ui.graphics.RectangleShape else shape)
            .background(RommioTheme.colors.surfaceStrong),
    )
}
