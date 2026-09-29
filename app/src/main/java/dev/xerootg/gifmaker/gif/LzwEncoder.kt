package dev.xerootg.gifmaker.gif

import java.io.OutputStream

/**
 * GIF-flavoured LZW encoder: variable code width (minCodeSize+1 .. 12 bits), LSB-first bit packing,
 * output chunked into 255-byte data sub-blocks, terminated by a zero-length block.
 *
 * The dictionary is a direct-mapped table keyed by `(prefixCode shl 8) or nextByte`. A value of 0
 * means "absent"; otherwise it stores `code + 1`. The table is 4 MiB and is reused across frames.
 *
 * Code-width growth follows the decoder convention every GIF reader uses: after the entry with
 * code `1 shl codeSize` has been added, subsequent codes are emitted one bit wider. When the table
 * is full (4096 codes) a Clear code is emitted and the dictionary restarts.
 */
class LzwEncoder(private val out: OutputStream) {
    private val table = IntArray(1 shl 20)
    private val block = ByteArray(255)
    private var blockLen = 0
    private var bitBuf = 0
    private var bitCount = 0

    /**
     * Encodes [indices] (each < 2^[minCodeSize]) as a complete GIF image-data section:
     * the LZW minimum code size byte, the sub-blocks, and the block terminator.
     */
    fun encode(indices: ByteArray, minCodeSize: Int) {
        require(minCodeSize in 2..8) { "minCodeSize must be 2..8, was $minCodeSize" }
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1

        out.write(minCodeSize)
        table.fill(0)
        emit(clearCode, codeSize)

        if (indices.isEmpty()) {
            emit(eoiCode, codeSize)
            finishStream()
            return
        }

        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val c = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            val found = table[key]
            if (found != 0) {
                prefix = found - 1
                continue
            }
            emit(prefix, codeSize)
            if (nextCode < MAX_CODES) {
                table[key] = nextCode + 1
                if (nextCode == (1 shl codeSize) && codeSize < MAX_BITS) codeSize++
                nextCode++
            } else {
                emit(clearCode, codeSize)
                table.fill(0)
                codeSize = minCodeSize + 1
                nextCode = eoiCode + 1
            }
            prefix = c
        }
        emit(prefix, codeSize)
        emit(eoiCode, codeSize)
        finishStream()
    }

    private fun emit(code: Int, codeSize: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            writeByte(bitBuf and 0xFF)
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    private fun writeByte(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == block.size) flushBlock()
    }

    private fun flushBlock() {
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
    }

    private fun finishStream() {
        if (bitCount > 0) writeByte(bitBuf and 0xFF)
        bitBuf = 0
        bitCount = 0
        flushBlock()
        out.write(0)
    }

    private companion object {
        const val MAX_BITS = 12
        const val MAX_CODES = 1 shl MAX_BITS
    }
}
