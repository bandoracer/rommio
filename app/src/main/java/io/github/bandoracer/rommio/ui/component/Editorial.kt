package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.ui.theme.RoleStyle
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/**
 * A display headline (docs/theme-layouts.md §2 "Display text"). Aurora sets it plainly in `ink`.
 * Riso prints it twice: the ink, and an `accent1` copy off register behind it by
 * [plateOffset] × misregistration (multiply on paper, screen on black stock).
 */
@Composable
fun DisplayText(
    text: String,
    modifier: Modifier = Modifier,
    role: RoleStyle = RommioTheme.type.display,
    size: Float? = null,
    plateOffset: Float = 1.5f,
    maxLines: Int = 3,
) {
    val theme = RommioTheme.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val base = size ?: role.style.fontSize.value
    val shown = if (role.uppercase) text.uppercase(java.util.Locale.getDefault()) else text
    val platePx = if (theme.isRiso) with(androidx.compose.ui.platform.LocalDensity.current) { (theme.material.misregistration * plateOffset).dp.roundToPx() } else 0
    // Like the iOS minimumScaleFactor(0.55): shrink to fit maxLines (never below 55 %).
    fun fit(widthPx: Int): Pair<Float, Int> {
        var s = base
        var result = measurer.measure(androidx.compose.ui.text.AnnotatedString(shown), style = role.style.sized(s), maxLines = maxLines,
            softWrap = true, constraints = androidx.compose.ui.unit.Constraints(maxWidth = widthPx))
        while (result.hasVisualOverflow && s * 0.95f > base * 0.55f) {
            s *= 0.95f
            result = measurer.measure(androidx.compose.ui.text.AnnotatedString(shown), style = role.style.sized(s), maxLines = maxLines,
                softWrap = true, constraints = androidx.compose.ui.unit.Constraints(maxWidth = widthPx))
        }
        return s to result.size.height + platePx
    }
    // BoxWithConstraints cannot answer intrinsic measurements (rows ruled to their tallest column
    // ask for them), so this modifier answers with the fitted text's height instead.
    val intrinsics = androidx.compose.runtime.remember(shown, base, maxLines, role, platePx) {
        FixedIntrinsics(heightFor = { w -> if (w == androidx.compose.ui.unit.Constraints.Infinity) fit(Int.MAX_VALUE / 2).second else fit(w).second })
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.then(intrinsics)) {
        val widthPx = constraints.maxWidth
        val fitted = androidx.compose.runtime.remember(shown, base, widthPx, maxLines, role) {
            if (!constraints.hasBoundedWidth) base else fit(widthPx).first
        }
        DisplayTextBody(text, role, fitted, plateOffset, maxLines)
    }
}

/**
 * Answers a parent's intrinsic measurements itself (height from [heightFor], width 0) so content
 * built on SubcomposeLayout (BoxWithConstraints) can sit in rows sized to their tallest column.
 */
internal class FixedIntrinsics(private val heightFor: (width: Int) -> Int = { 0 }) : androidx.compose.ui.layout.LayoutModifier {
    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurable: androidx.compose.ui.layout.Measurable,
        constraints: androidx.compose.ui.unit.Constraints,
    ): androidx.compose.ui.layout.MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicHeight(measurable: androidx.compose.ui.layout.IntrinsicMeasurable, width: Int): Int = heightFor(width)
    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicHeight(measurable: androidx.compose.ui.layout.IntrinsicMeasurable, width: Int): Int = heightFor(width)
    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicWidth(measurable: androidx.compose.ui.layout.IntrinsicMeasurable, height: Int): Int = 0
    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicWidth(measurable: androidx.compose.ui.layout.IntrinsicMeasurable, height: Int): Int = 0
}

@Composable
private fun DisplayTextBody(text: String, role: RoleStyle, size: Float, plateOffset: Float, maxLines: Int) {
    val theme = RommioTheme.current
    val c = theme.colors
    val modifier = Modifier
    if (!theme.isRiso) {
        RoleText(text, role, modifier = modifier.semantics { heading() }, size = size, color = c.ink, maxLines = maxLines)
        return
    }
    val offset = (theme.material.misregistration * plateOffset).dp
    Box(modifier.semantics { heading() }) {
        // The plate: the same words in pink, off register (hidden from accessibility). Over paper
        // (near white by day, black by night) multiply / screen leave the pink as it is, and the
        // ink overprints it, so both are painted normally.
        RoleText(
            text,
            role,
            size = size,
            color = c.accent1,
            maxLines = maxLines,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .offset(offset, offset)
                .clearAndSetSemantics {},
        )
        RoleText(text, role, size = size, color = c.ink, maxLines = maxLines)
    }
}

/** A printed rule: a 1 dp hairline, a 1 dp ink rule, the 1.5 dp keyline, or the 3 dp heavy rule. */
enum class RuleWeight { HAIRLINE, FINE, KEYLINE, HEAVY }

@Composable
fun PrintRule(modifier: Modifier = Modifier, weight: RuleWeight = RuleWeight.KEYLINE) {
    val theme = RommioTheme.current
    Box(
        modifier
            .fillMaxWidth()
            .height(
                when (weight) {
                    RuleWeight.HAIRLINE, RuleWeight.FINE -> 1.dp
                    RuleWeight.KEYLINE -> theme.strokeWidth
                    RuleWeight.HEAVY -> 3.dp
                },
            )
            .background(if (weight == RuleWeight.HAIRLINE) theme.colors.hairline else theme.colors.ink),
    )
}

/** A vertical 1 dp ink rule between columns (Riso). */
@Composable
fun ColumnRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(1.dp).background(RommioTheme.colors.ink))
}

/** A dateline (Riso): a 3 dp ink rule above, a 1 dp rule below, `label` 10 between. */
@Composable
fun Dateline(leading: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.ink))
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            RoleText(leading, theme.type.label, size = 10f, color = c.ink, maxLines = 1, modifier = Modifier.weight(1f))
            trailing?.let { RoleText(it, theme.type.label, size = 10f, color = c.inkSecondary, maxLines = 1, modifier = Modifier.padding(start = 10.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink))
    }
}

/**
 * A boxed feature (docs §2 "Ink box"). Riso: a 2 dp ink box with an ink header bar (`section`
 * 15, `ground` text) and `label` 10 detail. Aurora: a glass card with a `section` header and a
 * `numeral` 12.5 detail.
 */
@Composable
fun InkBox(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        Column(modifier.fillMaxWidth().border(2.dp, c.ink)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.ink)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoleText(title, theme.type.section, size = 15f, color = c.ground, maxLines = 1, modifier = Modifier.weight(1f))
                detail?.let { RoleText(it, theme.type.label, size = 10f, color = c.ground, maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
        return
    }
    GlassSurface(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
            RoleText(title, theme.type.section, color = c.inkSecondary, maxLines = 1, modifier = Modifier.weight(1f))
            detail?.let { RoleText(it, theme.type.numeral, size = 12.5f, color = c.inkTertiary, maxLines = 1) }
        }
        content()
    }
}

/**
 * A term and its value in a details box. Riso: the term in `label` 10 in a 96 dp column, the value
 * in `body` 14.5. Aurora: the term in `secondary` `inkSecondary`, the value in `bodyEmphasis` at the end.
 */
@Composable
fun SpecRow(term: String, value: String, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoleText(term, theme.type.label, size = 10f, color = c.ink, modifier = Modifier.width(96.dp).padding(top = 3.dp))
            RoleText(value, theme.type.body, size = 14.5f, color = c.ink, modifier = Modifier.weight(1f))
        }
        return
    }
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoleText(term, theme.type.secondary, color = c.inkSecondary)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            RoleText(value, theme.type.bodyEmphasis, color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/** An italic caption under a figure (Riso). */
@Composable
fun FigureCaption(text: String, modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    RoleText(text, theme.type.caption, size = 13f, italic = theme.isRiso, color = theme.colors.inkSecondary, modifier = modifier)
}

/**
 * Two top-aligned columns sharing the width by [ratio] (the studio's `1.5fr 1fr` grids), with an
 * optional 1 dp ink rule between them.
 */
@Composable
fun RatioColumns(
    modifier: Modifier = Modifier,
    ratio: Float = 0.6f,
    spacing: Dp = 14.dp,
    rule: Boolean = false,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(ratio), content = first)
        if (rule) {
            Box(Modifier.padding(horizontal = spacing / 2)) { ColumnRule() }
        } else {
            Box(Modifier.width(spacing))
        }
        Column(Modifier.weight(1f - ratio), content = second)
    }
}

/**
 * An index entry: [start] at its natural width (up to what is left), dot leaders filling the gap,
 * then [end]; start and end share a baseline and the dots sit on it (Riso contents, leaders).
 */
@Composable
fun LeaderLayout(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(start, { DotLeader() }, end),
        modifier = modifier,
    ) { (startMeasurables, leaderMeasurables, endMeasurables), constraints ->
        val gapPx = gap.roundToPx()
        val minLeader = 14.dp.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 10_000
        val loose = androidx.compose.ui.unit.Constraints(maxWidth = width)
        val endPlaceable = endMeasurables.firstOrNull()?.measure(loose)
        val endWidth = endPlaceable?.width ?: 0
        val startMax = (width - endWidth - gapPx * 2 - minLeader).coerceAtLeast(0)
        val startPlaceable = startMeasurables.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = startMax))
        val leaderWidth = (width - startPlaceable.width - endWidth - gapPx * 2).coerceAtLeast(0)
        val leaderPlaceable = leaderMeasurables.first().measure(androidx.compose.ui.unit.Constraints.fixed(leaderWidth, 8.dp.roundToPx()))
        fun baseline(p: androidx.compose.ui.layout.Placeable): Int =
            p[androidx.compose.ui.layout.FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: p.height
        val startBase = baseline(startPlaceable)
        val endBase = endPlaceable?.let(::baseline) ?: startBase
        val base = maxOf(startBase, endBase)
        val height = maxOf(base + (startPlaceable.height - startBase), base + ((endPlaceable?.height ?: 0) - endBase), base + 2.dp.roundToPx())
        layout(width, height, mapOf(androidx.compose.ui.layout.FirstBaseline to base)) {
            startPlaceable.place(0, base - startBase)
            leaderPlaceable.place(startPlaceable.width + gapPx, base - leaderPlaceable.height + 2.dp.roundToPx())
            endPlaceable?.place(width - endWidth, base - endBase)
        }
    }
}

/** A row with the Riso column rule between its cells. */
@Composable
fun RuledRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top, content = content)
}
