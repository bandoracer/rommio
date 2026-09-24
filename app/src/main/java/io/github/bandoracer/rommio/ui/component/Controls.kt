package io.github.bandoracer.rommio.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.ui.theme.RisoArtTokens
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.ui.theme.RommioThemeValue
import io.github.bandoracer.rommio.ui.theme.ThemeColorTokens

/** Semantic tones shared by badges, row icons and status text (`MUTE` is the spec's neutral). */
enum class Tone { OK, WAIT, LIVE, BAD, TINT, MUTE }

@Composable
fun toneColor(tone: Tone): Color {
    val c = RommioTheme.colors
    return tone.color(c)
}

fun Tone.color(c: ThemeColorTokens): Color = when (this) {
    Tone.OK -> c.ok
    Tone.WAIT -> c.wait
    Tone.LIVE -> c.live
    Tone.BAD -> c.bad
    Tone.TINT -> c.tint
    Tone.MUTE -> c.inkSecondary
}

/**
 * A tone as text on the page ground (docs/theme-layouts.md §1.2a): the tint through
 * [RommioThemeValue.tintText], errors in `badText`, and Riso never sets text in `ok` or `wait`
 * (its sync states are stamps with ink text).
 */
fun Tone.textColor(theme: RommioThemeValue): Color = when {
    this == Tone.TINT -> theme.tintText
    this == Tone.BAD -> theme.colors.badText
    theme.isRiso && (this == Tone.WAIT || this == Tone.OK) -> theme.colors.ink
    else -> color(theme.colors)
}

/** Riso's offset plate distance for buttons: 2 × misregistration (`.rb.ink`). */
private val RommioThemeValue.plateDistance: Dp get() = (material.misregistration * 2).dp

// ---- Buttons ------------------------------------------------------------------------------------

/**
 * The page's main action (docs/theme-layouts.md §2). Aurora: a capsule 54 dp high (44 small),
 * `button` 17/15, `buttonFill`/`buttonInk`, glowing in the page's `mark1` (55 % r17 y14) over a
 * black 16 % r3 y2 shadow. Riso: an ink block 52/44 in `button` 20/17 caps with the pink plate
 * printed 2 × misregistration down and right behind it.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    large: Boolean = true,
    trailingIcon: ImageVector? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        ButtonFrame(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .heightIn(min = if (large) 52.dp else 44.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .plateOffset(c.accent1, theme.plateDistance)
                .background(c.buttonFill),
            contentColor = c.buttonInk,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            ButtonContent(text, icon, if (large) 20f else 17f, trailingIcon)
        }
        return
    }
    val shape = RoundedCornerShape(percent = 50)
    ButtonFrame(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = if (large) 54.dp else 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(
                if (enabled) {
                    Modifier
                        .dropShadow(theme.mark1.copy(alpha = if (theme.isDark) 0.5f else 0.55f), 999.dp, 17.dp, 14.dp)
                        .dropShadow(Color.Black.copy(alpha = 0.16f), 999.dp, 3.dp, 2.dp)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(c.buttonFill)
            .drawBehind {
                // A 0.5 dp white 28 % keyline over the top half.
                val w = 0.5.dp.toPx()
                drawRoundRect(
                    Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.5f to Color.Transparent),
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(size.height / 2),
                    style = Stroke(w),
                )
            },
        contentColor = c.buttonInk,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
    ) {
        ButtonContent(text, icon, if (large) 17f else 15f, trailingIcon)
    }
}

/**
 * A quieter action. Aurora: a glass capsule 44 dp high, `button` 15 in `ink` (`bad` when
 * destructive). Riso: a 2 dp ink outline (`bad` when destructive), 44 dp, `button` 17.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val ink = if (destructive) c.badText else c.ink
    if (theme.isRiso) {
        ButtonFrame(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .heightIn(min = 44.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .border(2.dp, ink),
            contentColor = ink,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            ButtonContent(text, icon, 17f)
        }
        return
    }
    ButtonFrame(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .dropShadow(Color.Black.copy(alpha = if (theme.isDark) 0.3f else 0.06f), 999.dp, 10.dp, 5.dp)
            .themedSurface(theme, 999.dp, fill = c.surface, nested = true),
        contentColor = ink,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) {
        ButtonContent(text, icon, 15f)
    }
}

/** A quiet text-only action ("See all", "Retry sync"): `tint` (Aurora) or underlined ink caps (Riso). */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val color = when {
        !enabled -> c.inkTertiary
        destructive -> c.badText
        theme.isRiso -> c.ink
        else -> theme.tintText
    }
    ButtonFrame(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(if (theme.isRiso) RoundedCornerShape(0.dp) else RoundedCornerShape(percent = 50)),
        contentColor = color,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val role = theme.type.label
        CasedText(
            text,
            uppercase = role.uppercase,
            style = if (theme.isRiso) role.style else theme.type.bodyEmphasis.style,
            color = color,
            maxLines = 1,
            textDecoration = if (theme.isRiso) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        )
    }
}

/**
 * A round icon button (docs §2). Aurora: a glass circle, or when [prominent] a `buttonFill` disc
 * glowing in `mark1`. Riso: prominent = an ink disc with the pink plate at 1.25 × misregistration;
 * otherwise a 2 dp ink square.
 */
@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    prominent: Boolean = false,
    enabled: Boolean = true,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val mis = theme.material.misregistration.dp
    val shape = if (theme.isRiso && !prominent) RoundedCornerShape(0.dp) else CircleShape
    val face: Modifier = when {
        theme.isRiso && prominent -> Modifier
            .drawBehind {
                val o = (mis * 1.25f).toPx()
                drawCircle(c.accent1, radius = this.size.minDimension / 2, center = center + Offset(o, o))
                drawCircle(c.buttonFill, radius = this.size.minDimension / 2)
            }
        // Paper under the ink square: identical on paper, legible over a printed hero.
        theme.isRiso -> Modifier.background(c.ground).border(2.dp, c.ink)
        prominent -> Modifier
            .dropShadow(theme.mark1.copy(alpha = 0.45f), 999.dp, 10.dp, 5.dp)
            .clip(CircleShape)
            .background(c.buttonFill)
        else -> Modifier
            .dropShadow(Color.Black.copy(alpha = if (theme.isDark) 0.3f else 0.08f), 999.dp, 10.dp, 5.dp)
            .themedSurface(theme, 999.dp, fill = c.surface, nested = true)
    }
    val ink = if (prominent) c.buttonInk else c.ink
    Box(
        modifier = modifier
            .size(maxOf(size, 48.dp))
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).then(face), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.42f))
        }
    }
}

/**
 * A toolbar icon button with an optional count badge (Downloads in the Play toolbar): the icon
 * button style (glass circle / 2 dp ink square) at 40 dp inside a 48 dp target.
 */
@Composable
fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val description = if (badgeCount > 0) "$contentDescription, $badgeCount in progress" else contentDescription
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(if (theme.isRiso) RoundedCornerShape(0.dp) else CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (theme.isRiso) {
                        Modifier.border(2.dp, c.ink)
                    } else {
                        Modifier.themedSurface(theme, 999.dp, fill = c.surface, nested = true)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 2.dp)
                    .heightIn(min = 17.dp)
                    .widthIn(min = 17.dp)
                    .clip(if (theme.isRiso) RoundedCornerShape(0.dp) else CircleShape)
                    .background(c.bad)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badgeCount.toString(),
                    style = theme.type.numeral.style.copy(fontSize = 11.sp),
                    color = if (theme.isRiso) c.onAccent else Color.White,
                )
            }
        }
    }
}

/** A clickable button box: role, 48 dp minimum, ripple, content colour. */
@Composable
private fun ButtonFrame(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    contentColor: Color,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .widthIn(min = 48.dp)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = contentColor),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, size: Float, trailingIcon: ImageVector? = null) {
    val role = RommioTheme.type.button
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size((size + 1).dp)) }
        CasedText(
            text,
            role.uppercase,
            role.style.sized(size),
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (trailingIcon != null) Modifier.weight(1f) else Modifier,
        )
        trailingIcon?.let { Icon(it, contentDescription = null, modifier = Modifier.size((size + 1).dp)) }
    }
}

/** Legible content colour for a fill, taken from the theme's own ink and paper. */
fun onColor(background: Color, c: ThemeColorTokens): Color {
    val solid = background.compositeOver(c.ground)
    val lightTheme = c.ink.luminance() < c.ground.luminance()
    val darkInk = if (lightTheme) c.ink else c.ground
    val lightInk = if (lightTheme) c.groundRaised else c.ink
    return if (solid.luminance() > 0.45f) darkInk else lightInk
}

// ---- Status -------------------------------------------------------------------------------------

/** A spinning sync glyph (Aurora spinner, Riso `.st-live`). Paused in UI tests by Compose. */
@Composable
private fun SpinningSync(tint: Color, size: Dp) {
    val transition = rememberInfiniteTransition(label = "sync")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "syncAngle",
    )
    Icon(Icons.Outlined.Sync, contentDescription = null, tint = tint, modifier = Modifier.size(size).rotate(angle))
}

/**
 * A short status label that never truncates (docs §2 "Status chip / sync badge").
 * - Aurora: a capsule ≥ 24 dp high, padding 8/10, a 6 dp dot in the tone, `label`, tone text on the
 *   tone at 13 % (16 % dark). Syncing shows a spinner.
 * - Riso: a stamp ≥ 22 dp, padding 7, a 1.5 dp inset ink outline, `label` 10, a 9 dp square chip
 *   (ok = `accent4`, wait = `accent3`, live = spinning arrows in `accent2`, neutral = empty);
 *   conflict/failure = `bad` fill, `onAccent` text and a warning glyph, no outline.
 */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.MUTE,
    showsDot: Boolean = true,
    icon: ImageVector? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val label = theme.type.label
    if (theme.isRiso) {
        val bad = tone == Tone.BAD
        val ink = if (bad) c.onAccent else c.ink
        Row(
            modifier = modifier
                .heightIn(min = 22.dp)
                .then(if (bad) Modifier.background(c.bad) else Modifier.border(theme.strokeWidth, c.ink))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                tone == Tone.LIVE -> SpinningSync(c.accent2, 11.dp)
                bad -> Icon(icon ?: Icons.Outlined.WarningAmber, contentDescription = null, tint = ink, modifier = Modifier.size(11.dp))
                icon != null -> Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(11.dp))
                showsDot -> {
                    val fill = when (tone) {
                        Tone.OK -> c.accent4
                        Tone.WAIT -> c.accent3
                        Tone.LIVE, Tone.TINT -> c.accent2
                        Tone.BAD -> c.accent1
                        Tone.MUTE -> Color.Transparent
                    }
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(fill)
                            .drawBehind {
                                val w = 1.5.dp.toPx()
                                drawRect(c.ink, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
                            },
                    )
                }
            }
            CasedText(text, label.uppercase, label.style.sized(10f), color = ink, maxLines = 1, overflow = TextOverflow.Visible)
        }
        return
    }
    // A tint pill is the readable tint on a wash of itself (§1.2a); errors read in `badText`.
    val color = if (tone == Tone.TINT) theme.tintText else tone.color(c)
    val textColor = if (tone == Tone.MUTE) color else tone.textColor(theme)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = if (theme.isDark) 0.16f else 0.13f))
            .heightIn(min = 24.dp)
            .padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            tone == Tone.LIVE -> SpinningSync(color, 11.dp)
            icon != null -> Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
            showsDot -> Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
        CasedText(text, label.uppercase, label.style, color = textColor, maxLines = 1, overflow = TextOverflow.Visible)
    }
}

/** Sync state (save-sync-spec §11 words) as a [StatusChip]. */
@Composable
fun SyncBadge(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
) = StatusChip(text = text, tone = tone, modifier = modifier)

/**
 * A small label ("Installed", "3 save slots"): a [StatusChip] in its tone, or a neutral chip
 * without a dot when it carries none.
 */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    icon: ImageVector? = null,
) = StatusChip(text = text, tone = tone ?: Tone.MUTE, showsDot = tone != null, icon = icon, modifier = modifier)

fun supportTierLabel(tier: EmbeddedSupportTier): String = when (tier) {
    EmbeddedSupportTier.TOUCH_SUPPORTED -> "Touch"
    EmbeddedSupportTier.CONTROLLER_SUPPORTED -> "Controller"
    EmbeddedSupportTier.UNSUPPORTED -> "Browse only"
}

/**
 * How a platform or game can be played in the app. Aurora: a glass capsule 26 dp high with its
 * symbol and `label` 12.5. Riso: touch = ink fill with `ground` text; controller = 1.5 dp ink
 * outline; browse only = 1.5 dp dashed outline in `inkSecondary`.
 */
@Composable
fun SupportTierBadge(
    tier: EmbeddedSupportTier,
    modifier: Modifier = Modifier,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val icon = when (tier) {
        EmbeddedSupportTier.TOUCH_SUPPORTED -> Icons.Outlined.TouchApp
        EmbeddedSupportTier.CONTROLLER_SUPPORTED -> Icons.Outlined.SportsEsports
        EmbeddedSupportTier.UNSUPPORTED -> Icons.Outlined.Visibility
    }
    val label = supportTierLabel(tier)
    val role = theme.type.label
    if (theme.isRiso) {
        val ink = when (tier) {
            EmbeddedSupportTier.TOUCH_SUPPORTED -> c.ground
            EmbeddedSupportTier.CONTROLLER_SUPPORTED -> c.ink
            EmbeddedSupportTier.UNSUPPORTED -> c.inkSecondary
        }
        Row(
            modifier = modifier
                .heightIn(min = 22.dp)
                .then(
                    when (tier) {
                        EmbeddedSupportTier.TOUCH_SUPPORTED -> Modifier.background(c.ink)
                        EmbeddedSupportTier.CONTROLLER_SUPPORTED -> Modifier.border(theme.strokeWidth, c.ink)
                        EmbeddedSupportTier.UNSUPPORTED -> Modifier.drawBehind {
                            val w = 1.5.dp.toPx()
                            drawRect(
                                c.ink,
                                topLeft = Offset(w / 2, w / 2),
                                size = Size(size.width - w, size.height - w),
                                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                            )
                        }
                    },
                )
                .padding(horizontal = 7.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(11.dp))
            CasedText(label, role.uppercase, role.style.sized(10f), color = ink, maxLines = 1, overflow = TextOverflow.Visible)
        }
        return
    }
    val ink = if (tier == EmbeddedSupportTier.UNSUPPORTED) c.inkSecondary else c.ink
    Row(
        modifier = modifier
            .heightIn(min = 26.dp)
            .themedSurface(theme, 999.dp, fill = c.surface, nested = true)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
        CasedText(label, role.uppercase, role.style.sized(12.5f), color = ink, maxLines = 1, overflow = TextOverflow.Visible)
    }
}

/** A kicker's block colour (Riso): ink, or the pink / yellow plate. */
enum class KickerFill { INK, PINK, YELLOW }

/**
 * A dateline label (docs §2 "Kicker"). Aurora: a `section` label in `ink` and its `section`
 * detail in `inkSecondary`. Riso: `label` in an ink (or pink / yellow) block padded 7/5, then the
 * `label` detail in `inkSecondary`.
 */
@Composable
fun Kicker(
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    fill: KickerFill = KickerFill.INK,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        val role = theme.type.label
        Row(
            modifier = modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CasedText(
                label,
                uppercase = role.uppercase,
                style = role.style,
                color = if (fill == KickerFill.INK) c.ground else c.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                modifier = Modifier
                    .background(
                        when (fill) {
                            KickerFill.INK -> c.ink
                            KickerFill.PINK -> c.accent1
                            KickerFill.YELLOW -> c.accent3
                        },
                    )
                    .padding(horizontal = 7.dp, vertical = 5.dp),
            )
            detail?.let {
                CasedText(it, role.uppercase, role.style, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoleText(label, theme.type.section, color = c.ink, maxLines = 1)
        detail?.let { RoleText(it, theme.type.section, color = c.inkSecondary, maxLines = 1) }
    }
}

// ---- Progress -----------------------------------------------------------------------------------

/**
 * Determinate progress (docs §2). Aurora: a 5 dp capsule, track `ink` 12 %, fill `mark2 → mark1`
 * with a `mark1` glow (other tones: their colour). Riso: 14 dp of halftone dots (pitch 5, 55 %)
 * in a 1.5 dp ink outline; the fill in `accent2` with its own 1.5 dp ink outline.
 */
@Composable
fun RommioProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.TINT,
    height: Dp? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val clamped = progress.coerceIn(0f, 1f)
    val lit = tone == Tone.TINT || tone == Tone.LIVE
    val semantics = Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f) }
    if (theme.isRiso) {
        val dots = c.ink.copy(alpha = if (theme.isDark) 0.45f else 0.85f)
        val fill = if (lit) c.accent2 else tone.color(c)
        Box(
            modifier = modifier
                .then(semantics)
                .fillMaxWidth()
                .height(height ?: 14.dp)
                .drawBehind {
                    halftone(dots, 5.dp.toPx())
                    val stroke = 1.5.dp.toPx()
                    val w = size.width * clamped
                    if (w > 0f) {
                        drawRect(fill, size = Size(w, size.height))
                        drawRect(c.ink, topLeft = Offset(stroke / 2, stroke / 2), size = Size((w - stroke).coerceAtLeast(0f), size.height - stroke), style = Stroke(stroke))
                    }
                    drawRect(c.ink, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                },
        )
        return
    }
    val base = tone.color(c)
    val from = if (lit) theme.mark2 else base
    val to = if (lit) theme.mark1 else base
    val barHeight = height ?: 5.dp
    Box(
        modifier = modifier
            .then(semantics)
            .fillMaxWidth()
            .height(barHeight)
            .drawBehind {
                val h = size.height
                val radius = CornerRadius(h / 2)
                drawRoundRect(c.ink.copy(alpha = 0.12f), size = size, cornerRadius = radius)
                if (clamped > 0f) {
                    val w = maxOf(h, size.width * clamped)
                    // shadow(mark1 70 %, r6): a soft halo around the fill.
                    for (i in 1..4) {
                        val g = i * 1.5.dp.toPx()
                        drawRoundRect(
                            to.copy(alpha = 0.7f / 4 * (1f - i / 5f)),
                            topLeft = Offset(-g, -g),
                            size = Size(w + g * 2, h + g * 2),
                            cornerRadius = CornerRadius(h / 2 + g),
                        )
                    }
                    drawRoundRect(Brush.horizontalGradient(listOf(from, to), startX = 0f, endX = w), size = Size(w, h), cornerRadius = radius)
                }
            },
    )
}

/** A field of halftone dots at [pitch] (Riso progress tracks, stand-in art, empty chart days). */
fun DrawScope.halftone(dot: Color, pitch: Float, coverage: Float = RisoArtTokens.halftone) {
    val r = pitch * coverage * 0.25f
    var y = pitch / 2
    while (y < size.height) {
        var x = pitch / 2
        while (x < size.width) {
            drawCircle(dot, radius = r, center = Offset(x, y))
            x += pitch
        }
        y += pitch
    }
}

// ---- Choices ------------------------------------------------------------------------------------

/** A segmented single choice (Theme, Appearance, filters). Aurora: glass capsule; Riso: ink-ruled blocks. */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val riso = theme.isRiso
    val outer = if (riso) RoundedCornerShape(0.dp) else RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (riso) {
                    Modifier.border(theme.strokeWidth, c.ink)
                } else {
                    Modifier.themedSurface(theme, 999.dp, fill = c.surfaceStrong, nested = LocalInCard.current)
                },
            )
            .padding(if (riso) 0.dp else 3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (riso) 0.dp else 3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(outer)
                    .background(if (isSelected) c.buttonFill else Color.Transparent)
                    .then(
                        if (riso && index > 0) {
                            Modifier.drawBehind { drawRect(c.ink, size = Size(theme.strokeWidth.toPx(), size.height)) }
                        } else {
                            Modifier
                        },
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                val role = if (riso) theme.type.label else theme.type.bodyEmphasis
                CasedText(
                    text = label(option),
                    uppercase = role.uppercase,
                    style = if (riso) role.style.sized(11f) else role.style.sized(14f),
                    color = if (isSelected) c.buttonInk else c.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A filter chip (docs §2). Aurora: a glass capsule, `buttonFill` when selected, `bodyEmphasis` 14.
 * Riso: a 1.5 dp ink box, ink-filled when selected, `label` 10.5. Count in `numeral` 13 at 70 %.
 */
@Composable
fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    icon: ImageVector? = null,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val riso = theme.isRiso
    val shape = if (riso) RoundedCornerShape(0.dp) else RoundedCornerShape(percent = 50)
    val ink = if (selected) c.buttonInk else c.ink
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp)
            .defaultMinSize(minHeight = 36.dp)
            .then(
                when {
                    riso && selected -> Modifier.background(c.ink).border(1.5.dp, c.ink)
                    riso -> Modifier.border(1.5.dp, c.ink)
                    selected -> Modifier.clip(shape).background(c.buttonFill)
                    else -> Modifier.themedSurface(theme, 999.dp, fill = c.surface, nested = true)
                },
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp)) }
        val role = if (riso) theme.type.label else theme.type.bodyEmphasis
        CasedText(text, role.uppercase, role.style.sized(if (riso) 10.5f else 14f), color = ink, maxLines = 1)
        count?.let {
            Text(it.toString(), style = theme.type.numeral.style.sized(13f), color = ink.copy(alpha = 0.7f))
        }
    }
}

/**
 * A row's symbol (docs §2 "Icon tile"). Aurora: a 30 dp rounded-9 tile in the tone with a white
 * glyph (neutral: grey). Riso: a 32 dp 1.5 dp ink square with an ink glyph.
 */
@Composable
fun RowIcon(
    icon: ImageVector,
    tone: Tone = Tone.TINT,
    modifier: Modifier = Modifier,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        Box(
            modifier = modifier
                .size(32.dp)
                .border(1.5.dp, c.ink),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(17.dp))
        }
        return
    }
    val fill = if (tone == Tone.MUTE) Color(0xFF8C8C8C) else tone.color(c)
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}
