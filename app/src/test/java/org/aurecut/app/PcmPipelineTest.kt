package org.aurecut.app

import org.aurecut.app.core.FlacEncoder
import org.aurecut.app.core.GainSource
import org.aurecut.app.core.Normalizer
import org.aurecut.app.core.PcmClip
import org.aurecut.app.core.PcmReader
import org.aurecut.app.core.PeakPyramid
import org.aurecut.app.core.RangeSource
import org.aurecut.app.core.WavWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

class PcmPipelineTest {
    private fun tmp(name: String): File = File.createTempFile(name, ".bin").also { it.deleteOnExit() }

    private fun writePcm(samples: ShortArray): File {
        val f = tmp("pcm")
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(samples)
        f.writeBytes(bb.array())
        return f
    }

    private fun noisySine(frames: Int, channels: Int, seed: Long = 1): ShortArray {
        val rnd = Random(seed)
        return ShortArray(frames * channels) { i ->
            val t = i / channels
            (9000 * sin(2 * PI * 440 * t / 44100) + rnd.nextGaussian() * 300 + (i % channels) * 500).toInt().toShort()
        }
    }

    // ---- Normalizer ----

    @Test
    fun peakAndGain() {
        val samples = shortArrayOf(100, -200, 16384, -16000, 5)
        val clip = PcmClip(writePcm(samples), 44100, 1)
        PcmReader(clip).use { r ->
            assertEquals(16384, Normalizer.peakAbs(r, 1, 0, 5))
            assertEquals(200, Normalizer.peakAbs(r, 1, 0, 2))
            assertEquals(0, Normalizer.peakAbs(r, 1, 4, 4))
        }
        assertEquals(-6.0206, Normalizer.peakDb(16384), 1e-3)
        assertNull(Normalizer.gainFor(0, -1.0))
        val g = Normalizer.gainFor(16384, -1.0)!!
        assertEquals(5.0206, Normalizer.gainDb(g), 1e-3)
    }

    @Test
    fun peakOfMinValueIsFullScale() {
        val clip = PcmClip(writePcm(shortArrayOf(Short.MIN_VALUE, 1)), 8000, 1)
        PcmReader(clip).use { assertEquals(32768, Normalizer.peakAbs(it, 1, 0, 2)) }
    }

    @Test
    fun normalizedPeakLandsOnTarget() {
        val clip = PcmClip(writePcm(noisySine(30000, 2)), 44100, 2)
        PcmReader(clip).use { r ->
            val peak = Normalizer.peakAbs(r, 2, 0, clip.frames)
            val gain = Normalizer.gainFor(peak, -1.0)!!
            val src = GainSource(RangeSource(r, 2, 0, clip.frames), gain)
            val buf = ShortArray(4096 * 2)
            var maxAbs = 0
            while (true) {
                val n = src.read(buf, 4096)
                if (n == 0) break
                for (i in 0 until n * 2) maxAbs = max(maxAbs, abs(buf[i].toInt()))
            }
            val db = 20 * Math.log10(maxAbs / 32768.0)
            assertEquals(-1.0, db, 0.01)
        }
    }

    @Test
    fun gainClipsInsteadOfWrapping() {
        val buf = shortArrayOf(30000, -30000, 100)
        GainSource.applyGain(buf, 3, 2.0)
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 200), buf)
    }

    // ---- WAV ----

    @Test
    fun wavHeaderAndData() {
        val samples = noisySine(1000, 2)
        val f = tmp("wav")
        WavWriter(f, 48000, 2).use { w ->
            w.write(samples, 600)
            w.write(samples.copyOfRange(600 * 2, samples.size), 400)
            w.finish()
        }
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 1000 * 4, b.capacity())
        val tag = ByteArray(4)
        b.get(tag); assertEquals("RIFF", String(tag))
        assertEquals(36 + 4000, b.int)
        b.get(tag); assertEquals("WAVE", String(tag))
        b.get(tag); assertEquals("fmt ", String(tag))
        assertEquals(16, b.int)
        assertEquals(1, b.short.toInt())
        assertEquals(2, b.short.toInt())
        assertEquals(48000, b.int)
        assertEquals(48000 * 4, b.int)
        assertEquals(4, b.short.toInt())
        assertEquals(16, b.short.toInt())
        b.get(tag); assertEquals("data", String(tag))
        assertEquals(4000, b.int)
        val back = ShortArray(2000)
        b.asShortBuffer().get(back)
        assertArrayEquals(samples, back)
    }

    @Test
    fun wavSizeLimit() {
        assertTrue(WavWriter.fits(1_000_000_000L, 1))
        assertFalse(WavWriter.fits(1_100_000_000L, 2))
    }

    // ---- FLAC (bit-exact decoding is verified against ffmpeg outside the unit tests) ----

    @Test
    fun flacStructure() {
        val frames = FlacEncoder.BLOCK * 2 + 777
        val samples = noisySine(frames, 2)
        val f = tmp("flac")
        FlacEncoder(f, 44100, 2).use { e ->
            e.write(samples, 1234)
            e.write(samples.copyOfRange(1234 * 2, samples.size), frames - 1234)
            e.finish()
        }
        val data = f.readBytes()
        assertEquals("fLaC", String(data, 0, 4))
        assertEquals(0x80, data[4].toInt() and 0xFF)
        val b = ByteBuffer.wrap(data, 8, 34)
        assertEquals(FlacEncoder.BLOCK, b.short.toInt() and 0xFFFF)
        assertEquals(FlacEncoder.BLOCK, b.short.toInt() and 0xFFFF)
        val packed = ByteBuffer.wrap(data, 8 + 10, 8).long
        assertEquals(44100L, packed ushr 44)
        assertEquals(1L, (packed ushr 41) and 7) // channels - 1
        assertEquals(15L, (packed ushr 36) and 31) // bits per sample - 1
        assertEquals(frames.toLong(), packed and 0xFFFFFFFFFL)
        val md5 = MessageDigest.getInstance("MD5")
        val raw = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        raw.asShortBuffer().put(samples)
        assertArrayEquals(md5.digest(raw.array()), data.copyOfRange(8 + 18, 8 + 34))
        assertEquals(0xFF, data[42].toInt() and 0xFF)
        assertEquals(0xF8, data[43].toInt() and 0xFF)
        assertTrue("compresses a noisy sine", data.size < 42 + frames * 4)
    }

    @Test
    fun flacHandlesSilenceFullScaleAndTinyInput() {
        for (frames in intArrayOf(1, 7, 100, 4096, 5000)) {
            val f = tmp("flac-edge")
            val loud = ShortArray(frames * 2) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
            FlacEncoder(f, 8000, 2).use { it.write(loud, frames); it.finish() }
            assertTrue(f.length() > 42)
            // STREAMINFO block sizes below 16 are invalid, even for a one-frame file
            val info = ByteBuffer.wrap(f.readBytes(), 8, 4)
            assertTrue((info.short.toInt() and 0xFFFF) >= 16)
            assertTrue((info.short.toInt() and 0xFFFF) >= 16)
            val silent = ShortArray(frames)
            FlacEncoder(f, 8000, 1).use { it.write(silent, frames); it.finish() }
            assertTrue(f.length() > 42)
        }
    }

    // ---- Peaks ----

    @Test
    fun peakColumnsMatchBruteForce() {
        val frames = 200_000
        val samples = noisySine(frames, 2, seed = 7)
        val clip = PcmClip(writePcm(samples), 44100, 2)
        val pyramid = PeakPyramid.build(clip)
        assertTrue(pyramid.levels.size > 1)
        PcmReader(clip).use { r ->
            for (fpp in doubleArrayOf(1.0, 3.5, 100.0, 255.0, 256.0, 1000.0, 5000.0)) {
                val width = 120
                val start = 12_345.0
                val cols = pyramid.columns(r, start, fpp, width)
                for (px in 0 until width) {
                    val f0 = start + px * fpp
                    val a = floor(f0).toInt()
                    val bEnd = max(a + 1, ceil(start + (px + 1) * fpp).toInt()).coerceAtMost(frames)
                    var mn = Short.MAX_VALUE
                    var mx = Short.MIN_VALUE
                    for (s in a * 2 until bEnd * 2) {
                        mn = minOf(mn, samples[s]); mx = maxOf(mx, samples[s])
                    }
                    if (fpp < PeakPyramid.BASE_BLOCK) {
                        assertEquals("min fpp=$fpp px=$px", mn, cols.mins[px])
                        assertEquals("max fpp=$fpp px=$px", mx, cols.maxs[px])
                    } else {
                        // coarse levels are block aligned, so they may only be wider than the exact range
                        assertTrue("min fpp=$fpp px=$px", cols.mins[px] <= mn)
                        assertTrue("max fpp=$fpp px=$px", cols.maxs[px] >= mx)
                    }
                }
            }
        }
    }

    @Test
    fun peakBuildHandlesPartialLastBlock() {
        val samples = ShortArray(300) { (it * 50).toShort() }
        val clip = PcmClip(writePcm(samples), 8000, 1)
        val p = PeakPyramid.build(clip)
        val base = p.levels.first()
        assertEquals(2, base.mins.size)
        assertEquals(0.toShort(), base.mins[0])
        assertEquals((255 * 50).toShort(), base.maxs[0])
        assertEquals((256 * 50).toShort(), base.mins[1])
        assertEquals((299 * 50).toShort(), base.maxs[1])
    }

    @Test
    fun rangeSourceStopsAtTheEnd() {
        val samples = ShortArray(100) { it.toShort() }
        val clip = PcmClip(writePcm(samples), 8000, 1)
        PcmReader(clip).use { r ->
            val s = RangeSource(r, 1, 10, 35)
            val buf = ShortArray(16)
            assertEquals(16, s.read(buf, 16))
            assertEquals(10.toShort(), buf[0])
            assertEquals(9, s.read(buf, 16))
            assertEquals(34.toShort(), buf[8])
            assertEquals(0, s.read(buf, 16))
        }
    }
}
