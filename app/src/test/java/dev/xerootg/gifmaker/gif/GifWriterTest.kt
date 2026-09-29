package dev.xerootg.gifmaker.gif

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** A decoded frame: palette-resolved RGB pixels. */
internal class DecodedFrame(val width: Int, val height: Int, val rgb: IntArray, val delayCs: Int)

/**
 * Minimal GIF89a structural parser (header, colour tables, extensions, image descriptors) that
 * hands the image data to the reference [LzwDecoder]. Independent of the writer's code paths.
 */
internal object GifParser {
    fun parse(b: ByteArray): Pair<List<DecodedFrame>, Int?> {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16(): Int { val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8); p += 2; return v }
        assertEquals("GIF89a", String(b, 0, 6, Charsets.US_ASCII)); p = 6
        val sw = u16(); val sh = u16()
        val packed = u8(); u8(); u8()
        var global: IntArray? = null
        if (packed and 0x80 != 0) {
            val n = 2 shl (packed and 7)
            global = IntArray(n) { (u8() shl 16) or (u8() shl 8) or u8() }
        }
        var loop: Int? = null
        val frames = ArrayList<DecodedFrame>()
        var delay = 0
        loop@ while (true) {
            when (val block = u8()) {
                0x21 -> {
                    val label = u8()
                    if (label == 0xF9) {
                        assertEquals(4, u8()); u8(); delay = u16(); u8(); assertEquals(0, u8())
                    } else if (label == 0xFF) {
                        assertEquals(11, u8())
                        val app = String(b, p, 11, Charsets.US_ASCII); p += 11
                        assertEquals("NETSCAPE2.0", app)
                        assertEquals(3, u8()); assertEquals(1, u8()); loop = u16(); assertEquals(0, u8())
                    } else {
                        while (true) { val len = u8(); if (len == 0) break; p += len }
                    }
                }
                0x2C -> {
                    assertEquals(0, u16()); assertEquals(0, u16())
                    val w = u16(); val h = u16()
                    assertEquals(sw, w); assertEquals(sh, h)
                    val ip = u8()
                    var table = global
                    if (ip and 0x80 != 0) {
                        val n = 2 shl (ip and 7)
                        table = IntArray(n) { (u8() shl 16) or (u8() shl 8) or u8() }
                    }
                    assertNotNull("frame without any colour table", table)
                    val start = p
                    p++ // min code size
                    while (true) { val len = u8(); if (len == 0) break; p += len }
                    val indices = LzwDecoder.decode(b.copyOfRange(start, p), w * h)
                    assertEquals(w * h, indices.size)
                    frames += DecodedFrame(w, h, IntArray(w * h) { table!![indices[it].toInt() and 0xFF] }, delay)
                }
                0x3B -> break@loop
                else -> throw AssertionError("unexpected block 0x${block.toString(16)} at ${p - 1}")
            }
        }
        assertEquals("bytes after trailer", b.size, p)
        return frames to loop
    }
}

/** javax.imageio through reflection: unit tests compile against android.jar but run on the host JDK. */
internal object ImageIoGif {
    fun decode(bytes: ByteArray): List<DecodedFrame>? {
        val imageIO = try { Class.forName("javax.imageio.ImageIO") } catch (_: ClassNotFoundException) { return null }
        val readerCls = Class.forName("javax.imageio.ImageReader")
        val imgCls = Class.forName("java.awt.image.BufferedImage")
        val readers = imageIO.getMethod("getImageReadersByFormatName", String::class.java).invoke(null, "gif") as Iterator<*>
        val reader = readers.next()!!
        val iis = imageIO.getMethod("createImageInputStream", Any::class.java).invoke(null, ByteArrayInputStream(bytes))
        readerCls.getMethod("setInput", Any::class.java).invoke(reader, iis)
        val n = readerCls.getMethod("getNumImages", Boolean::class.javaPrimitiveType).invoke(reader, true) as Int
        val read = readerCls.getMethod("read", Int::class.javaPrimitiveType)
        val getW = imgCls.getMethod("getWidth"); val getH = imgCls.getMethod("getHeight")
        val getRGB = imgCls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        return (0 until n).map { i ->
            val img = read.invoke(reader, i)
            val w = getW.invoke(img) as Int; val h = getH.invoke(img) as Int
            DecodedFrame(w, h, IntArray(w * h) { (getRGB.invoke(img, it % w, it / w) as Int) and 0xFFFFFF }, 0)
        }
    }
}

class GifWriterTest {

    private fun expectedRgb(indices: ByteArray, palette: IntArray) =
        IntArray(indices.size) { palette[indices[it].toInt() and 0xFF] and 0xFFFFFF }

    private fun assertDecodesTo(bytes: ByteArray, frames: List<Pair<ByteArray, IntArray>>, w: Int, h: Int) {
        val (parsed, _) = GifParser.parse(bytes)
        assertEquals(frames.size, parsed.size)
        frames.forEachIndexed { i, (idx, pal) ->
            assertEquals(w, parsed[i].width); assertEquals(h, parsed[i].height)
            assertArrayEquals("frame $i (own parser)", expectedRgb(idx, pal), parsed[i].rgb)
        }
        val viaImageIo = ImageIoGif.decode(bytes)
        assertNotNull("javax.imageio unavailable on the test JVM", viaImageIo)
        assertEquals(frames.size, viaImageIo!!.size)
        frames.forEachIndexed { i, (idx, pal) ->
            assertArrayEquals("frame $i (ImageIO)", expectedRgb(idx, pal), viaImageIo[i].rgb)
        }
    }

    @Test fun headerLoopAndTrailer() {
        val bos = ByteArrayOutputStream()
        GifWriter(bos, 3, 2, intArrayOf(0x000000, 0xFFFFFF), 0).use {
            it.writeFrame(ByteArray(6) { i -> (i % 2).toByte() }, null, 5)
        }
        val b = bos.toByteArray()
        assertEquals("GIF89a", String(b, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B, b.last().toInt() and 0xFF)
        val (frames, loop) = GifParser.parse(b)
        assertEquals(0, loop)
        assertEquals(1, frames.size)
        assertEquals(5, frames[0].delayCs)

        val once = ByteArrayOutputStream()
        GifWriter(once, 3, 2, intArrayOf(0x000000, 0xFFFFFF), null).use { it.writeFrame(ByteArray(6), null, 5) }
        assertEquals(null, GifParser.parse(once.toByteArray()).second)

        val thrice = ByteArrayOutputStream()
        GifWriter(thrice, 3, 2, intArrayOf(0x000000, 0xFFFFFF), 3).use { it.writeFrame(ByteArray(6), null, 5) }
        assertEquals(3, GifParser.parse(thrice.toByteArray()).second)
    }

    @Test fun globalPaletteTwoFrames() {
        val palette = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val w = 7; val h = 5
        val f1 = ByteArray(w * h) { (it % 4).toByte() }
        val f2 = ByteArray(w * h) { ((it * 3) % 4).toByte() }
        val bos = ByteArrayOutputStream()
        GifWriter(bos, w, h, palette, 0).use { g ->
            g.writeFrame(f1, null, 10)
            g.writeFrame(f2, null, 20)
            assertEquals(2, g.frameCount)
        }
        assertDecodesTo(bos.toByteArray(), listOf(f1 to palette, f2 to palette), w, h)
    }

    @Test fun localPalettesWithoutGlobal() {
        val w = 9; val h = 4
        val p1 = intArrayOf(0x102030, 0x405060, 0x708090)
        val p2 = IntArray(200) { it * 0x010203 }
        val f1 = ByteArray(w * h) { (it % 3).toByte() }
        val f2 = ByteArray(w * h) { (it % 200).toByte() }
        val bos = ByteArrayOutputStream()
        GifWriter(bos, w, h, null, null).use { g ->
            g.writeFrame(f1, p1, 4)
            g.writeFrame(f2, p2, 4)
        }
        assertDecodesTo(bos.toByteArray(), listOf(f1 to p1, f2 to p2), w, h)
    }

    @Test fun largeNoisyFrameSurvivesDictionaryResets() {
        val w = 320; val h = 240
        val rnd = Random(3)
        val palette = IntArray(256) { rnd.nextInt(0x1000000) }
        val f = ByteArray(w * h) { rnd.nextInt(256).toByte() }
        val bos = ByteArrayOutputStream()
        GifWriter(bos, w, h, palette, 0).use { it.writeFrame(f, null, 3) }
        assertDecodesTo(bos.toByteArray(), listOf(f to palette), w, h)
    }

    @Test fun colorTableSizing() {
        assertEquals(0, ColorTable(intArrayOf(1)).sizeCode)
        assertEquals(2, ColorTable(intArrayOf(1)).entries)
        assertEquals(2, ColorTable(intArrayOf(1)).minCodeSize)
        assertEquals(1, ColorTable(IntArray(3)).sizeCode)
        assertEquals(4, ColorTable(IntArray(3)).entries)
        assertEquals(2, ColorTable(IntArray(5)).sizeCode)
        assertEquals(3, ColorTable(IntArray(5)).minCodeSize)
        assertEquals(7, ColorTable(IntArray(256)).sizeCode)
        assertEquals(256, ColorTable(IntArray(256)).entries)
        assertEquals(8, ColorTable(IntArray(256)).minCodeSize)
        assertTrue(ColorTable(IntArray(129)).entries == 256)
    }
}
