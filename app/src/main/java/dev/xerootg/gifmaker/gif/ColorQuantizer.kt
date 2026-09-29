package dev.xerootg.gifmaker.gif

/**
 * Histogram over a 15-bit (5 bits per channel) RGB grid. Exact per-bin channel sums are kept so
 * palette entries become true means of the pixels they represent rather than bin centres.
 * Pixels are 0xAARRGGBB (alpha ignored). Cheap enough to feed every pixel of every frame.
 */
class ColorHistogram {
    val counts = IntArray(BINS)
    val sumR = LongArray(BINS)
    val sumG = LongArray(BINS)
    val sumB = LongArray(BINS)
    var total = 0L
        private set

    fun add(pixels: IntArray) {
        for (p in pixels) {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            counts[bin]++
            sumR[bin] += r.toLong()
            sumG[bin] += g.toLong()
            sumB[bin] += b.toLong()
        }
        total += pixels.size.toLong()
    }

    companion object {
        const val BINS = 1 shl 15
    }
}

/**
 * Median-cut colour quantisation over a [ColorHistogram].
 *
 * Boxes are split along their longest axis at the population median. The first three quarters of
 * the splits pick the most populous box (good tonal resolution in large areas); the rest pick by
 * population × volume so sparse-but-wide boxes (highlights, small saturated objects) get entries too.
 */
object MedianCut {

    /** Returns up to [maxColors] colours as 0xFFRRGGBB. Never empty. */
    fun buildPalette(hist: ColorHistogram, maxColors: Int): IntArray {
        val limit = maxColors.coerceIn(2, 256)
        var nonEmpty = 0
        for (i in 0 until ColorHistogram.BINS) if (hist.counts[i] > 0) nonEmpty++
        if (nonEmpty == 0) return intArrayOf(0xFF000000.toInt())

        val bins = IntArray(nonEmpty)
        var k = 0
        for (i in 0 until ColorHistogram.BINS) if (hist.counts[i] > 0) bins[k++] = i

        val boxes = ArrayList<Box>(limit)
        boxes += Box(bins, 0, nonEmpty, hist)
        while (boxes.size < limit) {
            val byPopulation = boxes.size < limit * 3 / 4
            var best: Box? = null
            var bestScore = -1.0
            for (b in boxes) {
                if (b.length < 2) continue
                val score = if (byPopulation) b.count.toDouble() else b.count.toDouble() * b.volume()
                if (score > bestScore) {
                    bestScore = score
                    best = b
                }
            }
            val box = best ?: break
            val (lo, hi) = box.split(hist)
            boxes.remove(box)
            boxes += lo
            boxes += hi
        }
        return IntArray(boxes.size) { boxes[it].meanColor(hist) }
    }

    private class Box(val bins: IntArray, val start: Int, val end: Int, hist: ColorHistogram) {
        val length get() = end - start
        var count = 0L
        var rMin = 31; var rMax = 0
        var gMin = 31; var gMax = 0
        var bMin = 31; var bMax = 0

        init {
            for (i in start until end) {
                val bin = bins[i]
                count += hist.counts[bin].toLong()
                val r = (bin shr 10) and 31
                val g = (bin shr 5) and 31
                val b = bin and 31
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
            }
        }

        fun volume(): Double =
            (rMax - rMin + 1).toDouble() * (gMax - gMin + 1) * (bMax - bMin + 1)

        fun split(hist: ColorHistogram): Pair<Box, Box> {
            val rr = rMax - rMin
            val gg = gMax - gMin
            val bb = bMax - bMin
            val shift = if (rr >= gg && rr >= bb) 10 else if (gg >= bb) 5 else 0

            // Sort this slice by the chosen channel: pack (channel, binIndex) so a native int sort works.
            val keys = IntArray(length) { i ->
                val bin = bins[start + i]
                (((bin shr shift) and 31) shl 15) or bin
            }
            keys.sort()
            for (i in keys.indices) bins[start + i] = keys[i] and 0x7FFF

            val half = count / 2
            var acc = 0L
            var cut = start
            while (cut < end - 1) {
                acc += hist.counts[bins[cut]].toLong()
                cut++
                if (acc >= half) break
            }
            return Box(bins, start, cut, hist) to Box(bins, cut, end, hist)
        }

        fun meanColor(hist: ColorHistogram): Int {
            var sr = 0L; var sg = 0L; var sb = 0L
            for (i in start until end) {
                val bin = bins[i]
                sr += hist.sumR[bin]; sg += hist.sumG[bin]; sb += hist.sumB[bin]
            }
            val n = count.coerceAtLeast(1)
            val r = ((sr + n / 2) / n).toInt().coerceIn(0, 255)
            val g = ((sg + n / 2) / n).toInt().coerceIn(0, 255)
            val b = ((sb + n / 2) / n).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/**
 * Maps ARGB pixels onto a fixed palette, optionally with Floyd–Steinberg error diffusion.
 * Nearest-colour lookups are memoised on a 6-bit-per-channel key (256 KiB of ints).
 */
class PaletteMapper(val palette: IntArray) {
    private val n = palette.size
    private val pr = IntArray(n) { (palette[it] ushr 16) and 0xFF }
    private val pg = IntArray(n) { (palette[it] ushr 8) and 0xFF }
    private val pb = IntArray(n) { palette[it] and 0xFF }
    private val cache = IntArray(1 shl 18) { -1 }

    init {
        require(n in 1..256) { "palette must hold 1..256 colours" }
    }

    /**
     * Nearest palette index for (r,g,b). Lookups are resolved at the centre of the colour's
     * 6-bit bucket so the memoised answer does not depend on which colour of the bucket was
     * seen first; the added error is bounded by half a bucket (2 levels per channel).
     */
    fun nearest(r: Int, g: Int, b: Int): Int {
        val key = ((r shr 2) shl 12) or ((g shr 2) shl 6) or (b shr 2)
        val cached = cache[key]
        if (cached >= 0) return cached
        val cr = (r and 0xFC) or 2
        val cg = (g and 0xFC) or 2
        val cb = (b and 0xFC) or 2
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in 0 until n) {
            val dr = pr[i] - cr
            val dg = pg[i] - cg
            val db = pb[i] - cb
            // Perceptual weighting (roughly luma-proportional).
            val d = dr * dr * 2 + dg * dg * 4 + db * db * 3
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        cache[key] = best
        return best
    }

    fun map(pixels: IntArray, width: Int, height: Int, dither: Boolean): ByteArray {
        require(pixels.size == width * height)
        val out = ByteArray(pixels.size)
        if (!dither) {
            for (i in pixels.indices) {
                val p = pixels[i]
                out[i] = nearest((p ushr 16) and 0xFF, (p ushr 8) and 0xFF, p and 0xFF).toByte()
            }
            return out
        }

        // Error rows carry (r,g,b) triples scaled by 16, with one padding pixel on each side.
        var cur = IntArray((width + 2) * 3)
        var next = IntArray((width + 2) * 3)
        for (y in 0 until height) {
            next.fill(0)
            val row = y * width
            for (x in 0 until width) {
                val p = pixels[row + x]
                val bx = (x + 1) * 3
                val r = (((p ushr 16) and 0xFF) + cur[bx] / 16).coerceIn(0, 255)
                val g = (((p ushr 8) and 0xFF) + cur[bx + 1] / 16).coerceIn(0, 255)
                val b = ((p and 0xFF) + cur[bx + 2] / 16).coerceIn(0, 255)
                val idx = nearest(r, g, b)
                out[row + x] = idx.toByte()
                val er = r - pr[idx]
                val eg = g - pg[idx]
                val eb = b - pb[idx]
                // right: 7/16
                cur[bx + 3] += er * 7; cur[bx + 4] += eg * 7; cur[bx + 5] += eb * 7
                // down-left: 3/16
                next[bx - 3] += er * 3; next[bx - 2] += eg * 3; next[bx - 1] += eb * 3
                // down: 5/16
                next[bx] += er * 5; next[bx + 1] += eg * 5; next[bx + 2] += eb * 5
                // down-right: 1/16
                next[bx + 3] += er; next[bx + 4] += eg; next[bx + 5] += eb
            }
            val t = cur; cur = next; next = t
        }
        return out
    }
}
