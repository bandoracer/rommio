package io.github.bandoracer.rommio.ui.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The art treatments' pure rules (docs/theme-layouts.md §1.1, §1.4, §1.5). The palette cases mirror
 * iOS `ArtTreatmentTests` (same synthetic inputs, same expectations).
 */
class CoverArtRulesTest {
    private fun rgb(hex: Int) = CoverPalette.Rgb.of(hex)

    private fun close(a: Int, b: Int, tolerance: Int = 6): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= tolerance &&
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) <= tolerance

    // ---- §1.1 palette ----------------------------------------------------------------------

    @Test
    fun paletteExtractionIsDeterministicAndFindsTheAccent() {
        // iOS testPaletteExtractionIsDeterministicAndFindsTheAccent: a dark violet cover with a
        // small ember accent and a mid violet band, 24 × 24 cells.
        val pixels = (0 until 576).map { index ->
            when (index % 24) {
                in 0 until 14 -> rgb(0x1c0c2b)
                in 14 until 21 -> rgb(0x5d2a6e)
                in 21 until 23 -> rgb(0x0b0620)
                else -> rgb(0xff7a3d)
            }
        }
        val first = CoverPalette.palette(pixels)
        val second = CoverPalette.palette(pixels)
        assertEquals(first, second)
        assertEquals(5, first.size)
        assertEquals("#ff7a3d", AuroraLight.hex(first[4]))
        assertEquals("the most populous colour leads", "#1c0c2b", AuroraLight.hex(first[0]))
        assertTrue(first.subList(0, 4).map(AuroraLight::hex).contains("#5d2a6e"))
    }

    @Test
    fun paletteReadsAnImageThroughTheGrid() {
        // iOS testPaletteExtractionReadsARealImageThroughCoreImage: 40 × 30, left teal, right coral.
        val width = 40
        val height = 30
        val argb = IntArray(width * height) { i -> if (i % width < width / 2) 0xFF1F8A8F.toInt() else 0xFFF26B5B.toInt() }
        val grid = CoverPalette.gridPixels(argb, width, height)
        assertEquals("24 × 18 cells", 24 * 18, grid.size)
        val palette = CoverPalette.extract(argb, width, height)
        assertTrue(palette.map(AuroraLight::hex).toString(), palette.any { close(it, 0x1f8a8f) })
        assertTrue(palette.map(AuroraLight::hex).toString(), palette.any { close(it, 0xf26b5b) })
    }

    @Test
    fun gridAveragesAreasAndDropsTransparentCells() {
        // 48 × 48: a 2 × 2 checker of black and white averages to mid grey; the right half is clear.
        val size = 48
        val argb = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            when {
                x >= size / 2 -> 0x00000000
                (x + y) % 2 == 0 -> 0xFF000000.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        }
        val grid = CoverPalette.gridPixels(argb, size, size)
        assertEquals("only the opaque half survives", 12 * 24, grid.size)
        grid.forEach { assertTrue(abs((it.hex shr 16 and 0xFF) - 128) <= 1) }
    }

    @Test
    fun medianCutSplitsTheWidestRangeWeightedBySize() {
        val pixels = List(10) { rgb(0x000000) } + List(10) { rgb(0xff0000) } + List(2) { rgb(0x00ff00) }
        val seeds = CoverPalette.medianCut(pixels, boxes = 2)
        assertEquals(2, seeds.size)
        // Widest × count is red (range 1 over 22 pixels, same as green): red wins as the first channel.
        assertTrue(seeds.any { it.r > 0.8 })
    }

    @Test
    fun kMeansTiesGoToTheLowerCluster() {
        val clusters = CoverPalette.kMeans(listOf(rgb(0x808080)), seeds = listOf(rgb(0x707070), rgb(0x909090)), passes = 6)
        assertEquals(1, clusters[0].count)
        assertEquals(0, clusters[1].count)
    }

    @Test
    fun transparentArtFallsBackToNeutral() {
        assertEquals(CoverPalette.Neutral, CoverPalette.extract(IntArray(64) { 0x00FF0000 }, 8, 8))
        assertTrue(CoverPalette.isNeutral(CoverPalette.Neutral))
    }

    @Test
    fun aFlatCoverRepeatsItsColour() {
        val palette = CoverPalette.extract(IntArray(400) { 0xFF2121DE.toInt() }, 20, 20)
        assertEquals(List(5) { 0x2121de }, palette)
    }

    @Test
    fun aGreyPaletteIsNeutralAndAColourfulOneIsNot() {
        assertTrue(CoverPalette.isNeutral(listOf(0x202020, 0x505050, 0x808080, 0xb0b0b0, 0xe0e0e0)))
        assertFalse(CoverPalette.isNeutral(listOf(0x0b0620, 0x3a1450, 0x5d2a6e, 0x1c0c2b, 0xff7a3d)))
    }

    @Test
    fun paletteFilesRoundTrip() {
        val palette = listOf(0x0b0620, 0x3a1450, 0x5d2a6e, 0x1c0c2b, 0xff7a3d)
        assertEquals(palette, CoverArt.decodePalette(CoverArt.encodePalette(palette)))
    }

    // ---- §1.3 / §1.4 bakes ------------------------------------------------------------------

    @Test
    fun grainTileMatchesTheSeededNoise() {
        val tile = ArtBake.grainTile()
        assertEquals(ArtBake.GRAIN_SIZE * ArtBake.GRAIN_SIZE, tile.size)
        // Same xorshift32 as iOS GrainTexture (seed 0x9E3779B9): first values 204, 205, 245, 230, 195.
        assertEquals(listOf(204, 205, 245, 230, 195), tile.take(5).map { it and 0xFF })
        assertEquals(tile.toList(), ArtBake.grainTile().toList())
    }

    @Test
    fun backdropBlurSpreadsAndStaysOpaque() {
        val w = 24
        val h = 32
        val argb = IntArray(w * h) { i -> if (i % w < w / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val baked = ArtBake.backdrop(argb, w, h, dark = false)
        assertTrue(baked.all { it ushr 24 == 0xFF })
        // Blurred: the edge between halves is mixed.
        val mid = baked[10 * w + w / 2]
        assertTrue((mid shr 16 and 0xFF) in 40..250 && (mid and 0xFF) in 40..250)
        assertNotEquals(baked[0], baked[w - 1])
    }

    @Test
    fun gradeSaturatesAndBrightens() {
        val grey = ArtBake.grade(intArrayOf(0xFF808080.toInt()), saturation = 1.5, brightness = 1.0)[0]
        assertEquals("grey stays grey under saturate", 0x808080, grey and 0xFFFFFF)
        val bright = ArtBake.grade(intArrayOf(0xFF404040.toInt()), saturation = 1.0, brightness = 2.0)[0]
        assertEquals(0x808080, bright and 0xFFFFFF)
    }

    // ---- §1.5 Riso print --------------------------------------------------------------------

    @Test
    fun risoPlatesFollowTheStudioCurves() {
        assertEquals(0.7152 * 1.55 * 1.1, RisoPrint.curveA(dark = false).gain, 1e-9)
        assertEquals(-0.05, RisoPrint.curveA(dark = false).bias, 1e-9)
        assertEquals(0.2126 * 5.2 * 1.1, RisoPrint.curveB(dark = false).gain, 1e-9)
        assertEquals(0.7152 * 1.7 * 1.25, RisoPrint.curveA(dark = true).gain, 1e-9)
        assertEquals(-0.125, RisoPrint.curveB(dark = true).bias, 1e-9)
    }

    @Test
    fun fullOverprintByDayIsNavy() {
        // Riso light: paper #fbfaf6, pink #ff48b0, blue #0078bf (tokens).
        val inks = RisoInks(paper = 0xFBFAF6, plateA = 0xFF48B0, plateB = 0x0078BF, dark = false, halftone = 0.55f, cell = 9f, misregistration = 5f)
        val black = RisoPrint.printed(0x000000, inks)
        assertTrue("full overprint is the studio's pink × blue navy, got ${AuroraLight.hex(black)}", close(black, 0x002183))
        assertEquals("white stays paper", 0xFBFAF6, RisoPrint.printed(0xFFFFFF, inks))
        val dark = inks.copy(paper = 0x000000, plateB = 0x5EC8E5, dark = true)
        assertEquals("black stock stays black", 0x000000, RisoPrint.printed(0x000000, dark))
        val glow = RisoPrint.printed(0xFFFFFF, dark)
        assertTrue((glow shr 16 and 0xFF) + (glow shr 8 and 0xFF) + (glow and 0xFF) > 0.8 * 3 * 255)
    }

    @Test
    fun risoPlatesSeparateGreenToPinkAndRedToBlue() {
        assertEquals(1.0, RisoPrint.coverage(0xFF000000.toInt(), plateA = true, dark = false), 1e-9)
        assertEquals(1.0, RisoPrint.coverage(0xFF000000.toInt(), plateA = false, dark = false), 1e-9)
        assertEquals(0.0, RisoPrint.coverage(0xFFFFFFFF.toInt(), plateA = true, dark = false), 1e-9)
        assertEquals(1.0, RisoPrint.coverage(0xFFFF0000.toInt(), plateA = true, dark = false), 1e-9)
        assertEquals(0.0, RisoPrint.coverage(0xFFFF0000.toInt(), plateA = false, dark = false), 1e-9)
        assertEquals(1.0, RisoPrint.coverage(0xFFFFFFFF.toInt(), plateA = true, dark = true), 1e-9)
        assertEquals(0.0, RisoPrint.coverage(0x00000000, plateA = true, dark = false), 1e-9)
    }

    @Test
    fun misregistrationShiftsTheBluePlateRightAndUp() {
        // A black left column on white: the blue plate lands one pixel to the right.
        val w = 4
        val src = IntArray(w * 2) { i -> if (i % w == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val inks = RisoInks(paper = 0xFFFFFF, plateA = 0xFF48B0, plateB = 0x0078BF, dark = false, halftone = 0f, cell = 6f, misregistration = 1f)
        val out = RisoPrint.print(src, w, 2, inks)
        val c1 = out[1]
        assertEquals(0x00, c1 shr 16 and 0xFF)
        assertEquals(0x78, c1 shr 8 and 0xFF)
        assertEquals(0xFFFFFFFF.toInt(), out[2])
    }

    @Test
    fun halftoneScreensOnlyTheBluePlate() {
        val inks = RisoInks(paper = 0xFFFFFF, plateA = 0xFF48B0, plateB = 0x0078BF, dark = false, halftone = 1f, cell = 6f, misregistration = 0f)
        // White stays paper, solid black stays solid under the screen.
        assertEquals(0xFFFFFFFF.toInt(), RisoPrint.print(IntArray(36) { 0xFFFFFFFF.toInt() }, 6, 6, inks)[14])
        val solid = RisoPrint.print(IntArray(36) { 0xFF000000.toInt() }, 6, 6, inks)
        assertTrue(solid.all { it == solid[0] })
        // A pure-green area has no plate A (pink) coverage and full plate B (blue): mid tones of
        // plate A stay continuous, so a mid-grey green channel prints the same pink everywhere.
        val midGreen = IntArray(36) { 0xFFFF8000.toInt() }
        val printed = RisoPrint.print(midGreen, 6, 6, inks.copy(plateB = 0xFFFFFF))
        assertTrue("plate A is solid (not screened)", printed.all { it == printed[0] })
    }

    @Test
    fun dotPitchScalesWithTheCover() {
        assertEquals(3f, RisoPrint.dotPitchDp(52f), 1e-6f)
        assertEquals(4.5f, RisoPrint.dotPitchDp(270f), 1e-6f)
        assertEquals(5f, RisoPrint.dotPitchDp(800f), 1e-6f)
    }
}
