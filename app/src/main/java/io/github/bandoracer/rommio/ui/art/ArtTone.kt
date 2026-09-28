package io.github.bandoracer.rommio.ui.art

/**
 * How light the top of a picture is, where the status bar sits over it (Aurora's Home hero and the
 * Game page draw art under the bar; iOS `ArtTone`). Pure, on ARGB pixels, so it can be tested.
 */
object ArtTone {
    /**
     * Whether rows [band] (fractions of the height, from the top) are dark on average: mean
     * relative luminance of the encoded channels below 0.5 (the status bar then reads in light
     * text). An empty picture counts as dark.
     */
    fun isDark(argb: IntArray, width: Int, height: Int, band: ClosedFloatingPointRange<Double>): Boolean {
        if (width <= 0 || height <= 0 || argb.size < width * height) return true
        val from = (height * band.start.coerceIn(0.0, 1.0)).toInt().coerceAtMost(height - 1)
        val rows = maxOf(1, (height * minOf(band.endInclusive, 1.0)).toInt() - from).coerceAtMost(height - from)
        var sum = 0.0
        for (y in from until from + rows) {
            for (x in 0 until width) {
                val p = argb[y * width + x]
                sum += 0.2126 * (p shr 16 and 0xFF) / 255.0 + 0.7152 * (p shr 8 and 0xFF) / 255.0 + 0.0722 * (p and 0xFF) / 255.0
            }
        }
        return sum / (rows * width) < 0.5
    }

    /**
     * The rows of a picture under the status bar, as fractions of its height: the picture fills a
     * [heroWidth] × [heroHeight] box (cropped about its centre) and the bar covers the box's first
     * [topInset] (same units as the box). iOS `GameArtHero.statusBarBand`.
     */
    fun statusBarBand(imageWidth: Int, imageHeight: Int, heroWidth: Double, heroHeight: Double, topInset: Double): ClosedFloatingPointRange<Double> {
        val aspect = imageWidth.toDouble() / maxOf(1, imageHeight)
        val drawnHeight = maxOf(heroHeight, heroWidth / maxOf(aspect, 0.01))
        val cropped = (drawnHeight - heroHeight) / 2
        return (cropped / drawnHeight)..((cropped + topInset) / drawnHeight)
    }
}

/** Uniform black borders around a captured screen (a game's own letterbox or pillarbox; iOS `ScreenTrim`). */
object ScreenTrim {
    /** A kept region of a picture, in pixels. */
    data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The picture without rows and columns at its edges that are all but black (at least 98 % of
     * a line's pixels have every channel below 20); null when there are none, when trimming would
     * leave less than 40 % of either side, or for a picture too small or too large to scan.
     */
    fun region(argb: IntArray, width: Int, height: Int): Region? {
        if (width <= 8 || height <= 8 || width.toLong() * height > 4_000_000 || argb.size < width * height) return null
        fun dark(x: Int, y: Int): Boolean {
            val p = argb[y * width + x]
            return maxOf(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF) < 20
        }
        fun rowIsBorder(y: Int): Boolean = (0 until width).count { dark(it, y) } * 50 >= width * 49
        fun columnIsBorder(x: Int, top: Int, bottom: Int): Boolean = (top until bottom).count { dark(x, it) } * 50 >= (bottom - top) * 49
        var top = 0
        var bottom = height
        while (top < bottom - 1 && rowIsBorder(top)) top++
        while (bottom - 1 > top && rowIsBorder(bottom - 1)) bottom--
        var left = 0
        var right = width
        while (left < right - 1 && columnIsBorder(left, top, bottom)) left++
        while (right - 1 > left && columnIsBorder(right - 1, top, bottom)) right--
        val keptWidth = right - left
        val keptHeight = bottom - top
        if (keptWidth == width && keptHeight == height) return null
        if (keptWidth * 5 < width * 2 || keptHeight * 5 < height * 2) return null
        return Region(left, top, keptWidth, keptHeight)
    }
}
