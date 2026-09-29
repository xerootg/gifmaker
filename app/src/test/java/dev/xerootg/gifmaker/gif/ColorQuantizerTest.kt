package dev.xerootg.gifmaker.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ColorQuantizerTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun exactColorsAreReproduced() {
        val colors = intArrayOf(rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255), rgb(17, 33, 250))
        val px = IntArray(1000) { colors[it % 4] }
        val hist = ColorHistogram().also { it.add(px) }
        val palette = MedianCut.buildPalette(hist, 256)
        assertEquals(4, palette.size)
        val mapper = PaletteMapper(palette)
        val idx = mapper.map(px, 100, 10, dither = false)
        for (i in px.indices) assertEquals(px[i], palette[idx[i].toInt()])
    }

    @Test fun paletteNeverExceedsLimit() {
        val rnd = Random(9)
        val px = IntArray(20_000) { rnd.nextInt() or (0xFF shl 24) }
        val hist = ColorHistogram().also { it.add(px) }
        for (limit in listOf(2, 16, 64, 256)) {
            val palette = MedianCut.buildPalette(hist, limit)
            assertTrue("limit $limit produced ${palette.size}", palette.size in 1..limit)
        }
    }

    @Test fun grayRampErrorIsBounded() {
        val px = IntArray(256) { rgb(it, it, it) }
        val hist = ColorHistogram().also { it.add(px) }
        val palette = MedianCut.buildPalette(hist, 256)
        val mapper = PaletteMapper(palette)
        val idx = mapper.map(px, 256, 1, dither = false)
        for (i in px.indices) {
            val c = palette[idx[i].toInt()]
            val err = abs(((c shr 16) and 0xFF) - i)
            assertTrue("gray $i mapped with error $err", err <= 4)
        }
    }

    @Test fun ditheringPreservesMeanLuminance() {
        // A flat mid-gray quantised to a 2-colour palette should average out to roughly the same level.
        val w = 200; val h = 200
        val px = IntArray(w * h) { rgb(100, 100, 100) }
        val palette = intArrayOf(rgb(0, 0, 0), rgb(255, 255, 255))
        val idx = PaletteMapper(palette).map(px, w, h, dither = true)
        val mean = idx.sumOf { (it.toInt() and 0xFF) * 255 }.toDouble() / idx.size
        assertTrue("mean $mean", abs(mean - 100) < 6)
        val undithered = PaletteMapper(palette).map(px, w, h, dither = false)
        assertTrue(undithered.all { it.toInt() == 0 })
    }

    @Test fun emptyHistogramGivesOneColor() {
        assertEquals(1, MedianCut.buildPalette(ColorHistogram(), 256).size)
    }
}
