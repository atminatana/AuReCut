package org.aurecut.app.audio

import org.aurecut.app.core.PcmClip

/** Output formats offered in the save sheet. WAV and FLAC are written by the app itself. */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val mime: String,
    val bitrates: List<Int>,
    val defaultBitrate: Int,
) {
    WAV("WAV", "wav", "audio/x-wav", emptyList(), 0),
    FLAC("FLAC", "flac", "audio/flac", emptyList(), 0),
    AAC("AAC (M4A)", "m4a", "audio/mp4", listOf(64, 96, 128, 192, 256), 128),
    OPUS("Opus (OGG)", "ogg", "audio/ogg", listOf(24, 32, 64, 96, 128, 192), 96);

    val hasBitrate: Boolean get() = bitrates.isNotEmpty()

    /** Sample rate of the written file for a clip recorded at [clipRate]. Only Opus has to change it. */
    fun outputRate(clipRate: Int): Int =
        if (this == OPUS && clipRate !in OPUS_RATES) OPUS_FALLBACK_RATE else clipRate

    companion object {
        /** Rates the Opus codec accepts. */
        val OPUS_RATES = setOf(8000, 12000, 16000, 24000, 48000)
        const val OPUS_FALLBACK_RATE = 48000
    }
}

/** Everything the exporter needs to know about one save operation. */
data class ExportRequest(
    val clip: PcmClip,
    val startFrame: Long,
    val endFrame: Long,
    val format: ExportFormat,
    val bitrateKbps: Int,
    val normalize: Boolean,
    val targetDb: Double,
)

data class ExportResult(
    val sampleRate: Int,
    val resampledFrom: Int?,
    /** Applied gain in dB, null when normalization was off or the range was silent. */
    val gainDb: Double?,
    val silentNormalizeSkipped: Boolean,
    val bytes: Long,
)

enum class ExportError { EMPTY_RANGE, WAV_TOO_LARGE, ENCODER_UNAVAILABLE, ENCODER_FAILED, WRITE_FAILED }

class ExportException(val error: ExportError, cause: Throwable? = null) : Exception(error.name, cause)

/** Thrown when the user cancels a long operation. */
class OperationCancelled : Exception()
