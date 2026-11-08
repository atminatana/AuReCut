package org.aurecut.app

import org.aurecut.app.core.FloatBuf
import org.aurecut.app.core.Resampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun run(r: Resampler, input: FloatArray, channels: Int = 1): FloatBuf {
        val out = FloatBuf()
        val frames = input.size / channels
        var off = 0
        while (off < frames) {
            val c = min(4096, frames - off)
            r.process(input.copyOfRange(off * channels, (off + c) * channels), c, out, last = false)
            off += c
        }
        r.process(FloatArray(0), 0, out, last = true)
        return out
    }

    private fun tone(rate: Int, hz: Double, seconds: Double, amp: Double = 0.5) =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    @Test
    fun upsample44100To48000KeepsTheSine() {
        val r = Resampler(44100, 48000, 1)
        val out = run(r, tone(44100, 1000.0, 1.0))
        assertEquals(48000, out.size)
        assertEquals(48000L, r.expectedOutputFrames(44100))
        for (k in 200 until out.size - 200) {
            val expected = 0.5 * sin(2 * PI * 1000.0 * k / 48000)
            assertEquals("sample $k", expected, out.data[k].toDouble(), 2e-3)
        }
    }

    @Test
    fun downsample96000To48000PassesLowAndRejectsHigh() {
        fun rms(hz: Double): Double {
            val out = run(Resampler(96000, 48000, 1), tone(96000, hz, 1.0))
            var s = 0.0
            val from = 500
            val to = out.size - 500
            for (k in from until to) s += out.data[k].toDouble() * out.data[k]
            return sqrt(s / (to - from))
        }
        assertEquals(0.5 / sqrt(2.0), rms(1000.0), 5e-3)
        assertTrue("40 kHz must be removed", rms(40000.0) < 0.5 / sqrt(2.0) * 0.01)
    }

    @Test
    fun oddRatioAndOutputLength() {
        val r = Resampler(22050, 48000, 1)
        val n = 22050
        val out = run(r, tone(22050, 440.0, 1.0))
        assertEquals(r.expectedOutputFrames(n.toLong()).toInt(), out.size)
        for (k in 300 until out.size - 300) {
            assertEquals(0.5 * sin(2 * PI * 440.0 * k / 48000), out.data[k].toDouble(), 2e-3)
        }
    }

    @Test
    fun stereoChannelsStayIndependent() {
        val mono = tone(44100, 1000.0, 0.5)
        val stereo = FloatArray(mono.size * 2)
        for (i in mono.indices) {
            stereo[2 * i] = mono[i]
            stereo[2 * i + 1] = 0f
        }
        val out = run(Resampler(44100, 48000, 2), stereo, channels = 2)
        val frames = out.size / 2
        assertEquals(24000, frames)
        for (k in 200 until frames - 200) {
            assertEquals(0.5 * sin(2 * PI * 1000.0 * k / 48000), out.data[2 * k].toDouble(), 2e-3)
            assertEquals(0.0, out.data[2 * k + 1].toDouble(), 1e-6)
        }
    }

    @Test
    fun chunkSizeDoesNotChangeTheResult() {
        val input = tone(44100, 700.0, 0.3)
        val a = run(Resampler(44100, 48000, 1), input)
        val b = FloatBuf().also { out ->
            val r = Resampler(44100, 48000, 1)
            var off = 0
            while (off < input.size) {
                val c = min(137, input.size - off)
                r.process(input.copyOfRange(off, off + c), c, out, false)
                off += c
            }
            r.process(FloatArray(0), 0, out, true)
        }
        assertEquals(a.size, b.size)
        for (i in 0 until a.size) assertEquals(a.data[i].toDouble(), b.data[i].toDouble(), 1e-6)
    }
}
