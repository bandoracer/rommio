package io.github.bandoracer.rommio.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.bandoracer.rommio.ui.art.CoverArt
import io.github.bandoracer.rommio.ui.art.RisoInks
import io.github.bandoracer.rommio.ui.art.RisoPrint
import io.github.bandoracer.rommio.ui.theme.RisoArtTokens
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.ui.theme.RommioThemeValue

/**
 * Stand-in art while a cover loads or when there is none. Aurora: the page's lights behind the
 * game's initials. Riso: a halftone proof in the blue plate with the initials in ink.
 */
@Composable
fun CoverPlaceholder(title: String, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    // Aurora's stand-in is drawn in the page's lights (the lit accents); Riso's plates never light.
    val c = if (theme.isRiso) theme.colors else RommioTheme.light.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (theme.isRiso) {
                    Modifier
                        .background(c.surfaceStrong)
                        .drawBehind { halftone(c.accent2.copy(alpha = if (theme.isDark) 0.5f else 0.35f), 6.dp.toPx()) }
                } else {
                    Modifier.background(
                        Brush.linearGradient(listOf(c.accent1.copy(alpha = 0.9f), c.accent2.copy(alpha = 0.9f), c.accent3.copy(alpha = 0.95f))),
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        RoleText(
            text = initials(title),
            role = theme.type.headline,
            color = if (theme.isRiso) c.ink.copy(alpha = 0.7f) else c.onAccent.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(6.dp).clearAndSetSemantics {},
        )
    }
}

/**
 * Cover art with the theme's treatment (docs/theme-layouts.md §2 "Cover"). Aurora: radius
 * `cover` (14), a 0.5 dp ink keyline at 10 %, shadow `#28143c` 28 % r16 y12 (dark: black 55 %).
 * Riso: printed in two inks, 1 dp ink keyline, no radius, no shadow. [aspect] null fills the
 * size the caller gives.
 */
@Composable
fun GameCover(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    elevated: Boolean = true,
    cornerRadius: Dp? = null,
    aspect: Float? = 3f / 4f,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    val sized = if (aspect != null) modifier.aspectRatio(aspect) else modifier
    if (theme.isRiso) {
        Box(
            modifier = sized
                .alpha(if (dimmed) 0.55f else 1f)
                .clearAndSetSemantics {},
        ) {
            CoverPlaceholder(title)
            if (!imageUrl.isNullOrBlank()) RisoPrintedImage(imageUrl, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().border(1.dp, c.ink))
        }
        return
    }
    val radius = cornerRadius ?: theme.radius.cover
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = sized
            .then(
                if (elevated && theme.material.coverShadow) {
                    Modifier.dropShadow(
                        if (theme.isDark) Color.Black.copy(alpha = 0.55f) else Color(0xFF28143C).copy(alpha = 0.28f),
                        radius,
                        16.dp,
                        12.dp,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .alpha(if (dimmed) 0.62f else 1f)
            .clearAndSetSemantics {},
    ) {
        CoverPlaceholder(title)
        if (!imageUrl.isNullOrBlank()) {
            val context = LocalContext.current
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                // A shown cover gets its palette worked out in the background, so its Game page
                // (or any page it lights) opens lit on the first frame.
                onSuccess = { CoverArt.warmPalette(context, imageUrl) },
            )
        }
        Box(Modifier.fillMaxSize().border(0.5.dp, c.ink.copy(alpha = if (theme.isDark) 0.14f else 0.1f), shape))
    }
}

/** The inks a Riso cover prints with, in pixels for the current density, for a cover [longestSide] long. */
@Composable
fun rememberRisoInks(theme: RommioThemeValue = RommioTheme.current, longestSide: Dp = 320.dp): RisoInks {
    val density = LocalDensity.current.density
    val c = theme.colors
    return RisoInks(
        paper = if (theme.isDark) 0x000000 else c.ground.toArgb() and 0xFFFFFF,
        plateA = c.accent1.toArgb() and 0xFFFFFF,
        plateB = c.accent2.toArgb() and 0xFFFFFF,
        dark = theme.isDark,
        halftone = RisoArtTokens.halftone,
        cell = RisoPrint.dotPitchDp(longestSide.value) * density,
        misregistration = RisoArtTokens.misregistration * density,
    )
}

/**
 * A cover printed in Riso's two inks (§1.5), rendered at the displayed pixel size off the main
 * thread and cached (cover URL + size + inks). Nothing shows until the print is ready.
 */
@Composable
fun RisoPrintedImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // The print fills the space its parent gives it; it asks for none in intrinsic measurements.
    BoxWithConstraints(modifier.then(FixedIntrinsics())) {
        val widthPx = constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: 0
        val heightPx = if (constraints.hasBoundedHeight) constraints.maxHeight else widthPx * 4 / 3
        if (widthPx <= 0 || heightPx <= 0) return@BoxWithConstraints
        val inks = rememberRisoInks(longestSide = with(density) { maxOf(widthPx, heightPx).toDp() })
        val (w, h) = CoverArt.printSize(widthPx, heightPx)
        val key = CoverArt.printKey(url, w, h, inks)
        val print by produceState(initialValue = CoverArt.cachedPrint(key), key) {
            if (value == null) value = CoverArt.print(context.applicationContext, url, w, h, inks)
        }
        AnimatedVisibility(visible = print != null, enter = fadeIn(), exit = fadeOut()) {
            print?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun initials(title: String): String = title
    .split(Regex("[^\\p{L}\\p{N}]+"))
    .filter { it.isNotBlank() }
    .take(2)
    .joinToString("") { it.first().uppercase() }

/**
 * A game in a shelf or grid (iOS `GameTile`): cover `width` × 4/3, title `bodyEmphasis` 14.5
 * (Riso 13.5, two lines reserved), detail `caption` 12 (Riso `label` 9.5).
 */
@Composable
fun GameTile(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dimmed: Boolean = false,
    width: Dp? = null,
    badge: (@Composable () -> Unit)? = null,
) {
    val theme = RommioTheme.current
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (theme.isRiso) 6.dp else 8.dp),
    ) {
        Box {
            GameCover(imageUrl = imageUrl, title = title, dimmed = dimmed, modifier = Modifier.fillMaxWidth())
            badge?.let { Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { it() } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RoleText(
                text = title,
                role = theme.type.bodyEmphasis,
                size = if (theme.isRiso) 13.5f else 14.5f,
                color = theme.colors.ink,
                maxLines = 2,
                minLines = 2,
            )
            subtitle?.let {
                RoleText(
                    text = it,
                    role = if (theme.isRiso) theme.type.label else theme.type.caption,
                    size = if (theme.isRiso) 9.5f else 12f,
                    color = theme.colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A small cover for list rows: 40 × 52 (radius 8, no shadow) by default. */
@Composable
fun CoverThumb(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    GameCover(
        imageUrl = imageUrl,
        title = title,
        modifier = modifier.size(width = size, height = size * 1.3f),
        elevated = false,
        cornerRadius = 8.dp,
        aspect = null,
    )
}

/**
 * A cover presented as a lit object (Aurora `GlowingCover`, studio `.a-gs`): its own art blurred
 * r26 at saturation 1.8 glows beneath it (55 % / 80 %, 86 % × 92 % at +14 % down), a top-left
 * sheen, and a reflection below at 14 %. Riso prints the cover flat.
 */
@Composable
fun GlowingCover(
    imageUrl: String?,
    title: String,
    height: Dp,
    modifier: Modifier = Modifier,
    reflection: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val theme = RommioTheme.current
    val width = height * 3f / 4f
    val radius = if (theme.isRiso) 0.dp else minOf(22.dp, height * 0.07f)
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .semantics(mergeDescendants = true) { contentDescription = "Cover art for $title" }
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onClick) else Modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (theme.isGlass) {
            val saturate = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.8f) }) }
            // The glow needs a real blur (API 31+); below that the cover keeps its shadow only.
            if (!imageUrl.isNullOrBlank() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = saturate,
                    modifier = Modifier
                        .requiredSize(width * 0.86f, height * 0.92f)
                        .offset(y = height * 0.14f)
                        .graphicsLayer { alpha = if (theme.isDark) 0.8f else 0.55f }
                        .blur(26.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
                )
            }
            if (reflection) {
                GameCover(
                    imageUrl = imageUrl,
                    title = title,
                    elevated = false,
                    cornerRadius = radius,
                    aspect = null,
                    modifier = Modifier
                        .size(width, height)
                        .offset(y = height + 5.dp)
                        .graphicsLayer {
                            scaleY = -1f
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            // Flipped: the top of this layer is the reflection's far end.
                            drawRect(
                                Brush.verticalGradient(0f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color.White.copy(alpha = 0.14f)),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }
        }
        GameCover(
            imageUrl = imageUrl,
            title = title,
            elevated = false,
            cornerRadius = radius,
            aspect = null,
            modifier = Modifier
                .size(width, height)
                .then(
                    if (theme.isGlass) {
                        Modifier.dropShadow(
                            if (theme.isDark) Color.Black.copy(alpha = 0.6f) else Color(0xFF29143D).copy(alpha = 0.35f),
                            radius,
                            22.dp,
                            18.dp,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
        if (theme.isGlass) {
            Box(
                Modifier
                    .size(width, height)
                    .clip(shape)
                    .background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.16f), 0.35f to Color.Transparent)),
            )
        }
    }
}

/**
 * Another game in progress as a small lit card (Aurora `RoomCard`, studio `.a-rc`): the cover
 * under a 78 % black scrim, the platform `section` 10 in white 72 %, the title and the relative
 * time; selected = a 2 dp `mark1` outline.
 */
@Composable
fun RoomCard(
    title: String,
    platformSlug: String,
    detail: String?,
    imageUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 176.dp,
    /** The card's height (0.76 × [width] when not given). */
    height: Dp? = null,
    /** A captured frame (a resume point's preview) shown instead of the cover; never art-treated. */
    previewPath: String? = null,
    showsPlatform: Boolean = true,
) {
    val theme = RommioTheme.current
    val shape = RoundedCornerShape(18.dp)
    val height = height ?: (width * 0.76f)
    Box(
        modifier = modifier
            .size(width, height)
            .dropShadow(Color.Black.copy(alpha = if (theme.isDark) 0.45f else 0.16f), 18.dp, 14.dp, 10.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "Show $title", onClick = onClick)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.BottomStart,
    ) {
        CoverPlaceholder(title)
        if (previewPath != null) {
            AsyncImage(
                model = java.io.File(previewPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                modifier = Modifier.fillMaxSize().background(OverArt.frameGround),
            )
        } else if (!imageUrl.isNullOrBlank()) {
            val context = LocalContext.current
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { CoverArt.warmPalette(context, imageUrl) },
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showsPlatform) RoleText(platformSlug.uppercase(), theme.type.section, size = 10f, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
            RoleText(title, theme.type.bodyEmphasis, size = 15f, color = Color.White, maxLines = 1)
            detail?.let { RoleText(it, theme.type.caption, size = 11.5f, color = Color.White.copy(alpha = 0.76f), maxLines = 1) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .border(if (selected) 2.dp else 0.5.dp, if (selected) RommioTheme.light.mark1 else Color.White.copy(alpha = 0.14f), shape),
        )
    }
}

/** Page dots coloured by each game's light; the current one stretches to 18 dp. */
@Composable
fun PagerDots(
    colours: List<Color>,
    current: Int,
    modifier: Modifier = Modifier,
    onSelect: ((Int) -> Unit)? = null,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "Game ${current + 1} of ${colours.size}" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colours.forEachIndexed { index, colour ->
            Box(
                modifier = Modifier
                    .widthIn(min = 24.dp)
                    .heightIn(min = 44.dp)
                    .clickable(enabled = onSelect != null, onClick = { onSelect?.invoke(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .animateContentSize()
                        .size(width = if (index == current) 18.dp else 6.dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(colour.copy(alpha = if (index == current) 1f else 0.75f)),
                )
            }
        }
    }
}

/** A 3 dp strip of light made of a day's games (Aurora day headers). */
@Composable
fun LightStrip(colours: List<Color>, modifier: Modifier = Modifier) {
    val stops = when {
        colours.isEmpty() -> listOf(Color.Transparent, Color.Transparent)
        colours.size == 1 -> listOf(colours[0], colours[0])
        else -> colours
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(stops)),
    )
}

/** Connection and sync in one quiet line: a 7 dp `ok`/`wait` dot and `caption` 12.5. */
@Composable
fun SyncStatusLine(text: String, offline: Boolean, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (offline) theme.colors.wait else theme.colors.ok))
        RoleText(text, theme.type.caption, size = 12.5f, color = theme.colors.inkSecondary, maxLines = 1)
    }
}

/**
 * A collection with its cover art. Aurora: the art 0.62 as high as wide under a dark gradient with
 * the name and count in white, on a rounded card. Riso: the printed art, then `label` "Collection",
 * `headline` 22 name and `label` count.
 */
@Composable
fun CollectionTile(
    name: String,
    count: Int,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 240.dp,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        Column(
            modifier = modifier
                .width(width)
                .clickable(role = Role.Button, onClickLabel = "Open $name", onClick = onClick)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            GameCover(imageUrl, name, Modifier.size(width, width * 0.62f), aspect = null)
            RoleText("Collection", theme.type.label, size = 9.5f, color = c.inkSecondary)
            RoleText(name, theme.type.headline, size = 22f, color = c.ink, maxLines = 2)
            RoleText(PlayFormat.games(count), theme.type.label, size = 9.5f, color = c.inkSecondary)
        }
        return
    }
    val radius = theme.radius.card * 0.8f
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .size(width, width * 0.62f)
            .dropShadow(Color.Black.copy(alpha = if (theme.isDark) 0.4f else 0.12f), radius, 14.dp, 8.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "Open $name", onClick = onClick)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.BottomStart,
    ) {
        CoverPlaceholder(name)
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RoleText(name, theme.type.bodyEmphasis, color = Color.White, maxLines = 2)
            RoleText(PlayFormat.games(count), theme.type.caption, color = Color.White.copy(alpha = 0.85f))
        }
        Box(Modifier.fillMaxSize().border(0.5.dp, Color.White.copy(alpha = if (theme.isDark) 0.1f else 0.4f), shape))
    }
}

/** A white mount around a small print (Riso session diaries), rotated a little. */
@Composable
fun MountedPrint(
    imageUrl: String?,
    title: String,
    width: Dp,
    height: Dp,
    rotation: Float,
    modifier: Modifier = Modifier,
) {
    val theme = RommioTheme.current
    Box(
        modifier
            .graphicsLayer { rotationZ = rotation }
            .background(theme.colors.surface)
            .padding(3.dp),
    ) {
        GameCover(imageUrl, title, Modifier.size(width, height), aspect = null)
    }
}

/** Scales content with [factor] (used by the Aurora cover row's scroll transition). */
internal fun Modifier.scaled(factor: Float): Modifier = scale(factor)
