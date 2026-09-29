package dev.xerootg.gifmaker.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import dev.xerootg.gifmaker.model.FrameItem
import kotlin.math.max

/** Whole-image decoding helpers for previews and the crop editor. */
object BitmapLoader {

    /** Decodes [frame] at roughly [maxDim] on its longest side, with EXIF orientation applied. */
    fun loadOriented(context: Context, frame: FrameItem, maxDim: Int, config: Bitmap.Config): Bitmap? {
        var sample = 1
        while (max(frame.rawWidth, frame.rawHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        }
        val raw = try {
            context.contentResolver.openInputStream(frame.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        } ?: return null
        return orient(raw, frame.rotation, frame.flipped)
    }

    /** Applies "flip horizontally, then rotate clockwise", recycling the input if a copy was made. */
    fun orient(raw: Bitmap, rotation: Int, flipped: Boolean): Bitmap {
        if (rotation == 0 && !flipped) return raw
        val m = Matrix()
        if (flipped) m.postScale(-1f, 1f)
        m.postRotate(rotation.toFloat())
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }
}
