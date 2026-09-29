package dev.xerootg.gifmaker.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.xerootg.gifmaker.model.CropRect
import dev.xerootg.gifmaker.model.DateSource
import dev.xerootg.gifmaker.model.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Exercises the Android-side pipeline (bounds probe, EXIF, orientation, crop and scale) against
 * real JPEG bytes through Robolectric's native graphics runtime. Robolectric's
 * BitmapRegionDecoder returns blank pixels, so the renderer runs with the full-decode path; the
 * crop-rectangle and sample-size math is shared with the region path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FramePipelineRobolectricTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 64x48 JPEG: quadrants red (TL), green (TR), blue (BL), white (BR); EXIF rotate-90 + date. */
    private fun writeSampleJpeg(orientation: String = "6"): File {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = Color.RED; c.drawRect(0f, 0f, 32f, 24f, p)
        p.color = Color.GREEN; c.drawRect(32f, 0f, 64f, 24f, p)
        p.color = Color.BLUE; c.drawRect(0f, 24f, 32f, 48f, p)
        p.color = Color.WHITE; c.drawRect(32f, 24f, 64f, 48f, p)
        val file = File.createTempFile("sample", ".jpg", context.cacheDir)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:03:09 14:05:33")
            saveAttributes()
        }
        return file
    }

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    private fun assertColor(expected: Int, actual: Int, what: String, tol: Int = 40) {
        val dr = abs(Color.red(expected) - Color.red(actual))
        val dg = abs(Color.green(expected) - Color.green(actual))
        val db = abs(Color.blue(expected) - Color.blue(actual))
        assertTrue("$what: expected #${Integer.toHexString(expected)} got #${Integer.toHexString(actual)}", dr <= tol && dg <= tol && db <= tol)
    }

    @Test
    fun loadReadsBoundsOrientationAndExifDate() {
        val file = writeSampleJpeg()
        val item = FrameMetadata.load(context, Uri.fromFile(file), 1, 0)
        assertNotNull("a plain JPEG must load", item)
        item!!
        assertEquals(64, item.rawWidth)
        assertEquals(48, item.rawHeight)
        assertEquals(90, item.rotation)
        assertEquals(false, item.flipped)
        assertEquals(48, item.width)
        assertEquals(64, item.height)
        assertEquals(DateSource.EXIF, item.dateSource)
        assertEquals(utc(2024, 3, 9, 14, 5, 33), item.dateMillis)
        assertEquals("2024-03-09 14:05:33", item.dateLabel)
    }

    @Test
    fun renderAppliesOrientationCropAndScale() {
        val file = writeSampleJpeg()
        val item = FrameMetadata.load(context, Uri.fromFile(file), 1, 0)!!
        val renderer = FrameRenderer(context, useRegionDecoder = false)

        // Full frame, rotated 90° CW: raw BL (blue) -> oriented TL, raw TL (red) -> oriented TR,
        // raw BR (white) -> oriented BL, raw TR (green) -> oriented BR.
        val full = renderer.render(item, CropRect(), 48, 64, ScaleMode.FILL)
        assertEquals(48, full.width); assertEquals(64, full.height)
        assertColor(Color.BLUE, full.getPixel(8, 8), "oriented top-left")
        assertColor(Color.RED, full.getPixel(40, 8), "oriented top-right")
        assertColor(Color.WHITE, full.getPixel(8, 56), "oriented bottom-left")
        assertColor(Color.GREEN, full.getPixel(40, 56), "oriented bottom-right")

        // Crop the oriented top-left quadrant and scale it up: should be uniformly blue.
        val crop = renderer.render(item, CropRect(0f, 0f, 0.5f, 0.5f), 40, 40, ScaleMode.FILL)
        for ((x, y) in listOf(2 to 2, 37 to 2, 2 to 37, 37 to 37, 20 to 20)) {
            assertColor(Color.BLUE, crop.getPixel(x, y), "cropped quadrant at $x,$y")
        }

        // FIT letterboxes on black when the aspect differs.
        val fit = renderer.render(item, CropRect(), 64, 64, ScaleMode.FIT)
        assertColor(Color.BLACK, fit.getPixel(2, 32), "letterbox left")
        assertColor(Color.BLACK, fit.getPixel(61, 32), "letterbox right")
    }

    @Test
    fun unorientedJpegKeepsRawLayout() {
        val file = writeSampleJpeg(orientation = "1")
        val item = FrameMetadata.load(context, Uri.fromFile(file), 1, 0)!!
        assertEquals(0, item.rotation)
        val out = FrameRenderer(context, useRegionDecoder = false).render(item, CropRect(), 64, 48, ScaleMode.STRETCH)
        assertColor(Color.RED, out.getPixel(8, 8), "top-left")
        assertColor(Color.WHITE, out.getPixel(56, 40), "bottom-right")
    }

    /** Runs only when GIFMAKER_SAMPLE_JPEG points at a real camera/raw2dng JPEG on the host. */
    @Test
    fun realWorldSampleLoadsAndRenders() {
        val path = System.getenv("GIFMAKER_SAMPLE_JPEG")
        assumeTrue("GIFMAKER_SAMPLE_JPEG not set", path != null && File(path).isFile)
        val item = FrameMetadata.load(context, Uri.fromFile(File(path!!)), 1, 0)
        assertNotNull("real-world JPEG must load", item)
        item!!
        assertTrue(item.rawWidth > 0 && item.rawHeight > 0)
        println("sample: ${item.rawWidth}x${item.rawHeight} rot=${item.rotation} date=${item.dateLabel} (${item.dateSource})")
        val out = FrameRenderer(context, useRegionDecoder = false).render(item, CropRect(0.25f, 0.25f, 0.75f, 0.75f), 320, 240, ScaleMode.FILL)
        assertEquals(320, out.width); assertEquals(240, out.height)
        var nonBlack = 0
        for (y in 0 until 240 step 8) for (x in 0 until 320 step 8) if (out.getPixel(x, y) and 0xFFFFFF != 0) nonBlack++
        assertTrue("rendered frame should not be black", nonBlack > 500)
    }
}
