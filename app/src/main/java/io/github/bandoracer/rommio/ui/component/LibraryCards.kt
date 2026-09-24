package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.InstalledPlatformSummary
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl

/** A platform as a list row: logo, name, counts and how it plays in the app. */
@Composable
fun PlatformRow(
    platform: PlatformDto,
    imageBaseUrl: String?,
    summary: InstalledPlatformSummary?,
    supportTier: EmbeddedSupportTier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = RommioTheme.current
    val detail = buildString {
        append(pluralize(platform.romCount, "game"))
        summary?.takeIf { it.installedGameCount > 0 }?.let { append(" · ${it.installedGameCount} on this device") }
    }
    ListRow(
        title = platform.name,
        subtitle = detail,
        modifier = modifier,
        onClick = onClick,
        showChevron = true,
        leading = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(theme.radius.rowShape)
                    .background(theme.colors.surfaceStrong),
                contentAlignment = Alignment.Center,
            ) {
                PlatformLogo(platform = platform, imageBaseUrl = imageBaseUrl, supportTier = supportTier)
            }
        },
        trailing = { SupportTierBadge(supportTier) },
    )
}

/** Kept for existing call sites: a platform on its own card. */
@Composable
fun PlatformSpotlightCard(
    platform: PlatformDto,
    imageBaseUrl: String?,
    summary: InstalledPlatformSummary?,
    supportTier: EmbeddedSupportTier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListGroup(modifier = modifier) {
        PlatformRow(platform, imageBaseUrl, summary, supportTier, onClick)
    }
}

/** A game as a cover tile. */
@Composable
fun RomPosterCard(
    rom: RomDto,
    imageBaseUrl: String?,
    installed: Boolean,
    supportTier: EmbeddedSupportTier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPlatform: Boolean = true,
) {
    GameTile(
        title = rom.displayName,
        imageUrl = resolveRemoteAssetUrl(imageBaseUrl, rom.urlCover),
        onClick = onClick,
        modifier = modifier,
        subtitle = listOfNotNull(
            rom.platformName.takeIf { showPlatform && it.isNotBlank() },
            rom.metadata.releaseYear?.toString(),
            "On this device".takeIf { installed },
        ).joinToString(" · ").ifEmpty { null },
        dimmed = supportTier == EmbeddedSupportTier.UNSUPPORTED,
    )
}

fun collectionTypeLabel(collection: RommCollectionDto): String = when {
    collection.isVirtual -> "Automatic collection"
    collection.isSmart -> "Smart collection"
    collection.isFavorite -> "Favourites"
    else -> "Collection"
}

/** A collection card: a fan of up to three covers, its name and size. */
@Composable
fun CollectionCard(
    collection: RommCollectionDto,
    imageBaseUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackCoverUrls: List<String> = emptyList(),
) {
    val theme = RommioTheme.current
    val covers = remember(collection, fallbackCoverUrls, imageBaseUrl) {
        val direct = listOfNotNull(
            collection.pathCoverLarge, collection.pathCoverSmall,
        ) + collection.pathCoversLarge + collection.pathCoversSmall
        (fallbackCoverUrls + direct)
            .filter { it.isNotBlank() }
            .mapNotNull { resolveRemoteAssetUrl(imageBaseUrl, it) }
            .distinct()
            .take(3)
    }
    GlassSurface(
        modifier = modifier,
        onClick = onClick,
        onClickLabel = "Open ${collection.name}",
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(end = 24.dp)) {
            if (covers.isEmpty()) {
                GameCover(imageUrl = null, title = collection.name, modifier = Modifier.width(72.dp))
            } else {
                covers.forEachIndexed { index, url ->
                    GameCover(
                        imageUrl = url,
                        title = collection.name,
                        modifier = Modifier
                            .width(72.dp)
                            .offset(x = (index * 30).dp)
                            .zIndex((covers.size - index).toFloat()),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RoleText(text = collection.name, role = theme.type.bodyEmphasis, color = theme.colors.ink, maxLines = 2)
            RoleText(
                text = "${collectionTypeLabel(collection)} · ${pluralize(collection.romCount, "game")}",
                role = theme.type.caption,
                color = theme.colors.inkSecondary,
                maxLines = 1,
            )
        }
    }
}

/** Kept for existing call sites. */
@Composable
fun CollectionSpotlightCard(
    collection: RommCollectionDto,
    imageBaseUrl: String?,
    fallbackCoverUrls: List<String> = emptyList(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = CollectionCard(collection, imageBaseUrl, onClick, modifier, fallbackCoverUrls)

/** Kept for existing call sites. */
@Composable
fun SupportBadge(
    supportTier: EmbeddedSupportTier,
    modifier: Modifier = Modifier,
) = SupportTierBadge(supportTier, modifier)

@Composable
private fun PlatformLogo(
    platform: PlatformDto,
    imageBaseUrl: String?,
    supportTier: EmbeddedSupportTier,
) {
    val context = LocalContext.current
    val candidates = remember(platform.id, platform.slug, platform.fsSlug, platform.urlLogo, imageBaseUrl) {
        buildList {
            platform.urlLogo
                ?.takeIf { it.startsWith("file://") || it.startsWith("content://") }
                ?.let(::add)
            listOf(platform.slug, platform.fsSlug)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { slug ->
                    add(resolveRemoteAssetUrl(imageBaseUrl, "/assets/platforms/$slug.svg"))
                }
            add(resolveRemoteAssetUrl(imageBaseUrl, platform.urlLogo))
        }.filterNotNull().distinct()
    }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    var useSvgDecoder by remember(candidates, candidateIndex) { mutableStateOf(true) }
    val model = candidates.getOrNull(candidateIndex)?.let { candidate ->
        val isSvg = candidate.lowercase().endsWith(".svg")
        if (isSvg && useSvgDecoder) {
            ImageRequest.Builder(context)
                .data(candidate)
                .decoderFactory(SvgDecoder.Factory())
                .crossfade(true)
                .build()
        } else {
            candidate
        }
    }
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .size(34.dp)
                .alpha(if (supportTier == EmbeddedSupportTier.UNSUPPORTED) 0.6f else 1f),
            onError = {
                val current = candidates.getOrNull(candidateIndex)
                if (current?.lowercase()?.endsWith(".svg") == true && useSvgDecoder) {
                    useSvgDecoder = false
                } else if (candidateIndex < candidates.lastIndex) {
                    useSvgDecoder = true
                    candidateIndex += 1
                }
            },
        )
    } else {
        RoleText(
            text = platform.name.take(2),
            role = RommioTheme.type.label,
            color = RommioTheme.colors.inkSecondary,
        )
    }
}

/** Row of items with a fixed gap, used for inline tags. */
@Composable
fun InlineRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(RommioTheme.spacing.inline), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
