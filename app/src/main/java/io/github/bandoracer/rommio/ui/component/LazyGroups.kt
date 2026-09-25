package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.ui.theme.LocalRommioTheme
import io.github.bandoracer.rommio.ui.theme.RommioTheme

/**
 * Row groups whose rows are lazy items (docs/theme-layouts.md §2 "Row group"), so a long list in a
 * group builds only the rows on screen and keeps the exact look of [ListGroup]:
 *
 * - Aurora: one glass card behind the group's rows. The card is drawn by the list itself
 *   ([drawCardGroups]) from where its rows are laid out — shadow, blurred field, fill and glass
 *   edge exactly as [themedSurface] draws a card — so it is one card, not one per row.
 * - Riso: no card; the rows run on the paper (no inset) and a hairline closes the group.
 *
 * Register the groups of a list with a remembered [LazyCardGroups] and put [drawCardGroups] on the
 * `LazyColumn`.
 */
@Stable
class LazyCardGroups {
    /** Where each row sits in its group, by item key (written as the list's items are built). */
    internal val rows = HashMap<Any, RowSlot>()
    internal val groups = HashMap<Any, List<Any>>()
    /** Last measured height of each row, by item key (for the parts of a card off screen). */
    internal val sizes = HashMap<Any, Int>()

    internal fun register(group: Any, keys: List<Any>) {
        val previous = groups.put(group, keys)
        if (previous == keys) return
        previous?.forEach { rows.remove(it) }
        keys.forEachIndexed { index, key -> rows[key] = RowSlot(group, index, keys.size) }
    }
}

internal data class RowSlot(val group: Any, val index: Int, val count: Int)

/** The content type of a row in a card group: rows of a group share their compositions. */
@Immutable
data class CardRowType(val group: Any)

@Composable
fun rememberLazyCardGroups(): LazyCardGroups = remember { LazyCardGroups() }

/**
 * A row group as lazy items: an optional [title] header (with [meta]), the [items] as one lazy
 * item each (with [ListDivider]s between them when [dividerInset] is set), and an optional [footer].
 * The first item's top padding is [top] (the gap to what is above, as a column's spacing).
 */
fun <T> LazyListScope.lazyListGroup(
    groups: LazyCardGroups,
    riso: Boolean,
    key: String,
    items: List<T>,
    itemKey: (T) -> Any,
    title: String? = null,
    meta: String? = null,
    footer: String? = null,
    top: Dp = 0.dp,
    titleModifier: Modifier = Modifier,
    dividerInset: Dp? = 16.dp,
    row: @Composable (index: Int, item: T) -> Unit,
) {
    val keys = items.map { "$key/${itemKey(it)}" }
    groups.register(key, keys)
    // ListGroup: header, 8 dp, the card, 8 dp, the footer (Riso: no gaps, the rows close with a rule).
    val headerGap = if (riso) 0.dp else 8.dp
    if (title != null) {
        item(key = "$key/title", contentType = "groupTitle") {
            SectionHeader(
                title = title,
                meta = meta,
                modifier = titleModifier.padding(top = top).padding(horizontal = if (riso) 0.dp else 6.dp),
            )
        }
    }
    // The gap above the card is an item of its own, so the card starts at its first row.
    val firstTop = if (title != null) headerGap else top
    if (firstTop > 0.dp && items.isNotEmpty()) {
        item(key = "$key/gap", contentType = "groupGap") { Box(Modifier.height(firstTop)) }
    }
    items.forEachIndexed { index, value ->
        item(key = keys[index], contentType = CardRowType(key)) {
            GroupRow(riso) {
                if (index > 0 && dividerInset != null) ListDivider(inset = dividerInset)
                row(index, value)
            }
        }
    }
    if (riso) {
        item(key = "$key/close", contentType = "groupClose") {
            Box(Modifier.fillMaxWidth().height(1.dp).background(RommioTheme.colors.hairline))
        }
    }
    if (footer != null) {
        item(key = "$key/footer", contentType = "groupFooter") {
            val theme = RommioTheme.current
            RoleText(
                text = footer,
                role = theme.type.caption,
                color = theme.colors.inkSecondary,
                modifier = Modifier.padding(top = headerGap).padding(horizontal = if (riso) 0.dp else 6.dp, vertical = if (riso) 8.dp else 0.dp),
            )
        }
    }
}

/** A row inside a group: in the card (Aurora) or flush on the paper (Riso). */
@Composable
private fun GroupRow(riso: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val theme = RommioTheme.current
    if (riso) {
        CompositionLocalProvider(LocalRowInset provides 0.dp) {
            androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) { content() }
        }
    } else {
        CompositionLocalProvider(LocalContentColor provides theme.colors.ink, LocalInCard provides true) {
            androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) { content() }
        }
    }
}

/**
 * Draws the Aurora card of each [LazyCardGroups] group behind its rows (nothing in Riso). Put it
 * on the `LazyColumn`; [horizontalPadding] is the list's horizontal content padding. The cards
 * are clipped vertically to the list, as a scrolling column clips its content.
 */
fun Modifier.drawCardGroups(state: LazyListState, groups: LazyCardGroups, horizontalPadding: Dp): Modifier =
    this.then(CardGroupsElement(state, groups, horizontalPadding))

private data class CardGroupsElement(
    val state: LazyListState,
    val groups: LazyCardGroups,
    val horizontalPadding: Dp,
) : ModifierNodeElement<CardGroupsNode>() {
    override fun create() = CardGroupsNode(state, groups, horizontalPadding)
    override fun update(node: CardGroupsNode) {
        node.state = state
        node.groups = groups
        node.horizontalPadding = horizontalPadding
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "drawCardGroups"
    }
}

private class CardGroupsNode(
    var state: LazyListState,
    var groups: LazyCardGroups,
    var horizontalPadding: Dp,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var coordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun ContentDrawScope.draw() {
        val theme = currentValueOf(LocalRommioTheme)
        if (!theme.isGlass) {
            drawContent()
            return
        }
        // Reading the layout info redraws the cards as the list scrolls (the list redraws anyway).
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        val spans = LinkedHashMap<Any, CardSpan>()
        for (item in visible) {
            val slot = groups.rows[item.key] ?: continue
            if (item.contentType !is CardRowType) continue
            groups.sizes[item.key] = item.size
            val span = spans.getOrPut(slot.group) { CardSpan(slot.group) }
            // Item offsets start after the list's top content padding (viewportStartOffset is its negative).
            span.include(slot, item.offset - info.viewportStartOffset, item.size)
        }
        if (spans.isNotEmpty()) {
            val radius = theme.radius.card
            val radiusPx = radius.toPx()
            val shadow = theme.glassShadow()
            val left = horizontalPadding.toPx()
            val width = size.width - left * 2
            val backdrop = currentValueOf(LocalGlassBackdrop)
            val fieldOffset = backdrop?.let { fieldOffsetOf(it) }
            clipRect(left = -size.width, top = 0f, right = size.width * 2, bottom = size.height) {
                for (span in spans.values) {
                    val keys = groups.groups[span.group] ?: continue
                    val (top, bottom) = span.bounds(keys, groups.sizes)
                    val cardSize = Size(width, bottom - top)
                    if (cardSize.height <= 0f) continue
                    translate(left, top) {
                        drawSoftShadow(shadow.color, radius, offsetY = shadow.y, blur = shadow.radius * 2, size = cardSize)
                        val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, cardSize.width, cardSize.height, CornerRadius(radiusPx))) }
                        clipPath(shape) {
                            if (backdrop != null && fieldOffset != null) {
                                // The blurred field under this card (a cached layer; nothing is re-blurred).
                                translate(-(fieldOffset.x + left), -(fieldOffset.y + top)) { with(backdrop) { drawBlurredField() } }
                            }
                            drawRect(theme.colors.surface, size = cardSize)
                            glassEdge(radiusPx, theme.isDark, 1f, size = cardSize)
                        }
                    }
                }
            }
        }
        drawContent()
    }

    /** This list's position in the page field, when the field is in the same window. */
    private fun fieldOffsetOf(backdrop: GlassBackdrop): Offset? {
        val field = backdrop.fieldCoordinates ?: return null
        val me = coordinates ?: return null
        if (!field.isAttached || !me.isAttached || field.findRootCoordinates() !== me.findRootCoordinates()) return null
        return field.localPositionOf(me, Offset.Zero)
    }
}

/** The visible rows of one card, and from them the whole card's top and bottom. */
private class CardSpan(val group: Any) {
    private var firstIndex = Int.MAX_VALUE
    private var firstTop = 0
    private var lastIndex = -1
    private var lastBottom = 0
    private var count = 0

    fun include(slot: RowSlot, offset: Int, size: Int) {
        count = slot.count
        if (slot.index < firstIndex) {
            firstIndex = slot.index
            firstTop = offset
        }
        if (slot.index > lastIndex) {
            lastIndex = slot.index
            lastBottom = offset + size
        }
    }

    /**
     * The card's top and bottom in list coordinates: exact when its first and last rows are laid
     * out; otherwise extended by the rows off screen (their last measured heights, or the average).
     */
    fun bounds(keys: List<Any>, sizes: Map<Any, Int>): Pair<Float, Float> {
        val known = keys.mapNotNull { sizes[it] }
        val average = if (known.isEmpty()) 0f else known.average().toFloat()
        fun height(index: Int): Float = keys.getOrNull(index)?.let { sizes[it]?.toFloat() } ?: average
        var top = firstTop.toFloat()
        for (i in 0 until firstIndex) top -= height(i)
        var bottom = lastBottom.toFloat()
        for (i in lastIndex + 1 until count) bottom += height(i)
        return top to bottom
    }
}
