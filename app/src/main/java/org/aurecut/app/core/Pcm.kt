package org.aurecut.app.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw 16-bit little-endian interleaved PCM stored in a file.
 * The whole editor works on such a file, so long recordings never sit in memory.
 */
class PcmClip(val file: File, val sampleRate: Int, val channels: Int) {
    init {
        require(channels in 1..2) { "channels must be 1 or 2" }
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    val frames: Long get() = file.length() / (2L * channels)
    val durationSec: Double get() = frames.toDouble() / sampleRate
}

/** Random-access reader over a [PcmClip]. Thread-safe: calls are serialized. */
class PcmReader(private val clip: PcmClip) : Closeable {
    private val raf = RandomAccessFile(clip.file, "r")
    private var bytes = ByteArray(0)

    /** Reads up to [count] frames starting at [startFrame] into [out]; returns frames actually read. */
    @Synchronized
    fun read(startFrame: Long, count: Int, out: ShortArray): Int {
        if (count <= 0 || startFrame < 0) return 0
        val ch = clip.channels
        val want = count * 2 * ch
        if (bytes.size < want) bytes = ByteArray(want)
        raf.seek(startFrame * 2L * ch)
        var got = 0
        while (got < want) {
            val n = raf.read(bytes, got, want - got)
            if (n < 0) break
            got += n
        }
        val frames = got / (2 * ch)
        ByteBuffer.wrap(bytes, 0, frames * 2 * ch).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(out, 0, frames * ch)
        return frames
    }

    @Synchronized
    override fun close() {
        raf.close()
    }
}

/** A pull-style stream of interleaved 16-bit frames. */
interface PcmSource {
    val channels: Int

    /** Fills [buf] with up to [maxFrames] frames; returns the number of frames, 0 at end of stream. */
    fun read(buf: ShortArray, maxFrames: Int): Int
}

/** Reads the frame range [startFrame, endFrame) of a clip. */
class RangeSource(
    private val reader: PcmReader,
    override val channels: Int,
    startFrame: Long,
    private val endFrame: Long,
) : PcmSource {
    var position: Long = startFrame
        private set

    override fun read(buf: ShortArray, maxFrames: Int): Int {
        val left = endFrame - position
        if (left <= 0) return 0
        val n = reader.read(position, minOf(left, maxFrames.toLong()).toInt(), buf)
        position += n
        return n
    }
}

/** Multiplies samples by a constant gain with clipping. */
class GainSource(private val src: PcmSource, private val gain: Double) : PcmSource {
    override val channels: Int get() = src.channels

    override fun read(buf: ShortArray, maxFrames: Int): Int {
        val n = src.read(buf, maxFrames)
        if (gain != 1.0) applyGain(buf, n * channels, gain)
        return n
    }

    companion object {
        fun applyGain(buf: ShortArray, samples: Int, gain: Double) {
            for (i in 0 until samples) {
                val v = Math.round(buf[i] * gain)
                buf[i] = if (v > Short.MAX_VALUE) Short.MAX_VALUE else if (v < Short.MIN_VALUE) Short.MIN_VALUE else v.toShort()
            }
        }
    }
}

/** Sample-rate conversion stage built on [Resampler]. */
class ResampleSource(
    private val src: PcmSource,
    inRate: Int,
    outRate: Int,
) : PcmSource {
    override val channels: Int get() = src.channels

    private val resampler = Resampler(inRate, outRate, src.channels)
    private val inShort = ShortArray(CHUNK * src.channels)
    private val inFloat = FloatArray(CHUNK * src.channels)
    private val outFloat = FloatBuf()
    private var outPos = 0 // in samples
    private var finished = false

    override fun read(buf: ShortArray, maxFrames: Int): Int {
        val ch = channels
        while (outPos >= outFloat.size && !finished) {
            outFloat.clear()
            outPos = 0
            val n = src.read(inShort, CHUNK)
            if (n == 0) {
                resampler.process(inFloat, 0, outFloat, last = true)
                finished = true
            } else {
                val total = n * ch
                for (i in 0 until total) inFloat[i] = inShort[i] / 32768f
                resampler.process(inFloat, n, outFloat, last = false)
            }
        }
        val availableFrames = (outFloat.size - outPos) / ch
        val frames = minOf(availableFrames, maxFrames)
        for (i in 0 until frames * ch) {
            val v = Math.round(outFloat.data[outPos + i] * 32768f)
            buf[i] = if (v > Short.MAX_VALUE) Short.MAX_VALUE else if (v < Short.MIN_VALUE) Short.MIN_VALUE else v.toShort()
        }
        outPos += frames * ch
        return frames
    }

    private companion object {
        const val CHUNK = 4096
    }
}
