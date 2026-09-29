package dev.xerootg.gifmaker.gif

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Reference GIF-LZW decoder used only by tests. */
internal object LzwDecoder {
    fun decode(imageData: ByteArray, expectedLength: Int): ByteArray {
        val minCodeSize = imageData[0].toInt()
        val payload = ByteArrayOutputStream()
        var pos = 1
        while (true) {
            val len = imageData[pos++].toInt() and 0xFF
            if (len == 0) break
            payload.write(imageData, pos, len)
            pos += len
        }
        assertEquals("trailing bytes after block terminator", imageData.size, pos)
        val buf = payload.toByteArray()

        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var next = eoi + 1
        val prefix = IntArray(4096) { -1 }
        val suffix = IntArray(4096)
        val length = IntArray(4096)
        for (i in 0 until clear) { suffix[i] = i; length[i] = 1 }

        val out = ByteArrayOutputStream(expectedLength)
        var bitPos = 0
        fun readCode(): Int {
            var v = 0
            for (i in 0 until codeSize) {
                val idx = (bitPos + i) / 8
                if (idx >= buf.size) return -1
                val bit = (buf[idx].toInt() shr ((bitPos + i) % 8)) and 1
                v = v or (bit shl i)
            }
            bitPos += codeSize
            return v
        }
        fun firstOf(code: Int): Int { var c = code; while (prefix[c] >= 0) c = prefix[c]; return suffix[c] }
        fun emit(code: Int) {
            val s = ByteArray(length[code]); var c = code; var i = length[code] - 1
            while (c >= 0) { s[i--] = suffix[c].toByte(); c = prefix[c] }
            out.write(s)
        }
        fun addEntry(prev: Int, first: Int) {
            if (next < 4096) {
                prefix[next] = prev; suffix[next] = first; length[next] = length[prev] + 1
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
        }

        var prev = -1
        var sawEoi = false
        while (true) {
            val code = readCode()
            if (code < 0) break
            if (code == clear) { codeSize = minCodeSize + 1; next = eoi + 1; prev = -1; continue }
            if (code == eoi) { sawEoi = true; break }
            if (prev == -1) { emit(code); prev = code; continue }
            when {
                code < next -> { addEntry(prev, firstOf(code)); emit(code) }
                code == next -> { addEntry(prev, firstOf(prev)); emit(code) }
                else -> throw AssertionError("invalid code $code (next=$next)")
            }
            prev = code
        }
        assertEquals("missing EOI", true, sawEoi)
        return out.toByteArray()
    }
}

class LzwEncoderTest {

    private fun roundTrip(indices: ByteArray, minCodeSize: Int) {
        val bos = ByteArrayOutputStream()
        LzwEncoder(bos).encode(indices, minCodeSize)
        val decoded = LzwDecoder.decode(bos.toByteArray(), indices.size)
        assertArrayEquals(indices, decoded)
    }

    @Test fun emptyInput() = roundTrip(ByteArray(0), 8)

    @Test fun singlePixel() = roundTrip(byteArrayOf(5), 8)

    @Test fun constantRunTriggersKwKwK() = roundTrip(ByteArray(10_000) { 3 }, 2)

    @Test fun smallAlphabetLongInput() {
        val rnd = Random(7)
        roundTrip(ByteArray(50_000) { (rnd.nextInt(4)).toByte() }, 2)
    }

    @Test fun randomBytesOverflowTableManyTimes() {
        val rnd = Random(42)
        roundTrip(ByteArray(300_000) { rnd.nextInt(256).toByte() }, 8)
    }

    @Test fun structuredImageLike() {
        val w = 640; val h = 480
        val data = ByteArray(w * h) { i -> (((i % w) / 5 + (i / w) / 7) % 200).toByte() }
        roundTrip(data, 8)
    }

    @Test fun everyMinCodeSize() {
        val rnd = Random(1)
        for (mcs in 2..8) {
            val alphabet = 1 shl mcs
            roundTrip(ByteArray(20_000) { rnd.nextInt(alphabet).toByte() }, mcs)
        }
    }

    @Test fun encoderInstanceIsReusableAcrossFrames() {
        val bos = ByteArrayOutputStream()
        val enc = LzwEncoder(bos)
        val a = ByteArray(5000) { (it % 7).toByte() }
        val b = ByteArray(5000) { (it % 11).toByte() }
        enc.encode(a, 4)
        val split = bos.size()
        enc.encode(b, 4)
        val all = bos.toByteArray()
        assertArrayEquals(a, LzwDecoder.decode(all.copyOfRange(0, split), a.size))
        assertArrayEquals(b, LzwDecoder.decode(all.copyOfRange(split, all.size), b.size))
    }
}
