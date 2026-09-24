package io.github.bandoracer.rommio.ui.art

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A cover's five-colour palette in the studio's order (`StudioArt.palette`): four base colours,
 * then the accent, as `0xRRGGBB` ints. The extraction is the cross-platform contract in
 * docs/theme-layouts.md §1.1, identical to iOS `CoverPaletteExtractor`:
 *
 * 1. The cover is decoded at [DECODE_SIZE] px (longest side) and area-averaged down to at most
 *    [GRID_SIZE] × [GRID_SIZE] cells ([gridPixels]). Cells with alpha ≤ 16/255 are dropped; the
 *    rest are unpremultiplied.
 * 2. Median cut into [CLUSTER_COUNT] boxes: repeatedly split the box with the largest
 *    (channel range × pixel count) at the median of its widest channel.
 * 3. K-means, [PASSES] passes, seeded with the box means; distance `2Δr² + 4Δg² + 3Δb²`, ties to the
 *    lower cluster index; means quantised to 8 bits.
 * 4. Clusters sorted by population (ties by hex). Accent = the cluster holding at least
 *    [ACCENT_MIN_SHARE] that maximises `chroma × √share` (ties: lower index). Base = the next four
 *    clusters by population without the accent, padded with the last.
 *
 * A palette whose five colours all have chroma below [NEUTRAL_CHROMA] is neutral ([isNeutral]):
 * pages keep the theme's own light.
 */
object CoverPalette {
    const val DECODE_SIZE = 96
    const val GRID_SIZE = 24
    const val CLUSTER_COUNT = 8
    const val PASSES = 6
    const val ACCENT_MIN_SHARE = 0.02
    const val NEUTRAL_CHROMA = 0.06
    /** Cells at or below this alpha (of 255) are transparent and skipped. */
    const val ALPHA_CUTOFF = 16

    /** Nothing readable (all transparent): mid grey, which is neutral. */
    val Neutral: List<Int> = List(5) { 0x808080 }

    /** An sRGB colour with 0–1 channels. */
    data class Rgb(val r: Double, val g: Double, val b: Double) {
        val hex: Int get() = (byte(r) shl 16) or (byte(g) shl 8) or byte(b)

        companion object {
            fun of(rgb: Int): Rgb = Rgb((rgb shr 16 and 0xFF) / 255.0, (rgb shr 8 and 0xFF) / 255.0, (rgb and 0xFF) / 255.0)
        }
    }

    data class Cluster(val mean: Int, val count: Int)

    /** The palette of a decoded cover: ARGB (non-premultiplied, as `Bitmap.getPixels`) of [width] × [height]. */
    fun extract(argb: IntArray, width: Int, height: Int): List<Int> = palette(gridPixels(argb, width, height))

    /** A palette is neutral when no colour in it carries chroma: the theme keeps its own light. */
    fun isNeutral(palette: List<Int>): Boolean =
        palette.all { AuroraLight.chroma(AuroraLight.toHsl(it)) < NEUTRAL_CHROMA }

    /**
     * Area-averages the image to at most [GRID_SIZE] cells on its longer side (each cell is the
     * exact area-weighted mean of the source pixels it covers, averaged premultiplied like a
     * filtered downscale), drops transparent cells and unpremultiplies the rest.
     */
    fun gridPixels(argb: IntArray, width: Int, height: Int): List<Rgb> {
        require(argb.size >= width * height)
        if (width <= 0 || height <= 0) return emptyList()
        val scale = GRID_SIZE.toDouble() / max(width, height)
        val gw = max(1, (width * scale).roundToInt()).coerceAtMost(width)
        val gh = max(1, (height * scale).roundToInt()).coerceAtMost(height)
        val sx = width.toDouble() / gw
        val sy = height.toDouble() / gh
        val out = ArrayList<Rgb>(gw * gh)
        for (cy in 0 until gh) {
            val y0 = cy * sy
            val y1 = (cy + 1) * sy
            for (cx in 0 until gw) {
                val x0 = cx * sx
                val x1 = (cx + 1) * sx
                var a = 0.0
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var area = 0.0
                var y = y0.toInt()
                while (y < y1 && y < height) {
                    val wy = min(y1, y + 1.0) - max(y0, y.toDouble())
                    var x = x0.toInt()
                    while (x < x1 && x < width) {
                        val wx = min(x1, x + 1.0) - max(x0, x.toDouble())
                        val w = wx * wy
                        if (w > 0) {
                            val p = argb[y * width + x]
                            val alpha = (p ushr 24) / 255.0
                            a += alpha * w
                            r += (p shr 16 and 0xFF) / 255.0 * alpha * w
                            g += (p shr 8 and 0xFF) / 255.0 * alpha * w
                            b += (p and 0xFF) / 255.0 * alpha * w
                            area += w
                        }
                        x++
                    }
                    y++
                }
                if (area <= 0) continue
                // Rendered to 8 bits premultiplied, then read back (as the iOS grid is).
                val alpha8 = byte(a / area)
                if (alpha8 <= ALPHA_CUTOFF) continue
                val alpha = alpha8 / 255.0
                out += Rgb(
                    min(1.0, byte(r / area) / 255.0 / alpha),
                    min(1.0, byte(g / area) / 255.0 / alpha),
                    min(1.0, byte(b / area) / 255.0 / alpha),
                )
            }
        }
        return out
    }

    /** The palette of a set of colours (see the object comment). */
    fun palette(pixels: List<Rgb>): List<Int> {
        if (pixels.isEmpty()) return Neutral
        val clusters = kMeans(pixels, medianCut(pixels, CLUSTER_COUNT), PASSES)
            .filter { it.count > 0 }
            .sortedWith(compareByDescending<Cluster> { it.count }.thenBy { it.mean })
        val total = pixels.size.toDouble()
        var accentIndex = -1
        var bestScore = 0.0
        clusters.forEachIndexed { index, cluster ->
            val share = cluster.count / total
            if (share < ACCENT_MIN_SHARE) return@forEachIndexed
            val score = AuroraLight.chroma(AuroraLight.toHsl(cluster.mean)) * sqrt(share)
            if (accentIndex < 0 || score > bestScore) {
                accentIndex = index
                bestScore = score
            }
        }
        if (accentIndex < 0) accentIndex = 0
        val accent = clusters[accentIndex].mean
        val base = clusters.filterIndexed { index, _ -> index != accentIndex }.map { it.mean }.ifEmpty { listOf(accent) }
        val four = base.take(4) + List(max(0, 4 - base.size)) { base.last() }
        return four + accent
    }

    /**
     * Median cut: split the box with the widest channel range weighted by its size at the median
     * of that channel (lower half first), until there are [boxes] boxes. Returns the box means.
     */
    internal fun medianCut(pixels: List<Rgb>, boxes: Int): List<Rgb> {
        val queue = mutableListOf(pixels)
        while (queue.size < boxes) {
            var best = -1
            var bestScore = 0.0
            var bestChannel = 0
            queue.forEachIndexed { index, box ->
                if (box.size <= 1) return@forEachIndexed
                val (channel, range) = widestChannel(box)
                val score = range * box.size
                if (score > bestScore) {
                    best = index
                    bestScore = score
                    bestChannel = channel
                }
            }
            if (best < 0 || bestScore <= 0) break
            val channel = bestChannel
            val sorted = queue.removeAt(best).sortedBy { component(it, channel) }
            val middle = sorted.size / 2
            queue.add(best, sorted.subList(middle, sorted.size))
            queue.add(best, sorted.subList(0, middle))
        }
        return queue.map(::mean)
    }

    /** Lloyd's k-means from fixed seeds; ties go to the lower cluster index. */
    internal fun kMeans(pixels: List<Rgb>, seeds: List<Rgb>, passes: Int): List<Cluster> {
        val means = seeds.toMutableList()
        val assignment = IntArray(pixels.size)
        repeat(max(1, passes)) {
            pixels.forEachIndexed { index, pixel ->
                var best = 0
                var bestDistance = Double.POSITIVE_INFINITY
                means.forEachIndexed { cluster, centre ->
                    val d = distance(pixel, centre)
                    if (d < bestDistance) {
                        best = cluster
                        bestDistance = d
                    }
                }
                assignment[index] = best
            }
            for (cluster in means.indices) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var n = 0
                pixels.forEachIndexed { index, p ->
                    if (assignment[index] == cluster) {
                        r += p.r
                        g += p.g
                        b += p.b
                        n++
                    }
                }
                if (n > 0) means[cluster] = Rgb(r / n, g / n, b / n)
            }
        }
        return means.indices.map { cluster -> Cluster(means[cluster].hex, assignment.count { it == cluster }) }
    }

    private fun widestChannel(box: List<Rgb>): Pair<Int, Double> {
        var channel = 0
        var widest = 0.0
        for (c in 0 until 3) {
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            for (p in box) {
                val v = component(p, c)
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            val range = hi - lo
            if (range > widest) {
                channel = c
                widest = range
            }
        }
        return channel to widest
    }

    private fun component(p: Rgb, channel: Int): Double = when (channel) {
        0 -> p.r
        1 -> p.g
        else -> p.b
    }

    private fun mean(colours: List<Rgb>): Rgb {
        val n = max(colours.size, 1).toDouble()
        return Rgb(colours.sumOf { it.r } / n, colours.sumOf { it.g } / n, colours.sumOf { it.b } / n)
    }

    /** Perceptual-ish weights (green reads brightest). */
    private fun distance(a: Rgb, b: Rgb): Double {
        val r = a.r - b.r
        val g = a.g - b.g
        val bl = a.b - b.b
        return 2 * r * r + 4 * g * g + 3 * bl * bl
    }

    private fun byte(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
}
