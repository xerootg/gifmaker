package dev.xerootg.gifmaker.media

import android.content.Context
import dev.xerootg.gifmaker.gif.ColorHistogram
import dev.xerootg.gifmaker.gif.GifWriter
import dev.xerootg.gifmaker.gif.MedianCut
import dev.xerootg.gifmaker.gif.PaletteMapper
import dev.xerootg.gifmaker.model.CropRect
import dev.xerootg.gifmaker.model.ExportSettings
import dev.xerootg.gifmaker.model.FrameItem
import dev.xerootg.gifmaker.model.LoopMode
import dev.xerootg.gifmaker.model.PaletteMode
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the frame sequence and streams it into a GIF file.
 *
 * Global palette mode is two-pass (histogram over every frame, then encode); rendered pixels from
 * pass one are kept in memory when the whole sequence fits a modest budget so JPEGs are decoded
 * only once. Per-frame mode is single-pass with a local colour table per frame.
 */
class GifExporter(private val context: Context) {

    class Result(val file: File, val bytes: Long, val frameCount: Int, val durationMs: Long)

    suspend fun export(
        frames: List<FrameItem>,
        crop: CropRect,
        settings: ExportSettings,
        outFile: File,
        onProgress: (stage: String, progress: Float) -> Unit,
    ): Result {
        require(frames.isNotEmpty()) { "No frames to encode" }
        require(settings.sizeValid) {
            "Output size must be between ${ExportSettings.MIN_DIM} and ${ExportSettings.MAX_DIM} px"
        }
        val w = settings.outWidth
        val h = settings.outHeight
        val renderer = FrameRenderer(context)
        val delayCs = settings.delayCs
        val loop: Int? = when (settings.loopMode) {
            LoopMode.FOREVER -> 0
            LoopMode.ONCE -> null
            LoopMode.COUNT -> settings.loopCount.coerceIn(1, 65535)
        }

        val unique = frames.distinctBy { it.id }
        val global = settings.paletteMode == PaletteMode.GLOBAL
        val totalSteps = (if (global) unique.size else 0) + frames.size
        var step = 0
        fun report(stage: String) = onProgress(stage, step.toFloat() / totalSteps)

        val pixelBudgetOk = unique.size.toLong() * w * h * 4 <= PIXEL_CACHE_BYTES
        val pixelCache = HashMap<Long, IntArray>()

        var globalPalette: IntArray? = null
        var globalMapper: PaletteMapper? = null
        if (global) {
            val hist = ColorHistogram()
            for (f in unique) {
                yield()
                report("Analysing colours")
                val px = renderer.renderPixels(f, crop, w, h, settings.scaleMode)
                hist.add(px)
                if (pixelBudgetOk) pixelCache[f.id] = px
                step++
            }
            globalPalette = MedianCut.buildPalette(hist, settings.maxColors)
            globalMapper = PaletteMapper(globalPalette)
        }

        // Boomerang repeats frames; keep their encoded form so they are not rendered twice.
        val encodedCache: HashMap<Long, Encoded>? = if (frames.size != unique.size) HashMap() else null

        outFile.parentFile?.mkdirs()
        var count = 0
        FileOutputStream(outFile).use { fos ->
            GifWriter(fos, w, h, globalPalette, loop).use { gif ->
                for (f in frames) {
                    yield()
                    report("Encoding frames")
                    val encoded = encodedCache?.get(f.id) ?: run {
                        val px = pixelCache.remove(f.id) ?: renderer.renderPixels(f, crop, w, h, settings.scaleMode)
                        val e = if (globalMapper != null) {
                            Encoded(globalMapper.map(px, w, h, settings.dither), null)
                        } else {
                            val hist = ColorHistogram().also { it.add(px) }
                            val pal = MedianCut.buildPalette(hist, settings.maxColors)
                            Encoded(PaletteMapper(pal).map(px, w, h, settings.dither), pal)
                        }
                        encodedCache?.put(f.id, e)
                        e
                    }
                    gif.writeFrame(encoded.indices, encoded.palette, delayCs)
                    count++
                    step++
                }
            }
        }
        onProgress("Done", 1f)
        return Result(outFile, outFile.length(), count, count.toLong() * delayCs * 10L)
    }

    private class Encoded(val indices: ByteArray, val palette: IntArray?)

    private companion object {
        const val PIXEL_CACHE_BYTES = 96L * 1024 * 1024
    }
}
