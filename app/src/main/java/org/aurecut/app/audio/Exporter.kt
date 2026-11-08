package org.aurecut.app.audio

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.DocumentsContract
import org.aurecut.app.core.FlacEncoder
import org.aurecut.app.core.GainSource
import org.aurecut.app.core.Normalizer
import org.aurecut.app.core.PcmReader
import org.aurecut.app.core.PcmSource
import org.aurecut.app.core.RangeSource
import org.aurecut.app.core.ResampleSource
import org.aurecut.app.core.WavWriter
import java.io.File
import java.io.IOException

/**
 * Cuts the selected range out of the work file, normalizes it if asked, encodes it into a temporary file
 * and copies that file into the document the user picked. Nothing is left behind on failure or cancel.
 */
object Exporter {

    fun export(
        context: Context,
        req: ExportRequest,
        dest: Uri,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): ExportResult {
        val clip = req.clip
        val channels = clip.channels
        val frames = req.endFrame - req.startFrame
        val tmp = WorkDir.newFile(context, "export", req.format.extension)
        try {
            if (frames <= 0) throw ExportException(ExportError.EMPTY_RANGE)
            val outRate = req.format.outputRate(clip.sampleRate)
            val outFrames = if (outRate == clip.sampleRate) frames else (frames * outRate + clip.sampleRate - 1) / clip.sampleRate
            if (req.format == ExportFormat.WAV && !WavWriter.fits(outFrames, channels)) {
                throw ExportException(ExportError.WAV_TOO_LARGE)
            }

            var gainDb: Double? = null
            var silent = false
            PcmReader(clip).use { reader ->
                var gain = 1.0
                if (req.normalize) {
                    val peak = Normalizer.peakAbs(reader, channels, req.startFrame, req.endFrame, isCancelled)
                    if (isCancelled()) throw OperationCancelled()
                    val g = Normalizer.gainFor(peak, req.targetDb)
                    if (g == null) {
                        silent = true
                    } else {
                        gain = g
                        gainDb = Normalizer.gainDb(g)
                    }
                }
                var source: PcmSource = RangeSource(reader, channels, req.startFrame, req.endFrame)
                if (gain != 1.0) source = GainSource(source, gain)
                if (outRate != clip.sampleRate) source = ResampleSource(source, clip.sampleRate, outRate)
                encode(req, tmp, source, outRate, outFrames, isCancelled) { done -> onProgress(0.95f * done) }
            }
            if (isCancelled()) throw OperationCancelled()

            copyToDocument(context, tmp, dest, onProgress, isCancelled)
            return ExportResult(
                sampleRate = outRate,
                resampledFrom = if (outRate != clip.sampleRate) clip.sampleRate else null,
                gainDb = gainDb,
                silentNormalizeSkipped = silent,
                bytes = tmp.length(),
            )
        } catch (e: Throwable) {
            deleteDocumentQuietly(context, dest)
            throw if (e is IOException) ExportException(ExportError.WRITE_FAILED, e) else e
        } finally {
            tmp.delete()
        }
    }

    private fun encode(
        req: ExportRequest,
        tmp: File,
        source: PcmSource,
        outRate: Int,
        outFrames: Long,
        isCancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ) {
        val channels = req.clip.channels
        val buf = ShortArray(CHUNK_FRAMES * channels)

        fun pump(write: (ShortArray, Int) -> Unit) {
            var done = 0L
            while (true) {
                if (isCancelled()) throw OperationCancelled()
                val n = source.read(buf, CHUNK_FRAMES)
                if (n == 0) break
                write(buf, n)
                done += n
                progress((done.toFloat() / outFrames).coerceIn(0f, 1f))
            }
        }

        when (req.format) {
            ExportFormat.WAV -> WavWriter(tmp, outRate, channels).use { w ->
                pump(w::write)
                w.finish()
            }
            ExportFormat.FLAC -> FlacEncoder(tmp, outRate, channels).use { e ->
                pump(e::write)
                e.finish()
            }
            ExportFormat.AAC, ExportFormat.OPUS -> {
                val aac = req.format == ExportFormat.AAC
                val encoder = MediaEncoder(
                    file = tmp,
                    mime = if (aac) MediaFormat.MIMETYPE_AUDIO_AAC else MediaFormat.MIMETYPE_AUDIO_OPUS,
                    sampleRate = outRate,
                    channels = channels,
                    bitrateKbps = req.bitrateKbps,
                    muxerFormat = if (aac) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 else MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
                )
                try {
                    encoder.use { enc ->
                        pump(enc::write)
                        enc.finish()
                    }
                } catch (e: RuntimeException) {
                    throw ExportException(ExportError.ENCODER_FAILED, e)
                }
            }
        }
    }

    private fun copyToDocument(
        context: Context,
        src: File,
        dest: Uri,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val out = context.contentResolver.openOutputStream(dest, "wt") ?: throw ExportException(ExportError.WRITE_FAILED)
        out.use { stream ->
            src.inputStream().use { input ->
                val total = src.length().coerceAtLeast(1)
                val buf = ByteArray(1 shl 16)
                var copied = 0L
                while (true) {
                    if (isCancelled()) throw OperationCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    stream.write(buf, 0, n)
                    copied += n
                    onProgress(0.95f + 0.05f * copied / total)
                }
            }
            stream.flush()
        }
        onProgress(1f)
    }

    private fun deleteDocumentQuietly(context: Context, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            // not every provider lets us delete, an empty file is harmless
        }
    }

    private const val CHUNK_FRAMES = 8192
}
