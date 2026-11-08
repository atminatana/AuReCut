package org.aurecut.app.core

import kotlin.math.log10
import kotlin.math.pow

/** Peak normalization helpers. Full scale is 32768 (sample value 32767 is about -0.0003 dBFS). */
object Normalizer {
    const val DEFAULT_TARGET_DB = -1.0
    const val MIN_TARGET_DB = -24.0
    const val MAX_TARGET_DB = 0.0

    /** Largest absolute sample value in [startFrame, endFrame), 0..32768. */
    fun peakAbs(
        reader: PcmReader,
        channels: Int,
        startFrame: Long,
        endFrame: Long,
        isCancelled: () -> Boolean = { false },
    ): Int {
        val buf = ShortArray(BLOCK * channels)
        var pos = startFrame
        var peak = 0
        while (pos < endFrame) {
            if (isCancelled()) return peak
            val n = reader.read(pos, minOf(BLOCK.toLong(), endFrame - pos).toInt(), buf)
            if (n == 0) break
            for (i in 0 until n * channels) {
                val s = buf[i].toInt()
                val a = if (s < 0) -s else s
                if (a > peak) peak = a
            }
            pos += n
        }
        return peak
    }

    fun peakDb(peak: Int): Double = 20.0 * log10(peak / 32768.0)

    /** Linear gain that brings [peak] to [targetDb], or null when the audio is silent. */
    fun gainFor(peak: Int, targetDb: Double): Double? =
        if (peak <= 0) null else 10.0.pow((targetDb - peakDb(peak)) / 20.0)

    fun gainDb(gain: Double): Double = 20.0 * log10(gain)

    private const val BLOCK = 16384
}
