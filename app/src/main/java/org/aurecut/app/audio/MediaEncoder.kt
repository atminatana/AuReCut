package org.aurecut.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * AAC (in .m4a) and Opus (in .ogg) encoding through the phone's MediaCodec and MediaMuxer.
 * Input is interleaved 16-bit PCM at the exact rate the codec was configured with.
 */
internal class MediaEncoder(
    file: File,
    mime: String,
    private val sampleRate: Int,
    private val channels: Int,
    bitrateKbps: Int,
    muxerFormat: Int,
) : Closeable {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var muxerStopped = false
    private var framesIn = 0L

    init {
        val c = try {
            MediaCodec.createEncoderByType(mime)
        } catch (e: IOException) {
            throw ExportException(ExportError.ENCODER_UNAVAILABLE, e)
        } catch (e: RuntimeException) {
            throw ExportException(ExportError.ENCODER_UNAVAILABLE, e)
        }
        try {
            val caps = c.codecInfo.getCapabilitiesForType(mime).audioCapabilities
            if (caps != null && (!caps.isSampleRateSupported(sampleRate) || channels > caps.maxInputChannelCount)) {
                throw ExportException(ExportError.ENCODER_UNAVAILABLE)
            }
            val bitrate = caps?.bitrateRange?.clamp(bitrateKbps * 1000) ?: (bitrateKbps * 1000)
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            muxer = MediaMuxer(file.path, muxerFormat)
        } catch (e: ExportException) {
            c.release()
            throw e
        } catch (e: Exception) {
            c.release()
            throw ExportException(ExportError.ENCODER_UNAVAILABLE, e)
        }
        codec = c
    }

    /** Feeds [frames] interleaved frames; may block briefly while the codec drains. */
    fun write(buf: ShortArray, frames: Int) {
        var off = 0
        var stalls = 0
        while (off < frames) {
            val idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx < 0) {
                drain(0)
                if (++stalls > MAX_STALLS) throw ExportException(ExportError.ENCODER_FAILED)
                continue
            }
            stalls = 0
            val bb = codec.getInputBuffer(idx)!!
            bb.clear()
            val n = minOf(frames - off, bb.capacity() / (2 * channels))
            bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, off * channels, n * channels)
            codec.queueInputBuffer(idx, 0, n * 2 * channels, framesIn * 1_000_000L / sampleRate, 0)
            framesIn += n
            off += n
            drain(0)
        }
    }

    /** Sends end-of-stream, drains the codec and closes the container. */
    fun finish() {
        var stalls = 0
        while (true) {
            val idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, framesIn * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(0)
            if (++stalls > MAX_STALLS) throw ExportException(ExportError.ENCODER_FAILED)
        }
        var done = false
        for (i in 0 until MAX_STALLS) {
            if (drain(TIMEOUT_US)) {
                done = true
                break
            }
        }
        if (!done || !muxerStarted) throw ExportException(ExportError.ENCODER_FAILED)
        muxer.stop()
        muxerStopped = true
    }

    /** Moves every available encoded buffer to the muxer. Returns true once end-of-stream was seen. */
    private fun drain(timeoutUs: Long): Boolean {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    override fun close() {
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            // already stopped
        }
        codec.release()
        try {
            if (muxerStarted && !muxerStopped) muxer.stop()
        } catch (e: IllegalStateException) {
            // the output is already unusable
        }
        muxer.release()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_STALLS = 1000 // about 10 seconds without progress
    }
}
