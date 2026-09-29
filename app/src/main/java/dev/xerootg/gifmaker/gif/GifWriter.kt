package dev.xerootg.gifmaker.gif

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream

/**
 * A colour table as it appears in the file: padded to a power-of-two entry count.
 * Colours are 0xXXRRGGBB; the top byte is ignored.
 */
class ColorTable(private val colors: IntArray) {
    /** The 3-bit "size of colour table" field: entries = 2^(sizeCode+1). */
    val sizeCode: Int
    val entries: Int
    /** LZW minimum code size for image data using this table (GIF requires at least 2). */
    val minCodeSize: Int

    init {
        require(colors.size in 1..256) { "colour table must hold 1..256 entries" }
        var code = 0
        while ((2 shl code) < colors.size) code++
        sizeCode = code
        entries = 2 shl code
        minCodeSize = maxOf(2, code + 1)
    }

    fun write(out: OutputStream) {
        for (i in 0 until entries) {
            val c = if (i < colors.size) colors[i] else 0
            out.write((c ushr 16) and 0xFF)
            out.write((c ushr 8) and 0xFF)
            out.write(c and 0xFF)
        }
    }
}

/**
 * Streaming GIF89a writer. Frames are full-canvas, opaque, disposal "do not dispose".
 *
 * @param globalPalette optional global colour table; frames written without a local palette use it.
 * @param loopCount `null` = no NETSCAPE extension (play once); `0` = loop forever; `n` = repeat n times.
 */
class GifWriter(
    stream: OutputStream,
    val width: Int,
    val height: Int,
    globalPalette: IntArray?,
    private val loopCount: Int?,
) : Closeable {
    private val out = BufferedOutputStream(stream, 1 shl 16)
    private val global: ColorTable? = globalPalette?.let { ColorTable(it) }
    private val lzw = LzwEncoder(out)
    private var finished = false
    var frameCount = 0
        private set

    init {
        require(width in 1..65535 && height in 1..65535) { "GIF dimensions must be 1..65535" }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        // packed: GCT flag | colour resolution (8 bits -> 7) | sort=0 | GCT size
        out.write(if (global != null) (0xF0 or global.sizeCode) else 0x70)
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio: square
        global?.write(out)
        if (loopCount != null) {
            out.write(0x21); out.write(0xFF); out.write(11)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(3); out.write(1)
            writeShort(loopCount.coerceIn(0, 65535))
            out.write(0)
        }
    }

    /**
     * @param indices one palette index per pixel, row-major, width*height entries.
     * @param localPalette per-frame colour table, or `null` to use the global one.
     * @param delayCs frame delay in hundredths of a second.
     */
    fun writeFrame(indices: ByteArray, localPalette: IntArray?, delayCs: Int) {
        check(!finished) { "writer already finished" }
        require(indices.size == width * height) { "expected ${width * height} indices, got ${indices.size}" }
        val table = localPalette?.let { ColorTable(it) } ?: global
            ?: throw IllegalArgumentException("frame has no local palette and writer has no global palette")

        // Graphic Control Extension
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0x04) // disposal method 1 (do not dispose), no user input, no transparency
        writeShort(delayCs.coerceIn(0, 65535))
        out.write(0) // transparent colour index (unused)
        out.write(0) // block terminator

        // Image Descriptor
        out.write(0x2C)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        if (localPalette != null) {
            out.write(0x80 or table.sizeCode)
            table.write(out)
        } else {
            out.write(0)
        }

        lzw.encode(indices, table.minCodeSize)
        frameCount++
    }

    fun finish() {
        if (!finished) {
            finished = true
            out.write(0x3B)
            out.flush()
        }
    }

    override fun close() {
        finish()
        out.close()
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }
}
