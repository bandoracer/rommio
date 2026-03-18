package io.github.mattsays.rommnative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mattsays.rommnative.ui.theme.BrandMuted
import io.github.mattsays.rommnative.ui.theme.BrandPanel
import io.github.mattsays.rommnative.ui.theme.BrandPanelAlt
import io.github.mattsays.rommnative.ui.theme.BrandSeed
import io.github.mattsays.rommnative.ui.theme.BrandText

@Composable
fun RommGradientBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(BrandCanvasBase),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            BrandSeed.copy(alpha = 0.08f),
                            BrandPanelAlt.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

@Composable
fun FeaturePanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    eyebrow: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ChromePanel(
        modifier = modifier,
        containerColor = BrandPanelAlt.copy(alpha = 0.94f),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
    ) {
        PanelHeader(
            title = title,
            subtitle = subtitle,
            badge = badge,
            eyebrow = eyebrow,
        )
        content()
    }
}

@Composable
fun CompactPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    badge: String? = null,
    eyebrow: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    ChromePanel(
        modifier = modifier,
        containerColor = BrandPanel.copy(alpha = 0.96f),
        contentPadding = contentPadding,
    ) {
        if (title != null || subtitle != null || badge != null || eyebrow != null) {
            PanelHeader(
                title = title.orEmpty(),
                subtitle = subtitle,
                badge = badge,
                eyebrow = eyebrow,
                compact = true,
            )
        }
        content()
    }
}

@Composable
fun HeroCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    FeaturePanel(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        badge = badge,
        content = content,
    )
}

@Composable
fun EmptyStatePanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    supportingText: String? = null,
) {
    CompactPanel(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        badge = badge,
        eyebrow = "Empty state",
    ) {
        supportingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun LoadingSkeletonPanel(
    modifier: Modifier = Modifier,
    showArtwork: Boolean = false,
    lines: Int = 3,
) {
    CompactPanel(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        if (showArtwork) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = RoundedCornerShape(18.dp),
            )
        }
        repeat(lines) { index ->
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(if (index == lines - 1) 0.55f else 1f)
                    .height(if (index == 0) 20.dp else 14.dp),
            )
        }
    }
}

@Composable
fun QuickActionTile(
    title: String,
    subtitle: String? = null,
    icon: ImageVector = Icons.Outlined.Bolt,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = BrandPanel.copy(alpha = 0.96f),
            contentColor = BrandText,
        ),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = BrandSeed.copy(alpha = 0.16f),
                contentColor = BrandSeed,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    meta: String? = null,
    supportingText: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (supportingText != null) 8.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandText,
                )
                meta?.let {
                    Surface(
                        color = BrandSeed.copy(alpha = 0.14f),
                        contentColor = BrandSeed,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            action?.invoke()
        }
        supportingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Bolt,
    accentColor: Color = BrandSeed,
) {
    Surface(
        modifier = modifier,
        color = BrandPanel.copy(alpha = 0.9f),
        contentColor = BrandText,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 84.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = accentColor.copy(alpha = 0.16f),
                contentColor = accentColor,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(18.dp),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = BrandMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun StatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    MetricTile(
        label = label,
        value = value,
        modifier = modifier,
    )
}

private val BrandCanvasBase = Color(0xFF101215)

@Composable
private fun ChromePanel(
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = BrandText,
        ),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun PanelHeader(
    title: String,
    subtitle: String?,
    badge: String?,
    eyebrow: String?,
    compact: Boolean = false,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            eyebrow?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandSeed,
                )
            }
            badge?.let {
                Surface(
                    shape = CircleShape,
                    color = BrandSeed.copy(alpha = 0.18f),
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = BrandSeed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = BrandText,
            )
        }
        subtitle?.let {
            Text(
                text = it,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(999.dp),
) {
    Spacer(
        modifier = modifier
            .clip(shape)
            .background(BrandPanelAlt.copy(alpha = 0.9f)),
    )
}

fun formatBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var current = value.toDouble()
    var index = 0
    while (current >= 1024.0 && index < units.lastIndex) {
        current /= 1024.0
        index += 1
    }
    val rounded = if (current >= 100 || index == 0) {
        current.toInt().toString()
    } else {
        String.format("%.1f", current)
    }
    return "$rounded ${units[index]}"
}
