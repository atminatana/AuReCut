package org.aurecut.app.audio

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide handle on the running recording. The UI reads [state] and calls pause/resume/stop;
 * the foreground [RecordingService] owns the [Recorder] and publishes its progress here.
 */
object RecordingController {
    private val _state = MutableStateFlow<RecState>(RecState.Idle)
    val state: StateFlow<RecState> = _state.asStateFlow()

    @Volatile internal var recorder: Recorder? = null

    val isActive: Boolean get() = _state.value is RecState.Active

    internal fun publish(s: RecState) {
        _state.value = s
    }

    fun start(context: Context, config: RecConfig) {
        if (isActive) return
        _state.value = RecState.Active(paused = false, elapsedMs = 0, level = 0f)
        val intent = Intent(context, RecordingService::class.java)
            .putExtra(RecordingService.EXTRA_SOURCE, config.source.ordinal)
            .putExtra(RecordingService.EXTRA_RATE, config.sampleRate)
            .putExtra(RecordingService.EXTRA_CHANNELS, config.channels)
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            _state.value = RecState.Failed(RecFailure.START)
        }
    }

    fun pause() {
        recorder?.pause()
    }

    fun resume() {
        recorder?.resume()
    }

    fun stop() {
        recorder?.stop()
    }

    /** Called by the UI after it has taken a finished clip or shown an error. */
    fun acknowledge() {
        if (!isActive) _state.value = RecState.Idle
    }
}
