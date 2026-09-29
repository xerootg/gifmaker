package dev.xerootg.gifmaker.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import dev.xerootg.gifmaker.model.CropRect
import dev.xerootg.gifmaker.model.FrameItem
import dev.xerootg.gifmaker.model.ScaleMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Produces output-sized frames: maps the normalised crop (defined in oriented image space) back
 * into the stored JPEG's coordinate space, region-decodes only that rectangle at a sample size
 * just above the output resolution, then orients and scales it onto the output canvas.
 */
class FrameRenderer(private val context: Context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun renderPixels(frame: FrameItem, crop: CropRect, outW: Int, outH: Int, mode: ScaleMode): IntArray {
        val bmp = render(frame, crop, outW, outH, mode)
        val px = IntArray(outW * outH)
        bmp.getPixels(px, 0, outW, 0, 0, outW, outH)
        bmp.recycle()
        return px
    }

    fun render(frame: FrameItem, crop: CropRect, outW: Int, outH: Int, mode: ScaleMode): Bitmap {
        val orientedCrop = RectF(
            crop.left * frame.width, crop.top * frame.height,
            crop.right * frame.width, crop.bottom * frame.height,
        )
        val toOriented = orientationMatrix(frame)
        val toRaw = Matrix().also { check(toOriented.invert(it)) }
        val rawF = RectF()
        toRaw.mapRect(rawF, orientedCrop)

        val region = Rect(
            floor(rawF.left).toInt(), floor(rawF.top).toInt(),
            ceil(rawF.right).toInt(), ceil(rawF.bottom).toInt(),
        )
        if (!region.intersect(0, 0, frame.rawWidth, frame.rawHeight) || region.isEmpty) {
            region.set(0, 0, frame.rawWidth, frame.rawHeight)
        }

        val swap = frame.rotation == 90 || frame.rotation == 270
        val orientedW = if (swap) region.height() else region.width()
        val orientedH = if (swap) region.width() else region.height()
        var sample = 1
        while (orientedW / (sample * 2) >= outW && orientedH / (sample * 2) >= outH) sample *= 2

        val src = decodeRegion(frame, region, sample)
            ?: throw IllegalStateException("Could not decode ${frame.name}")

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val bw = src.width.toFloat()
        val bh = src.height.toFloat()
        val m = Matrix()
        m.postTranslate(-bw / 2f, -bh / 2f)
        if (frame.flipped) m.postScale(-1f, 1f)
        m.postRotate(frame.rotation.toFloat())
        val tw = if (swap) bh else bw
        val th = if (swap) bw else bh
        m.postTranslate(tw / 2f, th / 2f)

        val sx = outW / tw
        val sy = outH / th
        val (scaleX, scaleY) = when (mode) {
            ScaleMode.FILL -> max(sx, sy).let { it to it }
            ScaleMode.FIT -> min(sx, sy).let { it to it }
            ScaleMode.STRETCH -> sx to sy
        }
        m.postScale(scaleX, scaleY)
        m.postTranslate((outW - tw * scaleX) / 2f, (outH - th * scaleY) / 2f)

        canvas.drawBitmap(src, m, paint)
        src.recycle()
        return out
    }

    /** raw pixel space → oriented (displayed) pixel space, using the same flip-then-rotate order as EXIF. */
    private fun orientationMatrix(frame: FrameItem): Matrix {
        val m = Matrix()
        m.postTranslate(-frame.rawWidth / 2f, -frame.rawHeight / 2f)
        if (frame.flipped) m.postScale(-1f, 1f)
        m.postRotate(frame.rotation.toFloat())
        m.postTranslate(frame.width / 2f, frame.height / 2f)
        return m
    }

    private fun decodeRegion(frame: FrameItem, region: Rect, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // Fast path: decode only the needed rectangle.
        try {
            context.contentResolver.openInputStream(frame.uri)?.use { stream ->
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(stream, false)
                if (decoder != null) {
                    try {
                        decoder.decodeRegion(region, opts)?.let { return it }
                    } finally {
                        decoder.recycle()
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to a full decode below.
        }

        val full = try {
            context.contentResolver.openInputStream(frame.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        } ?: return null
        if (region.left == 0 && region.top == 0 && region.width() == frame.rawWidth && region.height() == frame.rawHeight) {
            return full
        }
        val x = (region.left / sample).coerceIn(0, full.width - 1)
        val y = (region.top / sample).coerceIn(0, full.height - 1)
        val w = (region.width() / sample).coerceIn(1, full.width - x)
        val h = (region.height() / sample).coerceIn(1, full.height - y)
        val cropped = Bitmap.createBitmap(full, x, y, w, h)
        if (cropped !== full) full.recycle()
        return cropped
    }
}
