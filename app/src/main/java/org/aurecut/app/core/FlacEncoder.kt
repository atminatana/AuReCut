package org.aurecut.app.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.min

/**
 * Pure Kotlin FLAC encoder for 16-bit mono/stereo audio.
 *
 * Fixed block size (4096), CONSTANT / FIXED (order 0-4) / VERBATIM subframes, Rice residual coding
 * with partition search, MD5 of the unencoded audio in STREAMINFO. The result is a standard .flac file.
 * It does not depend on the device having a FLAC encoder.
 */
class FlacEncoder(file: File, private val sampleRate: Int, private val channels: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private val md5 = MessageDigest.getInstance("MD5")
    private val bw = BitWriter()
    private val pending = ShortArray(BLOCK * channels)
    private var pendingFrames = 0
    private val chans = Array(channels) { IntArray(BLOCK) }
    private val residual = IntArray(BLOCK)
    private val md5Bytes = ByteArray(BLOCK * channels * 2)

    private var totalFrames = 0L
    private var frameNumber = 0L
    private var firstBlockSize = 0
    private var minFrameBytes = Int.MAX_VALUE
    private var maxFrameBytes = 0

    init {
        require(channels in 1..2) { "channels must be 1 or 2" }
        require(sampleRate in 1..1_048_575) { "unsupported sample rate" }
        raf.setLength(0)
        raf.write(ByteArray(HEADER_BYTES)) // placeholder, rewritten in finish()
    }

    /** Appends [frames] interleaved frames from [buf]. */
    fun write(buf: ShortArray, frames: Int) {
        var off = 0
        while (off < frames) {
            val n = min(frames - off, BLOCK - pendingFrames)
            System.arraycopy(buf, off * channels, pending, pendingFrames * channels, n * channels)
            pendingFrames += n
            off += n
            if (pendingFrames == BLOCK) {
                encodeBlock(BLOCK)
                pendingFrames = 0
            }
        }
    }

    /** Flushes the last block and writes the final STREAMINFO. Call once, before [close]. */
    fun finish() {
        if (pendingFrames > 0) {
            encodeBlock(pendingFrames)
            pendingFrames = 0
        }
        val minBlock: Int
        val maxBlock: Int
        if (frameNumber <= 1L) {
            // STREAMINFO block sizes below 16 are invalid; a lone short block is still allowed to be shorter.
            minBlock = if (firstBlockSize > 0) maxOf(firstBlockSize, MIN_STREAMINFO_BLOCK) else BLOCK
            maxBlock = minBlock
        } else {
            minBlock = BLOCK
            maxBlock = BLOCK
        }
        val sb = BitWriter(64)
        sb.writeBits(0x664C6143L, 32) // "fLaC"
        sb.writeBits(0x80, 8) // last metadata block, type STREAMINFO
        sb.writeBits(34, 24)
        sb.writeBits(minBlock, 16)
        sb.writeBits(maxBlock, 16)
        sb.writeBits(if (minFrameBytes == Int.MAX_VALUE) 0 else minFrameBytes, 24)
        sb.writeBits(maxFrameBytes, 24)
        sb.writeBits(sampleRate, 20)
        sb.writeBits(channels - 1, 3)
        sb.writeBits(15, 5) // bits per sample - 1
        sb.writeBits(totalFrames ushr 32, 4)
        sb.writeBits(totalFrames and 0xFFFFFFFFL, 32)
        for (b in md5.digest()) sb.writeBits(b.toInt() and 0xFF, 8)
        raf.seek(0)
        raf.write(sb.buf, 0, sb.length)
    }

    override fun close() {
        raf.close()
    }

    private fun encodeBlock(n: Int) {
        var k = 0
        for (i in 0 until n * channels) {
            val s = pending[i].toInt()
            md5Bytes[k++] = s.toByte()
            md5Bytes[k++] = (s shr 8).toByte()
        }
        md5.update(md5Bytes, 0, k)
        for (c in 0 until channels) {
            val dst = chans[c]
            for (i in 0 until n) dst[i] = pending[i * channels + c].toInt()
        }

        bw.reset()
        bw.writeBits(0xFFF8, 16) // sync, fixed block size
        val blockCode = when {
            n == BLOCK -> 12
            n <= 256 -> 6
            else -> 7
        }
        bw.writeBits(blockCode, 4)
        bw.writeBits(0, 4) // sample rate from STREAMINFO
        bw.writeBits(channels - 1, 4) // independent channels
        bw.writeBits(4, 3) // 16 bits per sample
        bw.writeBits(0, 1)
        bw.writeUtf8(frameNumber)
        if (blockCode == 6) bw.writeBits(n - 1, 8) else if (blockCode == 7) bw.writeBits(n - 1, 16)
        bw.writeBits(Crc.crc8(bw.buf, 0, bw.length), 8)

        for (c in 0 until channels) encodeSubframe(chans[c], n)
        bw.alignToByte()
        bw.writeBits(Crc.crc16(bw.buf, 0, bw.length), 16)
        raf.write(bw.buf, 0, bw.length)

        if (frameNumber == 0L) firstBlockSize = n
        if (bw.length < minFrameBytes) minFrameBytes = bw.length
        if (bw.length > maxFrameBytes) maxFrameBytes = bw.length
        totalFrames += n
        frameNumber++
    }

    private fun encodeSubframe(x: IntArray, n: Int) {
        var same = true
        for (i in 1 until n) {
            if (x[i] != x[0]) {
                same = false
                break
            }
        }
        if (same) {
            bw.writeBits(0, 1)
            bw.writeBits(0, 6) // CONSTANT
            bw.writeBits(0, 1)
            bw.writeBits(x[0] and 0xFFFF, 16)
            return
        }
        if (n < MIN_PREDICTED) {
            writeVerbatim(x, n)
            return
        }

        var order = 0
        var bestSum = Long.MAX_VALUE
        for (o in 0..MAX_ORDER) {
            var sum = 0L
            for (i in MAX_ORDER until n) {
                val e = fixedResidual(x, i, o)
                sum += if (e < 0) -e.toLong() else e.toLong()
            }
            if (sum < bestSum) {
                bestSum = sum
                order = o
            }
        }
        val count = n - order
        for (i in order until n) residual[i - order] = fixedResidual(x, i, order)

        // Rice partition order search, using the usual sum-based cost estimate.
        var maxP = 0
        while (maxP < MAX_PARTITION_ORDER && n % (1 shl (maxP + 1)) == 0 && (n shr (maxP + 1)) >= MIN_PARTITION && (n shr (maxP + 1)) > order) maxP++
        var sums = LongArray(1 shl maxP)
        run {
            val psize = n shr maxP
            var idx = 0
            for (j in 0 until (1 shl maxP)) {
                val cnt = psize - if (j == 0) order else 0
                var s = 0L
                repeat(cnt) {
                    val e = residual[idx++]
                    s += if (e >= 0) 2L * e else -2L * e - 1
                }
                sums[j] = s
            }
        }
        var bestP = 0
        var bestBits = Long.MAX_VALUE
        var p = maxP
        while (true) {
            val parts = 1 shl p
            val psize = n shr p
            var bits = 6L // coding method (2) + partition order (4)
            for (j in 0 until parts) {
                val cnt = psize - if (j == 0) order else 0
                bits += 4 + riceCost(sums[j], cnt)
            }
            if (bits < bestBits) {
                bestBits = bits
                bestP = p
            }
            if (p == 0) break
            val merged = LongArray(1 shl (p - 1))
            for (j in merged.indices) merged[j] = sums[2 * j] + sums[2 * j + 1]
            sums = merged
            p--
        }
        val verbatimBits = 16L * n
        val predictedBits = 16L * order + bestBits
        if (predictedBits >= verbatimBits) {
            writeVerbatim(x, n)
            return
        }

        val parts = 1 shl bestP
        val psize = n shr bestP
        bw.writeBits(0, 1)
        bw.writeBits(8 + order, 6) // FIXED, order 0..4
        bw.writeBits(0, 1)
        for (i in 0 until order) bw.writeBits(x[i] and 0xFFFF, 16)
        bw.writeBits(0, 2) // Rice coding with 4-bit parameters
        bw.writeBits(bestP, 4)
        var idx = 0
        for (j in 0 until parts) {
            val cnt = psize - if (j == 0) order else 0
            var s = 0L
            for (i in 0 until cnt) {
                val e = residual[idx + i]
                s += if (e >= 0) 2L * e else -2L * e - 1
            }
            val k = riceParam(s, cnt)
            bw.writeBits(k, 4)
            val mask = (1L shl k) - 1
            for (i in 0 until cnt) {
                val e = residual[idx++]
                val u = if (e >= 0) 2L * e else -2L * e - 1
                bw.writeUnary((u ushr k).toInt())
                bw.writeBits(u and mask, k)
            }
        }
        if (count < 0) error("unreachable")
    }

    private fun writeVerbatim(x: IntArray, n: Int) {
        bw.writeBits(0, 1)
        bw.writeBits(1, 6) // VERBATIM
        bw.writeBits(0, 1)
        for (i in 0 until n) bw.writeBits(x[i] and 0xFFFF, 16)
    }

    private fun fixedResidual(x: IntArray, i: Int, order: Int): Int = when (order) {
        0 -> x[i]
        1 -> x[i] - x[i - 1]
        2 -> x[i] - 2 * x[i - 1] + x[i - 2]
        3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
        else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
    }

    private fun riceParam(sum: Long, cnt: Int): Int {
        if (cnt == 0) return 0
        var best = 0
        var bestCost = cnt.toLong() + sum
        for (k in 1..MAX_RICE) {
            val cost = cnt.toLong() * (k + 1) + (sum shr k)
            if (cost < bestCost) {
                bestCost = cost
                best = k
            } else if (cost > bestCost) {
                break
            }
        }
        return best
    }

    private fun riceCost(sum: Long, cnt: Int): Long {
        if (cnt == 0) return 0
        val k = riceParam(sum, cnt)
        return cnt.toLong() * (k + 1) + (sum shr k)
    }

    companion object {
        const val BLOCK = 4096
        private const val HEADER_BYTES = 42
        private const val MAX_ORDER = 4
        private const val MAX_RICE = 14
        private const val MAX_PARTITION_ORDER = 8
        private const val MIN_PARTITION = 32
        private const val MIN_PREDICTED = 16
        private const val MIN_STREAMINFO_BLOCK = 16
    }
}
