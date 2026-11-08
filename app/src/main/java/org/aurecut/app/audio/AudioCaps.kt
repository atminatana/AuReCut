package org.aurecut.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder

enum class RecSource(val audioSource: Int) {
    MIC(MediaRecorder.AudioSource.MIC),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
}

data class RecConfig(val source: RecSource, val sampleRate: Int, val channels: Int)

/** What this phone can actually record, checked once when the record screen opens. */
data class RecordingCaps(
    val sources: List<RecSource>,
    val monoRates: List<Int>,
    val stereoRates: List<Int>,
) {
    val stereoAvailable: Boolean get() = stereoRates.isNotEmpty()

    fun ratesFor(channels: Int): List<Int> = if (channels == 2) stereoRates else monoRates
}

object AudioCaps {
    val RECORD_RATES = listOf(16000, 22050, 44100, 48000)
    const val DEFAULT_RATE = 44100

    fun recording(context: Context): RecordingCaps {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = RecSource.values().filter { it != RecSource.UNPROCESSED || unprocessed }
        var mono = RECORD_RATES.filter { rateWorks(it, AudioFormat.CHANNEL_IN_MONO) }
        val stereo = RECORD_RATES.filter { rateWorks(it, AudioFormat.CHANNEL_IN_STEREO) }
        if (mono.isEmpty()) mono = listOf(DEFAULT_RATE) // every phone records mono at 44.1 kHz, even if the probe failed
        return RecordingCaps(sources, mono, stereo)
    }

    private fun rateWorks(rate: Int, channelMask: Int): Boolean =
        AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT) > 0

    fun hasEncoder(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    /** WAV and FLAC are always there; AAC and Opus depend on the phone's codecs. */
    fun exportFormats(): List<ExportFormat> = ExportFormat.values().filter { format ->
        when (format) {
            ExportFormat.WAV, ExportFormat.FLAC -> true
            ExportFormat.AAC -> hasEncoder(MediaFormat.MIMETYPE_AUDIO_AAC)
            ExportFormat.OPUS -> hasEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS)
        }
    }
}
