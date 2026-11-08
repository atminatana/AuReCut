package org.aurecut.app.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Min/max per pixel column for drawing, valid for the view it was computed for. */
class WaveColumns(
    val start: Double,
    val framesPerPixel: Double,
    val mins: ShortArray,
    val maxs: ShortArray,
)

class PeakLevel(val block: Int, val mins: ShortArray, val maxs: ShortArray)

/**
 * Multi-resolution min/max envelope of a clip. The base level covers 256 frames per entry,
 * every next level is 4x coarser. Finer zoom than the base level reads the PCM file directly.
 */
class PeakPyramid(val totalFrames: Long, val channels: Int, val levels: List<PeakLevel>) {

    fun columns(reader: PcmReader, startFrame: Double, framesPerPixel: Double, width: Int): WaveColumns {
        val mins = ShortArray(width)
        val maxs = ShortArray(width)
        val level = levels.lastOrNull { it.block <= framesPerPixel }
        if (level != null) fromLevel(level, startFrame, framesPerPixel, width, mins, maxs)
        else fromRaw(reader, startFrame, framesPerPixel, width, mins, maxs)
        return WaveColumns(startFrame, framesPerPixel, mins, maxs)
    }

    private fun fromLevel(level: PeakLevel, start: Double, fpp: Double, width: Int, outMin: ShortArray, outMax: ShortArray) {
        val size = level.mins.size
        for (px in 0 until width) {
            val f0 = start + px * fpp
            val f1 = f0 + fpp
            val b0 = floor(f0 / level.block).toLong()
            var b1 = max(b0 + 1, ceil(f1 / level.block).toLong())
            if (b0 < 0 || b0 >= size) continue
            if (b1 > size) b1 = size.toLong()
            var mn = Short.MAX_VALUE
            var mx = Short.MIN_VALUE
            for (b in b0.toInt() until b1.toInt()) {
                if (level.mins[b] < mn) mn = level.mins[b]
                if (level.maxs[b] > mx) mx = level.maxs[b]
            }
            outMin[px] = mn
            outMax[px] = mx
        }
    }

    private fun fromRaw(reader: PcmReader, start: Double, fpp: Double, width: Int, outMin: ShortArray, outMax: ShortArray) {
        val first = max(0L, floor(start).toLong())
        val last = min(totalFrames, ceil(start + fpp * width).toLong() + 1)
        val count = (last - first).toInt()
        if (count <= 0) return
        val buf = ShortArray(count * channels)
        val got = reader.read(first, count, buf)
        for (px in 0 until width) {
            val a = (floor(start + px * fpp).toLong() - first).toInt()
            val b = max(a + 1, (ceil(start + (px + 1) * fpp).toLong() - first).toInt()).coerceAtMost(got)
            if (a < 0 || a >= got) continue
            var mn = Short.MAX_VALUE
            var mx = Short.MIN_VALUE
            for (s in a * channels until b * channels) {
                val v = buf[s]
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            outMin[px] = mn
            outMax[px] = mx
        }
    }

    companion object {
        const val BASE_BLOCK = 256

        fun build(
            clip: PcmClip,
            onProgress: (Float) -> Unit = {},
            isCancelled: () -> Boolean = { false },
        ): PeakPyramid {
            val total = clip.frames
            val ch = clip.channels
            val blocks = ((total + BASE_BLOCK - 1) / BASE_BLOCK).toInt()
            val mins = ShortArray(blocks)
            val maxs = ShortArray(blocks)
            PcmReader(clip).use { reader ->
                val chunk = BASE_BLOCK * 256
                val buf = ShortArray(chunk * ch)
                var pos = 0L
                var blk = 0
                while (pos < total) {
                    if (isCancelled()) break
                    val n = reader.read(pos, min(chunk.toLong(), total - pos).toInt(), buf)
                    if (n == 0) break
                    var i = 0
                    while (i < n) {
                        val e = min(n, i + BASE_BLOCK)
                        var mn = Short.MAX_VALUE
                        var mx = Short.MIN_VALUE
                        for (s in i * ch until e * ch) {
                            val v = buf[s]
                            if (v < mn) mn = v
                            if (v > mx) mx = v
                        }
                        mins[blk] = mn
                        maxs[blk] = mx
                        blk++
                        i = e
                    }
                    pos += n
                    onProgress(pos.toFloat() / total)
                }
            }
            val levels = ArrayList<PeakLevel>()
            levels += PeakLevel(BASE_BLOCK, mins, maxs)
            while (levels.last().mins.size > 256) {
                val prev = levels.last()
                val size = (prev.mins.size + 3) / 4
                val nm = ShortArray(size)
                val nx = ShortArray(size)
                for (i in 0 until size) {
                    var mn = Short.MAX_VALUE
                    var mx = Short.MIN_VALUE
                    for (j in i * 4 until min(prev.mins.size, i * 4 + 4)) {
                        if (prev.mins[j] < mn) mn = prev.mins[j]
                        if (prev.maxs[j] > mx) mx = prev.maxs[j]
                    }
                    nm[i] = mn
                    nx[i] = mx
                }
                levels += PeakLevel(prev.block * 4, nm, nx)
            }
            return PeakPyramid(total, ch, levels)
        }
    }
}
