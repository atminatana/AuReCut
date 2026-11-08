package org.aurecut.app.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Growable float buffer. */
class FloatBuf {
    var data = FloatArray(1 shl 14)
        private set
    var size = 0

    fun clear() {
        size = 0
    }

    fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }
}

/**
 * Streaming windowed-sinc resampler for any rational rate ratio.
 * Samples are interleaved floats in [-1, 1]. Blackman window, 16 zero crossings per side,
 * the cut-off drops below Nyquist of the output when downsampling.
 */
class Resampler(val inRate: Int, val outRate: Int, val channels: Int) {
    private val up: Int
    private val down: Int
    private val half: Int
    private val taps: Int
    private val phases: Int
    private val table: FloatArray

    private var pending = FloatArray(1 shl 14)
    private var pendingFrames = 0
    private var baseIndex = 0L // absolute input frame index of pending[0]
    private var outIndex = 0L

    init {
        require(inRate > 0 && outRate > 0 && channels > 0)
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        val cut = min(1.0, outRate.toDouble() / inRate)
        half = ceil(ZERO_CROSSINGS / cut).toInt()
        taps = 2 * half
        phases = min(up, MAX_PHASES)
        table = FloatArray(phases * taps)
        for (q in 0 until phases) {
            val f = q.toDouble() / phases
            var sum = 0.0
            val row = DoubleArray(taps)
            for (t in 0 until taps) {
                val d = t - (half - 1)
                val u = f - d
                val c = cut * sinc(cut * u) * blackman(u / half)
                row[t] = c
                sum += c
            }
            for (t in 0 until taps) table[q * taps + t] = (row[t] / sum).toFloat()
        }
    }

    /** Number of output frames produced for [inFrames] input frames, once finished. */
    fun expectedOutputFrames(inFrames: Long): Long = (inFrames * up + down - 1) / down

    /** Appends [inFrames] frames from [input] and writes all computable output frames into [out]. */
    fun process(input: FloatArray, inFrames: Int, out: FloatBuf, last: Boolean) {
        val ch = channels
        if (inFrames > 0) {
            val need = (pendingFrames + inFrames) * ch
            if (pending.size < need) pending = pending.copyOf(maxOf(pending.size * 2, need))
            System.arraycopy(input, 0, pending, pendingFrames * ch, inFrames * ch)
            pendingFrames += inFrames
        }
        val totalInput = baseIndex + pendingFrames
        while (true) {
            val num = outIndex * down
            var i0 = Math.floorDiv(num, up.toLong())
            val rem = (num - i0 * up).toInt()
            var q = ((rem.toLong() * phases + up / 2) / up).toInt()
            if (q >= phases) {
                q = 0
                i0 += 1
            }
            if (last) {
                if (i0 >= totalInput) break
            } else if (i0 + half >= totalInput) {
                break
            }
            out.ensure(ch)
            val first = i0 - (half - 1) - baseIndex // index into pending of the first tap
            val rowBase = q * taps
            for (c in 0 until ch) {
                var acc = 0f
                if (first >= 0 && first + taps <= pendingFrames) {
                    var idx = (first * ch + c).toInt()
                    for (t in 0 until taps) {
                        acc += table[rowBase + t] * pending[idx]
                        idx += ch
                    }
                } else {
                    for (t in 0 until taps) {
                        val j = first + t
                        if (j < 0 || j >= pendingFrames) continue
                        acc += table[rowBase + t] * pending[(j * ch + c).toInt()]
                    }
                }
                out.data[out.size + c] = acc
            }
            out.size += ch
            outIndex++
        }
        // Drop input that no later output sample needs.
        val num = outIndex * down
        val nextI0 = Math.floorDiv(num, up.toLong())
        val keepFrom = nextI0 - (half - 1)
        val drop = (keepFrom - baseIndex).coerceIn(0L, pendingFrames.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(pending, drop * ch, pending, 0, (pendingFrames - drop) * ch)
            pendingFrames -= drop
            baseIndex += drop
        }
    }

    private companion object {
        const val ZERO_CROSSINGS = 16.0
        const val MAX_PHASES = 2048

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun sinc(x: Double): Double = if (kotlin.math.abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)

        fun blackman(r: Double): Double =
            if (kotlin.math.abs(r) >= 1.0) 0.0 else 0.42 + 0.5 * cos(PI * r) + 0.08 * cos(2 * PI * r)
    }
}
