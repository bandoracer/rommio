package io.github.mattsays.rommnative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.decode.SvgDecoder
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import io.github.mattsays.rommnative.domain.player.EmbeddedSupportTier
import io.github.mattsays.rommnative.model.InstalledPlatformSummary
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RommCollectionDto
import io.github.mattsays.rommnative.ui.theme.BrandPanel
import io.github.mattsays.rommnative.ui.theme.BrandPanelAlt
import io.github.mattsays.rommnative.ui.theme.BrandSeed
import io.github.mattsays.rommnative.ui.theme.BrandText
import io.github.mattsays.rommnative.util.resolveRemoteAssetUrl

@Composable
fun PlatformSpotlightCard(
    platform: PlatformDto,
    imageBaseUrl: String?,
    summary: InstalledPlatformSummary?,
    supportTier: EmbeddedSupportTier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = BrandPanelAlt.copy(alpha = 0.88f),
            contentColor = BrandText,
        ),
        border = when (supportTier) {
            EmbeddedSupportTier.TOUCH_SUPPORTED -> null
            else -> BorderStroke(1.dp, supportAccent(supportTier).copy(alpha = 0.7f))
        },
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (supportTier == EmbeddedSupportTier.UNSUPPORTED) BrandPanel.copy(alpha = 0.72f) else BrandPanel),
                contentAlignment = Alignment.Center,
            ) {
                PlatformLogo(
                    platform = platform,
                    imageBaseUrl = imageBaseUrl,
                    supportTier = supportTier,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    platform.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${platform.romCount} games in RomM",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                SupportBadge(supportTier)
                summary?.let {
                    Text(
                        "${it.installedGameCount} installed • ${formatBytes(it.totalBytes)} local",
                        color = BrandSeed,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun RomPosterCard(
    rom: RomDto,
    imageBaseUrl: String?,
    installed: Boolean,
    supportTier: EmbeddedSupportTier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = BrandPanelAlt.copy(alpha = 0.9f),
            contentColor = BrandText,
        ),
        border = when (supportTier) {
            EmbeddedSupportTier.TOUCH_SUPPORTED -> null
            else -> BorderStroke(1.dp, supportAccent(supportTier).copy(alpha = 0.7f))
        },
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncImage(
                model = resolveRemoteAssetUrl(imageBaseUrl, rom.urlCover),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.76f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .alpha(
                        when (supportTier) {
                            EmbeddedSupportTier.UNSUPPORTED -> 0.58f
                            EmbeddedSupportTier.CONTROLLER_SUPPORTED -> 0.92f
                            EmbeddedSupportTier.TOUCH_SUPPORTED -> 1f
                        },
                    ),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        rom.displayName,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        rom.platformName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SupportBadge(supportTier)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                    .height(34.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (installed) {
                        Surface(
                            color = BrandSeed.copy(alpha = 0.16f),
                            contentColor = BrandSeed,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = "Installed",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CollectionSpotlightCard(
    collection: RommCollectionDto,
    imageBaseUrl: String?,
    fallbackCoverUrls: List<String> = emptyList(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typeLabel = when {
        collection.isVirtual -> "Auto-generated"
        collection.isSmart -> "Smart collection"
        collection.isFavorite -> "Favourite"
        else -> "Manual collection"
    }
    val directCover = firstNonBlank(
        collection.pathCoverLarge,
        collection.pathCoversLarge.firstOrNull(),
        collection.pathCoverSmall,
        collection.pathCoversSmall.firstOrNull(),
    )
    val previewCovers = fallbackCoverUrls
        .mapNotNull { cover -> resolveRemoteAssetUrl(imageBaseUrl, cover) }
        .distinct()
        .take(3)
    val coverModel = directCover?.let { resolveRemoteAssetUrl(imageBaseUrl, it) }
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = BrandPanelAlt.copy(alpha = 0.88f),
            contentColor = BrandText,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (previewCovers.isNotEmpty()) {
                CollectionPreviewCollage(previewCoverUrls = previewCovers)
            } else if (coverModel != null) {
                SubcomposeAsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    loading = {
                        CollectionCoverFallback(
                            name = collection.name,
                            typeLabel = typeLabel,
                            romCount = collection.romCount,
                        )
                    },
                    error = {
                        CollectionCoverFallback(
                            name = collection.name,
                            typeLabel = typeLabel,
                            romCount = collection.romCount,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                )
            } else {
                CollectionCoverFallback(
                    name = collection.name,
                    typeLabel = typeLabel,
                    romCount = collection.romCount,
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(collection.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("$typeLabel • ${collection.romCount} games", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CollectionPreviewCollage(
    previewCoverUrls: List<String>,
) {
    val primaryCover = previewCoverUrls.first()
    val accentCovers = previewCoverUrls.drop(1)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(BrandPanel),
    ) {
        AsyncImage(
            model = primaryCover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (accentCovers.isNotEmpty()) {
            AsyncImage(
                model = accentCovers.first(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp)
                    .width(92.dp)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(18.dp)),
            )
        }
        if (accentCovers.size > 1) {
            AsyncImage(
                model = accentCovers[1],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 14.dp)
                    .width(82.dp)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(18.dp)),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color.Transparent,
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f),
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.28f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun CollectionCoverFallback(
    name: String,
    typeLabel: String,
    romCount: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        BrandPanelAlt,
                        BrandPanel,
                        BrandSeed.copy(alpha = 0.22f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = BrandSeed.copy(alpha = 0.16f),
                contentColor = BrandSeed,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = if (romCount == 0) "Empty collection" else typeLabel,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = name,
                color = BrandText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (romCount == 0) {
                    "Add games to this collection to generate artwork."
                } else {
                    "$romCount games available"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun SupportBadge(
    supportTier: EmbeddedSupportTier,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = supportAccent(supportTier).copy(alpha = 0.16f),
        contentColor = supportAccent(supportTier),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = when (supportTier) {
                EmbeddedSupportTier.TOUCH_SUPPORTED -> "Touch"
                EmbeddedSupportTier.CONTROLLER_SUPPORTED -> "Controller"
                EmbeddedSupportTier.UNSUPPORTED -> "Unsupported"
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

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
                .size(48.dp)
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
        Spacer(
            modifier = Modifier
                .size(44.dp)
                .alpha(0f),
        )
    }
}

private fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}

private fun supportAccent(supportTier: EmbeddedSupportTier): Color {
    return when (supportTier) {
        EmbeddedSupportTier.TOUCH_SUPPORTED -> TouchAccent
        EmbeddedSupportTier.CONTROLLER_SUPPORTED -> ControllerAccent
        EmbeddedSupportTier.UNSUPPORTED -> UnsupportedAccent
    }
}

private val TouchAccent = Color(0xFF6EDB83)
private val ControllerAccent = Color(0xFF7CB8FF)
private val UnsupportedAccent = BrandSeed.copy(red = 0.93f, green = 0.68f, blue = 0.34f)
