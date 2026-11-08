package org.aurecut.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import org.aurecut.app.core.PcmClip
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

enum class RecFailure { PERMISSION, UNSUPPORTED, START, READ, DISK, EMPTY }

sealed interface RecState {
    data object Idle : RecState
    data class Active(val paused: Boolean, val elapsedMs: Long, val level: Float) : RecState
    /** [interrupted] is set when the recording ended early because of an error; the audio recorded so far is kept. */
    data class Finished(val clip: PcmClip, val interrupted: RecFailure? = null) : RecState
    data class Failed(val reason: RecFailure) : RecState
}

/**
 * Captures microphone audio into a raw PCM file on its own thread.
 * [onState] is called from that thread: about 20 times per second while recording, and once at the end
 * with [RecState.Finished] (also after an error, if some audio was captured) or [RecState.Failed].
 */
class Recorder(
    private val file: File,
    private val config: RecConfig,
    private val onState: (RecState) -> Unit,
) {
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    private var thread: Thread? = null

    /** Opens the microphone and starts the capture thread. Returns null on success, otherwise why it failed. */
    fun start(): RecFailure? {
        val mask = if (config.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(config.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return RecFailure.UNSUPPORTED
        val bufferBytes = maxOf(minBuffer * 2, config.sampleRate * config.channels * 2 / 5) // at least 200 ms
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(config.source.audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: SecurityException) {
            return RecFailure.PERMISSION
        } catch (e: Exception) {
            return RecFailure.START
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return RecFailure.START
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            return RecFailure.START
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release() // the microphone is taken by another app
            return RecFailure.START
        }
        thread = Thread({ capture(record) }, "AuReCut-recorder").also { it.start() }
        return null
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        stopRequested = true
    }

    private fun capture(record: AudioRecord) {
        val channels = config.channels
        val rate = config.sampleRate
        val shorts = ShortArray(rate / 20 * channels) // 50 ms per read
        val bytes = ByteArray(shorts.size * 2)
        var frames = 0L
        var failure: RecFailure? = null
        var level = 0f
        var lastEmit = 0L
        try {
            FileOutputStream(file).buffered(1 shl 16).use { out ->
                var wasPaused = false
                while (!stopRequested) {
                    if (paused) {
                        if (!wasPaused) {
                            safeStop(record)
                            wasPaused = true
                            onState(RecState.Active(true, frames * 1000 / rate, 0f))
                        }
                        Thread.sleep(30)
                        continue
                    }
                    if (wasPaused) {
                        record.startRecording()
                        wasPaused = false
                    }
                    val read = record.read(shorts, 0, shorts.size)
                    if (read < 0) {
                        failure = RecFailure.READ
                        break
                    }
                    val usable = read - read % channels
                    if (usable == 0) continue
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, usable)
                    out.write(bytes, 0, usable * 2)
                    frames += usable / channels
                    var peak = 0
                    for (i in 0 until usable) {
                        val a = abs(shorts[i].toInt())
                        if (a > peak) peak = a
                    }
                    level = peak / 32768f
                    val now = System.nanoTime()
                    if (now - lastEmit > EMIT_INTERVAL_NS) {
                        lastEmit = now
                        onState(RecState.Active(false, frames * 1000 / rate, level))
                    }
                }
            }
        } catch (e: IOException) {
            failure = RecFailure.DISK
        } catch (e: IllegalStateException) {
            failure = RecFailure.READ
        } catch (e: InterruptedException) {
            // stopping the process, treat like a normal stop
        } finally {
            safeStop(record)
            record.release()
        }
        onState(
            when {
                frames == 0L -> RecState.Failed(failure ?: RecFailure.EMPTY)
                else -> RecState.Finished(PcmClip(file, rate, channels), failure)
            },
        )
    }

    private fun safeStop(record: AudioRecord) {
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            // already stopped
        }
    }

    private companion object {
        const val EMIT_INTERVAL_NS = 50_000_000L
    }
}
