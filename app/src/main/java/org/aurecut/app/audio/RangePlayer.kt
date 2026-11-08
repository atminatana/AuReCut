package org.aurecut.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.aurecut.app.core.GainSource
import org.aurecut.app.core.Normalizer
import org.aurecut.app.core.PcmClip
import org.aurecut.app.core.PcmReader

/** Plays a frame range of a clip through AudioTrack and reports the playback position. */
class RangePlayer(private val clip: PcmClip) {

    private class Session {
        @Volatile var stop = false
        @Volatile var track: AudioTrack? = null
    }

    @Volatile private var current: Session? = null

    /**
     * Starts playing [startFrame, endFrame). With [normalizeToDb] the range is played with the same gain
     * the export would apply. Callbacks come from the player thread and only for the latest session.
     */
    fun play(
        startFrame: Long,
        endFrame: Long,
        normalizeToDb: Double?,
        onPosition: (Long) -> Unit,
        onEnded: () -> Unit,
    ) {
        stop()
        val session = Session()
        current = session
        Thread({ run(session, startFrame, endFrame, normalizeToDb, onPosition, onEnded) }, "AuReCut-player").start()
    }

    fun stop() {
        val s = current ?: return
        s.stop = true
        current = null
        try {
            s.track?.pause()
            s.track?.flush()
        } catch (e: IllegalStateException) {
            // already released
        }
    }

    private fun run(
        s: Session,
        start: Long,
        end: Long,
        normalizeToDb: Double?,
        onPosition: (Long) -> Unit,
        onEnded: () -> Unit,
    ) {
        val ch = clip.channels
        val rate = clip.sampleRate
        var track: AudioTrack? = null
        try {
            PcmReader(clip).use { reader ->
                var gain = 1.0
                if (normalizeToDb != null) {
                    val peak = Normalizer.peakAbs(reader, ch, start, end) { s.stop }
                    gain = Normalizer.gainFor(peak, normalizeToDb) ?: 1.0
                }
                if (s.stop) return

                val mask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val minBuffer = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, rate * ch * 2 / 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                s.track = t
                t.play()

                val chunkFrames = rate / 20
                val shorts = ShortArray(chunkFrames * ch)
                var pos = start
                var written = 0L

                fun reportHead() {
                    val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (current === s) onPosition(start + minOf(head, written))
                }

                while (pos < end && !s.stop) {
                    val n = reader.read(pos, minOf(chunkFrames.toLong(), end - pos).toInt(), shorts)
                    if (n <= 0) break
                    if (gain != 1.0) GainSource.applyGain(shorts, n * ch, gain)
                    var off = 0
                    while (off < n * ch && !s.stop) {
                        val w = t.write(shorts, off, n * ch - off)
                        if (w < 0) {
                            s.stop = true
                            break
                        }
                        off += w
                    }
                    pos += n
                    written += n
                    reportHead()
                }
                // let the buffered audio finish before closing the track
                while (!s.stop && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written) {
                    Thread.sleep(20)
                    reportHead()
                }
            }
        } catch (e: Exception) {
            // playback problems (device busy, track creation failed) just end playback
        } finally {
            try {
                track?.stop()
            } catch (e: IllegalStateException) {
                // never started
            }
            track?.release()
            if (current === s) {
                current = null
                onEnded()
            }
        }
    }
}
