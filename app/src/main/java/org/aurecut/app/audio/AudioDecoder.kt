package org.aurecut.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import org.aurecut.app.core.PcmClip
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class DecodeError { NO_AUDIO, UNSUPPORTED, EMPTY, IO }

class DecodeException(val error: DecodeError, cause: Throwable? = null) : Exception(error.name, cause)

/** Decodes any audio file Android can play (WAV, FLAC, AAC/M4A, Opus/OGG, MP3, ...) into a raw PCM work file. */
object AudioDecoder {

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (e: Exception) {
            // fall through to the last path segment
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
    }

    /** Throws [DecodeException], or [OperationCancelled] when [isCancelled] turns true. Deletes [out] on any failure. */
    fun decode(
        context: Context,
        uri: Uri,
        out: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): PcmClip {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var sink: PcmSink? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: IOException) {
                throw DecodeException(DecodeError.IO, e)
            } catch (e: RuntimeException) {
                throw DecodeException(DecodeError.UNSUPPORTED, e)
            }

            var trackIndex = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    inFormat = f
                    break
                }
            }
            if (trackIndex < 0 || inFormat == null) throw DecodeException(DecodeError.NO_AUDIO)
            extractor.selectTrack(trackIndex)

            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = intOr(inFormat, MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = intOr(inFormat, MediaFormat.KEY_CHANNEL_COUNT, 0)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            try {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(inFormat, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                throw DecodeException(DecodeError.UNSUPPORTED, e)
            }
            val dec = codec!!

            var writer: PcmSink? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastProgress = 0f

            while (!outputDone) {
                if (isCancelled()) throw OperationCancelled()

                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        rate = intOr(f, MediaFormat.KEY_SAMPLE_RATE, rate)
                        channels = intOr(f, MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = intOr(f, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            if (rate <= 0 || channels <= 0) throw DecodeException(DecodeError.UNSUPPORTED)
                            if (writer == null) {
                                writer = PcmSink(out, channels, minOf(channels, 2)).also { sink = it }
                            }
                            val ob = dec.getOutputBuffer(outIdx)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            writer.write(ob, encoding)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0) {
                            val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                            if (p - lastProgress >= 0.01f) {
                                lastProgress = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }

            val s = sink ?: throw DecodeException(DecodeError.EMPTY)
            s.close()
            sink = null
            if (s.frames == 0L) throw DecodeException(DecodeError.EMPTY)
            onProgress(1f)
            return PcmClip(out, rate, minOf(channels, 2))
        } catch (e: IOException) {
            out.delete()
            throw DecodeException(DecodeError.IO, e)
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            try {
                sink?.close()
            } catch (e: IOException) {
                // the file is being thrown away anyway
            }
            try {
                codec?.stop()
            } catch (e: IllegalStateException) {
                // never started or already stopped
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun intOr(f: MediaFormat, key: String, default: Int): Int =
        if (f.containsKey(key)) f.getInteger(key) else default

    /** Converts decoder output to 16-bit little-endian PCM with at most two channels and appends it to a file. */
    private class PcmSink(file: File, private val inChannels: Int, private val outChannels: Int) {
        private val stream = FileOutputStream(file).buffered(1 shl 16)
        private var shorts = ShortArray(1 shl 15)
        private var bytes = ByteArray(1 shl 16)
        var frames = 0L
            private set

        fun write(buf: ByteBuffer, encoding: Int) {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val samples: Int
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> {
                    samples = buf.remaining() / 2
                    ensureShorts(samples)
                    buf.asShortBuffer().get(shorts, 0, samples)
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    samples = buf.remaining() / 4
                    ensureShorts(samples)
                    val fb = buf.asFloatBuffer()
                    for (i in 0 until samples) {
                        val v = Math.round(fb.get() * 32768f)
                        shorts[i] = if (v > Short.MAX_VALUE) Short.MAX_VALUE else if (v < Short.MIN_VALUE) Short.MIN_VALUE else v.toShort()
                    }
                }
                AudioFormat.ENCODING_PCM_8BIT -> {
                    samples = buf.remaining()
                    ensureShorts(samples)
                    for (i in 0 until samples) shorts[i] = (((buf.get().toInt() and 0xFF) - 128) shl 8).toShort()
                }
                else -> throw DecodeException(DecodeError.UNSUPPORTED)
            }
            val frameCount = samples / inChannels
            if (inChannels != outChannels) {
                // more than two channels: keep the first two (front left and right)
                for (f in 0 until frameCount) for (c in 0 until outChannels) shorts[f * outChannels + c] = shorts[f * inChannels + c]
            }
            val outSamples = frameCount * outChannels
            if (bytes.size < outSamples * 2) bytes = ByteArray(outSamples * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, outSamples)
            stream.write(bytes, 0, outSamples * 2)
            frames += frameCount
        }

        private fun ensureShorts(n: Int) {
            if (shorts.size < n) shorts = ShortArray(n)
        }

        fun close() {
            stream.close()
        }
    }

    private const val TIMEOUT_US = 10_000L
}
