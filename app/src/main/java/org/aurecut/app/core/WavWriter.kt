package org.aurecut.app.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes 16-bit PCM WAV. Sizes in the header are filled in by [finish]. */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var bytes = ByteArray(0)
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(HEADER_BYTES))
    }

    fun write(buf: ShortArray, frames: Int) {
        val samples = frames * channels
        if (bytes.size < samples * 2) bytes = ByteArray(samples * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, samples)
        raf.write(bytes, 0, samples * 2)
        dataBytes += samples * 2L
    }

    fun finish() {
        val h = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt((36 + dataBytes).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(1) // PCM
        h.putShort(channels.toShort())
        h.putInt(sampleRate)
        h.putInt(sampleRate * channels * 2)
        h.putShort((channels * 2).toShort())
        h.putShort(16)
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array(), 0, HEADER_BYTES)
    }

    override fun close() {
        raf.close()
    }

    companion object {
        const val HEADER_BYTES = 44

        /** WAV stores sizes as unsigned 32-bit, so the data part is limited to just under 4 GiB. */
        const val MAX_DATA_BYTES = 0xFFFFFFFFL - 36

        fun fits(frames: Long, channels: Int): Boolean = frames * channels * 2L <= MAX_DATA_BYTES
    }
}
